package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.CertificateQuarantine;
import org.eclipse.milo.opcua.stack.core.security.CertificateValidator;
import org.eclipse.milo.opcua.stack.core.security.DefaultClientCertificateValidator;
import org.eclipse.milo.opcua.stack.core.security.MemoryTrustListManager;
import org.eclipse.milo.opcua.stack.core.security.TrustListManager;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.util.validation.ValidationCheck;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

/** 人工预置信任，或首次使用时固定服务端证书；两者均保留 Milo 完整属性校验。 */
final class ServerTrust {
    private ServerTrust() {}

    static CertificateValidator create(
            ClientSettings settings,
            EndpointDescription endpoint,
            TrustListManager trust,
            CertificateQuarantine quarantine)
            throws Exception {
        if (settings.serverTrust().equals("strict")) {
            return new DefaultClientCertificateValidator(
                    trust, ValidationCheck.ALL_OPTIONAL_CHECKS, quarantine);
        }
        byte[] encoded =
                endpoint.getServerCertificate() == null
                        ? null
                        : endpoint.getServerCertificate().bytes();
        if (encoded == null || encoded.length == 0) {
            throw new IllegalArgumentException("首次信任需要发现端点提供服务器证书");
        }
        X509Certificate selected;
        try (var in = new ByteArrayInputStream(encoded)) {
            selected =
                    (X509Certificate)
                            CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        String fingerprint = LocalPki.fingerprint(selected.getEncoded());
        Path pin = pinPath(settings);
        verifyExistingPin(pin, fingerprint);

        // 首次接受仅限选定端点的这张证书，不向原人工信任库批量添加发现结果。
        var temporaryTrust = new MemoryTrustListManager();
        temporaryTrust.addTrustedCertificate(selected);
        temporaryTrust.setIssuerCertificates(trust.getIssuerCertificates());
        temporaryTrust.setIssuerCrls(trust.getIssuerCrls());
        temporaryTrust.setTrustedCrls(trust.getTrustedCrls());
        var validator =
                new DefaultClientCertificateValidator(
                        temporaryTrust, ValidationCheck.ALL_OPTIONAL_CHECKS, quarantine);
        return (chain, applicationUri, hostnames) -> {
            if (chain == null || chain.isEmpty())
                throw new UaException(StatusCodes.Bad_CertificateInvalid);
            try {
                if (!fingerprint.equals(LocalPki.fingerprint(chain.getFirst().getEncoded()))) {
                    throw new UaException(StatusCodes.Bad_CertificateInvalid, "会话证书与选定端点不一致");
                }
                validator.validateCertificateChain(chain, applicationUri, hostnames);
                recordOrVerify(pin, chain.getFirst(), fingerprint);
            } catch (UaException e) {
                throw e;
            } catch (Exception e) {
                throw new UaException(StatusCodes.Bad_SecurityChecksFailed, "无法保存或核对服务器证书记录", e);
            }
        };
    }

    static Path pinPath(ClientSettings settings) throws Exception {
        String name =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                settings.endpoint()
                                                        .toString()
                                                        .getBytes(StandardCharsets.UTF_8)));
        return settings.pkiDirectory().resolve("server-pins").resolve(name + ".der");
    }

    private static void verifyExistingPin(Path pin, String fingerprint) throws Exception {
        if (Files.exists(pin)
                && !fingerprint.equals(LocalPki.fingerprint(Files.readAllBytes(pin)))) {
            throw new UaException(
                    StatusCodes.Bad_CertificateUntrusted, "该端点的服务器证书已变化，未自动替换已记录身份；请核对服务器换证情况");
        }
    }

    private static void recordOrVerify(Path pin, X509Certificate certificate, String fingerprint)
            throws Exception {
        Files.createDirectories(pin.getParent());
        // 多进程首次连接必须核对同一份记录，禁止互相覆盖。
        Path lock = pin.resolveSibling(pin.getFileName() + ".lock");
        try (var channel =
                        FileChannel.open(
                                lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var heldLock = channel.tryLock()) {
            if (heldLock == null) throw new IOException("服务器证书记录正在被其他进程更新，请稍后重试");
            verifyExistingPin(pin, fingerprint);
            if (Files.exists(pin)) return;
            Path temporary = Files.createTempFile(pin.getParent(), "server-", ".tmp");
            try {
                Files.write(temporary, certificate.getEncoded());
                Files.move(temporary, pin, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
            System.out.println("已自动记录首次服务器证书，SHA-256：" + fingerprint);
        }
    }
}

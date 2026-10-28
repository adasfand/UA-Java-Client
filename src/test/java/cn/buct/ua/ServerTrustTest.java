package cn.buct.ua;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Comparator;
import java.util.List;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.MemoryCertificateQuarantine;
import org.eclipse.milo.opcua.stack.core.security.MemoryTrustListManager;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte;

class ServerTrustTest {
    private static final String SERVER_URI = "urn:test:server";
    private Path directory;

    @BeforeEach void prepare() throws Exception {
        directory = Files.createTempDirectory(Files.createDirectories(Path.of(".cache/tmp").toAbsolutePath()), "server-trust-");
    }

    @AfterEach void clean() throws Exception {
        Path root = Path.of(".cache/tmp").toAbsolutePath().normalize();
        if (!directory.normalize().startsWith(root)) throw new IllegalStateException("测试路径越界");
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private ClientSettings settings(String mode, int port) throws Exception {
        Path config = directory.resolve("client.properties");
        Files.writeString(config, """
                endpoint.url=opc.tcp://localhost:%d
                security.policy=Basic256Sha256
                security.mode=SignAndEncrypt
                identity.type=anonymous
                application.uri=urn:test:client
                application.name=测试客户端
                pki.directory=pki
                nodes.file=nodes.properties
                %s
                """.formatted(port, mode == null ? "" : "security.serverTrust=" + mode));
        return ClientSettings.load(config);
    }

    private X509Certificate certificate() throws Exception {
        return new SelfSignedCertificateBuilder(SelfSignedCertificateGenerator.generateRsaKeyPair(2048))
                .setCommonName("Test Server").setApplicationUri(SERVER_URI).addDnsName("localhost").build();
    }

    private EndpointDescription endpoint(ClientSettings settings, X509Certificate cert) throws Exception {
        return new EndpointDescription(settings.endpoint().toString(), null, ByteString.of(cert.getEncoded()),
                MessageSecurityMode.SignAndEncrypt, SecurityPolicy.Basic256Sha256.getUri(), null, null, ubyte(0));
    }

    @Test void firstUseRecordsValidatedIdentityAndReusesItAcrossValidatorsWithoutManualImport() throws Exception {
        var settings = settings("first-use", 4840);
        var cert = certificate();
        var trust = new MemoryTrustListManager();
        var validator = ServerTrust.create(settings, endpoint(settings, cert), trust, new MemoryCertificateQuarantine());
        Path pin = ServerTrust.pinPath(settings);
        assertFalse(Files.exists(pin), "发现端点本身不能记录信任");
        validator.validateCertificateChain(List.of(cert), SERVER_URI, new String[]{"localhost"});
        assertArrayEquals(cert.getEncoded(), Files.readAllBytes(pin));
        assertTrue(trust.getTrustedCertificates().isEmpty(), "不得改动原人工信任库");
        ServerTrust.create(settings, endpoint(settings, cert), trust, new MemoryCertificateQuarantine())
                .validateCertificateChain(List.of(cert), SERVER_URI, new String[]{"localhost"});
    }

    @Test void strictRemainsDefaultAndDoesNotAutomaticallyUseFirstUsePins() throws Exception {
        var cert = certificate();
        var auto = settings("first-use", 4840);
        ServerTrust.create(auto, endpoint(auto, cert), new MemoryTrustListManager(), new MemoryCertificateQuarantine())
                .validateCertificateChain(List.of(cert), SERVER_URI, new String[]{"localhost"});
        var strict = settings(null, 4840);
        assertEquals("strict", strict.serverTrust());
        var trust = new MemoryTrustListManager();
        var validator = ServerTrust.create(strict, endpoint(strict, cert), trust, new MemoryCertificateQuarantine());
        assertThrows(UaException.class, () -> validator.validateCertificateChain(List.of(cert), SERVER_URI, new String[]{"localhost"}));
        trust.addTrustedCertificate(cert);
        assertDoesNotThrow(() -> validator.validateCertificateChain(List.of(cert), SERVER_URI, new String[]{"localhost"}));
    }

    @Test void wrongHostnameIsRejectedBeforeRecording() throws Exception {
        var settings = settings("first-use", 4840);
        var cert = certificate();
        var validator = ServerTrust.create(settings, endpoint(settings, cert), new MemoryTrustListManager(), new MemoryCertificateQuarantine());
        assertThrows(UaException.class, () -> validator.validateCertificateChain(List.of(cert), SERVER_URI, new String[]{"wrong.example"}));
        assertFalse(Files.exists(ServerTrust.pinPath(settings)));
    }

    @Test void wrongApplicationUriIsRejectedBeforeRecording() throws Exception {
        var settings = settings("first-use", 4840);
        var cert = certificate();
        var validator = ServerTrust.create(settings, endpoint(settings, cert), new MemoryTrustListManager(), new MemoryCertificateQuarantine());
        assertThrows(UaException.class, () -> validator.validateCertificateChain(List.of(cert), "urn:wrong", new String[]{"localhost"}));
        assertFalse(Files.exists(ServerTrust.pinPath(settings)));
    }

    @Test void changedCertificateCannotReplaceRecordedIdentityOrPassEvenIfManuallyTrusted() throws Exception {
        var settings = settings("first-use", 4840);
        var first = certificate();
        var changed = certificate();
        var trust = new MemoryTrustListManager();
        trust.addTrustedCertificate(changed);
        var validator = ServerTrust.create(settings, endpoint(settings, first), trust, new MemoryCertificateQuarantine());
        validator.validateCertificateChain(List.of(first), SERVER_URI, new String[]{"localhost"});
        assertThrows(UaException.class, () -> validator.validateCertificateChain(List.of(changed), SERVER_URI, new String[]{"localhost"}));
        assertThrows(UaException.class, () -> ServerTrust.create(settings, endpoint(settings, changed), trust, new MemoryCertificateQuarantine()));
        assertArrayEquals(first.getEncoded(), Files.readAllBytes(ServerTrust.pinPath(settings)));
    }

    @Test void sessionMustPresentTheCertificateSelectedAtDiscovery() throws Exception {
        var settings = settings("first-use", 4840);
        var selected = certificate();
        var other = certificate();
        var validator = ServerTrust.create(settings, endpoint(settings, selected), new MemoryTrustListManager(), new MemoryCertificateQuarantine());
        assertThrows(UaException.class, () -> validator.validateCertificateChain(List.of(other), SERVER_URI, new String[]{"localhost"}));
        assertFalse(Files.exists(ServerTrust.pinPath(settings)));
    }

    @Test void differentEndpointsUseIndependentRecordsWithinSamePkiDirectory() throws Exception {
        var first = settings("first-use", 4840);
        var second = settings("first-use", 4841);
        assertNotEquals(ServerTrust.pinPath(first), ServerTrust.pinPath(second));
        for (var settings : List.of(first, second)) {
            var cert = certificate();
            ServerTrust.create(settings, endpoint(settings, cert), new MemoryTrustListManager(), new MemoryCertificateQuarantine())
                    .validateCertificateChain(List.of(cert), SERVER_URI, new String[]{"localhost"});
            assertArrayEquals(cert.getEncoded(), Files.readAllBytes(ServerTrust.pinPath(settings)));
        }
    }

    @Test void unrecognizedModeAndMissingDiscoveryCertificateFail() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> settings("trust-all", 4840));
        var settings = settings("first-use", 4840);
        var empty = new EndpointDescription(settings.endpoint().toString(), null, ByteString.NULL_VALUE,
                MessageSecurityMode.SignAndEncrypt, SecurityPolicy.Basic256Sha256.getUri(), null, null, ubyte(0));
        assertThrows(IllegalArgumentException.class, () -> ServerTrust.create(settings, empty, new MemoryTrustListManager(), new MemoryCertificateQuarantine()));
        assertFalse(Files.exists(ServerTrust.pinPath(settings)));
    }
}

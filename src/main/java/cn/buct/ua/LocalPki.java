package cn.buct.ua;

import java.io.Console;
import java.io.InputStream;
import java.io.OutputStream;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import java.awt.GridLayout;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.milo.opcua.stack.core.security.FileBasedTrustListManager;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator;

/** 持久化应用身份；只按人工核对的 SHA-256 导入服务端公开证书。 */
public final class LocalPki {
    private static final String ALIAS = "ua-client";
    private LocalPki() {}

    public record Identity(KeyPair keyPair, X509Certificate certificate, X509Certificate[] chain) {}

    public static void initialize(ClientSettings settings) throws Exception {
        Path directory = settings.pkiDirectory();
        Path target = directory.resolve("client.p12");
        Files.createDirectories(directory);
        if (Files.exists(target)) throw new IllegalArgumentException("客户端证书已存在，不覆盖：" + target);
        char[] password = secret(settings.pkiPasswordEnv(), "设置本机证书库密码");
        Path temporary = null;
        try {
            KeyPair pair = SelfSignedCertificateGenerator.generateRsaKeyPair(2048);
            X509Certificate certificate = new SelfSignedCertificateBuilder(pair)
                    .setCommonName(settings.applicationName()).setOrganization("UA Development")
                    .setApplicationUri(settings.applicationUri()).addDnsName("localhost")
                    .addIpAddress("127.0.0.1")
                    .setSignatureAlgorithm(SelfSignedCertificateBuilder.SA_SHA256_RSA).build();
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, password);
            store.setKeyEntry(ALIAS, pair.getPrivate(), password, new X509Certificate[]{certificate});
            temporary = Files.createTempFile(directory, "identity-", ".tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) { store.store(out, password); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            Files.write(directory.resolve("client.der"), certificate.getEncoded());
            System.out.println("客户端身份已保存：" + target);
            System.out.println("请将 client.der 导入服务器的受信任客户端；不要发送 p12 私钥文件。");
        } finally {
            Arrays.fill(password, '\0');
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    public static Identity load(ClientSettings settings) throws Exception {
        char[] password = secret(settings.pkiPasswordEnv(), "输入本机证书库密码");
        try (InputStream in = Files.newInputStream(settings.pkiDirectory().resolve("client.p12"))) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, password);
            X509Certificate certificate = (X509Certificate) store.getCertificate(ALIAS);
            if (certificate == null) throw new IllegalArgumentException("证书库缺少客户端身份");
            certificate.checkValidity();
            var alternatives = certificate.getSubjectAlternativeNames();
            if (alternatives == null || alternatives.stream().noneMatch(v -> Integer.valueOf(6).equals(v.get(0))
                    && settings.applicationUri().equals(v.get(1)))) {
                throw new IllegalArgumentException("配置 application.uri 与已保存客户端证书不一致；请使用匹配配置");
            }
            var chain = Arrays.stream(store.getCertificateChain(ALIAS)).map(c -> (X509Certificate) c)
                    .toArray(X509Certificate[]::new);
            return new Identity(new KeyPair(certificate.getPublicKey(), (PrivateKey) store.getKey(ALIAS, password)), certificate, chain);
        } finally { Arrays.fill(password, '\0'); }
    }

    public static void trustServer(ClientSettings settings, Path certificateFile, String expected) throws Exception {
        X509Certificate certificate;
        try (InputStream in = Files.newInputStream(certificateFile)) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        certificate.checkValidity();
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        String normalized = expected.replace(":", "").replace(" ", "");
        if (!normalized.matches("(?i)[0-9a-f]{64}") || !fingerprint.equalsIgnoreCase(normalized)) {
            throw new IllegalArgumentException("证书 SHA-256 与人工核对值不符，未导入");
        }
        try (var trust = FileBasedTrustListManager.createAndInitialize(settings.pkiDirectory().resolve("trust-store"))) {
            trust.addTrustedCertificate(certificate);
        }
        System.out.println("已导入人工确认的服务器证书，SHA-256：" + fingerprint);
    }

    public static char[] secret(String environmentName, String prompt) {
        String value = System.getenv(environmentName);
        if (value != null && !value.isEmpty()) return value.toCharArray();
        Console console = System.console();
        char[] password = console != null ? console.readPassword("%s：", prompt) : desktopSecret(prompt, environmentName);
        if (password == null || password.length == 0) throw new IllegalArgumentException("密码不能为空");
        return password;
    }

    /** IDEA 控制台不支持 Console.readPassword 时，由操作者在本机窗口隐藏输入。 */
    private static char[] desktopSecret(String prompt, String environmentName) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalArgumentException("缺少本机凭据：请配置环境变量 " + environmentName + "（不要写入仓库）");
        }
        AtomicReference<char[]> result = new AtomicReference<>();
        Runnable show = () -> {
            JPasswordField input = new JPasswordField(28);
            JPanel panel = new JPanel(new GridLayout(0, 1, 0, 8));
            panel.add(new JLabel(prompt));
            panel.add(input);
            panel.add(new JLabel("仅本次进程使用，不保存到文件或运行配置。"));
            int choice = JOptionPane.showConfirmDialog(null, panel, "OPC UA 本机凭据",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice == JOptionPane.OK_OPTION) result.set(input.getPassword());
            input.setText("");
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) show.run();
            else SwingUtilities.invokeAndWait(show);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("本机密码输入已中断", e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException("无法打开本机密码输入窗口", e.getCause());
        }
        if (result.get() == null) throw new IllegalArgumentException("操作者取消了本次凭据输入");
        return result.get();
    }
}

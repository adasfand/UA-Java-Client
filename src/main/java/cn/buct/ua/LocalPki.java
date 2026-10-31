package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.security.FileBasedTrustListManager;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator;

import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Window;
import java.io.ByteArrayInputStream;
import java.io.Console;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Period;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** 持久化应用身份；只按人工核对的 SHA-256 导入服务端公开证书。 */
public final class LocalPki {
    private LocalPki() {}

    public record Identity(KeyPair keyPair, X509Certificate certificate, X509Certificate[] chain) {}

    public static void initialize(ClientSettings settings) throws Exception {
        initialize(settings, LocalPki::secret);
    }

    public static void initialize(
            ClientSettings settings, BiFunction<String, String, char[]> credentials)
            throws Exception {
        Path directory = settings.pkiDirectory();
        Path target = directory.resolve(settings.pki().fileName());
        if (Files.exists(target)) throw new IllegalArgumentException("客户端证书已存在，不覆盖：" + target);
        char[] password = requestSecret(credentials, settings.pkiPasswordEnv(), "设置本机证书库密码");
        Path temporary = null;
        Path publicTemporary = null;
        boolean identityWritten = false;
        boolean completed = false;
        try {
            Path publicTarget = directory.resolve("client.der");
            if (Files.exists(publicTarget) && !Files.isRegularFile(publicTarget))
                throw new IllegalArgumentException("公开证书位置不是普通文件，请检查 client.der 路径。");
            Files.createDirectories(directory);
            KeyPair pair = SelfSignedCertificateGenerator.generateRsaKeyPair(2048);
            var builder =
                    new SelfSignedCertificateBuilder(pair)
                            .setCommonName(settings.pki().commonName())
                            .setOrganization(settings.pki().organization())
                            .setApplicationUri(settings.applicationUri())
                            .setValidityPeriod(Period.ofDays(settings.pki().validityDays()))
                            .setSignatureAlgorithm(SelfSignedCertificateBuilder.SA_SHA256_RSA);
            settings.pki().dnsNames().forEach(builder::addDnsName);
            settings.pki().ipAddresses().forEach(builder::addIpAddress);
            X509Certificate certificate = builder.build();
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, password);
            store.setKeyEntry(
                    settings.pki().alias(),
                    pair.getPrivate(),
                    password,
                    new X509Certificate[] {certificate});
            temporary = Files.createTempFile(directory, "identity-", ".tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                store.store(out, password);
            }
            publicTemporary = Files.createTempFile(directory, "certificate-", ".tmp");
            Files.write(publicTemporary, certificate.getEncoded());
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            identityWritten = true;
            Files.move(
                    publicTemporary,
                    publicTarget,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            completed = true;
            System.out.println("客户端身份已保存：" + target);
            System.out.println("请将 client.der 导入服务器的受信任客户端；不要发送 p12 私钥文件。");
        } finally {
            Arrays.fill(password, '\0');
            if (identityWritten && !completed) Files.deleteIfExists(target);
            if (temporary != null) Files.deleteIfExists(temporary);
            if (publicTemporary != null) Files.deleteIfExists(publicTemporary);
        }
    }

    /** 凭据提供器返回的 char[] 由本方法接管并清零，适合桌面表单或本机凭据来源。 */
    public static Identity load(
            ClientSettings settings, BiFunction<String, String, char[]> credentials)
            throws Exception {
        char[] password = requestSecret(credentials, settings.pkiPasswordEnv(), "输入本机证书库密码");
        try (InputStream in =
                Files.newInputStream(settings.pkiDirectory().resolve(settings.pki().fileName()))) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, password);
            X509Certificate certificate =
                    (X509Certificate) store.getCertificate(settings.pki().alias());
            if (certificate == null || !store.isKeyEntry(settings.pki().alias())) {
                throw new IllegalArgumentException("证书库别名未对应含私钥的客户端身份，请核对 pki.alias");
            }
            certificate.checkValidity();
            var alternatives = certificate.getSubjectAlternativeNames();
            if (alternatives == null
                    || alternatives.stream()
                            .noneMatch(
                                    v ->
                                            Integer.valueOf(6).equals(v.get(0))
                                                    && settings.applicationUri()
                                                            .equals(v.get(1)))) {
                throw new IllegalArgumentException("配置 application.uri 与已保存客户端证书不一致；请使用匹配配置");
            }
            var chain =
                    Arrays.stream(store.getCertificateChain(settings.pki().alias()))
                            .map(c -> (X509Certificate) c)
                            .toArray(X509Certificate[]::new);
            return new Identity(
                    new KeyPair(
                            certificate.getPublicKey(),
                            (PrivateKey) store.getKey(settings.pki().alias(), password)),
                    certificate,
                    chain);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public static void trustServer(ClientSettings settings, Path certificateFile, String expected)
            throws Exception {
        X509Certificate certificate;
        try (InputStream in = Files.newInputStream(certificateFile)) {
            certificate =
                    (X509Certificate)
                            CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        certificate.checkValidity();
        String fingerprint = fingerprint(certificate.getEncoded());
        String normalized = expected.replace(":", "").replace(" ", "");
        if (!normalized.matches("(?i)[0-9a-f]{64}") || !fingerprint.equalsIgnoreCase(normalized)) {
            throw new IllegalArgumentException("证书 SHA-256 与人工核对值不符，未导入");
        }
        try (var trust =
                FileBasedTrustListManager.createAndInitialize(
                        settings.pkiDirectory().resolve("trust-store"))) {
            trust.addTrustedCertificate(certificate);
        }
        System.out.println("已导入人工确认的服务器证书，SHA-256：" + fingerprint);
    }

    /** 对证书链中的首张应用证书求指纹，与导入公开证书时的算法保持一致。 */
    public static String fingerprint(byte[] encoded) throws Exception {
        try (var in = new ByteArrayInputStream(encoded)) {
            var certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        }
    }

    static char[] requestSecret(
            BiFunction<String, String, char[]> credentials, String environmentName, String prompt) {
        char[] value = credentials.apply(environmentName, prompt);
        if (value == null || value.length == 0) throw new IllegalArgumentException("凭据输入已取消或为空");
        return value;
    }

    public static char[] secret(String environmentName, String prompt) {
        String value = System.getenv(environmentName);
        if (value != null && !value.isEmpty()) return value.toCharArray();
        Console console = System.console();
        char[] password =
                console != null
                        ? console.readPassword("%s：", prompt)
                        : desktopSecret(prompt, environmentName);
        if (password == null || password.length == 0) throw new IllegalArgumentException("密码不能为空");
        return password;
    }

    /** IDEA 控制台不支持 Console.readPassword 时，由操作者在本机窗口隐藏输入。 */
    private static char[] desktopSecret(String prompt, String environmentName) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalArgumentException("缺少本机凭据：请配置环境变量 " + environmentName + "（不要写入仓库）");
        }
        AtomicReference<char[]> result = new AtomicReference<>();
        Runnable show =
                () -> {
                    JPasswordField input = new JPasswordField(28);
                    JPanel panel = new JPanel(new GridLayout(0, 1, 0, 8));
                    panel.add(new JLabel(prompt));
                    panel.add(input);
                    panel.add(new JLabel("仅本次进程使用，不保存到文件或运行配置。"));
                    var options =
                            new JOptionPane(
                                    panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
                    // 使用真正无 owner 的窗口，避免隐藏的共享 owner 导致后台启动时难以找到密码框。
                    var dialog =
                            new JDialog(
                                    (Window) null,
                                    "OPC UA 本机凭据",
                                    Dialog.ModalityType.APPLICATION_MODAL);
                    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                    dialog.setContentPane(options);
                    options.addPropertyChangeListener(
                            event -> {
                                if (JOptionPane.VALUE_PROPERTY.equals(event.getPropertyName())
                                        && event.getNewValue() != JOptionPane.UNINITIALIZED_VALUE)
                                    dialog.setVisible(false);
                            });
                    try {
                        dialog.pack();
                        dialog.setLocationRelativeTo(null);
                        dialog.setAlwaysOnTop(true);
                        SwingUtilities.invokeLater(input::requestFocusInWindow);
                        dialog.setVisible(true);
                        if (Integer.valueOf(JOptionPane.OK_OPTION).equals(options.getValue()))
                            result.set(input.getPassword());
                    } finally {
                        input.setText("");
                        dialog.dispose();
                    }
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

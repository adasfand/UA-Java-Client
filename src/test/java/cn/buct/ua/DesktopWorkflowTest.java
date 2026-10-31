package cn.buct.ua;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;

class DesktopWorkflowTest {
    private Path directory;

    @BeforeEach
    void prepare() throws Exception {
        directory =
                Files.createTempDirectory(
                        Files.createDirectories(Path.of(".cache/tmp").toAbsolutePath()),
                        "desktop-workflow-");
    }

    @AfterEach
    void clean() throws Exception {
        if (directory == null) return;
        if (!directory.normalize().startsWith(Path.of(".cache/tmp").toAbsolutePath().normalize()))
            throw new IllegalStateException("测试目录越界");
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private Properties properties() {
        var p = new Properties();
        p.setProperty("endpoint.url", "opc.tcp://127.0.0.1:49320");
        p.setProperty("security.policy", "Basic256");
        p.setProperty("security.mode", "SignAndEncrypt");
        p.setProperty("security.serverTrust", "first-use");
        p.setProperty("identity.type", "username");
        p.setProperty("identity.username", "demo-user");
        p.setProperty("application.uri", "urn:test:desktop-workflow");
        p.setProperty("application.name", "桌面流程测试");
        p.setProperty("pki.directory", "pki");
        return p;
    }

    private ClientSettings settings(Properties p) {
        return ClientSettings.from(p, directory.resolve("client.properties"));
    }

    @Test
    void firstLaunchCopiesConnectionTemplateWithoutAutomaticIdentity() throws Exception {
        Path config = directory.resolve("config/local/client.properties");
        ClientSettings.prepare(config, Path.of("config/templates"));
        ClientSettings loaded = ClientSettings.load(config);
        assertFalse(Files.exists(loaded.pkiDirectory().resolve("client.p12")));
        assertArrayEquals(
                Files.readAllBytes(Path.of("config/templates/client.properties")),
                Files.readAllBytes(config));
    }

    @Test
    void configurationPreparationPreservesExistingPersonalFiles() throws Exception {
        Path config = directory.resolve("client.properties");
        Files.writeString(config, "# 用户自建连接参数\n");
        ClientSettings.prepare(config, Path.of("config/templates"));
        assertEquals("# 用户自建连接参数\n", Files.readString(config));
    }

    @Test
    void emptyOrCancelledPasswordDoesNotCreateAnEmptyPkiDirectory() {
        ClientSettings settings = settings(properties());
        for (char[] missing : new char[][] {null, new char[0]}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> LocalPki.initialize(settings, (env, prompt) -> missing));
            assertFalse(Files.exists(settings.pkiDirectory()));
        }
    }

    @Test
    void directoryCreationFailureStillClearsThePassword() throws Exception {
        ClientSettings settings = settings(properties());
        Files.writeString(settings.pkiDirectory(), "not a directory");
        char[] secret = "unit-test-password".toCharArray();
        assertThrows(
                java.io.IOException.class,
                () -> LocalPki.initialize(settings, (env, prompt) -> secret));
        assertArrayEquals(new char[secret.length], secret);
    }

    @Test
    void invalidPublicCertificateDestinationCannotLeaveAUsablePrivateIdentity() throws Exception {
        ClientSettings settings = settings(properties());
        Path publicPath = settings.pkiDirectory().resolve("client.der");
        Files.createDirectories(publicPath);
        char[] secret = "unit-test-password".toCharArray();
        assertThrows(
                IllegalArgumentException.class,
                () -> LocalPki.initialize(settings, (env, prompt) -> secret));
        assertFalse(Files.exists(settings.pkiDirectory().resolve("client.p12")));
        assertTrue(Files.isDirectory(publicPath));
        assertArrayEquals(new char[secret.length], secret);
    }

    @Test
    void initializedIdentityCanBeLoadedAndInitializationNeverOverwritesIt() throws Exception {
        ClientSettings settings = settings(properties());
        LocalPki.initialize(settings, (env, prompt) -> "unit-test-password".toCharArray());
        Path identity = settings.pkiDirectory().resolve("client.p12");
        byte[] original = Files.readAllBytes(identity);
        byte[] certificate = Files.readAllBytes(settings.pkiDirectory().resolve("client.der"));
        var loaded = LocalPki.load(settings, (env, prompt) -> "unit-test-password".toCharArray());
        assertArrayEquals(certificate, loaded.certificate().getEncoded());
        assertNotNull(loaded.keyPair().getPrivate());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        LocalPki.initialize(
                                settings,
                                (env, prompt) -> {
                                    fail("已有身份不应再次询问新密码");
                                    return null;
                                }));
        assertArrayEquals(original, Files.readAllBytes(identity));
    }
}

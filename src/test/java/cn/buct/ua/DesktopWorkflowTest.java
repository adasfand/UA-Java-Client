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
        p.setProperty("nodes.file", "nodes.properties");
        p.setProperty("environment", "simulation");
        return p;
    }

    private ClientSettings settings(Properties p) {
        return ClientSettings.from(p, directory.resolve("client.properties"));
    }

    @Test
    void firstLaunchCopiesBothTemplatesWithRelativePathsAndNoAutomaticIdentity() throws Exception {
        Path config = directory.resolve("config/local/client.properties");
        DesktopFiles.prepare(config, Path.of("config/templates"));
        ClientSettings loaded = ClientSettings.load(config);
        assertEquals(config.getParent().resolve("nodes.properties"), loaded.nodesFile());
        assertEquals(1, loaded.points().size());
        assertFalse(loaded.writesEnabled());
        assertFalse(Files.exists(loaded.pkiDirectory().resolve("client.p12")));
        assertArrayEquals(
                Files.readAllBytes(Path.of("config/templates/client.properties")),
                Files.readAllBytes(config));
    }

    @Test
    void configurationPreparationPreservesExistingPersonalFiles() throws Exception {
        Path config = directory.resolve("client.properties");
        Path nodes = directory.resolve("nodes.properties");
        Files.writeString(nodes, "# 用户自建点表\nnodes=\n");
        byte[] original = Files.readAllBytes(nodes);
        DesktopFiles.prepare(config, Path.of("config/templates"));
        assertArrayEquals(original, Files.readAllBytes(nodes));
        Files.writeString(config, "# 用户自建连接参数\n");
        DesktopFiles.prepare(config, Path.of("config/templates"));
        assertEquals("# 用户自建连接参数\n", Files.readString(config));
    }

    @Test
    void dataTableCanBeCreatedBeforeAnyNetworkConnection() throws Exception {
        Path config = directory.resolve("config/local/client.properties");
        DesktopFiles.prepare(config, Path.of("config/templates"));
        javax.swing.SwingUtilities.invokeAndWait(
                () -> {
                    var panel = new DataPanel(null, config);
                    var table =
                            (javax.swing.JTable)
                                    ((javax.swing.JScrollPane) panel.getComponent(1))
                                            .getViewport()
                                            .getView();
                    assertEquals(1, table.getRowCount());
                    assertEquals("serverTime", table.getValueAt(0, 0));
                    assertEquals("未读取", table.getValueAt(0, 3));
                });
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

    @Test
    void sameAuthenticatedSessionCanUseAnExplicitlySavedWriteSwitch() {
        var p = properties();
        ClientSettings connected = settings(p);
        p.setProperty("writes.enabled", "true");
        ClientSettings current = settings(p);
        assertDoesNotThrow(() -> current.requireSameConnection(connected));
        var point =
                new PointDefinition(
                        "sim", "KEPServerEX", "s", "Demo.Device.sim", "UInt16", true, "0", "100");
        assertThrows(IllegalArgumentException.class, () -> connected.requireWrite(point, true));
        assertDoesNotThrow(() -> current.requireWrite(point, true));
        assertThrows(IllegalArgumentException.class, () -> current.requireWrite(point, false));
    }

    @Test
    void changedServerUserIdentityOrEnvironmentCannotAuthorizeOldSession() {
        ClientSettings connected = settings(properties());
        for (var change :
                java.util.Map.of(
                                "endpoint.url", "opc.tcp://127.0.0.1:4840",
                                "identity.username", "another-user",
                                "application.uri", "urn:test:another-application",
                                "pki.directory", "other-pki",
                                "nodes.file", "other-nodes.properties",
                                "environment", "production",
                                "security.serverTrust", "strict")
                        .entrySet()) {
            var p = properties();
            p.setProperty(change.getKey(), change.getValue());
            p.setProperty("writes.enabled", "true");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> settings(p).requireSameConnection(connected),
                    change.getKey());
        }
    }
}

package cn.buct.ua;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Map;

class ClientConfigurationTest {
    private Path directory;
    private Path file;

    @BeforeEach
    void prepare() throws Exception {
        Path root = Files.createDirectories(Path.of(".cache/tmp").toAbsolutePath());
        directory = Files.createTempDirectory(root, "client-config-");
        file = directory.resolve("client.properties");
        Files.writeString(file, base());
    }

    @AfterEach
    void clean() throws Exception {
        if (directory == null) return;
        Path root = Path.of(".cache/tmp").toAbsolutePath().normalize();
        if (!directory.normalize().startsWith(root)) throw new IllegalStateException("测试目录越界");
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static String base() {
        return """
               # 客户端配置；密码仅在窗口输入
               endpoint.url=opc.tcp://localhost:49320
               security.policy=Basic256
               security.mode=SignAndEncrypt
               security.serverTrust=first-use
               identity.type=username
               identity.username=old-user
               identity.passwordEnv=UA_PASSWORD
               application.uri=urn:test:configuration
               application.name=本机客户端
               request.timeoutMs=10000
               pki.directory=../../pki
               pki.passwordEnv=UA_KEYSTORE_PASSWORD
               """;
    }

    private java.util.List<Path> backups() throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(p -> p.toString().endsWith(".bak")).toList();
        }
    }

    @Test
    void loginModeRoundTripRetainsUsernameAndDoesNotPersistPasswordsOrBackups() throws Exception {
        var editor = new ClientConfiguration(file);
        var anonymous = editor.save(Map.of("identity.type", "anonymous"));
        assertEquals("anonymous", anonymous.identityType());
        assertEquals("old-user", anonymous.username());
        var user = editor.save(Map.of("identity.type", "username"));
        assertEquals("username", user.identityType());
        assertEquals("old-user", user.username());
        assertTrue(backups().isEmpty());
    }

    @Test
    void displayedSettingsUseTheCapturedVersionUntilReloaded() throws Exception {
        var displayed = new ClientConfiguration(file);
        Files.writeString(file, base().replace("old-user", "external-user"));
        assertEquals("old-user", displayed.settings().username());
        assertEquals("external-user", new ClientConfiguration(file).settings().username());
        assertThrows(
                IOException.class, () -> displayed.save(Map.of("identity.username", "ui-user")));
        assertEquals("external-user", ClientSettings.load(file).username());
    }

    @Test
    void updatesEffectiveUsernameRemovesAllDuplicatesWithoutLeavingBackups() throws Exception {
        String source = base() + "# 保留注释\nidentity.user\\u006eame = stale-user\n";
        Files.writeString(file, source);
        var config = new ClientConfiguration(file);
        assertEquals("stale-user", config.values().get("identity.username"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> config.values().put("identity.username", "wrong"));
        var saved = config.save(Map.of("identity.username", "demo-ui"));
        assertEquals("demo-ui", saved.username());
        assertEquals("demo-ui", ClientSettings.load(file).username());
        assertEquals("demo-ui", config.values().get("identity.username"));
        assertEquals(
                1,
                Files.readString(file)
                        .lines()
                        .filter(line -> line.startsWith("identity.username="))
                        .count());
        assertFalse(Files.readString(file).contains("stale-user"));
        assertTrue(Files.readString(file).contains("# 保留注释"));
        assertTrue(backups().isEmpty());
    }

    @Test
    void preservesRelativePathsUnknownPropertiesCommentsAndLineEndings() throws Exception {
        String source =
                (base() + "# secret 这里只是注释\ncustom.value = 首行\\\n  次行\n").replace("\n", "\r\n");
        Files.writeString(file, source);
        var config = new ClientConfiguration(file);
        var saved = config.save(Map.of("identity.username", "new-user"));
        assertEquals(directory.resolve("../../pki").normalize(), saved.pkiDirectory());
        String output = Files.readString(file);
        assertTrue(output.contains("pki.directory=../../pki\r\n"));
        assertTrue(output.contains("custom.value = 首行\\\r\n  次行\r\n"));
        assertTrue(output.contains("# secret 这里只是注释\r\n"));
        assertEquals("首行次行", ClientSettings.read(file).getProperty("custom.value"));
    }

    @Test
    void roundTripsUtf8EscapesAndReplacesWholeContinuedProperty() throws Exception {
        Files.writeString(file, base() + "custom.label=旧\\\n  内容\n");
        String value = "  中文 空格=值:#!\\路径\n下一行\t结束  ";
        var config = new ClientConfiguration(file);
        config.save(Map.of("custom.label", value, "custom.new key", " 测试 "));
        var stored = ClientSettings.read(file);
        assertEquals(value, stored.getProperty("custom.label"));
        assertEquals(" 测试 ", stored.getProperty("custom.new key"));
        assertTrue(Files.readString(file).contains("中文"));
        assertFalse(Files.readString(file).contains("  内容"));
    }

    @Test
    void appendingAfterDanglingContinuationDoesNotMergeProperties() throws Exception {
        for (String suffix : new String[] {"custom.value=tail\\", "custom.value=tail\\\n"}) {
            Files.writeString(file, base() + suffix);
            new ClientConfiguration(file).save(Map.of("custom.new", "new-value"));
            var stored = ClientSettings.read(file);
            assertEquals("tail", stored.getProperty("custom.value"));
            assertEquals("new-value", stored.getProperty("custom.new"));
        }
    }

    @Test
    void unchangedSaveDoesNotCreateBackupOrRewriteFormatting() throws Exception {
        byte[] before = Files.readAllBytes(file);
        var config = new ClientConfiguration(file);
        config.save(config.values());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertTrue(backups().isEmpty());
    }

    @Test
    void staleSnapshotCannotOverwriteExternalEdit() throws Exception {
        var config = new ClientConfiguration(file);
        Files.writeString(file, base() + "# 外部修改\n");
        byte[] edited = Files.readAllBytes(file);
        assertThrows(IOException.class, () -> config.save(Map.of("identity.username", "new-user")));
        assertArrayEquals(edited, Files.readAllBytes(file));
        assertTrue(backups().isEmpty());
    }

    @Test
    void separateEditorSnapshotsConflictAfterFirstSave() throws Exception {
        var first = new ClientConfiguration(file);
        var second = new ClientConfiguration(file);
        first.save(Map.of("identity.username", "first-user"));
        assertThrows(
                IOException.class, () -> second.save(Map.of("identity.username", "second-user")));
        assertEquals("first-user", ClientSettings.load(file).username());
        assertTrue(backups().isEmpty());
    }

    @Test
    void invalidConnectionSettingsLeaveOriginalUnchanged() throws Exception {
        byte[] before = Files.readAllBytes(file);
        var config = new ClientConfiguration(file);
        for (Map<String, String> change :
                java.util.List.of(
                        Map.of("endpoint.url", "http://localhost:49320"),
                        Map.of("security.mode", "None"),
                        Map.of("identity.username", ""),
                        Map.of("request.timeoutMs", "999"),
                        Map.of("request.timeoutMs", "120001"),
                        Map.of("request.timeoutMs", "invalid"))) {
            assertThrows(IllegalArgumentException.class, () -> config.save(change));
            assertArrayEquals(before, Files.readAllBytes(file));
        }
        assertTrue(backups().isEmpty());
        try (var paths = Files.list(directory)) {
            assertTrue(paths.noneMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test
    void refusesPlaintextCredentialsButAllowsEnvironmentVariableNamesAndPkiMetadata()
            throws Exception {
        var config = new ClientConfiguration(file);
        byte[] before = Files.readAllBytes(file);
        for (String key :
                java.util.List.of(
                        "password",
                        "identity.password",
                        "pki.password",
                        "custom.secret",
                        "api.token")) {
            assertThrows(
                    IllegalArgumentException.class, () -> config.save(Map.of(key, "test-value")));
            assertArrayEquals(before, Files.readAllBytes(file));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> config.save(Map.of("identity.passwordEnv", "12345")));
        assertTrue(backups().isEmpty());
        config.save(
                Map.of(
                        "identity.passwordEnv",
                        "DEMO_UA_PASSWORD",
                        "pki.passwordEnv",
                        "DEMO_KEYSTORE_PASSWORD",
                        "pki.alias",
                        "client-key",
                        "pki.organization",
                        "测试组织"));
        assertEquals("DEMO_UA_PASSWORD", ClientSettings.load(file).passwordEnv());
        assertEquals("client-key", ClientSettings.load(file).pki().alias());
        assertFalse(Files.readString(file).contains("12345"));
    }

    @Test
    void cooperatingWriterLockPreventsSave() throws Exception {
        var config = new ClientConfiguration(file);
        byte[] before = Files.readAllBytes(file);
        try (var channel =
                        FileChannel.open(
                                file.resolveSibling("client.properties.catalog.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.lock()) {
            assertThrows(
                    IOException.class, () -> config.save(Map.of("identity.username", "new-user")));
        }
        assertArrayEquals(before, Files.readAllBytes(file));
        assertTrue(backups().isEmpty());
    }
}

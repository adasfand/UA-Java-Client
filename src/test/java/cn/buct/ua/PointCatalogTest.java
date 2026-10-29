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
import java.util.List;

class PointCatalogTest {
    private Path directory;
    private Path file;

    @BeforeEach
    void prepare() throws Exception {
        Path root = Files.createDirectories(Path.of(".cache/tmp").toAbsolutePath());
        directory = Files.createTempDirectory(root, "catalog-");
        file = directory.resolve("nodes.properties");
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

    private DiscoveredPoint point(String display, String id) {
        return new DiscoveredPoint(display, "urn:test:server", "s", id, "UInt16", -1, true, true);
    }

    private String existing() {
        return """
               # 已有业务设置和注释
               nodes=Temperature
               node.Temperature.namespaceUri=urn:test:server
               node.Temperature.identifierType=s
               node.Temperature.identifier=old-temperature
               node.Temperature.dataType=UInt16
               node.Temperature.writable=true
               node.Temperature.minimum=0
               node.Temperature.maximum=100
               node.Temperature.custom=保留此值
               # nodes=注释不是生效属性
               """;
    }

    @Test
    void mergesOnlyNewNodesPreservingBusinessFieldsAndExactBackup() throws Exception {
        Files.writeString(file, existing());
        byte[] before = Files.readAllBytes(file);
        var catalog = new PointCatalog(file);
        var result =
                catalog.save(
                        List.of(
                                point("改过的显示名", "old-temperature"),
                                point("Temperature", "new-temperature")));
        assertEquals(1, result.added());
        assertEquals(1, result.existing());
        var loaded = new PointCatalog(file).existingPoints();
        assertEquals(2, loaded.size());
        assertTrue(loaded.getFirst().writable());
        assertEquals("100", loaded.getFirst().maximum());
        assertEquals("Temperature", loaded.getFirst().alias());
        assertEquals("Temperature_2", loaded.getLast().alias());
        assertFalse(loaded.getLast().writable());
        var properties = ClientSettings.read(file);
        assertEquals("保留此值", properties.getProperty("node.Temperature.custom"));
        assertEquals("true", properties.getProperty("node.Temperature_2.serverWritable"));
        assertEquals("-1", properties.getProperty("node.Temperature_2.valueRank"));
        assertTrue(Files.readString(file).contains("# nodes=注释不是生效属性"));
        try (var paths = Files.list(directory)) {
            var backups = paths.filter(p -> p.toString().endsWith(".bak")).toList();
            assertEquals(1, backups.size());
            assertTrue(backups.getFirst().getFileName().toString().matches("[A-Za-z0-9.-]+"));
            assertArrayEquals(before, Files.readAllBytes(backups.getFirst()));
        }
    }

    @Test
    void roundTripsChineseIdentifiersAndPropertiesEscapesWithoutUnicodeEncoding() throws Exception {
        String id = "  通道 1.设备=2:标签#!\n\\尾部  ";
        var point = point("温度 = PV\n\\值", id);
        var result = new PointCatalog(file).save(List.of(point));
        assertEquals(1, result.added());
        var saved = new PointCatalog(file).existingPoints().getFirst();
        assertEquals(id, saved.identifier());
        assertEquals(
                point.displayName(),
                ClientSettings.read(file).getProperty("node." + saved.alias() + ".displayName"));
        assertTrue(Files.readString(file).contains("通道"));
        assertFalse(Files.readString(file).contains("\\u901a"));
        assertFalse(saved.writable());
        Path clientConfig = directory.resolve("client.properties");
        Files.writeString(
                clientConfig,
                """
                endpoint.url=opc.tcp://localhost:4840
                security.policy=Basic256Sha256
                security.mode=SignAndEncrypt
                identity.type=anonymous
                application.uri=urn:test:catalog
                application.name=点表测试
                pki.directory=pki
                nodes.file=nodes.properties
                """);
        assertEquals(id, ClientSettings.load(clientConfig).points().getFirst().identifier());
    }

    @Test
    void duplicateSelectionAndRepeatedSaveDoNotDuplicateNodesOrCreateExtraBackup()
            throws Exception {
        var catalog = new PointCatalog(file);
        DiscoveredPoint first = point("same", "id-one");
        assertEquals(2, catalog.save(List.of(first, first, point("same", "id-two"))).added());
        byte[] after = Files.readAllBytes(file);
        var repeated = catalog.save(List.of(first, point("same", "id-two")));
        assertEquals(0, repeated.added());
        assertEquals(2, repeated.existing());
        assertArrayEquals(after, Files.readAllBytes(file));
        assertEquals(
                List.of("same", "same_2"),
                catalog.existingPoints().stream().map(PointDefinition::alias).toList());
        try (var paths = Files.list(directory)) {
            assertEquals(0, paths.filter(p -> p.toString().endsWith(".bak")).count());
        }
    }

    @Test
    void externalChangesAndStaleSecondWindowAreNeverOverwritten() throws Exception {
        Files.writeString(file, existing());
        var first = new PointCatalog(file);
        var second = new PointCatalog(file);
        first.save(List.of(point("new", "id-one")));
        byte[] current = Files.readAllBytes(file);
        assertThrows(IOException.class, () -> second.save(List.of(point("other", "id-two"))));
        assertArrayEquals(current, Files.readAllBytes(file));
        var third = new PointCatalog(file);
        Files.writeString(file, "\n# 操作者刚刚编辑\n", StandardOpenOption.APPEND);
        current = Files.readAllBytes(file);
        assertThrows(IOException.class, () -> third.save(List.of(point("other", "id-three"))));
        assertArrayEquals(current, Files.readAllBytes(file));
    }

    @Test
    void updatesContinuedAndEscapedNodesKeyWhileKeepingOtherProperties() throws Exception {
        Files.writeString(
                file,
                existing().replace("nodes=Temperature", "n\\odes=\\\n  Temperature")
                        + "arbitrary=first\\\n  second\n");
        new PointCatalog(file).save(List.of(point("next", "id-next")));
        assertEquals("Temperature,next", ClientSettings.read(file).getProperty("nodes"));
        assertEquals("firstsecond", ClientSettings.read(file).getProperty("arbitrary"));
        assertEquals(2, new PointCatalog(file).existingPoints().size());
    }

    @Test
    void rejectsEmptySelectionAndConcurrentWriterWithoutTouchingOriginal() throws Exception {
        Files.writeString(file, existing());
        byte[] before = Files.readAllBytes(file);
        var catalog = new PointCatalog(file);
        assertThrows(IllegalArgumentException.class, () -> catalog.save(List.of()));
        Path lock = file.resolveSibling(file.getFileName() + ".catalog.lock");
        try (var channel =
                        FileChannel.open(
                                lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var ignored = channel.lock()) {
            assertThrows(IOException.class, () -> catalog.save(List.of(point("new", "id-one"))));
        }
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void canonicalIdentifiersAndUnknownReadonlyTypesRemainStable() throws Exception {
        var upper =
                new DiscoveredPoint(
                        "custom",
                        "urn:test",
                        "g",
                        "11111111-2222-3333-4444-ABCDEF123456",
                        "nsu=urn:custom;i=42",
                        1,
                        true,
                        false);
        var lower =
                new DiscoveredPoint(
                        "other",
                        "urn:test",
                        "g",
                        "11111111-2222-3333-4444-abcdef123456",
                        "nsu=urn:custom;i=42",
                        1,
                        true,
                        false);
        assertEquals(upper.key(), lower.key());
        assertEquals(
                new DiscoveredPoint("one", "urn:test", "i", "00042", "Int32", -1, true, false)
                        .key(),
                new DiscoveredPoint("two", "urn:test", "i", "42", "Int32", -1, true, false).key());
        var catalog = new PointCatalog(file);
        assertEquals(1, catalog.save(List.of(upper, lower)).added());
        assertEquals(
                "nsu=urn:custom;i=42",
                new PointCatalog(file).existingPoints().getFirst().dataType());
    }

    @Test
    void unlistedExistingNodePropertiesReserveTheirAliases() throws Exception {
        Files.writeString(file, "# 暂未选中但需保留的配置\nnode.reserved.custom=keep\n");
        var catalog = new PointCatalog(file);
        catalog.save(List.of(point("reserved", "id-new")));
        assertEquals("reserved_2", catalog.existingPoints().getFirst().alias());
        assertEquals("keep", ClientSettings.read(file).getProperty("node.reserved.custom"));
    }

    @Test
    void invalidExistingWriteRulesAreReportedWithoutRepairingUserConfiguration() throws Exception {
        Files.writeString(file, existing().replace("node.Temperature.maximum=100\n", ""));
        byte[] before = Files.readAllBytes(file);
        assertThrows(IllegalArgumentException.class, () -> new PointCatalog(file));
        assertArrayEquals(before, Files.readAllBytes(file));
    }
}

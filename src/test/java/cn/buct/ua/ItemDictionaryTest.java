package cn.buct.ua;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeoutException;

class ItemDictionaryTest {
    private Path directory;
    private Path config;

    @BeforeEach
    void prepare() throws Exception {
        directory =
                Files.createTempDirectory(
                        Files.createDirectories(Path.of(".cache/tmp").toAbsolutePath()),
                        "item-dictionary-");
        config = directory.resolve("client.properties");
        Files.writeString(
                config,
                """
                endpoint.url=opc.tcp://localhost:49320
                security.policy=Basic256
                security.mode=SignAndEncrypt
                security.serverTrust=strict
                identity.type=anonymous
                application.uri=urn:test:items
                application.name=ItemDictionary test
                pki.directory=pki
                """);
    }

    @AfterEach
    void clean() throws Exception {
        if (!directory.normalize().startsWith(Path.of(".cache/tmp").toAbsolutePath().normalize()))
            throw new IllegalStateException("测试目录越界");
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static List<DeviceAddressTable.Entry> entries() {
        return List.of(
                new DeviceAddressTable.Entry("PV", "ns=2;s=Furnace.PV"),
                new DeviceAddressTable.Entry("SP", "ns=2;s=Furnace.SP"));
    }

    private static DataValue good(Object value) {
        return new DataValue(new Variant(value), StatusCode.GOOD, null, null);
    }

    private static final class Session implements UaClient.Session {
        List<DataValue> values = List.of(good(10.5f), good(20));
        final ArrayList<List<String>> batches = new ArrayList<>();
        boolean fail;
        boolean shortResult;
        int writes;

        public DataValue read(String address) {
            throw new AssertionError("周期采集应复用批量传输。");
        }

        public List<DataValue> readMany(List<String> addresses) throws Exception {
            batches.add(List.copyOf(addresses));
            if (fail) throw new TimeoutException("test");
            if (shortResult) return List.of();
            var result = new ArrayList<DataValue>();
            for (String address : addresses)
                result.add(values.get(address.endsWith(".PV") ? 0 : 1));
            return result;
        }

        public StatusCode write(String address, Object value) {
            writes++;
            return StatusCode.GOOD;
        }

        public boolean isConnected() {
            return true;
        }

        public void close() {}
    }

    private UaClient client(Session session) throws Exception {
        var client = new UaClient(config, (settings, credentials) -> session);
        client.connect("localhost");
        return client;
    }

    @Test
    void tableDrivesAllReadsAndValuesAreIndexedByBusinessNameWithoutWriting() throws Exception {
        var session = new Session();
        try (var client = client(session)) {
            var items = new ItemDictionary(entries());
            assertThrows(IllegalStateException.class, () -> items.getValue("PV"));
            var snapshot = items.refresh(client);
            assertEquals(2, snapshot.samples().size());
            assertEquals(10.5, items.getDouble("PV"));
            assertEquals(20, items.data().get("SP"));
            assertEquals("ns=2;s=Furnace.PV", items.itemDictionary().get("PV"));
            assertEquals(
                    List.of(List.of("ns=2;s=Furnace.PV", "ns=2;s=Furnace.SP")), session.batches);
            assertEquals(0, session.writes);
            assertNotNull(snapshot.collectedAt());
            assertThrows(UnsupportedOperationException.class, () -> items.data().put("PV", 999));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> items.itemDictionary().put("PV", "ns=0;i=2258"));
            items.clear();
            assertTrue(items.data().isEmpty());
            assertThrows(IllegalStateException.class, () -> items.getValue("PV"));
        }
    }

    @Test
    void badQualityRemovesOnlyFailedPointInsteadOfRetainingItsOldValue() throws Exception {
        var session = new Session();
        try (var client = client(session)) {
            var items = new ItemDictionary(entries());
            var first = items.refresh(client);
            session.values =
                    List.of(
                            good(30.5f),
                            new DataValue(
                                    new Variant(999),
                                    new StatusCode(StatusCodes.Bad_NodeIdUnknown),
                                    null,
                                    null));
            var second = items.refresh(client);
            assertEquals(1, second.samples().size());
            assertEquals(30.5, items.getDouble("PV"));
            assertFalse(items.data().containsKey("SP"));
            assertThrows(IllegalStateException.class, () -> items.getValue("SP"));
            assertEquals(20, first.data().get("SP"), "已发布快照不能随下一轮变化");
            assertTrue(second.errors().get("SP").contains("Bad_NodeIdUnknown"));
        }
    }

    @Test
    void transportFailureInvalidatesThisRoundAndDoesNotMultiplyTimeouts() throws Exception {
        var session = new Session();
        try (var client = client(session)) {
            var items = new ItemDictionary(entries(), 1);
            items.refresh(client);
            assertEquals(2, session.batches.size());
            session.fail = true;
            var result = items.refresh(client);
            assertTrue(items.data().isEmpty());
            assertEquals(2, result.errors().size());
            assertEquals(3, session.batches.size(), "首批超时后不继续发出剩余批次");
        }
    }

    @Test
    void malformedServiceResultsNeverMapToWrongBusinessNames() throws Exception {
        var session = new Session();
        session.shortResult = true;
        try (var client = client(session)) {
            var snapshot = new ItemDictionary(entries()).refresh(client);
            assertTrue(snapshot.data().isEmpty());
            assertEquals(2, snapshot.errors().size());
        }
    }

    @Test
    void nullAndNonNumericGoodValuesArePreservedWithoutBecomingFakeZero() throws Exception {
        var session = new Session();
        session.values = List.of(good(null), good("running"));
        try (var client = client(session)) {
            var items = new ItemDictionary(entries());
            items.refresh(client);
            assertTrue(items.data().containsKey("PV"));
            assertNull(items.getValue("PV"));
            assertEquals("running", items.getValue("SP"));
            assertThrows(IllegalArgumentException.class, () -> items.getDouble("PV"));
            assertThrows(IllegalArgumentException.class, () -> items.getDouble("SP"));
            assertThrows(IllegalArgumentException.class, () -> items.getValue("unknown"));
        }
    }

    @Test
    void existingExcelReaderFeedsTheSameDictionaryAndDefaultTwoSecondSettings() throws Exception {
        var table = ItemDictionary.load(Path.of("config/templates/device-addresses.xlsx"));
        assertEquals(10, table.itemDictionary().size());
        var options = ItemDictionary.Options.load(config);
        assertEquals(2000, options.intervalMs());
        assertEquals(256, options.batchSize());
        assertThrows(IllegalArgumentException.class, () -> new ItemDictionary.Options(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ItemDictionary.Options(2000, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ItemDictionary(List.of(entries().getFirst(), entries().getFirst())));
    }
}

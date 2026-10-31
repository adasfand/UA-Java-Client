package cn.buct.ua;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;

class UaClientTest {
    private Path directory;
    private Path config;
    private static final String ADDRESS = "ns=2;s=Device.value";

    @BeforeEach
    void prepare() throws Exception {
        directory =
                Files.createTempDirectory(Files.createDirectories(Path.of(".cache/tmp")), "ua-api-")
                        .toAbsolutePath()
                        .normalize();
        config = directory.resolve("client.properties");
        Files.writeString(
                config,
                """
                endpoint.url=opc.tcp://localhost:49320/ua
                endpoint.discoveryUrl=opc.tcp://127.0.0.1:49320/old
                security.policy=Basic256Sha256
                security.mode=SignAndEncrypt
                security.serverTrust=strict
                identity.type=username
                identity.username=config-user
                application.uri=urn:test:api
                application.name=API test
                pki.directory=pki
                """);
    }

    @AfterEach
    void clean() throws Exception {
        if (directory == null) return;
        if (!directory.startsWith(Path.of(".cache/tmp").toAbsolutePath().normalize()))
            throw new IllegalStateException("测试目录越界");
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static class Session implements UaClient.Session {
        boolean connected = true;
        int closes;
        ArrayList<String> reads = new ArrayList<>();
        ArrayList<Object> writes = new ArrayList<>();
        DataValue data = new DataValue(new Variant(42), StatusCode.GOOD, null, null);
        StatusCode status = StatusCode.GOOD;

        public DataValue read(String address) {
            reads.add(address);
            return data;
        }

        public StatusCode write(String address, Object value) {
            writes.add(address);
            writes.add(value);
            return status;
        }

        public boolean isConnected() {
            return connected;
        }

        public void close() {
            connected = false;
            closes++;
        }
    }

    private UaClient connected(Session session) throws Exception {
        var client = new UaClient(config, (settings, credentials) -> session);
        client.connect("opc.tcp://192.0.2.15:4840/现场", "test-user", null, null);
        return client;
    }

    @Test
    void readingAndWritingRequireConnectionAndDoNotLoadPointTable() throws Exception {
        var session = new Session();
        try (var client = new UaClient(config, (settings, credentials) -> session)) {
            assertThrows(IllegalStateException.class, () -> client.read(ADDRESS));
            assertThrows(IllegalStateException.class, () -> client.write(ADDRESS, 12));
            client.connect("192.0.2.15", "test-user", null, null);
            assertEquals("opc.tcp://192.0.2.15:49320/ua", client.endpoint().toString());
            assertEquals(42, client.read(ADDRESS));
            assertEquals(StatusCode.GOOD, client.write(ADDRESS, 12.5f));
            assertEquals(java.util.List.of(ADDRESS), session.reads);
            assertEquals(java.util.List.of(ADDRESS, 12.5f), session.writes);
            assertFalse(Files.exists(directory.resolve("nodes-does-not-exist.properties")));
        }
    }

    @Test
    void suppliedEndpointOverridesStaleDiscoveryWithoutSavingOrChangingSecurity() throws Exception {
        byte[] original = Files.readAllBytes(config);
        var captured = new AtomicReference<ClientSettings>();
        try (var client =
                new UaClient(
                        config,
                        (settings, credentials) -> {
                            captured.set(settings);
                            return new Session();
                        })) {
            client.connect("opc.tcp://server.example:4841/new", "site-user", null, null);
            assertEquals(captured.get().endpoint(), captured.get().discoveryEndpoint());
            assertEquals("site-user", captured.get().username());
            assertEquals("Basic256Sha256", captured.get().policy().name());
            assertEquals("strict", captured.get().serverTrust());
            assertEquals(directory.resolve("pki"), captured.get().pkiDirectory());
            assertArrayEquals(original, Files.readAllBytes(config));
        }
    }

    @Test
    void separateRemoteIpControlsBothConnectionAndDiscoveryWithoutChangingSecurity()
            throws Exception {
        byte[] original = Files.readAllBytes(config);
        var captured = new AtomicReference<ClientSettings>();
        try (var client =
                new UaClient(
                        config,
                        (settings, credentials) -> {
                            captured.set(settings);
                            return new Session();
                        })) {
            client.connect(
                    "opc.tcp://previous.example:4841/ua%2Fsite",
                    "192.0.2.27", "site-user", null, null);
            assertEquals("opc.tcp://192.0.2.27:4841/ua%2Fsite", client.endpoint().toString());
            assertEquals(client.endpoint(), captured.get().endpoint());
            assertEquals(client.endpoint(), captured.get().discoveryEndpoint());
            assertEquals("site-user", captured.get().username());
            assertEquals("Basic256Sha256", captured.get().policy().name());
            assertEquals("strict", captured.get().serverTrust());
            assertArrayEquals(original, Files.readAllBytes(config));
        }
    }

    @Test
    void remoteHostCanUseConfiguredPortAndPathWhileEmptyArgumentsKeepSeparateDiscovery()
            throws Exception {
        var captured = new AtomicReference<ClientSettings>();
        try (var client =
                new UaClient(
                        config,
                        (settings, credentials) -> {
                            captured.set(settings);
                            return new Session();
                        })) {
            client.connect(null, "::1");
            assertEquals("opc.tcp://[::1]:49320/ua", client.endpoint().toString());
            assertEquals(client.endpoint(), captured.get().discoveryEndpoint());
            assertEquals("config-user", captured.get().username());
            client.close();
            client.connect(null, null, null, null, null);
            assertEquals("opc.tcp://localhost:49320/ua", client.endpoint().toString());
            assertEquals(
                    "opc.tcp://127.0.0.1:49320/old", captured.get().discoveryEndpoint().toString());
        }
    }

    @Test
    void malformedRemoteHostCannotOpenAConnection() throws Exception {
        var captured = new AtomicReference<ClientSettings>();
        try (var client =
                new UaClient(
                        config,
                        (settings, credentials) -> {
                            captured.set(settings);
                            return new Session();
                        })) {
            for (String invalid :
                    new String[] {
                        "opc.tcp://other:4840", "other:4840", "other/path", "user@other"
                    }) {
                assertThrows(
                        Exception.class,
                        () -> client.connect("opc.tcp://server.example:49320", invalid));
                assertNull(captured.get());
                assertFalse(client.isConnected());
            }
        }
    }

    @Test
    void secretProviderCopiesArraysAndNeverClearsCallerInput() throws Exception {
        char[] user = "test-account-secret".toCharArray();
        char[] store = "test-keystore-secret".toCharArray();
        try (var client =
                new UaClient(
                        config,
                        (settings, credentials) -> {
                            char[] suppliedStore =
                                    credentials.apply(settings.pkiPasswordEnv(), "输入本机证书库密码");
                            char[] suppliedUser =
                                    credentials.apply(
                                            settings.passwordEnv(), "输入 OPC UA 用户 test 的密码");
                            assertArrayEquals(store, suppliedStore);
                            assertArrayEquals(user, suppliedUser);
                            assertNotSame(user, suppliedUser);
                            Arrays.fill(suppliedUser, '\0');
                            Arrays.fill(suppliedStore, '\0');
                            assertArrayEquals(
                                    user, credentials.apply(settings.passwordEnv(), "用户密码"));
                            return new Session();
                        })) {
            client.connect("localhost", "test", user, store);
            assertEquals("test-account-secret", new String(user));
            assertEquals("test-keystore-secret", new String(store));
        } finally {
            Arrays.fill(user, '\0');
            Arrays.fill(store, '\0');
        }
    }

    @Test
    void invalidAddressAndBadQualityCannotBeReportedAsSuccessfulRead() throws Exception {
        var session = new Session();
        try (var client = connected(session)) {
            assertThrows(
                    IllegalArgumentException.class, () -> client.read("A151TT_06102A/ALM1/PV.CV"));
            assertTrue(session.reads.isEmpty());
            session.data =
                    new DataValue(
                            new Variant(null),
                            new StatusCode(StatusCodes.Bad_NodeIdUnknown),
                            null,
                            null);
            assertThrows(UaException.class, () -> client.read(ADDRESS));
            session.data = null;
            assertThrows(IllegalStateException.class, () -> client.read(ADDRESS));
        }
    }

    @Test
    void writeDoesNotReadBackOrDependOnSimulationWhitelistAndReturnsServiceStatus()
            throws Exception {
        var session = new Session();
        try (var client = connected(session)) {
            assertEquals(StatusCode.GOOD, client.write(ADDRESS, 12));
            assertTrue(session.reads.isEmpty());
            assertEquals(2, session.writes.size());
            session.status = new StatusCode(StatusCodes.Bad_NotWritable);
            assertThrows(UaException.class, () -> client.write(ADDRESS, 13));
            session.status = null;
            assertThrows(IllegalStateException.class, () -> client.write(ADDRESS, 14));
        }
    }

    @Test
    void duplicateConnectAndUseAfterCloseAreRejectedAndCloseIsIdempotent() throws Exception {
        var session = new Session();
        var client = connected(session);
        assertThrows(
                IllegalStateException.class,
                () -> client.connect("localhost", "other", null, null));
        client.close();
        client.close();
        assertEquals(1, session.closes);
        assertFalse(client.isConnected());
        assertThrows(IllegalStateException.class, () -> client.read(ADDRESS));
        assertThrows(IllegalStateException.class, client::endpoint);
    }

    @Test
    void failedConnectLeavesDisconnectedAndCallerSecretsIntact() throws Exception {
        char[] secret = "test-secret".toCharArray();
        try (var client =
                new UaClient(
                        config,
                        (settings, credentials) -> {
                            throw new IllegalStateException("连接失败");
                        })) {
            assertThrows(
                    IllegalStateException.class,
                    () -> client.connect("localhost", "user", secret, secret));
            assertFalse(client.isConnected());
            assertEquals("test-secret", new String(secret));
        } finally {
            Arrays.fill(secret, '\0');
        }
    }
}

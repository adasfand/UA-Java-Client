package cn.buct.ua;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.UserTokenType;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.UserTokenPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte;

class GeneralizationTest {
    private Path directory;

    @BeforeEach void prepare() throws Exception {
        directory = Files.createTempDirectory(Files.createDirectories(Path.of(".cache/tmp").toAbsolutePath()), "generalization-");
    }

    @AfterEach void clean() throws Exception {
        Path root = Path.of(".cache/tmp").toAbsolutePath().normalize();
        if (directory == null) return;
        if (!directory.normalize().startsWith(root)) throw new IllegalStateException("测试路径越界");
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private ClientSettings load(String extras) throws Exception {
        Path file = directory.resolve("client.properties");
        Files.writeString(file, """
                endpoint.url=opc.tcp://server.example:4840/ua
                security.policy=Basic256Sha256
                security.mode=SignAndEncrypt
                identity.type=username
                identity.username=test-user
                application.uri=urn:test:generalized-client
                application.name=测试客户端
                pki.directory=pki
                nodes.file=nodes.properties
                """ + extras);
        return ClientSettings.load(file);
    }

    private EndpointDescription endpoint(String url, SecurityPolicy policy, UserTokenType token) {
        return new EndpointDescription(url, null, null, MessageSecurityMode.SignAndEncrypt, policy.getUri(),
                new UserTokenPolicy[]{new UserTokenPolicy("token", token, null, null, null)}, null, ubyte(0));
    }

    @Test void independentDiscoveryAndEndpointSelectionRemainExplicit() throws Exception {
        var settings = load("endpoint.discoveryUrl=opc.tcp://192.0.2.20:4840/discovery\n");
        assertEquals("192.0.2.20", settings.discoveryEndpoint().getHost());
        assertEquals("server.example", settings.endpoint().getHost());
        var selected = endpoint(settings.endpoint().toString(), SecurityPolicy.Basic256Sha256, UserTokenType.UserName);
        assertSame(selected, settings.selectEndpoint(List.of(
                endpoint("opc.tcp://other.example:4840/ua", SecurityPolicy.Basic256Sha256, UserTokenType.UserName),
                endpoint(settings.endpoint().toString(), SecurityPolicy.Basic256, UserTokenType.UserName), selected)));
        assertThrows(IllegalArgumentException.class, () -> settings.selectEndpoint(List.of(
                endpoint(settings.endpoint().toString(), SecurityPolicy.Basic256Sha256, UserTokenType.Anonymous))));
        assertFalse(settings.acceptsEndpoint("opc.tcp://server.example:4840/ua?redirect=1"));
    }

    @Test void directConstructionCannotBypassSecurityAndAddressValidation() throws Exception {
        var s = load("");
        assertThrows(IllegalArgumentException.class, () -> new ClientSettings(s.endpoint(), SecurityPolicy.None,
                MessageSecurityMode.None, s.identityType(), s.username(), s.passwordEnv(), s.applicationUri(),
                s.applicationName(), s.timeoutMs(), s.pkiDirectory(), s.pkiPasswordEnv(), s.nodesFile(), false, "simulation"));
        assertThrows(IllegalArgumentException.class, () -> load("endpoint.url=opc.tcp://server.example:70000/ua\n"));
        assertThrows(IllegalArgumentException.class, () -> load("endpoint.discoveryUrl=opc.tcp://server.example:4840/ua#fragment\n"));
    }

    @Test void guidAndByteStringIdentifiersKeepTheirActualUaTypes() {
        String guid = "dc66de3e-137c-4f34-9bc4-aa13bcbdc127";
        var g = new PointDefinition("g", "urn:test", "g", guid, "String", false, null, null);
        assertEquals(UUID.fromString(guid), g.nodeId(4).getIdentifier());
        var b = new PointDefinition("b", "urn:test", "b", "AQID", "String", false, null, null);
        assertEquals("ns=4;b=AQID", b.nodeId(4).toParseableString());
        assertThrows(IllegalArgumentException.class, () -> new PointDefinition("g", "urn:test", "g", "1-1-1-1-1", "String", false, null, null));
        assertThrows(IllegalArgumentException.class, () -> new PointDefinition("b", "urn:test", "b", "!bad!", "String", false, null, null));
    }

    @Test void generatedIdentityHonorsConfiguredNamesAliasAndFileAndCanBeReused() throws Exception {
        var settings = load("""
                pki.file=identity.pfx
                pki.alias=custom-client
                pki.commonName=Portable Client
                pki.organization=Integration Test
                pki.dnsNames=client.example,localhost
                pki.ipAddresses=192.0.2.10,::1
                pki.validityDays=30
                """);
        char[] master = UUID.randomUUID().toString().toCharArray();
        var supplied = new AtomicReference<char[]>();
        java.util.function.BiFunction<String, String, char[]> credentials = (env, prompt) -> {
            char[] copy = master.clone(); supplied.set(copy); return copy;
        };
        try {
            LocalPki.initialize(settings, credentials);
            assertTrue(allZero(supplied.get()));
            var identity = LocalPki.load(settings, credentials);
            assertTrue(allZero(supplied.get()));
            assertNotNull(identity.keyPair().getPrivate());
            assertTrue(identity.certificate().getSubjectX500Principal().getName().contains("CN=Portable Client"));
            assertTrue(identity.certificate().getSubjectAlternativeNames().stream().anyMatch(v -> Integer.valueOf(2).equals(v.get(0)) && "client.example".equals(v.get(1))));
            assertTrue(identity.certificate().getSubjectAlternativeNames().stream().anyMatch(v -> Integer.valueOf(7).equals(v.get(0)) && "192.0.2.10".equals(v.get(1))));
            assertTrue(identity.certificate().getNotAfter().getTime() - identity.certificate().getNotBefore().getTime() < 32L * 86400000);
            var store = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(settings.pkiDirectory().resolve("identity.pfx"))) { store.load(in, master); }
            assertTrue(store.isKeyEntry("custom-client"));
            assertThrows(IllegalArgumentException.class, () -> LocalPki.initialize(settings, (env, prompt) -> { fail("不应再次索取口令"); return null; }));
            byte[] der = identity.certificate().getEncoded();
            byte[] chain = new byte[der.length * 2];
            System.arraycopy(der, 0, chain, 0, der.length);
            System.arraycopy(der, 0, chain, der.length, der.length);
            assertEquals(LocalPki.fingerprint(der), LocalPki.fingerprint(chain));
        } finally { Arrays.fill(master, '\0'); }
    }

    @Test void malformedCertificateOptionsFailBeforeGeneratingAnIdentity() {
        assertThrows(IllegalArgumentException.class, () -> load("pki.ipAddresses=not-an-ip\n"));
        assertThrows(IllegalArgumentException.class, () -> load("pki.file=../other.p12\n"));
        assertThrows(IllegalArgumentException.class, () -> load("pki.file=CLIENT.DER\n"));
        assertThrows(IllegalArgumentException.class, () -> load("pki.file=NUL.p12\n"));
        assertThrows(IllegalArgumentException.class, () -> load("pki.validityDays=0\n"));
    }

    private static boolean allZero(char[] value) {
        for (char c : value) if (c != '\0') return false;
        return true;
    }
}

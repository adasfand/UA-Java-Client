package cn.buct.ua;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;

class WriteSafetyTest {
    private ClientSettings settings(boolean enabled, String host, String environment) {
        return new ClientSettings(
                URI.create("opc.tcp://" + host + ":49320"),
                SecurityPolicy.Basic256,
                MessageSecurityMode.SignAndEncrypt,
                "username",
                "test",
                "UA_PASSWORD",
                "urn:test",
                "测试",
                10000,
                Path.of("pki"),
                "UA_KEYSTORE_PASSWORD",
                Path.of("nodes.properties"),
                enabled,
                environment);
    }

    private PointDefinition point(boolean writable) {
        return new PointDefinition(
                "test", "urn:simulator", "s", "demo", "Double", writable, "0", "100");
    }

    @Test
    void missingAnyWriteGateRejectsBeforeNetwork() {
        assertThrows(
                IllegalArgumentException.class,
                () -> settings(false, "127.0.0.1", "simulation").requireWrite(point(true), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> settings(true, "127.0.0.1", "simulation").requireWrite(point(false), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> settings(true, "127.0.0.1", "simulation").requireWrite(point(true), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> settings(true, "192.0.2.10", "simulation").requireWrite(point(true), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> settings(true, "127.0.0.1", "production").requireWrite(point(true), true));
        assertDoesNotThrow(
                () -> settings(true, "127.0.0.1", "simulation").requireWrite(point(true), true));
    }

    @Test
    void standardNamespaceNeverWritable() {
        var p =
                new PointDefinition(
                        "server",
                        "http://opcfoundation.org/UA/",
                        "i",
                        "2258",
                        "String",
                        true,
                        null,
                        null);
        assertThrows(
                IllegalArgumentException.class,
                () -> settings(true, "localhost", "simulation").requireWrite(p, true));
    }

    @Test
    void validatesRangeUnsignedAndExactType() {
        assertEquals(
                Float.class,
                new PointDefinition("p", "urn:test", "s", "p", "Float", true, "0", "100")
                        .parseWriteValue("1.25")
                        .getClass());
        assertThrows(IllegalArgumentException.class, () -> point(true).parseWriteValue("101"));
        assertThrows(NumberFormatException.class, () -> point(true).parseWriteValue("NaN"));
        var integer = new PointDefinition("p", "urn:test", "s", "p", "Int32", true, "0", "100");
        assertThrows(ArithmeticException.class, () -> integer.parseWriteValue("1.5"));
        var unsigned = new PointDefinition("p", "urn:test", "s", "p", "UInt16", true, "0", "70000");
        assertThrows(NumberFormatException.class, () -> unsigned.parseWriteValue("65536"));
        var bool = new PointDefinition("p", "urn:test", "s", "p", "Boolean", true, null, null);
        assertThrows(IllegalArgumentException.class, () -> bool.parseWriteValue("1"));
        assertEquals(Boolean.TRUE, bool.parseWriteValue("true"));
    }

    @Test
    void namespaceResolutionUsesSuppliedIndexAndUnsignedIdentifier() {
        var p =
                new PointDefinition(
                        "p", "urn:test", "i", "4294967295", "UInt32", false, null, null);
        assertEquals(7, p.nodeId(7).getNamespaceIndex().intValue());
        assertEquals("4294967295", p.nodeId(7).getIdentifier().toString());
    }

    @Test
    void advertisedEndpointCannotRedirectLocalWriteToAnotherHostOrPort() {
        var s = settings(true, "127.0.0.1", "simulation");
        assertTrue(s.acceptsEndpoint("opc.tcp://127.0.0.1:49320"));
        assertFalse(s.acceptsEndpoint("opc.tcp://192.0.2.10:49320"));
        assertFalse(s.acceptsEndpoint("opc.tcp://127.0.0.1:4840"));
    }

    @Test
    void floatingPointConversionCannotBypassRangeOrUnderflowToZero() {
        var rounded =
                new PointDefinition(
                        "p", "urn:test", "s", "p", "Float", true, "16777217", "16777220");
        assertThrows(IllegalArgumentException.class, () -> rounded.parseWriteValue("16777217"));
        var tiny = new PointDefinition("p", "urn:test", "s", "p", "Float", true, "0", "1");
        assertThrows(IllegalArgumentException.class, () -> tiny.parseWriteValue("1e-50"));
    }

    @Test
    void templateIsReadableAndWritesDisabled() throws Exception {
        var config = ClientSettings.load(Path.of("config/templates/client.properties"));
        assertFalse(config.writesEnabled());
        assertEquals("serverTime", config.points().getFirst().alias());
        assertFalse(config.points().getFirst().writable());
    }
}

package cn.buct.ua;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;

class ClientSettingsTest {
    private ClientSettings base() {
        return new ClientSettings(
                URI.create("opc.tcp://server.example:4840/ua"),
                SecurityPolicy.Basic256,
                MessageSecurityMode.SignAndEncrypt,
                "username",
                "original-user",
                "UA_PASSWORD",
                "urn:test:desktop",
                "测试客户端",
                1000,
                Path.of("pki"),
                "UA_KEYSTORE_PASSWORD",
                URI.create("opc.tcp://server.example:4840/ua"),
                ClientSettings.PkiSettings.defaults("测试客户端"),
                "strict");
    }

    @Test
    void changingRemoteHostPreservesPortAndPathAndSupportsIpv6() throws Exception {
        assertEquals(
                "opc.tcp://192.0.2.10:4840/ua",
                ClientSettings.withHost(base().endpoint().toString(), "192.0.2.10").toString());
        assertEquals(
                "opc.tcp://[::1]:4840/ua",
                ClientSettings.withHost(base().endpoint().toString(), "::1").toString());
        assertEquals(
                "opc.tcp://192.0.2.10:4840/ua%2Fserver",
                ClientSettings.withHost("opc.tcp://server.example:4840/ua%2Fserver", "192.0.2.10")
                        .toString());
    }

    @Test
    void rejectsIncompleteOrCredentialBearingAddressesBeforeConnecting() {
        for (String invalid :
                new String[] {
                    "127.0.0.1",
                    "opc.tcp://localhost",
                    "opc.tcp://localhost:65536",
                    "http://localhost:4840",
                    "opc.tcp://user:secret@localhost:4840",
                    "opc.tcp://localhost:4840/?secret=x"
                }) {
            assertThrows(
                    IllegalArgumentException.class, () -> ClientSettings.parseEndpoint(invalid));
        }
    }

    @Test
    void connectionChangesAddressAndUsernameWithoutChangingConfiguredSecurityOrPki()
            throws Exception {
        var base = base();
        var url = URI.create("opc.tcp://other.example:49320/UA");
        var settings = base.withConnection(url.toString(), null, " new-user ");
        assertEquals(url, settings.endpoint());
        assertEquals(url, settings.discoveryEndpoint());
        assertEquals(base.policy(), settings.policy());
        assertEquals(base.mode(), settings.mode());
        assertEquals("new-user", settings.username());
        assertEquals(base.pki(), settings.pki());
        assertEquals(base.pkiDirectory(), settings.pkiDirectory());
        assertEquals(base.applicationUri(), settings.applicationUri());
        assertEquals(base.serverTrust(), settings.serverTrust());
        assertEquals("original-user", base.username());
    }

    @Test
    void directConnectionKeepsExplicitConfiguredSecurity() throws Exception {
        var base = base();
        var settings = base.withConnection(null, null, "user");
        assertEquals(base.policy(), settings.policy());
        assertEquals(base.mode(), settings.mode());
    }
}

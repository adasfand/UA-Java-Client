package cn.buct.ua;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;

class DesktopEndpointTest {
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
                PkiSettings.defaults("测试客户端"),
                "strict");
    }

    @Test
    void changingRemoteHostPreservesPortAndPathAndSupportsIpv6() throws Exception {
        assertEquals(
                "opc.tcp://192.0.2.10:4840/ua",
                DesktopEndpoint.withHost(base().endpoint().toString(), "192.0.2.10").toString());
        assertEquals(
                "opc.tcp://[::1]:4840/ua",
                DesktopEndpoint.withHost(base().endpoint().toString(), "::1").toString());
        assertEquals(
                "opc.tcp://192.0.2.10:4840/ua%2Fserver",
                DesktopEndpoint.withHost("opc.tcp://server.example:4840/ua%2Fserver", "192.0.2.10")
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
            assertThrows(IllegalArgumentException.class, () -> DesktopEndpoint.address(invalid));
        }
    }

    @Test
    void connectionChangesAddressAndUsernameWithoutChangingConfiguredSecurityOrPki() {
        var base = base();
        var url = URI.create("opc.tcp://other.example:49320/UA");
        var discovery = URI.create("opc.tcp://192.0.2.10:49320");
        var settings = DesktopEndpoint.connection(base, url, discovery, " new-user ");
        assertEquals(url, settings.endpoint());
        assertEquals(discovery, settings.discoveryEndpoint());
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
    void directConnectionKeepsExplicitConfiguredSecurity() {
        var base = base();
        var settings =
                DesktopEndpoint.connection(base, base.endpoint(), base.discoveryEndpoint(), "user");
        assertEquals(base.policy(), settings.policy());
        assertEquals(base.mode(), settings.mode());
    }
}

package cn.buct.ua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte;
import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.UserTokenType;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.UserTokenPolicy;
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
                Path.of("nodes.properties"),
                true,
                "simulation");
    }

    private EndpointDescription endpoint(
            String url, SecurityPolicy policy, MessageSecurityMode mode, UserTokenType token) {
        return new EndpointDescription(
                url,
                null,
                null,
                mode,
                policy.getUri(),
                new UserTokenPolicy[] {new UserTokenPolicy("identity", token, null, null, null)},
                null,
                ubyte(0));
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
    void selectedEndpointOverridesPolicyWithoutChangingIdentityOrPkiAndDisablesWrites() {
        var base = base();
        var url = URI.create("opc.tcp://other.example:49320/UA");
        var discovery = URI.create("opc.tcp://192.0.2.10:49320");
        var selected =
                endpoint(
                        url.toString(),
                        SecurityPolicy.Basic256Sha256,
                        MessageSecurityMode.SignAndEncrypt,
                        UserTokenType.UserName);
        var settings = DesktopEndpoint.connection(base, url, discovery, selected, " new-user ");
        assertEquals(url, settings.endpoint());
        assertEquals(discovery, settings.discoveryEndpoint());
        assertEquals(SecurityPolicy.Basic256Sha256, settings.policy());
        assertEquals("new-user", settings.username());
        assertEquals(base.pki(), settings.pki());
        assertEquals(base.pkiDirectory(), settings.pkiDirectory());
        assertEquals(base.applicationUri(), settings.applicationUri());
        assertEquals(base.serverTrust(), settings.serverTrust());
        assertFalse(settings.writesEnabled());
        assertTrue(base.writesEnabled());
        assertEquals("original-user", base.username());
    }

    @Test
    void rejectsUnsupportedSecurityAndIdentityBeforePasswordPrompt() {
        var base = base();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DesktopEndpoint.connection(
                                base,
                                base.endpoint(),
                                base.discoveryEndpoint(),
                                endpoint(
                                        base.endpoint().toString(),
                                        SecurityPolicy.None,
                                        MessageSecurityMode.None,
                                        UserTokenType.UserName),
                                "user"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DesktopEndpoint.connection(
                                base,
                                base.endpoint(),
                                base.discoveryEndpoint(),
                                endpoint(
                                        base.endpoint().toString(),
                                        SecurityPolicy.Basic256,
                                        MessageSecurityMode.SignAndEncrypt,
                                        UserTokenType.Anonymous),
                                "user"));
    }

    @Test
    void rejectsStaleSelectionAfterAddressChanges() {
        var base = base();
        var selected =
                endpoint(
                        base.endpoint().toString(),
                        SecurityPolicy.Basic256,
                        MessageSecurityMode.SignAndEncrypt,
                        UserTokenType.UserName);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DesktopEndpoint.connection(
                                base,
                                URI.create("opc.tcp://other.example:4840"),
                                base.discoveryEndpoint(),
                                selected,
                                "user"));
    }

    @Test
    void directConnectionKeepsExplicitConfiguredSecurity() {
        var base = base();
        var settings =
                DesktopEndpoint.connection(
                        base, base.endpoint(), base.discoveryEndpoint(), null, "user");
        assertEquals(base.policy(), settings.policy());
        assertEquals(base.mode(), settings.mode());
        assertFalse(settings.writesEnabled());
    }
}

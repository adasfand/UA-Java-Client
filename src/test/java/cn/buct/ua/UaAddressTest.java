package cn.buct.ua;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.junit.jupiter.api.Test;

class UaAddressTest {
    @Test
    void namespaceUriResolvesCurrentServerIndexInsteadOfAssumingTwo() {
        var first = new NamespaceTable("http://opcfoundation.org/UA/", "urn:other", "KEPServerEX");
        var second = new NamespaceTable("http://opcfoundation.org/UA/", "KEPServerEX");
        assertEquals(
                new NodeId(2, "Channel.Device.Tag"),
                UaAddress.resolve("nsu=KEPServerEX;s=Channel.Device.Tag", first));
        assertEquals(
                new NodeId(1, "Channel.Device.Tag"),
                UaAddress.resolve("nsu=KEPServerEX;s=Channel.Device.Tag", second));
    }

    @Test
    void numericAndStringIdentifiersIncludingSpacesArePreserved() {
        var namespaces = new NamespaceTable("http://opcfoundation.org/UA/", "KEPServerEX");
        assertEquals(new NodeId(0, 2258), UaAddress.resolve("ns=0;i=2258", namespaces));
        assertEquals(
                "A/Tag 中文;with space ",
                UaAddress.resolve("ns=1;s=A/Tag 中文;with space ", namespaces).getIdentifier());
    }

    @Test
    void rawDaIdsUnknownNamespacesAndCrossServerAddressesAreRejected() {
        var namespaces = new NamespaceTable();
        assertThrows(
                IllegalArgumentException.class, () -> UaAddress.parse("A151TT_06102A/ALM1/PV.CV"));
        assertThrows(IllegalArgumentException.class, () -> UaAddress.parse(""));
        assertThrows(IllegalArgumentException.class, () -> UaAddress.parse("svr=1;ns=2;s=tag"));
        assertThrows(
                IllegalArgumentException.class,
                () -> UaAddress.resolve("nsu=urn:missing;s=tag", namespaces));
    }
}

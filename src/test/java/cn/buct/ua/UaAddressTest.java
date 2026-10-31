package cn.buct.ua;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.junit.jupiter.api.Test;

class UaAddressTest {
    @Test
    void importedAddressesSplitAndRoundTripWithoutCorruptingStringIdentifiers() {
        for (String address :
                new String[] {
                    "ns=0;i=2258",
                    "nsu=KEPServerEX;s=WriteDemo.Device1.sim",
                    "ns=2;s=Tag;with space ",
                    "s=DefaultNamespaceTag",
                    "svr=0;ns=2;s=Tag"
                }) {
            String[] parts = UaAddress.parts(address);
            assertEquals(
                    UaAddress.parse(address),
                    UaAddress.parse(UaAddress.fromParts(parts[0], parts[1])));
        }
    }

    @Test
    void splitInputsResolveTheSameNodeAndPreserveIdentifierText() {
        var namespaces = new NamespaceTable("http://opcfoundation.org/UA/", "KEPServerEX");
        assertEquals(
                new NodeId(0, 2258),
                UaAddress.resolve(UaAddress.fromParts("ns=0", "i=2258"), namespaces));
        assertEquals(
                new NodeId(1, "Tag;with space "),
                UaAddress.resolve(
                        UaAddress.fromParts("nsu=KEPServerEX", "s=Tag;with space "), namespaces));
        assertThrows(
                IllegalArgumentException.class,
                () -> UaAddress.fromParts("nsu=KEPServerEX;s=other", "s=tag"));
        assertThrows(
                IllegalArgumentException.class,
                () -> UaAddress.fromParts("nsu=KEPServerEX", "K0001"));
        assertThrows(IllegalArgumentException.class, () -> UaAddress.fromParts("", "s=tag"));
    }

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

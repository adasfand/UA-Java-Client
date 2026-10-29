package cn.buct.ua;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class DeviceTagViewTest {
    private PointDiscovery.Node node(String id, String name, NodeClass type) {
        return new PointDiscovery.Node(new NodeId(2, id), "KEPServerEX", name, type);
    }

    @Test
    void hidesKepSystemStatisticsPluginsAndDynamicTemplateBranches() {
        for (String id :
                List.of(
                        "_System",
                        "_AdvancedTags",
                        "channel._Statistics",
                        "channel.device._System._Enabled",
                        "channel.device._DynamicTags.USER")) {
            assertFalse(DeviceTagView.visible(node(id, "Friendly name", NodeClass.Object)), id);
        }
        assertFalse(
                DeviceTagView.visible(node("channel.device.tag", "_Enabled", NodeClass.Variable)));
    }

    @Test
    void keepsConfiguredChannelsGroupsAndTagsWithoutHardcodingTheirNames() {
        assertTrue(DeviceTagView.visible(node("模拟器示例", "模拟器示例", NodeClass.Object)));
        assertTrue(DeviceTagView.visible(node("用户通道.设备.温度", "温度", NodeClass.Variable)));
        assertTrue(DeviceTagView.visible(node("channel.group.Ramp1", "Ramp1", NodeClass.Variable)));
    }

    @Test
    void doesNotApplyKepNamingRulesToOtherVendors() {
        var other =
                new PointDiscovery.Node(
                        new NodeId(4, "_temperature"),
                        "urn:other:vendor",
                        "_temperature",
                        NodeClass.Variable);
        assertTrue(DeviceTagView.visible(other));
        assertFalse(
                DeviceTagView.visible(
                        new PointDiscovery.Node(
                                NodeIds.Server,
                                "http://opcfoundation.org/UA/",
                                "Server",
                                NodeClass.Object)));
    }

    @Test
    void deviceViewDoesNotRecurseOrReadHiddenNodeMetadata() throws Exception {
        var root = node("channel.device", "device", NodeClass.Object);
        var tag = node("channel.device.Ramp1", "Ramp1", NodeClass.Variable);
        var systemTag = node("channel.device._Enabled", "_Enabled", NodeClass.Variable);
        var group = node("channel.device.group", "group", NodeClass.Object);
        var described = new ArrayList<NodeId>();
        var browsed = new ArrayList<NodeId>();
        var source =
                new PointDiscovery.Source() {
                    public List<PointDiscovery.Node> children(NodeId parent) {
                        browsed.add(parent);
                        assertEquals(root.nodeId(), parent);
                        return List.of(tag, systemTag, group);
                    }

                    public DiscoveredPoint describe(PointDiscovery.Node node) {
                        described.add(node.nodeId());
                        return new DiscoveredPoint(
                                node.name(),
                                node.namespaceUri(),
                                "s",
                                node.nodeId().getIdentifier().toString(),
                                "Int32",
                                -1,
                                true,
                                false);
                    }
                };
        var result =
                PointDiscovery.scan(
                        DeviceTagView.directTags(source), root, 2, 100, 1000, () -> false);
        assertEquals(1, result.points().size());
        assertEquals("Ramp1", result.points().getFirst().displayName());
        assertEquals(List.of(root.nodeId()), browsed);
        assertEquals(List.of(tag.nodeId()), described);
        assertTrue(result.notices().isEmpty());
    }

    @Test
    void openingRootDoesNotScanAllDevices() throws Exception {
        var source =
                new PointDiscovery.Source() {
                    public List<PointDiscovery.Node> children(NodeId parent) {
                        fail("根视图不应扫描全服务器");
                        return List.of();
                    }

                    public DiscoveredPoint describe(PointDiscovery.Node node) {
                        fail("根视图不应读取属性");
                        return null;
                    }
                };
        var root =
                new PointDiscovery.Node(
                        NodeIds.ObjectsFolder,
                        "http://opcfoundation.org/UA/",
                        "连接性",
                        NodeClass.Object);
        var result =
                PointDiscovery.scan(
                        DeviceTagView.directTags(source), root, 2, 100, 1000, () -> false);
        assertTrue(result.points().isEmpty());
        assertTrue(result.notices().isEmpty());
    }
}

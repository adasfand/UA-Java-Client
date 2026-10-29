package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;

/** KEPServerEX 普通设备视图；通用浏览接口仍保留完整节点访问能力。 */
final class DeviceTagView {
    private DeviceTagView() {}

    static boolean visible(PointDiscovery.Node node) {
        if (node.nodeId().getNamespaceIndex().intValue() == 0) return false;
        if (!"KEPServerEX".equals(node.namespaceUri())) return true;
        // KEP 内置服务、统计及动态地址模板通过保留的下划线分支公开。
        if (node.nodeId().getIdentifier() instanceof String identifier) {
            for (String segment : identifier.split("\\.")) {
                if (segment.startsWith("_")) return false;
            }
        }
        return !node.name().startsWith("_");
    }

    static PointDiscovery.Source directTags(PointDiscovery.Source source) {
        return new PointDiscovery.Source() {
            @Override
            public java.util.List<PointDiscovery.Node> children(
                    org.eclipse.milo.opcua.stack.core.types.builtin.NodeId parent)
                    throws Exception {
                if (parent.equals(NodeIds.ObjectsFolder)) return java.util.List.of();
                return source.children(parent).stream()
                        .filter(DeviceTagView::visible)
                        .filter(node -> node.nodeClass() == NodeClass.Variable)
                        .toList();
            }

            @Override
            public DiscoveredPoint describe(PointDiscovery.Node node) throws Exception {
                return source.describe(node);
            }
        };
    }
}

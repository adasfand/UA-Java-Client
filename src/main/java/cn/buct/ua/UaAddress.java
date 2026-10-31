package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.ExpandedNodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

/** 现场提供的 UA 地址；不从 OPC DA ItemId 猜测 namespace index。 */
final class UaAddress {
    private UaAddress() {}

    /** 桌面分栏输入；标识符文本原样保留，避免改变含空格的字符串节点。 */
    static String fromParts(String namespace, String identifier) {
        String space = namespace == null ? "" : namespace.trim();
        if (!(space.matches("ns=[0-9]+")
                || (space.startsWith("nsu=") && space.length() > 4 && !space.contains(";"))))
            throw new IllegalArgumentException("命名空间请填 nsu=实际URI 或 ns=数字，例如 nsu=KEPServerEX。");
        if (identifier == null
                || identifier.length() < 3
                || identifier.charAt(1) != '='
                || "isgb".indexOf(identifier.charAt(0)) < 0)
            throw new IllegalArgumentException("节点标识请包含类型前缀，例如 s=WriteDemo.Device1.sim 或 i=2258。");
        String address = space + ";" + identifier;
        parse(address);
        return address;
    }

    static ExpandedNodeId parse(String address) {
        if (address == null || address.isBlank())
            throw new IllegalArgumentException("请提供 OPC UA NodeId 地址");
        final ExpandedNodeId parsed;
        try {
            parsed = ExpandedNodeId.parse(address);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "地址必须是 OPC UA NodeId，例如 ns=2;s=Channel.Device.Tag 或"
                            + " nsu=KEPServerEX;s=Channel.Device.Tag",
                    e);
        }
        if (!parsed.isLocal() || parsed.isNull())
            throw new IllegalArgumentException("请提供当前已连接服务器上的非空 NodeId，不支持跨服务器地址");
        return parsed;
    }

    static NodeId resolve(String address, NamespaceTable namespaces) {
        return parse(address)
                .toNodeId(namespaces)
                .orElseThrow(() -> new IllegalArgumentException("服务器没有提供地址指定的命名空间，请向现场核对 NodeId"));
    }
}

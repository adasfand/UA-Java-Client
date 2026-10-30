package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.ExpandedNodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

/** 现场提供的 UA 地址；不从 OPC DA ItemId 猜测 namespace index。 */
final class UaAddress {
    private UaAddress() {}

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

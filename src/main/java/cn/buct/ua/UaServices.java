package cn.buct.ua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

import org.eclipse.milo.opcua.sdk.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.Stack;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransport;
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransportConfig;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 有期限的端点发现；供连接过程选择匹配的安全策略。 */
public final class UaServices {
    private static final long MAX_CLEANUP_TIMEOUT_MS = 5000;

    private UaServices() {}

    public static List<EndpointDescription> discover(String url, long timeoutMs) throws Exception {
        long deadline = deadline(timeoutMs);
        var endpoint =
                new EndpointDescription(
                        url,
                        null,
                        null,
                        MessageSecurityMode.None,
                        SecurityPolicy.None.getUri(),
                        null,
                        Stack.TCP_UASC_UABINARY_TRANSPORT_URI,
                        ubyte(0));
        var transport =
                new OpcTcpClientTransport(
                        OpcTcpClientTransportConfig.newBuilder()
                                .setConnectTimeout(uint(timeoutMs))
                                .setAcknowledgeTimeout(uint(timeoutMs))
                                .build());
        var discovery = new DiscoveryClient(endpoint, transport);
        Exception failure = null;
        try {
            await(discovery.connectAsync(), deadline);
            var response =
                    await(
                            discovery.getEndpoints(
                                    url,
                                    new String[0],
                                    new String[] {Stack.TCP_UASC_UABINARY_TRANSPORT_URI}),
                            deadline);
            var endpoints = response.getEndpoints();
            return endpoints == null ? List.of() : List.copyOf(Arrays.asList(endpoints));
        } catch (Exception e) {
            failure = e;
            throw e;
        } finally {
            // 清理有独立的短期限；调用超时也必须发起断开，而非等待请求自行结束。
            try {
                discovery.disconnectAsync().get(cleanupTimeout(timeoutMs), TimeUnit.MILLISECONDS);
            } catch (Exception cleanup) {
                if (cleanup instanceof InterruptedException) Thread.currentThread().interrupt();
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }

    private static long deadline(long timeoutMs) {
        if (timeoutMs < 1 || timeoutMs > 120_000)
            throw new IllegalArgumentException("服务超时应为 1—120000 毫秒");
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    }

    private static long cleanupTimeout(long timeoutMs) {
        return Math.min(timeoutMs, MAX_CLEANUP_TIMEOUT_MS);
    }

    private static <T> T await(CompletableFuture<T> future, long deadline) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("OPC UA 操作超过调用期限");
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }
}

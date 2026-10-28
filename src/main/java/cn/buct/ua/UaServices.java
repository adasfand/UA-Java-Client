package cn.buct.ua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

import org.eclipse.milo.opcua.sdk.client.DiscoveryClient;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.Stack;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseResultMask;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.ViewDescription;
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransport;
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransportConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 有期限的发现与逐页浏览；不建立认证会话，不自动信任发现证书。 */
public final class UaServices {
    private static final int MAX_PAGE_SIZE = 100;
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

    public static List<ReferenceDescription> browse(
            OpcUaClient client, NodeId parent, long timeoutMs, int remainingLimit)
            throws Exception {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(parent, "parent");
        var description =
                new BrowseDescription(
                        parent,
                        BrowseDirection.Forward,
                        NodeIds.HierarchicalReferences,
                        true,
                        uint(0),
                        uint(BrowseResultMask.All.getValue()));
        return browse(
                new BrowsePages() {
                    @Override
                    public CompletableFuture<BrowseResult> first(int pageSize) {
                        return client.browseAsync(
                                        new ViewDescription(
                                                NodeId.NULL_VALUE, DateTime.MIN_VALUE, uint(0)),
                                        uint(pageSize),
                                        List.of(description))
                                .thenApply(r -> single(r.getResults()));
                    }

                    @Override
                    public CompletableFuture<BrowseResult> next(boolean release, ByteString point) {
                        return client.browseNextAsync(release, List.of(point))
                                .thenApply(r -> release ? null : single(r.getResults()));
                    }
                },
                timeoutMs,
                remainingLimit);
    }

    /** 小型请求接口供离线测试复用真实分页和清理逻辑。 */
    interface BrowsePages {
        CompletableFuture<BrowseResult> first(int pageSize);

        CompletableFuture<BrowseResult> next(boolean release, ByteString continuationPoint);
    }

    static List<ReferenceDescription> browse(BrowsePages pages, long timeoutMs, int remainingLimit)
            throws Exception {
        long deadline = deadline(timeoutMs);
        if (remainingLimit < 1) throw new IllegalArgumentException("浏览引用额度必须大于零");
        List<ReferenceDescription> references = new ArrayList<>();
        ByteString continuation = null;
        Exception failure = null;
        try {
            BrowseResult page =
                    await(pages.first(Math.min(MAX_PAGE_SIZE, remainingLimit)), deadline);
            while (true) {
                if (page == null) throw new IllegalStateException("浏览未返回节点结果");
                // 错误页可能没有返回新游标；此时仍保留上一页已知游标供 finally 释放。
                ByteString nextPoint = page.getContinuationPoint();
                if (hasPoint(nextPoint)) continuation = nextPoint;
                if (page.getStatusCode() == null) throw new IllegalStateException("浏览结果缺少状态码");
                if (!page.getStatusCode().isGood()) throw new UaException(page.getStatusCode());
                continuation = nextPoint;
                var batch = page.getReferences();
                if (batch != null) {
                    if (batch.length > remainingLimit - references.size())
                        throw limitReached(remainingLimit);
                    references.addAll(Arrays.asList(batch));
                }
                if (!hasPoint(continuation)) return List.copyOf(references);
                if (references.size() >= remainingLimit) throw limitReached(remainingLimit);
                page = await(pages.next(false, continuation), deadline);
            }
        } catch (Exception e) {
            failure = e;
            throw e;
        } finally {
            if (hasPoint(continuation)) {
                try {
                    pages.next(true, continuation)
                            .get(cleanupTimeout(timeoutMs), TimeUnit.MILLISECONDS);
                } catch (Exception cleanup) {
                    if (cleanup instanceof InterruptedException) Thread.currentThread().interrupt();
                    if (failure != null) failure.addSuppressed(cleanup);
                    else throw cleanup;
                }
            }
        }
    }

    private static BrowseResult single(BrowseResult[] results) {
        if (results == null || results.length != 1 || results[0] == null)
            throw new IllegalStateException("浏览返回数量与单节点请求不一致");
        return results[0];
    }

    private static boolean hasPoint(ByteString point) {
        return point != null && !point.isNullOrEmpty();
    }

    private static IllegalStateException limitReached(int limit) {
        return new IllegalStateException("浏览已达 " + limit + " 个剩余引用上限，请指定更具体的根节点；结果不完整");
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

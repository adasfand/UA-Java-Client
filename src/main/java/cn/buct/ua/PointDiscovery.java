package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** 与 UI、传输分离的有界节点扫描。深度包含根节点，根位于第 1 层。 */
final class PointDiscovery {
    private PointDiscovery() {}

    interface Source {
        List<Node> children(NodeId parent) throws Exception;

        DiscoveredPoint describe(Node node) throws Exception;
    }

    record Node(NodeId nodeId, String namespaceUri, String name, NodeClass nodeClass) {
        Node {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(namespaceUri, "namespaceUri");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(nodeClass, "nodeClass");
        }

        @Override
        public String toString() {
            return name;
        }
    }

    record Result(List<DiscoveredPoint> points, List<String> notices) {
        Result {
            points = List.copyOf(points);
            notices = List.copyOf(notices);
        }
    }

    /**
     * 只把 Variable 描述为点位，沿 Object 展开；以 ObjectsFolder 为起点时跳过标准命名空间子树。 Source
     * 自身必须限制单次请求的等待时间，本方法在每次请求前后检查整个扫描期限。
     */
    static Result scan(
            Source source,
            Node root,
            int maxDepth,
            int maxReferences,
            long timeoutMs,
            BooleanSupplier cancelled)
            throws Exception {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(cancelled, "cancelled");
        if (maxDepth < 1
                || maxDepth > 100
                || maxReferences < 1
                || maxReferences > 100_000
                || timeoutMs < 1
                || timeoutMs > 3_600_000) {
            throw new IllegalArgumentException("扫描深度、引用额度或总超时不在允许范围内");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        record Pending(Node node, int depth) {}
        var pending = new ArrayDeque<Pending>();
        var visited = new HashSet<NodeId>();
        var points = new LinkedHashMap<String, DiscoveredPoint>();
        var notices = new ArrayList<String>();
        boolean applicationsOnly = root.nodeId().equals(NodeIds.ObjectsFolder);
        int references = 0;
        boolean referenceLimitReached = false;
        pending.add(new Pending(root, 1));
        while (!pending.isEmpty()) {
            checkBudget(deadline, cancelled);
            var current = pending.removeFirst();
            Node node = current.node();
            if (!visited.add(node.nodeId())) continue;
            if (node.nodeClass() == NodeClass.Variable) {
                try {
                    DiscoveredPoint point = Objects.requireNonNull(source.describe(node));
                    checkBudget(deadline, cancelled);
                    points.putIfAbsent(point.key(), point);
                } catch (Exception e) {
                    preserveCancellation(e);
                    checkBudget(deadline, cancelled);
                    notices.add(
                            "变量属性未完整读取：" + node.name() + "（" + e.getClass().getSimpleName() + "）");
                }
                continue;
            }
            if (node.nodeClass() != NodeClass.Object) continue;
            if (referenceLimitReached) continue;
            if (current.depth() >= maxDepth) {
                notices.add("已达扫描深度上限，尚未展开：" + node.name() + "；结果不完整。");
                continue;
            }
            if (references >= maxReferences) {
                notices.add("已达扫描引用上限 " + maxReferences + "；请缩小到具体设备，结果不完整。");
                referenceLimitReached = true;
                continue;
            }
            List<Node> children;
            try {
                children = Objects.requireNonNull(source.children(node.nodeId()));
                checkBudget(deadline, cancelled);
            } catch (Exception e) {
                preserveCancellation(e);
                checkBudget(deadline, cancelled);
                notices.add("分支未完整浏览：" + node.name() + "（" + e.getClass().getSimpleName() + "）");
                continue;
            }
            for (Node child : children) {
                checkBudget(deadline, cancelled);
                if (references >= maxReferences) {
                    notices.add("已达扫描引用上限 " + maxReferences + "；请缩小到具体设备，结果不完整。");
                    // 已接受的变量仍可描述，后续不再展开其他对象。
                    referenceLimitReached = true;
                    break;
                }
                references++;
                if (applicationsOnly && child.nodeId().getNamespaceIndex().intValue() == 0)
                    continue;
                pending.addLast(new Pending(child, current.depth() + 1));
            }
        }
        checkBudget(deadline, cancelled);
        return new Result(new ArrayList<>(points.values()), notices);
    }

    private static void checkBudget(long deadline, BooleanSupplier cancelled)
            throws TimeoutException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("操作者取消了点位扫描");
        }
        if (System.nanoTime() >= deadline) throw new TimeoutException("点位扫描超过总等待期限");
    }

    private static void preserveCancellation(Exception e) throws Exception {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            throw e;
        }
        if (e instanceof CancellationException) throw e;
    }
}

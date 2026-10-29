package cn.buct.ua;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

class PointDiscoveryTest {
    private static PointDiscovery.Node object(String name) {
        return new PointDiscovery.Node(
                new NodeId(2, name), "urn:test:device", name, NodeClass.Object);
    }

    private static PointDiscovery.Node variable(String name) {
        return new PointDiscovery.Node(
                new NodeId(2, name), "urn:test:device", name, NodeClass.Variable);
    }

    private static DiscoveredPoint point(PointDiscovery.Node node) {
        return new DiscoveredPoint(
                node.name(),
                node.namespaceUri(),
                "s",
                node.nodeId().getIdentifier().toString(),
                "Double",
                -1,
                true,
                false);
    }

    private static class Source implements PointDiscovery.Source {
        final Map<NodeId, List<PointDiscovery.Node>> children = new HashMap<>();
        final Set<NodeId> failedBranches = new HashSet<>();
        final Set<NodeId> failedVariables = new HashSet<>();
        final List<NodeId> browsed = new ArrayList<>();
        final List<NodeId> described = new ArrayList<>();

        @Override
        public List<PointDiscovery.Node> children(NodeId parent) {
            browsed.add(parent);
            if (failedBranches.contains(parent)) throw new IllegalArgumentException("unavailable");
            return children.getOrDefault(parent, List.of());
        }

        @Override
        public DiscoveredPoint describe(PointDiscovery.Node node) {
            described.add(node.nodeId());
            if (failedVariables.contains(node.nodeId()))
                throw new IllegalArgumentException("denied");
            return point(node);
        }
    }

    @Test
    void scansApplicationBranchesAndAvoidsCyclesAndRepeatedReferences() throws Exception {
        var root = object("root");
        var device = object("device");
        var variable = variable("温度");
        var source = new Source();
        source.children.put(root.nodeId(), List.of(device));
        source.children.put(device.nodeId(), List.of(root, variable, variable));
        var result = PointDiscovery.scan(source, root, 6, 100, 1000, () -> false);
        assertEquals(List.of(point(variable)), result.points());
        assertEquals(List.of(root.nodeId(), device.nodeId()), source.browsed);
        assertEquals(List.of(variable.nodeId()), source.described);
        assertTrue(result.notices().isEmpty());
    }

    @Test
    void defaultObjectsFolderSkipsStandardServerSubtreeButExplicitRootsAllowIt() throws Exception {
        var root =
                new PointDiscovery.Node(
                        NodeIds.ObjectsFolder,
                        "http://opcfoundation.org/UA/",
                        "Objects",
                        NodeClass.Object);
        var server =
                new PointDiscovery.Node(
                        NodeIds.Server, "http://opcfoundation.org/UA/", "Server", NodeClass.Object);
        var standardVariable =
                new PointDiscovery.Node(
                        new NodeId(0, 2258),
                        "http://opcfoundation.org/UA/",
                        "CurrentTime",
                        NodeClass.Variable);
        var application = variable("application");
        var source = new Source();
        source.children.put(root.nodeId(), List.of(server, application));
        source.children.put(server.nodeId(), List.of(standardVariable));
        var first = PointDiscovery.scan(source, root, 6, 100, 1000, () -> false);
        assertEquals(List.of(point(application)), first.points());
        assertFalse(source.browsed.contains(server.nodeId()));
        var explicit = PointDiscovery.scan(source, server, 6, 100, 1000, () -> false);
        assertEquals(List.of(point(standardVariable)), explicit.points());
    }

    @Test
    void depthLimitReportsUnexpandedBranchAndKeepsAlreadyDescribedVariables() throws Exception {
        var root = object("root");
        var branch = object("branch");
        var visible = variable("visible");
        var deeper = variable("deeper");
        var source = new Source();
        source.children.put(root.nodeId(), List.of(visible, branch));
        source.children.put(branch.nodeId(), List.of(deeper));
        var result = PointDiscovery.scan(source, root, 2, 100, 1000, () -> false);
        assertEquals(List.of(point(visible)), result.points());
        assertFalse(source.browsed.contains(branch.nodeId()));
        assertTrue(result.notices().stream().anyMatch(s -> s.contains("深度上限")));
    }

    @Test
    void referenceLimitReturnsAnExplicitlyIncompleteSubsetWithoutFurtherBrowsing()
            throws Exception {
        var root = object("root");
        var first = variable("first");
        var second = variable("second");
        var source = new Source();
        source.children.put(root.nodeId(), List.of(first, second));
        var result = PointDiscovery.scan(source, root, 3, 1, 1000, () -> false);
        assertEquals(List.of(point(first)), result.points());
        assertEquals(List.of(root.nodeId()), source.browsed);
        assertTrue(result.notices().stream().anyMatch(s -> s.contains("引用上限")));
    }

    @Test
    void inaccessibleBranchesAndMetadataProduceNoticesWhileOtherPointsRemainAvailable()
            throws Exception {
        var root = object("root");
        var branch = object("denied branch");
        var denied = variable("denied variable");
        var available = variable("available");
        var source = new Source();
        source.children.put(root.nodeId(), List.of(branch, denied, available));
        source.failedBranches.add(branch.nodeId());
        source.failedVariables.add(denied.nodeId());
        var result = PointDiscovery.scan(source, root, 6, 100, 1000, () -> false);
        assertEquals(List.of(point(available)), result.points());
        assertEquals(2, result.notices().size());
    }

    @Test
    void cancellationDuringSourceRequestDoesNotReturnPartialResults() {
        var cancelled = new AtomicBoolean();
        var source =
                new Source() {
                    @Override
                    public List<PointDiscovery.Node> children(NodeId parent) {
                        cancelled.set(true);
                        return List.of(variable("late"));
                    }
                };
        assertThrows(
                CancellationException.class,
                () -> PointDiscovery.scan(source, object("root"), 6, 100, 1000, cancelled::get));
    }

    @Test
    void totalTimeoutIsCheckedAfterSourceReturns() {
        var source =
                new Source() {
                    @Override
                    public List<PointDiscovery.Node> children(NodeId parent) {
                        try {
                            Thread.sleep(25);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return List.of(variable("late"));
                    }
                };
        assertThrows(
                TimeoutException.class,
                () -> PointDiscovery.scan(source, object("root"), 6, 100, 5, () -> false));
    }

    @Test
    void explicitVariableRootIsDescribedWithoutBrowsingAndRetainsAccessFlags() throws Exception {
        var leaf = variable("not readable");
        var source =
                new Source() {
                    @Override
                    public DiscoveredPoint describe(PointDiscovery.Node node) {
                        return new DiscoveredPoint(
                                node.name(),
                                node.namespaceUri(),
                                "s",
                                "not readable",
                                "Double",
                                -1,
                                false,
                                false);
                    }
                };
        var result = PointDiscovery.scan(source, leaf, 1, 1, 1000, () -> false);
        assertEquals(1, result.points().size());
        assertFalse(result.points().getFirst().readable());
        assertTrue(source.browsed.isEmpty());
    }

    @Test
    void rejectsInvalidBoundsBeforeCallingSource() {
        var source = new Source();
        assertThrows(
                IllegalArgumentException.class,
                () -> PointDiscovery.scan(source, object("root"), 0, 100, 1000, () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> PointDiscovery.scan(source, object("root"), 3, 0, 1000, () -> false));
        assertTrue(source.browsed.isEmpty());
    }
}

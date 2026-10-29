package cn.buct.ua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.OpcUaClientConfig;
import org.eclipse.milo.opcua.sdk.client.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.identity.IdentityProvider;
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.FileBasedCertificateQuarantine;
import org.eclipse.milo.opcua.stack.core.security.FileBasedTrustListManager;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.eclipse.milo.opcua.stack.core.types.structured.WriteRequest;
import org.eclipse.milo.opcua.stack.core.types.structured.WriteResponse;
import org.eclipse.milo.opcua.stack.core.types.structured.WriteValue;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/** 单次连接的生命周期。无定时控制、无自动重放写入。 */
public final class UaConnection implements AutoCloseable {
    private static final int MAX_BROWSE_DEPTH = 3;
    private static final int MAX_BROWSE_REFERENCES = 500;
    private static final int ACCESS_READ = 0x01;
    private static final int ACCESS_WRITE = 0x02;
    private static final int SCALAR_VALUE_RANK = -1;

    private final ClientSettings settings;
    private final FileBasedTrustListManager trust;
    private OpcUaClient client;
    private byte[] userPassword;
    private boolean closed;

    public record ReadResult(String alias, NodeId nodeId, DataValue value) {}

    private record VariableMetadata(NodeId dataType, int valueRank, int access) {}

    public UaConnection(ClientSettings settings) throws Exception {
        this(settings, LocalPki::secret);
    }

    /** 阻塞连接入口，应由桌面 UI 的后台任务调用；凭据数组交给本连接管理并清零。 */
    public UaConnection(ClientSettings settings, BiFunction<String, String, char[]> credentials)
            throws Exception {
        this.settings = settings;
        var identity = LocalPki.load(settings, credentials);
        trust =
                FileBasedTrustListManager.createAndInitialize(
                        settings.pkiDirectory().resolve("trust-store"));
        try {
            var rejected = settings.pkiDirectory().resolve("rejected");
            Files.createDirectories(rejected);
            var endpoints =
                    UaServices.discover(
                            settings.discoveryEndpoint().toString(), settings.timeoutMs());
            var selected = settings.selectEndpoint(endpoints);
            var validator =
                    ServerTrust.create(
                            settings,
                            selected,
                            trust,
                            new FileBasedCertificateQuarantine(rejected.toFile()));
            IdentityProvider provider;
            if (settings.identityType().equals("username")) {
                char[] password =
                        LocalPki.requestSecret(
                                credentials,
                                settings.passwordEnv(),
                                "输入 OPC UA 用户 " + settings.username() + " 的密码");
                try {
                    var encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
                    userPassword = new byte[encoded.remaining()];
                    encoded.get(userPassword);
                    if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
                    provider =
                            new UsernameProvider(
                                    settings.username(), this::passwordForSession, validator);
                } finally {
                    Arrays.fill(password, '\0');
                }
            } else provider = new AnonymousProvider();
            var configuration =
                    OpcUaClientConfig.builder()
                            .setEndpoint(selected)
                            .setDiscoveryEndpoints(endpoints)
                            .setApplicationName(
                                    org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
                                            .english(settings.applicationName()))
                            .setApplicationUri(settings.applicationUri())
                            .setKeyPair(identity.keyPair())
                            .setCertificate(identity.certificate())
                            .setCertificateChain(identity.chain())
                            .setCertificateValidator(validator)
                            .setSessionEndpointValidationEnabled(true)
                            .setIdentityProvider(provider)
                            .setRequestTimeout(uint(settings.timeoutMs()))
                            .build();
            client =
                    OpcUaClient.create(
                            configuration,
                            transport ->
                                    transport
                                            .setConnectTimeout(uint(settings.timeoutMs()))
                                            .setAcknowledgeTimeout(uint(settings.timeoutMs())));
            client.connectAsync().get(settings.timeoutMs(), TimeUnit.MILLISECONDS);
            System.out.println("已建立认证会话：" + settings.endpoint());
        } catch (Exception e) {
            System.err.println("认证会话尚未建立；未执行浏览、读取或写入。");
            try {
                close();
            } catch (Exception cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private synchronized byte[] passwordForSession() {
        if (closed || userPassword == null) throw new IllegalStateException("连接已关闭，不能再次提供凭据");
        return userPassword.clone();
    }

    public void browse(NodeId parent) throws Exception {
        browse(parent, 1);
    }

    /** 有界只读浏览；从 Objects 出发时不递归标准服务器节点，只展开应用对象。 */
    public void browse(NodeId parent, int depth) throws Exception {
        if (depth < 1 || depth > MAX_BROWSE_DEPTH)
            throw new IllegalArgumentException("浏览层数必须为 1—3");
        record Pending(NodeId id, int level) {}
        var pending = new ArrayDeque<Pending>();
        var visited = new HashSet<NodeId>();
        pending.add(new Pending(parent, 1));
        int count = 0;
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!visited.add(current.id())) continue;
            var references =
                    UaServices.browse(
                            client,
                            current.id(),
                            settings.timeoutMs(),
                            MAX_BROWSE_REFERENCES - count);
            System.out.printf(
                    "浏览节点：%s，引用数量：%d%n", current.id().toParseableString(), references.size());
            for (var reference : references) {
                if (++count > MAX_BROWSE_REFERENCES)
                    throw new IllegalStateException("浏览已达 500 个引用上限，请指定更具体的根节点；结果不完整");
                var local =
                        reference.getNodeId().isLocal()
                                ? reference.getNodeId().toNodeId(client.getNamespaceTable())
                                : java.util.Optional.<NodeId>empty();
                System.out.printf(
                        "  名称=%s | 类别=%s%n",
                        reference.getBrowseName().getName(), reference.getNodeClass());
                System.out.println(
                        "    NodeId="
                                + local.map(NodeId::toParseableString)
                                        .orElse(reference.getNodeId().toString()));
                if (local.isEmpty()) continue;
                NodeId id = local.get();
                if (depth > 1 && reference.getNodeClass() == NodeClass.Variable) {
                    var metadata = metadata(id);
                    int access = metadata.access();
                    System.out.printf(
                            "    DataType=%s | ValueRank=%d | 当前用户可读=%s | 当前用户可写=%s%n",
                            metadata.dataType().toParseableString(),
                            metadata.valueRank(),
                            (access & ACCESS_READ) != 0,
                            (access & ACCESS_WRITE) != 0);
                }
                if (current.level() < depth
                        && reference.getNodeClass() == NodeClass.Object
                        && id.getNamespaceIndex().intValue() != 0) {
                    pending.addLast(new Pending(id, current.level() + 1));
                }
            }
        }
        System.out.println("命名空间：" + Arrays.toString(client.getNamespaceTable().toArray()));
    }

    private NodeId resolve(PointDefinition point) {
        var index = client.getNamespaceTable().getIndex(point.namespaceUri());
        if (index == null)
            throw new IllegalArgumentException("服务器未提供点位命名空间 URI：" + point.namespaceUri());
        return point.nodeId(index.intValue());
    }

    /** 为桌面节点树返回当前服务器的直接子节点，不读取变量值、不修改服务端。 */
    public List<PointDiscovery.Node> browseNodes(NodeId parent, int referenceLimit)
            throws Exception {
        var result = new java.util.ArrayList<PointDiscovery.Node>();
        for (var reference :
                UaServices.browse(client, parent, settings.timeoutMs(), referenceLimit)) {
            if (!reference.getNodeId().isLocal()) continue;
            var local = reference.getNodeId().toNodeId(client.getNamespaceTable());
            if (local.isEmpty()) continue;
            NodeId id = local.get();
            String uri = client.getNamespaceTable().get(id.getNamespaceIndex());
            if (uri == null) throw new IllegalStateException("服务器没有提供节点的命名空间 URI");
            String name =
                    reference.getDisplayName() == null
                            ? null
                            : reference.getDisplayName().getText();
            if (name == null || name.isBlank()) name = reference.getBrowseName().getName();
            result.add(new PointDiscovery.Node(id, uri, name, reference.getNodeClass()));
        }
        return List.copyOf(result);
    }

    /** 节点身份使用 namespace URI，避免 namespace index 在服务器重启后变化。 */
    public DiscoveredPoint describePoint(PointDiscovery.Node node) throws Exception {
        var attributes = metadata(node.nodeId());
        String type = builtInTypeName(attributes.dataType());
        return new DiscoveredPoint(
                node.name(),
                node.namespaceUri(),
                identifierType(node.nodeId()),
                identifierText(node.nodeId()),
                type,
                attributes.valueRank(),
                (attributes.access() & ACCESS_READ) != 0,
                (attributes.access() & ACCESS_WRITE) != 0);
    }

    public PointDiscovery.Result discoverPoints(
            PointDiscovery.Node root,
            int maxDepth,
            int maxReferences,
            long timeoutMs,
            java.util.function.BooleanSupplier cancelled)
            throws Exception {
        return PointDiscovery.scan(
                new PointDiscovery.Source() {
                    @Override
                    public List<PointDiscovery.Node> children(NodeId parent) throws Exception {
                        return browseNodes(parent, maxReferences);
                    }

                    @Override
                    public DiscoveredPoint describe(PointDiscovery.Node node) throws Exception {
                        return describePoint(node);
                    }
                },
                root,
                maxDepth,
                maxReferences,
                timeoutMs,
                cancelled);
    }

    /** 只列出当前设备/标签组直接配置的标签，不递归扫描诊断及动态地址模板。 */
    public PointDiscovery.Result discoverDeviceTags(
            PointDiscovery.Node root,
            int maxReferences,
            long timeoutMs,
            java.util.function.BooleanSupplier cancelled)
            throws Exception {
        var source =
                new PointDiscovery.Source() {
                    @Override
                    public List<PointDiscovery.Node> children(NodeId parent) throws Exception {
                        return browseNodes(parent, maxReferences);
                    }

                    @Override
                    public DiscoveredPoint describe(PointDiscovery.Node node) throws Exception {
                        return describePoint(node);
                    }
                };
        return PointDiscovery.scan(
                DeviceTagView.directTags(source), root, 2, maxReferences, timeoutMs, cancelled);
    }

    private String builtInTypeName(NodeId type) {
        if (type.getNamespaceIndex().intValue() == 0
                && type.getIdentifier()
                        instanceof
                        org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger id) {
            String name =
                    switch (id.intValue()) {
                        case 1 -> "Boolean";
                        case 2 -> "SByte";
                        case 3 -> "Byte";
                        case 4 -> "Int16";
                        case 5 -> "UInt16";
                        case 6 -> "Int32";
                        case 7 -> "UInt32";
                        case 8 -> "Int64";
                        case 9 -> "UInt64";
                        case 10 -> "Float";
                        case 11 -> "Double";
                        case 12 -> "String";
                        case 13 -> "DateTime";
                        case 14 -> "Guid";
                        case 15 -> "ByteString";
                        case 16 -> "XmlElement";
                        case 17 -> "NodeId";
                        case 18 -> "ExpandedNodeId";
                        case 19 -> "StatusCode";
                        case 20 -> "QualifiedName";
                        case 21 -> "LocalizedText";
                        case 22 -> "Structure";
                        case 23 -> "DataValue";
                        case 24 -> "BaseDataType";
                        case 25 -> "DiagnosticInfo";
                        case 26 -> "Number";
                        case 27 -> "Integer";
                        case 28 -> "UInteger";
                        case 29 -> "Enumeration";
                        default -> null;
                    };
            if (name != null) return name;
        }
        return "nsu="
                + client.getNamespaceTable().get(type.getNamespaceIndex())
                + ";"
                + identifierType(type)
                + "="
                + identifierText(type);
    }

    private static String identifierType(NodeId id) {
        return switch (id.getType()) {
            case Numeric -> "i";
            case String -> "s";
            case Guid -> "g";
            case Opaque -> "b";
        };
    }

    private static String identifierText(NodeId id) {
        return id.getIdentifier()
                        instanceof org.eclipse.milo.opcua.stack.core.types.builtin.ByteString bytes
                ? java.util.Base64.getEncoder().encodeToString(bytes.bytes())
                : id.getIdentifier().toString();
    }

    public void read(List<PointDefinition> points) throws Exception {
        List<ReadResult> results = readValues(points);
        boolean failed = false;
        for (ReadResult result : results) {
            DataValue value = result.value();
            System.out.printf(
                    "点位=%s | 节点=%s | 值=%s | 状态=%s | 源时间=%s | 服务时间=%s%n",
                    result.alias(),
                    result.nodeId(),
                    value.getValue(),
                    value.getStatusCode(),
                    value.getSourceTime(),
                    value.getServerTime());
            failed |= value.getStatusCode() == null || !value.getStatusCode().isGood();
        }
        if (failed) throw new IllegalStateException("存在读取失败或质量非 Good 的点位，不能按成功处理");
    }

    /** 返回原始类型、质量和时间戳，供桌面界面处理；非 Good 状态也保留在结果中。 */
    public List<ReadResult> readValues(List<PointDefinition> points) throws Exception {
        List<NodeId> ids = points.stream().map(this::resolve).toList();
        List<DataValue> values =
                client.readValuesAsync(0.0, TimestampsToReturn.Both, ids)
                        .get(settings.timeoutMs(), TimeUnit.MILLISECONDS);
        if (values.size() != points.size()) throw new IllegalStateException("读取返回数量与请求不一致");
        var results = new java.util.ArrayList<ReadResult>();
        for (int i = 0; i < values.size(); i++) {
            results.add(new ReadResult(points.get(i).alias(), ids.get(i), values.get(i)));
        }
        return List.copyOf(results);
    }

    private VariableMetadata metadata(NodeId id) throws Exception {
        var attributes =
                List.of(AttributeId.DataType, AttributeId.ValueRank, AttributeId.UserAccessLevel);
        var requests =
                attributes.stream()
                        .map(a -> new ReadValueId(id, a.uid(), null, QualifiedName.NULL_VALUE))
                        .toList();
        var values =
                client.readAsync(0.0, TimestampsToReturn.Neither, requests)
                        .get(settings.timeoutMs(), TimeUnit.MILLISECONDS)
                        .getResults();
        if (values == null || values.length != 3) throw new IllegalStateException("变量属性返回数量异常");
        for (DataValue value : values) {
            if (value == null || value.getStatusCode() == null)
                throw new IllegalStateException("变量属性缺少质量码");
            if (!value.getStatusCode().isGood()) throw new UaException(value.getStatusCode());
        }
        return new VariableMetadata(
                (NodeId) values[0].getValue().getValue(),
                (Integer) values[1].getValue().getValue(),
                ((UByte) values[2].getValue().getValue()).intValue());
    }

    public void write(PointDefinition point, String text, boolean confirmed) throws Exception {
        settings.requireWrite(point, confirmed);
        Object typed = point.parseWriteValue(text);
        NodeId id = resolve(point);
        var metadata = metadata(id);
        NodeId actualType = metadata.dataType();
        NodeId expectedType =
                switch (point.dataType()) {
                    case "Boolean" -> NodeIds.Boolean;
                    case "SByte" -> NodeIds.SByte;
                    case "Byte" -> NodeIds.Byte;
                    case "Int16" -> NodeIds.Int16;
                    case "UInt16" -> NodeIds.UInt16;
                    case "Int32" -> NodeIds.Int32;
                    case "UInt32" -> NodeIds.UInt32;
                    case "Int64" -> NodeIds.Int64;
                    case "UInt64" -> NodeIds.UInt64;
                    case "Float" -> NodeIds.Float;
                    case "Double" -> NodeIds.Double;
                    case "String" -> NodeIds.String;
                    default -> throw new IllegalArgumentException("不支持写入此类型");
                };
        if (!actualType.equals(expectedType) || metadata.valueRank() != SCALAR_VALUE_RANK) {
            throw new IllegalArgumentException("服务器 DataType / ValueRank 与配置的标量类型不符，未写入");
        }
        if ((metadata.access() & ACCESS_WRITE) == 0) {
            throw new IllegalArgumentException("当前用户没有该点位的写权限，未写入");
        }
        var before =
                client.readValuesAsync(0.0, TimestampsToReturn.Both, List.of(id))
                        .get(settings.timeoutMs(), TimeUnit.MILLISECONDS)
                        .getFirst();
        if (before.getStatusCode() == null || !before.getStatusCode().isGood())
            throw new IllegalStateException("写前读取质量异常，未写入");
        // 只使用当前已激活会话；不等待新会话。传输等待阶段可通过原始 future 取消。
        var session = client.getSessionAsync().getNow(null);
        if (session == null) throw new IllegalStateException("当前没有已激活会话，未提交写入");
        var request =
                new WriteRequest(
                        client.newRequestHeader(session.getAuthenticationToken()),
                        new WriteValue[] {
                            new WriteValue(
                                    id,
                                    AttributeId.Value.uid(),
                                    null,
                                    DataValue.valueOnly(new Variant(typed)))
                        });
        WriteResponse response;
        var writeFuture = client.getTransport().sendRequestMessage(request);
        try {
            response = (WriteResponse) writeFuture.get(settings.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // 取消原始 thenCompose future，阻止尚未获取通道的请求稍后提交；已发出的请求结果仍可能未知。
            writeFuture.cancel(false);
            try {
                close();
            } catch (Exception cleanup) {
                e.addSuppressed(cleanup);
            }
            throw new IllegalStateException("写请求未得到确认，结果可能未知；已请求断开，不自动重试", e);
        }
        var statuses = response.getResults();
        if (statuses == null
                || statuses.length != 1
                || statuses[0] == null
                || !statuses[0].isGood()) {
            throw new IllegalStateException("写入返回失败：" + Arrays.toString(statuses));
        }
        DataValue after;
        try {
            after =
                    client.readValuesAsync(0.0, TimestampsToReturn.Both, List.of(id))
                            .get(settings.timeoutMs(), TimeUnit.MILLISECONDS)
                            .getFirst();
        } catch (Exception e) {
            throw new IllegalStateException("写服务已返回 Good，但回读未完成，当前值未确认；不自动重试或回滚", e);
        }
        System.out.printf(
                "模拟写入：点位=%s | 旧值=%s | 请求值=%s | 写状态=%s | 读回=%s | 读回状态=%s%n",
                point.alias(),
                before.getValue(),
                typed,
                statuses[0],
                after.getValue(),
                after.getStatusCode());
        if (after.getStatusCode() == null
                || !after.getStatusCode().isGood()
                || !Objects.equals(typed, after.getValue().getValue())) {
            throw new IllegalStateException("写服务已接受，但读回未确认匹配；不重试、不自动回滚");
        }
    }

    @Override
    public void close() throws Exception {
        synchronized (this) {
            if (closed) return;
            closed = true;
            if (userPassword != null) Arrays.fill(userPassword, (byte) 0);
        }
        Exception failure = null;
        try {
            if (client != null)
                client.disconnectAsync().get(settings.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            failure = e;
        } finally {
            if (client != null) {
                try {
                    client.getTransport()
                            .disconnect()
                            .get(Math.min(settings.timeoutMs(), 5000), TimeUnit.MILLISECONDS);
                } catch (Exception cleanup) {
                    if (failure == null) failure = cleanup;
                    else failure.addSuppressed(cleanup);
                }
            }
            try {
                trust.close();
            } catch (Exception cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw failure;
        }
    }
}

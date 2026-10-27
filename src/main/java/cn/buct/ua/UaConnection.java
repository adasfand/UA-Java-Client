package cn.buct.ua;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.identity.IdentityProvider;
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider;
import org.eclipse.milo.opcua.stack.core.security.DefaultClientCertificateValidator;
import org.eclipse.milo.opcua.stack.core.security.FileBasedCertificateQuarantine;
import org.eclipse.milo.opcua.stack.core.security.FileBasedTrustListManager;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.util.validation.ValidationCheck;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

/** 单次连接的生命周期。无定时控制、无自动重放写入。 */
public final class UaConnection implements AutoCloseable {
    private final ClientSettings settings;
    private final FileBasedTrustListManager trust;
    private OpcUaClient client;

    public UaConnection(ClientSettings settings) throws Exception {
        this.settings = settings;
        var identity = LocalPki.load(settings);
        trust = FileBasedTrustListManager.createAndInitialize(settings.pkiDirectory().resolve("信任库"));
        try {
            var rejected = settings.pkiDirectory().resolve("待核验证书");
            Files.createDirectories(rejected);
            var validator = new DefaultClientCertificateValidator(trust, ValidationCheck.ALL_OPTIONAL_CHECKS,
                    new FileBasedCertificateQuarantine(rejected.toFile()));
            IdentityProvider provider;
            if (settings.identityType().equals("username")) {
                char[] password = LocalPki.secret(settings.passwordEnv(), "输入 KEPServerEX 用户密码");
                try { provider = new UsernameProvider(settings.username(), new String(password), validator); }
                finally { Arrays.fill(password, '\0'); }
            } else provider = new AnonymousProvider();
            client = OpcUaClient.create(settings.endpoint().toString(), endpoints -> endpoints.stream()
                            .filter(e -> settings.acceptsEndpoint(e.getEndpointUrl()))
                            .filter(e -> settings.policy().getUri().equals(e.getSecurityPolicyUri()))
                            .filter(e -> settings.mode() == e.getSecurityMode())
                            .filter(e -> Arrays.stream(e.getUserIdentityTokens()).anyMatch(t ->
                                    t.getTokenType().name().equals(settings.identityType().equals("username") ? "UserName" : "Anonymous")))
                            .findFirst(), transport -> transport.setConnectTimeout(uint(settings.timeoutMs()))
                                    .setAcknowledgeTimeout(uint(settings.timeoutMs())), builder -> builder
                            .setApplicationName(org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText.english(settings.applicationName()))
                            .setApplicationUri(settings.applicationUri())
                            .setKeyPair(identity.keyPair()).setCertificate(identity.certificate()).setCertificateChain(identity.chain())
                            .setCertificateValidator(validator).setSessionEndpointValidationEnabled(true)
                            .setIdentityProvider(provider).setRequestTimeout(uint(settings.timeoutMs())));
            client.connectAsync().get(settings.timeoutMs(), TimeUnit.MILLISECONDS);
            System.out.println("已建立认证会话：" + settings.endpoint());
        } catch (Exception e) {
            try { close(); } catch (Exception cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    public void browse(NodeId parent) throws Exception {
        var references = client.getAddressSpace().browse(parent);
        System.out.println("浏览节点：" + parent + "，引用数量：" + references.size());
        for (var reference : references) {
            System.out.printf("名称=%s | 节点=%s | 类别=%s%n", reference.getBrowseName(), reference.getNodeId(), reference.getNodeClass());
        }
        System.out.println("命名空间：" + Arrays.toString(client.getNamespaceTable().toArray()));
    }

    private NodeId resolve(PointDefinition point) {
        var index = client.getNamespaceTable().getIndex(point.namespaceUri());
        if (index == null) throw new IllegalArgumentException("服务器未提供点位命名空间 URI：" + point.namespaceUri());
        return point.nodeId(index.intValue());
    }

    public void read(List<PointDefinition> points) throws Exception {
        List<NodeId> ids = points.stream().map(this::resolve).toList();
        List<DataValue> values = client.readValues(0.0, TimestampsToReturn.Both, ids);
        if (values.size() != points.size()) throw new IllegalStateException("读取返回数量与请求不一致");
        boolean failed = false;
        for (int i = 0; i < values.size(); i++) {
            DataValue value = values.get(i);
            System.out.printf("点位=%s | 节点=%s | 值=%s | 状态=%s | 源时间=%s | 服务时间=%s%n",
                    points.get(i).alias(), ids.get(i), value.getValue(), value.getStatusCode(), value.getSourceTime(), value.getServerTime());
            failed |= value.getStatusCode() == null || !value.getStatusCode().isGood();
        }
        if (failed) throw new IllegalStateException("存在读取失败或质量非 Good 的点位，不能按成功处理");
    }

    public void write(PointDefinition point, String text, boolean confirmed) throws Exception {
        settings.requireWrite(point, confirmed);
        Object typed = point.parseWriteValue(text);
        NodeId id = resolve(point);
        var variable = client.getAddressSpace().getVariableNode(id);
        NodeId actualType = variable.readDataType();
        NodeId expectedType = new NodeId(0, switch (point.dataType()) {
            case "Boolean" -> 1; case "SByte" -> 2; case "Byte" -> 3; case "Int16" -> 4;
            case "UInt16" -> 5; case "Int32" -> 6; case "UInt32" -> 7; case "Int64" -> 8;
            case "UInt64" -> 9; case "Float" -> 10; case "Double" -> 11; case "String" -> 12;
            default -> throw new IllegalArgumentException("不支持写入此类型");
        });
        if (!actualType.equals(expectedType) || variable.readValueRank() != -1) {
            throw new IllegalArgumentException("服务器 DataType / ValueRank 与配置的标量类型不符，未写入");
        }
        if ((variable.readUserAccessLevel().intValue() & 2) == 0) {
            throw new IllegalArgumentException("当前用户没有该点位的写权限，未写入");
        }
        var before = client.readValue(0.0, TimestampsToReturn.Both, id);
        if (before.getStatusCode() == null || !before.getStatusCode().isGood()) throw new IllegalStateException("写前读取质量异常，未写入");
        var statuses = client.writeValues(List.of(id), List.of(DataValue.valueOnly(new Variant(typed))));
        if (statuses.size() != 1 || statuses.get(0) == null || !statuses.get(0).isGood()) {
            throw new IllegalStateException("写入返回失败：" + statuses);
        }
        DataValue after = client.readValue(0.0, TimestampsToReturn.Both, id);
        System.out.printf("模拟写入：点位=%s | 旧值=%s | 请求值=%s | 写状态=%s | 读回=%s | 读回状态=%s%n",
                point.alias(), before.getValue(), typed, statuses.get(0), after.getValue(), after.getStatusCode());
        if (after.getStatusCode() == null || !after.getStatusCode().isGood()
                || !Objects.equals(typed, after.getValue().getValue())) {
            throw new IllegalStateException("写服务已接受，但读回未确认匹配；不重试、不自动回滚");
        }
    }

    @Override public void close() throws Exception {
        try {
            if (client != null) client.disconnectAsync().get(settings.timeoutMs(), TimeUnit.MILLISECONDS);
        } finally { trust.close(); }
    }
}

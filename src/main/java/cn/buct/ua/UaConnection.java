package cn.buct.ua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.OpcUaClientConfig;
import org.eclipse.milo.opcua.sdk.client.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.identity.IdentityProvider;
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.FileBasedCertificateQuarantine;
import org.eclipse.milo.opcua.stack.core.security.FileBasedTrustListManager;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.eclipse.milo.opcua.stack.core.types.structured.WriteRequest;
import org.eclipse.milo.opcua.stack.core.types.structured.WriteResponse;
import org.eclipse.milo.opcua.stack.core.types.structured.WriteValue;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/** 单次连接的生命周期。无定时控制、无自动重放写入。 */
public final class UaConnection implements AutoCloseable {
    private static final int ACCESS_WRITE = 0x02;
    private static final int SCALAR_VALUE_RANK = -1;

    private final ClientSettings settings;
    private final FileBasedTrustListManager trust;
    private OpcUaClient client;
    private byte[] userPassword;
    private boolean closed;

    synchronized boolean isClosed() {
        return closed;
    }

    private record VariableMetadata(NodeId dataType, int valueRank, int access) {}

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
            System.err.println("认证会话尚未建立；未执行读取或写入。");
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

    /** 按现场提供的UA地址读取Value，由上层直接传入节点地址。 */
    public DataValue readAddress(String address) throws Exception {
        requireOpen();
        NodeId id = UaAddress.resolve(address, client.getNamespaceTable());
        var results =
                client.readValuesAsync(0.0, TimestampsToReturn.Both, List.of(id))
                        .get(settings.timeoutMs(), TimeUnit.MILLISECONDS);
        if (results == null || results.size() != 1 || results.getFirst() == null)
            throw new IllegalStateException("读取返回数量或内容异常");
        return results.getFirst();
    }

    /** 通用地址写入：按服务器类型转换，只写一次；不强制读取写Only点或回读动态点。 */
    public org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode writeAddress(
            String address, Object value) throws Exception {
        requireOpen();
        NodeId id = UaAddress.resolve(address, client.getNamespaceTable());
        VariableMetadata attributes = metadata(id);
        if (attributes.valueRank() != SCALAR_VALUE_RANK)
            throw new IllegalArgumentException("当前写接口支持int/float等基本标量，不支持数组或结构体");
        if ((attributes.access() & ACCESS_WRITE) == 0)
            throw new IllegalArgumentException("当前服务器账号没有该地址的写权限");
        Object typed = ScalarValue.convert(attributes.dataType(), value);
        return writeOnce(id, typed);
    }

    private void requireOpen() {
        if (isClosed() || client == null) throw new IllegalStateException("连接已关闭，请重新连接");
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

    private org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode writeOnce(
            NodeId id, Object typed) throws Exception {
        requireOpen();
        // 只使用当前已激活会话；不等待新会话。不在超时后自动重放写入。
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
        return checkedWriteStatus(response);
    }

    static org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode checkedWriteStatus(
            WriteResponse response) throws UaException {
        if (response == null
                || response.getResponseHeader() == null
                || response.getResponseHeader().getServiceResult() == null)
            throw new IllegalStateException("写服务缺少响应状态，结果未确认");
        var service = response.getResponseHeader().getServiceResult();
        if (!service.isGood()) throw new UaException(service);
        var statuses = response.getResults();
        if (statuses == null || statuses.length != 1 || statuses[0] == null)
            throw new IllegalStateException("写服务返回点数或状态异常，结果未确认");
        if (!statuses[0].isGood()) throw new UaException(statuses[0]);
        return statuses[0];
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

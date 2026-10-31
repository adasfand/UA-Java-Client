package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/** 提供给上层程序的连接、按地址读、按地址写接口；不依赖点表或桌面窗口。 */
public final class UaClient implements AutoCloseable {
    interface Session extends AutoCloseable {
        DataValue read(String address) throws Exception;

        default List<DataValue> readMany(List<String> addresses) throws Exception {
            var results = new ArrayList<DataValue>();
            for (String address : addresses) results.add(read(address));
            return results;
        }

        StatusCode write(String address, Object value) throws Exception;

        boolean isConnected();

        void close() throws Exception;
    }

    @FunctionalInterface
    interface SessionFactory {
        Session open(ClientSettings settings, BiFunction<String, String, char[]> credentials)
                throws Exception;
    }

    private final Path configFile;
    private final SessionFactory factory;
    private volatile Session session;
    private URI endpoint;

    public UaClient(Path configFile) {
        this(configFile, UaClient::openSession);
    }

    UaClient(Path configFile, SessionFactory factory) {
        this.configFile = Objects.requireNonNull(configFile);
        this.factory = Objects.requireNonNull(factory);
    }

    /** 账号与口令来源均已在配置/环境变量中准备时，只传服务器地址即可。 */
    public void connect(String serverAddress) throws Exception {
        connect(serverAddress, null, null, null);
    }

    /** 对应旧版的服务器地址与远程 IP 两个输入；账号及口令使用配置和环境变量。 */
    public void connect(String serverAddress, String remoteHost) throws Exception {
        connect(serverAddress, remoteHost, null, null, null);
    }

    /**
     * 地址可为完整 opc.tcp URL，或使用配置中端口/路径的 IP/主机名。 用户名传 null 使用配置值；两个口令传 null/空数组时使用配置指定的环境变量。
     * 不弹出窗口、不修改调用者口令数组；安全策略和身份材料从 configFile 加载。
     */
    public void connect(
            String serverAddress, String username, char[] userPassword, char[] keystorePassword)
            throws Exception {
        connect(serverAddress, null, username, userPassword, keystorePassword);
    }

    /** 远程 IP/主机由调用者传入，替换服务器地址的主机部分；不绑定客户端本机地址。 */
    public synchronized void connect(
            String serverAddress,
            String remoteHost,
            String username,
            char[] userPassword,
            char[] keystorePassword)
            throws Exception {
        if (isConnected()) throw new IllegalStateException("已有连接，请先断开再连接其他服务器");
        ClientSettings settings =
                ClientSettings.load(configFile).withConnection(serverAddress, remoteHost, username);
        connect(settings, userPassword, keystorePassword);
    }

    /** 桌面把已选登录方式随配置快照传入，不依赖先修改磁盘才能登录。 */
    synchronized void connect(ClientSettings settings, char[] userPassword, char[] keystorePassword)
            throws Exception {
        Objects.requireNonNull(settings, "settings");
        if (isConnected()) throw new IllegalStateException("已有连接，请先断开再连接其他服务器");
        if (session != null) close();
        boolean anonymous = settings.identityType().equals("anonymous");
        char[] user = anonymous ? new char[0] : copy(userPassword);
        char[] store = copy(keystorePassword);
        try {
            session =
                    factory.open(
                            settings,
                            (environment, prompt) -> {
                                boolean keyStore = prompt.equals("输入本机证书库密码");
                                if (!keyStore && anonymous)
                                    throw new IllegalStateException("匿名连接不提供服务器用户密码");
                                return secret(keyStore ? store : user, environment);
                            });
            if (!session.isConnected()) {
                close();
                throw new IllegalStateException("认证会话尚未建立");
            }
            endpoint = settings.endpoint();
        } finally {
            Arrays.fill(user, '\0');
            Arrays.fill(store, '\0');
        }
    }

    /** 一次性部署辅助：生成客户端身份和公开证书，不连接服务器，不批准服务端信任。 */
    public synchronized Path initializeCertificate(char[] keystorePassword) throws Exception {
        if (isConnected()) throw new IllegalStateException("已连接时不生成客户端身份");
        ClientSettings settings = ClientSettings.load(configFile);
        char[] store = copy(keystorePassword);
        try {
            LocalPki.initialize(settings, (environment, prompt) -> secret(store, environment));
            return settings.pkiDirectory().resolve("client.der");
        } finally {
            Arrays.fill(store, '\0');
        }
    }

    /** 返回服务器 Value 的原始 Java/Milo 值；质量非 Good 时抛异常，不用旧值代替。 */
    public synchronized Object read(String address) throws Exception {
        DataValue data = readData(address);
        return data.getValue() == null ? null : data.getValue().getValue();
    }

    /** 需要质量和时间戳的上层可使用此入口；仍按同一地址读取一次。 */
    public synchronized DataValue readData(String address) throws Exception {
        Session current = requireSession();
        UaAddress.parse(address);
        DataValue data = current.read(address);
        if (data == null || data.getStatusCode() == null)
            throw new IllegalStateException("读取结果缺少数据或质量码");
        if (!data.getStatusCode().isGood()) throw new UaException(data.getStatusCode());
        return data;
    }

    /** 表驱动采集使用；质量由各点独立判断，单点公开read接口的契约不变。 */
    synchronized List<DataValue> readMany(List<String> addresses) throws Exception {
        Session current = requireSession();
        List<String> request = List.copyOf(addresses);
        for (String address : request) UaAddress.parse(address);
        if (request.isEmpty()) return List.of();
        var results = current.readMany(request);
        if (results == null || results.size() != request.size())
            throw new IllegalStateException("批量读取结果数量与地址数量不一致");
        return Collections.unmodifiableList(new ArrayList<>(results));
    }

    /** 写一次并返回服务器状态。Good表示服务器接受写服务，不表示物理动作完成或值永远不变。 */
    public synchronized StatusCode write(String address, Object value) throws Exception {
        Session current = requireSession();
        UaAddress.parse(address);
        if (value == null) throw new IllegalArgumentException("写入值不能为空");
        StatusCode status = current.write(address, value);
        if (status == null) throw new IllegalStateException("写服务未返回状态码，结果未确认");
        if (!status.isGood()) throw new UaException(status);
        return status;
    }

    public boolean isConnected() {
        Session current = session;
        return current != null && current.isConnected();
    }

    public synchronized URI endpoint() {
        requireSession();
        return endpoint;
    }

    private Session requireSession() {
        if (!isConnected()) throw new IllegalStateException("请先成功连接服务器，再读取或写入");
        return session;
    }

    @Override
    public synchronized void close() throws Exception {
        Session current = session;
        session = null;
        endpoint = null;
        if (current != null) current.close();
    }

    private static char[] copy(char[] value) {
        return value == null ? new char[0] : value.clone();
    }

    private static char[] secret(char[] value, String environment) {
        if (value.length > 0) return value.clone();
        String fromEnvironment = System.getenv(environment);
        if (fromEnvironment == null || fromEnvironment.isEmpty())
            throw new IllegalArgumentException("缺少口令：请传入参数或设置本机环境变量 " + environment);
        return fromEnvironment.toCharArray();
    }

    private static Session openSession(
            ClientSettings settings, BiFunction<String, String, char[]> credentials)
            throws Exception {
        var connection = new UaConnection(settings, credentials);
        return new Session() {
            public DataValue read(String address) throws Exception {
                return connection.readAddress(address);
            }

            public List<DataValue> readMany(List<String> addresses) throws Exception {
                return connection.readAddresses(addresses);
            }

            public StatusCode write(String address, Object value) throws Exception {
                return connection.writeAddress(address, value);
            }

            public boolean isConnected() {
                return !connection.isClosed();
            }

            public void close() throws Exception {
                connection.close();
            }
        };
    }
}

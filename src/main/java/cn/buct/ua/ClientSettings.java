package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;

import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** 外部配置；不包含密码或私钥。 */
public record ClientSettings(
        URI endpoint,
        SecurityPolicy policy,
        MessageSecurityMode mode,
        String identityType,
        String username,
        String passwordEnv,
        String applicationUri,
        String applicationName,
        long timeoutMs,
        Path pkiDirectory,
        String pkiPasswordEnv,
        Path nodesFile,
        boolean writesEnabled,
        String environment,
        URI discoveryEndpoint,
        PkiSettings pki,
        String serverTrust) {

    public ClientSettings {
        validateEndpoint(endpoint);
        validateEndpoint(discoveryEndpoint);
        Objects.requireNonNull(policy, "security.policy");
        if (policy == SecurityPolicy.None || mode != MessageSecurityMode.SignAndEncrypt) {
            throw new IllegalArgumentException("当前仅接受明确的安全策略和 SignAndEncrypt 模式");
        }
        if (!"username".equals(identityType) && !"anonymous".equals(identityType)) {
            throw new IllegalArgumentException("identity.type 仅支持 username 或 anonymous");
        }
        if (identityType.equals("username") && (username == null || username.isBlank())) {
            throw new IllegalArgumentException("用户名身份必须配置 identity.username");
        }
        if (timeoutMs < 1000 || timeoutMs > 120000)
            throw new IllegalArgumentException("请求超时应为 1000—120000 毫秒");
        if (applicationUri == null
                || !URI.create(applicationUri).isAbsolute()
                || applicationName == null
                || applicationName.isBlank()) {
            throw new IllegalArgumentException("应用名称不能为空，application.uri 必须是绝对 URI");
        }
        if (passwordEnv == null
                || passwordEnv.isBlank()
                || pkiPasswordEnv == null
                || pkiPasswordEnv.isBlank()) {
            throw new IllegalArgumentException("凭据环境变量名称不能为空");
        }
        Objects.requireNonNull(pkiDirectory, "pki.directory");
        Objects.requireNonNull(nodesFile, "nodes.file");
        Objects.requireNonNull(pki, "pki");
        if (!"strict".equals(serverTrust) && !"first-use".equals(serverTrust)) {
            throw new IllegalArgumentException("security.serverTrust 仅支持 strict 或 first-use");
        }
    }

    /** 原有构造入口保持人工预置信任的行为。 */
    public ClientSettings(
            URI endpoint,
            SecurityPolicy policy,
            MessageSecurityMode mode,
            String identityType,
            String username,
            String passwordEnv,
            String applicationUri,
            String applicationName,
            long timeoutMs,
            Path pkiDirectory,
            String pkiPasswordEnv,
            Path nodesFile,
            boolean writesEnabled,
            String environment,
            URI discoveryEndpoint,
            PkiSettings pki) {
        this(
                endpoint,
                policy,
                mode,
                identityType,
                username,
                passwordEnv,
                applicationUri,
                applicationName,
                timeoutMs,
                pkiDirectory,
                pkiPasswordEnv,
                nodesFile,
                writesEnabled,
                environment,
                discoveryEndpoint,
                pki,
                "strict");
    }

    /** 保留原有构造入口；新增部署参数使用显式扩展构造或配置文件。 */
    public ClientSettings(
            URI endpoint,
            SecurityPolicy policy,
            MessageSecurityMode mode,
            String identityType,
            String username,
            String passwordEnv,
            String applicationUri,
            String applicationName,
            long timeoutMs,
            Path pkiDirectory,
            String pkiPasswordEnv,
            Path nodesFile,
            boolean writesEnabled,
            String environment) {
        this(
                endpoint,
                policy,
                mode,
                identityType,
                username,
                passwordEnv,
                applicationUri,
                applicationName,
                timeoutMs,
                pkiDirectory,
                pkiPasswordEnv,
                nodesFile,
                writesEnabled,
                environment,
                endpoint,
                PkiSettings.defaults(applicationName));
    }

    private static void validateEndpoint(URI endpoint) {
        if (endpoint == null
                || !"opc.tcp".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getPort() < 1
                || endpoint.getPort() > 65535
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("端点必须是带主机和有效端口的 opc.tcp 地址，不含凭据、查询或片段");
        }
    }

    public static ClientSettings load(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        return from(read(absolute), absolute);
    }

    /** 解析同一份配置快照；相对路径仍以实际配置文件目录为基准。 */
    static ClientSettings from(Properties p, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        URI endpoint = URI.create(required(p, "endpoint.url"));
        SecurityPolicy policy = SecurityPolicy.valueOf(required(p, "security.policy"));
        MessageSecurityMode mode = MessageSecurityMode.valueOf(required(p, "security.mode"));
        String identity = required(p, "identity.type");
        long timeout = Long.parseLong(p.getProperty("request.timeoutMs", "10000"));
        String applicationUri = required(p, "application.uri");
        String discovery = p.getProperty("endpoint.discoveryUrl", "").trim();
        return new ClientSettings(
                endpoint,
                policy,
                mode,
                identity,
                identity.equals("username") ? required(p, "identity.username") : "",
                p.getProperty("identity.passwordEnv", "UA_PASSWORD"),
                applicationUri,
                required(p, "application.name"),
                timeout,
                absolute.getParent().resolve(required(p, "pki.directory")).normalize(),
                p.getProperty("pki.passwordEnv", "UA_KEYSTORE_PASSWORD"),
                absolute.getParent()
                        .resolve(p.getProperty("nodes.file", "nodes.properties"))
                        .normalize(),
                bool(p, "writes.enabled", false),
                p.getProperty("environment", "simulation"),
                discovery.isEmpty() ? endpoint : URI.create(discovery),
                PkiSettings.from(p, required(p, "application.name")),
                p.getProperty("security.serverTrust", "strict").trim());
    }

    public List<PointDefinition> points() throws IOException {
        Properties p = read(nodesFile);
        List<PointDefinition> result = new ArrayList<>();
        HashSet<String> aliases = new HashSet<>();
        for (String item : required(p, "nodes").split(",", -1)) {
            String alias = item.trim();
            if (alias.isEmpty() || !aliases.add(alias))
                throw new IllegalArgumentException("点位别名为空或重复：" + alias);
            String prefix = "node." + alias + ".";
            result.add(
                    new PointDefinition(
                            alias,
                            required(p, prefix + "namespaceUri"),
                            required(p, prefix + "identifierType"),
                            rawIdentifier(p, prefix + "identifier"),
                            required(p, prefix + "dataType"),
                            bool(p, prefix + "writable", false),
                            p.getProperty(prefix + "minimum"),
                            p.getProperty(prefix + "maximum")));
        }
        return List.copyOf(result);
    }

    public PointDefinition point(String alias) throws IOException {
        return points().stream()
                .filter(n -> n.alias().equals(alias))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未配置该点位别名：" + alias));
    }

    /** 允许同一会话重新读取写入开关，但不允许用另一服务器或用户的配置授权当前会话。 */
    void requireSameConnection(ClientSettings connected) {
        if (!endpoint.equals(connected.endpoint)
                || policy != connected.policy
                || mode != connected.mode
                || !identityType.equals(connected.identityType)
                || !username.equals(connected.username)
                || !passwordEnv.equals(connected.passwordEnv)
                || !applicationUri.equals(connected.applicationUri)
                || !pkiDirectory.equals(connected.pkiDirectory)
                || !pki.equals(connected.pki)
                || !pkiPasswordEnv.equals(connected.pkiPasswordEnv)
                || !nodesFile.equals(connected.nodesFile)
                || !environment.equals(connected.environment)
                || !serverTrust.equals(connected.serverTrust)) {
            throw new IllegalArgumentException("连接身份或点表配置已改变，请断开并重新连接后操作");
        }
    }

    public void requireWrite(PointDefinition point, boolean confirmed) {
        requireWriteConfiguration(point, confirmed);
        if (!isLocalHost(endpoint.getHost())) {
            throw new IllegalArgumentException(
                    "模拟写入仅支持本机服务器；地址 " + endpoint.getHost() + " 未能确认为本机地址");
        }
    }

    /** 不进行地址解析，供桌面线程在显示确认框前检查本地参数。 */
    void requireWriteConfiguration(PointDefinition point, boolean confirmed) {
        if (!writesEnabled || !confirmed || !point.writable()) {
            throw new IllegalArgumentException("写入被拒绝：需要打开总开关、允许点位写入并确认本次操作");
        }
        if (!environment.equals("simulation")) {
            throw new IllegalArgumentException("模拟写入需要在连接配置中设置 environment=simulation");
        }
        if (point.namespaceUri().equals("http://opcfoundation.org/UA/")) {
            throw new IllegalArgumentException("首版不允许写入标准命名空间节点");
        }
    }

    /** 电脑名必须解析到当前系统实际拥有的地址，不凭名称相似或同网段认定本机。 */
    static boolean isLocalHost(String host) {
        try {
            InetAddress[] resolved = InetAddress.getAllByName(host);
            var assigned = new HashSet<InetAddress>();
            var interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    var addresses = interfaces.nextElement().getInetAddresses();
                    while (addresses.hasMoreElements()) assigned.add(addresses.nextElement());
                }
            }
            return areLocalAddresses(resolved, assigned);
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    static boolean areLocalAddresses(InetAddress[] resolved, Set<InetAddress> assigned) {
        if (resolved.length == 0) return false;
        for (InetAddress address : resolved) {
            if (address.isAnyLocalAddress()
                    || address.isMulticastAddress()
                    || !(address.isLoopbackAddress() || assigned.contains(address))) return false;
        }
        return true;
    }

    public boolean acceptsEndpoint(String candidate) {
        try {
            URI actual = URI.create(candidate);
            validateEndpoint(actual);
            return endpoint.getScheme().equalsIgnoreCase(actual.getScheme())
                    && endpoint.getHost().equalsIgnoreCase(actual.getHost())
                    && endpoint.getPort() == actual.getPort()
                    && java.util.Objects.equals(endpoint.getPath(), actual.getPath())
                    && actual.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public EndpointDescription selectEndpoint(List<EndpointDescription> endpoints) {
        String token = identityType.equals("username") ? "UserName" : "Anonymous";
        return endpoints.stream()
                .filter(e -> acceptsEndpoint(e.getEndpointUrl()))
                .filter(
                        e ->
                                policy.getUri().equals(e.getSecurityPolicyUri())
                                        && mode == e.getSecurityMode())
                .filter(
                        e ->
                                e.getUserIdentityTokens() != null
                                        && java.util.Arrays.stream(e.getUserIdentityTokens())
                                                .anyMatch(
                                                        t ->
                                                                t.getTokenType() != null
                                                                        && t.getTokenType()
                                                                                .name()
                                                                                .equals(token)))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "没有匹配的端点：请核对 endpoint.url、发现地址、安全策略、模式及身份类型"));
    }

    static Properties read(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(reader);
        }
        return p;
    }

    static String required(Properties p, String key) {
        String value = p.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("缺少配置：" + key);
        return value;
    }

    private static String rawIdentifier(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("缺少配置：" + key);
        return value;
    }

    static boolean bool(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key, Boolean.toString(fallback)).trim();
        if (!value.equals("true") && !value.equals("false"))
            throw new IllegalArgumentException(key + " 必须为 true 或 false");
        return Boolean.parseBoolean(value);
    }
}

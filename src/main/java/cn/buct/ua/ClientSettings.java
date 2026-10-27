package cn.buct.ua;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;

/** 外部配置；不包含密码、私钥或自动信任策略。 */
public record ClientSettings(URI endpoint, SecurityPolicy policy, MessageSecurityMode mode,
        String identityType, String username, String passwordEnv, String applicationUri,
        String applicationName, long timeoutMs, Path pkiDirectory, String pkiPasswordEnv,
        Path nodesFile, boolean writesEnabled, String environment) {

    public static ClientSettings load(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        Properties p = read(absolute);
        URI endpoint = URI.create(required(p, "endpoint.url"));
        if (!"opc.tcp".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getPort() < 1 || endpoint.getUserInfo() != null) {
            throw new IllegalArgumentException("endpoint.url 必须是带主机和端口的 opc.tcp 地址，不能包含凭据");
        }
        SecurityPolicy policy = SecurityPolicy.valueOf(required(p, "security.policy"));
        MessageSecurityMode mode = MessageSecurityMode.valueOf(required(p, "security.mode"));
        // 首版要求安全通道；不自动降级，也不通过无加密端点发送用户凭据。
        if (policy == SecurityPolicy.None || mode != MessageSecurityMode.SignAndEncrypt) {
            throw new IllegalArgumentException("首版仅接受明确的安全策略和 SignAndEncrypt 模式");
        }
        String identity = required(p, "identity.type");
        if (!identity.equals("username") && !identity.equals("anonymous")) {
            throw new IllegalArgumentException("identity.type 仅支持 username 或 anonymous");
        }
        long timeout = Long.parseLong(p.getProperty("request.timeoutMs", "10000"));
        if (timeout < 1000 || timeout > 120000) throw new IllegalArgumentException("请求超时应为 1000—120000 毫秒");
        String applicationUri = required(p, "application.uri");
        if (!URI.create(applicationUri).isAbsolute()) throw new IllegalArgumentException("application.uri 必须是绝对 URI");
        return new ClientSettings(endpoint, policy, mode, identity,
                identity.equals("username") ? required(p, "identity.username") : "",
                p.getProperty("identity.passwordEnv", "UA_PASSWORD"), applicationUri,
                required(p, "application.name"), timeout,
                absolute.getParent().resolve(required(p, "pki.directory")).normalize(),
                p.getProperty("pki.passwordEnv", "UA_KEYSTORE_PASSWORD"),
                absolute.getParent().resolve(required(p, "nodes.file")).normalize(),
                bool(p, "writes.enabled", false), p.getProperty("environment", "simulation"));
    }

    public List<PointDefinition> points() throws IOException {
        Properties p = read(nodesFile);
        List<PointDefinition> result = new ArrayList<>();
        HashSet<String> aliases = new HashSet<>();
        for (String item : required(p, "nodes").split(",", -1)) {
            String alias = item.trim();
            if (alias.isEmpty() || !aliases.add(alias)) throw new IllegalArgumentException("点位别名为空或重复：" + alias);
            String prefix = "node." + alias + ".";
            result.add(new PointDefinition(alias, required(p, prefix + "namespaceUri"),
                    required(p, prefix + "identifierType"), required(p, prefix + "identifier"),
                    required(p, prefix + "dataType"), bool(p, prefix + "writable", false),
                    p.getProperty(prefix + "minimum"), p.getProperty(prefix + "maximum")));
        }
        return List.copyOf(result);
    }

    public PointDefinition point(String alias) throws IOException {
        return points().stream().filter(n -> n.alias().equals(alias)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未配置该点位别名：" + alias));
    }

    public void requireWrite(PointDefinition point, boolean confirmed) {
        if (!writesEnabled || !confirmed || !point.writable()) {
            throw new IllegalArgumentException("写入被拒绝：需要总开关、点位 writable=true 和 --确认 参数");
        }
        String host = endpoint.getHost();
        if (!environment.equals("simulation") || !(host.equals("127.0.0.1")
                || host.equalsIgnoreCase("localhost") || host.equals("[::1]") || host.equals("::1"))) {
            throw new IllegalArgumentException("首版写入仅支持明确标记 simulation 的本机回环端点");
        }
        if (point.namespaceUri().equals("http://opcfoundation.org/UA/")) {
            throw new IllegalArgumentException("首版不允许写入标准命名空间节点");
        }
    }

    public boolean acceptsEndpoint(String candidate) {
        try {
            URI actual = URI.create(candidate);
            return endpoint.getScheme().equalsIgnoreCase(actual.getScheme())
                    && endpoint.getHost().equalsIgnoreCase(actual.getHost())
                    && endpoint.getPort() == actual.getPort()
                    && java.util.Objects.equals(endpoint.getPath(), actual.getPath())
                    && actual.getUserInfo() == null;
        } catch (IllegalArgumentException e) { return false; }
    }

    static Properties read(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
        return p;
    }

    static String required(Properties p, String key) {
        String value = p.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("缺少配置：" + key);
        return value;
    }

    static boolean bool(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key, Boolean.toString(fallback)).trim();
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(key + " 必须为 true 或 false");
        return Boolean.parseBoolean(value);
    }
}

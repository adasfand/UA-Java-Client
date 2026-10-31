package cn.buct.ua;

import org.bouncycastle.util.IPAddress;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

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
        Objects.requireNonNull(pki, "pki");
        if (!"strict".equals(serverTrust) && !"first-use".equals(serverTrust)) {
            throw new IllegalArgumentException("security.serverTrust 仅支持 strict 或 first-use");
        }
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

    /** 首次准备本机配置；正常读取不会创建或覆盖文件。 */
    static void prepare(Path configFile, Path templates) throws IOException {
        if (Files.exists(configFile)) return;
        Files.createDirectories(configFile.toAbsolutePath().normalize().getParent());
        try {
            Files.copy(templates.resolve("client.properties"), configFile);
        } catch (FileAlreadyExistsException ignored) {
            /* 其他启动进程已创建个人配置，保留该文件。 */
        }
    }

    static URI parseEndpoint(String text) {
        URI parsed = URI.create(text.trim());
        validateEndpoint(parsed);
        return parsed;
    }

    static URI withHost(String address, String host) throws URISyntaxException {
        URI original = parseEndpoint(address);
        if (host == null || host.isBlank()) throw new IllegalArgumentException("请输入目标服务器 IP 或主机名");
        return parseEndpoint(
                new URI(
                                        original.getScheme(),
                                        null,
                                        host.trim(),
                                        original.getPort(),
                                        null,
                                        null,
                                        null)
                                .toString()
                        + Objects.toString(original.getRawPath(), ""));
    }

    /** 界面或调用方传入连接参数，保留部署安全配置；不改磁盘文件。 */
    ClientSettings withConnection(String serverAddress, String remoteHost, String selectedUsername)
            throws URISyntaxException {
        return withConnection(serverAddress, remoteHost, selectedUsername, identityType);
    }

    ClientSettings withConnection(
            String serverAddress,
            String remoteHost,
            String selectedUsername,
            String selectedIdentity)
            throws URISyntaxException {
        boolean addressProvided = serverAddress != null && !serverAddress.isBlank();
        boolean hostProvided = remoteHost != null && !remoteHost.isBlank();
        URI target = endpoint;
        if (addressProvided) {
            String text = serverAddress.trim();
            target =
                    text.contains("://")
                            ? parseEndpoint(text)
                            : withHost(endpoint.toString(), text);
        }
        if (hostProvided) target = withHost(target.toString(), remoteHost);
        return new ClientSettings(
                target,
                policy,
                mode,
                selectedIdentity == null ? identityType : selectedIdentity,
                selectedUsername == null ? username : selectedUsername.trim(),
                passwordEnv,
                applicationUri,
                applicationName,
                timeoutMs,
                pkiDirectory,
                pkiPasswordEnv,
                addressProvided || hostProvided ? target : discoveryEndpoint,
                pki,
                serverTrust);
    }

    public static ClientSettings load(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        return from(read(absolute), absolute);
    }

    enum ConnectionAction {
        CONNECT,
        QUICK,
        ACTUAL
    }

    /** 三个连接入口只改变地址来源，账号和安全配置共用同一套校验。 */
    static ClientSettings connectionTarget(
            Path file, ConnectionAction action, String address, String remoteHost, String username)
            throws IOException, URISyntaxException {
        return connectionTarget(file, action, address, remoteHost, username, null);
    }

    static ClientSettings connectionTarget(
            Path file,
            ConnectionAction action,
            String address,
            String remoteHost,
            String username,
            String identityType)
            throws IOException, URISyntaxException {
        Properties p = read(file);
        ClientSettings base = from(p, file).withConnection(null, null, username, identityType);
        if (action == ConnectionAction.ACTUAL
                && p.getProperty("connection.actualUrl", "").isBlank())
            throw new IllegalArgumentException("尚未配置现场服务器地址 connection.actualUrl，请向现场确认后填写配置。");
        return switch (action) {
            case CONNECT -> base.withConnection(address, remoteHost, username);
            case QUICK ->
                    base.withConnection(
                            p.getProperty("connection.quickUrl", "").isBlank()
                                    ? base.endpoint().toString()
                                    : parseEndpoint(p.getProperty("connection.quickUrl"))
                                            .toString(),
                            remoteHost,
                            username);
            case ACTUAL ->
                    base.withConnection(
                            parseEndpoint(required(p, "connection.actualUrl")).toString(),
                            null,
                            username);
        };
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
                p.getProperty("identity.username", "").trim(),
                p.getProperty("identity.passwordEnv", "UA_PASSWORD"),
                applicationUri,
                required(p, "application.name"),
                timeout,
                absolute.getParent().resolve(required(p, "pki.directory")).normalize(),
                p.getProperty("pki.passwordEnv", "UA_KEYSTORE_PASSWORD"),
                discovery.isEmpty() ? endpoint : URI.create(discovery),
                PkiSettings.from(p, required(p, "application.name")),
                p.getProperty("security.serverTrust", "strict").trim());
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
                                        "没有匹配的端点（登录方式："
                                                + (identityType.equals("anonymous")
                                                        ? "匿名"
                                                        : "用户名/密码")
                                                + "）：请核对服务器URL、安全策略、模式，以及服务器是否允许该登录方式"));
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

    /** 证书生成和现有 PKCS12 身份的部署参数；不保存口令。 */
    public record PkiSettings(
            String fileName,
            String alias,
            String commonName,
            String organization,
            List<String> dnsNames,
            List<String> ipAddresses,
            int validityDays) {
        public PkiSettings {
            if (fileName == null
                    || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                    || fileName.endsWith(".")
                    || fileName.equalsIgnoreCase("client.der")
                    || fileName.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) {
                throw new IllegalArgumentException("pki.file 必须是可移植的英文文件名，且不能占用公开证书 client.der");
            }
            if (alias == null
                    || alias.isBlank()
                    || commonName == null
                    || commonName.isBlank()
                    || organization == null
                    || organization.isBlank()) {
                throw new IllegalArgumentException("证书别名、名称和组织不能为空");
            }
            dnsNames = List.copyOf(dnsNames);
            ipAddresses = List.copyOf(ipAddresses);
            if (dnsNames.stream().anyMatch(String::isBlank)
                    || ipAddresses.stream().anyMatch(ip -> !IPAddress.isValid(ip))) {
                throw new IllegalArgumentException("证书 DNS 名不能为空，IP SAN 必须是有效 IPv4/IPv6 地址");
            }
            if (validityDays < 1 || validityDays > 3650)
                throw new IllegalArgumentException("证书有效天数必须为 1—3650");
        }

        static PkiSettings defaults(String applicationName) {
            return from(new Properties(), applicationName);
        }

        static PkiSettings from(Properties p, String applicationName) {
            return new PkiSettings(
                    p.getProperty("pki.file", "client.p12").trim(),
                    p.getProperty("pki.alias", "ua-client").trim(),
                    p.getProperty("pki.commonName", applicationName).trim(),
                    p.getProperty("pki.organization", "UA Development").trim(),
                    names(p.getProperty("pki.dnsNames", "")),
                    names(p.getProperty("pki.ipAddresses", "")),
                    Integer.parseInt(p.getProperty("pki.validityDays", "365").trim()));
        }

        private static List<String> names(String text) {
            if (text.isBlank()) return List.of();
            return Arrays.stream(text.split(",", -1)).map(String::trim).toList();
        }
    }
}

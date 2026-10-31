package cn.buct.ua;

import java.net.URI;
import java.net.URISyntaxException;

/** 连接表单的地址及端点转换；不修改磁盘上的原配置。 */
final class DesktopEndpoint {
    private DesktopEndpoint() {}

    static URI address(String text) {
        URI uri = URI.create(text.trim());
        if (!"opc.tcp".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getPort() < 1
                || uri.getPort() > 65535
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("请输入完整地址，例如 opc.tcp://主机:端口");
        }
        return uri;
    }

    static URI withHost(String address, String host) throws URISyntaxException {
        URI original = address(address);
        if (host == null || host.isBlank()) throw new IllegalArgumentException("请输入目标服务器 IP 或主机名");
        return address(
                new URI(
                                        original.getScheme(),
                                        null,
                                        host.trim(),
                                        original.getPort(),
                                        null,
                                        null,
                                        null)
                                .toString()
                        + java.util.Objects.toString(original.getRawPath(), ""));
    }

    /** 分别接收服务器地址和目标主机；IP 只替换主机部分，端口及路径来自地址或配置。 */
    static URI target(URI configured, String serverAddress, String remoteHost)
            throws URISyntaxException {
        URI target = configured;
        if (serverAddress != null && !serverAddress.isBlank()) {
            String text = serverAddress.trim();
            target = text.contains("://") ? address(text) : withHost(configured.toString(), text);
        }
        return remoteHost == null || remoteHost.isBlank()
                ? target
                : withHost(target.toString(), remoteHost);
    }

    static ClientSettings connection(
            ClientSettings base, URI address, URI discovery, String username) {
        return new ClientSettings(
                address,
                base.policy(),
                base.mode(),
                base.identityType(),
                username.trim(),
                base.passwordEnv(),
                base.applicationUri(),
                base.applicationName(),
                base.timeoutMs(),
                base.pkiDirectory(),
                base.pkiPasswordEnv(),
                discovery,
                base.pki(),
                base.serverTrust());
    }
}

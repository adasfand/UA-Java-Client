package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;

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

    static ClientSettings connection(
            ClientSettings base,
            URI address,
            URI discovery,
            EndpointDescription selected,
            String username) {
        SecurityPolicy policy = base.policy();
        MessageSecurityMode mode = base.mode();
        if (selected != null) {
            if (!address.toString().equals(selected.getEndpointUrl())) {
                throw new IllegalArgumentException("地址已改变，请重新查询并选择服务器");
            }
            policy =
                    Arrays.stream(SecurityPolicy.values())
                            .filter(p -> p.getUri().equals(selected.getSecurityPolicyUri()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("服务器使用了当前库不支持的安全策略"));
            mode = selected.getSecurityMode();
        }
        var settings =
                new ClientSettings(
                        address,
                        policy,
                        mode,
                        base.identityType(),
                        username.trim(),
                        base.passwordEnv(),
                        base.applicationUri(),
                        base.applicationName(),
                        base.timeoutMs(),
                        base.pkiDirectory(),
                        base.pkiPasswordEnv(),
                        base.nodesFile(),
                        false,
                        base.environment(),
                        discovery,
                        base.pki(),
                        base.serverTrust());
        if (selected != null) settings.selectEndpoint(java.util.List.of(selected));
        return settings;
    }

    static String label(EndpointDescription endpoint) {
        String policy = endpoint.getSecurityPolicyUri();
        if (policy != null) policy = policy.substring(policy.lastIndexOf('#') + 1);
        return endpoint.getEndpointUrl() + "  |  " + policy + "  |  " + endpoint.getSecurityMode();
    }
}

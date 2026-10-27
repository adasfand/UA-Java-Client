package cn.buct.ua;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.eclipse.milo.opcua.sdk.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.Stack;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

/** 第一阶段命令行入口；桌面 UI 后续复用同一通信层。 */
public final class Main {
    private Main() {}
    public static void main(String[] args) {
        int status;
        try { status = run(args); }
        catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            System.err.println("操作未完成：" + cause.getClass().getSimpleName());
            if (cause instanceof org.eclipse.milo.opcua.stack.core.UaException ua) {
                System.err.println("UA 状态码：" + ua.getStatusCode());
            }
            if (e instanceof IllegalArgumentException && e.getStackTrace().length > 0
                    && e.getStackTrace()[0].getClassName().startsWith("cn.buct.ua.")) {
                System.err.println("配置或操作原因：" + e.getMessage());
            }
            // 不打印第三方完整异常文本，避免意外输出凭据或会话内部信息。
            System.err.println("请核对配置、服务状态、双方证书、主机名和用户权限；未自动降级或重试写入。");
            status = 1;
        } finally { Stack.releaseSharedResources(); }
        System.exit(status);
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("帮助") || args[0].equals("--help")) {
            System.out.println("用法：java -jar target/ua-java-client.jar 命令 本机配置/客户端.properties [参数]");
            System.out.println("命令：检查配置、发现、初始化证书、信任服务器 证书路径 SHA256、浏览 [NodeId]、读取 [别名]、写入 别名 值 --确认");
            return 0;
        }
        if (args.length < 2) throw new IllegalArgumentException("需要配置文件路径");
        ClientSettings settings = ClientSettings.load(Path.of(args[1]));
        if ((args[0].equals("浏览") || args[0].equals("读取")) && args.length > 3) {
            throw new IllegalArgumentException("浏览或读取最多接受一个节点/别名参数");
        }
        switch (args[0]) {
            case "检查配置" -> {
                System.out.printf("配置有效：地址=%s | 策略=%s | 模式=%s | 身份=%s | 点位数=%d | 写入开关=%s%n",
                        settings.endpoint(), settings.policy(), settings.mode(), settings.identityType(), settings.points().size(), settings.writesEnabled());
                return 0;
            }
            case "发现" -> {
                var endpoints = DiscoveryClient.getEndpoints(settings.endpoint().toString(), transport -> transport
                                .setConnectTimeout(org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint(settings.timeoutMs()))
                                .setAcknowledgeTimeout(org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint(settings.timeoutMs())))
                        .get(settings.timeoutMs(), TimeUnit.MILLISECONDS);
                for (var endpoint : endpoints) {
                    System.out.printf("地址=%s | 策略=%s | 模式=%s | 身份=%s%n", endpoint.getEndpointUrl(),
                            endpoint.getSecurityPolicyUri(), endpoint.getSecurityMode(),
                            Arrays.toString(Arrays.stream(endpoint.getUserIdentityTokens()).map(t -> t.getTokenType().name()).toArray()));
                }
                if (endpoints.isEmpty()) throw new IllegalStateException("服务器未返回可用端点");
                System.out.println("发现成功；这不表示认证登录、读取或写入已经通过。");
                return 0;
            }
            case "初始化证书" -> { LocalPki.initialize(settings); return 0; }
            case "信任服务器" -> {
                if (args.length != 4) throw new IllegalArgumentException("需要公开证书路径和独立核对的 SHA-256");
                LocalPki.trustServer(settings, Path.of(args[2]), args[3]); return 0;
            }
            case "浏览", "读取", "写入" -> { /* 以下操作需要真实认证会话。 */ }
            default -> throw new IllegalArgumentException("未知命令");
        }
        // 在任何网络连接或凭据读取前拒绝未授权写命令。
        if (args[0].equals("写入")) {
            if (args.length != 5 || !args[4].equals("--确认")) throw new IllegalArgumentException("写入需要：别名 值 --确认");
            var point = settings.point(args[2]);
            settings.requireWrite(point, true);
            point.parseWriteValue(args[3]);
        }
        try (UaConnection connection = new UaConnection(settings)) {
            switch (args[0]) {
                case "浏览" -> connection.browse(args.length == 3 ? NodeId.parse(args[2]) : new NodeId(0, 85));
                case "读取" -> connection.read(args.length == 3 ? java.util.List.of(settings.point(args[2])) : settings.points());
                case "写入" -> connection.write(settings.point(args[2]), args[3], true);
                default -> throw new IllegalStateException("未知命令");
            }
        }
        return 0;
    }
}

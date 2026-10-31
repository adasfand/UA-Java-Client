package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.Stack;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

/** 启动桌面客户端，并提供部署用的配置和证书命令。 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("桌面")) {
            launchDesktop(Path.of(args.length > 1 ? args[1] : "config/local/client.properties"));
            return;
        }
        int status;
        try {
            status = run(args);
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            System.err.println("操作未完成：" + cause.getClass().getSimpleName());
            for (Throwable detail = e; detail != null; detail = detail.getCause()) {
                if (detail instanceof org.eclipse.milo.opcua.stack.core.UaException ua) {
                    System.err.println("UA 状态码：" + ua.getStatusCode());
                    break;
                }
            }
            if (cause instanceof java.nio.file.NoSuchFileException missing) {
                System.err.println("所需本机文件不存在：" + missing.getFile());
            }
            if ((e instanceof IllegalArgumentException || e instanceof IllegalStateException)
                    && e.getStackTrace().length > 0
                    && e.getStackTrace()[0].getClassName().startsWith("cn.buct.ua.")) {
                System.err.println("配置或操作原因：" + e.getMessage());
            }
            // 不打印第三方完整异常文本，避免意外输出凭据或会话内部信息。
            System.err.println("请核对配置、服务状态、双方证书、主机名和用户权限；未自动降级或重试写入。");
            status = 1;
        } finally {
            Stack.releaseSharedResources();
        }
        System.exit(status);
    }

    private static void launchDesktop(Path config) {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("桌面客户端需要图形桌面环境");
        SwingUtilities.invokeLater(
                () -> {
                    try {
                        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                        var font = new FontUIResource(Font.DIALOG, Font.PLAIN, 14);
                        for (Object key : java.util.List.copyOf(UIManager.getDefaults().keySet()))
                            if (UIManager.get(key) instanceof Font) UIManager.put(key, font);
                        ClientSettings.prepare(config, Path.of("config/templates"));
                        new ServerWindow(ClientSettings.load(config), config).setVisible(true);
                    } catch (Exception e) {
                        JOptionPane.showMessageDialog(
                                null,
                                "无法启动客户端。\n" + ServerWindow.failure(e),
                                "启动失败",
                                JOptionPane.ERROR_MESSAGE);
                    }
                });
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("帮助") || args[0].equals("--help")) {
            System.out.println(
                    "用法：java -jar target/ua-java-client.jar 命令 config/local/client.properties"
                            + " [参数]");
            System.out.println("命令：桌面、检查配置、初始化证书、信任服务器 证书路径 SHA256");
            return 0;
        }
        if (args.length < 2) throw new IllegalArgumentException("需要配置文件路径");
        ClientSettings settings = ClientSettings.load(Path.of(args[1]));
        switch (args[0]) {
            case "检查配置" -> {
                System.out.printf(
                        "配置有效：地址=%s | 策略=%s | 模式=%s | 身份=%s | 服务器信任=%s%n",
                        settings.endpoint(),
                        settings.policy(),
                        settings.mode(),
                        settings.identityType(),
                        settings.serverTrust());
                return 0;
            }
            case "初始化证书" -> {
                LocalPki.initialize(settings);
                return 0;
            }
            case "信任服务器" -> {
                if (args.length != 4) throw new IllegalArgumentException("需要公开证书路径和独立核对的 SHA-256");
                LocalPki.trustServer(settings, Path.of(args[2]), args[3]);
                return 0;
            }
            default -> throw new IllegalArgumentException("未知命令");
        }
    }
}

package cn.buct.ua;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

/** 桌面入口。与命令行入口共用配置、PKI 和 OPC UA 通信层。 */
public final class DesktopMain {
    private DesktopMain() {}

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("桌面客户端需要图形桌面环境");
        }
        Path config = Path.of(args.length == 0 ? "config/local/client.properties" : args[0]);
        SwingUtilities.invokeLater(
                () -> {
                    try {
                        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                        var font = new FontUIResource(Font.DIALOG, Font.PLAIN, 14);
                        for (Object key : java.util.List.copyOf(UIManager.getDefaults().keySet())) {
                            if (UIManager.get(key) instanceof Font) UIManager.put(key, font);
                        }
                        new ServerWindow(ClientSettings.load(config), config).setVisible(true);
                    } catch (Exception e) {
                        JOptionPane.showMessageDialog(
                                null,
                                "无法启动客户端，请检查配置文件：\n" + config + "\n" + ServerWindow.failure(e),
                                "启动失败",
                                JOptionPane.ERROR_MESSAGE);
                    }
                });
    }
}

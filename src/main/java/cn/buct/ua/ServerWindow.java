package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.Stack;
import org.eclipse.milo.opcua.stack.core.UaException;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

/** 仅包含服务器连接功能的主窗口；会话一直持有到断开或退出。 */
final class ServerWindow extends JFrame {
    private final JLabel status = new JLabel("未连接");
    private final JLabel server = new JLabel(" ");
    private final JMenuItem connect = new JMenuItem("连接…");
    private final JMenuItem disconnect = new JMenuItem("断开");
    private final JMenuItem points = new JMenuItem("浏览并选择点位…");
    private final DesktopCredentials credentials = new DesktopCredentials(LocalPki::secret);
    private final Path configFile;
    private ClientSettings lastSettings;
    private UaConnection connection;
    private boolean busy;

    ServerWindow(ClientSettings defaults, Path config) {
        super("OPC UA 客户端");
        this.lastSettings = defaults;
        this.configFile = config;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(680, 400));
        setSize(900, 560);
        setLocationRelativeTo(null);

        var menu = new JMenu("服务器");
        menu.add(connect);
        menu.add(disconnect);
        menu.addSeparator();
        var forget = new JMenuItem("清除本次记住的凭据");
        menu.add(forget);
        menu.addSeparator();
        var exit = new JMenuItem("退出");
        menu.add(exit);
        var bar = new JMenuBar();
        bar.add(menu);
        var pointMenu = new JMenu("点位");
        pointMenu.add(points);
        bar.add(pointMenu);
        setJMenuBar(bar);
        var information = new JPanel(new java.awt.GridLayout(0, 1, 0, 8));
        information.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        information.add(status);
        information.add(server);
        add(information, BorderLayout.NORTH);
        var configuration = new JLabel("配置：" + config);
        configuration.setBorder(BorderFactory.createEmptyBorder(8, 24, 12, 24));
        add(configuration, BorderLayout.SOUTH);
        updateMenu();
        connect.addActionListener(
                e -> new ServerConnectDialog(this, lastSettings).setVisible(true));
        disconnect.addActionListener(e -> disconnect(false));
        points.addActionListener(e -> browsePoints());
        forget.addActionListener(e -> credentials.clear());
        exit.addActionListener(e -> disconnect(true));
        addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        disconnect(true);
                    }
                });
    }

    void connected(UaConnection connected, ClientSettings settings) {
        connection = connected;
        lastSettings = settings;
        status.setText("会话已建立：认证成功");
        server.setText(
                settings.endpoint()
                        + "  |  "
                        + settings.policy()
                        + "  |  "
                        + (settings.identityType().equals("username")
                                ? settings.username()
                                : "匿名用户"));
        updateMenu();
        javax.swing.SwingUtilities.invokeLater(this::browsePoints);
    }

    UaConnection openConnection(ClientSettings settings) throws Exception {
        try (var attempt = credentials.begin(settings)) {
            UaConnection opened = new UaConnection(settings, attempt);
            try {
                attempt.succeeded();
                return opened;
            } catch (Exception e) {
                try {
                    opened.close();
                } catch (Exception cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
        }
    }

    private void browsePoints() {
        if (busy || connection == null) return;
        try {
            new PointBrowserDialog(this, connection, lastSettings, configFile).setVisible(true);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, failure(e), "点位提示", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void updateMenu() {
        connect.setEnabled(!busy && connection == null);
        disconnect.setEnabled(!busy && connection != null);
        points.setEnabled(!busy && connection != null);
    }

    private void disconnect(boolean exit) {
        if (busy) return;
        busy = true;
        updateMenu();
        status.setText(exit ? "正在退出…" : "正在断开…");
        UaConnection current = connection;
        connection = null;
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    if (current != null) current.close();
                } finally {
                    if (exit) {
                        credentials.close();
                        Stack.releaseSharedResources();
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                busy = false;
                try {
                    get();
                    status.setText("未连接");
                } catch (Exception e) {
                    status.setText("会话已关闭；断开清理未完整确认");
                    if (!exit)
                        JOptionPane.showMessageDialog(
                                ServerWindow.this, failure(e), "断开提示", JOptionPane.WARNING_MESSAGE);
                }
                server.setText(" ");
                updateMenu();
                if (exit) {
                    dispose();
                    System.exit(0);
                }
            }
        }.execute();
    }

    /** 只输出本项目的校验提示或 UA 状态码，不将第三方异常中的会话信息带入界面。 */
    static String failure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof UaException ua) return "OPC UA 状态：" + ua.getStatusCode();
            if (t instanceof java.nio.file.NoSuchFileException) {
                return "缺少配置或客户端证书文件，请检查配置路径及 pki 目录。";
            }
            if ((t instanceof IllegalArgumentException || t instanceof IllegalStateException)
                    && t.getStackTrace().length > 0
                    && t.getStackTrace()[0].getClassName().startsWith("cn.buct.ua.")) {
                return t.getMessage();
            }
        }
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return "操作未完成（" + cause.getClass().getSimpleName() + "），请核对服务器地址、服务状态、证书和账号密码。";
    }
}

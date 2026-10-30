package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.Stack;
import org.eclipse.milo.opcua.stack.core.UaException;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

/** 通信演示窗口；配置、证书准备、选点和按需读写共用现有通信层。 */
final class ServerWindow extends JFrame {
    private final JLabel status = new JLabel("未连接");
    private final JLabel server = new JLabel(" ");
    private final JMenuItem connect = new JMenuItem("连接…");
    private final JMenuItem disconnect = new JMenuItem("断开");
    private final JMenuItem configuration = new JMenuItem("连接配置…");
    private final JMenuItem points = new JMenuItem("浏览并选择点位…");
    private final DesktopCredentials credentials =
            new DesktopCredentials(LocalPki::graphicalSecret);
    private final Path configFile;
    private final DataPanel data;
    private final JMenuItem initialize = new JMenuItem("初始化客户端证书…");
    private final JMenuItem exportCertificate = new JMenuItem("导出客户端公开证书…");
    private final JMenuItem trustServer = new JMenuItem("导入服务器公开证书…");
    private ClientSettings lastSettings;
    private UaConnection connection;
    private boolean busy;

    ServerWindow(ClientSettings defaults, Path config) {
        super("OPC UA 客户端");
        this.lastSettings = defaults;
        this.configFile = config;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(950, 620));
        setSize(1120, 720);
        setLocationRelativeTo(null);

        var menu = new JMenu("服务器");
        menu.add(connect);
        menu.add(disconnect);
        menu.add(configuration);
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
        var certificates = new JMenu("证书");
        certificates.add(initialize);
        certificates.add(exportCertificate);
        certificates.add(trustServer);
        var showDirectory = new JMenuItem("打开证书目录");
        certificates.add(showDirectory);
        bar.add(certificates);
        setJMenuBar(bar);
        var information = new JPanel(new java.awt.GridLayout(0, 1, 0, 8));
        information.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        information.add(status);
        information.add(server);
        add(information, BorderLayout.NORTH);
        data = new DataPanel(this, configFile);
        add(data, BorderLayout.CENTER);
        var configurationLabel = new JLabel("配置：" + config);
        configurationLabel.setBorder(BorderFactory.createEmptyBorder(8, 24, 12, 24));
        add(configurationLabel, BorderLayout.SOUTH);
        updateMenu();
        connect.addActionListener(e -> openConnectionDialog());
        configuration.addActionListener(e -> editConfiguration());
        disconnect.addActionListener(e -> disconnect(false));
        points.addActionListener(e -> browsePoints());
        initialize.addActionListener(e -> initializeCertificate());
        exportCertificate.addActionListener(e -> exportCertificate());
        trustServer.addActionListener(e -> importServerCertificate());
        showDirectory.addActionListener(
                e -> {
                    try {
                        Path directory = ClientSettings.load(configFile).pkiDirectory();
                        Files.createDirectories(directory);
                        java.awt.Desktop.getDesktop().open(directory.toFile());
                    } catch (Exception error) {
                        notice(error, "证书目录");
                    }
                });
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

    private void openConnectionDialog() {
        if (busy || connection != null) return;
        try {
            var settings = ClientSettings.load(configFile);
            if (!Files.exists(settings.pkiDirectory().resolve(settings.pki().fileName()))) {
                if (JOptionPane.showConfirmDialog(
                                this,
                                "尚未发现客户端身份。是否先生成证书？\n已有身份不会被覆盖。",
                                "首次连接",
                                JOptionPane.YES_NO_OPTION)
                        == JOptionPane.YES_OPTION) initializeCertificate();
                return;
            }
            new ServerConnectDialog(this, configFile).setVisible(true);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, failure(e), "配置提示", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void editConfiguration() {
        if (busy || connection != null) return;
        try {
            new ClientConfigurationDialog(this, configFile, this::configurationSaved)
                    .setVisible(true);
            data.reload();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, failure(e), "配置提示", JOptionPane.WARNING_MESSAGE);
        }
    }

    void configurationSaved(ClientSettings settings) {
        lastSettings = settings;
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
        data.connection(connected);
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
            data.reload();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, failure(e), "点位提示", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void updateMenu() {
        connect.setEnabled(!busy && connection == null);
        disconnect.setEnabled(!busy && connection != null);
        configuration.setEnabled(!busy && connection == null);
        points.setEnabled(!busy && connection != null);
        initialize.setEnabled(!busy && connection == null);
        exportCertificate.setEnabled(!busy && connection == null);
        trustServer.setEnabled(!busy && connection == null);
        data.externalBusy(busy);
    }

    void operationBusy(boolean active, String message) {
        busy = active;
        if (!active && connection != null && connection.isClosed()) {
            connection = null;
            data.connection(null);
        }
        status.setText(message != null ? message : connection == null ? "未连接" : "会话已建立：认证成功");
        updateMenu();
    }

    private void initializeCertificate() {
        if (busy || connection != null) return;
        try {
            var settings = ClientSettings.load(configFile);
            if (Files.exists(settings.pkiDirectory().resolve(settings.pki().fileName()))) {
                certificateReady(settings, "客户端身份已存在，连接时将复用，不重新生成。");
                return;
            }
            certificateTask(
                    () -> LocalPki.initialize(settings, LocalPki::graphicalSecret),
                    () -> certificateReady(settings, "客户端证书已生成。"));
        } catch (Exception e) {
            notice(e, "证书初始化");
        }
    }

    private void exportCertificate() {
        try {
            var settings = ClientSettings.load(configFile);
            certificateTask(
                    () -> {
                        var identity = LocalPki.load(settings, LocalPki::graphicalSecret);
                        Files.write(
                                settings.pkiDirectory().resolve("client.der"),
                                identity.certificate().getEncoded());
                    },
                    () -> certificateReady(settings, "已从现有身份导出公开证书；私钥未改变。"));
        } catch (Exception e) {
            notice(e, "导出证书");
        }
    }

    private void certificateReady(ClientSettings settings, String message) {
        JOptionPane.showMessageDialog(
                this,
                message
                        + "\n公开证书："
                        + settings.pkiDirectory().resolve("client.der")
                        + "\n首次使用时，请在 KEP 的“受信任的客户端”导入 client.der，再回到本窗口连接。"
                        + "\nclient.p12 含私钥，保留在本机。",
                "客户端证书",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void importServerCertificate() {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("选择服务器公开证书（strict 模式使用）");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        String fingerprint = JOptionPane.showInputDialog(this, "输入独立核对过的服务器证书 SHA-256 指纹：");
        if (fingerprint == null) return;
        try {
            var settings = ClientSettings.load(configFile);
            Path file = chooser.getSelectedFile().toPath();
            certificateTask(
                    () -> LocalPki.trustServer(settings, file, fingerprint),
                    () ->
                            JOptionPane.showMessageDialog(
                                    this,
                                    "服务器公开证书已导入人工信任库。\n"
                                            + "strict 模式使用此信任库；first-use 模式使用自己的首次记录。"));
        } catch (Exception e) {
            notice(e, "导入证书");
        }
    }

    private void certificateTask(CertificateAction task, Runnable complete) {
        operationBusy(true, "正在处理证书，请完成密码窗口…");
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception {
                task.run();
                return null;
            }

            protected void done() {
                operationBusy(false, null);
                try {
                    get();
                    complete.run();
                } catch (Exception e) {
                    notice(e, "证书提示");
                }
            }
        }.execute();
    }

    @FunctionalInterface
    private interface CertificateAction {
        void run() throws Exception;
    }

    private void notice(Exception e, String title) {
        JOptionPane.showMessageDialog(this, failure(e), title, JOptionPane.WARNING_MESSAGE);
    }

    private void disconnect(boolean exit) {
        if (busy) return;
        busy = true;
        updateMenu();
        status.setText(exit ? "正在退出…" : "正在断开…");
        UaConnection current = connection;
        connection = null;
        data.connection(null);
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

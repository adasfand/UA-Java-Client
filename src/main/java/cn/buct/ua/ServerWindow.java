package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.Stack;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** 服务器连接使用独立小窗口；主窗口只提供按地址读取、写入及结果。 */
final class ServerWindow extends JFrame {
    private final Path configFile;
    private ClientSettings settings;
    private final JMenuItem connectMenu = new JMenuItem("连接…");
    private final JMenuItem disconnect = new JMenuItem("断开");
    private final JLabel serverStatus = new JLabel("服务器：未连接");
    private final JTextField serverAddress = new JTextField(32);
    private final JTextField remoteHost = new JTextField(32);
    private final JTextField username = new JTextField(32);
    private final JPasswordField password = new JPasswordField(32);
    private final JButton connect = new JButton("连接");
    private final JButton cancel = new JButton("取消");
    private final JButton prepareCertificate = new JButton("首次准备客户端证书…");
    private final JLabel certificateState = new JLabel();
    private final JPanel accountPanel = new JPanel(new GridBagLayout());
    private final JTextArea connectionMessage = note("", 2, 44);
    private final JDialog connectionDialog;
    private final JTextField readAddress = new JTextField("ns=0;i=2258");
    private final JButton read = new JButton("读取");
    private final JTextArea result = new JTextArea(5, 52);
    private final JTextField writeAddress = new JTextField();
    private final JTextField writeValue = new JTextField();
    private final JButton write = new JButton("写入");
    private final JTextField status = new JTextField("请从“服务器 → 连接…”设置连接");
    private UaClient client;
    private boolean busy;
    private boolean closing;
    private boolean settingConnectionFields;
    private char[] certificatePassword = new char[0];
    private IdentityVersion cachedIdentity;

    private record IdentityVersion(Path file, String alias, FileTime modified, long size) {}

    private record CertificateFiles(Path identity, Path publicCertificate) {}

    ServerWindow(ClientSettings defaults, Path config) {
        super("OPC UA 客户端");
        configFile = config;
        settings = defaults;
        client = new UaClient(config);
        serverAddress.setText(defaults.endpoint().toString());
        remoteHost.setText(defaults.endpoint().getHost());
        username.setText(defaults.username());
        serverAddress.setToolTipText("复制 KEP 的“服务器端点”列表中的一条完整 URL");
        remoteHost.setToolTipText("填写运行 KEP 服务器的电脑 IP 或匹配的主机名");
        password.setToolTipText("在 KEP 用户管理器中给该用户设置的密码");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(700, 420));
        setSize(820, 520);
        setLocationRelativeTo(null);

        var menu = new JMenu("服务器");
        menu.add(connectMenu);
        menu.add(disconnect);
        var menuBar = new JMenuBar();
        menuBar.add(menu);
        setJMenuBar(menuBar);

        var content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        content.add(serverStatus, BorderLayout.NORTH);
        var operations = new JPanel(new BorderLayout(10, 10));
        var reading = new JPanel(new BorderLayout(8, 8));
        reading.setBorder(BorderFactory.createTitledBorder("读取"));
        reading.add(addressLine("地址", readAddress, read), BorderLayout.NORTH);
        result.setEditable(false);
        result.setLineWrap(true);
        result.setWrapStyleWord(true);
        result.setText("尚未读取");
        reading.add(new JScrollPane(result), BorderLayout.CENTER);
        operations.add(reading, BorderLayout.CENTER);
        var writing = new JPanel(new GridBagLayout());
        writing.setBorder(BorderFactory.createTitledBorder("写入"));
        row(writing, 0, "地址", writeAddress);
        row(writing, 1, "写入值", writeValue);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.add(write);
        var c = constraints(2);
        c.gridx = 1;
        writing.add(actions, c);
        operations.add(writing, BorderLayout.SOUTH);
        content.add(operations, BorderLayout.CENTER);
        status.setEditable(false);
        content.add(status, BorderLayout.SOUTH);
        setContentPane(content);
        connectionDialog = buildConnectionDialog();
        connectMenu.addActionListener(e -> showConnection());
        disconnect.addActionListener(e -> disconnect());
        connect.addActionListener(e -> connect());
        cancel.addActionListener(e -> connectionDialog.setVisible(false));
        prepareCertificate.addActionListener(e -> prepareCertificate());
        read.addActionListener(e -> read());
        write.addActionListener(e -> write());
        clearAccountPasswordWhenTargetChanges();
        addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        closing = true;
                        updateControls();
                        if (busy) status.setText("等待当前操作结束后退出…");
                        else finishClose();
                    }
                });
        updateControls();
    }

    private JDialog buildConnectionDialog() {
        var dialog = new JDialog(this, "服务器连接", true);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        if (!busy) dialog.setVisible(false);
                    }
                });
        var panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        var server = new JPanel(new GridBagLayout());
        server.setBorder(BorderFactory.createTitledBorder("OPC UA 服务器"));
        row(server, 0, "服务器地址", serverAddress);
        row(server, 1, "目标服务器 IP", remoteHost);
        var help = constraints(2);
        help.gridwidth = 2;
        server.add(note("服务器地址：复制 KEP“OPC UA 配置管理器 → 服务器端点”中的一条 URL。", 2, 44), help);
        panel.add(server, BorderLayout.NORTH);
        accountPanel.setBorder(BorderFactory.createTitledBorder("KEPServerEX 用户"));
        row(accountPanel, 0, "用户名", username);
        row(accountPanel, 1, "用户密码", password);
        var accountHelp = constraints(2);
        accountHelp.gridwidth = 2;
        accountPanel.add(note("填写 KEP 用户管理器中的用户及密码，不是用户组名称。", 2, 44), accountHelp);
        panel.add(accountPanel, BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout(8, 8));
        var certificates = new JPanel(new BorderLayout(8, 6));
        certificates.add(certificateState, BorderLayout.NORTH);
        var prepare = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        prepare.add(prepareCertificate);
        certificates.add(prepare, BorderLayout.CENTER);
        footer.add(certificates, BorderLayout.NORTH);
        footer.add(connectionMessage, BorderLayout.CENTER);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.add(connect);
        actions.add(cancel);
        footer.add(actions, BorderLayout.SOUTH);
        panel.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        dialog.getRootPane().setDefaultButton(connect);
        dialog.pack();
        dialog.setMinimumSize(dialog.getSize());
        dialog.setLocationRelativeTo(this);
        return dialog;
    }

    private void showConnection() {
        if (busy || closing || client.isConnected()) return;
        try {
            settings = ClientSettings.load(configFile);
            connectionMessage.setText("");
            updateControls();
            connectionDialog.pack();
            connectionDialog.setLocationRelativeTo(this);
            connectionDialog.setVisible(true);
        } catch (Exception e) {
            status.setText(failure(e));
        }
    }

    private void clearAccountPasswordWhenTargetChanges() {
        var listener =
                new DocumentListener() {
                    private void changed() {
                        if (!settingConnectionFields) password.setText("");
                    }

                    @Override
                    public void insertUpdate(DocumentEvent e) {
                        changed();
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e) {
                        changed();
                    }

                    @Override
                    public void changedUpdate(DocumentEvent e) {
                        changed();
                    }
                };
        serverAddress.getDocument().addDocumentListener(listener);
        remoteHost.getDocument().addDocumentListener(listener);
        username.getDocument().addDocumentListener(listener);
    }

    private Path identityFile() {
        return settings.pkiDirectory().resolve(settings.pki().fileName());
    }

    private IdentityVersion identityVersion() throws Exception {
        Path file = identityFile().toAbsolutePath().normalize();
        return new IdentityVersion(
                file, settings.pki().alias(), Files.getLastModifiedTime(file), Files.size(file));
    }

    private char[] askCertificatePassword(boolean creating) throws Exception {
        if (!creating && certificatePassword.length > 0 && identityVersion().equals(cachedIdentity))
            return certificatePassword.clone();
        clearCertificatePassword();
        var input = new JPasswordField(26);
        var confirm = new JPasswordField(26);
        var panel = new JPanel(new BorderLayout(8, 10));
        String explanation =
                creating
                        ? "现在还没有本机客户端证书。请自行设置一个新密码，程序会用它保护即将生成的 client.p12（本机私钥库）。无需提供已有证书密码。此密码与"
                              + " KEP 用户密码无关。"
                        : "请打开已有的本机 client.p12：输入你首次创建这个文件时设置的密码。它仅用于本机加载 Java 客户端私钥，不是 KEP 用户密码。";
        panel.add(note(explanation, 4, 38), BorderLayout.NORTH);
        var fields = new JPanel(new GridBagLayout());
        row(fields, 0, creating ? "设置新密码" : "client.p12 密码", input);
        if (creating) row(fields, 1, "再次输入新密码", confirm);
        panel.add(fields, BorderLayout.CENTER);
        try {
            while (true) {
                int choice =
                        JOptionPane.showConfirmDialog(
                                connectionDialog,
                                panel,
                                creating ? "首次准备：设置新密码并生成客户端证书" : "打开已有的本机 client.p12",
                                JOptionPane.OK_CANCEL_OPTION,
                                JOptionPane.PLAIN_MESSAGE);
                if (choice != JOptionPane.OK_OPTION) return null;
                char[] entered = input.getPassword();
                char[] repeated = confirm.getPassword();
                try {
                    if (entered.length == 0) {
                        JOptionPane.showMessageDialog(
                                connectionDialog,
                                creating
                                        ? "请先为即将生成的 client.p12 设置一个新密码。"
                                        : "请输入首次创建本机 client.p12 时设置的密码。");
                    } else if (creating && !Arrays.equals(entered, repeated)) {
                        JOptionPane.showMessageDialog(connectionDialog, "两次密码不一致，请重新填写。");
                    } else {
                        return entered.clone();
                    }
                } finally {
                    Arrays.fill(entered, '\0');
                    Arrays.fill(repeated, '\0');
                }
            }
        } finally {
            input.setText("");
            confirm.setText("");
        }
    }

    private void rememberCertificatePassword(char[] value) {
        clearCertificatePassword();
        try {
            cachedIdentity = identityVersion();
        } catch (Exception unavailable) {
            // 缓存只是本次进程的便利措施，不能把已成功生成或连接误报为失败。
            return;
        }
        certificatePassword = value.clone();
    }

    private void clearCertificatePassword() {
        Arrays.fill(certificatePassword, '\0');
        certificatePassword = new char[0];
        cachedIdentity = null;
    }

    private void prepareCertificate() {
        if (busy || closing) return;
        final char[] secret;
        try {
            settings = ClientSettings.load(configFile);
            if (Files.exists(identityFile())) {
                updateControls();
                return;
            }
            secret = askCertificatePassword(true);
            if (secret == null) return;
        } catch (Exception e) {
            connectionMessage.setText(failure(e));
            return;
        }
        runTask(
                () -> {
                    try {
                        client.close();
                        client = new UaClient(configFile);
                        Path certificate = client.initializeCertificate(secret);
                        Path identity = identityFile();
                        if (!Files.isRegularFile(identity) || !Files.isRegularFile(certificate))
                            throw new IllegalStateException("证书文件未完整生成，请查看本机目录权限后重试。");
                        rememberCertificatePassword(secret);
                        return new CertificateFiles(identity, certificate);
                    } catch (Exception e) {
                        clearCertificatePassword();
                        throw e;
                    } finally {
                        Arrays.fill(secret, '\0');
                    }
                },
                files -> {
                    connectionMessage.setText("证书已生成。请将 client.der 导入 KEP 的受信任客户端并重新初始化，再点击连接。");
                    status.setText("本机客户端证书已生成，尚未连接服务器");
                    var message =
                            note(
                                    "生成成功，文件实际位置如下：\n\n"
                                            + "交给 KEP 导入的公开证书：\n"
                                            + files.publicCertificate()
                                            + "\n\n留在这台电脑的私钥库：\n"
                                            + files.identity()
                                            + "\n\nclient.p12 由刚才设置的新密码保护；公开的 client.der 不需要这个密码。"
                                            + "\n请在 KEP 的“受信任的客户端”导入 client.der，重新初始化后再连接。",
                                    12,
                                    48);
                    int choice =
                            JOptionPane.showOptionDialog(
                                    connectionDialog,
                                    message,
                                    "客户端证书已生成",
                                    JOptionPane.DEFAULT_OPTION,
                                    JOptionPane.INFORMATION_MESSAGE,
                                    null,
                                    new String[] {"打开证书目录", "关闭"},
                                    "打开证书目录");
                    if (choice == 0) {
                        try {
                            if (!Desktop.isDesktopSupported()
                                    || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
                                throw new IllegalStateException("当前桌面不支持打开目录，请按显示的路径手动打开。");
                            Desktop.getDesktop().open(files.identity().getParent().toFile());
                        } catch (Exception e) {
                            JOptionPane.showMessageDialog(
                                    connectionDialog,
                                    "请手动打开证书目录：\n" + files.identity().getParent());
                        }
                    }
                },
                "正在生成本机客户端证书…");
    }

    private void connect() {
        if (busy || closing || client.isConnected()) return;
        String endpoint = serverAddress.getText().trim();
        String host = remoteHost.getText().trim();
        String user = username.getText().trim();
        char[] userSecret = password.getPassword();
        final char[] keySecret;
        try {
            settings = ClientSettings.load(configFile);
            if (!Files.exists(identityFile()))
                throw new IllegalArgumentException("请先点击“首次准备客户端证书…”，设置新密码并生成身份，再完成双方信任。");
            DesktopEndpoint.withHost(endpoint, host);
            if (settings.identityType().equals("username")
                    && (user.isEmpty() || userSecret.length == 0))
                throw new IllegalArgumentException("请填写 KEP 用户管理器中创建的用户名及该用户的密码。");
            keySecret = askCertificatePassword(false);
            if (keySecret == null) {
                Arrays.fill(userSecret, '\0');
                return;
            }
        } catch (Exception e) {
            Arrays.fill(userSecret, '\0');
            connectionMessage.setText(failure(e));
            return;
        }
        runTask(
                () -> {
                    try {
                        client.close();
                        client = new UaClient(configFile);
                        client.connect(endpoint, host, user, userSecret, keySecret);
                        rememberCertificatePassword(keySecret);
                        String actual = client.endpoint().toString();
                        try {
                            new ClientConfiguration(configFile)
                                    .save(
                                            java.util.Map.of(
                                                    "endpoint.url",
                                                    actual,
                                                    "endpoint.discoveryUrl",
                                                    actual,
                                                    "identity.username",
                                                    user));
                            return "连接成功";
                        } catch (Exception saveError) {
                            return "连接成功；连接参数未保存：" + failure(saveError);
                        }
                    } catch (Exception e) {
                        clearCertificatePassword();
                        throw e;
                    } finally {
                        Arrays.fill(userSecret, '\0');
                        Arrays.fill(keySecret, '\0');
                    }
                },
                message -> {
                    settingConnectionFields = true;
                    try {
                        serverAddress.setText(client.endpoint().toString());
                        remoteHost.setText(client.endpoint().getHost());
                    } finally {
                        settingConnectionFields = false;
                    }
                    serverStatus.setText(
                            "服务器："
                                    + client.endpoint()
                                    + "  |  "
                                    + (settings.identityType().equals("username") ? user : "匿名"));
                    result.setText("尚未读取");
                    connectionDialog.setVisible(false);
                    status.setText(message);
                },
                "正在连接服务器…");
    }

    private void disconnect() {
        if (busy || closing) return;
        runTask(
                () -> {
                    client.close();
                    return null;
                },
                ignored -> {
                    client = new UaClient(configFile);
                    serverStatus.setText("服务器：未连接");
                    result.setText("尚未读取");
                    status.setText("已断开");
                },
                "正在断开…");
    }

    private void read() {
        String address = readAddress.getText();
        result.setText("正在读取…");
        runTask(
                () -> client.readData(address),
                value -> {
                    result.setText(
                            "地址："
                                    + address
                                    + "\n值："
                                    + valueText(value)
                                    + "\n质量："
                                    + value.getStatusCode()
                                    + "\n源时间："
                                    + (value.getSourceTime() == null
                                            ? "无"
                                            : value.getSourceTime().getJavaInstant()));
                    status.setText("读取成功");
                },
                "正在读取…");
    }

    private void write() {
        String address = writeAddress.getText();
        String value = writeValue.getText();
        runTask(
                () -> client.write(address, value),
                code -> status.setText("写入服务已接受：" + code),
                "正在写入…");
    }

    private static String valueText(DataValue data) {
        Object value = data.getValue() == null ? null : data.getValue().getValue();
        if (value == null) return "null";
        if (!value.getClass().isArray()) return value.toString();
        var values = new java.util.ArrayList<String>();
        for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++)
            values.add(String.valueOf(java.lang.reflect.Array.get(value, i)));
        return values.toString();
    }

    private <T> void runTask(Callable<T> task, Consumer<T> complete, String progress) {
        if (busy || closing) return;
        busy = true;
        status.setText(progress);
        if (connectionDialog.isVisible()) connectionMessage.setText(progress);
        updateControls();
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return task.call();
            }

            @Override
            protected void done() {
                try {
                    T value = get();
                    if (!closing) complete.accept(value);
                } catch (Exception e) {
                    String text = failure(e);
                    status.setText(text);
                    if (connectionDialog.isVisible()) connectionMessage.setText(text);
                    if (progress.equals("正在读取…")) result.setText("读取未完成");
                } finally {
                    busy = false;
                    if (closing) finishClose();
                    else updateControls();
                }
            }
        }.execute();
    }

    private void updateControls() {
        boolean available = !busy && !closing;
        boolean connected = available && client.isConnected();
        boolean needsCertificate = !Files.exists(identityFile());
        accountPanel.setVisible(settings.identityType().equals("username"));
        certificateState.setText(needsCertificate ? "首次使用：设置新密码，生成这台电脑的客户端证书。" : "本机客户端身份：已准备");
        prepareCertificate.setVisible(needsCertificate);
        prepareCertificate.setEnabled(available && !connected && needsCertificate);
        serverAddress.setEnabled(available && !connected);
        remoteHost.setEnabled(available && !connected);
        username.setEnabled(available && !connected);
        password.setEnabled(available && !connected);
        connectMenu.setEnabled(available && !connected);
        connect.setEnabled(available && !connected && !needsCertificate);
        cancel.setEnabled(available);
        disconnect.setEnabled(available && connected);
        readAddress.setEnabled(available);
        writeAddress.setEnabled(available);
        writeValue.setEnabled(available);
        read.setEnabled(available && connected);
        write.setEnabled(available && connected);
    }

    private void finishClose() {
        busy = true;
        status.setText("正在退出…");
        updateControls();
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    client.close();
                } finally {
                    Stack.releaseSharedResources();
                }
                return null;
            }

            @Override
            protected void done() {
                password.setText("");
                clearCertificatePassword();
                connectionDialog.dispose();
                dispose();
                System.exit(0);
            }
        }.execute();
    }

    private static JTextArea note(String text, int rows, int columns) {
        var area = new JTextArea(text, rows, columns);
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(UIManager.getFont("Label.font"));
        return area;
    }

    private static GridBagConstraints constraints(int row) {
        var c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(4, 8, 4, 8);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private static void row(JPanel panel, int row, String label, java.awt.Component field) {
        var c = constraints(row);
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(field, c);
    }

    private static JPanel addressLine(String label, JTextField address, JButton action) {
        var panel = new JPanel(new BorderLayout(8, 0));
        panel.add(new JLabel(label), BorderLayout.WEST);
        panel.add(address, BorderLayout.CENTER);
        panel.add(action, BorderLayout.EAST);
        return panel;
    }

    /** 面向操作者显示本项目错误与UA状态，不输出内部凭据配置。 */
    static String failure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof UaException ua) return "OPC UA 状态：" + ua.getStatusCode();
            if (t instanceof java.security.UnrecoverableKeyException)
                return "无法打开本机 client.p12，请核对首次创建它时设置的密码。";
            if (t instanceof java.nio.file.NoSuchFileException)
                return "所需本机文件不存在，请检查连接配置或先准备本机客户端证书。";
            if (t instanceof java.net.URISyntaxException) return "请填写完整服务器 URL 和有效的目标服务器 IP / 主机名。";
            if ((t instanceof IllegalArgumentException || t instanceof IllegalStateException)
                    && t.getStackTrace().length > 0
                    && t.getStackTrace()[0].getClassName().startsWith("cn.buct.ua."))
                return t.getMessage();
        }
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return "操作未完成（" + cause.getClass().getSimpleName() + "），请核对服务器地址、本机证书密码及 KEP 账号。";
    }
}

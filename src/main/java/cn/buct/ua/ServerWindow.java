package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.Stack;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** 连接小窗口保留旧版五种操作；三个连接入口共用同一套认证与读写封装。 */
final class ServerWindow extends JFrame {
    private final Path configFile;
    private ClientSettings settings;
    private UaClient client;
    private boolean busy;
    private boolean closing;
    private boolean settingEndpoint;
    private final JMenuItem openConnection = new JMenuItem("连接服务器…");
    private final JMenuItem disconnect = new JMenuItem("断开");
    private final JDialog connectionDialog;
    private final JTextField connectionStatus = new JTextField();
    private final DefaultListModel<String> endpointUrls = new DefaultListModel<>();
    private final JList<String> servers = new JList<>(endpointUrls);
    private final JTextField serverAddress = new JTextField(32);
    private final JTextField remoteHost = new JTextField(32);
    private final JTextField username = new JTextField(32);
    private final JPasswordField password = new JPasswordField(32);
    private final JPasswordField keystorePassword = new JPasswordField(32);
    private final JPasswordField confirmation = new JPasswordField(32);
    private final JPanel accountPanel = new JPanel(new GridBagLayout());
    private final JPanel confirmationPanel = new JPanel(new GridBagLayout());
    private final JButton connect = new JButton("连接");
    private final JButton search = new JButton("查询");
    private final JButton cancel = new JButton("取消");
    private final JButton quickConnect = new JButton("一键连接");
    private final JButton actualConnect = new JButton("实际连接");
    private final JButton prepareCertificate = new JButton("首次生成客户端证书");
    private final JTextField namespace = new JTextField("ns=0");
    private final JTextField identifier = new JTextField("i=2258");
    private final JTextField writeValue = new JTextField();
    private final JButton read = new JButton("读取");
    private final JButton write = new JButton("写入");
    private final JTextArea result = new JTextArea(4, 52);
    private final JTextField status = new JTextField("请从“服务器 → 连接服务器…”打开连接窗口");

    ServerWindow(ClientSettings defaults, Path config) {
        super("OPC UA 客户端");
        configFile = config;
        settings = defaults;
        client = new UaClient(config);
        serverAddress.setText(defaults.endpoint().toString());
        remoteHost.setText(defaults.endpoint().getHost());
        username.setText(defaults.username());
        serverAddress.setToolTipText("填写服务器的 opc.tcp://IP:端口/路径；只填 IP 时沿用配置中的端口和路径");
        remoteHost.setToolTipText("目标服务器IP或主机名；查询、连接及一键连接使用此主机，实际连接使用完整现场预设");
        search.setToolTipText("查询该目标的UA端点，不查询本机电脑名，不登录，不修改证书信任");
        quickConnect.setToolTipText("使用配置的快捷服务器地址及当前远程IP，无需先查询");
        actualConnect.setToolTipText("使用配置文件中的完整现场服务器地址，无需先查询");
        password.setToolTipText("KEP 用户管理器中该用户的密码，不是用户组名称");
        keystorePassword.setToolTipText("打开本机 client.p12 的密码；首次没有证书时，在这里设置新密码");
        namespace.setToolTipText("命名空间 URI：nsu=KEPServerEX；或现场提供的数字索引 ns=数字");
        identifier.setToolTipText("字符串节点：s=通道.设备.标签；数字节点：i=数字");
        writeValue.setToolTipText("仅读取时不用填写");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(740, 400));
        setSize(850, 460);
        setLocationRelativeTo(null);

        var serverMenu = new JMenu("服务器");
        serverMenu.add(openConnection);
        serverMenu.add(disconnect);
        var menus = new JMenuBar();
        menus.add(serverMenu);
        setJMenuBar(menus);

        var content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        var operations = new JPanel(new BorderLayout(6, 6));
        operations.setBorder(BorderFactory.createTitledBorder("读写"));
        var fields = new JPanel(new GridBagLayout());
        row(fields, 0, "命名空间", namespace);
        row(fields, 1, "节点标识", identifier);
        row(fields, 2, "写入值", writeValue);
        var inputs = new JPanel(new BorderLayout(4, 4));
        inputs.add(fields, BorderLayout.NORTH);
        inputs.add(
                note(
                        "命名空间示例：nsu=KEPServerEX；节点标识示例：s=WriteDemo.Device1.sim。\n"
                                + "两项由现场提供，读写共用；默认 ns=0 和 i=2258 只读服务器时间，不填写 K0001。"),
                BorderLayout.CENTER);
        inputs.add(actions(read, write), BorderLayout.SOUTH);
        operations.add(inputs, BorderLayout.NORTH);
        result.setEditable(false);
        result.setLineWrap(true);
        result.setWrapStyleWord(true);
        result.setText("尚未读取");
        operations.add(new JScrollPane(result), BorderLayout.CENTER);
        content.add(operations, BorderLayout.CENTER);
        status.setEditable(false);
        content.add(status, BorderLayout.SOUTH);
        setContentPane(content);
        connectionDialog = new JDialog(this, "服务器连接", true);
        connectionDialog.setContentPane(connectionPanel());
        connectionDialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        connectionDialog.getRootPane().setDefaultButton(connect);
        connectionDialog.addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        if (!busy) connectionDialog.setVisible(false);
                    }
                });
        openConnection.addActionListener(e -> showConnection());
        search.addActionListener(e -> search());
        connect.addActionListener(e -> connect(ClientSettings.ConnectionAction.CONNECT));
        quickConnect.addActionListener(e -> connect(ClientSettings.ConnectionAction.QUICK));
        actualConnect.addActionListener(e -> connect(ClientSettings.ConnectionAction.ACTUAL));
        cancel.addActionListener(e -> connectionDialog.setVisible(false));
        disconnect.addActionListener(e -> disconnect());
        prepareCertificate.addActionListener(e -> prepareCertificate());
        read.addActionListener(e -> read());
        write.addActionListener(e -> write());
        clearAccountPasswordWhenTargetChanges();
        servers.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        servers.addListSelectionListener(
                e -> {
                    if (!e.getValueIsAdjusting() && !busy && servers.getSelectedValue() != null)
                        serverAddress.setText(servers.getSelectedValue());
                });
        addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        if (closing) return;
                        closing = true;
                        updateControls();
                        if (busy) setStatus("等待当前操作结束后退出…");
                        else finishClose();
                    }
                });
        updateControls();
    }

    private JPanel connectionPanel() {
        var panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        servers.setVisibleRowCount(3);
        var list = new JScrollPane(servers);
        list.setBorder(BorderFactory.createTitledBorder("OPC UA 服务器"));
        panel.add(list, BorderLayout.NORTH);
        var fields = new JPanel(new GridBagLayout());
        row(fields, 0, "服务器地址", serverAddress);
        row(fields, 1, "远程 IP 地址", remoteHost);
        row(accountPanel, 0, "用户名", username);
        row(accountPanel, 1, "用户密码", password);
        var account = constraints(2);
        account.gridwidth = 2;
        account.weightx = 1;
        fields.add(accountPanel, account);
        row(fields, 3, "本机私钥库密码", keystorePassword);
        row(confirmationPanel, 0, "确认新密码", confirmation);
        var confirm = constraints(4);
        confirm.gridwidth = 2;
        fields.add(confirmationPanel, confirm);
        panel.add(fields, BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout(4, 4));
        footer.add(
                note(
                        "服务器地址来自 KEP 的服务器端点；用户密码用于服务器登录。\n"
                                + "本机私钥库密码用于打开 client.p12；首次无证书时设置新密码并确认。"),
                BorderLayout.NORTH);
        var buttons = new JPanel(new java.awt.GridLayout(2, 3, 8, 6));
        for (var button : new JButton[] {search, connect, cancel, quickConnect, actualConnect})
            buttons.add(button);
        var controls = new JPanel(new BorderLayout(4, 4));
        controls.add(prepareCertificate, BorderLayout.NORTH);
        controls.add(buttons, BorderLayout.CENTER);
        footer.add(controls, BorderLayout.CENTER);
        connectionStatus.setEditable(false);
        footer.add(connectionStatus, BorderLayout.SOUTH);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private void showConnection() {
        if (busy || closing || client.isConnected()) return;
        try {
            settings = ClientSettings.load(configFile);
            updateControls();
            connectionDialog.pack();
            connectionDialog.setLocationRelativeTo(this);
            connectionDialog.setVisible(true);
        } catch (Exception e) {
            setStatus(failure(e));
        }
    }

    private void search() {
        String address = serverAddress.getText();
        String host = remoteHost.getText();
        endpointUrls.clear();
        runTask(
                () -> {
                    ClientSettings target =
                            ClientSettings.load(configFile).withConnection(address, host, null);
                    return UaConnection.discover(target.endpoint().toString(), target.timeoutMs())
                            .stream()
                            .map(e -> e.getEndpointUrl())
                            .filter(java.util.Objects::nonNull)
                            .distinct()
                            .toList();
                },
                urls -> {
                    urls.forEach(endpointUrls::addElement);
                    setStatus("查询到 " + urls.size() + " 个服务器地址；可选择或直接填写，查询不代表认证成功。");
                },
                "正在查询服务器…");
    }

    private void clearAccountPasswordWhenTargetChanges() {
        var listener =
                new DocumentListener() {
                    private void changed() {
                        if (!settingEndpoint) password.setText("");
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

    private void prepareCertificate() {
        if (busy || closing || client.isConnected()) return;
        char[] secret = keystorePassword.getPassword();
        char[] repeated = confirmation.getPassword();
        try {
            settings = ClientSettings.load(configFile);
            if (Files.exists(identityFile()))
                throw new IllegalArgumentException("已有本机身份，无需重新生成证书。");
            if (secret.length == 0 || !Arrays.equals(secret, repeated))
                throw new IllegalArgumentException("请设置本机私钥库新密码，并在确认栏输入相同密码。");
        } catch (Exception e) {
            Arrays.fill(secret, '\0');
            setStatus(failure(e));
            return;
        } finally {
            Arrays.fill(repeated, '\0');
        }
        runTask(
                () -> {
                    try {
                        Path certificate = client.initializeCertificate(secret);
                        if (!Files.isRegularFile(identityFile())
                                || !Files.isRegularFile(certificate))
                            throw new IllegalStateException("证书文件未完整生成，请检查本机目录权限。");
                        return certificate;
                    } finally {
                        Arrays.fill(secret, '\0');
                    }
                },
                certificate -> {
                    confirmation.setText("");
                    connectionDialog.setVisible(false);
                    result.setText(
                            "客户端证书已生成：\n"
                                    + certificate
                                    + "\n请将该公开证书导入 KEP“受信任的客户端”，重新初始化后再连接。"
                                    + "\n私钥库留在本机："
                                    + identityFile());
                    setStatus("证书已生成，尚未连接；请先完成双方信任。");
                },
                "正在生成客户端证书…");
    }

    private void connect(ClientSettings.ConnectionAction action) {
        if (busy || closing || client.isConnected()) return;
        String endpoint = serverAddress.getText().trim();
        String host = remoteHost.getText().trim();
        String user = username.getText().trim();
        char[] userSecret = password.getPassword();
        char[] keySecret = keystorePassword.getPassword();
        final ClientConfiguration configuration;
        try {
            configuration = new ClientConfiguration(configFile);
            settings = configuration.settings();
            if (!Files.isRegularFile(identityFile()))
                throw new IllegalArgumentException("请先在页面设置并确认新密码，生成客户端证书，完成双方信任。");
            if (action == ClientSettings.ConnectionAction.CONNECT && endpoint.isEmpty())
                throw new IllegalArgumentException("请输入服务器地址或 IP。");
            if (settings.identityType().equals("username")
                    && (user.isEmpty() || userSecret.length == 0))
                throw new IllegalArgumentException("请输入服务器用户名及用户密码。");
            ClientSettings target =
                    ClientSettings.connectionTarget(configFile, action, endpoint, host, user);
            endpoint = target.endpoint().toString();
            if (keySecret.length == 0)
                throw new IllegalArgumentException("请输入创建本机 client.p12 时设置的私钥库密码。");
        } catch (Exception e) {
            Arrays.fill(userSecret, '\0');
            Arrays.fill(keySecret, '\0');
            setStatus(failure(e));
            updateControls();
            return;
        }
        final String targetEndpoint = endpoint;
        runTask(
                () -> {
                    try {
                        client.close();
                        client = new UaClient(configFile);
                        client.connect(targetEndpoint, user, userSecret, keySecret);
                        String actual = client.endpoint().toString();
                        try {
                            configuration.save(
                                    Map.of(
                                            "endpoint.url",
                                            actual,
                                            "endpoint.discoveryUrl",
                                            actual,
                                            "identity.username",
                                            user));
                            return "连接成功";
                        } catch (Exception e) {
                            return "连接成功；连接参数未保存：" + failure(e);
                        }
                    } finally {
                        Arrays.fill(userSecret, '\0');
                        Arrays.fill(keySecret, '\0');
                    }
                },
                message -> {
                    settingEndpoint = true;
                    try {
                        serverAddress.setText(client.endpoint().toString());
                        remoteHost.setText(client.endpoint().getHost());
                    } finally {
                        settingEndpoint = false;
                    }
                    result.setText("尚未读取");
                    connectionDialog.setVisible(false);
                    setStatus(message);
                },
                "正在连接：" + targetEndpoint);
    }

    private void disconnect() {
        runTask(
                () -> {
                    client.close();
                    return null;
                },
                ignored -> {
                    result.setText("尚未读取");
                    setStatus("已断开");
                },
                "正在断开…");
    }

    private void read() {
        String space = namespace.getText();
        String node = identifier.getText();
        result.setText("正在读取…");
        runTask(
                () -> client.readData(UaAddress.fromParts(space, node)),
                value -> {
                    result.setText(
                            "地址：%s\n值：%s\n质量：%s\n源时间：%s"
                                    .formatted(
                                            space.trim() + ";" + node,
                                            valueText(value),
                                            value.getStatusCode(),
                                            value.getSourceTime() == null
                                                    ? "无"
                                                    : value.getSourceTime().getJavaInstant()));
                    setStatus("读取成功");
                },
                "正在读取…");
    }

    private void write() {
        String space = namespace.getText();
        String node = identifier.getText();
        String value = writeValue.getText();
        runTask(
                () -> client.write(UaAddress.fromParts(space, node), value),
                code -> setStatus("写入服务已接受：" + code + "；可点击读取核对。"),
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
        setStatus(progress);
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
                    setStatus(failure(e));
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
        boolean missing = !Files.isRegularFile(identityFile());
        accountPanel.setVisible(settings.identityType().equals("username"));
        confirmationPanel.setVisible(missing);
        prepareCertificate.setVisible(missing);
        prepareCertificate.setEnabled(available && !connected && missing);
        for (var field :
                new JTextField[] {
                    serverAddress, remoteHost, username, password, keystorePassword, confirmation
                }) field.setEnabled(available && !connected);
        connect.setEnabled(available && !connected && !missing);
        quickConnect.setEnabled(available && !connected && !missing);
        actualConnect.setEnabled(available && !connected && !missing);
        search.setEnabled(available && !connected);
        cancel.setEnabled(available);
        servers.setEnabled(available && !connected);
        openConnection.setEnabled(available && !connected);
        disconnect.setEnabled(available && connected);
        namespace.setEnabled(available);
        identifier.setEnabled(available);
        writeValue.setEnabled(available);
        read.setEnabled(available && connected);
        write.setEnabled(available && connected);
    }

    private void finishClose() {
        busy = true;
        setStatus("正在退出…");
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
                keystorePassword.setText("");
                confirmation.setText("");
                connectionDialog.dispose();
                dispose();
                System.exit(0);
            }
        }.execute();
    }

    private void setStatus(String message) {
        status.setText(message);
        connectionStatus.setText(message);
    }

    private static JPanel actions(JButton... buttons) {
        var panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        for (var button : buttons) panel.add(button);
        return panel;
    }

    private static JTextArea note(String text) {
        var area = new JTextArea(text);
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
        c.insets = new Insets(3, 6, 3, 6);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private static void row(JPanel panel, int row, String label, java.awt.Component field) {
        var c = constraints(row);
        var title = new JLabel(label);
        title.setPreferredSize(new Dimension(130, title.getPreferredSize().height));
        panel.add(title, c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(field, c);
    }

    /** 只展示本项目错误及UA状态，避免暴露内部凭据。 */
    static String failure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof UaException ua) return "OPC UA 状态：" + ua.getStatusCode();
            if (t instanceof java.security.UnrecoverableKeyException)
                return "无法打开本机 client.p12，请核对首次创建它时设置的密码。";
            if (t instanceof java.nio.file.NoSuchFileException)
                return "所需本机文件不存在，请检查连接配置或先准备本机客户端证书。";
            if (t instanceof java.net.URISyntaxException) return "请填写有效的服务器地址或 IP。";
            if ((t instanceof IllegalArgumentException || t instanceof IllegalStateException)
                    && t.getStackTrace().length > 0
                    && t.getStackTrace()[0].getClassName().startsWith("cn.buct.ua."))
                return t.getMessage();
        }
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return "操作未完成（" + cause.getClass().getSimpleName() + "），请核对服务器地址、本机私钥库密码及账号。";
    }
}

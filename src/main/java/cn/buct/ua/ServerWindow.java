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
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

/** 只提供连接、按地址读取和按地址写入，所有通信统一调用 UaClient。 */
final class ServerWindow extends JFrame {
    private final Path configFile;
    private final boolean usernameIdentity;
    private final JTextField serverAddress = new JTextField();
    private final JTextField username = new JTextField();
    private final JPasswordField password = new JPasswordField();
    private final JPasswordField keyStorePassword = new JPasswordField();
    private final JButton connect = new JButton("连接");
    private final JButton disconnect = new JButton("断开");
    private final JTextField readAddress = new JTextField("ns=0;i=2258");
    private final JButton read = new JButton("读取");
    private final JTextArea result = new JTextArea(5, 60);
    private final JTextField writeAddress = new JTextField();
    private final JTextField writeValue = new JTextField();
    private final JButton write = new JButton("写入");
    private final JTextField status = new JTextField("未连接");
    private UaClient client;
    private boolean busy;
    private boolean closing;

    ServerWindow(ClientSettings defaults, Path config) {
        super("OPC UA 客户端");
        configFile = config;
        usernameIdentity = defaults.identityType().equals("username");
        client = new UaClient(config);
        serverAddress.setText(defaults.endpoint().toString());
        username.setText(defaults.username());
        password.setToolTipText("服务器用户密码；留空时使用配置指定的环境变量");
        keyStorePassword.setToolTipText("客户端私钥库口令；留空时使用配置指定的环境变量");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(760, 580));
        setSize(920, 650);
        setLocationRelativeTo(null);

        var content = new JPanel(new BorderLayout(12, 12));
        content.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        var fields = new JPanel(new GridBagLayout());
        fields.setBorder(BorderFactory.createTitledBorder("连接"));
        row(fields, 0, "服务器地址 / IP", serverAddress);
        row(fields, 1, "用户名", username);
        row(fields, 2, "用户密码", password);
        row(fields, 3, "证书口令", keyStorePassword);
        var connectionActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        connectionActions.add(connect);
        connectionActions.add(disconnect);
        var c = constraints(4);
        c.gridx = 1;
        fields.add(connectionActions, c);
        content.add(fields, BorderLayout.NORTH);

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
        var writeActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        writeActions.add(write);
        var writeConstraints = constraints(2);
        writeConstraints.gridx = 1;
        writing.add(writeActions, writeConstraints);
        operations.add(writing, BorderLayout.SOUTH);
        content.add(operations, BorderLayout.CENTER);
        status.setEditable(false);
        content.add(status, BorderLayout.SOUTH);
        setContentPane(content);

        connect.addActionListener(e -> connect());
        disconnect.addActionListener(e -> disconnect());
        read.addActionListener(e -> read());
        write.addActionListener(e -> write());
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

    private void connect() {
        if (busy || closing || client.isConnected()) return;
        String endpoint = serverAddress.getText().trim();
        String user = username.getText().trim();
        char[] userSecret = password.getPassword();
        char[] keySecret = keyStorePassword.getPassword();
        runTask(
                () -> {
                    try {
                        client.close();
                        client = new UaClient(configFile);
                        var settings = ClientSettings.load(configFile);
                        Path identity = settings.pkiDirectory().resolve(settings.pki().fileName());
                        if (!Files.exists(identity)) {
                            Path certificate = client.initializeCertificate(keySecret);
                            return "客户端证书已生成：" + certificate + "；请在服务器信任此公开证书后再次连接。";
                        }
                        client.connect(endpoint, user, userSecret, keySecret);
                        try {
                            String actual = client.endpoint().toString();
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
                    } finally {
                        Arrays.fill(userSecret, '\0');
                        Arrays.fill(keySecret, '\0');
                    }
                },
                message -> {
                    result.setText("尚未读取");
                    if (client.isConnected()) serverAddress.setText(client.endpoint().toString());
                    status.setText(message);
                },
                "正在连接…");
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
        updateControls();
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return task.call();
            }

            @Override
            protected void done() {
                busy = false;
                try {
                    complete.accept(get());
                } catch (Exception e) {
                    status.setText(failure(e));
                    if (progress.equals("正在读取…")) result.setText("读取未完成");
                } finally {
                    if (closing) finishClose();
                    else updateControls();
                }
            }
        }.execute();
    }

    private void updateControls() {
        boolean available = !busy && !closing;
        // UaClient 的操作会持有会话锁；忙碌时不在 EDT 上查询，保证关闭等待也保持响应。
        boolean connected = available && client.isConnected();
        serverAddress.setEnabled(available && !connected);
        username.setEnabled(available && !connected && usernameIdentity);
        password.setEnabled(available && !connected && usernameIdentity);
        keyStorePassword.setEnabled(available && !connected);
        connect.setEnabled(available && !connected);
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
                keyStorePassword.setText("");
                dispose();
                System.exit(0);
            }
        }.execute();
    }

    /** 保留可理解的本项目错误与UA状态，避免向界面输出第三方会话内部信息。 */
    static String failure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof UaException ua) return "OPC UA 状态：" + ua.getStatusCode();
            if (t instanceof java.nio.file.NoSuchFileException) return "缺少本机配置或客户端证书，请按部署说明准备后重试。";
            if ((t instanceof IllegalArgumentException || t instanceof IllegalStateException)
                    && t.getStackTrace().length > 0
                    && t.getStackTrace()[0].getClassName().startsWith("cn.buct.ua."))
                return t.getMessage();
        }
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return "操作未完成（" + cause.getClass().getSimpleName() + "），请核对服务器地址、安全配置、证书及账号权限。";
    }
}

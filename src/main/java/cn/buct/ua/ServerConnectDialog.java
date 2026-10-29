package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** 仿照旧 C# 的服务器列表、地址及操作布局；查询与连接均在后台执行。 */
final class ServerConnectDialog extends JDialog {
    private final ServerWindow owner;
    private final ClientSettings defaults;
    private final DefaultListModel<String> rows = new DefaultListModel<>();
    private final JList<String> servers = new JList<>(rows);
    private final JTextField address = new JTextField(28);
    private final JTextField host = new JTextField(28);
    private final JTextField username = new JTextField(28);
    private final JLabel status = new JLabel("输入服务器地址后查询，或按配置策略直接连接。");
    private final JButton query = new JButton("查询");
    private final JButton connect = new JButton("连接");
    private final JButton cancel = new JButton("取消");
    private List<EndpointDescription> endpoints = List.of();
    private URI discovery;
    private boolean busy;
    private boolean updating;
    private boolean hostEdited;

    ServerConnectDialog(ServerWindow owner, ClientSettings defaults) {
        super(owner, "服务器连接", true);
        this.owner = owner;
        this.defaults = defaults;
        discovery = defaults.discoveryEndpoint();
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(630, 460));
        setSize(700, 490);
        setLocationRelativeTo(owner);
        var content = new JPanel(new BorderLayout(0, 18));
        content.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        var listPanel = new JPanel(new BorderLayout());
        listPanel.setBorder(BorderFactory.createTitledBorder("OPC UA 服务器"));
        servers.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        servers.setVisibleRowCount(7);
        servers.setFixedCellHeight(26);
        listPanel.add(new JScrollPane(servers));
        content.add(listPanel, BorderLayout.CENTER);
        var bottom = new JPanel(new BorderLayout(0, 14));
        var fields = new JPanel(new GridBagLayout());
        addField(fields, "服务器地址", address, 0);
        addField(fields, "远程 IP / 主机", host, 1);
        addField(fields, "用户名", username, 2);
        bottom.add(fields, BorderLayout.NORTH);
        bottom.add(status, BorderLayout.CENTER);
        var buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 44, 0));
        for (JButton button : List.of(query, connect, cancel)) {
            button.setPreferredSize(new Dimension(108, 30));
            buttons.add(button);
        }
        bottom.add(buttons, BorderLayout.SOUTH);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        address.setToolTipText("完整的 opc.tcp 地址，包含主机、端口和可选路径");
        host.setToolTipText("修改这里可替换服务器地址中的主机，端口和路径保持不变");
        username.setText(defaults.username());
        username.setEnabled(defaults.identityType().equals("username"));
        address.setText(defaults.endpoint().toString());
        host.setText(defaults.endpoint().getHost());
        address.getDocument()
                .addDocumentListener(
                        changes(
                                () -> {
                                    if (updating) return;
                                    hostEdited = false;
                                    servers.clearSelection();
                                    try {
                                        URI uri = DesktopEndpoint.address(address.getText());
                                        updating = true;
                                        host.setText(uri.getHost());
                                    } catch (IllegalArgumentException ignored) {
                                        // 允许用户先完成尚不完整的地址输入；操作前统一校验。
                                    } finally {
                                        updating = false;
                                    }
                                }));
        host.getDocument()
                .addDocumentListener(
                        changes(
                                () -> {
                                    if (!updating) {
                                        hostEdited = true;
                                        servers.clearSelection();
                                    }
                                }));
        servers.addListSelectionListener(
                e -> {
                    if (e.getValueIsAdjusting() || servers.getSelectedIndex() < 0) return;
                    EndpointDescription selected = endpoints.get(servers.getSelectedIndex());
                    updating = true;
                    try {
                        address.setText(selected.getEndpointUrl());
                        host.setText(URI.create(selected.getEndpointUrl()).getHost());
                        hostEdited = false;
                    } finally {
                        updating = false;
                    }
                });
        query.addActionListener(e -> query());
        connect.addActionListener(e -> connect());
        cancel.addActionListener(e -> dispose());
        addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        if (!busy) dispose();
                    }
                });
    }

    private static void addField(JPanel panel, String text, JTextField input, int row) {
        var c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(5, 12, 5, 24);
        panel.add(new JLabel(text), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(5, 0, 5, 12);
        panel.add(input, c);
    }

    private static DocumentListener changes(Runnable changed) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                changed.run();
            }

            public void removeUpdate(DocumentEvent e) {
                changed.run();
            }

            public void changedUpdate(DocumentEvent e) {
                changed.run();
            }
        };
    }

    private URI target() throws Exception {
        if (hostEdited) {
            URI uri = DesktopEndpoint.withHost(address.getText(), host.getText());
            address.setText(uri.toString());
        }
        return DesktopEndpoint.address(address.getText());
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        for (var component : List.of(query, connect, cancel, servers, address, host))
            component.setEnabled(!value);
        username.setEnabled(!value && defaults.identityType().equals("username"));
        status.setText(message);
    }

    private void query() {
        final URI url;
        try {
            URI entered = target();
            url = entered.equals(defaults.endpoint()) ? defaults.discoveryEndpoint() : entered;
        } catch (Exception e) {
            showFailure(e);
            return;
        }
        setBusy(true, "正在查询服务器…");
        rows.clear();
        endpoints = List.of();
        new SwingWorker<List<EndpointDescription>, Void>() {
            @Override
            protected List<EndpointDescription> doInBackground() throws Exception {
                return UaServices.discover(url.toString(), defaults.timeoutMs());
            }

            @Override
            protected void done() {
                setBusy(false, "查询完成");
                try {
                    endpoints = get();
                    discovery = url;
                    endpoints.forEach(endpoint -> rows.addElement(DesktopEndpoint.label(endpoint)));
                    status.setText("发现 " + endpoints.size() + " 个端点；请选择后连接。查询不表示登录成功。");
                    for (int i = 0; i < endpoints.size(); i++) {
                        var candidate = endpoints.get(i);
                        if (candidate.getEndpointUrl().equals(address.getText())
                                && defaults.policy()
                                        .getUri()
                                        .equals(candidate.getSecurityPolicyUri())
                                && defaults.mode() == candidate.getSecurityMode()) {
                            servers.setSelectedIndex(i);
                            break;
                        }
                    }
                } catch (Exception e) {
                    showFailure(e);
                }
            }
        }.execute();
    }

    private void connect() {
        final ClientSettings settings;
        try {
            URI url = target();
            int index = servers.getSelectedIndex();
            URI discoveryUrl =
                    index >= 0
                            ? discovery
                            : url.equals(defaults.endpoint()) ? defaults.discoveryEndpoint() : url;
            settings =
                    DesktopEndpoint.connection(
                            defaults,
                            url,
                            discoveryUrl,
                            index >= 0 ? endpoints.get(index) : null,
                            username.getText());
        } catch (Exception e) {
            showFailure(e);
            return;
        }
        setBusy(true, "正在建立会话，请完成密码输入；可在密码窗口取消。");
        new SwingWorker<UaConnection, Void>() {
            @Override
            protected UaConnection doInBackground() throws Exception {
                return owner.openConnection(settings);
            }

            @Override
            protected void done() {
                setBusy(false, "未连接");
                try {
                    owner.connected(get(), settings);
                    dispose();
                } catch (Exception e) {
                    showFailure(e);
                }
            }
        }.execute();
    }

    private void showFailure(Exception e) {
        status.setText("操作未完成");
        JOptionPane.showMessageDialog(
                this, ServerWindow.failure(e), "连接提示", JOptionPane.WARNING_MESSAGE);
    }
}

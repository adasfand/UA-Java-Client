package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;

/** 同一连接配置文件的可视化编辑入口；相对路径按原样保存。 */
final class ClientConfigurationDialog extends JDialog {
    private final Path file;
    private final Consumer<ClientSettings> saved;
    private final ConfigurationModel model = new ConfigurationModel();
    private final JTable table =
            new JTable(model) {
                @Override
                public TableCellEditor getCellEditor(int row, int column) {
                    String[] choices = choices(model.rows.get(convertRowIndexToModel(row)).key);
                    return column == 2 && choices.length > 0
                            ? new DefaultCellEditor(new JComboBox<>(choices))
                            : super.getCellEditor(row, column);
                }
            };
    private final JLabel status = new JLabel(" ");
    private final JButton save = new JButton("保存配置");
    private final JButton reload = new JButton("从文件重载");
    private final JButton close = new JButton("关闭");
    private ClientConfiguration configuration;
    private boolean busy;

    ClientConfigurationDialog(ServerWindow owner, Path file, Consumer<ClientSettings> saved)
            throws Exception {
        super(owner, "连接配置", true);
        this.file = file;
        this.saved = saved;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setSize(950, 720);
        setMinimumSize(new Dimension(760, 500));
        setLocationRelativeTo(owner);
        var content = new JPanel(new BorderLayout(8, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(new JLabel("配置文件：" + file + "  |  双击“值”编辑，保存后下次连接生效。"), BorderLayout.NORTH);
        table.setRowHeight(27);
        table.putClientProperty("terminateEditOnFocusLost", true);
        table.getColumnModel().getColumn(0).setPreferredWidth(170);
        table.getColumnModel().getColumn(1).setPreferredWidth(200);
        table.getColumnModel().getColumn(2).setPreferredWidth(420);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        var bottom = new JPanel(new BorderLayout(0, 8));
        var note = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        note.add(new JLabel("路径以配置文件所在目录为基准。密码在连接时输入；表中的凭据项是环境变量名称。"));
        note.add(new JLabel("应用 URI、证书名称及 SAN 修改后，现有证书不会自动重签。安全模式当前支持 SignAndEncrypt。"));
        note.add(status);
        bottom.add(note, BorderLayout.CENTER);
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        for (var button : List.of(reload, save, close)) buttons.add(button);
        bottom.add(buttons, BorderLayout.SOUTH);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        reload.addActionListener(e -> reload());
        save.addActionListener(e -> save());
        close.addActionListener(
                e -> {
                    if (!busy) dispose();
                });
        addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        if (!busy) dispose();
                    }
                });
        load();
    }

    private void load() throws Exception {
        configuration = new ClientConfiguration(file);
        model.load(configuration.values());
        status.setText("已从文件读取。保存会校验参数并清理所编辑键的重复定义。点表内容另由点位窗口管理。");
    }

    private void reload() {
        if (busy) return;
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        try {
            load();
        } catch (Exception e) {
            fail(e);
        }
    }

    private void save() {
        if (busy || table.isEditing() && !table.getCellEditor().stopCellEditing()) return;
        Map<String, String> changes = model.changes();
        setBusy(true);
        new SwingWorker<ClientSettings, Void>() {
            @Override
            protected ClientSettings doInBackground() throws Exception {
                return configuration.save(changes);
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    ClientSettings settings = get();
                    saved.accept(settings);
                    model.load(configuration.values());
                    status.setText("配置已保存；下次连接和其他读取此文件的运行入口将使用这些值。");
                } catch (Exception e) {
                    fail(e);
                }
            }
        }.execute();
    }

    private void setBusy(boolean value) {
        busy = value;
        table.setEnabled(!value);
        for (var button : List.of(save, reload, close)) button.setEnabled(!value);
        if (value) status.setText("正在校验并保存…");
    }

    private void fail(Exception e) {
        status.setText("配置未保存，请核对参数或从文件重载。");
        JOptionPane.showMessageDialog(
                this, ServerWindow.failure(e), "配置提示", JOptionPane.WARNING_MESSAGE);
    }

    private static String[] choices(String key) {
        return switch (key) {
            case "identity.type" -> new String[] {"username", "anonymous"};
            case "security.serverTrust" -> new String[] {"strict", "first-use"};
            case "security.mode" -> new String[] {"SignAndEncrypt"};
            case "security.policy" ->
                    Arrays.stream(SecurityPolicy.values())
                            .filter(policy -> policy != SecurityPolicy.None)
                            .map(Enum::name)
                            .toArray(String[]::new);
            case "writes.enabled" -> new String[] {"false", "true"};
            case "environment" -> new String[] {"simulation", "production"};
            default -> new String[0];
        };
    }

    private static final String[][] FIELDS = {
        {"服务器地址", "endpoint.url", ""},
        {"独立发现地址（可留空）", "endpoint.discoveryUrl", ""},
        {"安全策略", "security.policy", ""},
        {"安全模式", "security.mode", ""},
        {"服务器证书信任方式", "security.serverTrust", "strict"},
        {"用户身份方式", "identity.type", ""},
        {"用户名", "identity.username", ""},
        {"用户密码环境变量名称", "identity.passwordEnv", "UA_PASSWORD"},
        {"应用 URI", "application.uri", ""},
        {"应用名称", "application.name", ""},
        {"请求超时（毫秒）", "request.timeoutMs", "10000"},
        {"单次浏览数量上限", "browse.maxReferences", "500"},
        {"浏览总超时（毫秒）", "browse.timeoutMs", "60000"},
        {"PKI 目录", "pki.directory", ""},
        {"证书库密码环境变量名称", "pki.passwordEnv", "UA_KEYSTORE_PASSWORD"},
        {"证书库文件名", "pki.file", "client.p12"},
        {"证书库别名", "pki.alias", "ua-client"},
        {"证书名称", "pki.commonName", ""},
        {"证书组织", "pki.organization", "UA Development"},
        {"证书 DNS 名（逗号分隔）", "pki.dnsNames", ""},
        {"证书 IP（逗号分隔）", "pki.ipAddresses", ""},
        {"新证书有效天数", "pki.validityDays", "365"},
        {"点表文件", "nodes.file", ""},
        {"运行环境", "environment", "simulation"},
        {"写入总开关", "writes.enabled", "false"}
    };

    private static final class Row {
        final String label;
        final String key;
        final String original;
        final boolean present;
        String value;

        Row(String label, String key, String value, boolean present) {
            this.label = label;
            this.key = key;
            this.original = value;
            this.value = value;
            this.present = present;
        }
    }

    private static final class ConfigurationModel extends AbstractTableModel {
        final List<Row> rows = new ArrayList<>();

        void load(Map<String, String> values) {
            rows.clear();
            var known = new java.util.HashSet<String>();
            for (var field : FIELDS) {
                String fallback =
                        field[1].equals("pki.commonName")
                                ? values.getOrDefault("application.name", "")
                                : field[2];
                rows.add(
                        new Row(
                                field[0],
                                field[1],
                                values.getOrDefault(field[1], fallback),
                                values.containsKey(field[1])));
                known.add(field[1]);
            }
            values.keySet().stream()
                    .filter(key -> !known.contains(key))
                    .sorted()
                    .forEach(key -> rows.add(new Row("扩展参数", key, values.get(key), true)));
            fireTableDataChanged();
        }

        Map<String, String> changes() {
            var changes = new LinkedHashMap<String, String>();
            for (var row : rows)
                if (row.present || !row.value.equals(row.original)) changes.put(row.key, row.value);
            return changes;
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return 3;
        }

        public String getColumnName(int column) {
            return new String[] {"配置项", "文件中的键", "值"}[column];
        }

        public Object getValueAt(int row, int column) {
            Row item = rows.get(row);
            return switch (column) {
                case 0 -> item.label;
                case 1 -> item.key;
                default -> item.value;
            };
        }

        public boolean isCellEditable(int row, int column) {
            return column == 2;
        }

        public void setValueAt(Object value, int row, int column) {
            if (column == 2) {
                rows.get(row).value = value.toString();
                fireTableCellUpdated(row, column);
            }
        }
    }
}

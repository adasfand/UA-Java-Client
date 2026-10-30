package cn.buct.ua;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;

/** 桌面按需读写，复用现有认证会话和配置；不包含连续采集与历史存储。 */
final class DataPanel extends JPanel {
    private static final Set<String> WRITE_TYPES =
            Set.of(
                    "Boolean", "SByte", "Byte", "Int16", "UInt16", "Int32", "UInt32", "Int64",
                    "UInt64", "Float", "Double", "String");
    private final ServerWindow owner;
    private final Path configFile;
    private final PointsModel model = new PointsModel();
    private final JTable table = new JTable(model);
    private final JButton reload = new JButton("重载点表");
    private final JButton readSelected = new JButton("读取所选");
    private final JButton readAll = new JButton("读取全部");
    private final JButton configure = new JButton("点位设置…");
    private final JCheckBox writes = new JCheckBox("允许本机模拟写入");
    private final JTextField value = new JTextField(18);
    private final JButton write = new JButton("写入并回读");
    private final JLabel selected = new JLabel("请选择点位");
    private final JTextArea messages = new JTextArea(5, 40);
    private final Map<String, UaConnection.ReadResult> values = new HashMap<>();
    private List<PointDefinition> points = List.of();
    private PointCatalog catalog;
    private UaConnection connection;
    private boolean busy;

    DataPanel(ServerWindow owner, Path configFile) {
        super(new BorderLayout(8, 8));
        this.owner = owner;
        this.configFile = configFile;
        setBorder(BorderFactory.createEmptyBorder(4, 24, 8, 24));
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        for (var button : List.of(reload, readSelected, readAll, configure)) actions.add(button);
        actions.add(writes);
        add(actions, BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(27);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(2).setPreferredWidth(180);
        table.getColumnModel().getColumn(4).setPreferredWidth(220);
        add(new JScrollPane(table), BorderLayout.CENTER);
        var bottom = new JPanel(new BorderLayout(8, 8));
        var writeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        writeBar.add(selected);
        writeBar.add(new JLabel("写入值："));
        writeBar.add(value);
        writeBar.add(write);
        bottom.add(writeBar, BorderLayout.NORTH);
        messages.setEditable(false);
        messages.setLineWrap(true);
        messages.setWrapStyleWord(true);
        bottom.add(new JScrollPane(messages), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        table.getSelectionModel().addListSelectionListener(e -> updateControls());
        reload.addActionListener(e -> reload());
        readSelected.addActionListener(e -> read(false));
        readAll.addActionListener(e -> read(true));
        configure.addActionListener(e -> configurePoint());
        write.addActionListener(e -> write());
        writes.addActionListener(e -> saveWriteSwitch());
        reload();
    }

    void connection(UaConnection current) {
        connection = current;
        values.clear();
        model.fireTableDataChanged();
        reload();
    }

    void externalBusy(boolean active) {
        busy = active;
        updateControls();
    }

    void reload() {
        if (busy) return;
        String alias = selectedPoint() == null ? null : selectedPoint().alias();
        try {
            ClientSettings settings = ClientSettings.load(configFile);
            catalog = new PointCatalog(settings.nodesFile());
            points = catalog.existingPoints();
            writes.setSelected(settings.writesEnabled());
            values.clear();
            model.fireTableDataChanged();
            for (int i = 0; i < points.size(); i++) {
                if (points.get(i).alias().equals(alias)) {
                    int row = table.convertRowIndexToView(i);
                    table.setRowSelectionInterval(row, row);
                    break;
                }
            }
            if (table.getSelectedRow() < 0 && !points.isEmpty())
                table.setRowSelectionInterval(0, 0);
        } catch (Exception e) {
            points = List.of();
            catalog = null;
            values.clear();
            model.fireTableDataChanged();
            log(ServerWindow.failure(e));
        }
        updateControls();
    }

    private PointDefinition selectedPoint() {
        int row = table.getSelectedRow();
        if (row < 0) return null;
        int index = table.convertRowIndexToModel(row);
        return index < points.size() ? points.get(index) : null;
    }

    private void updateControls() {
        PointDefinition point = selectedPoint();
        boolean active = !busy && connection != null;
        reload.setEnabled(!busy);
        readSelected.setEnabled(active && point != null);
        readAll.setEnabled(active && !points.isEmpty());
        configure.setEnabled(!busy && catalog != null && point != null);
        writes.setEnabled(!busy && catalog != null);
        value.setEnabled(active && point != null);
        write.setEnabled(active && point != null && point.writable() && writes.isSelected());
        selected.setText(point == null ? "请选择点位" : "所选：" + point.alias());
        table.setEnabled(!busy);
    }

    private void saveWriteSwitch() {
        boolean requested = writes.isSelected();
        runTask(
                () ->
                        new ClientConfiguration(configFile)
                                .save(Map.of("writes.enabled", Boolean.toString(requested))),
                settings -> {
                    owner.configurationSaved(settings);
                    writes.setSelected(settings.writesEnabled());
                    log(requested ? "本机模拟写入总开关已打开；还需所选点的写授权。" : "写入总开关已关闭。");
                },
                "正在保存写入开关…",
                () -> writes.setSelected(!requested));
    }

    private void configurePoint() {
        PointDefinition point = selectedPoint();
        if (point == null) return;
        var allowed = new JCheckBox("允许应用写入此点", point.writable());
        allowed.setEnabled(WRITE_TYPES.contains(point.dataType()));
        var min = new JTextField(point.minimum() == null ? "" : point.minimum());
        var max = new JTextField(point.maximum() == null ? "" : point.maximum());
        boolean numeric = !Set.of("Boolean", "String").contains(point.dataType());
        min.setEnabled(numeric && allowed.isEnabled());
        max.setEnabled(numeric && allowed.isEnabled());
        var fields = new JPanel(new GridLayout(0, 2, 8, 8));
        fields.add(new JLabel("配置代称"));
        fields.add(new JLabel(point.alias()));
        fields.add(new JLabel("服务器标识"));
        fields.add(new JTextField(point.identifier()));
        ((JTextField) fields.getComponent(3)).setEditable(false);
        fields.add(new JLabel("数据类型"));
        fields.add(new JLabel(point.dataType()));
        fields.add(new JLabel("应用写授权"));
        fields.add(allowed);
        fields.add(new JLabel("最小值（数值型写入必填）"));
        fields.add(min);
        fields.add(new JLabel("最大值（数值型写入必填）"));
        fields.add(max);
        while (JOptionPane.showConfirmDialog(
                        owner,
                        fields,
                        "点位设置：" + point.alias(),
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE)
                == JOptionPane.OK_OPTION) {
            boolean enabled = allowed.isEnabled() && allowed.isSelected();
            String minimum = numeric ? min.getText().trim() : "";
            String maximum = numeric ? max.getText().trim() : "";
            try {
                new PointDefinition(
                        point.alias(),
                        point.namespaceUri(),
                        point.identifierType(),
                        point.identifier(),
                        point.dataType(),
                        enabled,
                        minimum.isEmpty() ? null : minimum,
                        maximum.isEmpty() ? null : maximum);
                if (!minimum.isEmpty() || !maximum.isEmpty()) {
                    if (minimum.isEmpty()
                            || maximum.isEmpty()
                            || new java.math.BigDecimal(minimum)
                                            .compareTo(new java.math.BigDecimal(maximum))
                                    > 0) throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                JOptionPane.showMessageDialog(
                        owner, "请填写有效的数值范围；数值型点开放写入时范围必填。", "点位设置", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            PointCatalog current = catalog;
            runTask(
                    () -> current.configure(point.alias(), enabled, minimum, maximum),
                    saved -> {
                        reloadAfterTask();
                        log("已保存点位 " + saved.alias() + " 的写授权和范围。");
                    },
                    "正在保存点位设置…",
                    () -> {});
            return;
        }
    }

    private void read(boolean all) {
        PointDefinition point = selectedPoint();
        List<PointDefinition> requested =
                all ? List.copyOf(points) : point == null ? List.of() : List.of(point);
        if (requested.isEmpty() || connection == null) return;
        UaConnection current = connection;
        requested.forEach(p -> values.remove(p.alias()));
        model.refreshValues();
        runTask(
                () -> current.readValues(requested),
                results -> {
                    results.forEach(result -> values.put(result.alias(), result));
                    model.refreshValues();
                    long good =
                            results.stream()
                                    .filter(
                                            r ->
                                                    r.value().getStatusCode() != null
                                                            && r.value().getStatusCode().isGood())
                                    .count();
                    log("读取完成：" + good + "/" + results.size() + " 个点质量为 Good。");
                },
                "正在读取…",
                () -> {});
    }

    private void write() {
        PointDefinition point = selectedPoint();
        if (point == null || connection == null) return;
        final ClientSettings authorization;
        final String input = value.getText();
        try {
            authorization = ClientSettings.load(configFile);
            authorization.requireWrite(point, true);
            try {
                point.parseWriteValue(input);
            } catch (ArithmeticException | NumberFormatException e) {
                throw new IllegalArgumentException(
                        "输入值无法转换为 " + point.dataType() + "，请核对整数/小数格式及类型范围。", e);
            }
        } catch (Exception e) {
            showFailure(e);
            return;
        }
        if (JOptionPane.showConfirmDialog(
                        owner,
                        "服务器："
                                + authorization.endpoint()
                                + "\n点位："
                                + point.alias()
                                + "\n标识："
                                + point.identifier()
                                + "\n写入值："
                                + input
                                + "\n确认执行一次写入并回读？",
                        "确认模拟写入",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.QUESTION_MESSAGE)
                != JOptionPane.OK_OPTION) return;
        UaConnection current = connection;
        values.remove(point.alias());
        model.refreshValues();
        runTask(
                () -> current.writeValue(point, input, true, ClientSettings.load(configFile)),
                result -> {
                    values.put(
                            result.alias(),
                            new UaConnection.ReadResult(
                                    result.alias(), result.nodeId(), result.after()));
                    model.refreshValues();
                    log(
                            "写入完成：点位="
                                    + result.alias()
                                    + "，旧值="
                                    + text(result.before().getValue().getValue())
                                    + "，请求="
                                    + text(result.requested())
                                    + "，写状态=Good，回读="
                                    + text(result.after().getValue().getValue())
                                    + "，回读质量=Good。");
                },
                "正在写入并回读…",
                () -> {});
    }

    private void reloadAfterTask() {
        busy = false;
        reload();
    }

    private <T> void runTask(
            Callable<T> task, Consumer<T> completed, String message, Runnable failed) {
        if (busy) return;
        busy = true;
        owner.operationBusy(true, message);
        updateControls();
        new SwingWorker<T, Void>() {
            protected T doInBackground() throws Exception {
                return task.call();
            }

            protected void done() {
                busy = false;
                owner.operationBusy(false, null);
                try {
                    completed.accept(get());
                } catch (Exception e) {
                    failed.run();
                    showFailure(e);
                }
                updateControls();
            }
        }.execute();
    }

    private void showFailure(Exception e) {
        String message = ServerWindow.failure(e);
        log(message);
        JOptionPane.showMessageDialog(owner, message, "读写提示", JOptionPane.WARNING_MESSAGE);
    }

    private void log(String message) {
        messages.append(LocalTime.now().withNano(0) + "  " + message + "\n");
        messages.setCaretPosition(messages.getDocument().getLength());
    }

    private static String text(Object input) {
        if (input == null) return "空";
        if (input.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(input);
            var result = new java.util.ArrayList<String>();
            for (int i = 0; i < length; i++)
                result.add(String.valueOf(java.lang.reflect.Array.get(input, i)));
            return result.toString();
        }
        return input.toString();
    }

    private final class PointsModel extends AbstractTableModel {
        private final String[] columns = {"点位", "类型", "当前值", "质量", "源时间", "允许写入"};

        void refreshValues() {
            if (!points.isEmpty()) fireTableRowsUpdated(0, points.size() - 1);
        }

        public int getRowCount() {
            return points.size();
        }

        public int getColumnCount() {
            return columns.length;
        }

        public String getColumnName(int column) {
            return columns[column];
        }

        public Object getValueAt(int row, int column) {
            PointDefinition point = points.get(row);
            var result = values.get(point.alias());
            var data = result == null ? null : result.value();
            return switch (column) {
                case 0 -> point.alias();
                case 1 -> point.dataType();
                case 2 -> data == null ? "—" : text(data.getValue().getValue());
                case 3 ->
                        data == null
                                ? "未读取"
                                : data.getStatusCode() == null
                                        ? "无质量码"
                                        : data.getStatusCode().isGood()
                                                ? "Good"
                                                : data.getStatusCode().toString();
                case 4 ->
                        data == null || data.getSourceTime() == null
                                ? "—"
                                : data.getSourceTime().getJavaInstant().toString();
                default -> point.writable() ? "是" : "否";
            };
        }
    }
}

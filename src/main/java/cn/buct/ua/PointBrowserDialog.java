package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/** 在已认证会话内发现变量、明确勾选并增量保存点表。 */
final class PointBrowserDialog extends JDialog {
    private final UaConnection connection;
    private final ClientSettings settings;
    private final int maxReferences;
    private final long timeoutMs;
    private final DefaultMutableTreeNode root =
            new DefaultMutableTreeNode(
                    new PointDiscovery.Node(
                            NodeIds.ObjectsFolder,
                            "http://opcfoundation.org/UA/",
                            "连接性",
                            NodeClass.Object));
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);
    private final PointsModel tableModel = new PointsModel();
    private final JTable table =
            new JTable(tableModel) {
                @Override
                public String getToolTipText(java.awt.event.MouseEvent event) {
                    int row = rowAtPoint(event.getPoint());
                    if (row < 0) return null;
                    var point = tableModel.rows.get(convertRowIndexToModel(row));
                    return point.namespaceUri()
                            + ";"
                            + point.identifierType()
                            + "="
                            + point.identifier();
                }
            };
    private final JButton refresh = new JButton("刷新设备");
    private final JButton scan = new JButton("刷新标签");
    private final JButton stop = new JButton("停止");
    private final JButton reload = new JButton("重载点表");
    private final JButton save = new JButton("保存勾选点位");
    private final JButton close = new JButton("关闭");
    private final JLabel status = new JLabel("正在加载设备与节点…");
    private final JTextArea notices = new JTextArea(3, 60);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Map<String, String> aliases = new HashMap<>();
    private PointCatalog catalog;
    private boolean busy;
    private boolean saving;
    private boolean closing;

    PointBrowserDialog(
            ServerWindow owner, UaConnection connection, ClientSettings settings, Path config)
            throws Exception {
        super(owner, "点位浏览与选择", true);
        this.connection = connection;
        this.settings = settings;
        var options = ClientSettings.read(config.toAbsolutePath().normalize());
        maxReferences = Integer.parseInt(options.getProperty("browse.maxReferences", "500"));
        timeoutMs = Long.parseLong(options.getProperty("browse.timeoutMs", "60000"));
        if (maxReferences < 1 || maxReferences > 10000 || timeoutMs < 1000 || timeoutMs > 300000)
            throw new IllegalArgumentException("浏览参数范围：引用 1～10000，总超时 1000～300000ms");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(860, 540));
        setSize(1120, 680);
        setLocationRelativeTo(owner);
        var content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        var toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        toolbar.add(refresh);
        toolbar.add(scan);
        toolbar.add(stop);
        toolbar.add(reload);
        content.add(toolbar, BorderLayout.NORTH);
        tree.setRootVisible(true);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(26);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {60, 280, 160, 220};
        for (int i = 0; i < widths.length; i++)
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        var split =
                new JSplitPane(
                        JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(tree), new JScrollPane(table));
        split.setDividerLocation(250);
        content.add(split, BorderLayout.CENTER);
        var bottom = new JPanel(new BorderLayout(0, 6));
        notices.setEditable(false);
        notices.setLineWrap(true);
        notices.setWrapStyleWord(true);
        bottom.add(new JScrollPane(notices), BorderLayout.CENTER);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        var selectAll = new JButton("勾选可读点");
        var deselect = new JButton("取消勾选");
        actions.add(selectAll);
        actions.add(deselect);
        actions.add(save);
        actions.add(close);
        var lower = new JPanel(new BorderLayout(0, 6));
        var target = new JLabel("保存点表：" + displayPath(settings.nodesFile()));
        lower.add(target, BorderLayout.NORTH);
        lower.add(status, BorderLayout.CENTER);
        lower.add(actions, BorderLayout.SOUTH);
        bottom.add(lower, BorderLayout.SOUTH);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        refresh.addActionListener(e -> loadBranch(selectedBranch(), false));
        scan.addActionListener(e -> scanBranch());
        stop.addActionListener(
                e -> {
                    cancelled.set(true);
                    status.setText("正在停止，请等待当前请求清理…");
                });
        reload.addActionListener(e -> reloadCatalog());
        save.addActionListener(e -> saveSelected());
        close.addActionListener(e -> closeDialog());
        selectAll.addActionListener(
                e -> {
                    if (!busy) tableModel.selectReadable(true);
                });
        deselect.addActionListener(
                e -> {
                    if (!busy) tableModel.selectReadable(false);
                });
        tree.addTreeWillExpandListener(
                new TreeWillExpandListener() {
                    public void treeWillExpand(TreeExpansionEvent e) {
                        var branch = (DefaultMutableTreeNode) e.getPath().getLastPathComponent();
                        if (!busy
                                && branch.getChildCount() == 1
                                && branch.getChildAt(0) instanceof DefaultMutableTreeNode child
                                && "待加载".equals(child.getUserObject())) loadBranch(branch, false);
                    }

                    public void treeWillCollapse(TreeExpansionEvent e) {}
                });
        tree.addTreeSelectionListener(
                event -> {
                    if (!busy) scanBranch();
                });
        addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        closeDialog();
                    }
                });
        setBusy(false);
        SwingUtilities.invokeLater(() -> loadBranch(root, true));
    }

    private static Path displayPath(Path file) {
        Path base = Path.of("").toAbsolutePath().normalize();
        Path absolute = file.toAbsolutePath().normalize();
        return absolute.startsWith(base) ? base.relativize(absolute) : file;
    }

    private DefaultMutableTreeNode selectedBranch() {
        Object selected = tree.getLastSelectedPathComponent();
        return selected instanceof DefaultMutableTreeNode node ? node : root;
    }

    private void loadBranch(DefaultMutableTreeNode branch, boolean initial) {
        if (busy || !(branch.getUserObject() instanceof PointDiscovery.Node node)) return;
        runTask(
                () -> connection.browseNodes(node.nodeId(), maxReferences),
                children -> {
                    branch.removeAllChildren();
                    for (var child : children) {
                        if (!DeviceTagView.visible(child) || child.nodeClass() != NodeClass.Object)
                            continue;
                        var entry = new DefaultMutableTreeNode(child);
                        if (child.nodeClass() == NodeClass.Object)
                            entry.add(new DefaultMutableTreeNode("待加载"));
                        branch.add(entry);
                    }
                    busy = true;
                    treeModel.reload(branch);
                    tree.expandPath(new TreePath(branch.getPath()));
                    tree.setSelectionPath(new TreePath(branch.getPath()));
                    busy = false;
                    if (initial) status.setText("请选择左侧设备，查看该设备配置的标签。");
                    else SwingUtilities.invokeLater(this::scanBranch);
                },
                "正在刷新设备…");
    }

    private void scanBranch() {
        if (busy || !(selectedBranch().getUserObject() instanceof PointDiscovery.Node node)) return;
        tableModel.replace(List.of());
        notices.setText("");
        if (node.nodeId().equals(NodeIds.ObjectsFolder)) {
            status.setText("请选择左侧设备，查看该设备配置的标签。");
            setBusy(false);
            return;
        }
        runTask(
                () -> {
                    PointDiscovery.Result result =
                            connection.discoverDeviceTags(
                                    node, maxReferences, timeoutMs, cancelled::get);
                    return new ScanResult(result, readCatalog());
                },
                result -> {
                    installCatalog(result.catalog());
                    tableModel.replace(result.scan().points());
                    var messages = new ArrayList<>(result.scan().notices());
                    if (result.catalog().error() != null) messages.add(result.catalog().error());
                    notices.setText(String.join("\n", messages));
                    status.setText(
                            "发现 "
                                    + result.scan().points().size()
                                    + " 个标签；勾选后保存，新点默认只读。"
                                    + (result.scan().notices().isEmpty() ? "" : " 本次结果不完整，请查看提示。"));
                    setBusy(false);
                },
                "正在加载 " + node.name() + " 的标签…");
    }

    private record ScanResult(PointDiscovery.Result scan, LoadedCatalog catalog) {}

    private record LoadedCatalog(PointCatalog catalog, Map<String, String> aliases, String error) {}

    private LoadedCatalog readCatalog() {
        try {
            var loaded = new PointCatalog(settings.nodesFile());
            var loadedAliases = new HashMap<String, String>();
            for (var point : loaded.existingPoints())
                loadedAliases.put(
                        DiscoveredPoint.keyOf(
                                point.namespaceUri(), point.identifierType(), point.identifier()),
                        point.alias());
            return new LoadedCatalog(loaded, Map.copyOf(loadedAliases), null);
        } catch (Exception e) {
            return new LoadedCatalog(
                    null, Map.of(), "原点表暂不可合并：" + ServerWindow.failure(e) + "；修正后点击“重载点表”。");
        }
    }

    private void installCatalog(LoadedCatalog loaded) {
        catalog = loaded.catalog();
        aliases.clear();
        aliases.putAll(loaded.aliases());
    }

    private void reloadCatalog() {
        if (busy) return;
        runTask(
                this::readCatalog,
                loaded -> {
                    installCatalog(loaded);
                    notices.setText(
                            loaded.error() == null ? "点表已重新加载，已有业务属性保持原样。" : loaded.error());
                    tableModel.fireTableDataChanged();
                    setBusy(false);
                },
                "正在加载原点表…");
        stop.setEnabled(false);
    }

    private void saveSelected() {
        if (busy || catalog == null) return;
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        List<DiscoveredPoint> selected = tableModel.selected();
        if (selected.isEmpty()) {
            status.setText("请先勾选需要保存的变量。");
            return;
        }
        PointCatalog current = catalog;
        saving = true;
        runTask(
                () -> current.save(selected),
                result -> {
                    installCatalog(
                            new LoadedCatalog(
                                    current,
                                    current.existingPoints().stream()
                                            .collect(
                                                    java.util.stream.Collectors.toMap(
                                                            point ->
                                                                    DiscoveredPoint.keyOf(
                                                                            point.namespaceUri(),
                                                                            point.identifierType(),
                                                                            point.identifier()),
                                                            PointDefinition::alias,
                                                            (first, duplicate) -> first)),
                                    null));
                    tableModel.fireTableDataChanged();
                    status.setText(
                            "保存成功：新增 "
                                    + result.added()
                                    + " 个，已有 "
                                    + result.existing()
                                    + " 个保持原配置。");
                    notices.setText(
                            "点表已保存到 " + displayPath(result.file()) + "。原点表如有修改已先备份；再次扫描可发现新点。");
                },
                "正在保存点表…");
        stop.setEnabled(false);
    }

    private <T> void runTask(Callable<T> task, Consumer<T> completed, String message) {
        if (busy) return;
        cancelled.set(false);
        setBusy(true);
        status.setText(message);
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return task.call();
            }

            @Override
            protected void done() {
                saving = false;
                setBusy(false);
                if (closing) {
                    dispose();
                    return;
                }
                try {
                    T value = get();
                    if (cancelled.get()) {
                        status.setText("操作已停止，请重新扫描。");
                        return;
                    }
                    completed.accept(value);
                } catch (Exception e) {
                    Throwable cause = e;
                    while (cause.getCause() != null) cause = cause.getCause();
                    if (cause instanceof CancellationException) status.setText("扫描已停止。");
                    else {
                        status.setText("操作未完成");
                        JOptionPane.showMessageDialog(
                                PointBrowserDialog.this,
                                ServerWindow.failure(e),
                                "点位提示",
                                JOptionPane.WARNING_MESSAGE);
                    }
                }
                setBusy(false);
            }
        }.execute();
    }

    private void setBusy(boolean value) {
        busy = value;
        refresh.setEnabled(!value);
        scan.setEnabled(!value);
        reload.setEnabled(!value);
        save.setEnabled(!value && catalog != null && tableModel.getRowCount() > 0);
        stop.setEnabled(value);
        close.setEnabled(!saving);
        table.setEnabled(!value);
        tree.setEnabled(!value);
    }

    private void closeDialog() {
        if (saving) {
            status.setText("正在保存点表，请等待保存结果后关闭。");
            return;
        }
        if (busy) {
            closing = true;
            cancelled.set(true);
            status.setText("正在结束当前操作后关闭…");
        } else dispose();
    }

    private final class PointsModel extends AbstractTableModel {
        private final String[] columns = {"选择", "标记名称", "数据类型", "配置代称"};
        private List<DiscoveredPoint> rows = List.of();
        private boolean[] selected = new boolean[0];

        void replace(List<DiscoveredPoint> values) {
            rows = List.copyOf(values);
            selected = new boolean[rows.size()];
            fireTableDataChanged();
        }

        void selectReadable(boolean value) {
            for (int i = 0; i < rows.size(); i++) selected[i] = value && rows.get(i).readable();
            fireTableDataChanged();
        }

        List<DiscoveredPoint> selected() {
            var result = new ArrayList<DiscoveredPoint>();
            for (int i = 0; i < rows.size(); i++) if (selected[i]) result.add(rows.get(i));
            return List.copyOf(result);
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return columns.length;
        }

        public String getColumnName(int column) {
            return columns[column];
        }

        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : String.class;
        }

        public boolean isCellEditable(int row, int column) {
            return !busy && column == 0 && rows.get(row).readable();
        }

        public void setValueAt(Object value, int row, int column) {
            if (column == 0) {
                selected[row] = Boolean.TRUE.equals(value);
                fireTableCellUpdated(row, column);
            }
        }

        public Object getValueAt(int row, int column) {
            var point = rows.get(row);
            return switch (column) {
                case 0 -> selected[row];
                case 1 -> point.displayName();
                case 2 -> point.dataType();
                default -> aliases.getOrDefault(point.key(), "保存时自动生成");
            };
        }
    }
}

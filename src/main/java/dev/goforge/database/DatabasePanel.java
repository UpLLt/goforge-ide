package dev.goforge.database;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.*;
import java.util.List;

public final class DatabasePanel extends JPanel implements Disposable {
    private final Project project;
    private final DatabaseService service;
    private final DatabaseProfiles store;
    private final JComboBox<DatabaseProfile> profiles = new JComboBox<>();
    private final JButton add = new JButton("New…"), edit = new JButton("Edit…"), remove = new JButton("Remove"), connect = new JButton("Connect"),
            disconnect = new JButton("Disconnect"), refresh = new JButton("Refresh"), select = new JButton("Select Rows SQL"), rowEdit = new JButton("Edit Row by Key…"),
            execute = new JButton("Execute"), explain = new JButton("Explain"), complete = new JButton("Complete"),
            cancel = new JButton("Cancel"), applyTransaction = new JButton("Apply"), limits = new JButton("Limits…");
    private final JComboBox<String> transactionOperation = new JComboBox<>(new String[]{"Begin", "Commit", "Rollback"});
    private final JLabel status = new JLabel();
    private final JTextArea sql = new JTextArea("SELECT sqlite_version() AS version;", 7, 40);
    private final JSpinner rowLimit = new JSpinner(new SpinnerNumberModel(500, 1, 10000, 100));
    private final JSpinner timeout = new JSpinner(new SpinnerNumberModel(30, 1, 300, 5));
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Database");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final Tree tree = new Tree(treeModel);
    private final Map<DatabaseSession.Table, DefaultMutableTreeNode> tableNodes = new HashMap<>();
    private final Set<DatabaseSession.Table> loaded = new HashSet<>();
    private final JTabbedPane results = new JTabbedPane();
    private boolean renderingTree;
    private boolean transactionOpen, transactionReady;
    private long sqlRevision;
    private JBPopup completionPopup;

    DatabasePanel(Project project, DatabaseService service) {
        super(new BorderLayout(0, 4)); this.project = project; this.service = service; store = DatabaseProfiles.getInstance(project);
        JPanel connections = new JPanel(new FlowLayout(FlowLayout.LEADING, JBUI.scale(3), JBUI.scale(2)));
        profiles.setPreferredSize(JBUI.size(190, 28));
        for (Component component : new Component[]{profiles, add, edit, remove, connect, disconnect, refresh, limits}) connections.add(component);
        add(connections, BorderLayout.NORTH);
        JPanel editor = new JPanel(new BorderLayout(0, 4));
        sql.setFont(new Font(Font.MONOSPACED, Font.PLAIN, sql.getFont().getSize())); sql.setTabSize(4);
        sql.getAccessibleContext().setAccessibleName("Database SQL statement");
        sql.setToolTipText("Execute selected SQL or one complete statement. SQL history is not saved.");
        transactionOperation.getAccessibleContext().setAccessibleName("Database transaction operation");
        transactionOperation.setToolTipText("Choose Begin, Commit or Rollback, then click Apply. Choosing alone does nothing.");
        applyTransaction.getAccessibleContext().setAccessibleName("Apply database transaction");
        applyTransaction.setToolTipText("Apply the selected transaction operation. SQL text is not executed.");
        explain.setToolTipText("Estimate one SELECT/WITH query's plan. Does not enable ANALYZE or separately execute the original query.");
        complete.setToolTipText("Ctrl+Space: current connection tables, columns and flat FROM/JOIN aliases. Your SQL is not executed.");
        JPanel commands = new JPanel(new FlowLayout(FlowLayout.LEADING, JBUI.scale(3), JBUI.scale(2)));
        for (Component component : new Component[]{execute, explain, complete, cancel, transactionOperation, applyTransaction}) commands.add(component);
        editor.add(commands, BorderLayout.NORTH); editor.add(new JScrollPane(sql));
        OnePixelSplitter console = new OnePixelSplitter(true, .5f, .15f, .85f);
        console.setHonorComponentsMinimumSize(false); console.setFirstComponent(editor); console.setSecondComponent(results);
        JPanel browser = new JPanel(new BorderLayout(0, 4)); browser.add(new JScrollPane(tree));
        JPanel tableActions = new JPanel(new GridLayout(2, 1, 0, 3)); tableActions.add(select); tableActions.add(rowEdit); browser.add(tableActions, BorderLayout.SOUTH);
        OnePixelSplitter split = new OnePixelSplitter(false, .24f, .1f, .65f);
        split.setHonorComponentsMinimumSize(false); split.setFirstComponent(browser); split.setSecondComponent(console);
        add(split); add(status, BorderLayout.SOUTH);
        add.addActionListener(event -> configure(null)); edit.addActionListener(event -> configure((DatabaseProfile) profiles.getSelectedItem()));
        remove.addActionListener(event -> {
            DatabaseProfile selected = (DatabaseProfile) profiles.getSelectedItem();
            if (selected != null && Messages.showYesNoDialog(project, "Remove connection settings for " + selected.name() + "? Database files and server data are not deleted.", "Remove Connection", Messages.getQuestionIcon()) == Messages.YES) {
                store.remove(selected.id()); reloadProfiles(null);
            }
        });
        connect.addActionListener(event -> {
            DatabaseProfile profile = (DatabaseProfile) profiles.getSelectedItem(); if (profile == null) return;
            char[] password = profile.kind() == DatabaseProfile.Kind.SQLITE ? new char[0] : DatabaseConnectionDialog.askPassword(project, profile);
            if (password != null) service.connect(profile, password);
        });
        disconnect.addActionListener(event -> service.disconnect(true)); refresh.addActionListener(event -> service.refreshSchema());
        execute.addActionListener(event -> execute()); cancel.addActionListener(event -> service.cancel());
        explain.addActionListener(event -> service.explain(selectedSql(), (Integer) rowLimit.getValue(), (Integer) timeout.getValue()));
        complete.addActionListener(event -> complete());
        limits.addActionListener(event -> configureLimits());
        transactionOperation.addActionListener(event -> refreshTransactionAction());
        applyTransaction.addActionListener(event -> {
            if (applyTransaction.isEnabled()) service.transaction((String) transactionOperation.getSelectedItem());
        });
        select.addActionListener(event -> {
            DatabaseSession.Table table = selectedTable(); DatabaseProfile profile = service.connectedProfile();
            if (table != null && profile != null) sql.setText(selectSql(table, profile.kind()));
        });
        rowEdit.addActionListener(event -> { DatabaseSession.Table table = selectedTable(); if (table != null) service.editRow(table); });
        tree.addTreeSelectionListener(event -> {
            if (renderingTree) return;
            DatabaseSession.Table table = selectedTable();
            if (table != null && !loaded.contains(table)) service.columns(table);
        });
        sql.getInputMap().put(KeyStroke.getKeyStroke("control ENTER"), "execute-sql");
        sql.getInputMap().put(KeyStroke.getKeyStroke("meta ENTER"), "execute-sql");
        sql.getActionMap().put("execute-sql", new AbstractAction() { @Override public void actionPerformed(ActionEvent event) { if (execute.isEnabled()) execute(); } });
        sql.getInputMap().put(KeyStroke.getKeyStroke("control SPACE"), "complete-sql");
        sql.getActionMap().put("complete-sql", new AbstractAction() { @Override public void actionPerformed(ActionEvent event) { if (complete.isEnabled()) complete(); } });
        sql.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() { sqlRevision++; closeCompletion(); }
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        });
        reloadProfiles(null); showMessage("Results", "Connect, enter a single SQL statement, then click Execute. Select Rows SQL prepares a query without executing it.");
    }
    private void configure(DatabaseProfile existing) {
        DatabaseConnectionDialog dialog = new DatabaseConnectionDialog(project, existing);
        if (dialog.showAndGet()) { store.put(dialog.profile()); reloadProfiles(dialog.profile().id()); }
    }
    private void reloadProfiles(String selectedId) {
        profiles.removeAllItems(); for (DatabaseProfile profile : store.profiles()) { profiles.addItem(profile); if (profile.id().equals(selectedId)) profiles.setSelectedItem(profile); }
        connect.setEnabled(profiles.getItemCount() > 0); edit.setEnabled(profiles.getItemCount() > 0); remove.setEnabled(profiles.getItemCount() > 0);
    }
    private void execute() {
        service.execute(selectedSql(), (Integer) rowLimit.getValue(), (Integer) timeout.getValue());
    }
    private String selectedSql() { String selection = sql.getSelectedText(); return selection == null || selection.isBlank() ? sql.getText() : selection; }
    private void complete() {
        closeCompletion(); String text = sql.getText(); int caret = sql.getCaretPosition(); long revision = sqlRevision;
        long connection = service.connectionGeneration();
        service.complete(text, caret, result -> {
            if (!completionStillCurrent(text, caret, revision, connection) || result.items().isEmpty()) return;
            completionPopup = JBPopupFactory.getInstance().createPopupChooserBuilder(result.items())
                    .setTitle("Current schema · choose an identifier")
                    .setItemChosenCallback(item -> applyCompletion(text, caret, revision, connection, result, item))
                    .createPopup();
            completionPopup.showUnderneathOf(complete);
        });
    }
    long sqlRevision() { return sqlRevision; }
    boolean completionStillCurrent(String text, int caret, long revision, long connection) {
        return sqlRevision == revision && sql.getText().equals(text) && sql.getCaretPosition() == caret && service.isConnected()
                && service.connectionGeneration() == connection;
    }
    void applyCompletion(String text, int caret, long revision, long connection, SqlCompletion.Result result, SqlCompletion.Item item) {
        if (!completionStillCurrent(text, caret, revision, connection) || !result.items().contains(item)) return;
        if (result.start() < 0 || result.end() < result.start() || result.end() > text.length()) return;
        sql.replaceRange(item.text(), result.start(), result.end()); sql.setCaretPosition(result.start() + item.text().length()); sql.requestFocusInWindow();
    }
    private void closeCompletion() { if (completionPopup != null) { completionPopup.cancel(); completionPopup = null; } }
    private void configureLimits() {
        JSpinner rows = new JSpinner(new SpinnerNumberModel(((Number) rowLimit.getValue()).intValue(), 1, 10000, 100));
        JSpinner seconds = new JSpinner(new SpinnerNumberModel(((Number) timeout.getValue()).intValue(), 1, 300, 5));
        DialogWrapper dialog = new DialogWrapper(project) {
            { setTitle("Database Query Limits"); init(); }
            @Override protected JComponent createCenterPanel() {
                JPanel fields = new JPanel(new GridLayout(2, 2, 8, 8));
                fields.add(new JLabel("Maximum displayed rows")); fields.add(rows);
                fields.add(new JLabel("Query timeout (seconds)")); fields.add(seconds); return fields;
            }
        };
        if (dialog.showAndGet()) { rowLimit.setValue(rows.getValue()); timeout.setValue(seconds.getValue()); }
    }
    private void refreshTransactionAction() {
        Object selected = transactionOperation.getSelectedItem();
        boolean valid = transactionOpen ? "Commit".equals(selected) || "Rollback".equals(selected) : "Begin".equals(selected);
        transactionOperation.setEnabled(transactionReady);
        applyTransaction.setEnabled(transactionReady && valid);
    }
    void render(boolean busy, boolean connected, boolean transaction, String message) {
        status.setText(message); status.setToolTipText(message);
        profiles.setEnabled(!busy && !connected); add.setEnabled(!busy && !connected);
        edit.setEnabled(!busy && !connected && profiles.getItemCount() > 0); remove.setEnabled(edit.isEnabled());
        connect.setEnabled(!busy && !connected && profiles.getItemCount() > 0);
        disconnect.setEnabled(connected || busy); refresh.setEnabled(connected && !busy); select.setEnabled(connected && !busy);
        rowEdit.setEnabled(connected && !busy && !transaction);
        execute.setEnabled(connected && !busy); cancel.setEnabled(busy);
        explain.setEnabled(connected && !busy); complete.setEnabled(connected && !busy);
        if (!connected || busy) closeCompletion();
        transactionReady = connected && !busy;
        if (transactionOpen != transaction || !connected) {
            transactionOpen = transaction;
            // Beginning a transaction never selects Commit implicitly.
            transactionOperation.setSelectedItem(transaction ? "Rollback" : "Begin");
        }
        refreshTransactionAction();
        limits.setEnabled(!busy);
    }
    void showSchema(DatabaseSession.Schema schema) {
        renderingTree = true;
        try {
            root.removeAllChildren(); tableNodes.clear(); loaded.clear(); Map<String, DefaultMutableTreeNode> groups = new LinkedHashMap<>();
            for (DatabaseSession.Table table : schema.tables()) {
                DefaultMutableTreeNode group = groups.computeIfAbsent(table.group(), name -> { var node = new DefaultMutableTreeNode(name); root.add(node); return node; });
                var node = new DefaultMutableTreeNode(table); group.add(node); tableNodes.put(table, node);
                node.add(new DefaultMutableTreeNode("Select table to load columns"));
            }
            if (schema.tables().isEmpty()) root.add(new DefaultMutableTreeNode("No tables or views. Create one using SQL, then Refresh."));
            treeModel.reload(); tree.expandRow(0);
        } finally { renderingTree = false; }
    }
    void showColumns(DatabaseSession.Table table, List<DatabaseSession.Column> columns) {
        var node = tableNodes.get(table); if (node == null) return;
        renderingTree = true;
        try { node.removeAllChildren(); columns.forEach(column -> node.add(new DefaultMutableTreeNode(column, false))); loaded.add(table); treeModel.reload(node); tree.expandPath(new javax.swing.tree.TreePath(node.getPath())); }
        finally { renderingTree = false; }
    }
    private DatabaseSession.Table selectedTable() {
        Object node = tree.getLastSelectedPathComponent();
        if (node instanceof DefaultMutableTreeNode current) {
            if (current.getUserObject() instanceof DatabaseSession.Table table) return table;
            if (current.getParent() instanceof DefaultMutableTreeNode parent && parent.getUserObject() instanceof DatabaseSession.Table table) return table;
        }
        return null;
    }
    void showResult(DatabaseSession.Result result) {
        results.removeAll(); int index = 0;
        for (DatabaseSession.Grid grid : result.grids()) {
            JBTable table = new JBTable(new ResultModel(grid)); table.setAutoCreateRowSorter(true); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); table.setCellSelectionEnabled(true);
            for (int column = 0; column < table.getColumnCount(); column++) table.getColumnModel().getColumn(column).setPreferredWidth(150);
            JPanel content = new JPanel(new BorderLayout()); content.add(new JScrollPane(table));
            JButton export = new JButton("Export Displayed Rows as CSV…");
            export.addActionListener(event -> {
                JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle("Export Displayed Database Rows"); chooser.setSelectedFile(new java.io.File("query-result.csv"));
                if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
                var path = chooser.getSelectedFile().toPath();
                if (java.nio.file.Files.exists(path) && Messages.showYesNoDialog(project, "Replace " + path.getFileName() + "?", "Export CSV", Messages.getQuestionIcon()) != Messages.YES) return;
                service.export(grid, path);
            });
            JPanel footer = new JPanel(new BorderLayout(4, 0));
            JLabel count = new JLabel(grid.rows().size() + " displayed rows" + (grid.truncated() ? " — truncated" : ""));
            count.setToolTipText("Display limits: chosen row count, approx. 5M characters, 4096 text characters / 256 binary bytes per cell.");
            footer.add(count); footer.add(export, BorderLayout.EAST); content.add(footer, BorderLayout.SOUTH);
            results.addTab("Result " + (++index), content);
        }
        String message = "Elapsed: " + result.elapsedMillis() + " ms\nUpdate counts: " + result.updateCounts()
                + "\nTransaction: " + (result.inTransaction() ? "open" : "autocommit")
                + (result.warnings().isEmpty() ? "" : "\nWarnings:\n" + String.join("\n", result.warnings()));
        appendMessage("Messages", message);
    }
    void showPlan(DatabaseSession.Result result) {
        showResult(result);
        for (int index = 0; index < result.grids().size(); index++) results.setTitleAt(index, "Plan " + (index + 1));
    }
    void clearResults() { showSchema(new DatabaseSession.Schema(List.of(), false)); showMessage("Results", "Disconnected. Query results cleared."); }
    void showError(String message) { showMessage("Error", message); }
    private void showMessage(String title, String message) { results.removeAll(); appendMessage(title, message); }
    private void appendMessage(String title, String message) {
        JTextArea area = new JTextArea(message); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true); results.addTab(title, new JScrollPane(area));
    }
    static String selectSql(DatabaseSession.Table table, DatabaseProfile.Kind kind) {
        String quote = kind == DatabaseProfile.Kind.MYSQL ? "`" : "\"";
        String namespace = kind == DatabaseProfile.Kind.MYSQL ? table.catalog() : table.schema();
        String name = quote + table.name().replace(quote, quote + quote) + quote;
        if (namespace != null && !namespace.isBlank()) name = quote + namespace.replace(quote, quote + quote) + quote + "." + name;
        return "SELECT * FROM " + name + ";";
    }
    static final class ResultModel extends AbstractTableModel {
        private final DatabaseSession.Grid grid;
        ResultModel(DatabaseSession.Grid grid) { this.grid = grid; }
        @Override public int getRowCount() { return grid.rows().size(); }
        @Override public int getColumnCount() { return grid.columns().size(); }
        @Override public String getColumnName(int column) { return grid.columns().get(column); }
        @Override public Object getValueAt(int row, int column) { return grid.rows().get(row).get(column); }
        @Override public boolean isCellEditable(int row, int column) { return false; }
    }
    @Override public void dispose() { closeCompletion(); service.detach(this); sql.setText(""); results.removeAll(); }
}

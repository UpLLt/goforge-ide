package dev.goforge.database;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.Nullable;
import javax.swing.*;
import java.awt.*;

final class DatabaseConnectionDialog extends DialogWrapper {
    private final Project project;
    private final String id;
    private final JTextField name = new JTextField(28), sqlite = new JTextField(28), host = new JTextField(28),
            database = new JTextField(28), username = new JTextField(28);
    private final JComboBox<DatabaseProfile.Kind> kind = new JComboBox<>(DatabaseProfile.Kind.values());
    private final JSpinner port = new JSpinner(new SpinnerNumberModel(5432, 1, 65535, 1));
    private final JCheckBox tls = new JCheckBox("Require TLS and verify server identity", true);
    private final JButton browse = new JButton("Browse…");
    private DatabaseProfile result;

    DatabaseConnectionDialog(Project project, DatabaseProfile existing) {
        super(project); this.project = project; id = existing == null ? DatabaseProfile.newId() : existing.id();
        setTitle(existing == null ? "New Database Connection" : "Edit Database Connection"); setOKButtonText("Save Connection");
        name.setText(existing == null ? "SQLite" : existing.name()); sqlite.setText(existing == null ? ":memory:" : existing.sqlitePath());
        host.setText(existing == null ? "localhost" : existing.host()); database.setText(existing == null ? "" : existing.database());
        username.setText(existing == null ? "" : existing.username());
        if (existing != null) { kind.setSelectedItem(existing.kind()); if (existing.port() > 0) port.setValue(existing.port()); tls.setSelected(existing.verifyTls()); }
        kind.addActionListener(event -> { var selected = selectedKind(); if (selected.defaultPort > 0) port.setValue(selected.defaultPort); updateFields(); });
        browse.addActionListener(event -> {
            var file = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("Select SQLite Database"), project, null);
            if (file != null) sqlite.setText(file.getPath());
        });
        init(); updateFields();
    }
    private DatabaseProfile.Kind selectedKind() { return (DatabaseProfile.Kind) kind.getSelectedItem(); }
    private void updateFields() {
        boolean local = selectedKind() == DatabaseProfile.Kind.SQLITE;
        sqlite.setEnabled(local); browse.setEnabled(local);
        host.setEnabled(!local); port.setEnabled(!local); database.setEnabled(!local); username.setEnabled(!local); tls.setEnabled(!local);
    }
    @Override protected @Nullable JComponent createCenterPanel() {
        JPanel form = new JPanel(new GridBagLayout()); GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4); c.anchor = GridBagConstraints.WEST;
        row(form, c, 0, "Name", name); row(form, c, 1, "Database type", kind);
        JPanel path = new JPanel(new BorderLayout(4, 0)); path.add(sqlite); path.add(browse, BorderLayout.EAST);
        row(form, c, 2, "SQLite file", path); row(form, c, 3, "Host", host); row(form, c, 4, "Port", port);
        row(form, c, 5, "Database", database); row(form, c, 6, "User", username); row(form, c, 7, "Transport", tls);
        c.gridx = 0; c.gridy = 8; c.gridwidth = 2;
        JTextArea note = new JTextArea("SQLite: :memory: is temporary; an absolute file path is created only when you click Connect.\nPasswords are requested on connection and kept only in memory. SQL and results are not saved.");
        note.setEditable(false); note.setOpaque(false); note.setLineWrap(true); note.setWrapStyleWord(true); note.setColumns(46); note.setRows(3);
        form.add(note, c); return form;
    }
    private static void row(JPanel panel, GridBagConstraints c, int row, String text, Component field) {
        c.gridy = row; c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE; panel.add(new JLabel(text), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; panel.add(field, c);
    }
    private DatabaseProfile read() {
        return new DatabaseProfile(id, name.getText(), selectedKind(), sqlite.getText(), host.getText(), (Integer) port.getValue(), database.getText(), username.getText(), tls.isSelected());
    }
    @Override protected @Nullable ValidationInfo doValidate() {
        try { read(); return null; } catch (RuntimeException error) { return new ValidationInfo(error.getMessage()); }
    }
    @Override protected void doOKAction() { result = read(); super.doOKAction(); }
    DatabaseProfile profile() { return result; }

    static char[] askPassword(Project project, DatabaseProfile profile) {
        JPasswordField password = new JPasswordField(24);
        DialogWrapper dialog = new DialogWrapper(project) {
            { setTitle("Connect to " + profile.name()); setOKButtonText("Connect"); init(); }
            @Override protected JComponent createCenterPanel() {
                JPanel panel = new JPanel(new BorderLayout(0, 8));
                panel.add(new JLabel("Password for " + profile.username() + " (memory only)"), BorderLayout.NORTH); panel.add(password);
                return panel;
            }
            @Override public JComponent getPreferredFocusedComponent() { return password; }
        };
        char[] value = dialog.showAndGet() ? password.getPassword() : null; password.setText(""); return value;
    }
}

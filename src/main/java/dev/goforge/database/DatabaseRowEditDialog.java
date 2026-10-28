package dev.goforge.database;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DatabaseRowEditDialog extends DialogWrapper {
    private final Map<String, JTextField> keys = new LinkedHashMap<>();
    private final JComboBox<String> column = new JComboBox<>();
    private final JTextArea value = new JTextArea(4, 32);
    private final JCheckBox nullValue = new JCheckBox("Set SQL NULL");
    DatabaseRowEditDialog(Project project, DatabaseSession.Table table, List<DatabaseSession.Column> columns) {
        super(project); setTitle("Edit Row in " + table.name()); setOKButtonText("Stage Update");
        columns.stream().filter(DatabaseSession.Column::primaryKey).forEach(c -> keys.put(c.name(), new JTextField(24)));
        columns.stream().filter(c -> !c.primaryKey()).forEach(c -> column.addItem(c.name()));
        nullValue.addActionListener(event -> value.setEnabled(!nullValue.isSelected())); init();
    }
    @Override protected JComponent createCenterPanel() {
        JPanel content = new JPanel(new BorderLayout(4, 8)); JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
        keys.forEach((name, input) -> { fields.add(new JLabel("Primary key: " + name)); fields.add(input); });
        fields.add(new JLabel("Column to update")); fields.add(column); content.add(fields, BorderLayout.NORTH);
        JPanel update = new JPanel(new BorderLayout()); update.add(new JScrollPane(value)); update.add(nullValue, BorderLayout.SOUTH); content.add(update);
        JTextArea note = new JTextArea("Only the row matching every supplied key is updated, using bound parameters.\nThis stages one update in a transaction. Review the count, then Commit or Rollback.");
        note.setEditable(false); note.setOpaque(false); content.add(note, BorderLayout.SOUTH); return content;
    }
    @Override protected ValidationInfo doValidate() {
        if (keys.isEmpty() || column.getItemCount() == 0) return new ValidationInfo("This editor requires a primary key and at least one non-key column.");
        return null;
    }
    Map<String, String> keys() { Map<String, String> result = new LinkedHashMap<>(); keys.forEach((name, input) -> result.put(name, input.getText())); return result; }
    String column() { return (String) column.getSelectedItem(); }
    String value() { return nullValue.isSelected() ? null : value.getText(); }
}

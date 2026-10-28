package dev.goforge.project;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.DocumentAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;

final class NewGoProjectDialog extends DialogWrapper {
    private final JTextField name = new JTextField("hello-go", 28);
    private final JTextField parent = new JTextField(System.getProperty("user.home"), 28);
    private final JTextField module = new JTextField("example.com/hello-go", 28);
    private final JComboBox<GoProjectScaffolder.Template> template = new JComboBox<>(GoProjectScaffolder.Template.values());
    private String suggestedModule = "example.com/hello-go";

    NewGoProjectDialog(@Nullable Project project) {
        super(project, true);
        setTitle("New Go Project");
        setOKButtonText("Create Project");
        name.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override protected void textChanged(@NotNull DocumentEvent e) {
                String next = "example.com/" + name.getText().trim();
                if (module.getText().equals(suggestedModule)) module.setText(next);
                suggestedModule = next;
            }
        });
        init();
    }

    @Override protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        addRow(panel, 0, "Project name", name);
        addRow(panel, 1, "Parent directory", parent);
        addRow(panel, 2, "Module path", module);
        addRow(panel, 3, "Template", template);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 4; c.gridwidth = 2; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(12, 0, 0, 0);
        panel.add(new JLabel("Creates a local module, example code and tests."), c);
        return panel;
    }

    private static void addRow(JPanel panel, int row, String text, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(5, 0, 5, 12);
        JLabel label = new JLabel(text); label.setLabelFor(field); panel.add(label, c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(5, 0, 5, 0);
        panel.add(field, c);
    }

    @Override public @Nullable JComponent getPreferredFocusedComponent() { return name; }

    @Override protected @Nullable ValidationInfo doValidate() {
        String projectName = name.getText().trim();
        if (projectName.isEmpty() || projectName.equals(".") || projectName.equals("..")
                || projectName.contains("/") || projectName.contains("\\") || projectName.indexOf('\0') >= 0)
            return new ValidationInfo("Enter a project name without path separators.", name);
        try {
            if (!Files.isDirectory(parentDirectory())) return new ValidationInfo("Choose an existing parent directory.", parent);
            if (Files.exists(target())) return new ValidationInfo("This directory already exists. Choose another name, or open the existing project.", name);
            GoProjectScaffolder.validateModulePath(modulePath());
        } catch (IllegalArgumentException e) { return new ValidationInfo(e.getMessage(), module); }
        return null;
    }

    Path parentDirectory() { return Path.of(parent.getText().trim()).toAbsolutePath().normalize(); }
    Path target() { return parentDirectory().resolve(name.getText().trim()); }
    String modulePath() { return module.getText().trim(); }
    GoProjectScaffolder.Template template() { return (GoProjectScaffolder.Template) template.getSelectedItem(); }
}

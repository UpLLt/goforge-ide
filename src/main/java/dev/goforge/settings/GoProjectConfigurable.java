package dev.goforge.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import javax.swing.*;

public final class GoProjectConfigurable implements Configurable {
    private final Project project;
    private JBTextField tags, os, arch;
    public GoProjectConfigurable(Project project) { this.project = project; }
    @Override public String getDisplayName() { return "Go Project"; }
    @Override public JComponent createComponent() {
        tags = new JBTextField(); os = new JBTextField(); arch = new JBTextField();
        var panel = FormBuilder.createFormBuilder().addLabeledComponent("Build tags (comma separated):", tags)
                .addLabeledComponent("GOOS (empty = host):", os).addLabeledComponent("GOARCH (empty = host):", arch)
                .addComponent(new JBLabel("<html>Applies to this project's Go commands and gopls.<br>Restart gopls in Language Servers after changes.<br>Run configuration environment and explicit flags can override these defaults.</html>"))
                .addComponentFillVertically(new JPanel(), 0).getPanel();
        reset(); return panel;
    }
    @Override public boolean isModified() {
        if (tags == null) return false;
        var state = GoProjectSettings.getInstance(project);
        return !tags.getText().trim().equals(state.buildTags) || !os.getText().trim().equals(state.goos) || !arch.getText().trim().equals(state.goarch);
    }
    @Override public void apply() throws ConfigurationException {
        String value = tags.getText().trim();
        if (!value.isEmpty() && !value.matches("[\\p{L}\\p{N}_.]+(?:,[\\p{L}\\p{N}_.]+)*"))
            throw new ConfigurationException("Enter comma-separated build tags without spaces.");
        for (var field : new JBTextField[]{os, arch}) if (!field.getText().trim().isEmpty() && !field.getText().trim().matches("[a-z0-9]+"))
            throw new ConfigurationException("GOOS and GOARCH use lowercase Go platform names.");
        var state = GoProjectSettings.getInstance(project); state.buildTags = value;
        state.goos = os.getText().trim(); state.goarch = arch.getText().trim();
    }
    @Override public void reset() {
        if (tags == null) return;
        var state = GoProjectSettings.getInstance(project);
        tags.setText(state.buildTags); os.setText(state.goos); arch.setText(state.goarch);
    }
    @Override public void disposeUIResources() { tags = os = arch = null; }
}

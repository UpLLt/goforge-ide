package dev.goforge.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GoForgeConfigurable implements Configurable {
    private JBTextField go, gopls, delve;
    private JBCheckBox staticcheck;
    private JPanel panel;

    @Override public String getDisplayName() { return "GoForge"; }
    @Override public @Nullable JComponent createComponent() {
        go = new JBTextField(); gopls = new JBTextField(); delve = new JBTextField();
        staticcheck = new JBCheckBox("Enable gopls staticcheck diagnostics");
        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Go executable:", go)
            .addLabeledComponent("gopls executable:", gopls)
            .addLabeledComponent("Delve executable:", delve)
            .addComponent(staticcheck)
            .addComponent(new JBLabel("<html>Leave paths empty to use the bundled tools. Explicit environment or project tools take priority.<br>Restart gopls in Language Servers after changing these settings.</html>"))
            .addComponentFillVertically(new JPanel(), 0).getPanel();
        reset();
        return panel;
    }
    @Override public boolean isModified() {
        if (panel == null) return false;
        var s = GoForgeSettings.getInstance();
        return !go.getText().trim().equals(s.goPath) || !gopls.getText().trim().equals(s.goplsPath)
            || !delve.getText().trim().equals(s.delvePath) || staticcheck.isSelected() != s.staticcheck;
    }
    @Override public void apply() throws ConfigurationException {
        validate(go.getText()); validate(gopls.getText()); validate(delve.getText());
        var s = GoForgeSettings.getInstance();
        s.goPath = go.getText().trim(); s.goplsPath = gopls.getText().trim();
        s.delvePath = delve.getText().trim(); s.staticcheck = staticcheck.isSelected();
    }
    private static void validate(String text) throws ConfigurationException {
        String value = text.trim();
        if (value.isEmpty()) return;
        try {
            var p = Path.of(value);
            if (!p.isAbsolute() || !Files.isRegularFile(p) || !Files.isExecutable(p))
                throw new ConfigurationException("Choose an absolute path to an executable file: " + value);
        } catch (java.nio.file.InvalidPathException e) {
            throw new ConfigurationException("Invalid executable path: " + value);
        }
    }
    @Override public void reset() {
        if (panel == null) return;
        var s = GoForgeSettings.getInstance();
        go.setText(s.goPath); gopls.setText(s.goplsPath); delve.setText(s.delvePath);
        staticcheck.setSelected(s.staticcheck);
    }
    @Override public void disposeUIResources() { panel = null; go = gopls = delve = null; staticcheck = null; }
}

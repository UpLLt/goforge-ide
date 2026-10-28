package dev.goforge.run;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.util.LinkedHashMap;

public final class GoRunSettingsEditor extends SettingsEditor<GoRunConfiguration> {
    private final JComboBox<GoCommand> command = new JComboBox<>(GoCommand.values());
    private final JBTextField directory = new JBTextField(), target = new JBTextField();
    private final JBTextField goArguments = new JBTextField(), programArguments = new JBTextField();
    private final JTextArea environment = new JTextArea(5, 40);
    private final JPanel panel;

    public GoRunSettingsEditor() {
        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Command:", command)
            .addLabeledComponent("Working directory:", directory)
            .addLabeledComponent("Package / file / subcommand:", target)
            .addLabeledComponent("Go arguments:", goArguments)
            .addLabeledComponent("Program arguments (go run):", programArguments)
            .addLabeledComponent("Environment (one NAME=value per line):", new JScrollPane(environment))
            .addComponent(new JBLabel("<html>Arguments support quoting. Commands run directly without a shell.<br>Use ./... for tests, tidy for go mod, or edit -json for go work.<br>For mod/work/tool, Go arguments follow the subcommand; otherwise they precede the package target.</html>"))
            .addComponentFillVertically(new JPanel(), 0).getPanel();
        command.addActionListener(e -> programArguments.setEnabled(command.getSelectedItem() == GoCommand.RUN));
    }
    @Override protected void resetEditorFrom(@NotNull GoRunConfiguration config) {
        command.setSelectedItem(config.getCommand()); directory.setText(config.getDirectory()); target.setText(config.getTarget());
        goArguments.setText(config.getGoArguments()); programArguments.setText(config.getProgramArguments());
        environment.setText(String.join("\n", config.getEnvironment().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
        programArguments.setEnabled(config.getCommand() == GoCommand.RUN);
    }
    @Override protected void applyEditorTo(@NotNull GoRunConfiguration config) throws ConfigurationException {
        var variables = new LinkedHashMap<String, String>();
        for (String line : environment.getText().split("\n")) {
            if (line.isBlank()) continue;
            int equals = line.indexOf('=');
            if (equals < 1 || !line.substring(0, equals).matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new ConfigurationException("Every environment entry must have the form NAME=value.");
            variables.put(line.substring(0, equals), line.substring(equals + 1));
        }
        config.setCommand((GoCommand) command.getSelectedItem()); config.setDirectory(directory.getText().trim());
        config.setTarget(target.getText().trim()); config.setGoArguments(goArguments.getText());
        config.setProgramArguments(programArguments.getText());
        config.getEnvironment().clear(); config.getEnvironment().putAll(variables);
    }
    @Override protected @NotNull JComponent createEditor() { return panel; }
}

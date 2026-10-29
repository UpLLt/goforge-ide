package dev.goforge.run;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.util.execution.ParametersListUtil;
import dev.goforge.settings.GoProjectSettings;
import dev.goforge.testing.GoTestSelection;
import dev.goforge.testing.GoTestSelector;
import dev.goforge.tools.HostTools;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.util.LinkedHashMap;

public final class GoRunSettingsEditor extends SettingsEditor<GoRunConfiguration> {
    private enum TestScope {
        PACKAGE("Package / manual"), NAMED("Named Test / Example");
        private final String title;
        TestScope(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }
    private final JComboBox<GoCommand> command = new JComboBox<>(GoCommand.values());
    private final JComboBox<TestScope> testScope = new JComboBox<>(TestScope.values());
    private final JTextArea testNames = new JTextArea(3, 40);
    private final JCheckBox freshRun = new JCheckBox("Run selected names afresh (-count=1)");
    private final JBLabel selectionError = new JBLabel();
    private final JBTextField directory = new JBTextField(), target = new JBTextField();
    private final JBTextField goArguments = new JBTextField(), programArguments = new JBTextField();
    private final JTextArea environment = new JTextArea(5, 40);
    private final JPanel panel;

    public GoRunSettingsEditor() {
        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Command:", command)
            .addLabeledComponent("Working directory:", directory)
            .addLabeledComponent("Package / file / subcommand:", target)
            .addLabeledComponent("Test selection:", testScope)
            .addLabeledComponent("Test names (one full name per line):", new JScrollPane(testNames))
            .addComponent(freshRun)
            .addComponent(selectionError)
            .addLabeledComponent("Go arguments:", goArguments)
            .addLabeledComponent("Program arguments (go run):", programArguments)
            .addLabeledComponent("Environment (one NAME=value per line):", new JScrollPane(environment))
            .addComponent(new JBLabel("<html>Arguments support quoting. Commands run directly without a shell.<br>Use ./... for tests, tidy for go mod, or edit -json for go work.<br>Named tests use package '.' in the chosen working directory; names are literal.<br>A selected subtest also runs its parents and descendants. Use names observed in the test tree.<br>For mod/work/tool, Go arguments follow the subcommand; otherwise they precede the package target.</html>"))
            .addComponentFillVertically(new JPanel(), 0).getPanel();
        testScope.getAccessibleContext().setAccessibleName("Test selection");
        testNames.getAccessibleContext().setAccessibleName("Test names");
        command.addActionListener(e -> {
            if (command.getSelectedItem() != GoCommand.TEST) testScope.setSelectedItem(TestScope.PACKAGE);
            updateEnabledFields();
        });
        testScope.addActionListener(e -> updateEnabledFields());
        updateEnabledFields();
    }
    @Override protected void resetEditorFrom(@NotNull GoRunConfiguration config) {
        command.setSelectedItem(config.getCommand()); directory.setText(config.getDirectory()); target.setText(config.getTarget());
        goArguments.setText(config.getGoArguments()); programArguments.setText(config.getProgramArguments());
        environment.setText(String.join("\n", config.getEnvironment().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
        var selection = config.getTestSelection();
        testScope.setSelectedItem(selection != null || config.getTestSelectionReadError() != null ? TestScope.NAMED : TestScope.PACKAGE);
        testNames.setText(selection == null ? "" : String.join("\n", selection.testNames()));
        freshRun.setSelected(selection != null && selection.rerun());
        selectionError.setText(config.getTestSelectionReadError() == null ? "" : config.getTestSelectionReadError());
        updateEnabledFields();
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
        GoTestSelection selected = null;
        if (testScope.getSelectedItem() == TestScope.NAMED) {
            try {
                if (command.getSelectedItem() != GoCommand.TEST || !".".equals(target.getText().trim()))
                    throw new IllegalArgumentException("Named tests require Command 'test' and package target '.'.");
                selected = new GoTestSelection(testNames.getText().lines().filter(line -> !line.isEmpty()).toList(), freshRun.isSelected());
                var effective = new GeneralCommandLine().withEnvironment(GoProjectSettings.getInstance(config.getProject()).environment())
                    .withEnvironment(variables).getEffectiveEnvironment();
                GoTestSelector.validateArguments(ParametersListUtil.parse(goArguments.getText()), HostTools.environmentValue(effective, "GOFLAGS"));
            } catch (IllegalArgumentException invalid) { throw new ConfigurationException(invalid.getMessage()); }
        }
        config.setCommand((GoCommand) command.getSelectedItem()); config.setDirectory(directory.getText().trim());
        config.setTarget(target.getText().trim()); config.setGoArguments(goArguments.getText());
        config.setProgramArguments(programArguments.getText());
        config.getEnvironment().clear(); config.getEnvironment().putAll(variables);
        config.setTestSelection(selected);
        selectionError.setText("");
    }
    private void updateEnabledFields() {
        boolean testing = command.getSelectedItem() == GoCommand.TEST;
        testScope.setEnabled(testing);
        boolean named = testing && testScope.getSelectedItem() == TestScope.NAMED;
        testNames.setEnabled(named); freshRun.setEnabled(named);
        programArguments.setEnabled(command.getSelectedItem() == GoCommand.RUN);
    }
    @Override protected @NotNull JComponent createEditor() { return panel; }
}

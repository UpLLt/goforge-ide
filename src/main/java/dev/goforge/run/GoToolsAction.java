package dev.goforge.run;

import com.intellij.execution.RunManager;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;

/** Creates ordinary editable run configurations, preserving the platform console, Stop and rerun actions. */
public final class GoToolsAction extends DumbAwareAction {
    record Preset(String label, GoCommand command, String target, String arguments, String description) {
        @Override public String toString() { return label; }
    }
    static final Preset[] PRESETS = {
        new Preset("List module dependencies", GoCommand.LIST, "all", "-m -json", "Read the module dependency graph (may download missing metadata)."),
        new Preset("Tidy module dependencies", GoCommand.MOD, "tidy", "", "Updates go.mod/go.sum using the Go toolchain."),
        new Preset("Download module dependencies", GoCommand.MOD, "download", "", "Downloads dependencies using your Go proxy configuration."),
        new Preset("Verify module cache", GoCommand.MOD, "verify", "", "Checks downloaded dependency checksums."),
        new Preset("Vendor dependencies", GoCommand.MOD, "vendor", "", "Recreates this module's vendor directory."),
        new Preset("Show workspace modules", GoCommand.WORK, "edit -json", "", "Reads the current go.work workspace."),
        new Preset("Initialize workspace", GoCommand.WORK, "init .", "", "Creates go.work; refuses an existing workspace file."),
        new Preset("Add module to workspace", GoCommand.WORK, "use .", "", "Adds the chosen module path to go.work; edit the path below."),
        new Preset("Sync workspace dependencies", GoCommand.WORK, "sync", "", "Updates module dependencies to match the workspace build list."),
        new Preset("Generate code", GoCommand.GENERATE, "./...", "", "Runs the project's go:generate commands; use only a trusted project."),
        new Preset("Run benchmarks", GoCommand.TEST, "./...", "-run ^$ -bench . -benchmem", "Runs benchmarks, records allocations and shows raw benchmark output."),
        new Preset("Fuzz a target", GoCommand.TEST, ".", "-run ^$ -fuzz FuzzName -fuzztime 30s", "Replace FuzzName with one fuzz target; mutations run for 30 seconds."),
        new Preset("Inspect Go environment", GoCommand.ENV, "GOVERSION GOROOT GOPATH GOMOD GOWORK GOOS GOARCH", "", "Shows the active Go toolchain and workspace configuration.")
    };
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null && event.getProject().getBasePath() != null);
    }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject(); if (project == null || project.getBasePath() == null) return;
        String initial = project.getBasePath();
        var selected = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (selected != null && selected.isInLocalFileSystem()) initial = (selected.isDirectory() ? selected : selected.getParent()).getPath();
        var choice = new JComboBox<>(PRESETS);
        var directory = new JBTextField(initial);
        var target = new JBTextField();
        var arguments = new JBTextField();
        var description = new JBLabel();
        Runnable update = () -> {
            var preset = (Preset) choice.getSelectedItem(); target.setText(preset.target()); arguments.setText(preset.arguments());
            description.setText("<html>" + preset.description() + "<br>The command is saved as a run configuration; Stop cancels it.</html>");
        };
        choice.addActionListener(e -> update.run()); update.run();
        var form = FormBuilder.createFormBuilder().addLabeledComponent("Task:", choice)
                .addLabeledComponent("Working directory:", directory).addLabeledComponent("Package / subcommand:", target)
                .addLabeledComponent("Go arguments:", arguments).addComponent(description).getPanel();
        var dialog = new DialogWrapper(project) {
            { setTitle("Go Tools"); setOKButtonText("Run Task"); init(); }
            @Override protected @Nullable JComponent createCenterPanel() { return form; }
            @Override protected @Nullable ValidationInfo doValidate() {
                try {
                    if (!Files.isDirectory(Path.of(directory.getText().trim()))) return new ValidationInfo("Choose an existing directory.", directory);
                } catch (RuntimeException badPath) { return new ValidationInfo("Invalid directory.", directory); }
                if (target.getText().isBlank()) return new ValidationInfo("Enter a package or subcommand.", target);
                return null;
            }
        };
        if (!dialog.showAndGet()) return;
        var preset = (Preset) choice.getSelectedItem();
        var manager = RunManager.getInstance(project);
        var factory = ConfigurationTypeUtil.findConfigurationType(GoRunConfigurationType.class).getConfigurationFactories()[0];
        var settings = manager.createConfiguration("Go Tools — " + preset.label(), factory);
        var config = (GoRunConfiguration) settings.getConfiguration();
        config.setCommand(preset.command()); config.setDirectory(directory.getText().trim());
        config.setTarget(target.getText().trim()); config.setGoArguments(arguments.getText().trim());
        manager.setTemporaryConfiguration(settings); manager.setSelectedConfiguration(settings);
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance());
    }
}

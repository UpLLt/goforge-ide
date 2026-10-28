package dev.goforge.run;

import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Standard Go pprof profiles and viewer; no proprietary profile format or analysis engine. */
public final class GoProfilingAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent e) { e.getPresentation().setEnabled(e.getProject() != null && e.getProject().getBasePath() != null); }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject(); if (project == null || project.getBasePath() == null) return;
        var mode = new JComboBox<>(new String[]{"Record CPU and memory from tests", "View profile (interactive pprof)", "Show profile top entries", "Compare profile with baseline"});
        var directory = new JBTextField(project.getBasePath());
        var target = new JBTextField(".");
        var arguments = new JBTextField("-count=1");
        var profile = new JBTextField(); var baseline = new JBTextField();
        var chooseProfile = new JButton("Choose…"); var chooseBaseline = new JButton("Choose…");
        chooseProfile.addActionListener(e -> choose(project, profile)); chooseBaseline.addActionListener(e -> choose(project, baseline));
        var profileRow = new JPanel(new java.awt.BorderLayout(5, 0)); profileRow.add(profile); profileRow.add(chooseProfile, java.awt.BorderLayout.EAST);
        var baselineRow = new JPanel(new java.awt.BorderLayout(5, 0)); baselineRow.add(baseline); baselineRow.add(chooseBaseline, java.awt.BorderLayout.EAST);
        var description = new JBLabel();
        Runnable refresh = () -> {
            boolean record = mode.getSelectedIndex() == 0, compare = mode.getSelectedIndex() == 3;
            target.setEnabled(record); arguments.setEnabled(record); profile.setEnabled(!record); chooseProfile.setEnabled(!record);
            baseline.setEnabled(compare); chooseBaseline.setEnabled(compare);
            description.setText(record ? "<html>Profiles and test binary go to a new temporary directory, shown in the Run console.<br>For longer CPU samples, add -run ^$ -bench . -benchtime 5s. Use one package.</html>"
                    : "<html>Uses the bundled Go pprof viewer. Interactive mode opens your browser on loopback.<br>Stop in the Run console terminates the viewer. Graph view also needs Graphviz.</html>");
        };
        mode.addActionListener(e -> refresh.run()); refresh.run();
        var panel = FormBuilder.createFormBuilder().addLabeledComponent("Mode:", mode).addLabeledComponent("Working directory:", directory)
                .addLabeledComponent("Package:", target).addLabeledComponent("Test flags:", arguments)
                .addLabeledComponent("Profile:", profileRow).addLabeledComponent("Baseline:", baselineRow).addComponent(description).getPanel();
        var dialog = new DialogWrapper(project) {
            { setTitle("Go Profiling"); setOKButtonText("Run"); init(); }
            @Override protected @Nullable JComponent createCenterPanel() { return panel; }
            @Override protected @Nullable ValidationInfo doValidate() {
                try {
                    if (!Files.isDirectory(Path.of(directory.getText()))) return new ValidationInfo("Choose a working directory.", directory);
                    if (mode.getSelectedIndex() == 0) {
                        var packages = ParametersListUtil.parse(target.getText());
                        if (packages.size() != 1 || packages.getFirst().startsWith("-") || packages.getFirst().contains("..."))
                            return new ValidationInfo("Choose one package, such as . or ./service.", target);
                        for (String argument : ParametersListUtil.parse(arguments.getText()))
                            if (argument.matches("-+(cpu|mem|block|mutex)profile(?:=.*)?") || argument.equals("-o") || argument.startsWith("-o="))
                                return new ValidationInfo("Output paths are assigned to a fresh profile directory.", arguments);
                    } else {
                        if (!Files.isRegularFile(Path.of(profile.getText()))) return new ValidationInfo("Select an existing profile.", profile);
                        if (mode.getSelectedIndex() == 3 && !Files.isRegularFile(Path.of(baseline.getText()))) return new ValidationInfo("Select a baseline profile.", baseline);
                    }
                } catch (RuntimeException invalid) { return new ValidationInfo("Invalid path or arguments."); }
                return null;
            }
        };
        if (!dialog.showAndGet()) return;
        var manager = RunManager.getInstance(project);
        var factory = ConfigurationTypeUtil.findConfigurationType(GoRunConfigurationType.class).getConfigurationFactories()[0];
        var settings = manager.createConfiguration("Go Profiling — " + mode.getSelectedItem(), factory);
        var run = (GoRunConfiguration) settings.getConfiguration(); run.setDirectory(directory.getText().trim());
        try {
            if (mode.getSelectedIndex() == 0) {
                Path output = Files.createTempDirectory("goforge-profile-");
                var flags = new ArrayList<>(ParametersListUtil.parse(arguments.getText()));
                flags.addAll(List.of("-cpuprofile=" + output.resolve("cpu.pprof"), "-memprofile=" + output.resolve("memory.pprof"), "-o=" + output.resolve("profile.test")));
                run.setCommand(GoCommand.TEST); run.setTarget(target.getText()); run.setGoArguments(ParametersListUtil.join(flags));
            } else {
                var command = new ArrayList<String>(); command.add("pprof");
                if (mode.getSelectedIndex() == 1) command.add("-http=127.0.0.1:0"); else command.add("-top");
                if (mode.getSelectedIndex() == 3) command.add("-diff_base=" + Path.of(baseline.getText()).toAbsolutePath());
                command.add(Path.of(profile.getText()).toAbsolutePath().toString());
                run.setCommand(GoCommand.TOOL); run.setTarget(ParametersListUtil.join(command));
            }
            manager.setTemporaryConfiguration(settings); manager.setSelectedConfiguration(settings);
            ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance());
        } catch (Exception error) { Messages.showErrorDialog(project, "Could not prepare the profile command: " + error.getMessage(), "Go Profiling"); }
    }
    private static void choose(Project project, JBTextField target) {
        var file = FileChooser.chooseFile(new FileChooserDescriptor(true, false, false, false, false, false).withTitle("Choose Go pprof File"), project, null);
        if (file != null) target.setText(file.getPath());
    }
}

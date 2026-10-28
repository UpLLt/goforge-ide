package dev.goforge.project;

import com.intellij.execution.RunManager;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import dev.goforge.run.GoCommand;
import dev.goforge.debug.GoDebugConfigurations;
import dev.goforge.run.GoRunConfiguration;
import dev.goforge.run.GoRunConfigurationType;
import dev.goforge.settings.GoForgeSettings;
import dev.goforge.tools.GoToolResolver;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.regex.Pattern;

public final class NewGoProjectAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var dialog = new NewGoProjectDialog(event.getProject());
        if (!dialog.showAndGet()) return;
        Path target = dialog.target();
        String module = dialog.modulePath();
        var template = dialog.template();
        new Task.Backgroundable(event.getProject(), "Creating Go project", false) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                try {
                    var command = new GeneralCommandLine();
                    String go = GoToolResolver.resolve(GoForgeSettings.getInstance().goPath, "go", null, command.getEffectiveEnvironment());
                    command.withExePath(go).withParameters("version").withEnvironment("GOTOOLCHAIN", "local");
                    var output = new CapturingProcessHandler(command).runProcess(10_000);
                    var version = Pattern.compile("\\bgo([0-9]+\\.[0-9]+)(?:\\.[0-9]+)?\\b").matcher(output.getStdout());
                    if (output.isTimeout() || output.getExitCode() != 0 || !version.find())
                        throw new IllegalStateException("Cannot read the Go SDK version. Check Settings → GoForge.");
                    GoProjectScaffolder.create(target, module, template, version.group(1));
                } catch (Exception error) { throw new IllegalStateException(error.getMessage(), error); }
            }
            @Override public void onSuccess() {
                try {
                    Project project = ProjectUtil.openOrImport(target, OpenProjectTask.build().asNewProject().withForceOpenInNewFrame(true));
                    if (project == null) throw new IllegalStateException("The files were created at " + target + ", but the project was not opened.");
                    configureRuns(project, target, template);
                    var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.resolve(template == GoProjectScaffolder.Template.APPLICATION ? "main.go" : "hello.go"));
                    if (file != null) FileEditorManager.getInstance(project).openFile(file, true);
                } catch (Exception error) { showError(event.getProject(), error); }
            }
            @Override public void onThrowable(@NotNull Throwable error) { showError(event.getProject(), error); }
        }.queue();
    }

    static void configureRuns(Project project, Path directory, GoProjectScaffolder.Template template) throws java.io.IOException {
        var manager = RunManager.getInstance(project);
        var factory = ConfigurationTypeUtil.findConfigurationType(GoRunConfigurationType.class).getConfigurationFactories()[0];
        for (GoCommand command : template == GoProjectScaffolder.Template.APPLICATION
                ? new GoCommand[]{GoCommand.RUN, GoCommand.TEST, GoCommand.BUILD}
                : new GoCommand[]{GoCommand.TEST, GoCommand.BUILD}) {
            var settings = manager.createConfiguration(command + " " + directory.getFileName(), factory);
            var run = (GoRunConfiguration) settings.getConfiguration();
            run.setCommand(command); run.setDirectory(directory.toString());
            run.setTarget(command == GoCommand.RUN ? "." : "./...");
            manager.addConfiguration(settings);
            if (command == GoCommand.RUN || (template == GoProjectScaffolder.Template.LIBRARY && command == GoCommand.TEST))
                manager.setSelectedConfiguration(settings);
        }
        GoDebugConfigurations.addForProject(project, directory, template == GoProjectScaffolder.Template.APPLICATION);
    }

    private static void showError(Project project, Throwable error) {
        NotificationGroupManager.getInstance().getNotificationGroup("GoForge")
            .createNotification("Could not create Go project", error.getMessage() == null ? error.toString() : error.getMessage(), NotificationType.ERROR)
            .notify(project);
    }
}

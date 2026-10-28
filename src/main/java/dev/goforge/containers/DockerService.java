package dev.goforge.containers;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service(Service.Level.PROJECT)
public final class DockerService implements Disposable {
    public static final String WINDOW_ID = "Containers";
    private final Project project;
    private final AtomicReference<Work> active = new AtomicReference<>();
    private DockerPanel panel;
    private volatile boolean disposed;
    private static final class Work { volatile boolean cancelled; String error, output, status; final java.util.List<Runnable> updates = new java.util.ArrayList<>(); }
    @FunctionalInterface interface RequestFactory { DockerCommands.Request create() throws Exception; }
    @FunctionalInterface private interface Job { void run(Work work, ProgressIndicator indicator) throws Exception; }
    public DockerService(Project project) { this.project = project; }
    public static DockerService getInstance(Project project) { return project.getService(DockerService.class); }
    public boolean isBusy() { return active.get() != null; }
    public void cancel() { var work = active.get(); if (work != null) work.cancelled = true; }
    Path directory() { return Path.of(project.getBasePath() == null ? System.getProperty("user.home") : project.getBasePath()); }
    void attach(DockerPanel panel) { this.panel = panel; }
    private boolean cancelled(Work work, ProgressIndicator indicator) { return work.cancelled || indicator.isCanceled() || disposed || project.isDisposed(); }
    private void ui(Work work, Runnable update) {
        work.updates.add(update);
    }
    private void submit(String title, Job job) {
        var work = new Work();
        if (disposed || project.isDisposed() || !active.compareAndSet(null, work)) return;
        panel.busy(true, title);
        new Task.Backgroundable(project, title, true) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                try { job.run(work, indicator); }
                catch (Exception error) { work.error = error.getMessage() == null ? error.toString() : error.getMessage(); }
            }
            @Override public void onCancel() { work.cancelled = true; }
            @Override public void onThrowable(@NotNull Throwable error) { work.error = error.toString(); }
            @Override public void onFinished() {
                if (active.get() != work || disposed || project.isDisposed()) return;
                if (!work.cancelled) for (Runnable update : work.updates) update.run();
                if (work.output != null) panel.output(work.output);
                active.compareAndSet(work, null);
                panel.busy(false, work.cancelled ? "Cancelled; refresh to check Docker state" : work.error == null ? work.status == null ? "Ready" : work.status : "Failed");
                if (work.error != null) panel.output(work.error);
            }
        }.queue();
    }
    private DockerClient.Result execute(String executable, String context, DockerCommands.Request request, Map<String,String> env, Work work, ProgressIndicator indicator) throws Exception {
        indicator.setText(request.title());
        return DockerClient.execute(executable, context, request, directory(), env, () -> cancelled(work, indicator));
    }
    void loadContexts(String configured) {
        submit("Load Docker contexts", (work, indicator) -> {
            var env = new GeneralCommandLine().getEffectiveEnvironment();
            var result = execute(DockerCommands.resolveExecutable(configured, env), null, DockerCommands.contexts(), env, work, indicator);
            var contexts = DockerData.contexts(result.requiredJson());
            ui(work, () -> { panel.setContexts(contexts); panel.output(result.output()); });
        });
    }
    void refresh(String configured, String context) {
        submit("Connect to Docker", (work, indicator) -> {
            var env = new GeneralCommandLine().getEffectiveEnvironment();
            String executable = DockerCommands.resolveExecutable(configured, env);
            var version = execute(executable, context, DockerCommands.version(), env, work, indicator);
            String connection = DockerData.connection(version.requiredJson());
            var containers = DockerData.containers(execute(executable, context, DockerCommands.containers(), env, work, indicator).requiredJson());
            var images = DockerData.images(execute(executable, context, DockerCommands.images(), env, work, indicator).requiredJson());
            ui(work, () -> { panel.inventory(context, connection, containers, images); panel.output(version.output()); });
        });
    }
    void request(String configured, String context, RequestFactory factory, boolean refreshInventory) {
        submit("Docker operation", (work, indicator) -> {
            var env = new GeneralCommandLine().getEffectiveEnvironment();
            String executable = DockerCommands.resolveExecutable(configured, env);
            var request = factory.create();
            var result = execute(executable, context, request, env, work, indicator);
            // Preserve real output even on nonzero exit/cancel; never invent a success row.
            work.output = result.output(); work.status = result.status();
            if (refreshInventory && result.succeeded() && !cancelled(work, indicator)) {
                var containers = DockerData.containers(execute(executable, context, DockerCommands.containers(), env, work, indicator).requiredJson());
                var images = DockerData.images(execute(executable, context, DockerCommands.images(), env, work, indicator).requiredJson());
                ui(work, () -> panel.inventory(context, "Connected · operation exit 0", containers, images));
            }
        });
    }
    void terminal(String configured, String context, String containerId, List<String> command) {
        submit("Open container terminal", (work, indicator) -> {
            String executable = DockerCommands.resolveExecutable(configured, new GeneralCommandLine().getEffectiveEnvironment());
            var arguments = DockerCommands.command(executable, context, DockerCommands.exec(containerId, command, true));
            ui(work, () -> {
                // Public API accepts the actual program/argv, no shell string is composed.
                TerminalToolWindowManager.getInstance(project).createNewSession(directory().toString(), "Docker " + containerId.substring(0,12), arguments, true, true);
                panel.output("Interactive Docker exec opened in Terminal. Exit the container program or close its Terminal tab to stop the local session.\nDocker exec programs already accepted by the daemon may continue after client disconnect.");
            });
        });
    }
    @Override public void dispose() { disposed = true; var work = active.getAndSet(null); if (work != null) work.cancelled = true; panel = null; }
}

package dev.goforge.coverage;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.ToolWindowManager;
import dev.goforge.settings.GoForgeSettings;
import dev.goforge.settings.GoProjectSettings;
import dev.goforge.tools.GoToolResolver;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

@Service(Service.Level.PROJECT)
public final class GoCoverageService implements Disposable {
    public static final String WINDOW_ID = "Go Coverage";
    private final Project project;
    private final GoCoverageMarkup markup;
    private final AtomicReference<Run> active = new AtomicReference<>();
    private volatile boolean disposed;
    private GoCoverageRun.Result result;
    private Path lastContext;
    private GoCoveragePanel panel;

    private static final class Run {
        volatile boolean cancelled, hidden;
        GoCoverageRun.Result result;
    }

    public GoCoverageService(Project project) { this.project = project; this.markup = new GoCoverageMarkup(project); }
    public static GoCoverageService getInstance(Project project) { return project.getService(GoCoverageService.class); }
    public boolean isRunning() { return active.get() != null; }

    public void start(Path context) {
        if (disposed || project.isDisposed()) return;
        var run = new Run();
        if (!active.compareAndSet(null, run)) { show(); return; }
        lastContext = context;
        result = null;
        markup.clear();
        show(); refresh();
        new Task.Backgroundable(project, "Go coverage", true) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                try {
                    var environment = GoProjectSettings.getInstance(project).buildEnvironment(new GeneralCommandLine().getEffectiveEnvironment());
                    String go = GoToolResolver.resolve(GoForgeSettings.getInstance().goPath, "go", project.getBasePath(), environment);
                    run.result = GoCoverageRun.execute(go, context, environment,
                            () -> run.cancelled || indicator.isCanceled() || disposed || project.isDisposed(), indicator::setText);
                } catch (Exception error) {
                    run.result = GoCoverageRun.Result.error(error.getMessage() == null ? error.toString() : error.getMessage());
                }
            }
            @Override public void onThrowable(@NotNull Throwable error) {
                run.result = GoCoverageRun.Result.error(error.getMessage() == null ? error.toString() : error.getMessage());
            }
            @Override public void onCancel() { run.cancelled = true; }
            @Override public void onFinished() {
                if (!active.compareAndSet(run, null) || disposed || project.isDisposed()) return;
                if (!run.hidden) {
                    result = completionResult(run.result, run.cancelled);
                    highlightOpenFiles();
                }
                refresh();
            }
        }.queue();
    }

    static GoCoverageRun.Result completionResult(GoCoverageRun.Result ready, boolean cancelled) {
        if (cancelled || ready == null) return new GoCoverageRun.Result(GoCoverageRun.Status.CANCELLED,
                ready == null ? null : ready.module(), ready == null ? null : ready.exitCode(), null, java.util.List.of(),
                ready == null ? "" : ready.output(), "Coverage cancelled; no measurement published");
        return ready;
    }

    void runAgain() {
        Path context = lastContext;
        if (context == null && project.getBasePath() != null) context = Path.of(project.getBasePath());
        if (context != null) start(context);
    }

    public void cancel() { Run run = active.get(); if (run != null) run.cancelled = true; }

    public void clear() {
        Run run = active.get();
        if (run != null) { run.cancelled = true; run.hidden = true; }
        result = null;
        markup.clear();
        refresh();
    }

    void attach(GoCoveragePanel panel) { this.panel = panel; refresh(); }
    private void refresh() { if (panel != null) panel.render(isRunning(), result); }
    private void show() {
        var window = ToolWindowManager.getInstance(project).getToolWindow(WINDOW_ID);
        if (window != null) window.activate(null);
    }

    private void highlightOpenFiles() {
        if (result == null) return;
        var open = FileEditorManager.getInstance(project).getOpenFiles();
        for (var virtual : open) {
            var document = FileDocumentManager.getInstance().getCachedDocument(virtual);
            if (document == null) continue;
            for (var file : result.files()) {
                if (file.source() != null && virtual.getPath().equals(file.source().path().toString())) markup.show(document, file);
            }
        }
    }

    void navigate(GoCoverageRun.FileResult file, GoCoverageProfile.Block block) {
        if (file.source() == null) { notice("This profile path was not mapped by go list."); return; }
        GoCoverageRun.Result expected = result;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            var virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file.source().path());
            ApplicationManager.getApplication().invokeLater(() -> {
                if (disposed || project.isDisposed() || result != expected) return;
                if (virtual == null || !virtual.isValid()) { notice("The source file no longer exists."); return; }
                var document = FileDocumentManager.getInstance().getDocument(virtual);
                if (document == null) { notice("The source file cannot be opened as text."); return; }
                if (!file.unchangedOnDisk() || !file.source().matches(document.getText())) {
                    new OpenFileDescriptor(project, virtual).navigate(true);
                    notice("Source differs from the tested snapshot. Opened the file without stale coverage positions; save and rerun.");
                    return;
                }
                try {
                    int offset = block == null ? 0 : GoCoverageOffsets.range(document.getText(), block).start();
                    new OpenFileDescriptor(project, virtual, offset).navigate(true);
                    if (!markup.show(document, file)) notice("Source opened. Coloring is unavailable for this file; numeric results remain visible.");
                } catch (IllegalArgumentException invalidPosition) { notice(invalidPosition.getMessage()); }
            });
        });
    }

    private void notice(String message) { if (panel != null) panel.notice(message); }

    @Override public void dispose() {
        disposed = true;
        Run run = active.getAndSet(null);
        if (run != null) run.cancelled = true;
        markup.dispose();
        panel = null;
    }
}

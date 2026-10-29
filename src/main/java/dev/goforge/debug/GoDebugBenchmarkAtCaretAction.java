package dev.goforge.debug;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import dev.goforge.run.GoCommand;
import dev.goforge.run.GoRunConfiguration;
import dev.goforge.run.GoRunConfigurationType;
import dev.goforge.settings.GoForgeSettings;
import dev.goforge.settings.GoProjectSettings;
import dev.goforge.testing.GoTestPackageIndex;
import dev.goforge.testing.GoTestSelector;
import dev.goforge.testing.GoTestSourceIndex;
import dev.goforge.tools.GoToolResolver;
import org.jetbrains.annotations.NotNull;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Creates a guarded local debug session directly from a saved benchmark's editor caret. */
public final class GoDebugBenchmarkAtCaretAction extends DumbAwareAction {
    record Request(Project project, VirtualFile file, String sourcePath, Document document, String text, long stamp,
                   long fileStamp, int offset, Settings settings) {
        void validate() throws ExecutionException {
            boolean current = ReadAction.compute(() -> !project.isDisposed() && file.isValid()
                    && file.getPath().equals(sourcePath)
                    && ProjectFileIndex.getInstance(project).isInContent(file)
                    && file.getModificationStamp() == fileStamp
                    && document.getModificationStamp() == stamp && document.getText().equals(text)
                    && !FileDocumentManager.getInstance().isDocumentUnsaved(document)
                    && settings.equals(Settings.capture(project)));
            if (!current) throw new ExecutionException("The selected benchmark source or Go settings changed. Save the source and select Debug Go Benchmark at Caret again; no debug process was started.");
        }
        Path resolveSource() throws ExecutionException {
            validate();
            try {
                Path resolved = Path.of(sourcePath).toRealPath();
                validate();
                return resolved;
            } catch (java.io.IOException error) { throw new ExecutionException("The selected benchmark source is unavailable at its original path. Select Debug again.", error); }
        }
        void validateSource(Path canonicalSource) throws ExecutionException {
            if (!resolveSource().equals(canonicalSource))
                throw new ExecutionException("The selected benchmark source now resolves to a different file. Select Debug again; no debug process was started.");
        }
    }
    record Settings(String go, String delve, String tags, String os, String arch) {
        static Settings capture(Project project) {
            var global = GoForgeSettings.getInstance(); var local = GoProjectSettings.getInstance(project);
            return new Settings(global.goPath, global.delvePath, local.buildTags, local.goos, local.goarch);
        }
    }
    record Prepared(GoRunConfiguration source, GoDebugSelectedTestAction.Prepared debug) { }
    @FunctionalInterface interface Preparation { Prepared prepare(Request request, ProgressIndicator indicator) throws Exception; }
    private final Preparation preparation;
    private final Consumer<RunnerAndConfigurationSettings> launcher;
    private final Consumer<String> observer;

    public GoDebugBenchmarkAtCaretAction() {
        this(GoDebugBenchmarkAtCaretAction::prepare,
                settings -> ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance()), ignored -> {});
    }
    GoDebugBenchmarkAtCaretAction(Preparation preparation, Consumer<RunnerAndConfigurationSettings> launcher, Consumer<String> observer) {
        this.preparation = Objects.requireNonNull(preparation);
        this.launcher = Objects.requireNonNull(launcher);
        this.observer = Objects.requireNonNull(observer);
    }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent event) {
        var request = capture(event);
        event.getPresentation().setText("Debug Go Benchmark at Caret");
        event.getPresentation().setDescription("Debug one iteration of the saved top-level benchmark at the caret");
        if (request != null) {
            var cached = GoTestSourceIndex.cached(Path.of(request.sourcePath()), request.text());
            if (cached != null) {
                var selected = cached.containing(request.offset());
                if (selected != null && "benchmark".equals(selected.kind()))
                    event.getPresentation().setText("Debug " + selected.name() + " at Caret");
            }
        }
        event.getPresentation().setEnabledAndVisible(request != null);
    }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Request request = capture(event);
        if (request == null) return;
        try { request.validate(); }
        catch (ExecutionException error) { showFailure(request.project(), error); return; }
        new Task.Backgroundable(request.project(), "Prepare Go benchmark at caret", true) {
            Prepared prepared;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                try {
                    observer.accept("prepare_start");
                    prepared = Objects.requireNonNull(preparation.prepare(request, indicator));
                    indicator.checkCanceled(); request.validate();
                    observer.accept("prepare_end");
                } catch (com.intellij.openapi.progress.ProcessCanceledException error) { throw error; }
                catch (CancellationException error) { throw new com.intellij.openapi.progress.ProcessCanceledException(); }
                catch (Exception error) { throw new RuntimeException(error.getMessage(), error); }
            }
            @Override public void onSuccess() {
                if (request.project().isDisposed()) return;
                try {
                    request.validate(); prepared.debug().guard().validate();
                    var settings = GoDebugSelectedTestAction.createConfiguration(prepared.source(), prepared.debug().directory(), prepared.debug().launch());
                    prepared.debug().guard().bind((DAPRunConfiguration) settings.getConfiguration());
                    var manager = RunManager.getInstance(request.project());
                    manager.setTemporaryConfiguration(settings); manager.setSelectedConfiguration(settings);
                    launcher.accept(settings); observer.accept("launched");
                } catch (Exception error) { showFailure(request.project(), error); }
            }
            @Override public void onCancel() { observer.accept("cancelled"); }
            @Override public void onThrowable(@NotNull Throwable error) {
                observer.accept("failed");
                if (!request.project().isDisposed()) showFailure(request.project(), error);
            }
        }.queue();
    }
    private static Request capture(AnActionEvent event) {
        return ReadAction.compute(() -> {
            var project = event.getProject(); var editor = event.getData(CommonDataKeys.EDITOR);
            if (project == null || project.isDisposed() || editor == null || editor.isDisposed()) return null;
            var document = editor.getDocument(); var file = FileDocumentManager.getInstance().getFile(document);
            if (file == null || !file.isValid() || !file.isInLocalFileSystem() || !file.getName().endsWith("_test.go")
                    || document.getTextLength() > 2_097_152 || !ProjectFileIndex.getInstance(project).isInContent(file)) return null;
            return new Request(project, file, file.getPath(), document, document.getText(), document.getModificationStamp(),
                    file.getModificationStamp(), editor.getCaretModel().getOffset(), Settings.capture(project));
        });
    }
    private static Prepared prepare(Request request, ProgressIndicator indicator) throws Exception {
        request.validate(); indicator.checkCanceled();
        var project = request.project(); BooleanSupplier stopping = () -> indicator.isCanceled() || project.isDisposed();
        var line = new GeneralCommandLine().withEnvironment(GoProjectSettings.getInstance(project).environment());
        String go = GoToolResolver.resolve(request.settings().go(), "go", project.getBasePath(), line.getEffectiveEnvironment());
        Path file = request.resolveSource();
        var snapshot = GoTestSourceIndex.inspect(go, line.getEffectiveEnvironment(), file, request.text(), stopping);
        var selection = GoBenchmarkAtCaretPlan.select(snapshot, file, request.text(), request.offset());
        indicator.setText("Prepare Debug " + selection.testNames().getFirst() + " (one iteration)");
        request.validateSource(file); indicator.checkCanceled();
        var factory = ConfigurationTypeUtil.findConfigurationType(GoRunConfigurationType.class).getConfigurationFactories()[0];
        var source = new GoRunConfiguration(project, factory, selection.testNames().getFirst());
        source.setCommand(GoCommand.TEST); source.setDirectory(file.getParent().toString()); source.setTarget(".");
        source.setTestSelection(selection); source.setGoArguments("-benchtime=1x"); source.checkConfiguration();
        var packages = GoTestPackageIndex.prepare(source, stopping);
        GoBenchmarkAtCaretPlan.requireActiveSource(packages, snapshot, selection);
        var guard = GoSelectedTestDebugGuard.capture(project, packages).withAdditionalCheck(() -> request.validateSource(file));
        var arguments = new ArrayList<>(GoTestSelector.splitGoFlags(GoTestPackageIndex.preparedGoFlags(source)));
        arguments.addAll(GoProjectSettings.getInstance(project).buildFlags());
        var launch = GoBenchmarkAtCaretPlan.launch(file.getParent().toString(), selection, arguments);
        request.validateSource(file); indicator.checkCanceled();
        return new Prepared(source, new GoDebugSelectedTestAction.Prepared(file.getParent().toString(), launch, guard));
    }
    private static void showFailure(Project project, Throwable failure) {
        Messages.showErrorDialog(project, failure.getMessage(), "Go Benchmark Debug");
    }
}

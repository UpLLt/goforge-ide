package dev.goforge.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.WrappingRunConfiguration;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import dev.goforge.settings.GoForgeSettings;
import dev.goforge.settings.GoProjectSettings;
import dev.goforge.testing.GoTestContextSelection;
import dev.goforge.testing.GoTestSelection;
import dev.goforge.testing.GoTestSourceIndex;
import dev.goforge.tools.GoToolResolver;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class GoRunConfigurationProducer extends LazyRunConfigurationProducer<GoRunConfiguration> {
    @FunctionalInterface interface SourceResolver {
        GoTestSourceIndex.Snapshot inspect(String go, Map<String, String> environment, Path file,
                String text, BooleanSupplier cancelled) throws Exception;
    }
    private final SourceResolver resolver;
    public GoRunConfigurationProducer() { this(GoTestSourceIndex::inspect); }
    GoRunConfigurationProducer(SourceResolver resolver) { this.resolver = resolver; }
    private static final Key<Request> REQUEST = Key.create("goforge.test.context.request");
    private record Request(VirtualFile file, Document document, String text, long stamp, int offset,
                           boolean fileOnlyContext, SourceResolver resolver) { }
    private record ConfigurationStamp(String name, GoCommand command, String directory, String target,
                                      String goArguments, String programArguments, Map<String, String> environment,
                                      GoTestSelection selection, String selectionError) {
        static ConfigurationStamp capture(GoRunConfiguration configuration) {
            return new ConfigurationStamp(configuration.getName(), configuration.getCommand(), configuration.getDirectory(),
                    configuration.getTarget(), configuration.getGoArguments(), configuration.getProgramArguments(),
                    Map.copyOf(configuration.getEnvironment()), configuration.getTestSelection(), configuration.getTestSelectionReadError());
        }
    }
    /** One captured execution attempt; only the owned copy is changed by background resolution. */
    public static final class PendingExecution {
        private final GoRunConfiguration original, prepared;
        private final Request request;
        private final ConfigurationStamp originalStamp;
        private BooleanSupplier cancelled = () -> false;
        private boolean resolved, committed;
        private PendingExecution(GoRunConfiguration original, GoRunConfiguration prepared, Request request) {
            this.original = original; this.prepared = prepared; this.request = request;
            originalStamp = ConfigurationStamp.capture(original);
        }
    }

    public static boolean hasPendingContext(GoRunConfiguration configuration) {
        return configuration.getCopyableUserData(REQUEST) != null;
    }

    /** Called on the EDT when a state captures its independent configuration copy, before background preparation. */
    public static PendingExecution capturePendingExecution(GoRunConfiguration executionCopy,
                                                           ExecutionEnvironment environment) throws ExecutionException {
        Request request = executionCopy.getCopyableUserData(REQUEST);
        if (request == null) return null; // Explicit package/named configurations retain their chosen scope.
        if (!ApplicationManager.getApplication().isDispatchThread())
            throw new ExecutionException("Capture the Go test execution context on the event dispatch thread.");
        var profile = WrappingRunConfiguration.unwrapRunProfile(environment.getRunProfile());
        if (!(profile instanceof GoRunConfiguration original) || original == executionCopy
                || original.getCopyableUserData(REQUEST) != request
                || !ConfigurationStamp.capture(original).equals(ConfigurationStamp.capture(executionCopy)))
            throw changedConfiguration();
        if (environment.isRunningCurrentFile() && request.fileOnlyContext()) {
            // Platform 252 creates Current File candidates from PsiFile, without an Editor, and
            // starts them directly without onFirstRun. Capture the actual launch caret here.
            var data = environment.getDataContext();
            var editor = data == null ? null : CommonDataKeys.EDITOR.getData(data);
            if (editor == null || editor.isDisposed() || editor.getDocument() != request.document())
                editor = FileEditorManager.getInstance(environment.getProject()).getSelectedTextEditor();
            if (editor == null || editor.isDisposed() || editor.getDocument() != request.document())
                throw new ExecutionException("The current Go test editor is unavailable. Open this file and select Run again; no package fallback was started.");
            request = new Request(request.file(), request.document(), request.document().getText(),
                    request.document().getModificationStamp(), editor.getCaretModel().getOffset(), true, request.resolver());
            original.putCopyableUserData(REQUEST, request);
            executionCopy.putCopyableUserData(REQUEST, request);
        }
        validateRequest(request);
        return new PendingExecution(original, executionCopy, request);
    }

    /** Resolves the exact captured source before checkConfiguration, package discovery or command construction. */
    public static void resolvePendingExecution(PendingExecution pending, BooleanSupplier cancelled) throws Exception {
        if (pending == null) return;
        if (ApplicationManager.getApplication().isDispatchThread() || ApplicationManager.getApplication().isReadAccessAllowed())
            throw new IllegalStateException("Go test context resolution must run in the background outside read actions.");
        pending.cancelled = cancelled;
        checkCancelled(pending);
        validateRequest(pending.request);
        if (pending.resolved) return;
        if (!pending.originalStamp.equals(ConfigurationStamp.capture(pending.prepared))) throw changedConfiguration();
        var snapshot = inspectRequest(pending.prepared, pending.request, cancelled);
        checkCancelled(pending);
        validateRequest(pending.request);
        applySelection(pending.prepared, snapshot.containing(pending.request.offset()));
        GoTestContextSelection.bind(pending.prepared, pending.request.file(), pending.request.text(), pending.request.stamp());
        GoTestContextSelection.validateBeforeRun(pending.prepared);
        checkCancelled(pending);
        pending.resolved = true;
    }

    /** Publishes resolved scope only if the original request and configuration still belong to this attempt. */
    public static void commitPendingExecution(PendingExecution pending) throws ExecutionException {
        if (pending == null) return;
        if (!ApplicationManager.getApplication().isDispatchThread())
            throw new ExecutionException("Publish the Go test selection on the event dispatch thread.");
        checkCancelled(pending);
        if (!pending.resolved) throw new ExecutionException("The captured Go test target has not been resolved; no tests were started.");
        validateRequest(pending.request);
        GoTestContextSelection.validateBeforeRun(pending.prepared);
        if (pending.committed) return;
        if (pending.original.getCopyableUserData(REQUEST) != pending.request
                || !pending.originalStamp.equals(ConfigurationStamp.capture(pending.original))) throw changedConfiguration();
        pending.original.setTestSelection(pending.prepared.getTestSelection());
        pending.original.setName(pending.prepared.getName());
        GoTestContextSelection.copyBinding(pending.prepared, pending.original);
        pending.original.putCopyableUserData(REQUEST, null);
        pending.prepared.putCopyableUserData(REQUEST, null);
        pending.committed = true;
    }

    private static void checkCancelled(PendingExecution pending) {
        if (pending.cancelled.getAsBoolean() || pending.original.getProject().isDisposed())
            throw new CancellationException("Go test context resolution was cancelled.");
    }
    private static void validateRequest(Request request) throws ExecutionException {
        boolean current = ReadAction.compute(() -> request.file().isValid()
                && request.document().getModificationStamp() == request.stamp()
                && request.document().getText().equals(request.text()));
        if (!current) throw new ExecutionException("The selected source changed during discovery. Save it and select Run again; no package fallback was started.");
    }
    private static ExecutionException changedConfiguration() {
        return new ExecutionException("The Go test configuration changed during preparation. Select Run again; the newer settings were not overwritten.");
    }
    private static GoTestSourceIndex.Snapshot inspectRequest(GoRunConfiguration configuration, Request request,
                                                            BooleanSupplier stopping) throws Exception {
        var project = configuration.getProject();
        var line = new GeneralCommandLine().withEnvironment(GoProjectSettings.getInstance(project).environment())
                .withEnvironment(configuration.getEnvironment());
        String go = GoToolResolver.resolve(GoForgeSettings.getInstance().goPath, "go", project.getBasePath(), line.getEffectiveEnvironment());
        var snapshot = request.resolver().inspect(go, line.getEffectiveEnvironment(), Path.of(request.file().getPath()), request.text(), stopping);
        if (snapshot == null || !snapshot.file().equals(Path.of(request.file().getPath()).toAbsolutePath().normalize())
                || !snapshot.text().equals(request.text())) throw new ExecutionException("Go test discovery did not match the captured source.");
        return snapshot;
    }
    @Override public @NotNull ConfigurationFactory getConfigurationFactory() {
        return ConfigurationTypeUtil.findConfigurationType(GoRunConfigurationType.class).getConfigurationFactories()[0];
    }
    @Override protected boolean setupConfigurationFromContext(@NotNull GoRunConfiguration configuration,
            @NotNull ConfigurationContext context, @NotNull Ref<PsiElement> sourceElement) {
        var location = context.getPsiLocation();
        if (location == null || location.getContainingFile() == null) return false;
        PsiFile psi = location.getContainingFile();
        var file = psi.getVirtualFile();
        if (file == null || !file.isInLocalFileSystem() || !"go".equals(file.getExtension()) || file.getParent() == null) return false;
        boolean test = file.getName().endsWith("_test.go");
        if (!test && !"main".equals(GoSourcePackage.name(psi.getText()))) return false;
        configuration.setCommand(test ? GoCommand.TEST : GoCommand.RUN);
        configuration.setDirectory(file.getParent().getPath());
        configuration.setTarget(".");
        configuration.setTestSelection(null);
        configuration.setName(configuration.getCommand().argument() + " " + file.getParent().getName());
        sourceElement.set(psi);
        if (test) {
            Request request = request(context);
            if (request == null) return false;
            configuration.putCopyableUserData(REQUEST, request);
            GoTestSourceIndex.Snapshot cached = GoTestSourceIndex.cached(Path.of(file.getPath()), request.text());
            if (cached != null) {
                var declaration = cached.containing(request.offset());
                applySelection(configuration, declaration);
                if (declaration != null) {
                    PsiElement identifier = psi.findElementAt(declaration.nameOffset());
                    if (identifier != null) sourceElement.set(identifier);
                }
            } else configuration.setName("test at caret in " + file.getName());
        }
        return true;
    }
    @Override public boolean isConfigurationFromContext(@NotNull GoRunConfiguration configuration, @NotNull ConfigurationContext context) {
        var location = context.getPsiLocation();
        if (location == null || location.getContainingFile() == null) return false;
        var file = location.getContainingFile().getVirtualFile();
        if (file == null || !file.isInLocalFileSystem() || file.getParent() == null || !"go".equals(file.getExtension())) return false;
        boolean test = file.getName().endsWith("_test.go");
        if (!test && !"main".equals(GoSourcePackage.name(location.getContainingFile().getText()))) return false;
        GoCommand command = test ? GoCommand.TEST : GoCommand.RUN;
        if (configuration.getCommand() != command || !configuration.getTarget().equals(".")
                || !configuration.getDirectory().equals(file.getParent().getPath())) return false;
        if (!test) return configuration.getTestSelection() == null;
        Request current = request(context);
        if (current == null) return false;
        var cached = GoTestSourceIndex.cached(Path.of(file.getPath()), current.text());
        if (cached != null) {
            var declaration = cached.containing(current.offset());
            return Objects.equals(configuration.getTestSelection(), declaration == null ? null : GoTestSelection.single(declaration.name()));
        }
        Request pending = configuration.getCopyableUserData(REQUEST);
        return pending != null && pending.file().equals(current.file()) && pending.offset() == current.offset()
                && pending.stamp() == current.stamp() && pending.text().equals(current.text());
    }
    @Override public void onFirstRun(@NotNull ConfigurationFromContext fromContext, @NotNull ConfigurationContext context,
                                     @NotNull Runnable performRunnable) {
        if (!(fromContext.getConfiguration() instanceof GoRunConfiguration configuration)) return;
        Request request = configuration.getCopyableUserData(REQUEST);
        if (request == null || configuration.getCommand() != GoCommand.TEST) { performRunnable.run(); return; }
        resolveFirstRun(configuration, request, performRunnable);
    }
    private Request request(ConfigurationContext context) {
        var location = context.getPsiLocation();
        if (location == null || location.getContainingFile() == null) return null;
        var file = location.getContainingFile().getVirtualFile();
        if (file == null) return null;
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null || document.getTextLength() > 8 * 1024 * 1024) return null;
        var editor = CommonDataKeys.EDITOR.getData(context.getDataContext());
        int offset = editor != null && editor.getDocument() == document ? editor.getCaretModel().getOffset() : location.getTextOffset();
        return new Request(file, document, document.getText(), document.getModificationStamp(), offset,
                editor == null && location instanceof PsiFile, resolver);
    }
    private static void applySelection(GoRunConfiguration configuration, GoTestSourceIndex.Declaration declaration) {
        configuration.setTestSelection(declaration == null ? null : GoTestSelection.single(declaration.name()));
        configuration.setName(declaration == null ? "test " + Path.of(configuration.getDirectory()).getFileName() : declaration.name());
    }
    private void resolveFirstRun(GoRunConfiguration configuration, Request request, Runnable performRunnable) {
        var project = configuration.getProject();
        var prepared = configuration.clone();
        var originalStamp = ConfigurationStamp.capture(configuration);
        new Task.Backgroundable(project, "Resolve Go test at caret", true) {
            GoTestSourceIndex.Snapshot snapshot;
            Exception failure;
            boolean cancelled;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                BooleanSupplier stopping = () -> indicator.isCanceled() || project.isDisposed();
                try {
                    snapshot = inspectRequest(prepared, request, stopping);
                    if (stopping.getAsBoolean()) throw new CancellationException();
                } catch (CancellationException error) { cancelled = true; }
                catch (Exception error) { failure = error; }
            }
            @Override public void onCancel() { cancelled = true; }
            @Override public void onSuccess() {
                if (cancelled || project.isDisposed()) return;
                if (failure != null || snapshot == null) {
                    showFailure(configuration, failure == null ? "Go test discovery did not finish." : failure.getMessage()); return;
                }
                if (configuration.getCopyableUserData(REQUEST) != request
                        || !originalStamp.equals(ConfigurationStamp.capture(configuration))) {
                    showFailure(configuration, changedConfiguration().getMessage()); return;
                }
                boolean current = ReadAction.compute(() -> request.file().isValid()
                        && request.document().getModificationStamp() == request.stamp()
                        && request.document().getText().equals(request.text()));
                if (!current) { showFailure(configuration, "The selected source changed during discovery. Save it and select Run again."); return; }
                applySelection(configuration, snapshot.containing(request.offset()));
                GoTestContextSelection.bind(configuration, request.file(), request.text(), request.stamp());
                configuration.putCopyableUserData(REQUEST, null);
                performRunnable.run();
            }
        }.queue();
    }
    private static void showFailure(GoRunConfiguration configuration, String message) {
        NotificationGroupManager.getInstance().getNotificationGroup("GoForge")
                .createNotification("Could not select Go test", message == null ? "Go test discovery failed." : message, NotificationType.ERROR)
                .notify(configuration.getProject());
    }
}

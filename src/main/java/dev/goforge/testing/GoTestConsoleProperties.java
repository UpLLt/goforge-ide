package dev.goforge.testing;

import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.testframework.AbstractTestProxy;
import com.intellij.execution.testframework.TestTreeView;
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction;
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties;
import com.intellij.execution.testframework.sm.runner.SMTestLocator;
import com.intellij.execution.testframework.sm.runner.SMTestProxy;
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter;
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener;
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ExecutionDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.util.ui.UIUtil;
import dev.goforge.debug.GoDebugSelectedTestAction;
import dev.goforge.run.GoCommand;
import dev.goforge.run.GoRunConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.event.TreeSelectionListener;

/** Per-console source identity and explicit selected/failed rerun controls. */
public final class GoTestConsoleProperties extends SMTRunnerConsoleProperties {
    private final GoTestRunRegistry registry;
    private final GoTestLocator locator;
    private GoRerunFailedTestsAction failedAction;
    private final Consumer<GoRunConfiguration> selectedLauncher;
    // Run toolbar contexts do not necessarily descend from the SM tree. Capture
    // the tree's exact singleton selection on EDT; BGT action updates read only
    // this immutable URL and the thread-safe registry, never Swing or a proxy.
    private volatile @Nullable String selectedLocationUrl;
    private volatile boolean selectionDisposed;
    public GoTestConsoleProperties(GoRunConfiguration configuration, Executor executor, GoTestRunRegistry registry) {
        this(configuration, executor, registry, GoTestConsoleProperties::launchSelected);
    }
    GoTestConsoleProperties(GoRunConfiguration configuration, Executor executor, GoTestRunRegistry registry,
                           Consumer<GoRunConfiguration> selectedLauncher) {
        super(configuration, "GoForge", executor); this.registry = registry; locator = new GoTestLocator(registry);
        this.selectedLauncher = selectedLauncher;
        setIdBasedTestTree(true);
        Disposer.register(this, registry::close);
    }
    public GoTestRunRegistry registry() { return registry; }
    @Override public @Nullable SMTestLocator getTestLocator() { return locator; }
    @Override public @Nullable AbstractRerunFailedTestsAction createRerunFailedTestsAction(ConsoleView console) {
        if (failedAction == null) { failedAction = new GoRerunFailedTestsAction(console); failedAction.init(this); }
        return failedAction;
    }
    /** The default SM hook alone does not mount a Run toolbar restart action. */
    public void mount(SMTRunnerConsoleView console, DefaultExecutionResult result, AnAction... extraActions) {
        bindSelection(console);
        var ownedRoot = console.getResultsViewer().getTestsRootNode();
        registry.project().getMessageBus().connect(console).subscribe(SMTRunnerEventsListener.TEST_STATUS,
                new SMTRunnerEventsAdapter() {
                    @Override public void onBeforeTestingFinished(@NotNull SMTestProxy.SMRootTestProxy root) {
                        // This callback runs in the SM conversion lifecycle. Identity
                        // binds it to this console, including a run with no test events.
                        if (root == ownedRoot && registry.wasLocallyTerminated()) root.setTerminated();
                    }
                });
        var action = createRerunFailedTestsAction(console);
        action.setModelProvider(console::getResultsViewer);
        var restarts = new ArrayList<AnAction>(List.of(result.getRestartActions())); restarts.add(action);
        result.setRestartActions(restarts.toArray(AnAction[]::new));
        var actions = new ArrayList<AnAction>(List.of(result.getActions()));
        actions.add(new RerunSelectedAction()); actions.add(new NavigateSourceAction());
        for (var extra : extraActions) actions.add(extra instanceof GoDebugSelectedTestAction debug
                ? debug.withSelection(this::selectedDebugConfiguration) : extra);
        result.setActions(actions.toArray(AnAction[]::new));
        if (!registry.hasSourceMapping()) console.print("Go test source mapping is unavailable for this run. Source navigation and selected/failed rerun are disabled; package execution remains available.\n", ConsoleViewContentType.SYSTEM_OUTPUT);
    }
    private void bindSelection(SMTRunnerConsoleView console) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        var tree = console.getResultsViewer().getTreeView();
        TreeSelectionListener listener = event -> captureSelection(tree);
        tree.addTreeSelectionListener(listener);
        captureSelection(tree);
        Disposer.register(console, () -> {
            selectionDisposed = true;
            selectedLocationUrl = null;
            UIUtil.invokeLaterIfNeeded(() -> tree.removeTreeSelectionListener(listener));
        });
    }
    private void captureSelection(TestTreeView tree) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        var paths = tree.getSelectionPaths();
        var proxy = !selectionDisposed && paths != null && paths.length == 1
                ? tree.getSelectedTest(paths[0]) : null;
        selectedLocationUrl = proxy == null ? null : proxy.getLocationUrl();
    }
    /** Selection belongs to this console even when the Run toolbar has no tree data keys. */
    public @Nullable GoRunConfiguration selectedConfiguration() {
        String url = selectedLocationUrl;
        return selectionDisposed || registry.project().isDisposed() || url == null ? null : rerunConfiguration(url);
    }
    /** Separate from ordinary rerun: a completed benchmark retains its own selector kind. */
    public @Nullable GoRunConfiguration selectedDebugConfiguration() {
        String url = selectedLocationUrl;
        return selectionDisposed || registry.project().isDisposed() || url == null ? null : debugConfiguration(url);
    }
    public @Nullable GoRunConfiguration selectedDebugConfiguration(@Nullable AbstractTestProxy proxy) {
        return selectionDisposed || registry.project().isDisposed() || proxy == null ? null : debugConfiguration(proxy.getLocationUrl());
    }
    private @Nullable GoRunConfiguration debugConfiguration(String url) {
        var target = registry.isTerminated() ? registry.debugTarget(url) : null;
        if (target == null) return null;
        boolean benchmark = target.declaration().kind().equals("benchmark");
        return cloneFor(target.directory().toString(), benchmark ? GoTestSelection.benchmark(target.fullName())
                : GoTestSelection.rerun(List.of(target.fullName())), (benchmark ? "benchmark " : "test ") + target.fullName());
    }
    public static @Nullable GoRunConfiguration selectedDebugConfiguration(AnActionEvent event) {
        var model = event.getData(TestTreeView.MODEL_DATA_KEY);
        if (model == null || !(model.getProperties() instanceof GoTestConsoleProperties properties)) return null;
        AbstractTestProxy[] selection = event.getData(AbstractTestProxy.DATA_KEYS);
        if (selection != null && selection.length != 1) return null;
        var proxy = event.getData(AbstractTestProxy.DATA_KEY);
        if (proxy == null && selection != null && selection.length == 1) proxy = selection[0];
        return properties.selectedDebugConfiguration(proxy);
    }
    private @Nullable GoTestRunRegistry.Target selectedNavigationTarget() {
        String url = selectedLocationUrl;
        return selectionDisposed || registry.project().isDisposed() || url == null ? null : registry.navigationTarget(url);
    }
    public static @Nullable GoRunConfiguration selectedConfiguration(AnActionEvent event) {
        var model = event.getData(TestTreeView.MODEL_DATA_KEY);
        if (model == null || !(model.getProperties() instanceof GoTestConsoleProperties properties)) return null;
        AbstractTestProxy[] selection = event.getData(AbstractTestProxy.DATA_KEYS);
        if (selection != null && selection.length != 1) return null;
        var proxy = event.getData(AbstractTestProxy.DATA_KEY);
        if (proxy == null && selection != null && selection.length == 1) proxy = selection[0];
        return properties.selectedConfiguration(proxy);
    }
    public @Nullable GoRunConfiguration selectedConfiguration(@Nullable AbstractTestProxy proxy) {
        return selectionDisposed || registry.project().isDisposed() || proxy == null ? null : rerunConfiguration(proxy.getLocationUrl());
    }
    private @Nullable GoRunConfiguration rerunConfiguration(String url) {
        var target = registry.isTerminated() ? registry.rerunTarget(url) : null;
        if (target == null) return null;
        if (!target.declaration().kind().equals("benchmark"))
            return cloneFor(target.directory().toString(), List.of(target.fullName()), "test " + target.fullName());
        // Result-tree benchmarks retain their typed selector and original benchtime.
        // Bind the observed, saved source to the existing background and launch gates.
        return ReadAction.compute(() -> {
            var file = LocalFileSystem.getInstance().findFileByNioFile(target.source().file());
            var document = registry.cachedDocument(target.source().file());
            var manager = FileDocumentManager.getInstance();
            var canonical = file == null ? null : file.getCanonicalPath();
            var documentFile = document == null ? null : manager.getFile(document);
            var documentCanonical = documentFile == null ? null : documentFile.getCanonicalPath();
            // The platform may return different VirtualFile wrappers for the same
            // local file. Bind the current cached Document and both canonical paths.
            if (file == null || !file.isValid() || canonical == null || document == null
                    || !Path.of(canonical).toAbsolutePath().normalize().equals(target.source().file())
                    || documentFile == null || !documentFile.isValid() || !documentFile.isInLocalFileSystem()
                    || documentCanonical == null || !Path.of(documentCanonical).toAbsolutePath().normalize().equals(target.source().file())
                    || manager.getCachedDocument(file) != document
                    || FileDocumentManager.getInstance().isDocumentUnsaved(document)
                    || !PsiDocumentManager.getInstance(registry.project()).isCommitted(document)
                    || !document.getText().equals(target.source().text())) return null;
            var configuration = cloneFor(target.directory().toString(), GoTestSelection.benchmark(target.fullName()),
                    "benchmark " + target.fullName());
            GoTestContextSelection.bind(configuration, file, target.source().text(), document.getModificationStamp());
            return configuration;
        });
    }
    private GoRunConfiguration cloneFor(String directory, List<String> names, String name) {
        return cloneFor(directory, GoTestSelection.rerun(names), name);
    }
    private GoRunConfiguration cloneFor(String directory, GoTestSelection selection, String name) {
        GoRunConfiguration configuration = registry.originalConfiguration();
        GoTestContextSelection.clear(configuration);
        var context = registry.commandContext();
        Path oldDirectory = context == null ? Path.of(configuration.getDirectory()).toAbsolutePath().normalize() : context.directory();
        if (!oldDirectory.equals(Path.of(directory))) {
            if (context == null) throw new IllegalStateException("The original Go working directory and GOFLAGS were not recorded. Run the package again before selecting a test from another directory.");
            var rebased = GoTestRerunArguments.rebase(context, Path.of(directory), ParametersListUtil.parse(configuration.getGoArguments()));
            configuration.setGoArguments(ParametersListUtil.join(rebased.arguments()));
            dev.goforge.tools.HostTools.putEnvironment(configuration.getEnvironment(), java.util.Map.of("GOFLAGS", rebased.goFlags()));
        }
        configuration.setCommand(GoCommand.TEST); configuration.setDirectory(directory); configuration.setTarget(".");
        configuration.setTestSelection(selection); configuration.setName(name);
        return configuration;
    }
    private @Nullable GoRunConfiguration failedConfiguration(List<? extends AbstractTestProxy> failed) {
        if (!registry.isTerminated() || registry.wasLocallyTerminated()) return null;
        var names = new LinkedHashSet<String>(); String pkg = null, directory = null;
        for (var proxy : failed) {
            var identity = registry.identity(proxy.getLocationUrl());
            if (identity == null || identity.inferred()) {
                // Aggregate suites/root repeat their children's failure state. An unowned
                // failed leaf, however, is a separate build/process/selection failure that
                // must not disappear from an apparently complete "rerun failed" action.
                if (proxy.isLeaf() && (proxy.isDefect() || proxy.isInterrupted())) return null;
                continue;
            }
            if (identity.status() != GoTestEvents.Status.FAILED && identity.status() != GoTestEvents.Status.INCOMPLETE) continue;
            var target = registry.target(proxy.getLocationUrl());
            if (target == null) return null; // do not silently omit failed tests whose package/source cannot be mapped
            if (pkg != null && !pkg.equals(target.packageName())) return null;
            pkg = target.packageName(); directory = target.directory().toString(); names.add(target.topLevelName());
        }
        if (names.isEmpty()) return null;
        return cloneFor(directory, List.copyOf(names), "failed top-level tests in " + pkg);
    }
    public final class GoRerunFailedTestsAction extends AbstractRerunFailedTestsAction {
        GoRerunFailedTestsAction(ConsoleView console) {
            super(console); getTemplatePresentation().setText("Rerun failed top-level tests in one package");
            getTemplatePresentation().setDescription("Runs each failed parent test's entire subtree. Unavailable when failures span packages or include an unmapped build, process or selection failure.");
        }
        @Override public boolean isActive(@NotNull AnActionEvent event) {
            if (event.getProject() == null || !registry.isTerminated()) return false;
            event.getPresentation().setDescription(getTemplatePresentation().getDescription());
            if (registry.wasLocallyTerminated()) {
                event.getPresentation().setDescription("This run was terminated. Select a specific observed test to rerun its subtree; failed-only rerun would omit unfinished work.");
                return false;
            }
            try {
                GoRunConfiguration selected = failedConfiguration(getFailedTests(event.getProject()));
                if (selected != null) event.getPresentation().setText("Rerun " + selected.getName());
                return selected != null;
            } catch (IllegalArgumentException | IllegalStateException error) {
                event.getPresentation().setDescription(error.getMessage());
                return false;
            }
        }
        @Override public void actionPerformed(@NotNull AnActionEvent event) {
            try { super.actionPerformed(event); }
            catch (IllegalArgumentException | IllegalStateException error) {
                Messages.showErrorDialog(event.getProject(), error.getMessage(), "Cannot Rerun Go Test");
            }
        }
        @Override public @Nullable MyRunProfile getRunProfile(@NotNull ExecutionEnvironment environment) {
            GoRunConfiguration selected = failedConfiguration(getFailedTests(environment.getProject()));
            if (selected == null) return null;
            return new MyRunProfile(selected) {
                @Override public Module @NotNull [] getModules() { return new Module[0]; }
                @Override public @Nullable RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment execution) {
                    return selected.getState(executor, execution);
                }
            };
        }
    }
    private static void launchSelected(GoRunConfiguration configuration) {
        try {
            var environment = ExecutionEnvironmentBuilder.create(configuration.getProject(), DefaultRunExecutor.getRunExecutorInstance(), configuration).build();
            environment.getRunner().execute(environment);
        } catch (Exception error) { Messages.showErrorDialog(configuration.getProject(), error.getMessage(), "Cannot Rerun Go Test"); }
    }
    private final class RerunSelectedAction extends AnAction {
        RerunSelectedAction() { super("Rerun selected Go test/benchmark", "Runs the observed test subtree or one completed top-level benchmark with its original benchmark duration", null); }
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
        @Override public void update(@NotNull AnActionEvent event) {
            event.getPresentation().setDescription(getTemplatePresentation().getDescription());
            event.getPresentation().setText(getTemplatePresentation().getText());
            try {
                var configuration = selectedConfiguration();
                event.getPresentation().setEnabled(configuration != null);
                if (configuration != null) event.getPresentation().setText(configuration.getTestSelection().kind() == GoTestSelection.Kind.BENCHMARK
                        ? "Rerun selected Go benchmark" : "Rerun selected Go test/subtree");
            }
            catch (IllegalArgumentException | IllegalStateException error) {
                event.getPresentation().setEnabled(false);
                event.getPresentation().setDescription(error.getMessage());
            }
        }
        @Override public void actionPerformed(@NotNull AnActionEvent event) {
            try {
                GoRunConfiguration configuration = selectedConfiguration();
                if (configuration == null) return;
                selectedLauncher.accept(configuration);
            } catch (IllegalArgumentException | IllegalStateException error) {
                Messages.showErrorDialog(event.getProject(), error.getMessage(), "Cannot Rerun Go Test");
            }
        }
    }
    private final class NavigateSourceAction extends AnAction {
        NavigateSourceAction() { super("Go to test declaration", "Runtime subtests navigate to their containing top-level test", null); }
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
        @Override public void update(@NotNull AnActionEvent event) {
            var target = selectedNavigationTarget(); event.getPresentation().setEnabled(target != null);
            event.getPresentation().setText(target != null && target.declaration().kind().equals("benchmark")
                    ? "Go to benchmark declaration"
                    : target != null && target.fullName().contains("/") ? "Go to containing test declaration" : "Go to test declaration");
        }
        @Override public void actionPerformed(@NotNull AnActionEvent event) {
            var target = selectedNavigationTarget(); if (target == null) return;
            String path = target.locationUrl().substring((GoTestRunRegistry.PROTOCOL + "://").length());
            var locations = locator.getLocation(GoTestRunRegistry.PROTOCOL, path, registry.project(), GlobalSearchScope.projectScope(registry.project()));
            if (locations.isEmpty()) {
                Messages.showInfoMessage(registry.project(), "The indexed source has changed or is unavailable. Run this test or benchmark again to refresh source navigation.", "Go Test Source");
                return;
            }
            var descriptor = locations.getFirst().getOpenFileDescriptor();
            if (descriptor != null) descriptor.navigate(true);
        }
    }
}

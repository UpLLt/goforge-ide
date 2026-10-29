package dev.goforge.run;

import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import dev.goforge.debug.GoDebugSelectedTestAction;
import dev.goforge.testing.GoTestConsoleProperties;
import dev.goforge.testing.GoTestContextSelection;
import dev.goforge.testing.GoTestPackageIndex;
import dev.goforge.testing.GoTestProcessHandler;
import dev.goforge.testing.GoTestRunRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.function.BooleanSupplier;

/** A test state is usable only after its background discovery succeeds. */
public final class GoTestCommandLineState extends CommandLineState {
    private final GoRunConfiguration configuration;
    private final ExecutionEnvironment environment;
    private GoRunConfigurationProducer.PendingExecution pendingContext;
    private boolean contextCaptured;
    private Map<String, GoTestRunRegistry.PackageSources> sources = Map.of();
    private GeneralCommandLine preparedCommand;
    private BooleanSupplier cancelled = () -> false;
    private volatile boolean prepared;

    public GoTestCommandLineState(GoRunConfiguration configuration, ExecutionEnvironment environment) {
        super(environment);
        this.configuration = configuration;
        this.environment = environment;
    }

    /** Capture UI context before any background work; getState itself may be called off the EDT. */
    public void captureContext() throws ExecutionException {
        pendingContext = GoRunConfigurationProducer.capturePendingExecution(configuration, environment);
        contextCaptured = true;
    }

    public void prepare(BooleanSupplier cancelled) throws Exception {
        if (ApplicationManager.getApplication().isDispatchThread())
            throw new IllegalStateException("Go test discovery must run in the background.");
        this.cancelled = cancelled;
        if (cancelled.getAsBoolean()) throw new com.intellij.openapi.progress.ProcessCanceledException();
        if (!contextCaptured && GoRunConfigurationProducer.hasPendingContext(configuration))
            throw new ExecutionException("Capture the current Go test editor before preparing its target; no package fallback was started.");
        GoRunConfigurationProducer.resolvePendingExecution(pendingContext, cancelled);
        configuration.checkConfiguration();
        GoTestContextSelection.validateBeforeRun(configuration);
        var discovered = GoTestPackageIndex.prepare(configuration, cancelled);
        GoTestContextSelection.validateBeforeRun(configuration);
        if (cancelled.getAsBoolean()) throw new com.intellij.openapi.progress.ProcessCanceledException();
        sources = Map.copyOf(discovered);
        preparedCommand = configuration.createCommandLine();
        prepared = true;
    }

    @Override public @NotNull ExecutionResult execute(@NotNull Executor executor,
            @NotNull ProgramRunner<?> runner) throws ExecutionException {
        validatePreparedSelection();
        var registry = new GoTestRunRegistry(configuration.getProject(), configuration, sources);
        var handler = startTestProcess(registry);
        com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView console = null;
        GoTestConsoleProperties properties = null;
        try {
            properties = new GoTestConsoleProperties(configuration, executor, registry);
            properties.setIdBasedTestTree(true);
            console = SMTestRunnerConnectionUtil.createConsole(properties);
            console.attachToProcess(handler);
            var result = new DefaultExecutionResult(console, handler, createActions(console, handler, executor));
            properties.mount(console, result, new GoDebugSelectedTestAction());
            return result;
        } catch (RuntimeException | Error failure) {
            handler.destroyProcess();
            if (console != null) Disposer.dispose(console);
            else if (properties != null) Disposer.dispose(properties);
            registry.close();
            throw failure;
        }
    }

    private void validatePreparedSelection() throws ExecutionException {
        if (!prepared || preparedCommand == null)
            throw new ExecutionException("Go test discovery has not completed. Run this configuration again.");
        if (cancelled.getAsBoolean() || configuration.getProject().isDisposed())
            throw new ExecutionException("Go test preparation was cancelled; no tests were started.");
        GoTestContextSelection.validateBeforeRun(configuration);
        GoRunConfigurationProducer.commitPendingExecution(pendingContext);
    }

    private GoTestProcessHandler startTestProcess(GoTestRunRegistry registry) throws ExecutionException {
        validatePreparedSelection();
        var handler = new GoTestProcessHandler(preparedCommand, registry);
        try {
            handler.setShouldDestroyProcessRecursively(true);
            if (cancelled.getAsBoolean() || configuration.getProject().isDisposed()) {
                handler.destroyProcess();
                throw new ExecutionException("Go test preparation was cancelled.");
            }
            GoTestContextSelection.started(configuration);
            ProcessTerminatedListener.attach(handler);
            return handler;
        } catch (ExecutionException | RuntimeException | Error failure) {
            handler.destroyProcess();
            throw failure;
        }
    }

    @Override protected @NotNull ProcessHandler startProcess() throws ExecutionException {
        validatePreparedSelection();
        return startTestProcess(new GoTestRunRegistry(configuration.getProject(), configuration, sources));
    }
}

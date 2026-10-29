package dev.goforge.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.configurations.WrappingRunConfiguration;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.AsyncProgramRunner;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.RunContentBuilder;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;

import java.util.concurrent.atomic.AtomicReference;

/** Completes cancellable Go test discovery before starting the actual test process. */
public final class GoTestProgramRunner extends AsyncProgramRunner<RunnerSettings> {
    public static final String ID = "GoForgeTestRunner";

    @Override public @NotNull String getRunnerId() { return ID; }

    @Override public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
        var actual = WrappingRunConfiguration.unwrapRunProfile(profile);
        return DefaultRunExecutor.EXECUTOR_ID.equals(executorId)
                && actual instanceof GoRunConfiguration config && config.getCommand() == GoCommand.TEST;
    }

    @Override protected @NotNull Promise<RunContentDescriptor> execute(
            @NotNull ExecutionEnvironment environment, @NotNull RunProfileState state) throws ExecutionException {
        if (!(state instanceof GoTestCommandLineState tests))
            throw new ExecutionException("The selected test configuration could not be prepared.");
        var project = environment.getProject();
        var result = new AsyncPromise<RunContentDescriptor>();
        var progress = new AtomicReference<ProgressIndicator>();
        var pendingProcess = new AtomicReference<com.intellij.execution.process.ProcessHandler>();
        Disposable lifetime = () -> {
            var indicator = progress.get();
            if (indicator != null) indicator.cancel();
            if (!result.isDone()) result.cancel();
            if (result.isCancelled() || project.isDisposed()) {
                var pending = pendingProcess.getAndSet(null);
                if (pending != null && !pending.isProcessTerminated()) pending.destroyProcess();
            }
        };
        Disposer.register(project, lifetime);
        result.onProcessed(ignored -> Disposer.dispose(lifetime));
        Runnable startPreparation = () -> {
          if (result.isDone() || project.isDisposed()) { result.cancel(); return; }
          try { tests.captureContext(); }
          catch (Throwable failure) { result.setError(failure); return; }
          new Task.Backgroundable(project, "Prepare Go tests", true) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                progress.set(indicator);
                if (result.isDone() || project.isDisposed()) indicator.cancel();
                indicator.checkCanceled();
                try {
                    tests.prepare(() -> indicator.isCanceled() || result.isDone() || project.isDisposed());
                } catch (java.util.concurrent.CancellationException cancelled) {
                    throw new com.intellij.openapi.progress.ProcessCanceledException();
                } catch (RuntimeException error) {
                    throw error;
                } catch (Exception error) {
                    throw new RuntimeException(error.getMessage(), error);
                }
                indicator.checkCanceled();
            }

            @Override public void onSuccess() {
                if (result.isDone() || project.isDisposed()) {
                    result.cancel();
                    return;
                }
                ExecutionResult execution = null;
                RunContentDescriptor descriptor = null;
                try {
                    execution = tests.execute(environment.getExecutor(), GoTestProgramRunner.this);
                    pendingProcess.set(execution.getProcessHandler());
                    if (result.isDone() || project.isDisposed()) {
                        execution.getProcessHandler().destroyProcess();
                        Disposer.dispose(execution.getExecutionConsole());
                        result.cancel();
                        return;
                    }
                    descriptor = new RunContentBuilder(execution, environment)
                            .showRunContent(environment.getContentToReuse());
                    result.setResult(descriptor);
                    if (result.isCancelled() || project.isDisposed()) {
                        execution.getProcessHandler().destroyProcess();
                        Disposer.dispose(descriptor);
                    }
                } catch (Throwable failure) {
                    if (execution != null && execution.getProcessHandler() != null
                            && !execution.getProcessHandler().isProcessTerminated())
                        execution.getProcessHandler().destroyProcess();
                    if (descriptor != null) Disposer.dispose(descriptor);
                    else if (execution != null) Disposer.dispose(execution.getExecutionConsole());
                    result.setError(failure);
                } finally {
                    pendingProcess.set(null);
                }
            }

            @Override public void onCancel() { result.cancel(); }
            @Override public void onThrowable(@NotNull Throwable error) { result.setError(error); }
          }.queue();
        };
        var application = com.intellij.openapi.application.ApplicationManager.getApplication();
        if (application.isDispatchThread()) startPreparation.run();
        else application.invokeLater(startPreparation, com.intellij.openapi.application.ModalityState.nonModal());
        return result;
    }
}

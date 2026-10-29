package dev.goforge.debug;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ColoredProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;

/** Allows the already-issued DAP disconnect to finish before forced process-tree termination. */
final class GoDelveProcessHandler extends ColoredProcessHandler {
    private final GoDelveProcessCleanup cleanup;
    private final java.util.concurrent.atomic.AtomicBoolean stopRequested = new java.util.concurrent.atomic.AtomicBoolean();
    private static final Runnable NO_DISCONNECT = () -> {};
    private final java.util.concurrent.atomic.AtomicReference<Runnable> disconnectRequest = new java.util.concurrent.atomic.AtomicReference<>(NO_DISCONNECT);
    static GoDelveProcessHandler start(GeneralCommandLine command, GoDelveSessionOutput output) throws ExecutionException {
        Process started = null;
        try {
            started = command.createProcess();
            return new GoDelveProcessHandler(started, command, output);
        } catch (ExecutionException | RuntimeException | Error failure) {
            if (started == null) {
                try { output.close(); } catch (java.io.IOException cleanup) { failure.addSuppressed(cleanup); }
            } else {
                // A superclass/console initialization failure must not abandon the already-started adapter.
                var cleanup = new GoDelveProcessCleanup(started, output,
                        message -> com.intellij.openapi.diagnostic.Logger.getInstance(GoDelveProcessHandler.class).warn(message), 0);
                Process process = started;
                cleanup.stop(true, process::destroyForcibly);
            }
            throw failure;
        }
    }
    private GoDelveProcessHandler(Process process, GeneralCommandLine command, GoDelveSessionOutput output) {
        super(process, command);
        setShouldDestroyProcessRecursively(true);
        setShouldKillProcessSoftly(false);
        cleanup = new GoDelveProcessCleanup(getProcess(), output,
                warning -> notifyTextAvailable(warning + "\n", ProcessOutputTypes.STDERR), 5000);
        cleanup.completion().whenComplete((unused, failure) -> disconnectRequest.set(NO_DISCONNECT));
    }
    java.util.concurrent.CompletableFuture<Void> cleanupCompletion() { return cleanup.completion(); }
    boolean canLaunch() { return !stopRequested.get() && getProcess().isAlive(); }
    void setDisconnectRequest(Runnable request) {
        java.util.Objects.requireNonNull(request);
        if (!canLaunch() || cleanup.completion().isDone()) return;
        disconnectRequest.set(request);
        if (!canLaunch() || cleanup.completion().isDone()) disconnectRequest.set(NO_DISCONNECT);
    }
    @Override protected void destroyProcessImpl() {
        stopRequested.set(true);
        // The platform's ordinary toolbar Stop destroys the handler directly;
        // no DAP client termination is guaranteed to have happened first.
        cleanup.disconnectThenStop(disconnectRequest.getAndSet(NO_DISCONNECT), this::forceTree);
    }
    @Override public void killProcess() {
        if (stopRequested.compareAndSet(false, true)) cleanup.disconnectThenForce(disconnectRequest.getAndSet(NO_DISCONNECT), this::forceTree);
        else cleanup.stop(true, this::forceTree);
    }
    private void forceTree() { super.killProcess(); }
}

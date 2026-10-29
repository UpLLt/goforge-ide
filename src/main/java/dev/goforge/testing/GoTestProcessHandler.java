package dev.goforge.testing;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;

/** Bridges go test JSON lines into the platform's id-based SM test tree. */
public final class GoTestProcessHandler extends KillableProcessHandler {
    private final GoTestEvents events;
    private final GoTestRunRegistry registry;
    private final Gson gson = new Gson();
    private final StringBuilder stdout = new StringBuilder();
    private boolean finalized;

    public GoTestProcessHandler(GeneralCommandLine command, GoTestRunRegistry registry) throws ExecutionException {
        super(requireRegistry(command, registry)); this.registry = registry;
        events = new GoTestEvents(registry::locationHint);
    }
    private static GeneralCommandLine requireRegistry(GeneralCommandLine command, GoTestRunRegistry registry) {
        // The per-console registry carries termination into the SM finish hook,
        // including empty runs. Reject unsupported use before super spawns an OS process.
        Objects.requireNonNull(registry, "A Go test run registry is required for the console lifecycle");
        return command;
    }
    public GoTestRunRegistry getRegistry() { return registry; }

    @Override public void destroyProcess() {
        recordLocalTermination();
        super.destroyProcess();
    }
    @Override public void killProcess() {
        recordLocalTermination();
        super.killProcess();
    }
    private synchronized void recordLocalTermination() {
        // Ignore a delayed Stop after the OS process has already exited. Record
        // provenance before the platform signal, without holding this monitor
        // during process-tree cleanup. This also covers force-kill escalation.
        if (!finalized && !isProcessTerminated() && getProcess().isAlive() && events.requestTermination()) {
            registry.markLocalTerminationRequested();
        }
    }

    @Override public synchronized void notifyTextAvailable(@NotNull String text, @NotNull Key outputType) {
        if (ProcessOutputTypes.STDERR.equals(outputType)) {
            emit(events.acceptText(text));
            return;
        }
        if (!ProcessOutputTypes.STDOUT.equals(outputType)) {
            super.notifyTextAvailable(text, outputType);
            return;
        }
        stdout.append(text);
        int newline;
        while ((newline = stdout.indexOf("\n")) >= 0) {
            String line = stdout.substring(0, newline);
            stdout.delete(0, newline + 1);
            consumeLine(line);
        }
    }

    @SuppressWarnings("unchecked")
    private void consumeLine(String line) {
        try {
            Object parsed = gson.fromJson(line, Object.class);
            if (parsed instanceof Map<?, ?> map && map.get("Action") instanceof String) {
                emit(events.accept((Map<String, Object>) map));
                return;
            }
        } catch (JsonParseException ignored) {
            // Toolchain diagnostics that are not test events remain visible in the test console.
        }
        emit(events.acceptText(line + "\n"));
    }

    private void emit(Iterable<GoTestEvents.Emission> emissions) {
        for (var emission : emissions) {
            registry.observe(emission);
            if (emission.teamCityMessage() != null && !emission.teamCityMessage().isBlank())
                super.notifyTextAvailable(emission.teamCityMessage() + "\n", ProcessOutputTypes.STDOUT);
        }
    }

    @Override protected synchronized void notifyProcessTerminated(int exitCode) {
        if (finalized) return;
        finalized = true;
        try {
            if (!stdout.isEmpty()) { consumeLine(stdout.toString()); stdout.setLength(0); }
            emit(events.selectedTestsNotObserved(registry.missingRequestedTests()));
            emit(events.finishProcess(exitCode));
        } finally { registry.markTerminated(); super.notifyProcessTerminated(exitCode); }
    }
}

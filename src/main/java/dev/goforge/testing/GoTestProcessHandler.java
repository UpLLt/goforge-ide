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

/** Bridges go test JSON lines into the platform's id-based SM test tree. */
public final class GoTestProcessHandler extends KillableProcessHandler {
    private final GoTestEvents events = new GoTestEvents();
    private final Gson gson = new Gson();
    private final StringBuilder stdout = new StringBuilder();

    public GoTestProcessHandler(GeneralCommandLine command) throws ExecutionException { super(command); }

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
            if (emission.teamCityMessage() != null && !emission.teamCityMessage().isBlank())
                super.notifyTextAvailable(emission.teamCityMessage() + "\n", ProcessOutputTypes.STDOUT);
        }
    }

    @Override protected synchronized void notifyProcessTerminated(int exitCode) {
        try {
            if (!stdout.isEmpty()) { consumeLine(stdout.toString()); stdout.setLength(0); }
            emit(events.finishProcess(exitCode));
        } finally { super.notifyProcessTerminated(exitCode); }
    }
}

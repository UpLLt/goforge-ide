package dev.goforge.coverage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * A bounded subprocess owned by one coverage session, with capped output capture.
 * Cleanup covers the parent and observed descendants, not an OS process group:
 * a child which immediately daemonizes before observation cannot be guaranteed.
 */
public final class GoCoverageProcess {
    private static final int OUTPUT_LIMIT = 4 * 1024 * 1024;
    public record Result(Integer exitCode, boolean cancelled, boolean timedOut, String stdout, String stderr,
                         boolean outputTruncated, boolean knownProcessesStopped) { }
    private record Owned(ProcessHandle handle, Optional<Instant> started) {
        boolean alive() { return handle.isAlive() && handle.info().startInstant().equals(started); }
    }
    private GoCoverageProcess() { }

    public static Result run(List<String> command, Path directory, Map<String, String> environment,
                             BooleanSupplier cancellation, Duration timeout) throws IOException, InterruptedException {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("A positive timeout is required.");
        if (cancellation.getAsBoolean()) return new Result(null, true, false, "", "", false, true);
        var builder = new ProcessBuilder(command).directory(directory.toFile());
        dev.goforge.tools.HostTools.putEnvironment(builder.environment(),environment);
        Process process = builder.start();
        var owned = new LinkedHashMap<Long, Owned>();
        owned.put(process.pid(), new Owned(process.toHandle(), process.info().startInstant()));
        var stdout = new Capture(process.getInputStream());
        var stderr = new Capture(process.getErrorStream());
        Thread out = new Thread(stdout, "GoForge coverage stdout"), err = new Thread(stderr, "GoForge coverage stderr");
        out.setDaemon(true); err.setDaemon(true); out.start(); err.start();
        boolean cancelled = false, timedOut = false;
        Integer exit = null;
        boolean interrupted = false;
        long started = System.nanoTime();
        try {
            while (true) {
                process.descendants().forEach(child -> owned.putIfAbsent(child.pid(), new Owned(child, child.info().startInstant())));
                if (cancellation.getAsBoolean()) { cancelled = true; break; }
                if (System.nanoTime() - started >= timeout.toNanos()) { timedOut = true; break; }
                if (process.waitFor(50, TimeUnit.MILLISECONDS)) { exit = process.exitValue(); break; }
            }
        } catch (InterruptedException error) {
            interrupted = true;
            throw error;
        } finally {
            var cleanup = new Cleanup(interrupted || Thread.interrupted());
            process.descendants().forEach(child -> owned.putIfAbsent(child.pid(), new Owned(child, child.info().startInstant())));
            try {
                stopOwned(owned.values(), cleanup);
                close(process.getOutputStream());
                cleanup.join(out, 1_000); cleanup.join(err, 1_000);
                if (out.isAlive()) close(process.getInputStream());
                if (err.isAlive()) close(process.getErrorStream());
                cleanup.join(out, 200); cleanup.join(err, 200);
            } finally {
                if (cleanup.interrupted) Thread.currentThread().interrupt();
            }
        }
        return new Result(exit, cancelled, timedOut, stdout.text(), stderr.text(), stdout.truncated || stderr.truncated,
                owned.values().stream().noneMatch(Owned::alive));
    }

    private static void stopOwned(Collection<Owned> processes, Cleanup cleanup) {
        var reverse = new ArrayList<>(processes).reversed();
        for (Owned process : reverse) if (process.alive()) process.handle().destroy();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
        while (processes.stream().anyMatch(Owned::alive) && System.nanoTime() < deadline) cleanup.pause();
        for (Owned process : reverse) if (process.alive()) process.handle().destroyForcibly();
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        while (processes.stream().anyMatch(Owned::alive) && System.nanoTime() < deadline) cleanup.pause();
    }

    private static void close(java.io.Closeable resource) {
        try { resource.close(); } catch (IOException alreadyClosed) { }
    }

    private static final class Cleanup {
        private boolean interrupted;
        Cleanup(boolean interrupted) { this.interrupted = interrupted; }
        void pause() {
            try { Thread.sleep(10); } catch (InterruptedException error) { interrupted = true; }
        }
        void join(Thread thread, long milliseconds) {
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
            while (thread.isAlive()) {
                long remaining = until - System.nanoTime();
                if (remaining <= 0) return;
                try { thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))); }
                catch (InterruptedException error) { interrupted = true; }
            }
        }
    }

    private static final class Capture implements Runnable {
        private final InputStream input;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile boolean truncated;
        Capture(InputStream input) { this.input = input; }
        @Override public void run() {
            try (input) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) >= 0;) {
                    synchronized (bytes) {
                        int keep = Math.min(count, OUTPUT_LIMIT - bytes.size());
                        bytes.write(buffer, 0, keep);
                        if (keep < count) truncated = true;
                    }
                }
            } catch (IOException closedOnTermination) { }
        }
        String text() {
            synchronized (bytes) { return bytes.toString(StandardCharsets.UTF_8); }
        }
    }
}

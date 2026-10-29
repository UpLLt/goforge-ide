package dev.goforge.debug;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Session-owned stop/cleanup, independent of the DAP socket and never blocking its caller. */
final class GoDelveProcessCleanup {
    private final Process process;
    private final GoDelveSessionOutput output;
    private final Consumer<String> warning;
    private final ScheduledExecutorService worker;
    private final Set<ProcessHandle> descendants = new LinkedHashSet<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean forced = new AtomicBoolean();
    private volatile boolean overflow;
    private final long graceMillis;
    private volatile Runnable forceTreeCallback;
    private long exitDeadline;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    GoDelveProcessCleanup(Process process, GoDelveSessionOutput output, Consumer<String> warning, long graceMillis) {
        this.process = process; this.output = output; this.warning = warning; this.graceMillis = graceMillis;
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "GoForge Delve session cleanup"); thread.setDaemon(true); return thread;
        });
        rememberChildren();
        var observation = worker.scheduleWithFixedDelay(this::rememberChildren, 0, 50, TimeUnit.MILLISECONDS);
        process.onExit().thenRun(() -> {
            observation.cancel(false);
            worker.execute(() -> { exitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); finish(); });
        });
    }
    CompletableFuture<Void> completion() { return completion; }
    synchronized void rememberChildren() {
        process.descendants().limit(257).forEach(child -> {
            if (descendants.size() < 256 || descendants.contains(child)) descendants.add(child); else overflow = true;
        });
    }
    void stop(boolean force, Runnable forceTree) {
        rememberChildren();
        forceTreeCallback = forceTree;
        if (force) {
            if (!completion.isDone()) enqueue(() -> force(forceTree), 0);
        } else if (stopping.compareAndSet(false, true) && !completion.isDone()) {
            // Scheduling, rather than waiting, keeps descendant observation active during the grace period.
            enqueue(() -> { if (process.isAlive() || childrenAlive()) force(forceTree); }, graceMillis);
        }
    }

    static void disposeOnce(AtomicBoolean disposed, Runnable dispose) {
        if (disposed.compareAndSet(false, true)) dispose.run();
    }
    static CompletableFuture<Void> disconnectAndDispose(
            java.util.function.Supplier<CompletableFuture<Void>> send, Runnable dispose) {
        CompletableFuture<Void> response;
        try { response = java.util.Objects.requireNonNull(send.get()); }
        catch (RuntimeException | Error failure) { response = CompletableFuture.failedFuture(failure); }
        // Unlike thenRunAsync, completion also closes the client after failure or cancellation.
        return response.whenCompleteAsync((unused, failure) -> dispose.run());
    }
    void disconnectThenForce(Runnable disconnect, Runnable forceTree) {
        rememberChildren(); forceTreeCallback = forceTree; stopping.set(true);
        if (completion.isDone()) return;
        // RPC serialization/writes may block before returning a future. They cannot occupy the
        // cleanup worker or delay its force deadline. One daemon request per direct Stop is enough.
        enqueue(() -> { if (process.isAlive() || childrenAlive()) force(forceTree); }, 500);
        var request = new Thread(() -> {
            if (completion.isDone()) return;
            try { disconnect.run(); }
            catch (RuntimeException failure) { warning.accept("Debug disconnect failed; forcing this session: " + failure.getMessage()); }
        }, "GoForge Delve disconnect");
        request.setDaemon(true);
        completion.whenComplete((unused, failure) -> request.interrupt());
        request.start();
    }
    private void force(Runnable forceTree) {
        if (!forced.compareAndSet(false, true)) return;
        rememberChildren();
        // Kill leaves while their parents can still reap them. Killing the adapter first can orphan
        // a stopped debuggee as a zombie on Unix. This grace is bounded and runs only on our worker.
        java.util.List<ProcessHandle> observed;
        synchronized (this) { observed = new java.util.ArrayList<>(descendants); }
        observed.sort(java.util.Comparator.comparingInt(GoDelveProcessCleanup::depth).reversed());
        long reapDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        for (var child : observed) {
            if (!child.isAlive()) continue;
            child.destroyForcibly();
            while (child.isAlive() && process.isAlive() && System.nanoTime() < reapDeadline) {
                try { Thread.sleep(10); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }
        // Preserve the platform recursive kill as the final fallback, including late descendants.
        try { forceTree.run(); }
        finally { synchronized (this) { descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly); } }
    }
    private static int depth(ProcessHandle process) {
        int depth = 0;
        while (depth < 64) { var parent = process.parent(); if (parent.isEmpty()) break; depth++; process = parent.get(); }
        return depth;
    }

    private void enqueue(Runnable task, long delay) {
        try { worker.schedule(task, delay, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException completed) { if (!completion.isDone()) throw completed; }
    }
    private void finish() {
        // A launched adapter may exit while its observed child remains. A requested Stop still owns that child.
        if (childrenAlive() && stopping.get() && forceTreeCallback != null) force(forceTreeCallback);
        if (childrenAlive() && System.nanoTime() < exitDeadline) { enqueue(this::finish, 20); return; }
        try {
            if (childrenAlive() || overflow) throw new IOException("A debug descendant is still alive or the observation limit was exceeded; retained " + output.directory());
            output.close();
            completion.complete(null);
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
            warning.accept("GoForge could not fully clean this debug session: " + failure.getMessage());
        } finally {
            try { output.release(); } catch (IOException failure) { warning.accept("Could not release debug output ownership: " + failure.getMessage()); }
            worker.shutdownNow();
        }
    }
    private synchronized boolean childrenAlive() { return descendants.stream().anyMatch(ProcessHandle::isAlive); }
}

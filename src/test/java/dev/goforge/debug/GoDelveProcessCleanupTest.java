package dev.goforge.debug;

import org.junit.Test;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class GoDelveProcessCleanupTest {
    @org.junit.Rule public final org.junit.rules.TemporaryFolder childClasspath = new org.junit.rules.TemporaryFolder();
    private Process child(String... args) throws Exception {
        // The platform test loader may expose a null CodeSource location.
        // This fixture has only JDK dependencies and no nested classes.
        Path classes = childClasspath.newFolder("child classes").toPath();
        Path bytecode = classes.resolve(GoDelveCleanupChild.class.getName().replace('.', '/') + ".class");
        Files.createDirectories(bytecode.getParent());
        try (var input = GoDelveCleanupChild.class.getResourceAsStream("GoDelveCleanupChild.class")) {
            assertNotNull("compiled child fixture", input); Files.copy(input, bytecode);
        }
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
        command.add("-cp"); command.add(classes.toString()); command.add(GoDelveCleanupChild.class.getName()); command.addAll(java.util.List.of(args));
        return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }
    @Test public void gracefulStopDoesNotKillAndCleansDefaultOutput() throws Exception {
        Path parent = Files.createTempDirectory("delve-cleanup-test-"); var output = GoDelveSessionOutput.create(parent);
        Files.writeString(output.binary(), "generated"); var process = child(); var kills = new AtomicInteger();
        assertEquals("ready", process.inputReader().readLine());
        var cleanup = new GoDelveProcessCleanup(process, output, message -> fail(message), 2000);
        long start = System.nanoTime(); cleanup.stop(false, () -> { kills.incrementAndGet(); process.destroyForcibly(); });
        assertTrue("Stop caller must not wait", System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(500));
        process.getOutputStream().write(1); process.getOutputStream().flush();
        cleanup.completion().get(7, TimeUnit.SECONDS); assertEquals(0, kills.get()); assertFalse(Files.exists(output.directory())); Files.delete(parent);
    }
    @Test public void timedOutStopKillsAndCleansExactOwnedOutput() throws Exception {
        Path parent = Files.createTempDirectory("delve-cleanup-test-"); var output = GoDelveSessionOutput.create(parent);
        Files.writeString(output.binary(), "generated"); var process = child(); var kills = new AtomicInteger();
        assertEquals("ready", process.inputReader().readLine());
        var cleanup = new GoDelveProcessCleanup(process, output, message -> fail(message), 100);
        cleanup.stop(false, () -> { kills.incrementAndGet(); process.destroyForcibly(); });
        cleanup.completion().get(7, TimeUnit.SECONDS); assertEquals(1, kills.get()); assertFalse(process.isAlive()); assertFalse(Files.exists(output.directory())); Files.delete(parent);
    }
    @Test public void forceStopEscalatesBeforeGraceAndIsIdempotent() throws Exception {
        Path parent = Files.createTempDirectory("delve-cleanup-test-"); var output = GoDelveSessionOutput.create(parent);
        var process = child(); assertEquals("ready", process.inputReader().readLine()); var kills = new AtomicInteger();
        var cleanup = new GoDelveProcessCleanup(process, output, message -> fail(message), 5000);
        Runnable force = () -> { kills.incrementAndGet(); process.destroyForcibly(); };
        cleanup.stop(false, force); cleanup.stop(true, force); cleanup.stop(true, force);
        cleanup.completion().get(3, TimeUnit.SECONDS); cleanup.stop(true, force);
        assertEquals(1, kills.get()); assertFalse(Files.exists(output.directory())); Files.delete(parent);
    }
    @Test public void observedDescendantIsTerminatedBeforeOutputCleanup() throws Exception {
        Path parent = Files.createTempDirectory("delve-cleanup-test-"); var output = GoDelveSessionOutput.create(parent);
        Files.writeString(output.binary(), "generated"); var process = child("parent");
        long childPid = Long.parseLong(process.inputReader().readLine()); var descendant = ProcessHandle.of(childPid).orElseThrow();
        var cleanup = new GoDelveProcessCleanup(process, output, message -> fail(message), 100);
        cleanup.rememberChildren(); cleanup.stop(true, () -> {});
        // The callback represents an already-exited adapter/tree-kill race; the recorded child still must be killed.
        cleanup.completion().get(7, TimeUnit.SECONDS);
        assertFalse(descendant.isAlive()); assertFalse(process.isAlive()); assertFalse(Files.exists(output.directory())); Files.delete(parent);
    }
    @Test public void requestedStopStillKillsChildAfterAdapterExited() throws Exception {
        Path parent = Files.createTempDirectory("delve-cleanup-test-"); var output = GoDelveSessionOutput.create(parent);
        Files.writeString(output.binary(), "generated"); var process = child("detach");
        long childPid = Long.parseLong(process.inputReader().readLine()); var descendant = ProcessHandle.of(childPid).orElseThrow();
        var cleanup = new GoDelveProcessCleanup(process, output, message -> fail(message), 2000);
        cleanup.rememberChildren(); cleanup.stop(false, process::destroyForcibly);
        process.getOutputStream().write(1); process.getOutputStream().flush(); process.waitFor(2, TimeUnit.SECONDS);
        cleanup.completion().get(7, TimeUnit.SECONDS);
        assertFalse(descendant.isAlive()); assertFalse(Files.exists(output.directory())); Files.delete(parent);
    }
    @Test public void blockedDisconnectCannotDelayForcedStop() throws Exception {
        Path parent = Files.createTempDirectory("delve-cleanup-test-"); var output = GoDelveSessionOutput.create(parent);
        Files.writeString(output.binary(), "generated"); var process = child(); assertEquals("ready", process.inputReader().readLine());
        var entered = new CountDownLatch(1); var returned = new CountDownLatch(1);
        var cleanup = new GoDelveProcessCleanup(process, output, message -> fail(message), 5000);
        cleanup.disconnectThenForce(() -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { returned.countDown(); }
        }, process::destroyForcibly);
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        cleanup.completion().get(3, TimeUnit.SECONDS);
        assertTrue(returned.await(1, TimeUnit.SECONDS)); assertFalse(process.isAlive());
        assertFalse(Files.exists(output.directory())); Files.delete(parent);
    }
    @Test public void ordinaryDisconnectGetsGraceBeyondTheDirectForceWindow() throws Exception {
        Path parent = Files.createTempDirectory("delve-cleanup-test-"); var output = GoDelveSessionOutput.create(parent);
        var process = child(); assertEquals("ready", process.inputReader().readLine()); var kills = new AtomicInteger();
        var cleanup = new GoDelveProcessCleanup(process, output, message -> fail(message), 2000);
        cleanup.disconnectThenStop(() -> {
            try {
                Thread.sleep(750);
                process.getOutputStream().write(1); process.getOutputStream().flush();
            } catch (Exception failure) { throw new RuntimeException(failure); }
        }, () -> { kills.incrementAndGet(); process.destroyForcibly(); });
        cleanup.completion().get(7, TimeUnit.SECONDS);
        assertEquals(0, process.exitValue()); assertEquals(0, kills.get());
        assertFalse(Files.exists(output.directory())); Files.delete(parent);
    }
    @Test public void blockedOrdinaryDisconnectCannotHoldTheGraceDeadline() throws Exception {
        Path parent = Files.createTempDirectory("delve-cleanup-test-"); var output = GoDelveSessionOutput.create(parent);
        var process = child(); assertEquals("ready", process.inputReader().readLine());
        var entered = new CountDownLatch(1); var returned = new CountDownLatch(1);
        var cleanup = new GoDelveProcessCleanup(process, output, message -> fail(message), 200);
        cleanup.disconnectThenStop(() -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { returned.countDown(); }
        }, process::destroyForcibly);
        assertTrue(entered.await(1, TimeUnit.SECONDS)); cleanup.completion().get(3, TimeUnit.SECONDS);
        assertTrue(returned.await(1, TimeUnit.SECONDS)); assertFalse(process.isAlive());
        assertFalse(Files.exists(output.directory())); Files.delete(parent);
    }
    private static Runnable once(java.util.concurrent.atomic.AtomicBoolean flag, AtomicInteger calls) {
        return () -> GoDelveProcessCleanup.disposeOnce(flag, calls::incrementAndGet);
    }
    @Test public void successfulDisconnectAndSessionEndDisposeExactlyOnce() throws Exception {
        var calls = new AtomicInteger(); Runnable dispose = once(new java.util.concurrent.atomic.AtomicBoolean(), calls);
        GoDelveProcessCleanup.disconnectAndDispose(() -> CompletableFuture.completedFuture(null), dispose).get(2, TimeUnit.SECONDS);
        dispose.run(); assertEquals(1, calls.get());
    }
    @Test public void failedDisconnectStillDisposesExactlyOnce() throws Exception {
        var calls = new AtomicInteger(); Runnable dispose = once(new java.util.concurrent.atomic.AtomicBoolean(), calls);
        var original = new java.io.IOException("closed socket");
        var done = GoDelveProcessCleanup.disconnectAndDispose(() -> CompletableFuture.failedFuture(original), dispose);
        assertSame(original, assertThrows(ExecutionException.class, () -> done.get(2, TimeUnit.SECONDS)).getCause());
        dispose.run(); assertEquals(1, calls.get());
    }
    @Test public void cancelledDisconnectStillDisposesExactlyOnce() throws Exception {
        var calls = new AtomicInteger(); Runnable dispose = once(new java.util.concurrent.atomic.AtomicBoolean(), calls);
        var response = new CompletableFuture<Void>();
        var done = GoDelveProcessCleanup.disconnectAndDispose(() -> response, dispose); response.cancel(true);
        assertThrows(ExecutionException.class, () -> done.get(2, TimeUnit.SECONDS));
        dispose.run(); assertEquals(1, calls.get());
    }
    @Test public void synchronousSendFailureStillDisposesExactlyOnce() throws Exception {
        var calls = new AtomicInteger(); Runnable dispose = once(new java.util.concurrent.atomic.AtomicBoolean(), calls);
        var original = new IllegalStateException("write failed before future");
        var done = GoDelveProcessCleanup.disconnectAndDispose(() -> { throw original; }, dispose);
        assertSame(original, assertThrows(ExecutionException.class, () -> done.get(2, TimeUnit.SECONDS)).getCause());
        dispose.run(); assertEquals(1, calls.get());
    }
    @Test public void pendingDisconnectAndExceptionalSessionEndStillDisposeOnce() throws Exception {
        var calls = new AtomicInteger(); Runnable dispose = once(new java.util.concurrent.atomic.AtomicBoolean(), calls);
        var response = new CompletableFuture<Void>(); var session = new CompletableFuture<Void>();
        var done = GoDelveProcessCleanup.disconnectAndDispose(() -> response, dispose);
        var ended = session.whenCompleteAsync((unused, failure) -> dispose.run());
        session.completeExceptionally(new java.io.IOException("retained output"));
        assertThrows(ExecutionException.class, () -> ended.get(2, TimeUnit.SECONDS));
        assertEquals(1, calls.get()); assertFalse(done.isDone());
        response.completeExceptionally(new java.io.IOException("late socket close"));
        assertThrows(ExecutionException.class, () -> done.get(2, TimeUnit.SECONDS)); assertEquals(1, calls.get());
    }
}

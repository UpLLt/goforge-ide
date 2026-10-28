package dev.goforge.coverage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class GoCoverageProcessTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void keepsRealExitCodeStdoutAndStderr() throws Exception {
        var result = GoCoverageProcess.run(command("output"), temporary.getRoot().toPath(), Map.of(), () -> false, Duration.ofSeconds(5));
        assertEquals(Integer.valueOf(7), result.exitCode());
        assertEquals("actual stdout\n", result.stdout()); assertEquals("actual stderr\n", result.stderr());
        assertFalse(result.cancelled()); assertFalse(result.timedOut()); assertTrue(result.knownProcessesStopped());
    }
    @Test public void timeoutTerminatesOwnedParentAndObservedChild() throws Exception {
        Path pids = temporary.getRoot().toPath().resolve("pids");
        var result = GoCoverageProcess.run(command("spawn", pids.toString()), temporary.getRoot().toPath(), Map.of(), () -> false, Duration.ofSeconds(1));
        assertTrue(result.timedOut()); assertTrue(result.knownProcessesStopped()); assertGone(pids);
    }
    @Test public void explicitCancellationTerminatesOwnedProcesses() throws Exception { stopped(false); }
    @Test public void threadInterruptionCannotSkipCleanupAndInterruptFlagIsRestored() throws Exception { stopped(true); }

    private void stopped(boolean interrupt) throws Exception {
        Path pids = temporary.getRoot().toPath().resolve("pids");
        var cancelled = new AtomicBoolean();
        var result = new AtomicReference<GoCoverageProcess.Result>();
        var failure = new AtomicReference<Throwable>();
        var restored = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try { result.set(GoCoverageProcess.run(commandUnchecked("spawn", pids.toString()), temporary.getRoot().toPath(), Map.of(), cancelled::get, Duration.ofSeconds(10))); }
            catch (InterruptedException expected) { restored.set(Thread.currentThread().isInterrupted()); }
            catch (Throwable error) { failure.set(error); }
        });
        worker.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(pids) && worker.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertNull(failure.get());
            assertTrue("child must actually be running before stopping", Files.exists(pids));
            if (interrupt) worker.interrupt(); else cancelled.set(true);
            worker.join(4_000);
            assertFalse("cleanup must finish within a bound", worker.isAlive());
            assertNull(failure.get());
            if (interrupt) assertTrue("interrupt status must be restored", restored.get());
            else { assertTrue(result.get().cancelled()); assertTrue(result.get().knownProcessesStopped()); }
            assertGone(pids);
        } finally {
            cancelled.set(true); worker.interrupt(); worker.join(4_000);
        }
    }
    private List<String> command(String... arguments) throws Exception {
        Path classes = temporary.getRoot().toPath().resolve("child-classes");
        Path bytecode = classes.resolve("dev/goforge/coverage/CoverageChildProcess.class");
        Files.createDirectories(bytecode.getParent());
        try (var input = CoverageChildProcess.class.getResourceAsStream("CoverageChildProcess.class")) {
            if (input == null) throw new java.io.IOException("Missing child-process fixture bytecode");
            Files.copy(input, bytecode, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
        command.add("-cp"); command.add(classes.toString());
        command.add(CoverageChildProcess.class.getName()); command.addAll(List.of(arguments)); return command;
    }
    private List<String> commandUnchecked(String... arguments) {
        try { return command(arguments); } catch (Exception error) { throw new RuntimeException(error); }
    }
    private static void assertGone(Path pids) throws Exception {
        for (String value : Files.readString(pids).split(" ")) {
            long pid = Long.parseLong(value);
            assertFalse("owned PID still alive: " + pid, ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
    }
}

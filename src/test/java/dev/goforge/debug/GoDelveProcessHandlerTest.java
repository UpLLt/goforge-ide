package dev.goforge.debug;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Key;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.nio.file.*;
import java.util.concurrent.*;

/** Actual IntelliJ handler and harmless child; full DAP/Delve/native GUI are separate acceptance. */
public final class GoDelveProcessHandlerTest extends BasePlatformTestCase {
    public void testNoClientStopIsBoundedAndRemovesOnlyOwnedOutput() throws Exception {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Path parent = Files.createTempDirectory("goforge-handler-test-");
            Path sibling = Files.writeString(parent.resolve("keep"), "unchanged");
            var output = GoDelveSessionOutput.create(parent); Files.writeString(output.binary(), "owned");
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            Path classes = Files.createDirectory(parent.resolve("child classes"));
            Path bytecode = classes.resolve(GoDelveCleanupChild.class.getName().replace('.', '/') + ".class");
            Files.createDirectories(bytecode.getParent());
            try (var input = GoDelveCleanupChild.class.getResourceAsStream("GoDelveCleanupChild.class")) {
                assertNotNull("compiled child fixture", input); Files.copy(input, bytecode);
            }
            var handler = GoDelveProcessHandler.start(new GeneralCommandLine(javaExecutable, "-cp", classes.toString(), GoDelveCleanupChild.class.getName()), output);
            var ready = new CountDownLatch(1);
            handler.addProcessListener(new ProcessAdapter() { public void onTextAvailable(ProcessEvent event, Key type) { if (event.getText().contains("ready")) ready.countDown(); } });
            try {
                handler.startNotify(); assertTrue(ready.await(5, TimeUnit.SECONDS));
                long started = System.nanoTime(); handler.destroyProcess();
                assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(500));
                handler.cleanupCompletion().get(12, TimeUnit.SECONDS);
                assertFalse(handler.getProcess().isAlive()); assertFalse(Files.exists(output.directory()));
                assertEquals("unchanged", Files.readString(sibling));
            } finally {
                if (handler.getProcess().isAlive()) { handler.killProcess(); handler.cleanupCompletion().get(12, TimeUnit.SECONDS); }
                try (var files = Files.walk(classes)) {
                    for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
                }
                Files.delete(sibling); Files.delete(parent);
            }
            return null;
        }).get(20, TimeUnit.SECONDS);
    }
    public void testAdapterStartFailureReleasesEmptyOwnedDirectory() throws Exception {
        Path parent = Files.createTempDirectory("goforge-handler-start-test-");
        var output = GoDelveSessionOutput.create(parent);
        try {
            org.junit.Assert.assertThrows(com.intellij.execution.ExecutionException.class,
                    () -> GoDelveProcessHandler.start(new GeneralCommandLine(parent.resolve("missing-adapter").toString()), output));
            assertFalse(Files.exists(output.directory()));
        } finally { Files.delete(parent); }
    }
}

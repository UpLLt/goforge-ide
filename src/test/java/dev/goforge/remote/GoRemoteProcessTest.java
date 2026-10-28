package dev.goforge.remote;

import org.junit.Test;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;

public class GoRemoteProcessTest {
    private static Path directory() { return Path.of(System.getProperty("java.io.tmpdir")); }
    private static List<String> child(String mode, String... arguments) throws Exception {
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin",executable).toString(),
                "-cp",com.intellij.util.PathUtil.getJarPathForClass(Child.class),
                Child.class.getName(),mode));
        command.addAll(Arrays.asList(arguments));return command;
    }
    /** Real native child processes on every host; no local POSIX shell is required. */
    public static final class Child {
        public static void main(String[] args) throws Exception {
            switch(args[0]) {
                case "output" -> {System.out.print("output");System.err.print("problem");System.exit(7);}
                case "input" -> System.in.transferTo(System.out);
                case "sleep" -> {System.out.println(ProcessHandle.current().pid());System.out.flush();Thread.sleep(30_000);}
                case "blocked" -> {System.out.print("ready");System.out.flush();Thread.sleep(30_000);}
                case "large" -> System.out.write(new byte[1_048_576]);
                case "argument" -> System.out.write(args[1].getBytes(StandardCharsets.UTF_8));
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }
    @Test public void preservesActualExitAndSeparateOutput() throws Exception {
        var r=GoRemoteProcess.run(child("output"),directory(),Map.of(),new byte[0],false,() -> false,Duration.ofSeconds(5),x -> {});
        assertEquals(Integer.valueOf(7),r.exitCode());assertEquals("output",r.stdout());assertEquals("problem",r.stderr());assertFalse(r.success());assertTrue(r.localProcessesStopped());
    }
    @Test public void inputIsDeliveredAsDataAndClosedForRemoteFileHelpers() throws Exception {
        String data="value; $(echo never execute)\n";
        var r=GoRemoteProcess.run(child("input"),directory(),Map.of(),data.getBytes(StandardCharsets.UTF_8),false,() -> false,Duration.ofSeconds(5),x -> {});
        assertTrue(r.success());assertEquals(data,r.stdout());
    }
    @Test public void timeoutStopsOwnedLongRunningProcess() throws Exception {
        var r=GoRemoteProcess.run(child("sleep"),directory(),Map.of(),new byte[0],false,() -> false,Duration.ofSeconds(2),x -> {});
        assertTrue(r.timedOut());assertFalse(r.success());assertTrue(r.localProcessesStopped());
        long pid=Long.parseLong(r.stdout().trim());assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
    @Test public void cancelledBeforeStartDoesNotExecuteMissingCommand() throws Exception {
        var r=GoRemoteProcess.run(List.of(directory().resolve("goforge-no-such-command").toString()),directory(),Map.of(),new byte[0],false,() -> true,Duration.ofSeconds(1),x -> {});
        assertTrue(r.cancelled());assertNull(r.exitCode());assertTrue(r.localProcessesStopped());
    }
    @Test(timeout=10000) public void cancellationDoesNotWaitForBlockedInputWriter() throws Exception {
        long start=System.nanoTime();
        var ready=new java.util.concurrent.atomic.AtomicBoolean();
        var r=GoRemoteProcess.run(child("blocked"),directory(),Map.of(),new byte[2*1024*1024],false,ready::get,Duration.ofSeconds(5),x -> ready.set(true));
        assertTrue(r.cancelled());assertTrue(r.localProcessesStopped());assertTrue(System.nanoTime()-start<5_000_000_000L);
    }
    @Test public void largeOutputIsDrainedWithoutUnboundedUiCallbacks() throws Exception {
        var visible=new java.util.concurrent.atomic.AtomicInteger();
        var r=GoRemoteProcess.run(child("large"),directory(),Map.of(),new byte[0],false,() -> false,Duration.ofSeconds(5),text -> visible.addAndGet(text.length()));
        assertTrue(r.success());assertEquals(1048576,r.stdout().length());assertTrue(visible.get()<512*1024+200);assertTrue(r.localProcessesStopped());
    }
    @Test public void nativeArgumentVectorPreservesSpacesQuotesAndShellMetacharacters() throws Exception {
        String value="C:\\workspace with spaces\\源代码\\'; $(echo bad) & %PATH% `echo bad`";
        var r=GoRemoteProcess.run(child("argument",value),directory(),Map.of(),new byte[0],false,() -> false,Duration.ofSeconds(5),x -> {});
        assertTrue(r.success());assertEquals(value,r.stdout());assertTrue(r.localProcessesStopped());
    }
}

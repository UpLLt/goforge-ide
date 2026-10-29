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
        boolean windows=System.getProperty("os.name").startsWith("Windows");
        String executable=windows?"java.exe":"java";
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin",executable).toString()));
        String classpath=com.intellij.util.PathUtil.getJarPathForClass(Child.class);
        if(windows&&mode.equals("argument")) {
            Path jnaJar=Path.of(com.intellij.util.PathUtil.getJarPathForClass(com.sun.jna.Native.class));
            // IntelliJ's Windows distribution uses lib/jna/amd64, whereas JNA's
            // Platform.ARCH reports x86-64. Follow the verified distribution layout.
            String architecture=System.getProperty("os.arch");
            if(architecture.equals("x86_64")||architecture.equals("x86-64")) architecture="amd64";
            Path nativeDirectory=jnaJar.getParent().resolve("jna").resolve(architecture);
            if(!java.nio.file.Files.isRegularFile(nativeDirectory.resolve("jnidispatch.dll")))
                throw new IllegalStateException("Missing platform JNA fixture library: "+nativeDirectory);
            command.add("-Djna.boot.library.path="+nativeDirectory);
            classpath+=java.io.File.pathSeparator+jnaJar;
        }
        command.addAll(List.of("-cp",classpath,Child.class.getName(),mode));
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
                case "argument" -> System.out.write((System.getProperty("os.name").startsWith("Windows")
                        ? windowsNativeArgument() : args[1]).getBytes(StandardCharsets.UTF_8));
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
        /** The Windows Java launcher narrows main() argv through CP_ACP. Read the
         * actual Unicode native argv to test ProcessBuilder independently of that conversion. */
        private static String windowsNativeArgument() {
            var kernel=com.sun.jna.NativeLibrary.getInstance("kernel32");
            var shell=com.sun.jna.NativeLibrary.getInstance("shell32");
            var commandLine=kernel.getFunction("GetCommandLineW",com.sun.jna.Function.ALT_CONVENTION)
                    .invokePointer(new Object[0]);
            var count=new com.sun.jna.ptr.IntByReference();
            var argv=shell.getFunction("CommandLineToArgvW",com.sun.jna.Function.ALT_CONVENTION)
                    .invokePointer(new Object[]{commandLine,count});
            if(argv==null) throw new IllegalStateException("CommandLineToArgvW failed: "+com.sun.jna.Native.getLastError());
            try {
                var pointers=argv.getPointerArray(0,count.getValue());
                for(int i=0;i<pointers.length;i++) {
                    if(Child.class.getName().equals(pointers[i].getWideString(0))) {
                        if(pointers.length!=i+3||!"argument".equals(pointers[i+1].getWideString(0)))
                            throw new IllegalStateException("Native child argument boundaries changed");
                        return pointers[i+2].getWideString(0);
                    }
                }
                throw new IllegalStateException("Missing child class in native command line");
            } finally {
                kernel.getFunction("LocalFree",com.sun.jna.Function.ALT_CONVENTION).invokePointer(new Object[]{argv});
            }
        }
    }
    @Test public void preservesActualExitAndSeparateOutput() throws Exception {
        var r=GoRemoteProcess.run(child("output"),directory(),Map.of(),new byte[0],false,() -> false,Duration.ofSeconds(5),x -> {});
        assertEquals(Integer.valueOf(7),r.exitCode());assertEquals("output",r.stdout());assertEquals("problem",r.stderr());assertFalse(r.success());assertTrue(r.localProcessesStopped());
    }
    @Test public void inputIsDeliveredAsDataAndClosedForRemoteFileHelpers() throws Exception {
        String data="源代码 value; $(echo never execute)\n";
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

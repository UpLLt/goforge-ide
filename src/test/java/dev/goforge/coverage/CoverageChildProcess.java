package dev.goforge.coverage;

import java.nio.file.Files;
import java.nio.file.Path;

/** Standalone, dependency-free child used only by process cleanup regression tests. */
public final class CoverageChildProcess {
    public static void main(String[] arguments) throws Exception {
        if (arguments[0].equals("output")) {
            System.out.print("actual stdout\n"); System.err.print("actual stderr\n"); System.exit(7);
        }
        if (arguments[0].equals("sleep")) { Thread.sleep(60_000); return; }
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), CoverageChildProcess.class.getName(), "sleep").start();
        Path marker = Path.of(arguments[1]);
        Path pending = marker.resolveSibling(marker.getFileName() + ".pending");
        Files.writeString(pending, ProcessHandle.current().pid() + " " + child.pid());
        Files.move(pending, marker);
        child.waitFor();
    }
}

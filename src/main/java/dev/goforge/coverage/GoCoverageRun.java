package dev.goforge.coverage;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One single-module measurement using saved source files and a private profile. */
public final class GoCoverageRun {
    public enum Status { PASSED, TESTS_FAILED, ERROR, CANCELLED, TIMED_OUT }
    public record FileResult(GoCoverageProfile.FileCoverage coverage, GoCoverageSourceMap.Source source,
                             boolean unchangedOnDisk, String problem) { }
    public record Result(Status status, Path module, Integer exitCode, GoCoverageProfile profile,
                         List<FileResult> files, String output, String detail) {
        public Result { files = List.copyOf(files); }
        public static Result error(String message) {
            return new Result(Status.ERROR, null, null, null, List.of(), message, message);
        }
    }
    private GoCoverageRun() { }

    public static Result execute(String go, Path context, Map<String, String> inherited,
                                 BooleanSupplier cancelled, Consumer<String> progress) {
        var environment = new HashMap<>(dev.goforge.tools.HostTools.withPrependedPath(inherited, Path.of(go).getParent()));
        environment.put("GOWORK", "off");
        environment.put("GOTOOLCHAIN", "local");
        var output = new StringBuilder("Single module; tests use saved files. go.work is not combined.\n\n");
        Path module = null, temporary = null;
        Integer exit = null;
        GoCoverageProfile profile = null;
        var files = new ArrayList<FileResult>();
        Status status = Status.ERROR;
        String detail = "Coverage did not complete.";
        long started = System.nanoTime();
        try {
            context = context.toRealPath();
            progress.accept("Locating the Go module");
            var discovery = invoke(go, List.of("env", "GOMOD"), context, environment, cancelled,
                    Duration.ofSeconds(20), output);
            exit = discovery.exitCode();
            requireSuccess(discovery, "Cannot locate the Go module");
            String gomod = discovery.stdout().trim();
            if (gomod.isEmpty() || gomod.equals("/dev/null") || gomod.equalsIgnoreCase("NUL"))
                throw new IOException("Select a directory inside a Go module containing go.mod.");
            Path moduleFile = Path.of(gomod).toRealPath();
            if (!moduleFile.getFileName().toString().equals("go.mod") || !Files.isRegularFile(moduleFile))
                throw new IOException("go env GOMOD did not return a module file.");
            module = moduleFile.getParent();

            progress.accept("Reading package source paths");
            var listing = invoke(go, List.of("list", "-mod=readonly", "-json", "./..."), module, environment,
                    cancelled, Duration.ofMinutes(2), output);
            exit = listing.exitCode();
            requireSuccess(listing, "Cannot enumerate module packages");
            if (listing.outputTruncated()) throw new IOException("go list output exceeded the capture limit; source mapping is incomplete.");
            checkCancelled(cancelled);
            var sources = GoCoverageSourceMap.parse(new StringReader(listing.stdout()), module);

            temporary = Files.createTempDirectory("goforge-coverage-");
            Path profileFile = temporary.resolve("coverage.out");
            long remaining = Duration.ofMinutes(10).toNanos() - (System.nanoTime() - started);
            if (remaining <= 0) throw new Stopped(Status.TIMED_OUT, "Coverage exceeded its 10-minute limit.");
            progress.accept("Running Go tests with coverage");
            var testing = invoke(go, List.of("test", "-mod=readonly", "-count=1", "-covermode=atomic", "-coverpkg=./...",
                            "-coverprofile=" + profileFile, "-timeout=5m", "./..."), module, environment, cancelled,
                    Duration.ofNanos(remaining), output);
            exit = testing.exitCode();
            status = exit != null && exit == 0 ? Status.PASSED : Status.TESTS_FAILED;
            detail = status == Status.PASSED ? "Tests passed" : "Tests failed; coverage may be partial";
            if (!Files.isRegularFile(profileFile)) throw new IOException("Go did not produce a coverage profile. See the test output.");
            if (Files.size(profileFile) > 20 * 1024 * 1024) throw new IOException("Coverage profile exceeds the 20 MiB limit.");
            checkCancelled(cancelled);
            try (var reader = Files.newBufferedReader(profileFile)) { profile = GoCoverageProfile.parse(reader); }
            for (var file : profile.files()) {
                checkCancelled(cancelled);
                var source = sources.resolve(file.name());
                boolean same = false;
                String problem = source == null ? "Not mapped by go list" : "";
                if (source != null) {
                    try {
                        same = source.matches(Files.readString(source.path()));
                        if (!same) problem = "Source changed during the run";
                    } catch (IOException error) { problem = "Source is no longer readable"; }
                }
                files.add(new FileResult(file, source, same, problem));
            }
            checkCancelled(cancelled);
            if (profile.totals().statements() == 0) detail += "; no instrumented statements";
        } catch (Stopped stopped) {
            status = stopped.status;
            detail = stopped.getMessage();
            profile = null; files.clear();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            status = Status.CANCELLED;
            detail = "Coverage cancelled";
        } catch (Exception error) {
            // Preserve an actual test failure even if its partial profile is absent or invalid.
            if (status != Status.TESTS_FAILED) status = Status.ERROR;
            detail = error.getMessage() == null ? error.toString() : error.getMessage();
            output.append("\n").append(detail).append('\n');
        } finally {
            if (temporary != null) {
                try (var paths = Files.walk(temporary)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                } catch (IOException cleanup) {
                    output.append("\nCould not remove temporary coverage output at ").append(temporary)
                            .append(": ").append(cleanup.getMessage()).append('\n');
                }
            }
        }
        return new Result(status, module, exit, profile, files, output.toString(), detail);
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws Stopped {
        if (cancelled.getAsBoolean()) throw new Stopped(Status.CANCELLED, "Coverage cancelled");
    }

    private static GoCoverageProcess.Result invoke(String go, List<String> arguments, Path directory,
                                                    Map<String, String> environment, BooleanSupplier cancelled,
                                                    Duration timeout, StringBuilder log) throws Exception {
        var command = new ArrayList<String>(); command.add(go); command.addAll(arguments);
        log.append("$ ").append(String.join(" ", command)).append('\n');
        var result = GoCoverageProcess.run(command, directory, environment, cancelled, timeout);
        log.append(result.stdout());
        if (!result.stderr().isEmpty()) log.append("\n[stderr]\n").append(result.stderr());
        log.append("\n[exit ").append(result.exitCode() == null ? "not completed" : result.exitCode()).append("]\n\n");
        if (result.outputTruncated()) log.append("Output was truncated after 4 MiB per stream.\n");
        if (!result.knownProcessesStopped()) throw new IOException("An owned coverage process did not terminate; see process output.");
        if (result.cancelled()) throw new Stopped(Status.CANCELLED, "Coverage cancelled");
        if (result.timedOut()) throw new Stopped(Status.TIMED_OUT, "Coverage command timed out: go " + arguments.get(0));
        return result;
    }

    private static void requireSuccess(GoCoverageProcess.Result result, String message) throws IOException {
        if (result.exitCode() == null || result.exitCode() != 0) throw new IOException(message + "; see command output.");
    }

    private static final class Stopped extends Exception {
        final Status status;
        Stopped(Status status, String message) { super(message); this.status = status; }
    }
}

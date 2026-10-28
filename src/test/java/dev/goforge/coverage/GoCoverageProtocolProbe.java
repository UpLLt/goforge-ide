package dev.goforge.coverage;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Explicit real local Go-tool probe, separate from product JUnit/UI acceptance. */
public final class GoCoverageProtocolProbe {
    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        Path temporary = Files.createTempDirectory("goforge-coverage-probe-");
        var report = new LinkedHashMap<String, Object>();
        report.put("started_at", Instant.now().toString());
        report.put("scope", "real Go coverage backend on private fixtures; not native IDE UI or complete parity acceptance");
        report.put("passed", false);
        try {
            Path module = Files.createDirectory(temporary.resolve("module"));
            Files.writeString(module.resolve("go.mod"), "module example.test/coverageprobe\n\ngo 1.25\n");
            Files.writeString(module.resolve("calc.go"), """
                    package calc

                    func Sign(n int) int {
                        if n > 0 {
                            return 1
                        }
                        return 0
                    }
                    func Untested() int { return 42 }
                    """);
            Path test = module.resolve("calc_test.go");
            Files.writeString(test, "package calc\nimport \"testing\"\nfunc TestSign(t *testing.T) { if Sign(1) != 1 { t.Fatal(\"wrong sign\") } }\n");
            String go = root.resolve(".tools/bin/go").toString();
            var success = GoCoverageRun.execute(go, module, System.getenv(), () -> false, ignored -> { });
            require(success.status() == GoCoverageRun.Status.PASSED, success.output());
            require(success.profile() != null && success.profile().totals().covered() == 2
                    && success.profile().totals().statements() == 4, "Expected two covered statements out of four");
            require(success.output().contains("50.0%"), "Go's own reported percentage disagrees");
            require(success.files().size() == 1 && success.files().getFirst().source() != null
                    && success.files().getFirst().unchangedOnDisk(), "Source was not mapped accurately");
            requirePrivateOutputDeleted(success);
            report.put("success", describe(success));

            Files.writeString(test, "package calc\nimport \"testing\"\nfunc TestSign(t *testing.T) { t.Fatal(\"intentional fixture failure\") }\n");
            var failure = GoCoverageRun.execute(go, module, System.getenv(), () -> false, ignored -> { });
            require(failure.status() == GoCoverageRun.Status.TESTS_FAILED && failure.exitCode() != 0, "Test failure was not retained");
            require(failure.output().contains("intentional fixture failure"), "Actual failure output is missing");
            requirePrivateOutputDeleted(failure);
            report.put("test_failure", describe(failure));

            Files.writeString(module.resolve("calc.go"), "package calc\nfunc broken( {\n");
            var broken = GoCoverageRun.execute(go, module, System.getenv(), () -> false, ignored -> { });
            report.put("build_failure", describe(broken));
            require(broken.status() != GoCoverageRun.Status.PASSED && broken.exitCode() != null && broken.exitCode() != 0
                    && broken.output().contains("[build failed]") && broken.output().contains("expected ')'"),
                    "Actual cover compiler failure was not retained: " + broken.output());

            Path noModule = Files.createDirectory(temporary.resolve("no-module"));
            var absent = GoCoverageRun.execute(go, noModule, System.getenv(), () -> false, ignored -> { });
            require(absent.status() == GoCoverageRun.Status.ERROR && absent.profile() == null, "No-module case must fail without fabricated coverage");
            report.put("no_module", describe(absent));
            report.put("passed", true);
        } catch (Throwable failure) {
            report.put("failure", failure.toString()); throw failure;
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            report.put("private_fixtures_deleted", true);
            report.put("completed_at", Instant.now().toString());
            Path output = root.resolve(".artifacts/coverage-protocol.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
            System.out.println(output);
        }
    }

    private static Map<String, Object> describe(GoCoverageRun.Result result) {
        var value = new LinkedHashMap<String, Object>();
        value.put("status", result.status().name()); value.put("exit_code", result.exitCode());
        value.put("detail", result.detail()); value.put("output", result.output());
        if (result.profile() != null) {
            value.put("covered_statements", result.profile().totals().covered());
            value.put("total_statements", result.profile().totals().statements());
            value.put("coverage", result.profile().totals().display());
            value.put("mode", result.profile().mode());
        }
        value.put("files", result.files().stream().map(file -> Map.of("profile_name", file.coverage().name(),
                "resolved", file.source() != null, "unchanged_on_disk", file.unchangedOnDisk(), "problem", file.problem())).toList());
        return value;
    }
    private static void requirePrivateOutputDeleted(GoCoverageRun.Result result) {
        var match = Pattern.compile("-coverprofile=(.+) -timeout=5m").matcher(result.output());
        require(match.find(), "Coverage command has no private profile argument");
        Path profile = Path.of(match.group(1));
        require(!Files.exists(profile) && !Files.exists(profile.getParent()), "Private coverage output leaked after completion");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

package dev.goforge.testing;

import com.google.gson.Gson;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.goforge.testing.GoTestEvents.Kind.*;
import static dev.goforge.testing.GoTestEvents.Status.*;
import static org.junit.Assert.*;

/** Replays unmodified JSON captured from the bundled Go 1.26.4, not synthetic benchmark results. */
public class GoTestBenchmarkReplayTest {
    private static List<GoTestEvents.Emission> replay(String name, int exitCode) throws Exception {
        GoTestEvents converter = new GoTestEvents();
        List<GoTestEvents.Emission> emissions = new ArrayList<>();
        String path = "/dev/goforge/testing/benchmarks/" + name + ".jsonl";
        try (var stream = GoTestBenchmarkReplayTest.class.getResourceAsStream(path)) {
            assertNotNull(path, stream);
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    @SuppressWarnings("unchecked") Map<String, Object> event = new Gson().fromJson(line, Map.class);
                    emissions.addAll(converter.accept(event));
                }
            }
        }
        emissions.addAll(converter.finishProcess(exitCode));
        return emissions;
    }

    private static void successfulBenchmarks(List<GoTestEvents.Emission> out, int benchmarks) {
        assertEquals(benchmarks, out.stream().filter(e -> e.kind() == TEST_FINISHED
                && e.status() == BENCHMARK_COMPLETED).count());
        assertEquals(1, out.stream().filter(e -> e.kind() == SUITE_FINISHED && e.status() == PASSED).count());
        assertFalse(out.toString(), out.stream().anyMatch(e -> e.kind() == TEST_FAILED
                || e.kind() == INFRASTRUCTURE_FAILURE || e.kind() == PROTOCOL_WARNING));
        assertTrue(out.stream().filter(e -> e.kind() == TEST_FINISHED)
                .allMatch(e -> e.text().contains("package pass")));
    }

    @Test public void actualProfilingFixtureAndQuietBenchmarkCompleteOnObservedPackagePass() throws Exception {
        successfulBenchmarks(replay("ui-fixture-benchmark", 0), 1);
        successfulBenchmarks(replay("plain", 0), 1);
    }

    @Test public void loggedBenchmarkWithoutBenchActionRetainsItsOutput() throws Exception {
        var out = replay("logged", 0);
        successfulBenchmarks(out, 1);
        assertTrue(out.stream().anyMatch(e -> e.kind() == OUTPUT_ATTACHMENT && e.text().contains("benchmark log")));
    }

    @Test public void nestedAndFilteredParentsNeedNoFabricatedTimingResult() throws Exception {
        var nested = replay("nested", 0);
        successfulBenchmarks(nested, 3);
        assertEquals(List.of("BenchmarkNested/child/leaf", "BenchmarkNested/child", "BenchmarkNested"),
                nested.stream().filter(e -> e.kind() == TEST_FINISHED).map(GoTestEvents.Emission::testName).toList());
        successfulBenchmarks(replay("empty-parent", 0), 1);
    }

    @Test public void repeatedCpuMeasurementsRemainOneBenchmarkWithAllOutput() throws Exception {
        var out = replay("repeat-classic", 0);
        successfulBenchmarks(out, 1);
        var text = out.stream().filter(e -> e.kind() == OUTPUT_ATTACHMENT || e.kind() == PACKAGE_OUTPUT).map(GoTestEvents.Emission::text)
                .reduce("", String::concat);
        assertEquals(4, text.lines().filter(line -> line.contains("ns/op")).count());
        assertEquals(1, out.stream().filter(e -> e.kind() == TEST_STARTED).count());
    }

    @Test public void actualBenchmarkFailureAndSkipKeepTheirExplicitResults() throws Exception {
        var failure = replay("fail", 1);
        assertEquals(1, failure.stream().filter(e -> e.kind() == TEST_FAILED && e.status() == FAILED).count());
        assertFalse(failure.stream().anyMatch(e -> e.status() == BENCHMARK_COMPLETED));
        assertTrue(failure.stream().anyMatch(e -> e.kind() == SUITE_FINISHED && e.status() == FAILED));
        var skipped = replay("skip", 0);
        assertEquals(1, skipped.stream().filter(e -> e.kind() == TEST_SKIPPED && e.status() == SKIPPED).count());
        assertFalse(skipped.stream().anyMatch(e -> e.status() == BENCHMARK_COMPLETED));
    }

    @Test public void packageFailureAndExitZeroWithoutPackageResultCannotCompleteBenchmark() {
        for (boolean packageFailure : new boolean[]{false, true}) {
            GoTestEvents converter = new GoTestEvents();
            converter.accept(event("start", ""));
            converter.accept(event("run", "BenchmarkInterrupted"));
            var out = packageFailure ? converter.accept(event("fail", "")) : converter.finishProcess(0);
            assertFalse(out.stream().anyMatch(e -> e.status() == BENCHMARK_COMPLETED));
            assertTrue(out.stream().anyMatch(e -> e.kind() == TEST_FAILED && e.status() == INCOMPLETE));
        }
    }

    @Test public void ordinaryTestsInvalidBenchmarkNamesAndOutputOnlyNeverUseBenchmarkException() {
        for (String name : List.of("TestOrdinary", "TestParent/BenchmarkChild", "Benchmarklower", "Benchmarké")) {
            GoTestEvents converter = new GoTestEvents();
            converter.accept(event("start", ""));
            converter.accept(event("run", name));
            var out = converter.accept(event("pass", ""));
            assertTrue(name, out.stream().anyMatch(e -> e.kind() == TEST_FAILED && e.status() == INCOMPLETE));
            assertFalse(out.stream().anyMatch(e -> e.status() == BENCHMARK_COMPLETED));
        }
        GoTestEvents converter = new GoTestEvents();
        converter.accept(event("start", ""));
        converter.accept(event("output", "BenchmarkOutputOnly"));
        assertTrue(converter.accept(event("pass", "")).stream()
                .anyMatch(e -> e.kind() == TEST_FAILED && e.status() == INCOMPLETE));
        // Go permits the bare prefix and letters outside the Ll category.
        for (String name : List.of("Benchmark", "Benchmarkª", "Benchmark/child")) {
            GoTestEvents valid = new GoTestEvents();
            valid.accept(event("start", ""));
            valid.accept(event("run", name));
            successfulBenchmarks(valid.accept(event("pass", "")), 1);
        }
    }

    @Test public void benchmarkSuccessDoesNotHideMissingOrdinaryTestTerminalInSamePackage() {
        GoTestEvents converter = new GoTestEvents();
        converter.accept(event("start", ""));
        converter.accept(event("run", "BenchmarkComplete"));
        converter.accept(event("run", "TestMissingTerminal"));
        var out = converter.accept(event("pass", ""));
        assertTrue(out.stream().anyMatch(e -> e.testName().equals("BenchmarkComplete") && e.status() == BENCHMARK_COMPLETED));
        assertTrue(out.stream().anyMatch(e -> e.testName().equals("TestMissingTerminal") && e.kind() == TEST_FAILED
                && e.status() == INCOMPLETE));
        assertTrue(out.stream().anyMatch(e -> e.kind() == SUITE_FINISHED && e.status() == INCOMPLETE));
    }

    private static Map<String, Object> event(String action, String name) {
        return Map.of("Action", action, "Package", "example.com/boundary", "Test", name);
    }
}

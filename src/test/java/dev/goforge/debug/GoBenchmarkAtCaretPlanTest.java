package dev.goforge.debug;

import dev.goforge.testing.GoTestRunRegistry;
import dev.goforge.testing.GoTestSelection;
import dev.goforge.testing.GoTestSourceIndex;
import org.junit.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class GoBenchmarkAtCaretPlanTest {
    private static final Path FILE = Path.of("fixture", "benchmark_test.go").toAbsolutePath();
    private static final String TEXT = "package p\nimport \"testing\"\nfunc Benchmark世界(b *testing.B) { println(42) }\n";
    private static GoTestSourceIndex.Snapshot snapshot(String kind) {
        return new GoTestSourceIndex.Snapshot(FILE, "not-used", TEXT, List.of(new GoTestSourceIndex.Declaration(
                "Benchmark世界", kind, TEXT.indexOf("func"), TEXT.length(), TEXT.indexOf("Benchmark"))));
    }
    @Test public void caretInsideTopLevelBenchmarkSelectsExactUnicodeName() {
        assertEquals(GoTestSelection.benchmark("Benchmark世界"), GoBenchmarkAtCaretPlan.select(snapshot("benchmark"), FILE, TEXT, TEXT.indexOf("42")));
    }
    @Test public void missingUnsupportedAndMismatchedSourcesNeverFallBack() {
        assertThrows(IllegalArgumentException.class, () -> GoBenchmarkAtCaretPlan.select(snapshot("benchmark"), FILE, TEXT, 0));
        assertThrows(IllegalArgumentException.class, () -> GoBenchmarkAtCaretPlan.select(snapshot("unsupported-benchmark"), FILE, TEXT, TEXT.indexOf("42")));
        assertThrows(IllegalArgumentException.class, () -> GoBenchmarkAtCaretPlan.select(snapshot("benchmark"), FILE.resolveSibling("other_test.go"), TEXT, TEXT.indexOf("42")));
        assertThrows(IllegalArgumentException.class, () -> GoBenchmarkAtCaretPlan.select(snapshot("benchmark"), FILE, TEXT + "\n", TEXT.indexOf("42")));
    }
    @Test public void exactActiveSourceIsRequiredEvenWhenSameNameExistsElsewhere() {
        var selected = snapshot("benchmark"); var selection = GoTestSelection.benchmark("Benchmark世界");
        GoBenchmarkAtCaretPlan.requireActiveSource(Map.of("p", new GoTestRunRegistry.PackageSources(FILE.getParent(), List.of(selected))), selected, selection);
        var other = new GoTestSourceIndex.Snapshot(FILE.resolveSibling("other_test.go"), "", TEXT, selected.declarations());
        assertThrows(IllegalArgumentException.class, () -> GoBenchmarkAtCaretPlan.requireActiveSource(Map.of("p", new GoTestRunRegistry.PackageSources(FILE.getParent(), List.of(other))), selected, selection));
        assertThrows(IllegalArgumentException.class, () -> GoBenchmarkAtCaretPlan.requireActiveSource(Map.of(), selected, selection));
    }
    @Test public void planRunsOneIterationWithExactBenchAndEmptyOrdinaryTestSelector() {
        var plan = GoBenchmarkAtCaretPlan.launch("C:\\开发 space", GoTestSelection.benchmark("Benchmark世界"), List.of("-tags=selected", "-benchmem"));
        assertEquals(List.of("-test.benchmem", "-test.run=^$", "-test.bench=^Benchmark世界$", "-test.count=1", "-test.benchtime=1x"), plan.get("args"));
        assertEquals(List.of("-tags=selected"), plan.get("buildFlags")); assertEquals("test", plan.get("mode"));
        assertEquals("C:\\开发 space", plan.get("program"));
    }
    @Test public void explicitOneIterationAndOneCpuArePreserved() {
        var plan = GoBenchmarkAtCaretPlan.launch("/p", GoTestSelection.benchmark("BenchmarkOne"), List.of("-test.benchtime", "1x", "-cpu=2"));
        assertEquals(List.of("-test.benchtime=1x", "-test.cpu=2", "-test.run=^$", "-test.bench=^BenchmarkOne$", "-test.count=1"), plan.get("args"));
    }
    @Test public void repeatedTimingOrCpuSettingsAndSelectorOverridesAreRefused() {
        for (String flag : List.of("-benchtime=2x", "-benchtime=1s", "-cpu=1,2", "-cpu=0", "-cpu=-1", "-bench=.", "-run=.", "-count=2", "-fuzz=FuzzOne"))
            assertThrows(flag, IllegalArgumentException.class, () -> GoBenchmarkAtCaretPlan.launch("/p", GoTestSelection.benchmark("BenchmarkOne"), List.of(flag)));
    }
    @Test public void ordinaryTestsAndSubbenchmarksAreNotAccepted() {
        assertThrows(IllegalArgumentException.class, () -> GoBenchmarkAtCaretPlan.launch("/p", GoTestSelection.rerun(List.of("TestOne")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> GoTestSelection.benchmark("BenchmarkOne/child"));
    }
}

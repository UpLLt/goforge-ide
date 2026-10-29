package dev.goforge.debug;

import dev.goforge.testing.GoTestSelection;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class GoSelectedTestDebugPlanTest {
    @Test public void separatesBuildAndTestArgumentsWithoutExpandingSelectedSubtree() {
        var launch = GoSelectedTestDebugPlan.launch("/work/中文 project", new GoTestSelection(List.of("TestCases/a+b/[x]")),
                List.of("-race", "-tags", "integration,slow", "-ldflags=-X example.com/pkg.Name=hello world",
                        "-short", "-timeout=90s", "-test.parallel", "2"));
        assertEquals("test", launch.get("mode"));
        assertEquals("/work/中文 project", launch.get("program"));
        assertEquals(List.of("-race", "-tags=integration,slow", "-ldflags=-X example.com/pkg.Name=hello world"), launch.get("buildFlags"));
        assertEquals(List.of("-test.short", "-test.timeout=90s", "-test.parallel=2",
                "-test.run=^TestCases$/^a\\+b$/^\\[x\\]$", "-test.count=1"), launch.get("args"));
    }

    @Test public void preservesWindowsPathAndEmptySubtestSegmentAsLiteralData() {
        var launch = GoSelectedTestDebugPlan.launch("C:\\Users\\开发\\my project",
                new GoTestSelection(List.of("TestCases//dup#01")), List.of());
        assertEquals("C:\\Users\\开发\\my project", launch.get("cwd"));
        assertEquals(List.of("-test.run=^TestCases$/^$/^dup#01$", "-test.count=1"), launch.get("args"));
    }

    @Test public void refusesFlagsThatWouldDropBehaviorOrOverrideTheSelection() {
        for (String flag : List.of("-cover", "-exec=custom", "-run=TestOther", "-test.run=TestOther",
                "-count=3", "-args", "./...", "--", "-unknown=value")) {
            try {
                GoSelectedTestDebugPlan.launch("/project", new GoTestSelection(List.of("TestOne")), List.of(flag));
                fail("Accepted unsupported flag " + flag);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("cannot preserve"));
            }
        }
    }

    @Test public void rejectsMissingFlagValue() {
        assertThrows(IllegalArgumentException.class, () -> GoSelectedTestDebugPlan.launch("/project",
                new GoTestSelection(List.of("TestOne")), List.of("-timeout")));
    }

    @Test public void retainsEffectiveGoFlagsBeforeExplicitOverrides() {
        var launch = GoSelectedTestDebugPlan.launch("/project", new GoTestSelection(List.of("TestOne")),
                List.of("-short", "-timeout=30s", "-tags=from_goenv", "-tags=from_project",
                        "-timeout=90s", "-tags=from_arguments"));
        assertEquals(List.of("-tags=from_goenv", "-tags=from_project", "-tags=from_arguments"), launch.get("buildFlags"));
        assertEquals(List.of("-test.short", "-test.timeout=30s", "-test.timeout=90s",
                "-test.run=^TestOne$", "-test.count=1"), launch.get("args"));
    }

    @Test public void buildVcsWithoutValueDoesNotConsumeFollowingRuntimeFlag() {
        var launch = GoSelectedTestDebugPlan.launch("/project", new GoTestSelection(List.of("TestOne")),
                List.of("-buildvcs", "-short"));
        assertEquals(List.of("-buildvcs"), launch.get("buildFlags"));
        assertTrue(((List<?>) launch.get("args")).contains("-test.short"));
    }

    @Test public void benchmarkDebugUsesBothLiteralSelectorAndAnEmptyOrdinaryTestSelector() {
        var launch = GoSelectedTestDebugPlan.launch("C:\\Users\\开发\\bench project", GoTestSelection.benchmark("Benchmark世界"),
                List.of("-tags=benchmark_fixture", "-benchtime", "1x", "-benchmem", "-test.timeout=30s"));
        assertEquals(List.of("-tags=benchmark_fixture"), launch.get("buildFlags"));
        assertEquals(List.of("-test.benchtime=1x", "-test.benchmem", "-test.timeout=30s", "-test.run=^$", "-test.bench=^Benchmark世界$", "-test.count=1"), launch.get("args"));
        assertEquals("C:\\Users\\开发\\bench project", launch.get("cwd"));
    }
    @Test public void benchmarkTimingIsPreservedWhenExplicitAndNotInventedWhenAbsent() {
        var launch=GoSelectedTestDebugPlan.launch("/project", GoTestSelection.benchmark("BenchmarkOne"), List.of());
        assertEquals(List.of("-test.run=^$", "-test.bench=^BenchmarkOne$", "-test.count=1"), launch.get("args"));
        var explicit=GoSelectedTestDebugPlan.launch("/project", GoTestSelection.benchmark("BenchmarkOne"), List.of("-test.benchtime=2x", "-benchmem=false"));
        assertEquals(List.of("-test.benchtime=2x", "-test.benchmem=false", "-test.run=^$", "-test.bench=^BenchmarkOne$", "-test.count=1"),explicit.get("args"));
    }
    @Test public void benchmarkDebugRejectsSelectorOverridesAndOrdinaryTestsDoNotAcquireBenchmarkFlags() {
        for(String argument:List.of("-run=TestOther", "-test.run=TestOther", "-bench=.", "-test.bench=.", "-count=4", "-args", "-exec=wrapper"))
            assertThrows(argument,IllegalArgumentException.class,()->GoSelectedTestDebugPlan.launch("/project",GoTestSelection.benchmark("BenchmarkOne"),List.of(argument)));
        for(String argument:List.of("-benchtime=1x", "-test.benchmem"))
            assertThrows(argument,IllegalArgumentException.class,()->GoSelectedTestDebugPlan.launch("/project",GoTestSelection.single("TestOne"),List.of(argument)));
        assertThrows(IllegalArgumentException.class,()->GoSelectedTestDebugPlan.launch("/project",GoTestSelection.benchmark("BenchmarkOne"),List.of("-benchtime")));
    }

}

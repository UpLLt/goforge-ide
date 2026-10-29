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

    @Test public void selectedBenchmarkCannotBecomeAnOrdinaryTestDebugFilter() {
        var error=assertThrows(IllegalArgumentException.class,()->GoSelectedTestDebugPlan.launch("/project",GoTestSelection.benchmark("BenchmarkOne"),List.of()));
        assertTrue(error.getMessage().contains("use Run"));
    }

}

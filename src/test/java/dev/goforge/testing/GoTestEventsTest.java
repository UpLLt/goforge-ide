package dev.goforge.testing;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.goforge.testing.GoTestEvents.Kind.*;
import static dev.goforge.testing.GoTestEvents.Status.*;
import static org.junit.Assert.*;

public class GoTestEventsTest {
    private static Map<String, Object> event(String action, String pkg, String test, Object... fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Action", action);
        if (pkg != null) m.put("Package", pkg);
        if (test != null) m.put("Test", test);
        for (int i = 0; i < fields.length; i += 2) m.put((String) fields[i], fields[i + 1]);
        return m;
    }
    private static GoTestEvents.Emission one(List<GoTestEvents.Emission> out, GoTestEvents.Kind kind) {
        List<GoTestEvents.Emission> matches = out.stream().filter(e -> e.kind() == kind).toList();
        assertEquals("Expected one " + kind + ", got " + out, 1, matches.size());
        return matches.get(0);
    }
    private static long count(List<GoTestEvents.Emission> out, GoTestEvents.Kind kind) {
        return out.stream().filter(e -> e.kind() == kind).count();
    }

    @Test public void nestedParallelTestsKeepTheirIdentityAndExactOutput() {
        GoTestEvents c = new GoTestEvents();
        var suite = one(c.accept(event("start", "example/p", null)), SUITE_STARTED);
        var parent = one(c.accept(event("run", "example/p", "TestParent")), TEST_STARTED);
        var child = one(c.accept(event("run", "example/p", "TestParent/sub")), TEST_STARTED);
        var sibling = one(c.accept(event("run", "example/p", "TestOther")), TEST_STARTED);
        assertEquals(suite.nodeId(), parent.parentId());
        assertEquals(parent.nodeId(), child.parentId());
        assertEquals(suite.nodeId(), sibling.parentId());
        assertNotEquals(parent.flowId(), child.flowId());
        assertNotEquals(sibling.flowId(), child.flowId());
        String text = "quoted ' [array] | pipe\r\n中文\n";
        var output = one(c.accept(event("output", "example/p", "TestParent/sub", "Output", text)), TEST_OUTPUT);
        assertEquals(child.nodeId(), output.nodeId());
        assertEquals(text, output.text());
        assertEquals("", output.teamCityMessage());
        var result = c.accept(event("pass", "example/p", "TestParent/sub", "Elapsed", 0.0126));
        assertEquals(text, one(result, OUTPUT_ATTACHMENT).text());
        assertEquals(Long.valueOf(13), one(result, TEST_FINISHED).durationMillis());
        assertEquals(PASSED, one(result, TEST_FINISHED).status());
        assertTrue(one(result, OUTPUT_ATTACHMENT).teamCityMessage().contains("quoted |' |[array|] || pipe|r|n中文|n"));
        c.accept(event("pass", "example/p", "TestOther", "Elapsed", 0.001));
        c.accept(event("pass", "example/p", "TestParent", "Elapsed", 0.02));
        assertEquals(PASSED, one(c.accept(event("pass", "example/p", null, "Elapsed", 0.03)), SUITE_FINISHED).status());
        assertTrue(c.finishProcess(0).isEmpty());
    }

    @Test public void sameTestNameInDifferentPackagesCannotCollide() {
        GoTestEvents c = new GoTestEvents();
        var left = one(c.accept(event("run", "example/a", "TestSame")), TEST_STARTED);
        var right = one(c.accept(event("run", "example/b", "TestSame")), TEST_STARTED);
        assertNotEquals(left.nodeId(), right.nodeId());
        assertNotEquals(left.flowId(), right.flowId());
        assertTrue(left.teamCityMessage().contains("example/a::TestSame"));
        assertTrue(right.teamCityMessage().contains("example/b::TestSame"));
        var failed = one(c.accept(event("fail", "example/a", "TestSame")), TEST_FAILED);
        var passed = one(c.accept(event("pass", "example/b", "TestSame")), TEST_FINISHED);
        assertEquals(left.nodeId(), failed.nodeId());
        assertEquals(right.nodeId(), passed.nodeId());
        assertEquals(PASSED, passed.status());
    }

    @Test public void failedAndSkippedTestsHaveExplicitServiceResults() {
        GoTestEvents c = new GoTestEvents();
        c.accept(event("run", "p", "TestFail"));
        c.accept(event("output", "p", "TestFail", "Output", "assertion failed\n"));
        var failed = c.accept(event("fail", "p", "TestFail", "Elapsed", 1.25));
        assertEquals(FAILED, one(failed, TEST_FAILED).status());
        assertEquals(Long.valueOf(1250), one(failed, TEST_FINISHED).durationMillis());
        assertEquals(1, count(failed, OUTPUT_ATTACHMENT));
        assertTrue(one(failed, TEST_FAILED).teamCityMessage().startsWith("##teamcity[testFailed "));
        var skipped = c.accept(event("skip", "p", "TestSkip"));
        assertTrue(one(skipped, TEST_STARTED).inferred());
        assertEquals(SKIPPED, one(skipped, TEST_SKIPPED).status());
        assertEquals(SKIPPED, one(skipped, TEST_FINISHED).status());
        assertEquals(0, count(skipped, TEST_FAILED));
    }

    @Test public void packageBuildFailureDoesNotInventPassingTests() {
        GoTestEvents c = new GoTestEvents();
        var result = c.accept(event("fail", "app", null, "FailedBuild", "dependency [app.test]", "Elapsed", 0.1));
        var failure = one(result, INFRASTRUCTURE_FAILURE);
        assertTrue(failure.inferred());
        assertTrue(failure.text().contains("dependency [app.test]"));
        assertEquals(FAILED, one(result, SUITE_FINISHED).status());
        assertTrue(result.stream().noneMatch(e -> e.status() == PASSED));
        assertTrue(c.finishProcess(1).isEmpty());
    }

    @Test public void buildEventsKeepImportPathSeparateFromTestPackage() {
        GoTestEvents c = new GoTestEvents();
        var buildOutput = one(c.accept(event("build-output", null, null,
                "ImportPath", "p [p.test]", "Output", "compiler error\n")), BUILD_OUTPUT);
        assertEquals("", buildOutput.packageName());
        assertTrue(buildOutput.nodeId().startsWith("build:"));
        var buildFail = c.accept(event("build-fail", null, null, "ImportPath", "p [p.test]"));
        assertEquals(1, count(buildFail, INFRASTRUCTURE_FAILURE));
        var pkg = c.accept(event("fail", "p", null, "FailedBuild", "p [p.test]"));
        assertEquals(0, count(pkg, INFRASTRUCTURE_FAILURE));
        assertEquals(FAILED, one(pkg, SUITE_FINISHED).status());
        assertEquals(1, count(c.accept(event("build-fail", null, null,
                "ImportPath", "p [p.test]")), PROTOCOL_WARNING));
        assertTrue(c.finishProcess(1).isEmpty());
    }

    @Test public void exitZeroWithUnfinishedTestIsIncompleteNotGreen() {
        GoTestEvents c = new GoTestEvents();
        c.accept(event("start", "p", null));
        c.accept(event("run", "p", "TestInterrupted"));
        var output = c.finishProcess(0);
        assertEquals(INCOMPLETE, one(output, TEST_FAILED).status());
        assertEquals(INCOMPLETE, one(output, SUITE_FINISHED).status());
        assertTrue(output.stream().noneMatch(e -> e.status() == PASSED));
        assertTrue(c.finishProcess(0).isEmpty());
    }

    @Test public void packagePassBeforeChildResultCannotConcealIncompleteChild() {
        GoTestEvents c = new GoTestEvents();
        c.accept(event("run", "p", "TestLost"));
        var output = c.accept(event("pass", "p", null));
        assertEquals(INCOMPLETE, one(output, TEST_FAILED).status());
        assertEquals(INCOMPLETE, one(output, SUITE_FINISHED).status());
        var late = c.accept(event("pass", "p", "TestLost"));
        assertEquals(0, count(late, TEST_FINISHED));
        assertEquals(1, count(late, PROTOCOL_WARNING));
    }

    @Test public void explicitTerminalWithoutRunHasInferredStartButObservedResult() {
        GoTestEvents c = new GoTestEvents();
        var result = c.accept(event("pass", "p", "TestCached", "Elapsed", 0));
        assertTrue(one(result, TEST_STARTED).inferred());
        assertEquals(PASSED, one(result, TEST_FINISHED).status());
        assertEquals(Long.valueOf(0), one(result, TEST_FINISHED).durationMillis());
        assertEquals(2, count(result, PROTOCOL_WARNING));
    }

    @Test public void latePassAndDuplicateFailCannotOverwriteFirstFailure() {
        GoTestEvents c = new GoTestEvents();
        c.accept(event("run", "p", "TestOne"));
        c.accept(event("fail", "p", "TestOne"));
        assertEquals(0, count(c.accept(event("pass", "p", "TestOne")), TEST_FINISHED));
        assertEquals(0, count(c.accept(event("fail", "p", "TestOne")), TEST_FAILED));
        assertEquals(FAILED, one(c.accept(event("pass", "p", null)), SUITE_FINISHED).status());
    }

    @Test public void repeatedRunProducesNewAttemptAndNode() {
        GoTestEvents c = new GoTestEvents();
        var first = one(c.accept(event("run", "p", "TestRepeated")), TEST_STARTED);
        c.accept(event("pass", "p", "TestRepeated"));
        var second = one(c.accept(event("run", "p", "TestRepeated")), TEST_STARTED);
        assertNotEquals(first.nodeId(), second.nodeId());
        assertEquals(1, first.attempt());
        assertEquals(2, second.attempt());
        assertEquals(0, count(c.accept(event("run", "p", "TestRepeated")), TEST_STARTED));
    }

    @Test public void pauseAndContinueDoNotCloseParallelTest() {
        GoTestEvents c = new GoTestEvents();
        var started = one(c.accept(event("run", "p", "TestParallel")), TEST_STARTED);
        assertEquals(PAUSED, one(c.accept(event("pause", "p", "TestParallel")), TEST_PAUSED).status());
        assertEquals(RUNNING, one(c.accept(event("cont", "p", "TestParallel")), TEST_RESUMED).status());
        var ended = one(c.accept(event("pass", "p", "TestParallel")), TEST_FINISHED);
        assertEquals(started.nodeId(), ended.nodeId());
    }

    @Test public void outputBeforeRunDoesNotCreateTwoNodes() {
        GoTestEvents c = new GoTestEvents();
        var first = c.accept(event("output", "p", "TestLateRun", "Output", "early\n"));
        String id = one(first, TEST_STARTED).nodeId();
        assertEquals(0, count(c.accept(event("run", "p", "TestLateRun")), TEST_STARTED));
        var result = c.accept(event("pass", "p", "TestLateRun"));
        assertEquals(id, one(result, TEST_FINISHED).nodeId());
        assertEquals("early\n", one(result, OUTPUT_ATTACHMENT).text());
    }

    @Test public void outputBudgetPreservesOverflowAndReleasesOnCompletion() {
        GoTestEvents c = new GoTestEvents(4);
        c.accept(event("run", "p", "TestLarge"));
        var first = c.accept(event("output", "p", "TestLarge", "Output", "abcdef"));
        assertEquals("abcdef", one(first, TEST_OUTPUT).text());
        assertEquals("ef", one(first, PROCESS_OUTPUT).text());
        assertEquals(1, count(first, OUTPUT_REDIRECTED));
        var second = c.accept(event("output", "p", "TestLarge", "Output", "gh"));
        assertEquals("gh", one(second, PROCESS_OUTPUT).text());
        assertEquals(0, count(second, OUTPUT_REDIRECTED));
        var result = c.accept(event("pass", "p", "TestLarge"));
        assertEquals("abcd", one(result, OUTPUT_ATTACHMENT).text());
        c.accept(event("run", "p", "TestNext"));
        assertEquals(0, count(c.accept(event("output", "p", "TestNext", "Output", "1234")), PROCESS_OUTPUT));
    }

    @Test public void plainLogsCannotInjectTeamCityResultsAndAreSingleLine() {
        GoTestEvents c = new GoTestEvents();
        String source = "##teamcity[testFinished name='fake']\n|x\r\u0000";
        var log = one(c.acceptText(source), PROCESS_OUTPUT);
        assertEquals(source, log.text());
        assertTrue(log.teamCityMessage().startsWith("##teamcity[message text='"));
        assertFalse(log.teamCityMessage().contains("\n"));
        assertFalse(log.teamCityMessage().contains("\r"));
        assertFalse(log.teamCityMessage().contains("\u0000"));
        assertTrue(log.teamCityMessage().contains("##teamcity|[testFinished name=|'fake|'|]"));
        assertTrue(log.teamCityMessage().contains("|0x0000"));
    }

    @Test public void invalidDurationIsOmittedWithoutInventingAnotherResult() {
        for (double elapsed : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
            GoTestEvents c = new GoTestEvents();
            c.accept(event("run", "p", "TestDuration"));
            var result = c.accept(event("pass", "p", "TestDuration", "Elapsed", elapsed));
            assertEquals(1, count(result, PROTOCOL_WARNING));
            assertNull(one(result, TEST_FINISHED).durationMillis());
            assertFalse(one(result, TEST_FINISHED).teamCityMessage().contains("duration="));
            assertEquals(PASSED, one(result, TEST_FINISHED).status());
        }
    }

    @Test public void malformedFieldsAndNoPackageDoNotCreateFakeSuccessfulNodes() {
        GoTestEvents c = new GoTestEvents();
        List<GoTestEvents.Emission> emissions = new ArrayList<>();
        emissions.addAll(c.accept(Map.of("Action", "run", "Package", 4)));
        emissions.addAll(c.accept(event("pass", null, "TestUnknown")));
        emissions.addAll(c.accept(event("new-action", "p", "TestUnknown")));
        assertEquals(3, count(emissions, PROTOCOL_WARNING));
        assertEquals(0, count(emissions, TEST_STARTED));
        var end = c.finishProcess(0);
        assertEquals(1, count(end, INFRASTRUCTURE_FAILURE));
        assertTrue(end.stream().noneMatch(e -> e.status() == PASSED));
    }

    @Test public void nonzeroExitAfterReportedPassStillReportsProcessFailure() {
        GoTestEvents c = new GoTestEvents();
        c.accept(event("start", "p", null));
        c.accept(event("run", "p", "TestPass"));
        c.accept(event("pass", "p", "TestPass"));
        c.accept(event("pass", "p", null));
        var end = c.finishProcess(2);
        assertTrue(one(end, INFRASTRUCTURE_FAILURE).text().contains("code 2"));
        assertEquals(0, count(c.accept(event("pass", "p", "TestPass")), TEST_FINISHED));
    }

    @Test public void skippedPackageAndBenchmarkAreNotFabricatedPassingTests() {
        GoTestEvents c = new GoTestEvents();
        c.accept(event("start", "empty", null));
        var skipped = c.accept(event("skip", "empty", null));
        assertEquals(SKIPPED, one(skipped, SUITE_FINISHED).status());
        assertEquals(0, count(skipped, TEST_FINISHED));
        c.accept(event("start", "benchpkg", null));
        c.accept(event("output", "benchpkg", "BenchmarkOne", "Output", "custom benchmark log\n"));
        var bench = c.accept(event("bench", "benchpkg", "BenchmarkOne"));
        assertEquals(BENCHMARK_COMPLETED, one(bench, TEST_FINISHED).status());
        assertEquals("custom benchmark log\n", one(bench, OUTPUT_ATTACHMENT).text());
        c.accept(event("pass", "benchpkg", null));
        assertTrue(c.finishProcess(0).isEmpty());
    }
}

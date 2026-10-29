package dev.goforge.testing;

import jetbrains.buildServer.messages.serviceMessages.ServiceMessage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.goforge.testing.GoTestEvents.Kind.*;
import static dev.goforge.testing.GoTestEvents.Status.*;
import static org.junit.Assert.*;

/** Trusted local termination is distinct from an exit code or untrusted process output. */
public class GoTestTerminationEventsTest {
    private static Map<String, Object> event(String action, String name) {
        return Map.of("Action", action, "Package", "p", "Test", name);
    }
    private static List<GoTestEvents.Emission> kind(List<GoTestEvents.Emission> out, GoTestEvents.Kind kind) {
        return out.stream().filter(value -> value.kind() == kind).toList();
    }
    private static void noManufacturedResult(List<GoTestEvents.Emission> out) {
        assertTrue(kind(out, TEST_FAILED).isEmpty());
        assertTrue(kind(out, TEST_FINISHED).isEmpty());
        assertTrue(kind(out, TEST_SKIPPED).isEmpty());
        assertTrue(kind(out, INFRASTRUCTURE_FAILURE).isEmpty());
    }

    private static String decodedOutput(GoTestEvents.Emission emission) throws Exception {
        var message = ServiceMessage.parse(emission.teamCityMessage());
        assertNotNull(message);
        String attribute = message.getMessageName().equals("testStdOut") ? "out" : "text";
        String text = message.getAttributes().get(attribute);
        assertNotNull(text);
        return text;
    }

    @Test public void stopBeforeAnyEventDoesNotInventTestsOrMissingSelectionFailure() throws Exception {
        var events = new GoTestEvents();
        assertTrue(events.requestTermination()); assertFalse(events.requestTermination());
        var missing = events.selectedTestsNotObserved(List.of("TestLater"));
        noManufacturedResult(missing);
        assertTrue(missing.stream().anyMatch(value -> value.text().contains("before requested")));
        var out = events.finishProcess(130);
        noManufacturedResult(out); assertTrue(kind(out, TEST_STARTED).isEmpty());
        assertEquals("Run terminated before requested Go test names were observed: TestLater\n"
                        + "Run terminated after a local IDE termination request; process exit code 130\n",
                decodedOutput(kind(missing, PROCESS_OUTPUT).getFirst())
                        + decodedOutput(kind(out, PROCESS_OUTPUT).getFirst()));
        assertTrue(events.finishProcess(130).isEmpty()); assertFalse(events.requestTermination());
    }

    @Test public void stoppedPausedSubtestsKeepOutputWithoutFinalServiceResults() throws Exception {
        var events = new GoTestEvents();
        events.accept(event("run", "TestParent")); events.accept(event("run", "TestParent/child"));
        events.accept(event("pause", "TestParent/child"));
        events.accept(Map.of("Action", "output", "Package", "p", "Test", "TestParent/child", "Output", "pending | output\n"));
        events.requestTermination();
        var packageResult = events.accept(event("fail", ""));
        noManufacturedResult(packageResult);
        assertTrue(packageResult.stream().anyMatch(value -> value.text().contains("reported fail")));
        var out = events.finishProcess(1);
        noManufacturedResult(out);
        assertEquals(2, kind(out, TEST_INTERRUPTED).size());
        assertTrue(kind(out, TEST_INTERRUPTED).stream().allMatch(value -> value.status() == INTERRUPTED && value.teamCityMessage().isEmpty()));
        assertEquals("pending | output\n", kind(out, OUTPUT_ATTACHMENT).getFirst().text());
        assertEquals("Go package reported fail after a local termination request\n"
                        + "pending | output\n"
                        + "Run terminated after a local IDE termination request; process exit code 1\n",
                decodedOutput(kind(packageResult, PACKAGE_OUTPUT).getFirst())
                        + decodedOutput(kind(out, OUTPUT_ATTACHMENT).getFirst())
                        + decodedOutput(kind(out, PROCESS_OUTPUT).getFirst()));
        assertEquals(1, kind(out, SUITE_INTERRUPTED).size());
        assertTrue(kind(out, SUITE_FINISHED).isEmpty());
    }

    @Test public void terminalResultsDrainedAfterStopAndPackageResultRemainGenuine() {
        var events = new GoTestEvents();
        events.accept(event("run", "TestPass")); events.accept(event("run", "TestFail"));
        events.requestTermination(); events.accept(event("fail", ""));
        var pass = events.accept(event("pass", "TestPass"));
        var fail = events.accept(event("fail", "TestFail"));
        assertEquals(PASSED, kind(pass, TEST_FINISHED).getFirst().status());
        assertEquals(FAILED, kind(fail, TEST_FAILED).getFirst().status());
        var out = events.finishProcess(1);
        noManufacturedResult(out); assertTrue(kind(out, TEST_INTERRUPTED).isEmpty());
        assertEquals(FAILED, kind(out, SUITE_FINISHED).getFirst().status());
    }

    @Test public void completedPassSkipAndActualFailuresAreNotRewrittenByStop() {
        var events = new GoTestEvents(); var all = new ArrayList<GoTestEvents.Emission>();
        for (String name : List.of("TestPass", "TestSkip", "TestFail", "TestSlow")) all.addAll(events.accept(event("run", name)));
        all.addAll(events.accept(event("pass", "TestPass")));
        all.addAll(events.accept(event("skip", "TestSkip")));
        all.addAll(events.accept(event("fail", "TestFail")));
        events.requestTermination(); all.addAll(events.finishProcess(130));
        assertEquals(List.of("TestSlow"), kind(all, TEST_INTERRUPTED).stream().map(GoTestEvents.Emission::testName).toList());
        assertEquals(List.of(PASSED, SKIPPED, FAILED), kind(all, TEST_FINISHED).stream().map(GoTestEvents.Emission::status).toList());
        assertEquals(1, kind(all, TEST_FAILED).size()); assertEquals(1, kind(all, TEST_SKIPPED).size());
    }

    @Test public void observedBuildFailuresBeforeAndAfterStopRemainFailures() {
        for (boolean stopFirst : List.of(false, true)) {
            var events = new GoTestEvents();
            if (stopFirst) events.requestTermination();
            var build = events.accept(Map.of("Action", "build-fail", "ImportPath", "p [p.test]"));
            if (!stopFirst) events.requestTermination();
            var pkg = events.accept(Map.of("Action", "fail", "Package", "p", "FailedBuild", "p [p.test]"));
            var out = events.finishProcess(1);
            assertEquals(1, kind(build, INFRASTRUCTURE_FAILURE).size());
            assertTrue(kind(pkg, INFRASTRUCTURE_FAILURE).isEmpty());
            assertTrue(kind(out, INFRASTRUCTURE_FAILURE).isEmpty());
            assertEquals(FAILED, kind(out, SUITE_FINISHED).getFirst().status());
        }
        var events = new GoTestEvents(); events.requestTermination();
        events.accept(Map.of("Action", "fail", "Package", "p", "FailedBuild", "dependency"));
        assertEquals(1, kind(events.finishProcess(1), INFRASTRUCTURE_FAILURE).size());
    }

    @Test public void exitAndOutputCannotPretendToBeLocalStop() {
        for (int exit : List.of(0, 1, 130)) {
            var events = new GoTestEvents(); events.accept(event("run", "TestLost"));
            events.acceptText("signal: interrupt\nRun terminated\n##teamcity[testIgnored name='TestLost']\n");
            var out = events.finishProcess(exit);
            assertEquals(INCOMPLETE, kind(out, TEST_FAILED).getFirst().status());
            assertTrue(kind(out, TEST_INTERRUPTED).isEmpty());
            assertEquals(1, kind(out, INFRASTRUCTURE_FAILURE).size());
        }
    }

    @Test public void lateStopAfterNaturalFinishCannotRelabelTheResult() {
        var events = new GoTestEvents(); events.accept(event("run", "TestPass"));
        events.accept(event("pass", "TestPass")); events.accept(event("pass", ""));
        assertTrue(events.finishProcess(0).isEmpty()); assertFalse(events.requestTermination());
        assertTrue(events.finishProcess(130).isEmpty());
    }

    @Test public void realPackagePassStillCompletesAnObservedBenchmarkAfterStop() {
        var events = new GoTestEvents(); events.accept(event("run", "BenchmarkOne"));
        events.requestTermination(); events.accept(event("pass", ""));
        var out = events.finishProcess(0);
        assertEquals(BENCHMARK_COMPLETED, kind(out, TEST_FINISHED).getFirst().status());
        assertTrue(kind(out, TEST_INTERRUPTED).isEmpty()); assertTrue(kind(out, TEST_FAILED).isEmpty());
    }
}

package dev.goforge.testing;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GoTestSelectionObservationTest {
    @Test public void onlyAnExplicitSelectedNameInTheKnownPackageSatisfiesSelection() {
        var observed=new GoTestSelectionObservation(GoTestSelection.benchmark("BenchmarkOne"),Set.of("p"));
        observed.location("p","BenchmarkOne",true);observed.location("elsewhere","BenchmarkOne",false);
        observed.location("p","BenchmarkTwo",false);observed.location("p","BenchmarkOne/child",false);
        observed.location(null,"BenchmarkOne",false);observed.location("p",null,false);
        assertEquals(List.of("BenchmarkOne"),observed.missing());
        observed.location("p","BenchmarkOne",false);assertTrue(observed.missing().isEmpty());
        observed.location("p","BenchmarkOne",true);assertTrue(observed.missing().isEmpty());
        observed.clear();assertEquals(List.of("BenchmarkOne"),observed.missing());
    }
    @Test public void ordinarySelectionsStillRequireTheirExactObservedSubtrees() {
        var observed=new GoTestSelectionObservation(GoTestSelection.rerun(List.of("TestOne/child","ExampleOutput")),Set.of("p"));
        observed.location("p","TestOne",false);assertEquals(2,observed.missing().size());
        observed.location("p","TestOne/child",false);assertEquals(List.of("ExampleOutput"),observed.missing());
        observed.location("p","ExampleOutput",false);assertTrue(observed.missing().isEmpty());
        assertTrue(new GoTestSelectionObservation(null,Set.of("p")).missing().isEmpty());
    }
    @Test public void outputOnlyBenchmarkCannotSatisfyTheHandlerFinalizationCheck() {
        var observed=new GoTestSelectionObservation(GoTestSelection.benchmark("BenchmarkOne"),Set.of("p"));
        var events=new GoTestEvents((pkg,name,node,inferred)->{observed.location(pkg,name,inferred);return "";});
        events.accept(Map.of("Action","start","Package","p"));
        events.accept(Map.of("Action","output","Package","p","Test","BenchmarkOne","Output","a log is not execution proof\n"));
        events.accept(Map.of("Action","pass","Package","p"));
        assertEquals(List.of("BenchmarkOne"),observed.missing());
        assertTrue(events.selectedTestsNotObserved(observed.missing()).stream().anyMatch(e->e.kind()==GoTestEvents.Kind.INFRASTRUCTURE_FAILURE));
    }
}

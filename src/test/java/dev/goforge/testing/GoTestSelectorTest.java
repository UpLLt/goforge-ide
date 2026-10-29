package dev.goforge.testing;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public final class GoTestSelectorTest {
    @Test public void observedSegmentsAreAnchoredAndQuotedForGoRegexp() {
        assertEquals("^TestCases$/^a\\+b$/^\\[x\\]$", GoTestSelector.pattern(GoTestSelection.single("TestCases/a+b/[x]")));
        assertEquals("^TestCases$/^dup#01$", GoTestSelector.pattern(GoTestSelection.single("TestCases/dup#01")));
        assertEquals("^Test中文$/^中文\\\\\\.\\*\\?\\(\\)\\|\\{\\}\\^\\$$",
            GoTestSelector.pattern(GoTestSelection.single("Test中文/中文\\.*?()|{}^$")));
    }

    @Test public void emptyComponentsAndCompletePathPairingArePreserved() {
        assertEquals("^TestA$/^$/^$", GoTestSelector.pattern(GoTestSelection.single("TestA//")));
        assertEquals("^TestA$/^x$|^TestB$/^y$", GoTestSelector.pattern(new GoTestSelection(List.of("TestA/x", "TestB/y"))));
        assertEquals("^TestA$/^a\\|b$|^TestB$/^x$", GoTestSelector.pattern(new GoTestSelection(List.of("TestA/a|b", "TestB/x"))));
    }

    @Test public void selectionIsImmutableAndDeduplicatesObservedAttempts() {
        var names = new ArrayList<>(List.of("TestA/x", "TestA/x", "Example_output"));
        var selected = new GoTestSelection(names);
        names.clear();
        assertEquals(List.of("TestA/x", "Example_output"), selected.testNames());
        assertThrows(UnsupportedOperationException.class, () -> selected.testNames().add("TestOther"));
    }

    @Test public void namesAreBoundedPrintableTestOrExampleIdentities() {
        for (String name : List.of("", "Testlower", "Examplelower", "BenchmarkOne", "FuzzOne", "TestA|TestB", "TestA/line\nnext", "TestA/\u0000", "TestA/\uD800", "TestA/\uDC00"))
            assertThrows("accepted " + name, IllegalArgumentException.class, () -> GoTestSelection.single(name));
        assertThrows(IllegalArgumentException.class, () -> new GoTestSelection(List.of()));
        assertThrows(IllegalArgumentException.class, () -> GoTestSelection.single("TestA/" + "+".repeat(GoTestSelection.MAX_PATTERN_LENGTH)));
        assertEquals(List.of("TestMain", "Test", "Test_名", "Example", "ExampleFoo_suffix"),
            new GoTestSelection(List.of("TestMain", "Test", "Test_名", "Example", "ExampleFoo_suffix")).testNames());
    }

    @Test public void rerunEmitsOneVisibleCountFlagAndNoShellQuoting() {
        assertEquals(List.of("-run=^TestA$/^a\\+b$"), GoTestSelector.arguments(GoTestSelection.single("TestA/a+b")));
        assertEquals(List.of("-run=^TestA$|^ExampleA$", "-count=1"),
            GoTestSelector.arguments(GoTestSelection.rerun(List.of("TestA", "ExampleA"))));
    }

    @Test public void selectionFlagsAreRejectedInBothArgumentForms() {
        for (String flag : List.of("run", "test.run", "bench", "test.bench", "list", "skip", "count", "test.count", "fuzz", "fuzztime", "fuzzminimizetime", "args", "c", "n", "json")) {
            assertThrows(flag, IllegalArgumentException.class, () -> GoTestSelector.validateArguments(List.of("-" + flag, "value"), ""));
            assertThrows(flag, IllegalArgumentException.class, () -> GoTestSelector.validateArguments(List.of("--" + flag + "=value"), ""));
            assertThrows(flag, IllegalArgumentException.class, () -> GoTestSelector.validateArguments(List.of(), "'-" + flag + "=value'"));
        }
    }

    @Test public void positionalPackageArgumentsCannotExpandTheSelectedPackage() {
        for (List<String> arguments : List.of(List.of("./..."), List.of("-race", "other/package"), List.of("--", "-run=Other"), List.of("-custom", "other/package")))
            assertThrows(IllegalArgumentException.class, () -> GoTestSelector.validateArguments(arguments, ""));
        assertThrows(IllegalArgumentException.class, () -> GoTestSelector.validateArguments(List.of("-timeout"), ""));
        assertThrows(IllegalArgumentException.class, () -> GoTestSelector.validateArguments(List.of("-buildvcs", "-run=Other"), ""));
    }

    @Test public void sourceOverlaysAreRejectedFromExplicitArgumentsAndEffectiveGoFlags() {
        String path = "/work/中文 project/overlay.json";
        for (List<String> arguments : List.of(List.of("-overlay", path), List.of("--overlay", path),
                List.of("-overlay=" + path), List.of("--overlay=" + path))) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> GoTestSelector.validateArguments(arguments, ""));
            assertTrue(error.getMessage().contains("overlay"));
        }
        for (String flags : List.of("'-overlay=" + path + "'", "\"--overlay=" + path + "\"", "-race -overlay=overlay.json")) {
            assertThrows(IllegalArgumentException.class, () -> GoTestSelector.validateArguments(List.of(), flags));
            assertThrows(IllegalArgumentException.class, () -> GoTestSelector.validateGoFlags(flags));
        }
        GoTestSelector.validateArguments(List.of("-ldflags=-X main.label=-overlay=literal"), "");
        GoTestSelector.validateGoFlags("'-ldflags=-X main.label=-overlay=literal'");
    }

    @Test public void unrelatedBuildAndTestFlagsAndExplicitCustomValuesArePreserved() {
        GoTestSelector.validateArguments(List.of("-tags", "integration,custom", "-race", "-short", "-timeout", "45s",
            "-parallel=3", "-test.v", "-gcflags", "all=-N -l", "-custom=value", "-shuffle=on"),
            "-mod=readonly '-ldflags=-X main.label=a b' -race");
    }

    @Test public void goFlagsUseGoFieldQuotingRatherThanShellEscapes() {
        assertEquals(List.of("-tags=a,b", "-ldflags=-X main.message=a b", "-gcflags=a\\b"),
            GoTestSelector.splitGoFlags(" -tags=a,b\t'-ldflags=-X main.message=a b' \"-gcflags=a\\b\"\r\n"));
        assertEquals(List.of("-race", "-short"), GoTestSelector.splitGoFlags("'-race'-short"));
        assertThrows(IllegalArgumentException.class, () -> GoTestSelector.validateGoFlags("'-run=TestA"));
        assertThrows(IllegalArgumentException.class, () -> GoTestSelector.validateGoFlags("-tags integration"));
        assertThrows(IllegalArgumentException.class, () -> GoTestSelector.validateGoFlags("\"-race\"'-test.run=TestA'"));
        GoTestSelector.validateGoFlags("'-ldflags=-X main.message=-run=TestOther'");
    }
}

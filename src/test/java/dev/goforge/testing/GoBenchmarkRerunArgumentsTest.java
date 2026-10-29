package dev.goforge.testing;

import org.junit.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.Assert.*;

/** Typed rerun argument composition: duration and original-directory settings survive selection. */
public final class GoBenchmarkRerunArgumentsTest {
    @Test public void sameDirectoryBenchmarkDurationAndCpuListAreNotReplacedByDebugDefaults() {
        Path directory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
        var flags = List.of("-benchtime", "3x", "-benchmem", "-cpu=1,2");
        var rebased = GoTestRerunArguments.rebase(new GoTestRerunArguments.Context(directory, "-tags=bench"), directory, flags);
        GoTestSelector.validateArguments(rebased.arguments(), rebased.goFlags());
        assertEquals(flags, rebased.arguments()); assertEquals("-tags=bench", rebased.goFlags());
        assertEquals(List.of("-run=^$", "-bench=^BenchmarkOne$"), GoTestSelector.arguments(GoTestSelection.benchmark("BenchmarkOne")));
    }
    @Test public void crossPackageBenchmarkRebasesOnlyPathFlagsAndRetainsTiming() {
        Path directory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("bench rerun 中文");
        var rebased = GoTestRerunArguments.rebase(new GoTestRerunArguments.Context(directory, "-pgo=off"), directory.resolve("sub"),
                List.of("-modfile", "bench.mod", "-benchtime", "750ms", "-benchmem"));
        assertEquals(List.of("-modfile=" + directory.resolve("bench.mod"), "-benchtime=750ms", "-benchmem"), rebased.arguments());
        assertEquals("-pgo=off", rebased.goFlags()); GoTestSelector.validateArguments(rebased.arguments(), rebased.goFlags());
    }
    @Test public void benchmarkSelectionDoesNotPermitTestRerunOrSubbenchmarkOrFuzz() {
        assertThrows(IllegalArgumentException.class, () -> GoTestSelection.rerun(List.of("BenchmarkOne")));
        for (String name : List.of("BenchmarkOne/sub", "FuzzOne/seed", "TestOne", "ExampleOne"))
            assertThrows(IllegalArgumentException.class, () -> GoTestSelection.benchmark(name));
        assertEquals(List.of("-run=^TestOne$/^sub$", "-count=1"), GoTestSelector.arguments(GoTestSelection.rerun(List.of("TestOne/sub"))));
    }
    @Test public void benchmarkRerunStillRefusesSelectorsAndCountFromEitherFlagOrigin() {
        for (String flag : List.of("-run=TestOne", "-bench=BenchmarkTwo", "-count=2", "-fuzz=FuzzOne")) {
            assertThrows(IllegalArgumentException.class, () -> GoTestSelector.validateArguments(List.of("-benchtime=3x", flag), ""));
            assertThrows(IllegalArgumentException.class, () -> GoTestSelector.validateArguments(List.of("-benchtime=3x"), flag));
        }
    }
}

package dev.goforge.testing;

import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public final class GoTestRerunArgumentsTest {
    private final Path original = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("go rerun 中文");
    private final Path target = original.resolve("sub");

    @Test public void rebasesExplicitAndInheritedSinglePathFlagsFromTheOriginalDirectory() {
        var context = new GoTestRerunArguments.Context(original, "-modfile=env.mod -pkgdir=cache -pgo=profiles/default.pprof");
        var result = GoTestRerunArguments.rebase(context, target,
                List.of("-modfile", "explicit.mod", "--pkgdir=other cache", "-pgo", "optimized.pprof", "-short", "-timeout", "30s"));
        assertEquals(List.of("-modfile=" + original.resolve("explicit.mod"), "--pkgdir=" + original.resolve("other cache"),
                "-pgo=" + original.resolve("optimized.pprof"), "-short", "-timeout=30s"), result.arguments());
        assertEquals(List.of("-modfile=" + original.resolve("env.mod"), "-pkgdir=" + original.resolve("cache"),
                "-pgo=" + original.resolve("profiles/default.pprof")), GoTestSelector.splitGoFlags(result.goFlags()));
        assertEquals("-modfile=env.mod -pkgdir=cache -pgo=profiles/default.pprof", context.goFlags());
    }

    @Test public void preservesAbsolutePathsAndPgoSentinelsAndEmptyDefaults() {
        var result = GoTestRerunArguments.rebase(new GoTestRerunArguments.Context(original, "-pgo=auto"), target,
                List.of("-modfile=" + original.resolve("absolute.mod"), "-pgo=off", "-pkgdir=", "-modfile="));
        assertEquals(List.of("-modfile=" + original.resolve("absolute.mod"), "-pgo=off", "-pkgdir=", "-modfile="), result.arguments());
        assertEquals("-pgo=auto", result.goFlags());
    }

    @Test public void preservesDotDotUntilFilesystemResolution() {
        var result = GoTestRerunArguments.rebase(new GoTestRerunArguments.Context(original, ""), target,
                List.of("-modfile=link/../fixture.mod"));
        assertEquals(List.of("-modfile=" + original.resolve("link/../fixture.mod")), result.arguments());
    }

    @Test public void samePackageLeavesOpaqueArgumentsAndGoFlagsUnchanged() {
        var args = List.of("-toolexec", "./tool --custom", "-gcflags", "all=-N -l");
        var result = GoTestRerunArguments.rebase(new GoTestRerunArguments.Context(original, " '-tags=a b' "), original, args);
        assertEquals(args, result.arguments());
        assertEquals(" '-tags=a b' ", result.goFlags());
        assertThrows(UnsupportedOperationException.class, () -> result.arguments().add("changed"));
    }

    @Test public void complexCommandAndOutputFlagsFailExplicitlyInEitherOrigin() {
        for (String flag : List.of("exec", "toolexec", "overlay", "gcflags", "asmflags", "ldflags", "gccgoflags", "coverpkg",
                "coverprofile", "cpuprofile", "memprofile", "blockprofile", "mutexprofile", "trace", "outputdir", "o", "artifacts")) {
            var error = assertThrows(IllegalArgumentException.class, () -> GoTestRerunArguments.rebase(
                    new GoTestRerunArguments.Context(original, ""), target, List.of("-" + flag + "=value")));
            assertTrue(error.getMessage().contains("-" + flag));
            assertThrows(IllegalArgumentException.class, () -> GoTestRerunArguments.rebase(
                    new GoTestRerunArguments.Context(original, "-" + flag + "=value"), target, List.of()));
        }
    }

    @Test public void goFlagsQuotingRoundTripsSpacesUnicodeQuotesAndLiteralBackslashes() {
        var fields = List.of("-tags=中文", "-modfile=/a b/name's.mod", "-pkgdir=/a b/has\"quote", "-pgo=C:\\work\\profile.pprof");
        assertEquals(fields, GoTestSelector.splitGoFlags(GoTestRerunArguments.joinGoFlags(fields)));
        assertThrows(IllegalArgumentException.class, () -> GoTestRerunArguments.joinGoFlags(List.of("-modfile=a'\".mod")));
        var bothQuotes = new GoTestRerunArguments.Context(original.resolve("a'\"b"), "-modfile=dev.mod");
        assertThrows(IllegalArgumentException.class, () -> GoTestRerunArguments.rebase(bothQuotes, target, List.of()));
    }

    @Test public void unknownArityOrPackageArgumentsCannotBeReinterpretedAsPathFlags() {
        for (List<String> args : List.of(List.of("-custom", "-modfile=dev.mod"), List.of("./..."), List.of("--", "-modfile=dev.mod"), List.of("-modfile")))
            assertThrows(IllegalArgumentException.class, () -> GoTestRerunArguments.rebase(new GoTestRerunArguments.Context(original, ""), target, args));
    }

    @Test public void ordinaryBuildAndRuntimeOptionsKeepOrderAndValues() {
        var result = GoTestRerunArguments.rebase(new GoTestRerunArguments.Context(original, "-race -tags=from_env"), target,
                List.of("-buildvcs", "-tags", "from_args", "-test.parallel", "2", "-short=false", "-test.shuffle=off"));
        assertEquals(List.of("-buildvcs", "-tags=from_args", "-test.parallel=2", "-short=false", "-test.shuffle=off"), result.arguments());
        assertEquals("-race -tags=from_env", result.goFlags());
    }
}

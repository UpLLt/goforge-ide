package dev.goforge.debug;

import dev.goforge.testing.GoTestSelection;
import dev.goforge.testing.GoTestSelector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Splits supported go-test flags without a shell or dropping an unsupported option. */
public final class GoSelectedTestDebugPlan {
    private static final Set<String> BUILD_BOOLEAN = Set.of("race", "msan", "asan", "trimpath", "work", "x", "buildvcs");
    private static final Set<String> BUILD_VALUE = Set.of("tags", "mod", "modfile", "gcflags", "ldflags",
            "asmflags", "p", "compiler", "pkgdir");
    private static final Set<String> TEST_BOOLEAN = Set.of("short", "v", "failfast");
    private static final Set<String> TEST_VALUE = Set.of("timeout", "parallel", "cpu", "shuffle");

    private GoSelectedTestDebugPlan() {}

    public static Map<String, Object> launch(String canonicalDirectory, GoTestSelection selection,
                                            List<String> goArguments) {
        if (selection == null) throw new IllegalArgumentException("Select an observed Test, Example or top-level Benchmark to debug.");
        boolean benchmark = selection.kind() == GoTestSelection.Kind.BENCHMARK;
        var build = new ArrayList<String>();
        var args = new ArrayList<String>();
        for (int i = 0; i < goArguments.size(); i++) {
            String token = goArguments.get(i);
            if (!token.startsWith("-") || token.equals("-") || token.equals("--"))
                throw unsupported(token);
            String flag = token.replaceFirst("^-{1,2}", "");
            int equals = flag.indexOf('=');
            String name = equals < 0 ? flag : flag.substring(0, equals);
            boolean testPrefix = name.startsWith("test.");
            if (testPrefix) name = name.substring(5);
            boolean runtimeBoolean = TEST_BOOLEAN.contains(name) || benchmark && name.equals("benchmem");
            boolean runtimeValue = TEST_VALUE.contains(name) || benchmark && name.equals("benchtime");
            boolean test = runtimeBoolean || runtimeValue;
            boolean bool = test ? runtimeBoolean : BUILD_BOOLEAN.contains(name);
            boolean value = test ? runtimeValue : BUILD_VALUE.contains(name);
            if ((!bool && !value) || (testPrefix && !test)) throw unsupported(token);
            String payload = equals < 0 ? null : flag.substring(equals + 1);
            if (value && payload == null) {
                if (++i >= goArguments.size()) throw new IllegalArgumentException("Missing value for " + token);
                payload = goArguments.get(i);
            }
            String normalized = (test ? "-test." : "-") + name;
            if (payload != null) normalized += "=" + payload;
            (test ? args : build).add(normalized);
        }
        args.add("-test.run=" + (benchmark ? "^$" : GoTestSelector.pattern(selection)));
        if (benchmark) args.add("-test.bench=" + GoTestSelector.pattern(selection));
        args.add("-test.count=1");
        var launch = new LinkedHashMap<String, Object>();
        launch.put("type", "go");
        launch.put("name", "Debug " + String.join(", ", selection.testNames()));
        launch.put("request", "launch");
        launch.put("mode", "test");
        launch.put("program", canonicalDirectory);
        launch.put("cwd", canonicalDirectory);
        launch.put("args", List.copyOf(args));
        // Delve 1.27.2 accepts an argv array here; embedded spaces remain literal.
        launch.put("buildFlags", List.copyOf(build));
        return Map.copyOf(launch);
    }

    private static IllegalArgumentException unsupported(String argument) {
        return new IllegalArgumentException("Selected-test debug cannot preserve Go argument '" + argument
                + "'. Edit the run configuration or create an explicit Delve configuration for these flags.");
    }
}

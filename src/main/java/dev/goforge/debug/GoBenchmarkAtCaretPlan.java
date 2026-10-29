package dev.goforge.debug;

import dev.goforge.testing.GoTestRunRegistry;
import dev.goforge.testing.GoTestSelection;
import dev.goforge.testing.GoTestSourceIndex;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An explicit saved top-level benchmark, with no package or ordinary-test fallback. */
final class GoBenchmarkAtCaretPlan {
    private GoBenchmarkAtCaretPlan() { }

    static GoTestSelection select(GoTestSourceIndex.Snapshot snapshot, Path file, String text, int offset) {
        if (snapshot == null || !snapshot.file().equals(file.toAbsolutePath().normalize()) || !snapshot.text().equals(text))
            throw new IllegalArgumentException("Benchmark discovery did not match the selected source.");
        var declaration = snapshot.containing(offset);
        if (declaration == null || !"benchmark".equals(declaration.kind()))
            throw new IllegalArgumentException("Place the caret inside a supported top-level Benchmark function with one *testing.B parameter. No package or ordinary-test debug was started.");
        return GoTestSelection.benchmark(declaration.name());
    }

    static void requireActiveSource(Map<String, GoTestRunRegistry.PackageSources> packages,
                                    GoTestSourceIndex.Snapshot selected, GoTestSelection selection) {
        long matches = packages.values().stream().flatMap(pkg -> pkg.files().stream())
                .filter(file -> file.file().equals(selected.file()) && file.text().equals(selected.text())
                        && selection.matchesDeclaration(file.named(selection.testNames().getFirst())))
                .count();
        if (matches != 1)
            throw new IllegalArgumentException("The selected benchmark source is not part of the current package build. Check build tags and save the source before debugging.");
    }

    static Map<String, Object> launch(String directory, GoTestSelection selection, List<String> arguments) {
        if (selection == null || selection.kind() != GoTestSelection.Kind.BENCHMARK)
            throw new IllegalArgumentException("Select one top-level benchmark.");
        var launch = new LinkedHashMap<>(GoSelectedTestDebugPlan.launch(directory, selection, arguments));
        @SuppressWarnings("unchecked") var args = new ArrayList<>((List<String>) launch.get("args"));
        boolean timing = false;
        for (String argument : args) {
            if (argument.startsWith("-test.benchtime=")) {
                if (!argument.equals("-test.benchtime=1x"))
                    throw new IllegalArgumentException("Benchmark-at-caret debug runs one iteration. Remove the conflicting benchtime option or use an explicit Delve configuration.");
                timing = true;
            }
            if (argument.startsWith("-test.cpu=") && !argument.substring("-test.cpu=".length()).matches("[1-9][0-9]*"))
                throw new IllegalArgumentException("Benchmark-at-caret debug requires one positive CPU setting, not a repeated CPU list.");
        }
        if (!timing) args.add("-test.benchtime=1x");
        launch.put("args", List.copyOf(args));
        return Map.copyOf(launch);
    }
}

package dev.goforge.testing;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Records actual selected-name starts independently of source-navigation/rerun eligibility. */
final class GoTestSelectionObservation {
    private final GoTestSelection selection;
    private final Set<String> packages;
    private final Set<String> observed = new HashSet<>();
    GoTestSelectionObservation(GoTestSelection selection, Set<String> packages) {
        this.selection = selection; this.packages = Set.copyOf(packages);
    }
    void location(String packageName, String name, boolean inferred) {
        if (!inferred && selection != null && packageName != null && name != null
                && packages.contains(packageName) && selection.testNames().contains(name))
            observed.add(name);
    }
    List<String> missing() {
        return selection == null ? List.of() : selection.testNames().stream().filter(name -> !observed.contains(name)).toList();
    }
    void clear() { observed.clear(); }
}

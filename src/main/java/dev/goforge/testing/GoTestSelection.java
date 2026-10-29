package dev.goforge.testing;

import java.util.LinkedHashSet;
import java.util.List;

/** Literal Go test names within one package. Names identify requested subtrees, not data rows. */
public record GoTestSelection(List<String> testNames, boolean rerun, Kind kind) {
    public enum Kind { TESTS, BENCHMARK }
    public static final int MAX_NAMES = 128;
    public static final int MAX_PATTERN_LENGTH = 16_384;

    public GoTestSelection(List<String> testNames) { this(testNames, false, Kind.TESTS); }
    public GoTestSelection(List<String> testNames, boolean rerun) { this(testNames, rerun, Kind.TESTS); }

    public GoTestSelection {
        if (kind == null) throw new IllegalArgumentException("A named selection must have a kind.");
        if (testNames == null || testNames.isEmpty() || testNames.size() > MAX_NAMES)
            throw new IllegalArgumentException("Select between 1 and " + MAX_NAMES + " Test/Example names, or exactly one benchmark.");
        if (kind == Kind.BENCHMARK && (testNames.size() != 1 || rerun))
            throw new IllegalArgumentException("Select exactly one top-level benchmark without a test rerun flag.");
        var unique = new LinkedHashSet<String>();
        for (String name : testNames) {
            validateName(name, kind);
            unique.add(name);
        }
        testNames = List.copyOf(unique);
        if (GoTestSelector.pattern(testNames).length() > MAX_PATTERN_LENGTH)
            throw new IllegalArgumentException("The selected test filter is too long. Select fewer tests.");
    }

    public static GoTestSelection single(String name) { return new GoTestSelection(List.of(name)); }
    public static GoTestSelection rerun(List<String> names) { return new GoTestSelection(names, true); }
    public static GoTestSelection benchmark(String name) { return new GoTestSelection(List.of(name), false, Kind.BENCHMARK); }
    public static GoTestSelection declaration(String name, String kind) {
        if ("benchmark".equals(kind)) return benchmark(name);
        if (("test".equals(kind) && name != null && name.startsWith("Test"))
                || ("example".equals(kind) && name != null && name.startsWith("Example"))) return single(name);
        throw new IllegalArgumentException("The declaration kind does not match its runnable name.");
    }
    public boolean matchesDeclaration(GoTestSourceIndex.Declaration declaration) {
        return declaration != null && (kind == Kind.BENCHMARK ? "benchmark".equals(declaration.kind())
                : "test".equals(declaration.kind()) || "example".equals(declaration.kind()));
    }

    private static void validateName(String name, Kind kind) {
        if (name == null || name.isEmpty() || name.length() > MAX_PATTERN_LENGTH)
            throw new IllegalArgumentException("A selected name must not be empty or too long.");
        for (int offset = 0; offset < name.length();) {
            char ch = name.charAt(offset);
            if (Character.isLowSurrogate(ch) || (Character.isHighSurrogate(ch)
                    && (offset + 1 >= name.length() || !Character.isLowSurrogate(name.charAt(offset + 1)))))
                throw new IllegalArgumentException("A selected test name contains an invalid Unicode character.");
            int cp = name.codePointAt(offset);
            if (Character.isISOControl(cp))
                throw new IllegalArgumentException("Use the exact printable name from the test tree, without control characters.");
            offset += Character.charCount(cp);
        }
        String top = name.substring(0, name.indexOf('/') < 0 ? name.length() : name.indexOf('/'));
        boolean test = top.startsWith("Test") && (top.length() == 4 || Character.getType(top.codePointAt(4)) != Character.LOWERCASE_LETTER);
        boolean example = top.startsWith("Example") && (top.length() == 7 || Character.getType(top.codePointAt(7)) != Character.LOWERCASE_LETTER);
        boolean benchmark = top.startsWith("Benchmark") && (top.length() == 9 || Character.getType(top.codePointAt(9)) != Character.LOWERCASE_LETTER);
        if (kind == Kind.BENCHMARK && (!benchmark || name.indexOf('/') >= 0))
            throw new IllegalArgumentException("Select one top-level Benchmark name; subbenchmark and fuzz selections are not supported.");
        if (kind == Kind.TESTS && !test && !example)
            throw new IllegalArgumentException("This selection supports Test and Example functions. Choose Benchmark for a top-level benchmark, or Package / manual for fuzzing.");
        if (!top.codePoints().allMatch(cp -> cp == '_' || Character.isLetter(cp) || Character.isDigit(cp)))
            throw new IllegalArgumentException("The top-level test name must be a Go identifier.");
    }
}

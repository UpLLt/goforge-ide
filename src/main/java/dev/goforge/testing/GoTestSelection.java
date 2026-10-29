package dev.goforge.testing;

import java.util.LinkedHashSet;
import java.util.List;

/** Literal Go test names within one package. Names identify requested subtrees, not data rows. */
public record GoTestSelection(List<String> testNames, boolean rerun) {
    public static final int MAX_NAMES = 128;
    public static final int MAX_PATTERN_LENGTH = 16_384;

    public GoTestSelection(List<String> testNames) { this(testNames, false); }

    public GoTestSelection {
        if (testNames == null || testNames.isEmpty() || testNames.size() > MAX_NAMES)
            throw new IllegalArgumentException("Select between 1 and " + MAX_NAMES + " Test/Example names.");
        var unique = new LinkedHashSet<String>();
        for (String name : testNames) {
            validateName(name);
            unique.add(name);
        }
        testNames = List.copyOf(unique);
        if (GoTestSelector.pattern(testNames).length() > MAX_PATTERN_LENGTH)
            throw new IllegalArgumentException("The selected test filter is too long. Select fewer tests.");
    }

    public static GoTestSelection single(String name) { return new GoTestSelection(List.of(name)); }
    public static GoTestSelection rerun(List<String> names) { return new GoTestSelection(names, true); }

    private static void validateName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_PATTERN_LENGTH)
            throw new IllegalArgumentException("A selected Test/Example name must not be empty or too long.");
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
        if (!test && !example)
            throw new IllegalArgumentException("Named selection supports Test and Example functions; use Package / manual for benchmarks or fuzzing.");
        if (!top.codePoints().allMatch(cp -> cp == '_' || Character.isLetter(cp) || Character.isDigit(cp)))
            throw new IllegalArgumentException("The top-level test name must be a Go identifier.");
    }
}

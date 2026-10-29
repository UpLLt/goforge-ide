package dev.goforge.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Go test's slash-separated matcher uses a separate regexp for each literal path component. */
public final class GoTestSelector {
    private static final String META = "\\.+*?()|[]{}^$";
    private static final Set<String> CONFLICTING = Set.of("run", "bench", "list", "skip", "args", "count", "c", "n", "C", "h", "help", "json", "overlay");
    private static final Set<String> BOOLEAN_FLAGS = Set.of("a", "race", "msan", "asan", "cover", "v", "work", "x",
        "trimpath", "modcacherw", "linkshared", "short", "failfast", "fullpath", "benchmem", "artifacts", "buildvcs");
    private static final Set<String> VALUE_FLAGS = Set.of("p", "asmflags", "buildmode", "compiler", "gccgoflags",
        "gcflags", "installsuffix", "ldflags", "mod", "modfile", "overlay", "pkgdir", "tags", "toolexec", "pgo",
        "cpu", "parallel", "timeout", "exec", "o", "outputdir", "covermode", "coverpkg", "coverprofile", "vet",
        "cpuprofile", "memprofile", "memprofilerate", "blockprofile", "blockprofilerate", "mutexprofile",
        "mutexprofilefraction", "trace", "shuffle", "benchtime");

    private GoTestSelector() { }

    public static String pattern(GoTestSelection selection) { return pattern(selection.testNames()); }

    static String pattern(List<String> names) {
        // Alternation joins complete paths. Grouping the components independently would create cross-pairs.
        return names.stream().map(GoTestSelector::literalPath).collect(Collectors.joining("|"));
    }

    public static String literalPath(String name) {
        return java.util.Arrays.stream(name.split("/", -1)).map(GoTestSelector::literalSegment).collect(Collectors.joining("/"));
    }

    private static String literalSegment(String segment) {
        StringBuilder out = new StringBuilder("^");
        for (int i = 0; i < segment.length(); i++) {
            char ch = segment.charAt(i);
            if (META.indexOf(ch) >= 0) out.append('\\');
            out.append(ch);
        }
        return out.append('$').toString();
    }

    public static List<String> arguments(GoTestSelection selection) {
        if (selection.kind() == GoTestSelection.Kind.BENCHMARK)
            return List.of("-run=^$", "-bench=" + pattern(selection));
        return selection.rerun() ? List.of("-run=" + pattern(selection), "-count=1")
            : List.of("-run=" + pattern(selection));
    }

    public static void validateArguments(List<String> arguments, String effectiveGoFlags) {
        validateFlags(arguments, false, "Go arguments");
        validateGoFlags(effectiveGoFlags);
    }

    public static void validateGoFlags(String effectiveGoFlags) {
        validateFlags(splitGoFlags(effectiveGoFlags == null ? "" : effectiveGoFlags), true, "GOFLAGS");
    }

    private static void validateFlags(List<String> arguments, boolean environment, String source) {
        for (int i = 0; i < arguments.size(); i++) {
            String arg = arguments.get(i);
            if (arg == null || !arg.startsWith("-") || arg.equals("-") || arg.equals("--"))
                throw conflict(source, "contains a positional argument or '--'");
            int prefix = arg.startsWith("--") ? 2 : 1;
            int equals = arg.indexOf('=', prefix);
            String name = arg.substring(prefix, equals < 0 ? arg.length() : equals);
            if (name.isEmpty() || name.startsWith("-") || name.codePoints().anyMatch(cp -> Character.isWhitespace(cp) || Character.isISOControl(cp)))
                throw conflict(source, "contains an invalid flag name");
            if (name.startsWith("test.")) name = name.substring(5);
            if (CONFLICTING.contains(name) || name.startsWith("fuzz"))
                throw conflict(source, "contains conflicting flag " + arg.substring(0, equals < 0 ? arg.length() : equals));
            if (equals >= 0) continue;
            if (BOOLEAN_FLAGS.contains(name)) continue;
            if (VALUE_FLAGS.contains(name) && !environment) {
                if (++i >= arguments.size()) throw conflict(source, "is missing the value of " + arg);
                continue;
            }
            throw conflict(source, "requires the explicit -flag=value form for " + arg);
        }
    }

    private static IllegalArgumentException conflict(String source, String reason) {
        return new IllegalArgumentException(source + " " + reason
            + ". Remove the conflicting option or choose Package / manual. Named reruns use -count=1.");
    }

    /** GOFLAGS quotes enclose complete fields and do not unescape backslashes, unlike shell arguments. */
    public static List<String> splitGoFlags(String input) {
        var result = new ArrayList<String>();
        int cursor = 0;
        while (cursor < input.length()) {
            while (cursor < input.length() && isGoSpace(input.charAt(cursor))) cursor++;
            if (cursor == input.length()) break;
            char first = input.charAt(cursor);
            if (first == '\'' || first == '"') {
                int end = input.indexOf(first, cursor + 1);
                if (end < 0) throw conflict("GOFLAGS", "has an unterminated quoted field");
                result.add(input.substring(cursor + 1, end));
                cursor = end + 1;
            } else {
                int end = cursor;
                while (end < input.length() && !isGoSpace(input.charAt(end))) end++;
                result.add(input.substring(cursor, end));
                cursor = end;
            }
        }
        return List.copyOf(result);
    }

    private static boolean isGoSpace(char ch) { return ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n'; }
}

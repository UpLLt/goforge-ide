package dev.goforge.testing;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Preserves supported command-directory-relative flags when an observed package is selected. */
public final class GoTestRerunArguments {
    public record Context(Path directory, String goFlags) {
        public Context {
            if (!directory.isAbsolute()) throw new IllegalArgumentException("The original Go working directory must be absolute.");
            if (goFlags == null) throw new IllegalArgumentException("The original effective GOFLAGS must be recorded.");
        }
    }
    public record Rebased(List<String> arguments, String goFlags) {
        public Rebased { arguments = List.copyOf(arguments); }
    }
    private static final Set<String> PATH_FLAGS = Set.of("modfile", "pkgdir", "pgo");
    private static final Set<String> UNSUPPORTED = Set.of("overlay", "exec", "toolexec", "asmflags", "gcflags",
            "ldflags", "gccgoflags", "coverpkg", "coverprofile", "cpuprofile", "memprofile", "blockprofile",
            "mutexprofile", "trace", "outputdir", "o", "artifacts");
    private static final Set<String> VALUE_FLAGS = Set.of("p", "asmflags", "buildmode", "compiler", "gccgoflags",
            "gcflags", "installsuffix", "ldflags", "mod", "modfile", "overlay", "pkgdir", "tags", "toolexec", "pgo",
            "cpu", "parallel", "timeout", "exec", "o", "outputdir", "covermode", "coverpkg", "coverprofile", "vet",
            "cpuprofile", "memprofile", "memprofilerate", "blockprofile", "blockprofilerate", "mutexprofile",
            "mutexprofilefraction", "trace", "shuffle", "benchtime", "run", "bench", "skip", "count", "list",
            "fuzz", "fuzztime", "fuzzminimizetime");
    private static final Set<String> BOOLEAN_FLAGS = Set.of("a", "race", "msan", "asan", "cover", "v", "work", "x",
            "trimpath", "modcacherw", "linkshared", "short", "failfast", "fullpath", "benchmem", "artifacts",
            "buildvcs", "json", "c", "n");

    private GoTestRerunArguments() { }

    public static Rebased rebase(Context context, Path targetDirectory, List<String> arguments) {
        if (context.directory().equals(targetDirectory)) return new Rebased(arguments, context.goFlags());
        var goFlags = rewrite(context.directory(), GoTestSelector.splitGoFlags(context.goFlags()));
        return new Rebased(rewrite(context.directory(), arguments), joinGoFlags(goFlags));
    }

    private static List<String> rewrite(Path directory, List<String> arguments) {
        var result = new ArrayList<String>();
        for (int i = 0; i < arguments.size(); i++) {
            String token = arguments.get(i);
            if (!token.startsWith("-") || token.equals("-") || token.equals("--")) throw unsupported(token);
            String flag = token.substring(token.startsWith("--") ? 2 : 1);
            int equals = flag.indexOf('=');
            String name = equals < 0 ? flag : flag.substring(0, equals);
            String bare = name.startsWith("test.") ? name.substring(5) : name;
            if (UNSUPPORTED.contains(bare)) throw unsupported("-" + name);
            String value = equals < 0 ? null : flag.substring(equals + 1);
            if (value == null && VALUE_FLAGS.contains(bare)) {
                if (++i >= arguments.size()) throw new IllegalArgumentException("Missing value for " + token + ".");
                value = arguments.get(i);
            } else if (value == null && !BOOLEAN_FLAGS.contains(bare)) throw unsupported(token);
            if (PATH_FLAGS.contains(name) && value != null && !value.isEmpty()
                    && !(name.equals("pgo") && (value.equals("auto") || value.equals("off")))) {
                Path path = Path.of(value);
                // Do not normalize away '..' before the filesystem resolves any intervening symlink.
                if (!path.isAbsolute()) value = directory.resolve(path).toString();
            }
            result.add((token.startsWith("--") ? "--" : "-") + name + (value == null ? "" : "=" + value));
        }
        return List.copyOf(result);
    }

    /** cmd/internal/quoted.Join grammar: quote whole fields, never shell-escape their contents. */
    public static String joinGoFlags(List<String> fields) {
        var result = new ArrayList<String>();
        for (String field : fields) {
            boolean space = field.chars().anyMatch(c -> c == ' ' || c == '\t' || c == '\r' || c == '\n');
            boolean single = field.indexOf('\'') >= 0, doubleQuote = field.indexOf('"') >= 0;
            if (!space && !single && !doubleQuote) result.add(field);
            else if (!single) result.add("'" + field + "'");
            else if (!doubleQuote) result.add("\"" + field + "\"");
            else throw new IllegalArgumentException("Cannot preserve GOFLAGS containing both single and double quotes after changing the working directory. Create a named-test configuration directly in the package directory.");
        }
        return String.join(" ", result);
    }

    private static IllegalArgumentException unsupported(String flag) {
        return new IllegalArgumentException("Cannot preserve " + flag + " when rerunning from a different package directory. "
                + "Create a named-test configuration directly in that package directory and configure this option there.");
    }
}

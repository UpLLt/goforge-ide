package dev.goforge.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local, dependency-free starter projects. Existing directories are never overwritten. */
public final class GoProjectScaffolder {
    public enum Template {
        APPLICATION("Application"), LIBRARY("Library");
        private final String label;
        Template(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    private static final Set<String> KEYWORDS = Set.of("break", "default", "func", "interface", "select", "case",
        "defer", "go", "map", "struct", "chan", "else", "goto", "package", "switch", "const", "fallthrough",
        "if", "range", "type", "continue", "for", "import", "return", "var");
    private static final Set<String> RESERVED_PATH_SEGMENTS = Set.of("CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private GoProjectScaffolder() {}

    public static void validateModulePath(String module) {
        if (module == null || !module.matches("[A-Za-z0-9][A-Za-z0-9._~/-]*"))
            throw new IllegalArgumentException("Enter a Go module path, for example example.com/hello.");
        for (String part : module.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith("."))
                throw new IllegalArgumentException("The module path contains an empty or invalid path segment.");
            // Go applies these CheckImportPath/checkElem rules on every OS, also
            // when loading a local main module. Keep local paths without a dot valid.
            // https://github.com/golang/go/blob/go1.26.4/src/cmd/vendor/golang.org/x/mod/module/module.go
            int dot = part.indexOf('.');
            String shortName = dot < 0 ? part : part.substring(0, dot);
            if (RESERVED_PATH_SEGMENTS.contains(shortName.toUpperCase(java.util.Locale.ROOT)))
                throw new IllegalArgumentException("Go does not allow this module path segment: " + part);
            int tilde = shortName.lastIndexOf('~');
            if (tilde >= 0 && shortName.substring(tilde + 1).matches("[0-9]+"))
                throw new IllegalArgumentException("Go does not allow path segments ending with a tilde and digits: " + part);
        }
    }

    public static String packageName(String module) {
        validateModulePath(module);
        String[] parts = module.split("/");
        int last = parts.length - 1;
        if (last > 0 && parts[last].matches("v(?:[2-9]|[1-9][0-9]+)")) last--;
        String name = parts[last].replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(java.util.Locale.ROOT);
        if (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_') name = "pkg_" + name;
        if (name.equals("_") || name.equals("main") || KEYWORDS.contains(name)) name += "pkg";
        return name;
    }

    public static List<Path> create(Path target, String module, Template template, String goVersion) throws IOException {
        validateModulePath(module);
        if (template == null) throw new IllegalArgumentException("Choose an application or library template.");
        if (goVersion == null || !goVersion.matches("[1-9][0-9]*\\.[0-9]+"))
            throw new IllegalArgumentException("Cannot determine the selected Go SDK version.");
        target = target.toAbsolutePath().normalize();
        if (target.getParent() == null || !Files.isDirectory(target.getParent()))
            throw new IllegalArgumentException("Choose an existing parent directory.");
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("go.mod", "module " + module + "\n\ngo " + goVersion + "\n");
        String pkg = template == Template.APPLICATION ? "main" : packageName(module);
        String greeting = "func Hello(name string) string {\n\treturn \"Hello, \" + name + \"!\"\n}\n";
        if (template == Template.APPLICATION) {
            sources.put("main.go", "package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Println(Hello(\"GoForge\"))\n}\n\n" + greeting);
        } else sources.put("hello.go", "// Package " + pkg + " provides greeting helpers.\npackage " + pkg + "\n\n" + greeting);
        sources.put(template == Template.APPLICATION ? "main_test.go" : "hello_test.go", "package " + pkg
            + "\n\nimport \"testing\"\n\nfunc TestHello(t *testing.T) {\n\tif got := Hello(\"GoForge\"); got != \"Hello, GoForge!\" {\n\t\tt.Fatalf(\"unexpected greeting: %q\", got)\n\t}\n}\n");
        sources.put(".gitignore", "/.idea/\n/bin/\n*.test\ncoverage.out\n");
        // createDirectory fails atomically for files, symlinks and even empty existing directories.
        Files.createDirectory(target);
        List<Path> created = new ArrayList<>();
        try {
            for (var entry : sources.entrySet()) {
                Path file = target.resolve(entry.getKey());
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                created.add(file);
            }
            return List.copyOf(created);
        } catch (IOException | RuntimeException error) {
            // Only remove files this invocation created; never recursively delete unrelated content.
            for (Path file : created.reversed()) try { Files.deleteIfExists(file); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            try { Files.delete(target); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
}

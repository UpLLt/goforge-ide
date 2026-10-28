package dev.goforge.coverage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonStreamParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Exact import-path/file mapping from go list; never searches by basename. */
public final class GoCoverageSourceMap {
    public record Source(Path path, String text) {
        public boolean matches(String current) { return text.equals(normalize(current)); }
    }
    private final Map<String, Source> files;

    private GoCoverageSourceMap(Map<String, Source> files) { this.files = Map.copyOf(files); }
    public Source resolve(String profileName) { return files.get(profileName); }
    public Collection<Source> sources() { return files.values().stream().distinct().toList(); }
    public static String normalize(String text) { return text.replace("\r\n", "\n"); }

    public static GoCoverageSourceMap parse(Reader json, Path moduleRoot) throws IOException {
        Path root = moduleRoot.toRealPath();
        var files = new HashMap<String, Source>();
        var snapshots = new HashMap<Path, Source>();
        try {
            var input = new JsonStreamParser(json);
            while (input.hasNext()) {
                JsonObject pkg = input.next().getAsJsonObject();
                if (pkg.has("Error")) throw new IOException("go list reported a package error: " + pkg.get("Error"));
                String importPath = required(pkg, "ImportPath");
                Path directory = Path.of(required(pkg, "Dir")).toRealPath();
                JsonObject module = pkg.getAsJsonObject("Module");
                if (module == null || !Path.of(required(module, "Dir")).toRealPath().equals(root)
                        || !directory.startsWith(root))
                    throw new IOException("go list returned a package outside the selected module: " + importPath);
                for (String key : List.of("GoFiles", "CgoFiles")) {
                    if (!pkg.has(key)) continue;
                    for (JsonElement element : pkg.getAsJsonArray(key)) {
                        String name = element.getAsString();
                        Path relative = Path.of(name);
                        if (relative.isAbsolute() || !relative.normalize().equals(relative) || relative.startsWith(".."))
                            throw new IOException("go list returned a non-local source filename: " + name);
                        Path path = directory.resolve(relative).toRealPath();
                        if (!path.startsWith(root) || !Files.isRegularFile(path))
                            throw new IOException("Source file escapes the selected module: " + name);
                        Source source = snapshots.get(path);
                        if (source == null) {
                            if (Files.size(path) > 10 * 1024 * 1024) throw new IOException("Source snapshot is larger than 10 MiB: " + path);
                            source = new Source(path, normalize(Files.readString(path)));
                            snapshots.put(path, source);
                        }
                        putUnique(files, importPath + "/" + name.replace('\\', '/'), source);
                        // Absolute names are accepted only when go list proved this exact file.
                        putUnique(files, path.toString(), source);
                    }
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("Cannot parse go list package metadata: " + error.getMessage(), error);
        }
        return new GoCoverageSourceMap(files);
    }

    private static String required(JsonObject object, String key) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive() || object.get(key).getAsString().isBlank())
            throw new IOException("go list metadata is missing " + key + ".");
        return object.get(key).getAsString();
    }

    private static void putUnique(Map<String, Source> files, String key, Source source) throws IOException {
        Source old = files.putIfAbsent(key, source);
        if (old != null && !old.path().equals(source.path())) throw new IOException("Ambiguous coverage path: " + key);
    }
}

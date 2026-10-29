package dev.goforge.remote;

import com.google.gson.JsonObject;
import java.util.*;

/** Selected module identities only. A go.sum-only version never enters this catalogue. */
final class GoRemoteModuleSources {
    record Module(String path, String version, String root, String token) { }
    private final Map<String,Module> byToken = new LinkedHashMap<>();
    private boolean initialized;
    private String unavailable;
    void initialize(JsonObject value) {
        if (initialized) throw new IllegalArgumentException("Remote module catalogue was repeated.");
        initialized = true;
        if (value != null && value.keySet().equals(Set.of("error"))) {
            unavailable = GoRemoteStdlibSources.required(value, "error");
            if (unavailable.length() > 4096) throw new IllegalArgumentException("Invalid module discovery error.");
            return;
        }
        if (value == null || !value.keySet().equals(Set.of("cacheRoot", "modules"))) throw new IllegalArgumentException("Invalid module catalogue.");
        String cache = GoRemoteStdlibSources.required(value, "cacheRoot");
        if (!cache.startsWith("/") || cache.endsWith("/")) throw new IllegalArgumentException("Invalid remote module cache root.");
        GoRemoteStdlibSources.segments(cache.substring(1), false);
        var entries = value.getAsJsonArray("modules");
        if (entries == null || entries.size() > 256) throw new IllegalArgumentException("Invalid remote module catalogue.");
        var seen = new HashSet<String>(); var prepared = new LinkedHashMap<String,Module>();
        for (var entry : entries) {
            var row = entry.getAsJsonObject();
            if (!row.keySet().equals(Set.of("path", "version", "root", "token"))) throw new IllegalArgumentException("Invalid selected module identity.");
            String path = GoRemoteStdlibSources.required(row, "path"), version = GoRemoteStdlibSources.required(row, "version");
            String root = GoRemoteStdlibSources.required(row, "root"), token = GoRemoteStdlibSources.required(row, "token");
            if (!version.matches("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?(?:\\+incompatible)?")
                    || !root.equals(cache + "/" + escape(path) + "@" + escape(version)) || !token.matches("[a-f0-9]{64}")
                    || !seen.add(path) || prepared.containsKey(token)) throw new IllegalArgumentException("Ambiguous selected module path/version.");
            prepared.put(token, new Module(path, version, root, token));
        }
        byToken.putAll(prepared);
    }
    Module matching(String uri) {
        if (!initialized) throw new IllegalArgumentException("Remote dependency source lookup is still loading; retry after discovery finishes.");
        if (unavailable != null) throw new IllegalArgumentException(unavailable);
        String path = GoRemoteStdlibSources.filePath(uri);
        for (var module : byToken.values()) if (path.startsWith(module.root()+"/")) return module;
        throw new IllegalArgumentException("The definition is outside the selected, checksum-bound remote modules and SDK.");
    }
    boolean contains(GoRemoteStdlibSources.Target target) {
        var module = byToken.get(target.token());
        return module != null && module.root().equals(target.root())
                && target.origin().equals(new GoRemoteStdlibSources.Origin(GoRemoteStdlibSources.Kind.MODULE, module.path(), module.version()));
    }
    void clear() { byToken.clear(); }
    static String escape(String value) {
        if (!value.matches("[A-Za-z0-9._~+/-]+")) throw new IllegalArgumentException("Unsupported module identity.");
        GoRemoteStdlibSources.segments(value, false);
        var result = new StringBuilder();
        for (char c : value.toCharArray()) { if (c >= 'A' && c <= 'Z') result.append('!').append(Character.toLowerCase(c)); else result.append(c); }
        return result.toString();
    }
}

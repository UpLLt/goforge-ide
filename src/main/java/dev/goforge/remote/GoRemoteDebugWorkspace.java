package dev.goforge.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.goforge.tools.HostTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded, relocatable workspace binding. Go itself parses workspace/module syntax; no files are edited. */
public record GoRemoteDebugWorkspace(String workFile, List<String> modules, List<String> localReplacements) {
    public GoRemoteDebugWorkspace {
        if (!workFile.equals("") && !workFile.equals("go.work")) throw new IllegalArgumentException("Only a project-root go.work is supported.");
        modules = List.copyOf(modules); localReplacements = List.copyOf(localReplacements);
        if (modules.isEmpty() || modules.size() > 32 || localReplacements.size() > 64)
            throw new IllegalArgumentException("Review supports 1–32 workspace modules and at most 64 local replacements.");
        for (String path : modules) relativeRoot(path);
        for (String path : localReplacements) relativeRoot(path);
        if (new HashSet<>(modules).size() != modules.size()) throw new IllegalArgumentException("Duplicate workspace modules.");
        if (workFile.isEmpty() && (!modules.equals(List.of(".")) || !localReplacements.isEmpty()))
            throw new IllegalArgumentException("Single-module binding must use the project root.");
    }
    public static GoRemoteDebugWorkspace singleModule() { return new GoRemoteDebugWorkspace("", List.of("."), List.of()); }
    public boolean enabled() { return !workFile.isEmpty(); }
    private static void relativeRoot(String root) { if (!root.equals(".")) GoRemoteProfile.relative(root); }
    static String modFile(String root) { return root.equals(".") ? "go.mod" : root + "/go.mod"; }
    void requireFiles(Map<String,String> files) {
        if (enabled() && !files.containsKey(workFile)) throw new IllegalArgumentException("Workspace file is not in the saved source review.");
        for (String module : modules) if (!files.containsKey(modFile(module)))
            throw new IllegalArgumentException("Workspace module go.mod is outside the saved source review: " + module);
        for (String replacement : localReplacements) if (!files.containsKey(modFile(replacement)))
            throw new IllegalArgumentException("Local replacement go.mod is outside the saved source review: " + replacement);
    }
    public static GoRemoteDebugWorkspace inspect(String go, Path directory, Map<String,String> files,
            Map<String,String> environment, BooleanSupplier cancelled) throws IOException {
        Path root = directory.toRealPath();
        if (!files.containsKey("go.work")) {
            if (Files.exists(root.resolve("go.work"))) throw new IOException("The project go.work was excluded from source review.");
            var single = singleModule(); single.requireFiles(files); return single;
        }
        var env = new HashMap<>(environment);
        HostTools.putEnvironment(env, Map.of("GOWORK","off","GOENV","off","GOFLAGS","","GOTOOLCHAIN","local","GOPROXY","off","GOSUMDB","off"));
        Metadata reader = (kind, file) -> {
            if (cancelled.getAsBoolean()) throw new IOException("Workspace review cancelled.");
            GoRemoteProcess.Result result;
            try { result = GoRemoteProcess.run(List.of(go,kind,"edit","-json",file.toString()), root, env, new byte[0], false, cancelled, Duration.ofSeconds(15), ignored -> {}); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Workspace review interrupted.", e); }
            if (!result.success()) throw new IOException("Cannot inspect saved " + file.getFileName() + ": " + result.failure() + "\n" + result.stderr());
            try { return JsonParser.parseString(result.stdout()).getAsJsonObject(); }
            catch (RuntimeException e) { throw new IOException("Go returned invalid workspace metadata.", e); }
        };
        return inspect(root, files, reader);
    }
    @FunctionalInterface interface Metadata { JsonObject read(String kind, Path file) throws IOException; }
    static GoRemoteDebugWorkspace inspect(Path root, Map<String,String> files, Metadata reader) throws IOException {
        JsonObject work = reader.read("work", root.resolve("go.work"));
        var modules = new TreeSet<String>(); var replacements = new TreeSet<String>();
        for (var entry : array(work,"Use")) {
            String module = boundedDirectory(root, root, entry.getAsJsonObject().get("DiskPath").getAsString(), files);
            if (!modules.add(module)) throw new IOException("Duplicate workspace module: " + module);
        }
        if (modules.isEmpty() || modules.size() > 32) throw new IOException("Review supports 1–32 workspace modules.");
        collectReplacements(root,root,work,files,replacements);
        for (String module : modules) {
            Path directory = root.resolve(module);
            collectReplacements(root,directory,reader.read("mod",directory.resolve("go.mod")),files,replacements);
        }
        var binding = new GoRemoteDebugWorkspace("go.work", List.copyOf(modules), List.copyOf(replacements));
        binding.requireFiles(files); return binding;
    }
    private static JsonArray array(JsonObject object,String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.getAsJsonArray(name) : new JsonArray();
    }
    private static void collectReplacements(Path root,Path base,JsonObject json,Map<String,String> files,Set<String> result) throws IOException {
        for (var entry : array(json,"Replace")) {
            JsonObject target = entry.getAsJsonObject().getAsJsonObject("New");
            if (target.has("Version") && !target.get("Version").getAsString().isEmpty()) continue;
            result.add(boundedDirectory(root,base,target.get("Path").getAsString(),files));
            if (result.size() > 64) throw new IOException("Workspace has more than 64 local replacements.");
        }
    }
    static String boundedDirectory(Path root,Path base,String value,Map<String,String> files) throws IOException {
        GoRemoteProfile.noControls(value);
        // Absolute paths (including Windows spelling on another OS) cannot be relocated safely.
        if (value.isBlank() || value.startsWith("/") || value.contains("\\") || value.matches("^[A-Za-z]:.*"))
            throw new IOException("Workspace use/replace must be relative to its declaring file: " + value);
        Path resolved = base.resolve(value).normalize();
        if (!resolved.startsWith(root)) throw new IOException("Workspace use/replace leaves the reviewed root: " + value);
        Path cursor=root;
        for (Path part : root.relativize(resolved)) {cursor=cursor.resolve(part);if(Files.isSymbolicLink(cursor))throw new IOException("Workspace directory contains a symlink: " + value);}
        if (!Files.isDirectory(resolved) || !resolved.toRealPath().equals(resolved)) throw new IOException("Workspace module is missing or redirected: " + value);
        String relative=root.relativize(resolved).toString().replace('\\','/');if(relative.isEmpty())relative=".";
        if (!files.containsKey(modFile(relative))) throw new IOException("Workspace module go.mod is outside the saved source review: " + value);
        return relative;
    }
}

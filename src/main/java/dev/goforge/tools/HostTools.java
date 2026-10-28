package dev.goforge.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** Host executable discovery; arguments are still passed directly, never through a shell. */
public final class HostTools {
    private HostTools() { }
    public static boolean isWindows() { return System.getProperty("os.name", "").startsWith("Windows"); }
    public static String executableName(String name) { return executableName(name, isWindows()); }
    static String executableName(String name, boolean windows) {
        return windows && !name.toLowerCase(Locale.ROOT).endsWith(".exe") ? name + ".exe" : name;
    }
    public static String environmentValue(Map<String, String> environment, String name) {
        return environmentValue(environment, name, isWindows());
    }
    static String environmentValue(Map<String, String> environment, String name, boolean windows) {
        String exact = environment.get(name);
        if (exact != null) return exact;
        if (windows) for (var entry : environment.entrySet())
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        return "";
    }
    public static List<Path> pathCandidates(String executable, Map<String, String> environment) {
        return pathCandidates(executable, environment, isWindows());
    }
    static List<Path> pathCandidates(String executable, Map<String, String> environment, boolean windows) {
        var paths = new ArrayList<Path>();
        for (String directory : environmentValue(environment, "PATH", windows).split(Pattern.quote(windows ? ";" : ":"))) {
            if (windows && directory.length() >= 2 && directory.startsWith("\"") && directory.endsWith("\""))
                directory = directory.substring(1, directory.length() - 1);
            // Relative/empty PATH entries would implicitly execute from the current project.
            if (!directory.isBlank()) {
                try { Path path = Path.of(directory); if (path.isAbsolute()) paths.add(path.resolve(executableName(executable, windows))); }
                catch (java.nio.file.InvalidPathException invalidEntry) { /* Ignore unusable search entries. */ }
            }
        }
        return List.copyOf(paths);
    }
    public static Map<String, String> withPrependedPath(Map<String, String> environment, Path directory) {
        return withPrependedPath(environment, directory, isWindows());
    }
    static Map<String, String> withPrependedPath(Map<String, String> environment, Path directory, boolean windows) {
        String previous = environmentValue(environment, "PATH", windows);
        var result = new LinkedHashMap<>(environment);
        result.keySet().removeIf(key -> windows ? key.equalsIgnoreCase("PATH") : key.equals("PATH"));
        result.put("PATH", directory + (previous.isEmpty() ? "" : (windows ? ";" : ":") + previous));
        return result;
    }
    public static void putEnvironment(Map<String, String> target, Map<String, String> additions) {
        putEnvironment(target, additions, isWindows());
    }
    static void putEnvironment(Map<String, String> target, Map<String, String> additions, boolean windows) {
        // ProcessBuilder environment maps may retain differently-cased Windows keys.
        for (var entry : additions.entrySet()) {
            if (windows) target.keySet().removeIf(key -> key.equalsIgnoreCase(entry.getKey()));
            target.put(entry.getKey(), entry.getValue());
        }
    }
    public static String resolve(String configured, Map<String, String> environment) throws IOException {
        return resolve(configured, environment, isWindows());
    }
    static String resolve(String configured, Map<String, String> environment, boolean windows) throws IOException {
        String value = configured == null ? "" : configured.trim();
        if (value.isEmpty() || value.startsWith("-") || value.chars().anyMatch(Character::isISOControl))
            throw new IOException("Enter a native executable name or path.");
        if (value.contains("/") || value.contains("\\")) {
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (executable(path)) return path.toString();
            throw new IOException("Executable file was not found or is not executable: " + path);
        }
        var candidates = new ArrayList<>(pathCandidates(value, environment, windows));
        if (windows && (value.equalsIgnoreCase("ssh") || value.equalsIgnoreCase("ssh.exe"))) {
            String systemRoot = environmentValue(environment, "SystemRoot", true);
            if (!systemRoot.isBlank()) candidates.add(Path.of(systemRoot, "System32", "OpenSSH", "ssh.exe"));
        }
        for (Path path : candidates) if (executable(path)) return path.toAbsolutePath().toString();
        throw new IOException("Cannot find " + executableName(value, windows) + ". Install it or enter its executable path.");
    }
    private static boolean executable(Path path) { return Files.isRegularFile(path) && Files.isExecutable(path); }
}

package dev.goforge.tools;

import com.intellij.openapi.application.PathManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Tool discovery does not download, install or execute anything. */
public final class GoToolResolver {
    private GoToolResolver() {}

    public static String resolve(String configured, String tool, String projectPath, Map<String, String> environment) {
        return resolve(configured, tool, projectPath, environment,
            Path.of(PathManager.getHomePath(), "goforge-tools", "bin"));
    }

    static String resolve(String configured, String tool, String projectPath, Map<String, String> environment, Path bundledTools) {
        if (configured != null && !configured.isBlank()) {
            Path explicit = Path.of(configured.trim());
            if (!explicit.isAbsolute() || !isExecutable(explicit))
                throw new IllegalStateException("Configured " + tool + " is not an executable file: " + configured);
            return explicit.toString();
        }
        String executable = HostTools.executableName(tool);
        List<Path> candidates = new ArrayList<>();
        String tools = HostTools.environmentValue(environment, "GOFORGE_TOOLS_DIR");
        if (tools != null && !tools.isBlank()) candidates.add(Path.of(tools).resolve(executable));
        if (projectPath != null) candidates.add(Path.of(projectPath, ".tools", "bin", executable));
        if (bundledTools != null) {
            // Execute the actual SDK binary; a wrapper would become an extra cancellation boundary.
            if (tool.equals("go") && bundledTools.getParent() != null)
                candidates.add(bundledTools.getParent().resolve("go/bin").resolve(executable));
            candidates.add(bundledTools.resolve(executable));
        }
        candidates.addAll(HostTools.pathCandidates(tool, environment));
        for (Path candidate : candidates) if (isExecutable(candidate)) return candidate.toAbsolutePath().toString();
        throw new IllegalStateException("Cannot find " + tool + ". Choose its executable in Settings → GoForge.");
    }

    private static boolean isExecutable(Path path) { return Files.isRegularFile(path) && Files.isExecutable(path); }
}

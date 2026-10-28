package dev.goforge.debug;

import com.google.gson.Gson;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Creates ready-to-run package configurations for a newly generated Go project. */
public final class GoDebugConfigurations {
    private GoDebugConfigurations() {}

    public static List<RunnerAndConfigurationSettings> addForProject(
            Project project, Path directory, boolean application) throws IOException {
        // Delve runs go build from the canonical cwd. Its absolute package target
        // must use that same path, including macOS /tmp and /var symlinks.
        Path absoluteDirectory = directory.toRealPath();
        var manager = RunManager.getInstance(project);
        var factory = new DAPRunConfigurationProvider().getConfigurationFactory();
        String path = absoluteDirectory.toString();
        String projectName = absoluteDirectory.getFileName() == null ? path : absoluteDirectory.getFileName().toString();
        var created = new ArrayList<RunnerAndConfigurationSettings>();
        for (boolean tests : application ? new boolean[]{false, true} : new boolean[]{true}) {
            String name = (tests ? "Debug tests " : "Debug ") + projectName;
            var settings = manager.createConfiguration(name, factory);
            var configuration = (DAPRunConfiguration) settings.getConfiguration();
            configuration.setServerId("goforge.delve");
            configuration.setServerName("GoForge — Delve");
            configuration.setDebugMode(DebugMode.LAUNCH);
            configuration.setWorkingDirectory(path);
            configuration.setLaunchConfigurationId(tests ? "goforge_tests" : "goforge_package");
            configuration.setLaunchConfiguration(new Gson().toJson(Map.of(
                    "type", "go", "name", name, "request", "launch",
                    "mode", tests ? "test" : "debug", "program", path, "cwd", path)));
            manager.addConfiguration(settings);
            created.add(settings);
        }
        return List.copyOf(created);
    }
}

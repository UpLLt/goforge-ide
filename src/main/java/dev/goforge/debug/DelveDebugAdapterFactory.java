package dev.goforge.debug;

import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.LaunchConfiguration;
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor;
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptorFactory;
import org.jetbrains.annotations.NotNull;
import dev.goforge.run.GoSourcePackage;

import java.util.List;
import java.util.Map;

public final class DelveDebugAdapterFactory extends DebugAdapterDescriptorFactory {
    @Override public DebugAdapterDescriptor createDebugAdapterDescriptor(@NotNull RunConfigurationOptions options,
            @NotNull ExecutionEnvironment environment) {
        return new DelveDebugAdapterDescriptor(options, environment, getServerDefinition());
    }

    @Override public boolean isDebuggableFile(@NotNull VirtualFile file, @NotNull Project project) {
        return "go".equals(file.getExtension());
    }

    @Override public boolean prepareConfiguration(@NotNull RunConfiguration configuration,
            @NotNull VirtualFile file, @NotNull Project project) {
        if (!(configuration instanceof DAPRunConfiguration dap) || !isDebuggableFile(file, project)
                || file.getParent() == null) return false;
        boolean tests = file.getName().endsWith("_test.go");
        var psi = PsiManager.getInstance(project).findFile(file);
        if (!tests && (psi == null || !"main".equals(GoSourcePackage.name(psi.getText())))) return false;
        if (!super.prepareConfiguration(configuration, file, project)) return false;
        String directory = file.getParent().getPath();
        dap.setWorkingDirectory(directory);
        dap.setLaunchConfigurationId(tests ? "goforge_tests" : "goforge_package");
        dap.setLaunchConfiguration(new com.google.gson.Gson().toJson(Map.of(
            "type", "go", "request", "launch", "mode", tests ? "test" : "debug",
            "program", directory, "cwd", directory)));
        dap.setName((tests ? "debug tests " : "debug ") + file.getParent().getName());
        return true;
    }

    @Override public @NotNull List<LaunchConfiguration> getLaunchConfigurations() {
        return List.of(
            new LaunchConfiguration("goforge_package", "Debug Go package", """
                {"type":"go","name":"Debug Go package","request":"launch","mode":"debug", 
                 "program":"${workspaceFolder}","cwd":"${workspaceFolder}"}
                """, DebugMode.LAUNCH),
            new LaunchConfiguration("goforge_tests", "Debug Go tests", """
                {"type":"go","name":"Debug Go tests","request":"launch","mode":"test",
                 "program":"${workspaceFolder}","cwd":"${workspaceFolder}"}
                """, DebugMode.LAUNCH));
    }
}

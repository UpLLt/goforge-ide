package dev.goforge.debug;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationProvider;
import dev.goforge.remote.GoRemoteDebugPlan;

public final class GoRemoteDebugConfigurations {
    private GoRemoteDebugConfigurations() {}
    public static RunnerAndConfigurationSettings create(Project project,GoRemoteDebugPlan plan){
        String name=plan.attach()?"Remote attach PID "+plan.processId():"Remote "+(plan.mode().equals("test")?"debug tests ":"debug ")+plan.target();
        var manager=RunManager.getInstance(project);var factory=new DAPRunConfigurationProvider().getConfigurationFactory();
        // Sessions are deliberately not restorable: persisted DAP configurations retain only an
        // expiring session ID and the descriptor refuses them until the user reviews a new plan.
        var settings=manager.createConfiguration(new RemoteDapRunConfiguration(project,factory,name),factory);
        var config=(DAPRunConfiguration)settings.getConfiguration();config.setServerId("goforge.remoteDelve");config.setServerName("GoForge — Remote Delve (SSH)");
        // LSP4IJ's outer ATTACH skips startServer. The descriptor's clientMode selects the actual attach request and detach policy.
        config.setDebugMode(DebugMode.LAUNCH);config.setWorkingDirectory(plan.localRoot());config.setLaunchConfigurationId("goforge_remote_"+plan.mode());
        String session=dev.goforge.remote.GoRemoteDebugSessions.getInstance(project).register(plan);
        config.setLaunchConfiguration(new com.google.gson.Gson().toJson(java.util.Map.of("type","go","request","launch","goforgeRemoteSession",session)));
        config.setServerTrace(com.redhat.devtools.lsp4ij.settings.ServerTrace.off);return settings;
    }
}

package dev.goforge.debug;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.filters.TextConsoleBuilderImpl;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import org.jetbrains.annotations.NotNull;

/** Reviewed, in-memory SSH sessions use the normal DAP runner with a transport-aware console. */
final class RemoteDapRunConfiguration extends DAPRunConfiguration {
    RemoteDapRunConfiguration(Project project, ConfigurationFactory factory, String name) {
        super(project, factory, name);
    }

    @Override public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) {
        var state = (DAPCommandLineState) super.getState(executor, environment);
        // The ordinary DAP settings editor can change the selected adapter. Its stdout may be
        // user-visible diagnostics, so only our verified SSH bridge owns the transport-only rule.
        if (!(state.getServerDescriptor() instanceof RemoteDelveDebugAdapterDescriptor)) return state;
        state.setConsoleBuilder(new TextConsoleBuilderImpl(getProject()) {
            @Override protected @NotNull ConsoleView createConsole() {
                return new RemoteDapConsoleView(getProject(), getScope(), isViewer(), isUsePredefinedMessageFilter());
            }
        });
        return state;
    }
}

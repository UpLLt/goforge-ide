package dev.goforge.debug;

import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.redhat.devtools.lsp4ij.dap.definitions.DebugAdapterServerDefinition;
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor;
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptorFactory;
import org.jetbrains.annotations.NotNull;

public final class RemoteDelveDebugAdapterFactory extends DebugAdapterDescriptorFactory {
    @Override public @NotNull DebugAdapterDescriptor createDebugAdapterDescriptor(@NotNull RunConfigurationOptions options,
            @NotNull ExecutionEnvironment environment){
        return new RemoteDelveDebugAdapterDescriptor(options,environment,getServerDefinition());
    }
    // Remote coordinates must be reviewed from the Remote tool window, never inferred from a gutter action.
    @Override public boolean isDebuggableFile(@NotNull VirtualFile file,@NotNull Project project){return false;}
}

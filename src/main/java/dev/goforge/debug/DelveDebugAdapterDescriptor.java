package dev.goforge.debug;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.configurations.options.WorkingDirectoryConfigurable;
import com.redhat.devtools.lsp4ij.dap.definitions.DebugAdapterServerDefinition;
import com.redhat.devtools.lsp4ij.dap.descriptors.DefaultDebugAdapterDescriptor;
import com.redhat.devtools.lsp4ij.dap.descriptors.ServerReadyConfig;
import dev.goforge.language.GoFileType;
import dev.goforge.settings.GoForgeSettings;
import dev.goforge.settings.GoProjectSettings;
import dev.goforge.tools.GoToolResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public final class DelveDebugAdapterDescriptor extends DefaultDebugAdapterDescriptor {
    public DelveDebugAdapterDescriptor(@NotNull RunConfigurationOptions options,
            @NotNull ExecutionEnvironment environment, @NotNull DebugAdapterServerDefinition definition) {
        super(options, environment, definition);
    }

    @Override public ProcessHandler startServer() throws ExecutionException {
        com.intellij.openapi.progress.ProgressManager.checkCanceled();
        GoSelectedTestDebugGuard.validate(environment);
        try {
            var settings = GoForgeSettings.getInstance();
            var command = new GeneralCommandLine().withCharset(StandardCharsets.UTF_8);
            var envData = getEnvData();
            if (envData != null) {
                command.withEnvironment(envData.getEnvs());
                if (!envData.isPassParentEnvs()) command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE);
            }
            var projectDefaults = GoProjectSettings.getInstance(environment.getProject());
            command.withEnvironment(projectDefaults.buildEnvironment(command.getEffectiveEnvironment()));
            if (envData != null) command.withEnvironment(envData.getEnvs());
            String projectPath = environment.getProject().getBasePath();
            var env = command.getEffectiveEnvironment();
            String delve = GoToolResolver.resolve(settings.delvePath, "dlv", projectPath, env);
            String go = GoToolResolver.resolve(settings.goPath, "go", projectPath, env);
            // Delve chooses a free loopback port; LSP4IJ reads the actual address from its readiness message.
            command.withExePath(delve).withParameters("dap", "--listen=127.0.0.1:0");
            command.withEnvironment(dev.goforge.tools.HostTools.withPrependedPath(env, Path.of(go).getParent()));
            if (options instanceof WorkingDirectoryConfigurable working && working.getWorkingDirectory() != null
                    && !working.getWorkingDirectory().isBlank()) command.withWorkDirectory(working.getWorkingDirectory());
            var handler = startServer(command);
            try {
                handler.setShouldDestroyProcessRecursively(true);
                com.intellij.openapi.progress.ProgressManager.checkCanceled();
                if (environment.getProject().isDisposed()) throw new ExecutionException("The debug project was closed.");
                return handler;
            } catch (ExecutionException | RuntimeException | Error failure) {
                handler.destroyProcess();
                throw failure;
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ExecutionException(e.getMessage(), e);
        }
    }

    @Override public @NotNull ServerReadyConfig getServerReadyConfig(@NotNull DebugMode mode) {
        return mode == DebugMode.ATTACH ? super.getServerReadyConfig(mode)
            : new ServerReadyConfig("DAP server listening at: ${address}:${port}");
    }
    @Override public FileType getFileType() { return GoFileType.INSTANCE; }
    @Override public @NotNull XDebuggerEditorsProvider createDebuggerEditorsProvider(
            @Nullable FileType fileType, @NotNull DAPDebugProcess debugProcess) {
        return new GoDebuggerEditorsProvider(debugProcess);
    }
    @Override public boolean isDebuggableFile(@NotNull VirtualFile file, @NotNull Project project) {
        return "go".equals(file.getExtension());
    }
}

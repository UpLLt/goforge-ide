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
import java.util.Map;
import java.util.LinkedHashMap;
import com.redhat.devtools.lsp4ij.dap.client.DAPClient;
import com.redhat.devtools.lsp4ij.settings.ServerTrace;
import com.intellij.execution.process.ProcessTerminatedListener;

public final class DelveDebugAdapterDescriptor extends DefaultDebugAdapterDescriptor {
    private GoDelveSessionOutput generatedOutput;
    private GoDelveProcessHandler ownedHandler;
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
            com.intellij.execution.process.OSProcessHandler handler;
            if (GoDelveSessionOutput.required(super.getDapParameters(), getDebugMode() == DebugMode.LAUNCH)) {
                generatedOutput = GoDelveSessionOutput.create(Path.of(System.getProperty("java.io.tmpdir")));
                ownedHandler = GoDelveProcessHandler.start(command, generatedOutput);
                handler = ownedHandler;
                ProcessTerminatedListener.attach(handler);
            } else handler = startServer(command);
            try {
                handler.setShouldDestroyProcessRecursively(true);
                com.intellij.openapi.progress.ProgressManager.checkCanceled();
                if (environment.getProject().isDisposed()) throw new ExecutionException("The debug project was closed.");
                return handler;
            } catch (ExecutionException | RuntimeException | Error failure) {
                if (!handler.isStartNotified()) handler.startNotify();
                handler.destroyProcess();
                throw failure;
            }
        } catch (java.io.IOException | IllegalArgumentException | IllegalStateException e) {
            throw new ExecutionException(e.getMessage(), e);
        }
    }

    @Override public @NotNull DAPClient createClient(@NotNull DAPDebugProcess process,
            @NotNull Map<String, Object> parameters, boolean debug, @NotNull DebugMode mode,
            @NotNull ServerTrace trace, @Nullable DAPClient parent) {
        if (generatedOutput == null || parent != null) return super.createClient(process, parameters, debug, mode, trace, parent);
        if (!ownedHandler.canLaunch()) throw new java.util.concurrent.CancellationException("The debug session was stopped before launch.");
        if (!GoDelveSessionOutput.required(parameters, mode == DebugMode.LAUNCH))
            throw new IllegalStateException("Debug launch configuration changed after the adapter started.");
        var owned = new LinkedHashMap<>(parameters);
        owned.put("output", generatedOutput.binary().toString());
        var disconnectStarted = new java.util.concurrent.atomic.AtomicBoolean();
        var disposed = new java.util.concurrent.atomic.AtomicBoolean();
        DAPClient client = new DAPClient(process, owned, debug, mode, trace, parent) {
            @Override public void dispose() { GoDelveProcessCleanup.disposeOnce(disposed, super::dispose); }
            @Override public void terminate() {
                if (disconnectStarted.compareAndSet(false, true)) super.terminate();
            }
        };
        // A failed or permanently pending disconnect must not retain the client after its session ends.
        ownedHandler.cleanupCompletion().whenCompleteAsync((unused, failure) -> client.dispose());
        ownedHandler.setDisconnectRequest(() -> {
            var server = client.getDebugProtocolServer();
            if (server == null || !disconnectStarted.compareAndSet(false, true)) return;
            var disconnect = new org.eclipse.lsp4j.debug.DisconnectArguments();
            disconnect.setTerminateDebuggee(true); // Only this local launch owns a generated output/target.
            GoDelveProcessCleanup.disconnectAndDispose(() -> server.disconnect(disconnect), client::dispose);
        });
        return client;
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

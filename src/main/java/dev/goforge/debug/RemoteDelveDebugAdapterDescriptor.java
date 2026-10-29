package dev.goforge.debug;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ColoredProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.client.DAPClient;
import com.redhat.devtools.lsp4ij.dap.definitions.DebugAdapterServerDefinition;
import com.redhat.devtools.lsp4ij.dap.descriptors.DefaultDebugAdapterDescriptor;
import com.redhat.devtools.lsp4ij.dap.descriptors.ServerReadyConfig;
import com.redhat.devtools.lsp4ij.settings.ServerTrace;
import dev.goforge.language.GoFileType;
import dev.goforge.remote.GoRemoteDebugPlan;
import dev.goforge.remote.GoRemoteDebugDocuments;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** SSH owns the adapter process; the DAP client independently chooses launch or attach semantics. */
public final class RemoteDelveDebugAdapterDescriptor extends DefaultDebugAdapterDescriptor {
    private GoRemoteDebugPlan plan;
    public RemoteDelveDebugAdapterDescriptor(RunConfigurationOptions options,ExecutionEnvironment environment,DebugAdapterServerDefinition definition){super(options,environment,definition);}
    private GoRemoteDebugPlan plan(){if(plan==null)plan=dev.goforge.remote.GoRemoteDebugSessions.getInstance(environment.getProject()).require(String.valueOf(super.getDapParameters().get("goforgeRemoteSession")));return plan;}
    @Override public @NotNull DebugMode getDebugMode(){return DebugMode.LAUNCH;}
    public DebugMode clientMode(){return plan().attach()?DebugMode.ATTACH:DebugMode.LAUNCH;}
    @Override public @NotNull Map<String,Object> getDapParameters(){var args=new java.util.LinkedHashMap<>(plan().dapArguments());args.remove("env");args.remove("args");return args;}
    @Override public ProcessHandler startServer()throws ExecutionException {
        if (!(environment.getRunProfile() instanceof RemoteDapRunConfiguration))
            throw new ExecutionException("Review and start this SSH debug session from Go Remote; restored generic DAP configurations cannot reconnect.");
        try {
            var binding=plan();GoRemoteDebugDocuments.requireSaved(binding.localRoot());binding.verifyLocal();
            String ssh=dev.goforge.tools.HostTools.resolve("ssh",new GeneralCommandLine().getEffectiveEnvironment());
            var command=new GeneralCommandLine(binding.command(ssh)).withCharset(StandardCharsets.UTF_8);
            command.withWorkDirectory(binding.localRoot());
            var process=createBridgeProcessHandler(command,displayLabel(binding));
            try {var output=process.getProcessInput();if(output==null)throw new java.io.IOException("SSH stdin is unavailable");output.write((binding.startupJson()+"\n").getBytes(StandardCharsets.UTF_8));output.flush();}
            catch(Exception failure){process.destroyProcess();throw failure;}
            return process;
        } catch(Exception e){throw new ExecutionException("Remote debugging could not start: "+e.getMessage(),e);}
    }
    static String displayLabel(GoRemoteDebugPlan binding){
        String action=binding.attach()?"attach PID "+binding.processId():binding.mode().equals("test")?"tests":binding.mode().equals("exec")?"executable":"package";
        return "Remote Delve via SSH "+binding.profile().host()+":"+binding.profile().port()+" ("+action+")";
    }
    static ColoredProcessHandler createBridgeProcessHandler(GeneralCommandLine command,String display)throws ExecutionException {
        // The process receives the real argv. Only the public handler's display/log text is replaced;
        // never use a fake executable path or emit the Python bridge / private stdin manifest to the console.
        var handler=new ColoredProcessHandler(command.createProcess(),display,command.getCharset());
        ProcessTerminatedListener.attach(handler);handler.setShouldDestroyProcessRecursively(true);return handler;
    }
    // No TCP connection in the IDE. The SSH stdio stream carries only DAP frames.
    @Override public @NotNull ServerReadyConfig getServerReadyConfig(@NotNull DebugMode mode){return new ServerReadyConfig(0);}
    @Override public @NotNull DAPClient createClient(@NotNull DAPDebugProcess process,@NotNull Map<String,Object> parameters,
            boolean debug,@NotNull DebugMode ignored,@NotNull ServerTrace trace,@Nullable DAPClient parent){
        return new GoRemoteDapClient(environment.getProject(),process,parameters,debug,clientMode(),parent);
    }
    @Override public FileType getFileType(){return GoFileType.INSTANCE;}
    @Override public @NotNull XDebuggerEditorsProvider createDebuggerEditorsProvider(@Nullable FileType type,@NotNull DAPDebugProcess process){return new GoDebuggerEditorsProvider(process);}
    @Override public boolean isDebuggableFile(@NotNull VirtualFile file,@NotNull Project project){return !(file instanceof com.redhat.devtools.lsp4ij.dap.client.files.DAPFile)&&"go".equals(file.getExtension());}
}

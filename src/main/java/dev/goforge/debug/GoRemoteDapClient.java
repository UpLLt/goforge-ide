package dev.goforge.debug;

import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.client.DAPClient;
import com.redhat.devtools.lsp4ij.settings.ServerTrace;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import java.util.Map;
import java.util.UUID;

/** Uses original DAP stack navigation after installing session-owned readonly SDK documents. */
final class GoRemoteDapClient extends DAPClient {
    private final com.intellij.openapi.project.Project project;
    private final String sourceConfiguration="GoForge selected SDK "+UUID.randomUUID();
    private GoRemoteDapSources sources;
    private IDebugProtocolServer wrapped;
    private boolean ended;
    GoRemoteDapClient(com.intellij.openapi.project.Project project,DAPDebugProcess process,Map<String,Object> parameters,boolean debug,DebugMode mode,DAPClient parent){
        super(process,parameters,debug,mode,ServerTrace.off,parent);this.project=java.util.Objects.requireNonNull(project);
    }
    @Override public com.intellij.openapi.project.Project getProject(){return project;}
    @Override public String getConfigName(){return sourceConfiguration;}
    @Override public synchronized IDebugProtocolServer getDebugProtocolServer(){
        if(ended)throw new java.util.concurrent.CancellationException("Debug session ended");
        if(wrapped==null){sources=new GoRemoteDapSources(getProject(),sourceConfiguration,getSessionId());wrapped=sources.wrap(super.getDebugProtocolServer());}
        return wrapped;
    }
    @Override public void stopped(org.eclipse.lsp4j.debug.StoppedEventArguments event){
        GoRemoteDapSources active;synchronized(this){if(ended)return;getDebugProtocolServer();active=sources;}
        active.newPause();super.stopped(event);
    }
    @Override public void continued(org.eclipse.lsp4j.debug.ContinuedEventArguments event){
        GoRemoteDapSources active;synchronized(this){if(ended)return;active=sources;}
        if(active!=null)active.resume();super.continued(event);
    }
    @Override public void dispose(){
        GoRemoteDapSources active;synchronized(this){ended=true;active=sources;}
        try{if(active!=null)active.close();}finally{super.dispose();}
    }
}

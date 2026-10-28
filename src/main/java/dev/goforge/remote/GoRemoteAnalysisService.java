package dev.goforge.remote;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;
import com.redhat.devtools.lsp4ij.server.CannotStartProcessException;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Opt-in, project-lifetime remote analysis. Coordinates are persistent; this activation is not. */
@Service(Service.Level.PROJECT)
public final class GoRemoteAnalysisService implements Disposable {
    private final Project project;
    private final Set<GoRemoteLspTransport> transports=ConcurrentHashMap.newKeySet();
    private volatile GoRemoteAnalysisConfig active;
    private volatile GoRemoteAnalysisConfig failed;
    private volatile boolean disposed;
    public GoRemoteAnalysisService(Project project){this.project=project;}
    public static GoRemoteAnalysisService getInstance(Project project){return project.getService(GoRemoteAnalysisService.class);}
    public GoRemoteAnalysisConfig active(){return active;}
    public String status(){var selected=active;return selected==null?"Local Go analysis":(failed==selected?"Remote analysis stopped after failure: ":"Remote analysis session: ")+selected.profile().host()+":"+selected.remoteRoot();}
    public void activate(GoRemoteAnalysisConfig config) {
        if(disposed||project.isDisposed())return;
        active=config;failed=null;transports.forEach(GoRemoteLspTransport::closeAsync);transports.clear();restart();
    }
    public void stopAnalysis() {
        active=null;failed=null;transports.forEach(GoRemoteLspTransport::closeAsync);transports.clear();
        notice("Remote analysis stopped. Returning to local gopls. The remote snapshot is retained for inspection; no existing workspace files were overwritten.\n");restart();
    }
    private void restart() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if(disposed||project.isDisposed())return;
            for(var wrapper:LanguageServiceAccessor.getInstance(project).getStartedServers())
                if("goforge.gopls".equals(wrapper.getServerDefinition().getId()))wrapper.restart();
        });
    }
    public StreamConnectionProvider connectionProvider() {
        var expected=active;if(expected==null)return null;
        return new StreamConnectionProvider() {
            private volatile GoRemoteLspTransport transport;
            public void start()throws CannotStartProcessException {
                if(disposed||project.isDisposed()||active!=expected)throw new CannotStartProcessException("Remote analysis session is no longer active.");
                if(failed==expected)throw new CannotStartProcessException("Remote analysis stopped after a failure. Start a new snapshot or stop remote analysis to use local gopls.");
                try {
                    transport=new GoRemoteLspTransport(expected,new GeneralCommandLine().getEffectiveEnvironment(),GoRemoteAnalysisService.this::notice,() -> {if(active==expected)failed=expected;});
                    transports.add(transport);
                    if(disposed||project.isDisposed()||active!=expected){stop();throw new IllegalStateException("Remote analysis session changed.");}
                    transport.awaitReady(Duration.ofSeconds(15));
                }catch(Exception e){stop();throw new CannotStartProcessException(e);}
            }
            public InputStream getInputStream(){return transport==null?null:transport.input();}
            public OutputStream getOutputStream(){return transport==null?null:transport.output();}
            public boolean isAlive(){return transport!=null&&transport.alive();}
            public void stop(){var running=transport;if(running!=null){running.closeAsync();transports.remove(running);}}
        };
    }
    private void notice(String text){if(!disposed&&!project.isDisposed())GoRemoteService.getInstance(project).notice(text);}
    public void dispose(){disposed=true;active=null;transports.forEach(GoRemoteLspTransport::closeAsync);transports.clear();}
}

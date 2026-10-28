package dev.goforge.remote;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Service(Service.Level.PROJECT)
public final class GoRemoteService implements Disposable {
    public static final String WINDOW_ID="Go Remote";
    private final Project project;private final AtomicReference<Operation> active=new AtomicReference<>();
    private volatile boolean disposed;private GoRemotePanel panel;
    private volatile long analysisGeneration;
    private static final class Operation {volatile boolean cancelled;long analysisGeneration;}
    @FunctionalInterface private interface Work<T> {T run(BooleanSupplier cancelled) throws Exception;}
    public GoRemoteService(Project project) {this.project=project;}
    public static GoRemoteService getInstance(Project project) {return project.getService(GoRemoteService.class);}
    void attach(GoRemotePanel panel) {this.panel=panel;panel.running(active.get()!=null);}
    void detach(GoRemotePanel expected) {if(panel==expected)panel=null;}
    void cancel() {var run=active.get();if(run!=null)run.cancelled=true;}
    private Path local() {
        if(project.getBasePath()==null)throw new IllegalStateException("Open a local project first.");
        return Path.of(project.getBasePath());
    }
    private GoRemoteBackend backend() throws java.io.IOException {var env=new GeneralCommandLine().getEffectiveEnvironment();return new GoRemoteBackend(dev.goforge.tools.HostTools.resolve("ssh",env),local(),env);}
    void check(GoRemoteProfile p) {
        start("Check remote workspace",c -> backend().check(p,c),r -> notice("Connected: "+r+"\nGo commands use "+p.goExecutable()+". The Go analysis tab starts an explicit snapshot session; this check does not switch language servers.\n"));
    }
    void browse(GoRemoteProfile p,String relative) {
        start("Browse remote workspace",c -> backend().list(p,relative,c),rows -> {if(panel!=null)panel.entries(relative,rows);notice("Listed "+rows.size()+" entries.\n");});
    }
    void go(GoRemoteProfile p,GoRemoteBackend.GoTask task) {
        notice("Remote "+task.name().toLowerCase()+" in "+p.host()+":"+p.remoteDirectory()+"\n");
        start("Remote Go "+task.name().toLowerCase(),c -> backend().go(p,task,c,this::notice),result -> notice(result.success()?"\nRemote Go command exited 0.\n":"\n"+result.failure()+"\n"));
    }
    void upload(GoRemoteProfile p) {
        start("Prepare project upload preview",c -> GoRemoteFiles.snapshot(local(),c),snapshot -> {
            String name="goforge-upload-"+UUID.randomUUID();
            StringBuilder preview=new StringBuilder("Source: ").append(local()).append("\nDestination: ").append(p.host()).append(':').append(p.remoteDirectory()).append('/').append(name)
                    .append("\n\nCreate a NEW directory only. No existing remote path is overwritten or deleted.\nSaved files only; unsaved editor changes are not included.\n")
                    .append(snapshot.files().size()).append(" files, ").append(snapshot.bytes()).append(" bytes\n\n");
            for(var file:snapshot.files())preview.append(file.path()).append("  (").append(file.bytes()).append(" bytes)\n");
            preview.append("\nExcluded hidden files/directories, symlinks and generated directories:\n");
            snapshot.skipped().forEach(path -> preview.append(path).append('\n'));
            if(!new Preview(project,preview.toString()).showAndGet())return;
            start("Upload new remote snapshot",c -> backend().upload(p,name,snapshot,c),result -> {
                notice("Uploaded new snapshot: "+result+"\nSet Connection > Remote workspace to this directory to run it.\n");
                browse(p,"");
            },"Cancelled. A newly created remote snapshot may be incomplete; no prior paths were replaced.\n");
        });
    }
    void analyze(GoRemoteProfile p,String gopls) {
        GoRemoteAnalysisConfig.validateExecutable(gopls);
        long generation=beginAnalysisRequest();
        start("Prepare remote analysis preview",c -> GoRemoteFiles.snapshot(local(),c),snapshot -> {
            var text=new StringBuilder("Local project: ").append(local()).append("\nRemote parent: ").append(p.host()).append(':').append(p.remoteDirectory())
                    .append("\nRemote gopls: ").append(gopls).append("\n\nCreate a NEW private analysis snapshot and switch this project's Go language features to remote gopls.\n")
                    .append("No tools are installed on the remote host. It needs Python 3, Go and gopls.\n")
                    .append("Unsaved editor text is sent as LSP overlays; saving updates ONLY this session's snapshot with conflict checks.\n")
                    .append("Local files are never replaced by download. Rename edits use the normal IDE preview/undo.\n")
                    .append("Single go.mod module; GOWORK=off, no module downloads or local environment forwarding. Existing remote module cache can be used.\n")
                    .append("External dependency navigation is unavailable. Adding/deleting/renaming files requires a new snapshot.\n")
                    .append("Stop/close disconnects processes but retains the remote snapshot for your review. Activation is not saved across IDE restarts.\n\n")
                    .append(snapshot.files().size()).append(" saved files, ").append(snapshot.bytes()).append(" bytes:\n");
            snapshot.files().forEach(f -> text.append(f.path()).append('\n'));
            text.append("\nExcluded:\n");snapshot.skipped().forEach(f -> text.append(f).append('\n'));
            if(!new Preview(project,text.toString(),"Start remote analysis","Create snapshot and analyze").showAndGet())return;
            if(!isCurrentAnalysisRequest(generation))return;
            start("Create remote analysis snapshot",c -> GoRemoteAnalysisConfig.create(backend(),p,local(),gopls,snapshot,c),config -> {
                commitAnalysisRequest(generation,() -> {
                    GoRemoteAnalysisService.getInstance(project).activate(config);
                    notice("Remote analysis selected: "+p.host()+":"+config.remoteRoot()+"\nOpen a Go file to initialize the language server. Existing open Go editors reconnect with their current unsaved text.\n");
                });
            },"Cancelled. The newly created analysis snapshot may remain; no previous workspace files were overwritten.\n",generation);
        },"Cancelled. No analysis session was activated.\n",generation);
    }
    void debug(GoRemoteProfile profile,String delve,String mode,String target,int pid,boolean useAnalysis,java.util.List<String> arguments,java.util.Map<String,String> environment) {
        GoRemoteProfile selected=profile;
        if(useAnalysis){
            var analysis=GoRemoteAnalysisService.getInstance(project).active();
            if(analysis==null)throw new IllegalStateException("Start remote analysis first, or choose the configured workspace.");
            var p=analysis.profile();selected=new GoRemoteProfile(p.host(),p.user(),p.port(),p.keyFile(),analysis.remoteRoot(),p.goExecutable(),p.knownHostsFile());
        }
        var remote=selected;GoRemoteDebugDocuments.requireSaved(local().toString());
        start("Prepare remote debugging review",c->{
            var toolEnv=new GeneralCommandLine().getEffectiveEnvironment();
            String localGo=dev.goforge.tools.GoToolResolver.resolve(dev.goforge.settings.GoForgeSettings.getInstance().goPath,"go",project.getBasePath(),toolEnv);
            return GoRemoteDebugPlan.review(remote,local(),delve,mode,target,pid,GoRemoteDebugSources.snapshot(local(),c),arguments,environment,localGo,toolEnv,c);
        },plan->{
            GoRemoteDebugDocuments.requireSaved(plan.localRoot());
            String text="Local saved source: "+plan.localRoot()+"\nRemote workspace: "+remote.host()+":"+remote.remoteDirectory()+"\nDelve: "+delve+"\nMode: "+mode+(plan.attach()?" / PID "+pid:" / package "+target)+"\n\n"
                +"Arguments: "+new com.google.gson.Gson().toJson(arguments)+"\nEnvironment names: "+String.join(", ",environment.keySet())+" (values are not saved in the SSH profile)\n\n"
                +"Verify "+plan.files().size()+" visible saved files by SHA-256 before launching Delve.\nNo files are uploaded, overwritten or automatically saved. No remote tools are installed.\n"+(plan.workspace().enabled()?"go.work modules: "+String.join(", ",plan.workspace().modules())+"; local replacements: "+String.join(", ",plan.workspace().localReplacements())+". A private workspace file protects the original go.work.sum.\n":"GOWORK=off (single module).\n")+"No module downloads. Remote Go module cache must already satisfy dependencies.\n"
                +(plan.attach()?"Attach to this existing PID. Stop disconnects and preserves it. Remote ptrace permission is required.\n":mode.equals("exec")?"Launch the selected existing executable without rebuilding it. Stop terminates the launched target.\n":"Build and launch this package with debugging enabled. Stop terminates the launched target.\n")
                +(mode.equals("exec")||plan.attach()?"The selected binary must be built from these sources with debug information (-gcflags=all=-N -l); source hashes cannot prove binary provenance.\n":"")
                +"\nDebugging source is fixed for this run. Editing during the session can make line locations stale.\nExternal remote dependency source navigation is not available.\n\n"+String.join("\n",new java.util.TreeSet<>(plan.files().keySet()));
            if(!new Preview(project,text,"Review remote debugging",plan.attach()?"Attach debugger":"Start debugger").showAndGet())return;
            GoRemoteDebugDocuments.requireSaved(plan.localRoot());
            var configuration=dev.goforge.debug.GoRemoteDebugConfigurations.create(project,plan);
            var manager=com.intellij.execution.RunManager.getInstance(project);manager.setTemporaryConfiguration(configuration);manager.setSelectedConfiguration(configuration);
            com.intellij.execution.ProgramRunnerUtil.executeConfiguration(configuration,com.intellij.execution.executors.DefaultDebugExecutor.getDebugExecutorInstance());
            notice("Remote debug session requested. Use the Debug tool window for progress, breakpoints and Stop.\n");
        });
    }
    long beginAnalysisRequest(){return ++analysisGeneration;}
    private boolean isCurrentAnalysisRequest(long generation){return !disposed&&!project.isDisposed()&&generation==analysisGeneration;}
    boolean commitAnalysisRequest(long generation,Runnable commit) {
        if(!isCurrentAnalysisRequest(generation))return false;
        commit.run();return true;
    }
    void invalidateAnalysisRequests(){++analysisGeneration;var run=active.get();if(run!=null&&run.analysisGeneration!=0)run.cancelled=true;}
    void stopAnalysis(){invalidateAnalysisRequests();GoRemoteAnalysisService.getInstance(project).stopAnalysis();}
    void download(GoRemoteProfile p,String remoteFile) {
        String relative=Messages.showInputDialog(project,"Download "+p.host()+":"+p.remoteDirectory()+"/"+remoteFile+"\nCreate a NEW file under "+local()+". Existing files are never overwritten.\nThe destination parent folder must already exist.","Download remote file",Messages.getQuestionIcon(),remoteFile,null);
        if(relative==null)return;
        try {GoRemoteProfile.relative(relative);}catch(RuntimeException e) {notice(e.getMessage()+"\n");return;}
        start("Download remote file",c -> {
            byte[] bytes=backend().download(p,remoteFile,c);if(c.getAsBoolean())throw new IllegalStateException("Download cancelled.");
            return GoRemoteFiles.writeNew(local(),relative,bytes);
        },path -> {LocalFileSystem.getInstance().refreshNioFiles(List.of(path),true,false,null);notice("Downloaded new file: "+path+"\n");});
    }
    void terminal(GoRemoteProfile p) {
        if(disposed||project.isDisposed())return;
        try {
            String ssh=dev.goforge.tools.HostTools.resolve("ssh",new GeneralCommandLine().getEffectiveEnvironment());
            TerminalToolWindowManager.getInstance(project).createNewSession(local().toString(),"SSH "+p.host(),p.ssh(ssh,p.terminalCommand(),true),true,true);
        } catch(java.io.IOException unavailable) { notice(unavailable.getMessage()+"\n"); }
    }
    private <T> void start(String title,Work<T> work,Consumer<T> done) {
        start(title,work,done,"Cancelled.\n");
    }
    private <T> void start(String title,Work<T> work,Consumer<T> done,String cancellationMessage) {
        start(title,work,done,cancellationMessage,0);
    }
    private <T> void start(String title,Work<T> work,Consumer<T> done,String cancellationMessage,long generation) {
        if(disposed||project.isDisposed())return;var op=new Operation();
        op.analysisGeneration=generation;
        if(!active.compareAndSet(null,op)){notice("Another remote operation is running; cancel it or wait.\n");return;}
        if(panel!=null)panel.running(true);
        new Task.Backgroundable(project,title,true) {
            T result;String error;
            public void run(@NotNull ProgressIndicator indicator) {
                try {result=work.run(() -> op.cancelled||indicator.isCanceled()||disposed||project.isDisposed()||generation!=0&&!isCurrentAnalysisRequest(generation));}
                catch(Exception e) {error=e.getMessage()==null?e.toString():e.getMessage();}
            }
            public void onCancel() {op.cancelled=true;}
            public void onThrowable(@NotNull Throwable e) {error=e.getMessage()==null?e.toString():e.getMessage();}
            public void onFinished() {
                if(!active.compareAndSet(op,null)||disposed||project.isDisposed())return;
                if(panel!=null)panel.running(false);
                if(generation!=0&&!isCurrentAnalysisRequest(generation))notice("Remote analysis request discarded. Any newly created snapshot is retained for inspection; no analysis session was activated.\n");
                else if(op.cancelled)notice(cancellationMessage);
                else if(error!=null)notice("Failed: "+error+"\n");
                else {try {done.accept(result);}catch(RuntimeException failure){notice("Failed: "+failure.getMessage()+"\n");}}
            }
        }.queue();
    }
    void notice(String text) {
        if(disposed||project.isDisposed())return;
        ApplicationManager.getApplication().invokeLater(() -> {if(!disposed&&!project.isDisposed()&&panel!=null)panel.append(text);});
    }
    public void dispose() {disposed=true;invalidateAnalysisRequests();cancel();panel=null;}
    private static final class Preview extends DialogWrapper {
        private final String text;
        Preview(Project project,String text) {this(project,text,"Preview remote snapshot upload","Upload new snapshot");}
        Preview(Project project,String text,String title,String action) {super(project);this.text=text;setTitle(title);setOKButtonText(action);init();}
        protected JComponent createCenterPanel() {var area=new JTextArea(text);area.setEditable(false);area.setCaretPosition(0);var scroll=new JScrollPane(area);scroll.setPreferredSize(new Dimension(720,420));return scroll;}
    }
}

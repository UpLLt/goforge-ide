package dev.goforge.language;

import com.intellij.application.options.CodeStyle;
import com.intellij.formatting.service.FormattingNotificationService;
import com.intellij.lang.ImportOptimizer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.EmptyRunnable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.redhat.devtools.lsp4ij.DocumentContentSynchronizer;
import com.redhat.devtools.lsp4ij.LanguageServerWrapper;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;
import com.redhat.devtools.lsp4ij.ServerStatus;
import dev.goforge.lsp.GoplsClientFeatures;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/** A real platform Optimize Imports provider. Preparation is cancellable and read-only;
 * the returned operation applies validated edits inside the platform's existing write command.
 * Automatic saves remain owned by GoFormatOnSaveAction, not this synchronous adapter. */
public final class GoImportOptimizer implements ImportOptimizer {
    @Override public boolean supports(@NotNull PsiFile psi) {
        var file=psi.getVirtualFile();
        return !psi.getProject().isDisposed()&&psi.isValid()&&file!=null&&file.isValid()
                &&file.isInLocalFileSystem()&&file.isWritable()&&file.getFileType()==GoFileType.INSTANCE;
    }

    @Override public @NotNull Runnable processFile(@NotNull PsiFile psi) {
        var project=psi.getProject();
        // Normal OptimizeImportsProcessor prepares under a cancellable background read action.
        // Never block an EDT/direct write caller waiting for a language-server response.
        if(ApplicationManager.getApplication().isDispatchThread())
            return failure(project,"Go import optimization requires the normal background Optimize Imports action.");
        try {
            ApplicationManager.getApplication().assertReadAccessAllowed();
            if(!supports(psi))return EmptyRunnable.getInstance();
            if(CodeStyle.getSettings(psi).getExcludedFiles().contains(psi))return EmptyRunnable.getInstance();
            var file=psi.getVirtualFile();var document=FileDocumentManager.getInstance().getDocument(file);
            if(document==null||document.getTextLength()>8*1024*1024)
                throw new IllegalStateException("The Go document is unavailable or exceeds the 8 MiB limit.");
            LanguageServerWrapper[] selected={null};
            LanguageServiceAccessor.getInstance(project).processLanguageServers(psi,wrapper->{
                if(!wrapper.isDisposed()&&wrapper.getServerStatus()==ServerStatus.started
                        &&wrapper.getClientFeatures() instanceof GoplsClientFeatures
                        &&wrapper.getClientFeatures().getCodeActionFeature().isEnabled(psi)
                        &&wrapper.getClientFeatures().getCodeActionFeature().isCodeActionSupported(psi))selected[0]=wrapper;
            });
            var wrapper=selected[0];
            if(wrapper==null||wrapper.getLanguageServer()==null)
                throw new IllegalStateException("Open this Go file and wait for gopls to start, then optimize imports again.");
            var opened=wrapper.getOpenedDocument(wrapper.toUri(file));
            var sync=opened==null?null:opened.getSynchronizer();
            if(sync==null||sync.getDocument()!=document||!sync.getDidOpenFuture().isDone()||sync.getDidOpenFuture().isCompletedExceptionally())
                throw new IllegalStateException("The Go document is not ready in gopls. Try Optimize Imports again after it opens.");
            var snapshot=new Snapshot(project,file,document,document.getModificationStamp(),document.getText(),
                    wrapper,wrapper.getLanguageServer(),sync,wrapper.toUriString(file));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            await(sync.flushPendingChanges(),deadline,false);snapshot.requireCurrent();
            Integer version=snapshot.sentVersion();
            if(version==null)throw new IllegalStateException("The Go buffer version is not synchronized.");
            var context=new CodeActionContext(List.of());context.setOnly(List.of(CodeActionKind.SourceOrganizeImports));
            context.setTriggerKind(CodeActionTriggerKind.Invoked);
            var lines=snapshot.text().split("\n",-1);
            var range=new Range(new Position(0,0),new Position(lines.length-1,lines[lines.length-1].length()));
            var actions=await(snapshot.server().getTextDocumentService().codeAction(
                    new CodeActionParams(new TextDocumentIdentifier(snapshot.uri()),range,context)),deadline,true);
            snapshot.requireCurrent();snapshot.requireVersion(version);
            var edits=GoSaveImportEdits.prepare(snapshot.uri(),snapshot.text(),version,actions);
            if(edits.isEmpty())return EmptyRunnable.getInstance();
            return new CollectingInfoRunnable(){
                private boolean applied;
                @Override public void run(){
                    try{
                        ProgressManager.checkCanceled();snapshot.requireCurrent();snapshot.requireVersion(version);
                        ApplicationManager.getApplication().assertWriteAccessAllowed();
                        for(var edit:edits.reversed())document.replaceString(edit.start(),edit.end(),edit.text());
                        applied=true;
                    }catch(ProcessCanceledException cancelled){throw cancelled;}
                    catch(RuntimeException stale){report(project,stale);}
                }
                @Override public @Nullable String getUserNotificationInfo(){return applied?"Go imports optimized":null;}
            };
        }catch(ProcessCanceledException cancelled){throw cancelled;}
        catch(Exception invalid){return failure(project,Objects.toString(invalid.getMessage(),invalid.getClass().getSimpleName()));}
    }

    private record Snapshot(Project project,VirtualFile file,Document document,long stamp,String text,
                            LanguageServerWrapper wrapper,LanguageServer server,DocumentContentSynchronizer sync,String uri){
        Integer sentVersion(){return ((GoplsClientFeatures)wrapper.getClientFeatures()).getSentDocumentVersion(uri);}
        void requireVersion(int expected){if(!Objects.equals(expected,sentVersion()))throw new IllegalStateException("The Go overlay changed; optimize imports again.");}
        void requireCurrent(){
            if(project.isDisposed()||!file.isValid()||!file.isWritable()||!file.isInLocalFileSystem()
                    ||file.getFileType()!=GoFileType.INSTANCE||FileDocumentManager.getInstance().getFile(document)!=file
                    ||document.getModificationStamp()!=stamp||!document.getText().equals(text)
                    ||wrapper.isDisposed()||wrapper.getServerStatus()!=ServerStatus.started||wrapper.getLanguageServer()!=server
                    ||!uri.equals(wrapper.toUriString(file)))throw new IllegalStateException("The Go document or server changed; optimize imports again.");
            var opened=wrapper.getOpenedDocument(wrapper.toUri(file));
            var psi=PsiManager.getInstance(project).findFile(file);
            var feature=wrapper.getClientFeatures().getCodeActionFeature();
            if(opened==null||opened.getSynchronizer()!=sync||psi==null||!feature.isEnabled(psi)
                    ||!feature.isCodeActionSupported(psi)||CodeStyle.getSettings(psi).getExcludedFiles().contains(psi))
                throw new IllegalStateException("Go import optimization is no longer available for this file.");
        }
    }
    private static <T>T await(CompletableFuture<T> future,long deadline,boolean cancelOwned)throws Exception{
        try{
            while(true){
                ProgressManager.checkCanceled();
                long remaining=deadline-System.nanoTime();
                if(remaining<=0)throw new TimeoutException("Go import optimization timed out.");
                try{return future.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(25)),TimeUnit.NANOSECONDS);}
                catch(TimeoutException timeout){if(System.nanoTime()>=deadline)throw timeout;}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ProcessCanceledException(interrupted);}
            }
        }finally{if(cancelOwned&&!future.isDone())future.cancel(true);}
    }
    private static Runnable failure(Project project,String message){return new CollectingInfoRunnable(){
        @Override public void run(){report(project,new IllegalStateException(message));}
        @Override public @Nullable String getUserNotificationInfo(){return null;}
    };}
    private static void report(Project project,RuntimeException error){
        if(!project.isDisposed())FormattingNotificationService.getInstance(project).reportError(
                "GoForge","Go imports were not optimized",Objects.toString(error.getMessage(),error.getClass().getSimpleName()).substring(0,Math.min(1000,Objects.toString(error.getMessage(),error.getClass().getSimpleName()).length())));
    }
}

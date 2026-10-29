package dev.goforge.refactor;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import com.redhat.devtools.lsp4ij.LanguageServerWrapper;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Previewed Go refactorings with a stable snapshot and one undoable write command. */
public final class GoRefactorAction extends AnAction {
    private record Choice(CodeAction action,GoChangeSignature signature,boolean addParameter,boolean scalarType) {
        Choice(CodeAction action){this(action,null,false,false);}
        @Override public String toString() { return action.getTitle(); }
    }
    private record Query(LanguageServerWrapper wrapper, LanguageServer server, GoRefactorSnapshot snapshot, List<GoRefactorWorkspaceEdit.Source> sources, List<Choice> choices,Position selection) { }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent event) {
        var editor = event.getData(CommonDataKeys.EDITOR);
        var file = editor == null ? null : com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.getDocument());
        event.getPresentation().setEnabledAndVisible(event.getProject() != null && editor != null
                && file != null && file.isInLocalFileSystem() && "go".equals(file.getExtension()));
    }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject(); var editor = event.getData(CommonDataKeys.EDITOR);
        var file = editor == null ? null : com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (project == null || editor == null || file == null) return;
        Document document = editor.getDocument(); String source = document.getText(); long stamp = document.getModificationStamp();
        if (source.length() > 8 * 1024 * 1024) { fail(project, "This file exceeds the 8 MiB refactoring limit."); return; }
        var selection = editor.getSelectionModel();
        int start = selection.hasSelection() ? selection.getSelectionStart() : editor.getCaretModel().getOffset();
        int end = selection.hasSelection() ? selection.getSelectionEnd() : start;
        Range range;
        try { range = new Range(GoRefactorEdit.position(source, start), GoRefactorEdit.position(source, end)); }
        catch (RuntimeException error) { fail(project, error.getMessage()); return; }
        new Task.Backgroundable(project, "Find Go Refactorings", true) {
            Query result;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                try {
                    var accessor = LanguageServiceAccessor.getInstance(project);
                    var future = ReadAction.compute(() -> {
                        var psi = PsiManager.getInstance(project).findFile(file);
                        if (psi == null) throw new IllegalStateException("Go file is no longer available.");
                        return accessor.getLanguageServers(psi, f -> f.isServerDefinition("goforge.gopls"), f -> f.isServerDefinition("goforge.gopls"));
                    });
                    var servers = await(future, indicator, project);
                    if (servers.isEmpty()) throw new IllegalStateException("Open this Go file and wait for gopls to start.");
                    var wrapper = servers.get(0).getServerWrapper();
                    var server = await(wrapper.getInitializedServer(), indicator, project);
                    var snapshot=GoRefactorSnapshot.capture(project,indicator);
                    var sources=new ArrayList<GoRefactorWorkspaceEdit.Source>();
                    var features=wrapper.getClientFeatures() instanceof dev.goforge.lsp.GoplsClientFeatures f ? f : null;
                    if(features==null)throw new IllegalStateException("Go document version tracking is unavailable.");
                    for(var entry:snapshot.entries()){
                        indicator.checkCanceled();String entryUri=wrapper.toUriString(entry.file());
                        var opened=wrapper.getOpenedDocument(wrapper.toUri(entry.file()));
                        if(entry.unsaved()&&(opened==null||opened.getSynchronizer()==null)){
                            var connection=ReadAction.compute(()->{
                                var psi=PsiManager.getInstance(project).findFile(entry.file());
                                if(psi==null)throw new IllegalStateException("An unsaved Go source is unavailable.");
                                return accessor.getLanguageServers(psi,f->f.getServerWrapper()==wrapper,f->f.getServerWrapper()==wrapper);
                            });
                            if(await(connection,indicator,project).isEmpty())throw new IllegalStateException("An unsaved Go source could not be synchronized.");
                            opened=wrapper.getOpenedDocument(wrapper.toUri(entry.file()));
                        }
                        Integer version=0; // gopls disk-backed file handles use version zero; overlays are tracked explicitly.
                        if(opened!=null&&opened.getSynchronizer()!=null){
                            var sync=opened.getSynchronizer();await(sync.getDidOpenFuture(),indicator,project);await(sync.flushPendingChanges(),indicator,project);
                            version=features.getSentDocumentVersion(entryUri);
                            if(version==null)throw new IllegalStateException("A Go buffer version is not synchronized.");
                        }else if(entry.unsaved())throw new IllegalStateException("An unsaved Go source is not connected.");
                        sources.add(new GoRefactorWorkspaceEdit.Source(entryUri,entry.text(),version));
                    }
                    snapshot.requireDiskCurrent();ReadAction.run(snapshot::requireCurrent);
                    requireCurrent(project,file,document,stamp);
                    String uri=wrapper.toUriString(file);
                    var context = new CodeActionContext(List.of());
                    context.setOnly(List.of("refactor.extract", "refactor.inline", "refactor.rewrite"));
                    context.setTriggerKind(CodeActionTriggerKind.Invoked);
                    var actions = await(server.getTextDocumentService().codeAction(new CodeActionParams(new TextDocumentIdentifier(uri), range, context)), indicator, project, true);
                    var choices = new ArrayList<Choice>();
                    if (actions != null) for (var item : actions) if (item.isRight()) {
                        var action = item.getRight();
                        if (action.getDisabled() == null && action.getKind() != null && action.getKind().startsWith("refactor.")
                                && ((action.getCommand()==null&&(action.getEdit()!=null||action.getData()!=null)) || isExtractToNewFile(action))) choices.add(new Choice(action));
                    }
                    if(GoChangeSignature.candidate(source,start,end)){
                        var prepared=await(server.getTextDocumentService().prepareRename(new GoChangeSignature.PrepareRequest(new TextDocumentIdentifier(uri),range.getStart())),indicator,project,true);
                        var signature=prepared!=null&&prepared.isSecond()?GoChangeSignature.prepared(uri,source,range.getStart(),prepared.getSecond()):null;
                        if(prepared!=null&&prepared.isSecond()&&GoChangeSignature.preparedDeclaration(source,range.getStart(),prepared.getSecond())){
                            var add=new CodeAction("Add parameter to verified project calls…");add.setKind("goforge.addParameter");choices.add(0,new Choice(add,null,true,false));
                            var scalar=new CodeAction("Change scalar parameter/result type…");scalar.setKind("goforge.changeScalarType");choices.add(0,new Choice(scalar,null,false,true));
                        }
                        if(signature!=null){
                            var custom=new CodeAction("Change signature across files…");custom.setKind("goforge.changeSignature");choices.add(0,new Choice(custom,signature,false,false));
                        }
                    }
                    ReadAction.run(snapshot::requireCurrent);
                    result = new Query(wrapper,server,snapshot,List.copyOf(sources),List.copyOf(choices),range.getStart());
                } catch (ProcessCanceledException cancelled) {
                    if (GoRefactorScratch.hasSuppressedFailure(cancelled)) throw new IllegalStateException(detail(cancelled),cancelled);
                    throw cancelled;
                }
                catch (CancellationException cancelled) {
                    if (GoRefactorScratch.hasSuppressedFailure(cancelled)) throw new IllegalStateException(detail(cancelled),cancelled);
                    throw new ProcessCanceledException();
                }
                catch (Exception error) { throw new IllegalStateException(detail(error), error); }
            }
            @Override public void onSuccess() {
                if (project.isDisposed()) return;
                if (document.getModificationStamp() != stamp) { fail(project, "The selection changed. Request the refactoring again."); return; }
                if (result == null || result.choices().isEmpty()) { Messages.showInfoMessage(project,
                        "No previewable refactoring is available at this selection. Select an expression or statements, place the caret on a call to inline, or on a declaration's func keyword to change its signature.", "Go Refactor"); return; }
                JBPopupFactory.getInstance().createPopupChooserBuilder(result.choices()).setTitle("Go Refactor")
                        .setItemChosenCallback(choice -> {
                            String signature=null;GoAddParameter.Spec parameter=null;GoChangeScalarType.Spec scalarType=null;
                            if(choice.scalarType()){
                                var dialog=new GoChangeScalarTypeDialog(project);if(!dialog.showAndGet())return;
                                scalarType=dialog.specification();
                            }else if(choice.addParameter()){
                                var dialog=new GoAddParameterDialog(project);if(!dialog.showAndGet())return;
                                parameter=dialog.specification();
                            }else if(choice.signature()!=null){
                                signature=Messages.showInputDialog(project,"Reorder existing parameters or remove unused ones. Keep parameter names/types and results.\nAll affected callers will be previewed. Calls with nontrivial argument evaluation are refused.","Change Go Signature",null,choice.signature().placeholder(),null);
                                if(signature==null)return;
                                try{choice.signature().request(signature);}catch(IllegalArgumentException error){fail(project,error.getMessage());return;}
                            }
                            preview(project,file,document,stamp,source,result,choice.action(),choice.signature(),signature,parameter,scalarType);
                        })
                        .createPopup().showInBestPositionFor(editor);
            }
            @Override public void onThrowable(@NotNull Throwable error) { if (!project.isDisposed()) fail(project, detail(error)); }
        }.queue();
    }
    private static void preview(Project project, VirtualFile file, Document document, long stamp, String source, Query query, CodeAction action,GoChangeSignature signature,String requestedSignature,GoAddParameter.Spec parameter,GoChangeScalarType.Spec scalarType) {
        new Task.Backgroundable(project, "Preview " + action.getTitle(), true) {
            GoRefactorWorkspaceEdit result;
            GoChangeScalarType.Plan checkedType;
            String checkedGoSetting;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                try {
                    requireCurrent(project, file, document, stamp);
                    if (query.wrapper().isDisposed() || !query.wrapper().isActive() || query.wrapper().getLanguageServer() != query.server())
                        throw new IllegalStateException("The Go language server restarted. Request the refactoring again.");
                    WorkspaceEdit workspace;
                    if(scalarType!=null){
                        requireCompileConfiguration(project);
                        var settings=dev.goforge.settings.GoForgeSettings.getInstance();
                        var command=new com.intellij.execution.configurations.GeneralCommandLine();
                        Map<String,String> environment=new HashMap<>(command.getEffectiveEnvironment());
                        String go=dev.goforge.tools.GoToolResolver.resolve(settings.goPath,"go",project.getBasePath(),environment);
                        checkedGoSetting=settings.goPath;
                        checkedType=GoChangeScalarType.plan(go,environment,query.snapshot().directory(),query.sources(),query.wrapper().toUriString(file),query.selection(),scalarType,
                                target->await(query.server().getTextDocumentService().references(new ReferenceParams(new TextDocumentIdentifier(target.getUri()),target.getRange().getStart(),new ReferenceContext(false))),indicator,project,true),
                                ()->indicator.isCanceled()||project.isDisposed());
                        workspace=checkedType.workspace();
                    }else if(parameter!=null){
                        var settings=dev.goforge.settings.GoForgeSettings.getInstance();
                        var command=new com.intellij.execution.configurations.GeneralCommandLine();
                        Map<String,String> environment=new HashMap<>(command.getEffectiveEnvironment());
                        String go=dev.goforge.tools.GoToolResolver.resolve(settings.goPath,"go",project.getBasePath(),environment);
                        GoSignatureSafety.References references=target->await(query.server().getTextDocumentService().references(new ReferenceParams(new TextDocumentIdentifier(target.getUri()),target.getRange().getStart(),new ReferenceContext(false))),indicator,project,true);
                        try {
                            workspace=GoAddParameter.plan(go,environment,query.sources(),query.wrapper().toUriString(file),query.selection(),parameter,references,()->indicator.isCanceled()||project.isDisposed());
                        } catch(GoAddParameter.CompilationRequired needsProof) {
                            GoRefactorScratch.requireCleanFailure(needsProof);
                            requireCompileConfiguration(project);
                            checkedGoSetting=settings.goPath;
                            checkedType=GoAddParameter.compileCheckedPlan(go,environment,query.snapshot().directory(),query.sources(),query.wrapper().toUriString(file),query.selection(),parameter,references,()->indicator.isCanceled()||project.isDisposed());
                            workspace=checkedType.workspace();
                        }
                    }else if(signature!=null){
                        workspace=await(query.server().getTextDocumentService().rename(signature.request(requestedSignature)),indicator,project,true);
                    }else if(isExtractToNewFile(action)){
                        if(dev.goforge.remote.GoRemoteAnalysisService.getInstance(project).active()!=null)
                            throw new IllegalStateException("Stop remote Go analysis before extracting to a new file; its snapshot has a fixed file set.");
                        var settings=dev.goforge.settings.GoForgeSettings.getInstance();
                        var command=new com.intellij.execution.configurations.GeneralCommandLine().withEnvironment(dev.goforge.settings.GoProjectSettings.getInstance(project).environment());
                        Map<String,String> environment=new HashMap<>(dev.goforge.settings.GoProjectSettings.getInstance(project).buildEnvironment(command.getEffectiveEnvironment()));
                        String gopls=dev.goforge.tools.GoToolResolver.resolve(settings.goplsPath,"gopls",project.getBasePath(),environment);
                        String go=dev.goforge.tools.GoToolResolver.resolve(settings.goPath,"go",project.getBasePath(),environment);
                        environment=dev.goforge.tools.HostTools.withPrependedPath(environment,java.nio.file.Path.of(go).getParent());
                        workspace=GoRefactorCommandCapture.capture(List.of(gopls,"serve"),environment,query.snapshot().directory(),query.sources(),action.getCommand(),()->indicator.isCanceled()||project.isDisposed());
                    }else{
                        var resolved=action.getEdit()==null?await(query.server().getTextDocumentService().resolveCodeAction(action),indicator,project,true):action;
                        if(resolved.getCommand()!=null)throw new IllegalStateException("This refactoring requires an unsupported command and cannot be previewed here.");
                        workspace=resolved.getEdit();
                    }
                    ReadAction.run(query.snapshot()::requireCurrent);
                    result = GoRefactorWorkspaceEdit.prepare(query.sources(), workspace);
                    query.snapshot().prepareTargets(result);
                    if(GoSignatureSafety.required(action.getKind())){
                        var settings=dev.goforge.settings.GoForgeSettings.getInstance();
                        var command=new com.intellij.execution.configurations.GeneralCommandLine();
                        Map<String,String> environment=new HashMap<>(command.getEffectiveEnvironment());
                        String go=dev.goforge.tools.GoToolResolver.resolve(settings.goPath,"go",project.getBasePath(),environment);
                        GoSignatureSafety.verify(go,environment,query.sources(),query.wrapper().toUriString(file),query.selection(),result,
                                signature==null?null:signature.placeholder(),requestedSignature,
                                target->await(query.server().getTextDocumentService().references(new ReferenceParams(new TextDocumentIdentifier(target.getUri()),target.getRange().getStart(),new ReferenceContext(false))),indicator,project,true),
                                ()->indicator.isCanceled()||project.isDisposed());
                    }
                    query.snapshot().requireDiskCurrent();
                    if(checkedType!=null)requireCompileProof(project,checkedType,checkedGoSetting,query);
                    ReadAction.run(query.snapshot()::requireCurrent);
                    requireCurrent(project, file, document, stamp);
                } catch (ProcessCanceledException cancelled) {
                    if (GoRefactorScratch.hasSuppressedFailure(cancelled)) throw new IllegalStateException(detail(cancelled),cancelled);
                    throw cancelled;
                }
                catch (CancellationException cancelled) {
                    if (GoRefactorScratch.hasSuppressedFailure(cancelled)) throw new IllegalStateException(detail(cancelled),cancelled);
                    throw new ProcessCanceledException();
                }
                catch (Exception error) { throw new IllegalStateException(detail(error), error); }
            }
            @Override public void onSuccess() {
                if (project.isDisposed()) return;
                try {
                    requireCurrent(project, file, document, stamp);
                    query.snapshot().requireCurrent();
                    if (new GoRefactorPreviewDialog(project,action.getTitle(),query.snapshot(),result).showAndGet()) {
                        new Task.Backgroundable(project,"Validate Go Refactoring",true){
                            @Override public void run(@NotNull ProgressIndicator indicator){
                                indicator.checkCanceled();
                                try{query.snapshot().requireDiskCurrent();ReadAction.run(query.snapshot()::requireCurrent);query.snapshot().requireTargetDiskCurrent(result);if(checkedType!=null)requireCompileProof(project,checkedType,checkedGoSetting,query);}
                                catch(java.io.IOException failure){throw new IllegalStateException("A source could not be revalidated. No edits were applied.",failure);}
                            }
                            @Override public void onSuccess(){
                                if(project.isDisposed())return;
                                try{if(checkedType!=null)requireCompileProof(project,checkedType,checkedGoSetting,query);GoRefactorApply.apply(project,query.snapshot(),result,action.getTitle());}
                                catch(Exception failure){fail(project,detail(failure));}
                            }
                            @Override public void onThrowable(@NotNull Throwable failure){if(!project.isDisposed())fail(project,detail(failure));}
                        }.queue();
                    }
                } catch (RuntimeException error) { fail(project, detail(error)); }
            }
            @Override public void onThrowable(@NotNull Throwable error) { if (!project.isDisposed()) fail(project, detail(error)); }
        }.queue();
    }
    private static void requireCompileProof(Project project,GoChangeScalarType.Plan plan,String selectedGo,Query query)throws java.io.IOException {
        requireCompileConfiguration(project);
        if(query.wrapper().isDisposed()||!query.wrapper().isActive()||query.wrapper().getLanguageServer()!=query.server())
            throw new IllegalStateException("The Go language server restarted. Request a fresh compile-checked preview.");
        var settings=dev.goforge.settings.GoForgeSettings.getInstance();
        if(!Objects.equals(settings.goPath,selectedGo))
            throw new IllegalStateException("The selected Go SDK changed after type checking. Request a fresh preview.");
        Map<String,String> environment=new HashMap<>(new com.intellij.execution.configurations.GeneralCommandLine().getEffectiveEnvironment());
        String go=dev.goforge.tools.GoToolResolver.resolve(settings.goPath,"go",project.getBasePath(),environment);
        plan.requireCompiler(go,environment);
    }
    static void requireCompileConfiguration(Project project){
        if(dev.goforge.remote.GoRemoteAnalysisService.getInstance(project).active()!=null)
            throw new IllegalStateException("Stop remote Go analysis before this compile-checked refactoring; this check uses one local module.");
        var manager=com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
        java.nio.file.Path root=java.nio.file.Path.of(Objects.requireNonNull(project.getBasePath())).toAbsolutePath().normalize();
        for(var document:manager.getUnsavedDocuments()){
            var file=manager.getFile(document);
            if(file!=null&&file.isInLocalFileSystem()){
                String name=file.getName();var path=file.toNioPath().toAbsolutePath().normalize();
                if((name.equals("go.mod")||name.equals("go.sum"))&&path.startsWith(root)
                        ||(name.equals("go.work")||name.equals("go.work.sum"))&&root.startsWith(path.getParent()))
                    throw new IllegalStateException("Save or revert unsaved module/workspace metadata before this compile-checked refactoring.");
            }
        }
        var settings=dev.goforge.settings.GoProjectSettings.getInstance(project);
        if(!settings.environment().isEmpty()||!settings.buildFlags().isEmpty())
            throw new IllegalStateException("Compile-checked refactorings require default host build settings; custom GOOS, GOARCH and build tags are not yet supported.");
    }
    private static boolean isExtractToNewFile(CodeAction action){
        return "refactor.extract.toNewFile".equals(action.getKind())&&action.getCommand()!=null
                &&"gopls.extract_to_new_file".equals(action.getCommand().getCommand())&&action.getEdit()==null;
    }
    private static void requireCurrent(Project project, VirtualFile file, Document document, long stamp) {
        if (project.isDisposed() || !file.isValid() || !file.isWritable() || document.getModificationStamp() != stamp
                || com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document) != file)
            throw new IllegalStateException("The document changed. Request a fresh refactoring preview.");
    }
    private static <T> T await(CompletableFuture<T> future, ProgressIndicator indicator, Project project) throws Exception {
        return await(future, indicator, project, false);
    }
    private static <T> T await(CompletableFuture<T> future, ProgressIndicator indicator, Project project, boolean ownRequest) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try {
            while (true) {
                indicator.checkCanceled(); if (project.isDisposed()) throw new ProcessCanceledException();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("Go refactoring timed out.");
                try { return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS); }
                catch (TimeoutException waiting) { /* Check cancellation without holding IDE locks. */ }
            }
        } catch (Exception error) { if (ownRequest) future.cancel(true); throw error; }
    }
    private static String detail(Throwable error) {
        return GoRefactorScratch.failureDetail(error);
    }
    private static void fail(Project project, String message) { Messages.showErrorDialog(project, message, "Go Refactor"); }
}

package dev.goforge.refactor;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.eclipse.lsp4j.*;
import java.nio.file.*;
import java.util.*;

/** Physical files, existing dirty buffers, actual command/Undo and production closed-file synchronization. */
public final class GoRefactorWorkspacePlatformTest extends BasePlatformTestCase {
    private Path directory;private VirtualFile root;
    @Override protected void setUp() throws Exception {
        super.setUp();directory=FileUtil.createTempDirectory("goforge-refactor-workspace-",null,true).toPath();
        root=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory);assertNotNull(root);
        WriteAction.run(()->PsiTestUtil.addContentRoot(getModule(),root));
        dev.goforge.lsp.GoClosedDocumentSynchronizer.getInstance(getProject());
    }
    @Override protected void tearDown() throws Exception {
        try{if(root!=null){VfsUtilCore.iterateChildrenRecursively(root,null,file->{var doc=manager().getCachedDocument(file);if(doc!=null)manager().reloadFromDisk(doc);return true;});WriteAction.run(()->PsiTestUtil.removeContentEntry(getModule(),root));}if(directory!=null)FileUtil.delete(directory.toFile());}
        finally{super.tearDown();}
    }
    private static FileDocumentManager manager(){return FileDocumentManager.getInstance();}
    private Document source(String name,String text)throws Exception{
        Path path=directory.resolve(name);Files.createDirectories(path.getParent());Files.writeString(path,text);var file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);assertNotNull(file);return manager().getDocument(file);
    }
    private GoRefactorSnapshot capture()throws Exception{return GoRefactorSnapshot.capture(getProject(),root,new EmptyProgressIndicator());}
    private GoRefactorWorkspaceEdit plan(GoRefactorSnapshot snapshot){
        var sources=new ArrayList<GoRefactorWorkspaceEdit.Source>();var edits=new LinkedHashMap<String,List<TextEdit>>();
        for(var entry:snapshot.entries()){
            String uri=entry.file().toNioPath().toUri().toString();sources.add(new GoRefactorWorkspaceEdit.Source(uri,entry.text(),0));
            int start=entry.text().indexOf("Before");
            edits.put(uri,List.of(new TextEdit(new Range(GoRefactorEdit.position(entry.text(),start),GoRefactorEdit.position(entry.text(),start+6)),"After")));
        }
        return GoRefactorWorkspaceEdit.prepare(sources,new WorkspaceEdit(edits));
    }
    public void testSnapshotAndCreationGuardMatchVfsPathWithNativeDocumentUri()throws Exception {
        var document=source("space 世界.go","package demo\nvar Before = 1\n");var file=manager().getFile(document);
        assertNotNull(file);var snapshot=capture();String uri=file.toNioPath().toUri().toASCIIString();
        assertSame(document,snapshot.entry(uri).document());
        var queued=new GoRefactorFileCreationGuard.Notifications();queued.record(file.getPath(),true);
        assertTrue(queued.consume(uri));assertFalse(queued.consume(uri));
        if(dev.goforge.tools.HostTools.isWindows()) {
            String lowerDrive=uri.replaceFirst("file:/+([A-Z]):", "file:///"+Character.toLowerCase(file.toNioPath().toString().charAt(0))+":");
            assertSame(document,snapshot.entry(lowerDrive).document());queued.record(file.getPath(),true);assertTrue(queued.consume(lowerDrive));
        }
    }
    public void testAllFilesApplyAndSingleUndoRedoPreserveDirtyOpenBufferAndClosedDisk()throws Exception{
        String initial="package demo\nvar Before = 1\n";
        var open=source("open.go",initial);var closed=source("closed.go",initial);
        var editor=EditorFactory.getInstance().createEditor(open,getProject(),PlainTextFileType.INSTANCE,false);
        try{
            WriteCommandAction.runWriteCommandAction(getProject(),()->open.insertString(open.getTextLength(),"// unsaved\n"));
            String dirty=open.getText();var snapshot=capture();var plan=plan(snapshot);
            assertEquals(initial,Files.readString(directory.resolve("open.go")));assertEquals(initial,closed.getText());
            GoRefactorApply.apply(getProject(),snapshot,plan,"Change both");
            assertEquals(dirty.replace("Before","After"),open.getText());assertEquals(initial.replace("Before","After"),closed.getText());
            assertEquals(initial,Files.readString(directory.resolve("open.go")));assertTrue(manager().isDocumentUnsaved(open));
            assertEquals(closed.getText(),Files.readString(directory.resolve("closed.go")));
            var textEditor=TextEditorProvider.getInstance().getTextEditor(editor);var undo=UndoManager.getInstance(getProject());
            assertTrue(undo.isUndoAvailable(textEditor));undo.undo(textEditor);
            assertEquals(dirty,open.getText());assertEquals(initial,closed.getText());assertEquals(initial,Files.readString(directory.resolve("closed.go")));
            assertTrue(undo.isRedoAvailable(textEditor));undo.redo(textEditor);
            assertEquals(dirty.replace("Before","After"),open.getText());assertEquals(initial.replace("Before","After"),Files.readString(directory.resolve("closed.go")));
        }finally{EditorFactory.getInstance().releaseEditor(editor);}
    }
    public void testCrossPackageSignatureBatchHasTwoUndoRedoCyclesAndRetainsDirtyCaller()throws Exception {
        String api="package api\nfunc Format(prefix string, count int, suffix string) string {return prefix+suffix}\n";
        String caller="package app\nimport \"example.test/api\"\nfunc Call() string {return api.Format(\"a\",7,\"z\")}\n";
        String test="package api_test\nimport \"example.test/api\"\nfunc Call() string {return api.Format(\"b\",3,\"q\")}\n";
        var definition=source("api/format.go",api);var open=source("app/call.go",caller);var closed=source("api/format_test.go",test);
        var editor=EditorFactory.getInstance().createEditor(open,getProject(),PlainTextFileType.INSTANCE,false);
        try {
            WriteCommandAction.runWriteCommandAction(getProject(),()->open.insertString(0,"// unsaved 世界🙂\n"));String dirty=open.getText();
            var snapshot=capture();var plan=signaturePlan(snapshot);
            assertEquals(api,definition.getText());assertEquals(test,closed.getText());assertEquals(caller,Files.readString(directory.resolve("app/call.go")));
            GoRefactorApply.apply(getProject(),snapshot,plan,"Change signature across packages");
            var undo=UndoManager.getInstance(getProject());var textEditor=TextEditorProvider.getInstance().getTextEditor(editor);
            for(int cycle=0;cycle<2;cycle++) {
                assertTrue(definition.getText().contains("suffix string, prefix string, count int"));assertTrue(open.getText().contains("api.Format(\"z\",\"a\",7)"));assertTrue(closed.getText().contains("api.Format(\"q\",\"b\",3)"));
                assertEquals(definition.getText(),Files.readString(directory.resolve("api/format.go")));assertEquals(closed.getText(),Files.readString(directory.resolve("api/format_test.go")));assertEquals(caller,Files.readString(directory.resolve("app/call.go")));assertTrue(manager().isDocumentUnsaved(open));
                assertTrue(undo.isUndoAvailable(textEditor));undo.undo(textEditor);
                assertEquals(api,definition.getText());assertEquals(dirty,open.getText());assertEquals(test,closed.getText());assertEquals(api,Files.readString(directory.resolve("api/format.go")));assertEquals(test,Files.readString(directory.resolve("api/format_test.go")));
                assertTrue(undo.isRedoAvailable(textEditor));undo.redo(textEditor);
            }
        }finally{EditorFactory.getInstance().releaseEditor(editor);}
    }
    public void testCrossPackageSignatureConflictDoesNotChangeEarlierDefinitionOrDirtyCaller()throws Exception {
        String api="package api\nfunc Format(prefix string, count int, suffix string) string {return prefix+suffix}\n";
        var definition=source("api/format.go",api);var caller=source("app/call.go","package app\nfunc Call() {api.Format(\"a\",7,\"z\")}\n");var snapshot=capture();var plan=signaturePlan(snapshot);
        WriteCommandAction.runWriteCommandAction(getProject(),()->caller.insertString(0,"// user's later change\n"));String dirty=caller.getText();
        assertThrows(IllegalStateException.class,()->GoRefactorApply.apply(getProject(),snapshot,plan,"Rejected stale signature"));
        assertEquals(api,definition.getText());assertEquals(api,Files.readString(directory.resolve("api/format.go")));assertEquals(dirty,caller.getText());
    }
    private GoRefactorWorkspaceEdit signaturePlan(GoRefactorSnapshot snapshot) {
        var sources=new ArrayList<GoRefactorWorkspaceEdit.Source>();var edits=new ArrayList<org.eclipse.lsp4j.jsonrpc.messages.Either<TextDocumentEdit,ResourceOperation>>();
        for(var entry:snapshot.entries()) {
            String uri=entry.file().toNioPath().toUri().toString();int version=entry.unsaved()?11:0;sources.add(new GoRefactorWorkspaceEdit.Source(uri,entry.text(),version));
            String text=entry.text().replace("prefix string, count int, suffix string","suffix string, prefix string, count int").replace("api.Format(\"a\",7,\"z\")","api.Format(\"z\",\"a\",7)").replace("api.Format(\"b\",3,\"q\")","api.Format(\"q\",\"b\",3)");
            edits.add(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(new TextDocumentEdit(new VersionedTextDocumentIdentifier(uri,version),List.of(new TextEdit(new Range(new Position(0,0),GoRefactorEdit.position(entry.text(),entry.text().length())),text)))));
        }
        return GoRefactorWorkspaceEdit.prepare(sources,new WorkspaceEdit(edits));
    }
    public void testAddParameterBatchPreservesDirtyCallerAndHasTwoUndoRedoCycles()throws Exception {
        String api="package api\nfunc Join(first string, second string) string {return first+second}\n";
        String app="package app\nimport \"example.test/api\"\nfunc Call() string {return api.Join(left(),right())}\n";
        String external="package api_test\nimport \"example.test/api\"\nvar _=api.Join(\"c\",\"d\")\n";
        var definition=source("api/join.go",api);var caller=source("app/call.go",app);var closed=source("api/join_test.go",external);
        var editor=EditorFactory.getInstance().createEditor(caller,getProject(),PlainTextFileType.INSTANCE,false);
        try {
            WriteCommandAction.runWriteCommandAction(getProject(),()->caller.insertString(0,"// unsaved \u4e16\u754c\ud83d\ude42\n"));
            String dirty=caller.getText();var snapshot=capture();var plan=addParameterPlan(snapshot);
            assertEquals(api,definition.getText());assertEquals(external,closed.getText());assertEquals(app,Files.readString(directory.resolve("app/call.go")));
            GoRefactorApply.apply(getProject(),snapshot,plan,"Add parameter to verified project calls");
            var undo=UndoManager.getInstance(getProject());var textEditor=TextEditorProvider.getInstance().getTextEditor(editor);
            for(int cycle=0;cycle<2;cycle++) {
                assertTrue(definition.getText().contains("first string, extra int, second string"));
                assertTrue(caller.getText().contains("api.Join(left(),7, right())"));
                assertTrue(closed.getText().contains("api.Join(\"c\",7, \"d\")"));
                assertEquals(app,Files.readString(directory.resolve("app/call.go")));assertTrue(manager().isDocumentUnsaved(caller));
                assertEquals(definition.getText(),Files.readString(directory.resolve("api/join.go")));assertEquals(closed.getText(),Files.readString(directory.resolve("api/join_test.go")));
                assertTrue(undo.isUndoAvailable(textEditor));undo.undo(textEditor);
                assertEquals(api,definition.getText());assertEquals(dirty,caller.getText());assertEquals(external,closed.getText());
                assertEquals(api,Files.readString(directory.resolve("api/join.go")));assertEquals(external,Files.readString(directory.resolve("api/join_test.go")));
                assertTrue(undo.isRedoAvailable(textEditor));undo.redo(textEditor);
            }
        }finally{EditorFactory.getInstance().releaseEditor(editor);}
    }
    public void testAddParameterStaleCallerRejectsWholeBatch()throws Exception {
        String api="package api\nfunc Join(first string, second string) string {return first+second}\n";
        var definition=source("api/join.go",api);var caller=source("app/call.go","package app\nfunc Call(){api.Join(left(),right())}\n");
        var snapshot=capture();var plan=addParameterPlan(snapshot);
        WriteCommandAction.runWriteCommandAction(getProject(),()->caller.insertString(0,"// later user edit\n"));String changed=caller.getText();
        assertThrows(IllegalStateException.class,()->GoRefactorApply.apply(getProject(),snapshot,plan,"Add parameter rejected"));
        assertEquals(api,definition.getText());assertEquals(api,Files.readString(directory.resolve("api/join.go")));assertEquals(changed,caller.getText());
    }
    /** Representative planner insertions; the separate real-Go probe exercises AST generation. */
    private GoRefactorWorkspaceEdit addParameterPlan(GoRefactorSnapshot snapshot) {
        var sources=new ArrayList<GoRefactorWorkspaceEdit.Source>();var edits=new ArrayList<org.eclipse.lsp4j.jsonrpc.messages.Either<TextDocumentEdit,ResourceOperation>>();
        for(var entry:snapshot.entries()) {
            String uri=entry.file().toNioPath().toUri().toString(),text=entry.text();int version=entry.unsaved()?11:0;
            sources.add(new GoRefactorWorkspaceEdit.Source(uri,text,version));
            String token=text.contains("func Join")?"second string":text.contains("right()")?"right()":"\"d\"";
            int at=text.indexOf(token);assertTrue(at>=0);Position point=GoRefactorEdit.position(text,at);
            String insert=text.contains("func Join")?"extra int, ":"7, ";
            edits.add(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(new TextDocumentEdit(new VersionedTextDocumentIdentifier(uri,version),List.of(new TextEdit(new Range(point,point),insert)))));
        }
        return GoRefactorWorkspaceEdit.prepare(sources,new WorkspaceEdit(edits));
    }

    public void testSecondReadOnlyDocumentRejectsWholeBatch()throws Exception{
        var first=source("a.go","package demo\nvar Before = 1\n");var second=source("b.go",first.getText());var snapshot=capture();var plan=plan(snapshot);
        second.setReadOnly(true);
        try{assertThrows(IllegalStateException.class,()->GoRefactorApply.apply(getProject(),snapshot,plan,"Rejected"));}
        finally{second.setReadOnly(false);}
        assertEquals(snapshot.entries().getFirst().text(),first.getText());assertEquals(first.getText(),Files.readString(directory.resolve("a.go")));
    }
    public void testStaleSecondFileRetainsAllOriginalAndNewUserText()throws Exception{
        var first=source("a.go","package demo\nvar Before = 1\n");var second=source("b.go",first.getText());var snapshot=capture();var plan=plan(snapshot);
        WriteCommandAction.runWriteCommandAction(getProject(),()->second.setText("package demo\n// user's new contents\n"));
        assertThrows(IllegalStateException.class,()->GoRefactorApply.apply(getProject(),snapshot,plan,"Rejected"));
        assertTrue(first.getText().contains("Before"));assertTrue(second.getText().contains("user's new"));
    }
    public void testNewRefreshedCallerAfterPreviewRejectsWholeBatchBeforeAnyWrite()throws Exception{
        String original="package demo\nvar Before = 1\n";
        var first=source("a.go",original);var second=source("b.go",original);
        var snapshot=capture();var plan=plan(snapshot);snapshot.requireDiskCurrent();
        String caller="package demo\nfunc Later(){_ = Before}\n";source("later.go",caller);
        assertThrows(IllegalStateException.class,()->GoRefactorApply.apply(getProject(),snapshot,plan,"Rejected new caller"));
        assertEquals(original,first.getText());assertEquals(original,second.getText());
        assertEquals(original,Files.readString(directory.resolve("a.go")));assertEquals(original,Files.readString(directory.resolve("b.go")));
        assertEquals(caller,Files.readString(directory.resolve("later.go")));
    }
    public void testQueuedApplySeesUnrefreshedExternalCallerEvenWithRestoredDirectoryTimestamp()throws Exception{
        String original="package demo\nvar Before = 1\n";var document=source("a.go",original);
        var snapshot=capture();Path target=directory.resolve("extracted.go");var plan=createPlan(snapshot,target);snapshot.prepareTargets(plan);
        snapshot.requireDiskCurrent(); // Worker completed before a queued UI callback publishes the write.
        var directoryTime=Files.getLastModifiedTime(directory);
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();var completed=new java.util.concurrent.atomic.AtomicBoolean();
        String caller="package demo\nfunc Later(){_ = Before}\n";
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(()->{
            try{
                Files.writeString(directory.resolve("unrefreshed.go"),caller);
                Files.setLastModifiedTime(directory,directoryTime); // Timestamp-only guards must not pass this.
                GoRefactorApply.apply(getProject(),snapshot,plan,"Rejected queued new caller");
            }catch(Throwable error){failure.set(error);}finally{completed.set(true);}
        },com.intellij.openapi.application.ModalityState.current());
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Queued refactoring boundary",completed::get,5);
        assertInstanceOf(failure.get(),IllegalStateException.class);
        assertTrue(failure.get().getMessage(),failure.get().getMessage().contains("inventory"));
        assertEquals(original,document.getText());assertEquals(original,Files.readString(directory.resolve("a.go")));
        assertEquals(caller,Files.readString(directory.resolve("unrefreshed.go")));assertFalse(Files.exists(target));
    }
    public void testExternalFileBecomingSourceDirectoryWithSameNameRejectsPreview()throws Exception{
        String original="package demo\nvar Before = 1\n";var document=source("a.go",original);
        Path parent=directory.resolve("notes");Files.writeString(parent,"ordinary metadata");root.refresh(false,true);
        var snapshot=capture();var plan=plan(snapshot);snapshot.requireDiskCurrent();
        Files.delete(parent);Files.createDirectory(parent);Files.writeString(parent.resolve("caller.go"),"package nested\nfunc Later(){}\n");
        assertThrows(IllegalStateException.class,()->GoRefactorApply.apply(getProject(),snapshot,plan,"Rejected new directory"));
        assertEquals(original,document.getText());assertEquals(original,Files.readString(directory.resolve("a.go")));
        assertTrue(Files.exists(parent.resolve("caller.go")));
    }
    public void testDeletedUntouchedSourceRejectsBeforeEditingOtherFiles()throws Exception{
        String original="package demo\nvar Before = 1\n";var document=source("a.go",original);source("untouched.go",original);
        var snapshot=capture();var plan=onlyFilePlan(snapshot,document);snapshot.requireDiskCurrent();
        Files.delete(directory.resolve("untouched.go"));
        try{
            assertThrows(IllegalStateException.class,()->GoRefactorApply.apply(getProject(),snapshot,plan,"Rejected removed caller"));
            assertEquals(original,document.getText());assertEquals(original,Files.readString(directory.resolve("a.go")));
        }finally{Files.writeString(directory.resolve("untouched.go"),original);}
    }
    public void testMovedUntouchedSourceRejectsBeforeEditingOtherFiles()throws Exception{
        String original="package demo\nvar Before = 1\n";var document=source("a.go",original);source("untouched.go",original);
        Path parent=Files.createDirectory(directory.resolve("existing"));root.refresh(false,true);
        var snapshot=capture();var plan=onlyFilePlan(snapshot,document);snapshot.requireDiskCurrent();
        Files.move(directory.resolve("untouched.go"),parent.resolve("moved.go"));
        try{
            assertThrows(IllegalStateException.class,()->GoRefactorApply.apply(getProject(),snapshot,plan,"Rejected moved caller"));
            assertEquals(original,document.getText());assertEquals(original,Files.readString(directory.resolve("a.go")));
            assertEquals(original,Files.readString(parent.resolve("moved.go")));
        }finally{Files.move(parent.resolve("moved.go"),directory.resolve("untouched.go"));}
    }
    public void testExcludedSubtreeAndExistingMetadataContentChangesDoNotInvalidateInventory()throws Exception{
        String original="package demo\nvar Before = 1\n";var document=source("a.go",original);
        Path excluded=Files.createDirectory(directory.resolve("excluded"));Path metadata=directory.resolve("workspace.xml");Files.writeString(metadata,"before");root.refresh(false,true);
        var excludedFile=LocalFileSystem.getInstance().findFileByNioFile(excluded);assertNotNull(excludedFile);
        WriteAction.run(()->PsiTestUtil.addExcludedRoot(getModule(),excludedFile));
        var snapshot=capture();var plan=plan(snapshot);snapshot.requireDiskCurrent();
        Files.writeString(excluded.resolve("ignored.go"),"package ignored\nfunc New(){}\n");Files.writeString(metadata,"new IDE state");
        GoRefactorApply.apply(getProject(),snapshot,plan,"Apply with unchanged source inventory");
        assertEquals(original.replace("Before","After"),document.getText());assertEquals("new IDE state",Files.readString(metadata));
    }
    private GoRefactorWorkspaceEdit onlyFilePlan(GoRefactorSnapshot snapshot,Document target){
        var sources=new ArrayList<GoRefactorWorkspaceEdit.Source>();String uri=null;
        for(var entry:snapshot.entries()){
            String current=entry.file().toNioPath().toUri().toString();sources.add(new GoRefactorWorkspaceEdit.Source(current,entry.text(),0));
            if(entry.document()==target)uri=current;
        }
        assertNotNull(uri);int start=target.getText().indexOf("Before");
        return GoRefactorWorkspaceEdit.prepare(sources,new WorkspaceEdit(Map.of(uri,List.of(new TextEdit(
                new Range(GoRefactorEdit.position(target.getText(),start),GoRefactorEdit.position(target.getText(),start+6)),"After")))));
    }
    public void testExternalDiskChangeAndMovedPathRejectSnapshot()throws Exception{
        var source=source("a.go","package demo\nvar Before = 1\n");var snapshot=capture();
        Files.writeString(directory.resolve("a.go"),"package demo\n// external text length changed\n");
        assertThrows(IllegalStateException.class,snapshot::requireDiskCurrent);
        manager().getFile(source).refresh(false,false);snapshot=capture();var saved=snapshot;
        WriteAction.run(()->manager().getFile(source).rename(this,"moved.go"));
        assertThrows(IllegalStateException.class,saved::requireCurrent);
    }
    public void testDiskWriteAfterWorkerValidationIsRejectedWithoutVfsRefresh()throws Exception {
        String original="package demo\nvar Before = 1\n";
        var first=source("a.go",original);var second=source("b.go",original);var snapshot=capture();var plan=plan(snapshot);
        snapshot.requireDiskCurrent(); // Models completed worker validation, before the queued EDT publication.
        String external="package demo\n// independent external editor contents\n";
        Files.writeString(directory.resolve("b.go"),external);
        assertEquals("Cached document must not silently refresh the race away",original,second.getText());
        assertThrows(IllegalStateException.class,()->GoRefactorApply.apply(getProject(),snapshot,plan,"Rejected"));
        assertEquals(original,first.getText());assertEquals(original,Files.readString(directory.resolve("a.go")));
        assertEquals(external,Files.readString(directory.resolve("b.go")));
    }
    public void testSymlinkSourceIsNotAcceptedAsProjectTarget()throws Exception{
        Path outside=Files.createTempFile("goforge-refactor-external-",".go");
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(getTestRootDisposable(),outside.toRealPath().toString());
        try{
            Files.writeString(outside,"package demo\nvar Before = 1\n");Files.createSymbolicLink(directory.resolve("link.go"),outside);
            root.refresh(false,true);var snapshot=capture();
            assertTrue(snapshot.entries().isEmpty());assertThrows(IllegalArgumentException.class,()->snapshot.entry(directory.resolve("link.go").toUri().toString()));
        }finally{Files.deleteIfExists(directory.resolve("link.go"));Files.deleteIfExists(outside);}
    }
    private GoRefactorWorkspaceEdit createPlan(GoRefactorSnapshot snapshot,Path destination){
        return createPlan(snapshot,destination,"package demo\nvar Extracted = 2\n");
    }
    private GoRefactorWorkspaceEdit createPlan(GoRefactorSnapshot snapshot,Path destination,String newText){
        var first=snapshot.entries().getFirst();String uri=first.file().toNioPath().toUri().toString(),fresh=destination.toUri().toString();
        var source=new GoRefactorWorkspaceEdit.Source(uri,first.text(),0);int start=first.text().indexOf("Before");
        var oldEdit=new TextDocumentEdit(new VersionedTextDocumentIdentifier(uri,0),List.of(new TextEdit(new Range(GoRefactorEdit.position(first.text(),start),GoRefactorEdit.position(first.text(),start+6)),"After")));
        var newEdit=new TextDocumentEdit(new VersionedTextDocumentIdentifier(fresh,0),List.of(new TextEdit(new Range(new Position(0,0),new Position(0,0)),newText)));
        return GoRefactorWorkspaceEdit.prepare(List.of(source),new WorkspaceEdit(List.of(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(oldEdit),org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(new CreateFile(fresh)),org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(newEdit))));
    }
    public void testCreateFilePreviewApplyAndGlobalUndoRedoRestoreFileExistence()throws Exception{
        String original="package demo\nvar Before = 1\n";var document=source("a.go",original);var snapshot=capture();Path target=directory.resolve("new.go");
        var plan=createPlan(snapshot,target);snapshot.prepareTargets(plan);assertFalse(Files.exists(target));assertEquals(original,document.getText());
        var editor=EditorFactory.getInstance().createEditor(document,getProject(),PlainTextFileType.INSTANCE,false);
        try{
            GoRefactorApply.apply(getProject(),snapshot,plan,"Extract to file");assertEquals(original.replace("Before","After"),document.getText());assertEquals("package demo\nvar Extracted = 2\n",Files.readString(target));
            var undo=UndoManager.getInstance(getProject());var textEditor=TextEditorProvider.getInstance().getTextEditor(editor);assertTrue(undo.isUndoAvailable(textEditor));undo.undo(textEditor);
            assertEquals(original,document.getText());assertFalse("Undo must remove the newly created file",Files.exists(target));
            assertTrue(undo.isRedoAvailable(textEditor));undo.redo(textEditor);assertEquals(original.replace("Before","After"),document.getText());assertEquals("package demo\nvar Extracted = 2\n",Files.readString(target));
        }finally{EditorFactory.getInstance().releaseEditor(editor);}
    }
    public void testFileCreatedAfterPreviewRefusesWholeBatchWithoutOverwrite()throws Exception{
        String original="package demo\nvar Before = 1\n";var document=source("a.go",original);var snapshot=capture();Path target=directory.resolve("new.go");var plan=createPlan(snapshot,target);snapshot.prepareTargets(plan);
        Files.writeString(target,"external owner");assertThrows(IllegalStateException.class,()->GoRefactorApply.apply(getProject(),snapshot,plan,"Rejected"));
        assertEquals(original,document.getText());assertEquals("external owner",Files.readString(target));
    }
    public void testNewFileOutsideProjectOrSymlinkParentIsRefused()throws Exception{
        source("a.go","package demo\nvar Before = 1\n");var snapshot=capture();Path outside=Files.createTempDirectory("goforge-refactor-parent-");
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(getTestRootDisposable(),outside.toRealPath().toString());
        try{
            var external=createPlan(snapshot,outside.resolve("new.go"));assertThrows(IllegalArgumentException.class,()->snapshot.prepareTargets(external));
            Files.createSymbolicLink(directory.resolve("linked"),outside);root.refresh(false,true);
            var linked=createPlan(snapshot,directory.resolve("linked/new.go"));assertThrows(IllegalArgumentException.class,()->snapshot.prepareTargets(linked));
            assertFalse(Files.exists(outside.resolve("new.go")));
        }finally{Files.deleteIfExists(directory.resolve("linked"));Files.deleteIfExists(outside);}
    }
    public void testParentReplacedAfterPreviewRefusesAllEdits()throws Exception{
        String original="package demo\nvar Before = 1\n";var document=source("a.go",original);Path parent=Files.createDirectory(directory.resolve("newdir"));root.refresh(false,true);
        var snapshot=capture();var plan=createPlan(snapshot,parent.resolve("new.go"));snapshot.prepareTargets(plan);
        Files.move(parent,directory.resolve("oldparent"));Files.createDirectory(parent);
        assertThrows(IllegalStateException.class,()->GoRefactorApply.apply(getProject(),snapshot,plan,"Rejected"));assertEquals(original,document.getText());assertFalse(Files.exists(parent.resolve("new.go")));
    }
    public void testHardlinkAliasesRefuseSnapshot()throws Exception{
        source("a.go","package demo\nvar Before = 1\n");Files.createLink(directory.resolve("alias.go"),directory.resolve("a.go"));root.refresh(false,true);
        assertThrows(IllegalStateException.class,this::capture);
    }

    public void testHardlinkAliasesWithMissingFileKeysAreRejected()throws Exception {
        Path original=directory.resolve("original.go"),alias=directory.resolve("alias.go");
        Files.writeString(original,"package demo\n");Files.createLink(alias,original);
        var actual=GoRefactorSnapshot.DiskIdentity.read(original);
        var identity=new GoRefactorSnapshot.DiskIdentity(new Object(),actual.size(),actual.modified());
        var missing=new GoRefactorSnapshot.DiskIdentity(null,identity.size(),identity.modified());
        for(boolean firstMissing:new boolean[]{false,true}) {
            var aliases=new GoRefactorSnapshot.SourceAliases();
            aliases.add(original,firstMissing?missing:identity);
            assertThrows(IllegalStateException.class,()->aliases.add(alias,firstMissing?identity:missing));
        }
        var aliases=new GoRefactorSnapshot.SourceAliases();aliases.add(original,missing);
        assertThrows(IllegalStateException.class,()->aliases.add(alias,missing));
    }
    public void testSameSizeIndependentFilesWithMissingFileKeysRemainDistinct()throws Exception {
        Path first=directory.resolve("first.go"),second=directory.resolve("second.go");
        Files.writeString(first,"package demo\n");Files.writeString(second,"package demo\n");
        var identity=GoRefactorSnapshot.DiskIdentity.read(first);
        var missing=new GoRefactorSnapshot.DiskIdentity(null,identity.size(),identity.modified());
        var aliases=new GoRefactorSnapshot.SourceAliases();aliases.add(first,missing);aliases.add(second,missing);
        assertFalse(Files.isSameFile(first,second));
    }

    private GoRefactorSnapshot captureWithUnavailableFileKeys()throws Exception {
        root.refresh(false,true);
        return GoRefactorSnapshot.capture(getProject(),root,new EmptyProgressIndicator(),path->{
            var disk=GoRefactorSnapshot.DiskIdentity.read(path);
            return new GoRefactorSnapshot.DiskIdentity(null,disk.size(),disk.modified());
        });
    }
    public void testLateHardlinkWithUnavailableKeysRefusesWorkerAndApplyBeforeWrites()throws Exception {
        String original="package demo\nvar Before = 1\n";
        var first=source("a.go",original);var second=source("b.go",original);
        Path firstPath=directory.resolve("a.go"),secondPath=directory.resolve("b.go");
        var commonTime=java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(firstPath,commonTime);Files.setLastModifiedTime(secondPath,commonTime);
        var snapshot=captureWithUnavailableFileKeys();var edits=plan(snapshot);snapshot.requireDiskCurrent();
        Files.delete(secondPath);Files.createLink(secondPath,firstPath);
        // The replacement retains bytes, size and mtime, and VFS has not refreshed.
        assertEquals(snapshot.entry(secondPath.toUri().toString()).disk().modified(),Files.getLastModifiedTime(secondPath));
        var workerRefusal=org.junit.Assert.assertThrows(IllegalStateException.class,snapshot::requireDiskCurrent);
        assertTrue(workerRefusal.getMessage(),workerRefusal.getMessage().contains("hard-link aliases"));
        var applyRefusal=org.junit.Assert.assertThrows(IllegalStateException.class,()->GoRefactorApply.apply(getProject(),snapshot,edits,"Refuse late alias"));
        assertTrue(applyRefusal.getMessage(),applyRefusal.getMessage().contains("hard-link aliases"));
        for(var doc:List.of(first,second)){assertEquals(original,doc.getText());assertFalse(manager().isDocumentUnsaved(doc));}
        assertEquals(original,Files.readString(firstPath));assertEquals(original,Files.readString(secondPath));
    }
    public void testUnaffectedSourceBecomingTargetAliasRefusesFinalApplyBeforeWrites()throws Exception {
        String original="package demo\nvar Before = 1\n";
        var affected=source("a.go",original);var untouched=source("b.go",original);
        Path affectedPath=directory.resolve("a.go"),untouchedPath=directory.resolve("b.go");
        var commonTime=java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(affectedPath,commonTime);Files.setLastModifiedTime(untouchedPath,commonTime);
        var snapshot=captureWithUnavailableFileKeys();var edits=onlyFilePlan(snapshot,affected);
        snapshot.requireDiskCurrent();snapshot.requireTargetDiskCurrent(edits);
        Files.delete(untouchedPath);Files.createLink(untouchedPath,affectedPath);
        // No worker rerun: model a replacement after the worker, before the write command.
        var refusal=org.junit.Assert.assertThrows(IllegalStateException.class,()->GoRefactorApply.apply(getProject(),snapshot,edits,"Refuse unaffected alias"));
        assertTrue(refusal.getMessage(),refusal.getMessage().contains("hard-link aliases"));
        for(var doc:List.of(affected,untouched)){assertEquals(original,doc.getText());assertFalse(manager().isDocumentUnsaved(doc));}
        assertEquals(original,Files.readString(affectedPath));assertEquals(original,Files.readString(untouchedPath));
    }
    public void testMissingKeyAliasQueriesAreBoundedAndDoNotWriteFiles()throws Exception {
        String original="package demo\n";var paths=new ArrayList<Path>();var aliases=new GoRefactorSnapshot.SourceAliases();
        var commonTime=java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L);
        var refusal=org.junit.Assert.assertThrows(IllegalStateException.class,()->{
            for(int i=0;i<=GoRefactorSnapshot.SourceAliases.MAX_NATIVE_QUERIES;i++){
                Path path=directory.resolve("source"+i+".go");Files.writeString(path,original);paths.add(path);
                Files.setLastModifiedTime(path,commonTime);
                var disk=GoRefactorSnapshot.DiskIdentity.read(path);
                aliases.add(path,new GoRefactorSnapshot.DiskIdentity(null,disk.size(),disk.modified()));
            }
        });
        assertTrue(refusal.getMessage(),refusal.getMessage().contains("bounded 256 native comparisons"));
        for(var path:paths)assertEquals(original,Files.readString(path));
    }
    public void testManyPhysicalSourcesWithStableKeysNeedNoNativeAliasQueries()throws Exception {
        String original="package demo\n";var aliases=new GoRefactorSnapshot.SourceAliases();var paths=new ArrayList<Path>();
        for(int i=0;i<64;i++){
            Path path=directory.resolve("different-time-"+i+".go");Files.writeString(path,original);paths.add(path);
            Files.setLastModifiedTime(path,java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L+i*2_000L));
            var disk=GoRefactorSnapshot.DiskIdentity.read(path);
            assertNotNull("Physical file identity is available on this host",disk.key());aliases.add(path,disk);
        }
        assertEquals("Stable keys need no pairwise native identity comparisons",0,aliases.nativeQueries());
        for(var path:paths)assertEquals(original,Files.readString(path));
    }
    public void testKeylessHardlinksWithDivergentReportedMetadataStillRefuse()throws Exception {
        Path first=directory.resolve("first.go"),alias=directory.resolve("alias.go");
        Files.writeString(first,"package demo\n");Files.createLink(alias,first);
        var aliases=new GoRefactorSnapshot.SourceAliases();
        aliases.add(first,new GoRefactorSnapshot.DiskIdentity(null,1,java.nio.file.attribute.FileTime.fromMillis(1_000L)));
        var refusal=org.junit.Assert.assertThrows(IllegalStateException.class,()->aliases.add(alias,
                new GoRefactorSnapshot.DiskIdentity(null,2,java.nio.file.attribute.FileTime.fromMillis(2_000L))));
        assertTrue(refusal.getMessage(),refusal.getMessage().contains("hard-link aliases"));
        assertEquals("package demo\n",Files.readString(first));assertEquals("package demo\n",Files.readString(alias));
    }
    public void testMissingKeyAliasChecksHonorCancellationBeforeNativeQueries()throws Exception {
        Path first=directory.resolve("first.go"),second=directory.resolve("second.go");
        Files.writeString(first,"package demo\n");Files.writeString(second,"package demo\n");
        var indicator=new EmptyProgressIndicator();var aliases=new GoRefactorSnapshot.SourceAliases(indicator::checkCanceled);
        var disk=GoRefactorSnapshot.DiskIdentity.read(first);
        var missing=new GoRefactorSnapshot.DiskIdentity(null,disk.size(),disk.modified());
        aliases.add(first,missing);indicator.cancel();
        assertThrows(com.intellij.openapi.progress.ProcessCanceledException.class,()->aliases.add(second,missing));
        assertEquals("package demo\n",Files.readString(first));assertEquals("package demo\n",Files.readString(second));
    }

    public void testCaseVariantCollisionAfterPreviewMatchesFilesystemAndDoesNotOverwrite()throws Exception{
        String original="package demo\nvar Before = 1\n";var document=source("a.go",original);var snapshot=capture();Path target=directory.resolve("new.go"),variant=directory.resolve("NEW.go");
        var plan=createPlan(snapshot,target);snapshot.prepareTargets(plan);Files.writeString(variant,"different owner");
        if(Files.exists(target))assertThrows(IllegalStateException.class,()->GoRefactorApply.apply(getProject(),snapshot,plan,"Rejected"));
        else snapshot.requireTargetDiskCurrent(plan); // Case-sensitive volumes have two distinct names.
        assertEquals(original,document.getText());assertEquals("different owner",Files.readString(variant));
    }

    /** Actual upstream VFS listener; the controlled server models gopls' asynchronous empty-file header edit. */
    public void testCreateApplyUndoRedoWithActualLspFileListenerDoesNotInjectAnotherPackage()throws Exception {
        String original="package demo\nvar Before = 1\n";var document=source("a.go",original);
        var listener=new CreationListenerFixture(true,false);
        var snapshot=capture();Path target=directory.resolve("new.go");var plan=createPlan(snapshot,target);snapshot.prepareTargets(plan);
        var editor=EditorFactory.getInstance().createEditor(document,getProject(),PlainTextFileType.INSTANCE,false);
        try {
            GoRefactorApply.apply(getProject(),snapshot,plan,"Extract with listener");saveAndRefresh(editor);
            assertEquals(0,listener.sentCreates.size());assertEquals("package demo\nvar Extracted = 2\n",Files.readString(target));
            var undo=UndoManager.getInstance(getProject());var textEditor=TextEditorProvider.getInstance().getTextEditor(editor);
            String initialStack=((com.intellij.openapi.command.impl.UndoManagerImpl)undo).dumpState(textEditor,"new file action ownership");
            assertTrue(initialStack.contains("FileUndoProvider$MyUndoableAction{refs=[new.go]"));
            assertFalse("The new file must not have a second document-edit undo action",initialStack.contains(", ref=new.go}"));
            for(int cycle=1;cycle<=2;cycle++) {
                assertTrue("Undo cycle "+cycle,undo.isUndoAvailable(textEditor));performIdeAction("Undo",editor);saveAndRefresh(editor);
                assertEquals(original,document.getText());assertEquals(original,Files.readString(directory.resolve("a.go")));assertFalse(Files.exists(target));
                assertTrue("Redo cycle "+cycle,undo.isRedoAvailable(textEditor));performIdeAction("Redo",editor);saveAndRefresh(editor);
                assertEquals(0,listener.sentCreates.size());assertEquals("package demo\nvar Extracted = 2\n",Files.readString(target));
                assertEquals(original.replace("Before","After"),document.getText());
                assertEquals(document.getText(),Files.readString(directory.resolve("a.go")));
            }
        } finally {EditorFactory.getInstance().releaseEditor(editor);}
    }

    public void testLaterUserEditOfCreatedFileBlocksGlobalUndoAndIsPreserved()throws Exception {
        String original="package demo\nvar Before = 1\n";var document=source("a.go",original);
        var snapshot=capture();Path target=directory.resolve("new.go");var plan=createPlan(snapshot,target);snapshot.prepareTargets(plan);
        var editor=EditorFactory.getInstance().createEditor(document,getProject(),PlainTextFileType.INSTANCE,false);
        try {
            GoRefactorApply.apply(getProject(),snapshot,plan,"Extract before user edit");saveAndRefresh(editor);
            var created=manager().getDocument(root.findChild("new.go"));assertNotNull(created);
            WriteCommandAction.runWriteCommandAction(getProject(),"User edits generated file",null,()->created.insertString(created.getTextLength(),"// user's later work\n"));
            manager().saveDocument(created);String edited=created.getText();
            // The pinned platform's DefaultUndoReportHandler throws instead of
            // opening Cannot Undo in unit-test mode. Preserve that refusal.
            var refusal=org.junit.Assert.assertThrows(RuntimeException.class,()->performIdeAction("Undo",editor));
            assertTrue(refusal.getMessage(),refusal.getMessage().contains("already changed"));
            assertTrue(refusal.getMessage(),refusal.getMessage().contains("new.go"));
            assertEquals(edited,created.getText());assertEquals(edited,Files.readString(target));
            assertEquals(original.replace("Before","After"),document.getText());
        } finally {EditorFactory.getInstance().releaseEditor(editor);}
    }

    public void testCreatedUtf8FileExactlyMatchesUnicodePreviewAndIsSaved()throws Exception {
        source("a.go","package demo\nvar Before = 1\n");var snapshot=capture();Path target=directory.resolve("new.go");
        String text="package demo\n// 世界 😀\nvar Extracted = \"你好\"\n";
        var plan=createPlan(snapshot,target,text);snapshot.prepareTargets(plan);
        GoRefactorApply.apply(getProject(),snapshot,plan,"Extract Unicode");
        org.junit.Assert.assertArrayEquals(text.getBytes(java.nio.charset.StandardCharsets.UTF_8),Files.readAllBytes(target));
        var document=manager().getDocument(root.findChild("new.go"));assertNotNull(document);
        assertEquals(text,document.getText());assertFalse(manager().isDocumentUnsaved(document));
    }

    public void testMalformedNewFileUnicodeRejectsWholeBatchBeforeMutation()throws Exception {
        String original="package demo\nvar Before = 1\n";var document=source("a.go",original);var snapshot=capture();Path target=directory.resolve("new.go");
        var plan=createPlan(snapshot,target,"package demo\n// \uD800\n");snapshot.prepareTargets(plan);
        assertThrows(IllegalArgumentException.class,()->GoRefactorApply.apply(getProject(),snapshot,plan,"Rejected Unicode"));
        assertFalse(Files.exists(target));assertEquals(original,document.getText());
        assertEquals(original,Files.readString(directory.resolve("a.go")));assertFalse(manager().isDocumentUnsaved(document));
    }

    public void testActualListenerStillTemplatesAnOrdinaryEmptyUserFile()throws Exception {
        assertOrdinaryFileTemplateUsesConfiguredLineSeparator("\n");
    }
    public void testActualListenerTemplatesOrdinaryEmptyUserFileWithConfiguredCrLf()throws Exception {
        assertOrdinaryFileTemplateUsesConfiguredLineSeparator("\r\n");
    }
    private void assertOrdinaryFileTemplateUsesConfiguredLineSeparator(String separator)throws Exception {
        var settings=com.intellij.application.options.CodeStyle.getSettings(getProject());
        String previous=settings.LINE_SEPARATOR;settings.LINE_SEPARATOR=separator;
        try {
            var listener=new CreationListenerFixture(true,false);
            WriteCommandAction.runWriteCommandAction(getProject(),()->{try{root.createChildData(this,"user.go");}catch(Exception e){throw new RuntimeException(e);}});
            drainEvents();assertEquals(1,listener.sentCreates.size());
            assertEquals("package main"+separator,Files.readString(directory.resolve("user.go")));
            assertEquals("package main\n",manager().getDocument(root.findChild("user.go")).getText());
        } finally {settings.LINE_SEPARATOR=previous;}
    }

    public void testQueuedGeneratedCreationThenUserRecreationKeepsTheirSeparateClassifications()throws Exception {
        var listener=new CreationListenerFixture(false,true);
        WriteCommandAction.runWriteCommandAction(getProject(),()->{
            try {var file=root.createChildData(GoRefactorApply.class,"reused.go");file.delete(this);root.createChildData(this,"reused.go");}
            catch(Exception e){throw new RuntimeException(e);}
        });
        assertTrue(listener.sentCreates.isEmpty());listener.flush();
        assertEquals("Only the later ordinary user creation reaches gopls",1,listener.sentCreates.size());
        assertEquals(directory.resolve("reused.go").toUri().getPath(),java.net.URI.create(listener.sentCreates.getFirst()).getPath());
    }

    public void testCapabilityPublishedBeforeListenerReadinessDoesNotPolluteNextCreation()throws Exception {
        var listener=new CreationListenerFixture(false,true);listener.createSupported=false;
        WriteCommandAction.runWriteCommandAction(getProject(),()->{
            try {root.createChildData(this,"startup.go").delete(this);}catch(Exception e){throw new RuntimeException(e);}
        });
        assertTrue(listener.queued.isEmpty());listener.createSupported=true;
        WriteCommandAction.runWriteCommandAction(getProject(),()->{
            try {root.createChildData(GoRefactorApply.class,"startup.go");}catch(Exception e){throw new RuntimeException(e);}
        });
        listener.flush();assertTrue("A startup false decision must not shift the later generated notification",listener.sentCreates.isEmpty());
    }

    public void testOldConnectionCannotConsumeNewConnectionCreationDecisions()throws Exception {
        var listener=new CreationListenerFixture(false,true);
        WriteCommandAction.runWriteCommandAction(getProject(),()->{
            try {root.createChildData(this,"reconnect.go").delete(this);}catch(Exception e){throw new RuntimeException(e);}
        });
        var oldConnection=listener.decisions;var newConnection=listener.guard.beginConnection();
        WriteCommandAction.runWriteCommandAction(getProject(),()->{
            try {root.createChildData(GoRefactorApply.class,"reconnect.go");}catch(Exception e){throw new RuntimeException(e);}
        });
        String uri=directory.resolve("reconnect.go").toUri().toString();
        assertFalse(oldConnection.test(uri));assertFalse(oldConnection.test(uri));
        assertTrue("The late old dispatcher must not steal this new connection's decision",newConnection.test(uri));
        assertFalse(newConnection.test(uri));
    }

    private static void drainEvents(){com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();}

    private void performIdeAction(String id,com.intellij.openapi.editor.Editor editor){
        String registeredId=switch(id){
            case "Undo" -> com.intellij.openapi.actionSystem.IdeActions.ACTION_UNDO;
            case "Redo" -> com.intellij.openapi.actionSystem.IdeActions.ACTION_REDO;
            default -> id;
        };
        var action=com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(registeredId);assertNotNull(registeredId,action);
        var context=com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT,getProject())
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR,editor)
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE,manager().getFile(editor.getDocument()))
                .add(com.intellij.openapi.actionSystem.PlatformCoreDataKeys.FILE_EDITOR,TextEditorProvider.getInstance().getTextEditor(editor))
                .add(com.intellij.openapi.actionSystem.PlatformCoreDataKeys.CONTEXT_COMPONENT,editor.getContentComponent()).build();
        var event=new com.intellij.testFramework.TestActionEvent(context,action);action.update(event);
        assertTrue(id+" action enabled",event.getPresentation().isEnabled());
        com.intellij.openapi.actionSystem.ex.ActionUtil.performActionDumbAwareWithCallbacks(action,event);
    }

    private void saveAndRefresh(com.intellij.openapi.editor.Editor editor){
        com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        performIdeAction("SaveAll",editor);
        var completed=new java.util.concurrent.atomic.AtomicBoolean();root.refresh(true,true,()->completed.set(true));
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(!completed.get()&&System.nanoTime()<deadline){drainEvents();java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);}
        assertTrue("Asynchronous VFS refresh completed",completed.get());drainEvents();
        var created=root.findChild("new.go");
        if(created!=null){
            var psi=com.intellij.psi.PsiManager.getInstance(getProject()).findFile(created);assertNotNull(psi);
            var doc=manager().getDocument(created);assertNotNull(doc);
            assertEquals(doc.getText(),psi.getText());assertEquals(0,EditorFactory.getInstance().getEditors(doc).length);
            performIdeAction("SaveAll",editor);drainEvents();
        }
        System.out.println(((com.intellij.openapi.command.impl.UndoManagerImpl)UndoManager.getInstance(getProject()))
                .dumpState(TextEditorProvider.getInstance().getTextEditor(editor),"after SaveAll + async refresh"));
    }

    private final class CreationListenerFixture {
        final List<String> sentCreates=new ArrayList<>();
        final List<java.util.function.Function<org.eclipse.lsp4j.services.LanguageServer,org.eclipse.lsp4j.services.LanguageServer>> queued=new ArrayList<>();
        final org.eclipse.lsp4j.services.LanguageServer server;
        final GoRefactorFileCreationGuard guard;
        final java.util.function.Predicate<String> decisions;
        boolean createSupported=true;
        CreationListenerFixture(boolean simulateTemplate,boolean defer) {
            var capabilities=new ServerCapabilities();var workspace=new WorkspaceServerCapabilities();
            var operations=new FileOperationsServerCapabilities();var options=new FileOperationOptions();
            var pattern=new FileOperationPattern();pattern.setGlob("**/*.go");var filter=new FileOperationFilter(pattern);filter.setScheme("file");
            options.setFilters(List.of(filter));operations.setDidCreate(options);workspace.setFileOperations(operations);capabilities.setWorkspace(workspace);
            var definition=com.redhat.devtools.lsp4ij.LanguageServersRegistry.getInstance().getServerDefinition("goforge.gopls");assertNotNull(definition);
            // Constructing the real wrapper installs its package-private LSPFileListener.
            var wrapper=new com.redhat.devtools.lsp4ij.LanguageServerWrapper(getProject(),definition){
                @Override public boolean isActive(){return !isDisposed();}
                @Override public boolean canCreateFilesSupported(){return createSupported;}
                @Override public boolean isDidCreateFilesSupported(VirtualFile file){return "go".equals(file.getExtension());}
                @Override public boolean isWillCreateFilesSupported(VirtualFile file){return false;}
                @Override public ServerCapabilities getServerCapabilitiesSync(){return capabilities;}
                @Override public java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.services.LanguageServer> sendNotification(java.util.function.Function<org.eclipse.lsp4j.services.LanguageServer,org.eclipse.lsp4j.services.LanguageServer> notification){
                    if(defer)queued.add(notification);else notification.apply(server);
                    return java.util.concurrent.CompletableFuture.completedFuture(server);
                }
            };
            com.intellij.openapi.util.Disposer.register(getTestRootDisposable(),wrapper);
            guard=new GoRefactorFileCreationGuard(wrapper);decisions=guard.beginConnection();
            var receiver=new org.eclipse.lsp4j.jsonrpc.Endpoint(){
                @Override public java.util.concurrent.CompletableFuture<?> request(String method,Object params){return java.util.concurrent.CompletableFuture.completedFuture(null);}
                @Override public void notify(String method,Object params){
                    if(!"workspace/didCreateFiles".equals(method))return;
                    for(var file:((CreateFilesParams)params).getFiles()){
                        sentCreates.add(file.getUri());
                        if(!simulateTemplate)continue;
                        var path=Path.of(java.net.URI.create(file.getUri()));
                        try {if(Files.size(path)!=0)continue;}catch(Exception e){throw new RuntimeException(e);}
                        // Capture the empty-file decision before the refactor fills
                        // its Document, then apply later, as gopls does over RPC.
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(()->
                            WriteCommandAction.runWriteCommandAction(getProject(),()->{
                                var vf=LocalFileSystem.getInstance().findFileByNioFile(path);assertNotNull(vf);
                                var doc=manager().getDocument(vf);assertNotNull(doc);doc.insertString(0,"package main\n");manager().saveDocument(doc);
                            }));
                    }
                }
            };
            server=org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints.toServiceObject(new GoRefactorFileCreationEndpoint(receiver,decisions),org.eclipse.lsp4j.services.LanguageServer.class);
        }
        void flush(){for(var notification:List.copyOf(queued))notification.apply(server);queued.clear();}
    }

}

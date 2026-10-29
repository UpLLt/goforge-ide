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
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import java.nio.file.*;
import java.util.*;

/** Transaction tests use the protocol-proven single-token edit shape; they do not claim to run Go's compiler. */
public final class GoMethodResultTypePlatformTest extends BasePlatformTestCase {
    private static final String DECLARATION="package api\nimport \"fmt\"\ntype worker struct{}\nfunc(w worker) render()int{fmt.Print(\"\");return 7}\n";
    private static final String CALLER="package api\nimport \"fmt\"\nfunc Call()string{w:=worker{};return fmt.Sprint(w.render())}\n";
    private Path directory;private VirtualFile root;
    @Override protected void setUp()throws Exception{
        super.setUp();directory=FileUtil.createTempDirectory("goforge-method-scalar-",null,true).toPath();
        root=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory);assertNotNull(root);
        WriteAction.run(()->PsiTestUtil.addContentRoot(getModule(),root));
        dev.goforge.lsp.GoClosedDocumentSynchronizer.getInstance(getProject());
    }
    @Override protected void tearDown()throws Exception{
        try{if(root!=null){VfsUtilCore.iterateChildrenRecursively(root,null,file->{var doc=manager().getCachedDocument(file);if(doc!=null)manager().reloadFromDisk(doc);return true;});WriteAction.run(()->PsiTestUtil.removeContentEntry(getModule(),root));}if(directory!=null)FileUtil.delete(directory.toFile());}
        finally{super.tearDown();}
    }
    private static FileDocumentManager manager(){return FileDocumentManager.getInstance();}
    private Document source(String name,String text)throws Exception{
        Path path=directory.resolve(name);Files.writeString(path,text);var file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);assertNotNull(file);return manager().getDocument(file);
    }
    private GoRefactorSnapshot capture()throws Exception{return GoRefactorSnapshot.capture(getProject(),root,new EmptyProgressIndicator());}
    private GoRefactorWorkspaceEdit plan(GoRefactorSnapshot snapshot){
        var sources=new ArrayList<GoRefactorWorkspaceEdit.Source>();var changes=new ArrayList<Either<TextDocumentEdit,ResourceOperation>>();
        for(var entry:snapshot.entries()){
            String uri=entry.file().toNioPath().toUri().toString();int version=entry.unsaved()?7:0;sources.add(new GoRefactorWorkspaceEdit.Source(uri,entry.text(),version));
            if(entry.file().getName().equals("method.go")){
                int at=entry.text().indexOf("render()int")+8;
                var edit=new TextEdit(new Range(GoRefactorEdit.position(entry.text(),at),GoRefactorEdit.position(entry.text(),at+3)),"int64");
                changes.add(Either.forLeft(GoAddParameter.documentEdit(new VersionedTextDocumentIdentifier(uri,version),List.of(edit))));
            }
        }
        return GoRefactorWorkspaceEdit.prepare(sources,new WorkspaceEdit(changes));
    }
    public void testPrivateMethodResultEditPreservesDirtyCallerAndSingleUndoRedo()throws Exception{
        var definition=source("method.go",DECLARATION);var caller=source("caller.go",CALLER);
        var callerEditor=EditorFactory.getInstance().createEditor(caller,getProject(),PlainTextFileType.INSTANCE,false);
        var definitionEditor=EditorFactory.getInstance().createEditor(definition,getProject(),PlainTextFileType.INSTANCE,false);
        try{
            WriteCommandAction.runWriteCommandAction(getProject(),()->caller.insertString(0,"// unsaved 世界🙂\n"));
            String dirty=caller.getText();assertTrue(manager().isDocumentUnsaved(caller));assertEquals(CALLER,Files.readString(directory.resolve("caller.go")));
            var snapshot=capture();var preview=plan(snapshot);assertEquals(1,preview.files().size());
            GoRefactorApply.apply(getProject(),snapshot,preview,"Change private method scalar result type");
            assertEquals(DECLARATION.replace("render()int{","render()int64{"),definition.getText());assertEquals(dirty,caller.getText());
            assertTrue(manager().isDocumentUnsaved(definition));manager().saveDocument(definition);
            assertEquals(definition.getText(),Files.readString(directory.resolve("method.go")));assertEquals(CALLER,Files.readString(directory.resolve("caller.go")));assertTrue(manager().isDocumentUnsaved(caller));
            // Undo from the changed declaration, where the user invoked the refactoring.
            // The unchanged caller still has its separate prior typing command.
            var textEditor=TextEditorProvider.getInstance().getTextEditor(definitionEditor);var undo=UndoManager.getInstance(getProject());
            assertTrue(undo.isUndoAvailable(textEditor));undo.undo(textEditor);assertEquals(DECLARATION,definition.getText());assertEquals(dirty,caller.getText());
            manager().saveDocument(definition);assertEquals(DECLARATION,Files.readString(directory.resolve("method.go")));assertTrue(manager().isDocumentUnsaved(caller));assertEquals(CALLER,Files.readString(directory.resolve("caller.go")));
            assertTrue(undo.isRedoAvailable(textEditor));undo.redo(textEditor);assertEquals(DECLARATION.replace("render()int{","render()int64{"),definition.getText());assertEquals(dirty,caller.getText());
            manager().saveDocument(definition);assertEquals(definition.getText(),Files.readString(directory.resolve("method.go")));assertEquals(CALLER,Files.readString(directory.resolve("caller.go")));assertTrue(manager().isDocumentUnsaved(caller));
        }finally{EditorFactory.getInstance().releaseEditor(definitionEditor);EditorFactory.getInstance().releaseEditor(callerEditor);}
    }
    public void testLaterCallerEditRefusesWholeMethodResultTransaction()throws Exception{
        var definition=source("method.go",DECLARATION);var caller=source("caller.go",CALLER);var snapshot=capture();var preview=plan(snapshot);
        var editor=EditorFactory.getInstance().createEditor(caller,getProject(),PlainTextFileType.INSTANCE,false);
        try{
            WriteCommandAction.runWriteCommandAction(getProject(),()->caller.insertString(0,"// later change\n"));String dirty=caller.getText();
            assertTrue(manager().isDocumentUnsaved(caller));assertEquals(CALLER,Files.readString(directory.resolve("caller.go")));
            org.junit.Assert.assertThrows(IllegalStateException.class,()->GoRefactorApply.apply(getProject(),snapshot,preview,"Stale method type change"));
            assertEquals(DECLARATION,definition.getText());assertEquals(DECLARATION,Files.readString(directory.resolve("method.go")));assertEquals(dirty,caller.getText());assertEquals(CALLER,Files.readString(directory.resolve("caller.go")));assertTrue(manager().isDocumentUnsaved(caller));
        }finally{EditorFactory.getInstance().releaseEditor(editor);}
    }
}

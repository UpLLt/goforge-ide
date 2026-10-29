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

/** Transaction tests use the planner capture-block shape; they do not claim to run Go's compiler. */
public final class GoFunctionReorderPlatformTest extends BasePlatformTestCase {
    private static final String DECLARATION="package api\nfunc reorder(a int,b int){}\n";
    private static final String CALLER="package api\nfunc call(){reorder(g(),h())}\n";
    private static final String REORDERED="package api\nfunc call(){{\n\tvar _goforgeArg0 int = g()\n\tvar _goforgeArg1 int = h()\n\treorder(_goforgeArg1, _goforgeArg0)\n}}\n";
    private Path directory;private VirtualFile root;
    @Override protected void setUp()throws Exception{
        super.setUp();directory=FileUtil.createTempDirectory("goforge-function-reorder-",null,true).toPath();
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
            String replacement=switch(entry.file().getName()){
                case "function.go" -> entry.text().replace("a int,b int","b int, a int");
                case "caller.go" -> REORDERED;
                default -> null;
            };
            if(replacement!=null){var edit=new TextEdit(new Range(new Position(),GoRefactorEdit.position(entry.text(),entry.text().length())),replacement);changes.add(Either.forLeft(GoAddParameter.documentEdit(new VersionedTextDocumentIdentifier(uri,version),List.of(edit))));}

        }
        return GoRefactorWorkspaceEdit.prepare(sources,new WorkspaceEdit(changes));
    }

    public void testCaptureBlockAndDeclarationAreOneUndoableTransaction()throws Exception{
        var definition=source("function.go",DECLARATION);var caller=source("caller.go",CALLER);var notes=source("helpers.go","package api\nfunc g()int{return 1};func h()int{return 2}\n");
        var definitionEditor=EditorFactory.getInstance().createEditor(definition,getProject(),PlainTextFileType.INSTANCE,false);
        var callerEditor=EditorFactory.getInstance().createEditor(caller,getProject(),PlainTextFileType.INSTANCE,false);
        var notesEditor=EditorFactory.getInstance().createEditor(notes,getProject(),PlainTextFileType.INSTANCE,false);
        try{
            String notesDisk=notes.getText();WriteCommandAction.runWriteCommandAction(getProject(),()->notes.insertString(0,"// unrelated unsaved 世界🙂\n"));String dirty=notes.getText();
            var snapshot=capture();var preview=plan(snapshot);assertEquals(2,preview.files().size());assertEquals(DECLARATION,Files.readString(directory.resolve("function.go")));assertEquals(CALLER,Files.readString(directory.resolve("caller.go")));
            GoRefactorApply.apply(getProject(),snapshot,preview,"Reorder scalar parameters preserving call order");
            assertEquals(DECLARATION.replace("a int,b int","b int, a int"),definition.getText());assertEquals(REORDERED,caller.getText());assertEquals(dirty,notes.getText());
            manager().saveDocument(definition);manager().saveDocument(caller);assertEquals(definition.getText(),Files.readString(directory.resolve("function.go")));assertEquals(REORDERED,Files.readString(directory.resolve("caller.go")));assertEquals(notesDisk,Files.readString(directory.resolve("helpers.go")));assertTrue(manager().isDocumentUnsaved(notes));
            var textEditor=TextEditorProvider.getInstance().getTextEditor(definitionEditor);var undo=UndoManager.getInstance(getProject());assertTrue(undo.isUndoAvailable(textEditor));undo.undo(textEditor);
            assertEquals(DECLARATION,definition.getText());assertEquals(CALLER,caller.getText());assertEquals(dirty,notes.getText());manager().saveDocument(definition);manager().saveDocument(caller);assertEquals(DECLARATION,Files.readString(directory.resolve("function.go")));assertEquals(CALLER,Files.readString(directory.resolve("caller.go")));
            assertTrue(undo.isRedoAvailable(textEditor));undo.redo(textEditor);assertEquals(REORDERED,caller.getText());assertEquals(DECLARATION.replace("a int,b int","b int, a int"),definition.getText());manager().saveDocument(definition);manager().saveDocument(caller);assertEquals(REORDERED,Files.readString(directory.resolve("caller.go")));assertTrue(manager().isDocumentUnsaved(notes));assertEquals(dirty,notes.getText());assertEquals(notesDisk,Files.readString(directory.resolve("helpers.go")));
        }finally{EditorFactory.getInstance().releaseEditor(notesEditor);EditorFactory.getInstance().releaseEditor(callerEditor);EditorFactory.getInstance().releaseEditor(definitionEditor);}
    }
    public void testLaterCallerTypingRejectsDeclarationAndCaptureBlockTogether()throws Exception{
        var definition=source("function.go",DECLARATION);var caller=source("caller.go",CALLER);var editor=EditorFactory.getInstance().createEditor(caller,getProject(),PlainTextFileType.INSTANCE,false);
        try{var snapshot=capture();var preview=plan(snapshot);WriteCommandAction.runWriteCommandAction(getProject(),()->caller.insertString(0,"// later typing\n"));String dirty=caller.getText();assertTrue(manager().isDocumentUnsaved(caller));
            org.junit.Assert.assertThrows(IllegalStateException.class,()->GoRefactorApply.apply(getProject(),snapshot,preview,"Stale function reorder"));assertEquals(DECLARATION,definition.getText());assertEquals(DECLARATION,Files.readString(directory.resolve("function.go")));assertEquals(CALLER,Files.readString(directory.resolve("caller.go")));assertEquals(dirty,caller.getText());assertTrue(manager().isDocumentUnsaved(caller));
        }finally{EditorFactory.getInstance().releaseEditor(editor);}
    }
}

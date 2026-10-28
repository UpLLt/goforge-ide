package dev.goforge.refactor;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.eclipse.lsp4j.*;
import java.util.*;

public final class GoRefactorPlatformTest extends BasePlatformTestCase {
    private static final String URI = "file:///sample.go";
    private static final String BEFORE = "a + b";
    private static GoRefactorEdit plan() {
        return GoRefactorEdit.prepare(URI, BEFORE, 1, new WorkspaceEdit(Map.of(URI, List.of(
                new TextEdit(new Range(new Position(0, 0), new Position(0, 1)), "alpha"),
                new TextEdit(new Range(new Position(0, 4), new Position(0, 5)), "beta")))));
    }
    public void testPreviewDoesNotWriteAndApplyIsOneUndoableCommand() {
        myFixture.configureByText(PlainTextFileType.INSTANCE, BEFORE);
        var document = myFixture.getEditor().getDocument(); var edit = plan();
        assertEquals(BEFORE, document.getText());
        GoRefactorApply.apply(getProject(), document, document.getModificationStamp(), edit, "Extract");
        assertEquals("alpha + beta", document.getText());
        var editor = TextEditorProvider.getInstance().getTextEditor(myFixture.getEditor());
        var undo = UndoManager.getInstance(getProject());
        assertTrue(undo.isUndoAvailable(editor)); undo.undo(editor); assertEquals(BEFORE, document.getText());
        assertTrue(undo.isRedoAvailable(editor)); undo.redo(editor); assertEquals("alpha + beta", document.getText());
    }
    public void testStalePreviewKeepsNewUserText() {
        myFixture.configureByText(PlainTextFileType.INSTANCE, BEFORE);
        var document = myFixture.getEditor().getDocument(); long stamp = document.getModificationStamp(); var edit = plan();
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText("user changed text"));
        assertThrows(IllegalStateException.class, () -> GoRefactorApply.apply(getProject(), document, stamp, edit, "Extract"));
        assertEquals("user changed text", document.getText());
    }
    public void testActionIsRegistered() { assertInstanceOf(ActionManager.getInstance().getAction("GoForge.Refactor"), GoRefactorAction.class); }
    public void testReadOnlyDocumentIsRetained() {
        myFixture.configureByText(PlainTextFileType.INSTANCE, BEFORE);
        var document = myFixture.getEditor().getDocument(); document.setReadOnly(true);
        try { assertThrows(IllegalStateException.class, () -> GoRefactorApply.apply(getProject(), document, document.getModificationStamp(), plan(), "Extract")); }
        finally { document.setReadOnly(false); }
        assertEquals(BEFORE, document.getText());
    }
}

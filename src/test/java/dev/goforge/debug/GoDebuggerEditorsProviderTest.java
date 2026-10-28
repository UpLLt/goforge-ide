package dev.goforge.debug;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.xdebugger.evaluation.EvaluationMode;
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.dap.evaluation.DAPExpressionCodeFragment;
import dev.goforge.language.GoFileType;
import dev.goforge.language.GoLanguage;

public final class GoDebuggerEditorsProviderTest extends BasePlatformTestCase {
    public void testExpressionDocumentRemainsAvailableDuringUncommittedPaste() {
        var context = myFixture.addFileToProject("main.go", "package main\nfunc main() {}\n");
        var provider = new GoDebuggerEditorsProvider(null);
        var manager = PsiDocumentManager.getInstance(getProject());
        var document = provider.createDocument(getProject(), XExpressionImpl.fromText(""),
                context, EvaluationMode.EXPRESSION);
        var fragment = manager.getPsiFile(document);
        assertInstanceOf(fragment, DAPExpressionCodeFragment.class);
        assertSame(GoFileType.INSTANCE, fragment.getFileType());
        assertSame(GoLanguage.INSTANCE, fragment.getLanguage());
        assertEquals(fragment.getViewProvider().isEventSystemEnabled(), fragment.isPhysical());
        assertFalse(LanguageServersRegistry.getInstance().isFileSupported(fragment));
        assertFalse(LanguageServersRegistry.getInstance().isFileSupported(fragment.getVirtualFile(), GoLanguage.INSTANCE));
        assertEquals("", fragment.getNode().getText());

        // EditorPaste's highlighting requests the PSI's Document before it is committed.
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            document.insertString(0, "message");
            assertFalse(manager.isCommitted(document));
            assertSame(document, manager.getDocument(fragment));
        });
        manager.commitDocument(document);
        assertEquals("message", fragment.getText());
        assertSame(document, manager.getDocument(fragment));

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            document.replaceString(0, document.getTextLength(), "message + \"!\"");
            assertSame(document, manager.getDocument(fragment));
        });
        manager.commitDocument(document);
        assertEquals(document.getText(), fragment.getText());
        assertSame(GoLanguage.INSTANCE, context.getLanguage());
        assertSame(GoFileType.INSTANCE, context.getFileType());
    }
}

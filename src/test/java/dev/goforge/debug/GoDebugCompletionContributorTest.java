package dev.goforge.debug;

import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;

import java.util.Arrays;
import java.util.List;

public final class GoDebugCompletionContributorTest extends BasePlatformTestCase {
    @Override protected void tearDown() throws Exception {
        try {
            assertNoLanguageServer("after runtime completion");
        } finally { super.tearDown(); }
    }

    public void testActualCompletionOffersLocalsAndReplacesOnlyTypedPrefix() {
        var server = GoDebugCompletionEngineTest.fieldsServer();
        configureExpression("mes", server);
        var items = myFixture.completeBasic();
        assertNotNull(items);
        assertEquals(List.of("message", "messageCount"), Arrays.stream(items).map(i -> i.getLookupString()).sorted().toList());
        myFixture.getLookup().setCurrentItem(Arrays.stream(items).filter(i -> i.getLookupString().equals("message")).findFirst().orElseThrow());
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        assertEquals("message", myFixture.getEditor().getDocument().getText());
        assertEquals(List.of(1), server.references);
    }

    public void testActualSelectorCompletionPreservesReceiver() {
        var server = GoDebugCompletionEngineTest.fieldsServer();
        configureExpression("message + user.Na", server);
        var items = myFixture.completeBasic();
        assertNotNull(items);
        assertEquals(List.of("Name", "Nationality"), Arrays.stream(items).map(i -> i.getLookupString()).sorted().toList());
        myFixture.getLookup().setCurrentItem(Arrays.stream(items).filter(i -> i.getLookupString().equals("Name")).findFirst().orElseThrow());
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        assertEquals("message + user.Name", myFixture.getEditor().getDocument().getText());
        assertEquals(List.of(1, 10), server.references);
    }

    public void testComputedReceiverDoesNotIssueAnyDapRequest() {
        var server = GoDebugCompletionEngineTest.fieldsServer();
        configureExpression("buildUser().Na", server);
        var items = myFixture.completeBasic();
        assertTrue(items == null || items.length == 0);
        assertEquals(0, server.scopeRequests);
        assertEquals("buildUser().Na", myFixture.getEditor().getDocument().getText());
    }

    public void testNoPausedFrameProducesNoRuntimeCandidates() {
        var fragment = new GoDebugExpressionFragment(getProject(), "mes", () -> null);
        configureFragment(fragment);
        var items = myFixture.completeBasic();
        assertTrue(items == null || items.length == 0);
        assertEquals("mes", myFixture.getEditor().getDocument().getText());
    }

    private void configureExpression(String expression, GoDebugCompletionEngineTest.FixtureServer server) {
        configureFragment(new GoDebugExpressionFragment(getProject(), expression,
                () -> new GoDebugCompletionEngine.Context(server, 7, () -> true)));
    }

    private void configureFragment(GoDebugExpressionFragment fragment) {
        assertNoLanguageServer("before configuring debugger fragment");
        // Register the same event-enabled provider used by the debugger editor;
        // configuring a fresh ordinary .go file would not exercise this path.
        PsiManagerEx.getInstanceEx(getProject()).getFileManager().setViewProvider(fragment.getVirtualFile(), fragment.getViewProvider());
        myFixture.configureFromExistingVirtualFile(fragment.getVirtualFile());
        assertSame(fragment, myFixture.getFile());
        assertSame(myFixture.getEditor().getDocument(), PsiDocumentManager.getInstance(getProject()).getDocument(fragment));
        assertFalse(LanguageServersRegistry.getInstance().isFileSupported(fragment));
        myFixture.getEditor().getCaretModel().moveToOffset(fragment.getTextLength());
        assertNoLanguageServer("after configuring debugger fragment");
    }

    private void assertNoLanguageServer(String stage) {
        assertTrue(stage, LanguageServiceAccessor.getInstance(getProject()).getStartedServers().isEmpty());
    }
}

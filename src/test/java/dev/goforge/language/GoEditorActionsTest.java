package dev.goforge.language;

import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.RunAll;
import com.intellij.util.ThrowableRunnable;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition;

/** Executes platform editor actions, with no gopls process or substituted action handlers. */
public final class GoEditorActionsTest extends BasePlatformTestCase {
    private boolean oldSmartIndent, oldPair, oldInsertBrace;
    private CodeStyleSettings oldTemporary;
    private LanguageServerDefinition gopls;
    private boolean oldGoplsEnabled;
    private LanguageServiceAccessor accessor;
    @Override protected void setUp() throws Exception {
        super.setUp();
        accessor = LanguageServiceAccessor.getInstance(getProject());
        assertTrue("fixture must start without retained language-server wrappers", accessor.getStartedServers().isEmpty());
        gopls = LanguageServersRegistry.getInstance().getServerDefinition("goforge.gopls");
        assertNotNull(gopls);
        oldGoplsEnabled = gopls.isEnabled(getProject());
        // Exercise normal editor actions while the server is unavailable. The extension's
        // public enablement flag is process-local and restored after all fixture editors close.
        // getStartedServers includes even failed/unstarted wrappers, not just live processes.
        gopls.setEnabled(false, getProject());
        assertFalse(gopls.isEnabled(getProject()));
        var insight = CodeInsightSettings.getInstance();
        oldSmartIndent = insight.SMART_INDENT_ON_ENTER; oldPair = insight.AUTOINSERT_PAIR_BRACKET;
        oldInsertBrace = insight.INSERT_BRACE_ON_ENTER;
        insight.SMART_INDENT_ON_ENTER = true; insight.AUTOINSERT_PAIR_BRACKET = true;
        insight.INSERT_BRACE_ON_ENTER = true;
        var manager = CodeStyleSettingsManager.getInstance(getProject());
        oldTemporary = manager.getTemporarySettings();
        manager.setTemporarySettings(manager.getCurrentSettings().clone());
        indent(true);
    }
    @Override protected void runTestRunnable(ThrowableRunnable<Throwable> testRunnable) throws Throwable {
        Throwable actionFailure = null;
        try {
            super.runTestRunnable(testRunnable);
        } catch (Throwable failure) {
            actionFailure = failure;
            throw failure;
        } finally {
            try {
                assertTrue("basic editor actions must not create gopls wrappers", accessor.getStartedServers().isEmpty());
            } catch (Throwable isolationFailure) {
                // Preserve the action's original assertion if isolation also fails.
                if (actionFailure != null) actionFailure.addSuppressed(isolationFailure);
                else throw isolationFailure;
            }
        }
    }
    @Override protected void tearDown() throws Exception {
        var project = getProject();
        RunAll.runAll(
                () -> { if (accessor != null) accessor.projectClosing(project); },
                () -> {
                    var insight = CodeInsightSettings.getInstance(); insight.SMART_INDENT_ON_ENTER = oldSmartIndent; insight.AUTOINSERT_PAIR_BRACKET = oldPair;
                    insight.INSERT_BRACE_ON_ENTER = oldInsertBrace;
                    var manager = CodeStyleSettingsManager.getInstance(project);
                    if (oldTemporary == null) manager.dropTemporarySettings(); else manager.setTemporarySettings(oldTemporary);
                },
                () -> super.tearDown(),
                () -> { if (gopls != null) gopls.setEnabled(oldGoplsEnabled, project); });
    }
    private void indent(boolean tabs) {
        var options = CodeStyleSettingsManager.getInstance(getProject()).getCurrentSettings()
                .getCommonSettings(GoLanguage.INSTANCE).getIndentOptions();
        assertNotNull(options);options.USE_TAB_CHARACTER = tabs;options.INDENT_SIZE = tabs ? 4 : 2;options.TAB_SIZE = 4;
    }
    private void source(String text) { myFixture.configureByText(GoFileType.INSTANCE, text); }
    private void action(String id) { myFixture.performEditorAction(id); }
    private void enter(String before, String after) {
        source(before);
        action(IdeActions.ACTION_EDITOR_ENTER);
        assertEquals("Enter document text", after.replace("<caret>", ""), myFixture.getEditor().getDocument().getText());
        myFixture.checkResult(after);
    }

    public void testLineCommentActionToggleAndUndoRedoPreserveUnicodeAndTabs() {
        String original = "package p\nfunc f() {\n\t<selection>println(\"世界\")\n\tprintln(2)</selection>\n}";
        source(original);String initial = myFixture.getEditor().getDocument().getText();
        action(IdeActions.ACTION_COMMENT_LINE);String commented = myFixture.getEditor().getDocument().getText();
        assertEquals("package p\nfunc f() {\n\t//println(\"世界\")\n\t//println(2)\n}", commented);
        action(IdeActions.ACTION_UNDO);assertEquals(initial, myFixture.getEditor().getDocument().getText());
        action(IdeActions.ACTION_REDO);assertEquals(commented, myFixture.getEditor().getDocument().getText());
        action(IdeActions.ACTION_COMMENT_LINE);assertEquals(initial, myFixture.getEditor().getDocument().getText());
    }
    public void testLineCommentAtFinalLineWithoutNewline() {
        source("package p\nvar 世界 = 1<caret>");String original = myFixture.getEditor().getDocument().getText();
        action(IdeActions.ACTION_COMMENT_LINE);assertTrue(myFixture.getEditor().getDocument().getText().contains("//"));
        action(IdeActions.ACTION_COMMENT_LINE);assertEquals(original, myFixture.getEditor().getDocument().getText());
    }
    public void testBlockCommentActionToggleAndUndo() {
        source("package p\nvar value = <selection>1 + 2</selection>\n");String original = myFixture.getEditor().getDocument().getText();
        action(IdeActions.ACTION_COMMENT_BLOCK);assertEquals("package p\nvar value = /*1 + 2*/\n", myFixture.getEditor().getDocument().getText());
        action(IdeActions.ACTION_UNDO);assertEquals(original, myFixture.getEditor().getDocument().getText());
        action(IdeActions.ACTION_REDO);action(IdeActions.ACTION_COMMENT_BLOCK);assertEquals(original, myFixture.getEditor().getDocument().getText());
    }
    public void testBlockCommentRefusesTerminatorInsideStringWithoutChangingSource() {
        source("package p\n<selection>var s = \"*/ executable()\"</selection>\n");
        String original = myFixture.getEditor().getDocument().getText();
        action(IdeActions.ACTION_COMMENT_BLOCK);assertEquals(original, myFixture.getEditor().getDocument().getText());
    }
    public void testBlockCommentAroundExistingCommentKeepsAllSelectedCodeCommented() {
        source("package p\n<selection>var a = 1 /* note */\nvar b = 2</selection>\n");
        String original = myFixture.getEditor().getDocument().getText();
        action(IdeActions.ACTION_COMMENT_BLOCK);String commented = myFixture.getEditor().getDocument().getText();
        assertFalse(original.equals(commented));
        var lexer = new GoSourceLexer();lexer.start(commented, commented.indexOf('\n') + 1, commented.length());
        while (lexer.getTokenType() != null) {
            assertTrue("selected code must not escape comment blocks", lexer.getTokenType() == GoSourceLexer.BLOCK_COMMENT
                    || lexer.getTokenType() == com.intellij.psi.TokenType.WHITE_SPACE);
            lexer.advance();
        }
        action(IdeActions.ACTION_UNDO);assertEquals(original, myFixture.getEditor().getDocument().getText());
    }
    public void testTypingPairedDelimitersAndSkippingExistingCloser() {
        for (String pair : new String[]{"()", "[]", "{}"}) {
            source("package p\nvar x = <caret>");myFixture.type(pair.charAt(0));
            myFixture.checkResult("package p\nvar x = " + pair.charAt(0) + "<caret>" + pair.charAt(1));
            myFixture.type(pair.charAt(1));myFixture.checkResult("package p\nvar x = " + pair + "<caret>");
        }
    }
    public void testPairingSettingIsRespected() {
        CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET = false;
        source("package p\nfunc f() <caret>");myFixture.type('{');myFixture.checkResult("package p\nfunc f() {<caret>");
    }
    public void testBracesInCommentsAndLiteralsAreNotPaired() {
        for (String context : new String[]{"// text <caret>", "/* text <caret> */", "var s = \"text <caret>\"", "var s = `text <caret>`", "var r = '<caret>'"}) {
            source("package p\n" + context);myFixture.type('{');myFixture.checkResult("package p\n" + context.replace("<caret>", "{<caret>"));
        }
    }
    public void testMatchBraceActionIgnoresLiteralAndCommentBraces() {
        source("package p\nfunc f() <caret>{\n\tprintln(\"}\") // }\n\tif true { println(`{}`) }\n}");
        String original = myFixture.getEditor().getDocument().getText();
        action(IdeActions.ACTION_EDITOR_MATCH_BRACE);
        int target = myFixture.getEditor().getCaretModel().getOffset();
        int closer = original.lastIndexOf('}');
        assertTrue("navigation must reach the matching outer closing brace boundary", target == closer || target == closer + 1);
        assertEquals(original, myFixture.getEditor().getDocument().getText());
    }
    public void testEnterBetweenBracesUsesTabsAndOneUndoGroup() {
        String before = "package p\nfunc f() {<caret>}";
        enter(before, "package p\nfunc f() {\n\t<caret>\n}");
        action(IdeActions.ACTION_UNDO);myFixture.checkResult(before);
        action(IdeActions.ACTION_REDO);myFixture.checkResult("package p\nfunc f() {\n\t<caret>\n}");
    }
    public void testEnterAfterOpeningAndInsideExistingNestedBody() {
        enter("package p\nfunc f() {<caret>", "package p\nfunc f() {\n\t<caret>\n}");
        enter("package p\nfunc f() {\n\tif true {\n\t\tprintln(1)<caret>\n\t}\n}",
                "package p\nfunc f() {\n\tif true {\n\t\tprintln(1)\n\t\t<caret>\n\t}\n}");
    }
    public void testEnterUsesConfiguredSpacesAndAlignsClosingBrace() {
        indent(false);
        enter("package p\nfunc f() {<caret>}", "package p\nfunc f() {\n  <caret>\n}");
    }
    public void testEnterIgnoresBracesInTrailingCommentsAndStrings() {
        enter("package p\nfunc f() {\n\tprintln(\"{\") // } {<caret>\n}",
                "package p\nfunc f() {\n\tprintln(\"{\") // } {\n\t<caret>\n}");
    }
    public void testEnterAfterCallOpeningMatchesObservedContinuationAndUndoRedo() {
        String before = "package p\nfunc f() {\n\tprintln(<caret>\n\t\t\"continuation\",\n\t)\n}";
        String after = "package p\nfunc f() {\n\tprintln(\n\t\t<caret>\n\t\t\"continuation\",\n\t)\n}";
        enter(before, after);
        action(IdeActions.ACTION_UNDO); myFixture.checkResult(before);
        action(IdeActions.ACTION_REDO); myFixture.checkResult(after);
    }
    public void testEnterAfterCallArgumentCommaKeepsOneContinuationLevel() {
        enter("package p\nfunc f() {\n\tprintln(\n\t\t\"continuation\",<caret>\n\t)\n}",
                "package p\nfunc f() {\n\tprintln(\n\t\t\"continuation\",\n\t\t<caret>\n\t)\n}");
        action(IdeActions.ACTION_EDITOR_ENTER);
        myFixture.checkResult("package p\nfunc f() {\n\tprintln(\n\t\t\"continuation\",\n\t\t\n\t\t<caret>\n\t)\n}");
    }
    public void testContinuationUsesConfiguredWidthIndependentOfBlockIndent() {
        indent(false);
        var options = CodeStyleSettingsManager.getInstance(getProject()).getCurrentSettings()
                .getCommonSettings(GoLanguage.INSTANCE).getIndentOptions();
        options.CONTINUATION_INDENT_SIZE = 6;
        enter("package p\nfunc f() {\n  println(<caret>\n  )\n}",
                "package p\nfunc f() {\n  println(\n        <caret>\n  )\n}");
    }
    public void testNestedContinuationUsesItsOwnOpeningLine() {
        enter("package p\nfunc f() {\n\touter(\n\t\tinner(<caret>\n\t\t),\n\t)\n}",
                "package p\nfunc f() {\n\touter(\n\t\tinner(\n\t\t\t<caret>\n\t\t),\n\t)\n}");
    }
    public void testCompletedContinuationReturnsToTheEnclosingBody() {
        enter("package p\nfunc f() {\n\tprintln(\n\t\t1,\n\t)<caret>\n}",
                "package p\nfunc f() {\n\tprintln(\n\t\t1,\n\t)\n\t<caret>\n}");
    }
    public void testFunctionLiteralInsideCallKeepsBlockIndentRatherThanContinuationWidth() {
        var options = CodeStyleSettingsManager.getInstance(getProject()).getCurrentSettings()
                .getCommonSettings(GoLanguage.INSTANCE).getIndentOptions();
        options.CONTINUATION_INDENT_SIZE = 8;
        enter("package p\nfunc f() {\n\tapply(func() {<caret>})\n}",
                "package p\nfunc f() {\n\tapply(func() {\n\t\t<caret>\n\t})\n}");
    }
    public void testContinuationIgnoresDelimitersInTrailingCommentsAndLiteralArguments() {
        enter("package p\nfunc f() {\n\tprintln(\n\t\t\"([{)]}\", /* ) ] } */<caret>\n\t)\n}",
                "package p\nfunc f() {\n\tprintln(\n\t\t\"([{)]}\", /* ) ] } */\n\t\t<caret>\n\t)\n}");
    }
    public void testContinuationEnterWithMultipleCaretsIsOneUndoGroup() {
        String before = "package p\nfunc f() {\n\tfirst(<caret>\n\t\t1,\n\t)\n\tsecond(\n\t\t2,<caret>\n\t)\n}";
        String after = "package p\nfunc f() {\n\tfirst(\n\t\t<caret>\n\t\t1,\n\t)\n\tsecond(\n\t\t2,\n\t\t<caret>\n\t)\n}";
        enter(before, after);
        action(IdeActions.ACTION_UNDO); myFixture.checkResult(before);
        action(IdeActions.ACTION_REDO); myFixture.checkResult(after);
    }
    public void testEnterBeforeClosingParenthesisReturnsToItsOpeningLineIndent() {
        enter("package p\nfunc f() {\n\tprintln(\n\t\t1,<caret>)\n}",
                "package p\nfunc f() {\n\tprintln(\n\t\t1,\n\t<caret>)\n}");
    }
    public void testContinuationSmartIndentOffKeepsThePlatformFallback() {
        CodeInsightSettings.getInstance().SMART_INDENT_ON_ENTER = false;
        enter("package p\nfunc f() {\n\tprintln(<caret>\n\t)\n}",
                "package p\nfunc f() {\n\tprintln(\n\t<caret>\n\t)\n}");
    }
    public void testRawStringWithinContinuationStillStartsAnUnindentedLiteralLine() {
        enter("package p\nfunc f() {\n\tprintln(\n\t\t`left(<caret> right)`,\n\t)\n}",
                "package p\nfunc f() {\n\tprintln(\n\t\t`left(\n<caret>right)`,\n\t)\n}");
    }
    public void testEnterInRawStringPreservesLiteralIndentation() {
        enter("package p\nfunc f() {\n\ts := `left<caret>right`\n}",
                "package p\nfunc f() {\n\ts := `left\n<caret>right`\n}");
    }
    public void testEnterInUnterminatedRawStringDoesNotAddProgramIndent() {
        enter("package p\nfunc f() {\n\ts := `raw {<caret>",
                "package p\nfunc f() {\n\ts := `raw {\n<caret>");
    }
    public void testSmartRawStringEnterNormalizesNewLineIndentAndUndoRestoresSpacesAndTabs() {
        for (String suffix : new String[]{"   right", "\t\tright"}) {
            String before = "package p\nfunc f() {\n\ts := `left<caret>" + suffix + "`\n}";
            String after = "package p\nfunc f() {\n\ts := `left\n<caret>right`\n}";
            enter(before, after);
            action(IdeActions.ACTION_UNDO);myFixture.checkResult(before);
            action(IdeActions.ACTION_REDO);myFixture.checkResult(after);
        }
    }
    public void testSmartRawStringEnterWithinLeadingWhitespaceOnlyAdjustsNewLine() {
        enter("package p\nvar s = `left\n \t<caret>  right`",
                "package p\nvar s = `left\n \t\n<caret>right`");
    }
    public void testRawStringEnterReplacesSelectionAndUndoRestoresBytes() {
        String before = "package p\nfunc f() {\n\ts := `left<selection>selected</selection>   right`\n}";
        source(before);String original = myFixture.getEditor().getDocument().getText();
        action(IdeActions.ACTION_EDITOR_ENTER);
        myFixture.checkResult("package p\nfunc f() {\n\ts := `left\n<caret>right`\n}");
        action(IdeActions.ACTION_UNDO);assertEquals(original, myFixture.getEditor().getDocument().getText());
        action(IdeActions.ACTION_REDO);myFixture.checkResult("package p\nfunc f() {\n\ts := `left\n<caret>right`\n}");
    }
    public void testRawStringEnterWithMultipleCaretsIsOneUndoGroup() {
        String before = "package p\nfunc f() {\n\ta := `left<caret> right`\n\tb := `top<caret>\tbottom`\n}";
        String after = "package p\nfunc f() {\n\ta := `left\n<caret>right`\n\tb := `top\n<caret>bottom`\n}";
        enter(before, after);
        action(IdeActions.ACTION_UNDO);myFixture.checkResult(before);
        action(IdeActions.ACTION_REDO);myFixture.checkResult(after);
    }
    public void testEnterAfterClosedRawStringStillIndentsGoCode() {
        enter("package p\nfunc f() {\n\ts := `raw`<caret>\n}",
                "package p\nfunc f() {\n\ts := `raw`\n\t<caret>\n}");
    }
    public void testSmartRawStringEnterDoesNotCrossNewlineOrTrimLaterLiteralData() {
        enter("package p\nvar s = `left<caret> \t\n  later\ttext`",
                "package p\nvar s = `left\n<caret>\n  later\ttext`");
    }
    public void testRawStringSmartIndentOffRetainsPlatformEnterBehavior() {
        // This checks the unchanged 252 platform fallback, not a target-IDE comparison.
        CodeInsightSettings.getInstance().SMART_INDENT_ON_ENTER = false;
        enter("package p\nfunc f() {\n\ts := `left<caret>  right`\n}",
                "package p\nfunc f() {\n\ts := `left\n\t<caret>  right`\n}");
    }
    public void testSmartIndentOffDoesNotAddBlockIndent() {
        CodeInsightSettings.getInstance().SMART_INDENT_ON_ENTER = false;
        // Closing-brace insertion explicitly requests indentation even when smart
        // indentation is off. Disable that separate feature to isolate this setting.
        CodeInsightSettings.getInstance().INSERT_BRACE_ON_ENTER = false;
        enter("package p\nfunc f() {<caret>", "package p\nfunc f() {\n<caret>");
    }
}

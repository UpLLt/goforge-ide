package dev.goforge.language;

import com.intellij.codeInsight.editorActions.EnterHandler;
import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorModificationUtil;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiFile;

/** Smart Enter inside a raw literal starts an unindented line without copying Go indentation. */
public final class GoRawStringEnterHandler implements EnterHandlerDelegate {
    @Override public boolean invokeInsideIndent(int ignored, Editor editor, DataContext context) {
        // Keep the platform's leading-whitespace fast path from copying raw content.
        return EnterHandler.getLanguage(context) == GoLanguage.INSTANCE
                && CodeInsightSettings.getInstance().SMART_INDENT_ON_ENTER
                && editor.isInsertMode() && insideRawString(editor, editor.getCaretModel().getOffset());
    }

    @Override public Result preprocessEnter(PsiFile file, Editor editor, Ref<Integer> offset,
                                            Ref<Integer> advance, DataContext context,
                                            EditorActionHandler originalHandler) {
        if (file.getLanguage() != GoLanguage.INSTANCE || !editor.isInsertMode()
                || !CodeInsightSettings.getInstance().SMART_INDENT_ON_ENTER
                || !insideRawString(editor, offset.get())) return Result.Continue;
        // The platform has already removed this caret's selection. Match the observed
        // smart-Enter behavior: horizontal whitespace immediately to its right becomes
        // the new line's empty indent. Never cross a newline, a backtick or other data.
        // One document edit retains the ordinary multi-caret Undo/Redo group, including
        // restoration of every removed space/tab. The indent provider remains passive.
        var document = editor.getDocument();
        var text = document.getCharsSequence();
        int end = offset.get();
        while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) end++;
        document.replaceString(offset.get(), end, "\n");
        editor.getCaretModel().moveToOffset(offset.get() + 1);
        EditorModificationUtil.scrollToCaret(editor);
        return Result.Stop;
    }

    private static boolean insideRawString(Editor editor, int offset) {
        var text = editor.getDocument().getCharsSequence();
        if (offset <= 0 || offset > text.length()) return false;
        var token = editor.getHighlighter().createIterator(offset - 1);
        if (token.atEnd() || token.getTokenType() != GoSourceLexer.RAW_STRING || offset <= token.getStart()) return false;
        // Just after a closing backtick is Go code; an unfinished raw token at EOF
        // still belongs to the literal. Immediately before the closing tick is inside.
        return offset < token.getEnd() || token.getEnd() - token.getStart() < 2
                || text.charAt(token.getEnd() - 1) != '`';
    }
}

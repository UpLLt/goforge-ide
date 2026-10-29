package dev.goforge.language;

import com.intellij.application.options.CodeStyle;
import com.intellij.lang.Language;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.Nullable;
import static dev.goforge.language.GoSourceLexer.*;

/** Local, bounded block indentation. It never formats, saves or contacts a language server. */
public final class GoLineIndentProvider implements LineIndentProvider {
    private static final int MAX_CONTEXT_TOKENS = 20_000;

    @Override public boolean isSuitableFor(@Nullable Language language) { return language == GoLanguage.INSTANCE; }

    @Override public @Nullable String getLineIndent(Project project, Editor editor, Language language, int offset) {
        if (!isSuitableFor(language)) return null;
        Document document = editor.getDocument();
        if (offset < 0 || offset > document.getTextLength()) return DO_NOT_ADJUST;
        CharSequence text = document.getCharsSequence();
        int line = document.getLineNumber(offset);
        int lineStart = document.getLineStartOffset(line);
        int content = lineStart;
        while (content < document.getLineEndOffset(line) && horizontal(text.charAt(content))) content++;
        var at = editor.getHighlighter().createIterator(Math.min(offset, Math.max(0, text.length() - 1)));
        // A newline within a raw string is data, not Go indentation. In block comments
        // preserve the user's layout rather than inventing Javadoc syntax.
        if (!at.atEnd() && (at.getTokenType() == RAW_STRING || at.getTokenType() == BLOCK_COMMENT)
                && at.getStart() < offset) {
            boolean terminated = at.getTokenType() == RAW_STRING
                    ? at.getEnd() - at.getStart() >= 2 && text.charAt(at.getEnd() - 1) == '`'
                    : at.getEnd() - at.getStart() >= 4 && text.charAt(at.getEnd() - 2) == '*' && text.charAt(at.getEnd() - 1) == '/';
            if (offset < at.getEnd() || !terminated) return DO_NOT_ADJUST;
        }

        boolean closing = content < text.length() && text.charAt(content) == '}';
        if (closing) {
            var token = editor.getHighlighter().createIterator(content);
            closing = !token.atEnd() && token.getTokenType() == RBRACE;
        }
        // Find the enclosing real brace, balancing completed blocks and skipping
        // comments/literals as opaque lexer tokens. Stop conservatively on huge input.
        var iterator = editor.getHighlighter().createIterator(Math.max(0, lineStart - 1));
        int depth = 0;
        for (int scanned = 0; !iterator.atEnd() && scanned < MAX_CONTEXT_TOKENS; scanned++, iterator.retreat()) {
            if (iterator.getStart() >= lineStart) continue;
            IElementType type = iterator.getTokenType();
            if (type == RBRACE) depth++;
            else if (type == LBRACE) {
                if (depth-- == 0) {
                    String base = leading(document, text, document.getLineNumber(iterator.getStart()));
                    if (closing) return base;
                    CommonCodeStyleSettings.IndentOptions options = CodeStyle.getIndentOptions(project, document);
                    return base + indentUnit(options);
                }
            }
        }
        // Outside a known block, maintain the previous nonblank line's indentation.
        // Do not change indentation inside strings with synthetic brace-looking text.
        for (int previous = line - 1; previous >= 0 && line - previous <= 1000; previous--) {
            String indent = leading(document, text, previous);
            if (document.getLineStartOffset(previous) + indent.length() < document.getLineEndOffset(previous)) return indent;
        }
        return "";
    }

    private static String indentUnit(CommonCodeStyleSettings.IndentOptions options) {
        int width = Math.max(0, options.INDENT_SIZE);
        if (!options.USE_TAB_CHARACTER) return " ".repeat(width);
        int tab = Math.max(1, options.TAB_SIZE);
        return "\t".repeat(width / tab) + " ".repeat(width % tab);
    }
    private static String leading(Document document, CharSequence text, int line) {
        int start = document.getLineStartOffset(line), end = start;
        while (end < document.getLineEndOffset(line) && horizontal(text.charAt(end))) end++;
        return text.subSequence(start, end).toString();
    }
    private static boolean horizontal(char value) { return value == ' ' || value == '\t'; }
}

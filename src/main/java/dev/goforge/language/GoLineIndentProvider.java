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
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import java.util.ArrayDeque;
import static dev.goforge.language.GoSourceLexer.*;

/** Local, bounded block and delimiter indentation. Never formats, saves or contacts a server. */
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

        IElementType closing = null;
        if (content < text.length()) {
            var token = editor.getHighlighter().createIterator(content);
            if (!token.atEnd() && openerFor(token.getTokenType()) != null) closing = token.getTokenType();
        }
        // Match all delimiter kinds together: a completed call must not hide a body,
        // and a function literal inside a call keeps its own block indentation.
        var iterator = editor.getHighlighter().createIterator(Math.max(0, lineStart - 1));
        Opening opening = findOpening(iterator, lineStart, MAX_CONTEXT_TOKENS);
        if (opening != null) {
            String base = leading(document, text, document.getLineNumber(opening.offset()));
            if (closing != null) return openerFor(closing) == opening.type() ? base : DO_NOT_ADJUST;
            CommonCodeStyleSettings.IndentOptions options = CodeStyle.getIndentOptions(project, document);
            int width = opening.type() == LBRACE ? options.INDENT_SIZE : options.CONTINUATION_INDENT_SIZE;
            return base + indentUnit(options, width);
        }
        // Outside a known block, maintain the previous nonblank line's indentation.
        // Do not change indentation inside strings with synthetic brace-looking text.
        for (int previous = line - 1; previous >= 0 && line - previous <= 1000; previous--) {
            String indent = leading(document, text, previous);
            if (document.getLineStartOffset(previous) + indent.length() < document.getLineEndOffset(previous)) return indent;
        }
        return "";
    }

    record Opening(IElementType type, int offset) { }

    /** A passive backwards token scan; comments and literals remain opaque. */
    static @Nullable Opening findOpening(HighlighterIterator iterator, int before, int limit) {
        var completed = new ArrayDeque<IElementType>();
        for (int scanned = 0; !iterator.atEnd() && scanned < limit; scanned++, iterator.retreat()) {
            if (iterator.getStart() >= before) continue;
            IElementType type = iterator.getTokenType();
            IElementType expected = openerFor(type);
            if (expected != null) completed.push(expected);
            else if (type == LPAREN || type == LBRACKET || type == LBRACE) {
                if (completed.isEmpty()) return new Opening(type, iterator.getStart());
                // Malformed mixed delimiters have no trustworthy continuation anchor.
                if (completed.pop() != type) return null;
            }
        }
        return null;
    }
    private static @Nullable IElementType openerFor(IElementType closer) {
        return closer == RPAREN ? LPAREN : closer == RBRACKET ? LBRACKET : closer == RBRACE ? LBRACE : null;
    }
    private static String indentUnit(CommonCodeStyleSettings.IndentOptions options, int requestedWidth) {
        int width = Math.max(0, requestedWidth);
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

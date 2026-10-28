package dev.goforge.language;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Source-preserving fallback tokens, not a Go grammar or semantic lexer. */
public final class GoSourceLexer extends LexerBase {
    public static final IElementType SOURCE = new IElementType("GO_SOURCE", GoLanguage.INSTANCE);
    public static final IElementType IDENTIFIER = new IElementType("GO_IDENTIFIER", GoLanguage.INSTANCE);
    private CharSequence buffer = "";
    private int end;
    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;

    @Override public void start(@NotNull CharSequence text, int startOffset, int endOffset, int initialState) {
        buffer = text;
        end = endOffset;
        tokenStart = tokenEnd = startOffset;
        locateToken();
    }

    private void locateToken() {
        if (tokenStart >= end) { tokenEnd = end; tokenType = null; return; }
        int first = codePointAt(tokenStart);
        boolean whitespace = Character.isWhitespace(first);
        boolean identifier = isIdentifierStart(first);
        tokenType = whitespace ? TokenType.WHITE_SPACE : identifier ? IDENTIFIER : SOURCE;
        tokenEnd = tokenStart + Character.charCount(first);
        // Keep word ranges useful before semantic tokens arrive. Punctuation is
        // deliberately one code point per token, without claiming Go syntax parsing.
        while (tokenEnd < end) {
            int next = codePointAt(tokenEnd);
            if (!(whitespace ? Character.isWhitespace(next) : identifier && isIdentifierPart(next))) break;
            tokenEnd += Character.charCount(next);
        }
    }

    private int codePointAt(int offset) {
        return offset + 1 < end ? Character.codePointAt(buffer, offset) : buffer.charAt(offset);
    }
    private static boolean isIdentifierStart(int value) { return value == '_' || Character.isLetter(value); }
    private static boolean isIdentifierPart(int value) { return isIdentifierStart(value) || Character.isDigit(value); }

    @Override public int getState() { return 0; }
    @Override public @Nullable IElementType getTokenType() { return tokenType; }
    @Override public int getTokenStart() { return tokenStart; }
    @Override public int getTokenEnd() { return tokenEnd; }
    @Override public void advance() { tokenStart = tokenEnd; locateToken(); }
    @Override public @NotNull CharSequence getBufferSequence() { return buffer; }
    @Override public int getBufferEnd() { return end; }
}

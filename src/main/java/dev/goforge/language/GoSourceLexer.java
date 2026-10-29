package dev.goforge.language;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Lossless lexical contexts for editor actions; Go parsing and semantics remain with gopls. */
public final class GoSourceLexer extends LexerBase {
    public static final IElementType SOURCE = new IElementType("GO_SOURCE", GoLanguage.INSTANCE);
    public static final IElementType IDENTIFIER = new IElementType("GO_IDENTIFIER", GoLanguage.INSTANCE);
    public static final IElementType LINE_COMMENT = token("LINE_COMMENT"), BLOCK_COMMENT = token("BLOCK_COMMENT");
    public static final IElementType STRING = token("STRING"), RAW_STRING = token("RAW_STRING"), RUNE = token("RUNE");
    public static final IElementType LPAREN = token("LPAREN"), RPAREN = token("RPAREN");
    public static final IElementType LBRACKET = token("LBRACKET"), RBRACKET = token("RBRACKET");
    public static final IElementType LBRACE = token("LBRACE"), RBRACE = token("RBRACE");
    public static final IElementType COMMA = token("COMMA"), SEMICOLON = token("SEMICOLON"), COLON = token("COLON");
    private CharSequence buffer = "";
    private int end;
    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;

    private static IElementType token(String name) { return new IElementType("GO_" + name, GoLanguage.INSTANCE); }

    @Override public void start(@NotNull CharSequence text, int startOffset, int endOffset, int initialState) {
        buffer = text;
        end = endOffset;
        tokenStart = tokenEnd = startOffset;
        locateToken();
    }

    private void locateToken() {
        if (tokenStart >= end) { tokenEnd = end; tokenType = null; return; }
        int first = codePointAt(tokenStart);
        tokenEnd = tokenStart + Character.charCount(first);
        if (first == '/' && tokenEnd < end) {
            if (buffer.charAt(tokenEnd) == '/') {
                tokenType = LINE_COMMENT;
                while (tokenEnd < end && buffer.charAt(tokenEnd) != '\r' && buffer.charAt(tokenEnd) != '\n') tokenEnd++;
                return;
            }
            if (buffer.charAt(tokenEnd) == '*') {
                tokenType = BLOCK_COMMENT;
                tokenEnd++;
                while (tokenEnd < end) {
                    if (buffer.charAt(tokenEnd++) == '*' && tokenEnd < end && buffer.charAt(tokenEnd) == '/') {
                        tokenEnd++;
                        break;
                    }
                }
                return;
            }
        }
        if (first == '"' || first == '\'' || first == '`') {
            tokenType = first == '"' ? STRING : first == '\'' ? RUNE : RAW_STRING;
            while (tokenEnd < end) {
                char c = buffer.charAt(tokenEnd);
                if (first != '`' && (c == '\r' || c == '\n')) break;
                tokenEnd++;
                if (c == first) break;
                if (first != '`' && c == '\\' && tokenEnd < end
                        && buffer.charAt(tokenEnd) != '\r' && buffer.charAt(tokenEnd) != '\n') tokenEnd++;
            }
            return;
        }
        IElementType punctuation = switch (first) {
            case '(' -> LPAREN; case ')' -> RPAREN; case '[' -> LBRACKET; case ']' -> RBRACKET;
            case '{' -> LBRACE; case '}' -> RBRACE; case ',' -> COMMA; case ';' -> SEMICOLON; case ':' -> COLON;
            default -> null;
        };
        if (punctuation != null) { tokenType = punctuation; return; }
        boolean whitespace = Character.isWhitespace(first);
        boolean identifier = isIdentifierStart(first);
        tokenType = whitespace ? TokenType.WHITE_SPACE : identifier ? IDENTIFIER : SOURCE;
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

    // Multiline literals/comments are indivisible tokens. Every restartable token
    // boundary is therefore in the default state, including nonzero start offsets.
    @Override public int getState() { return 0; }
    @Override public @Nullable IElementType getTokenType() { return tokenType; }
    @Override public int getTokenStart() { return tokenStart; }
    @Override public int getTokenEnd() { return tokenEnd; }
    @Override public void advance() { tokenStart = tokenEnd; locateToken(); }
    @Override public @NotNull CharSequence getBufferSequence() { return buffer; }
    @Override public int getBufferEnd() { return end; }
}

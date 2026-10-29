package dev.goforge.language;

import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import static dev.goforge.language.GoSourceLexer.*;

public final class GoSourceLexerTest {
    private record Token(IElementType type, int start, int end, int state, String text) {}
    private static List<Token> tokens(String text, int start, int end, int state) {
        var lexer = new GoSourceLexer(); lexer.start(text, start, end, state);
        var result = new ArrayList<Token>();
        while (lexer.getTokenType() != null) {
            assertTrue("lexer must always make progress", lexer.getTokenEnd() > lexer.getTokenStart());
            result.add(new Token(lexer.getTokenType(), lexer.getTokenStart(), lexer.getTokenEnd(), lexer.getState(),
                    text.substring(lexer.getTokenStart(), lexer.getTokenEnd())));
            lexer.advance();
        }
        assertEquals(end, lexer.getTokenEnd());
        return result;
    }
    @Test public void commentsAndLiteralsKeepEmbeddedBracesOpaqueAndLossless() {
        String text = "// {世界}\r\n/* [ nested /* stops here */ }\n\"a\\\"{b}\" '\\'' `raw\r\n{}[]()`";
        var values = tokens(text, 0, text.length(), 0);
        assertEquals(text, values.stream().map(Token::text).reduce("", String::concat));
        assertEquals(List.of(LINE_COMMENT, TokenType.WHITE_SPACE, BLOCK_COMMENT, TokenType.WHITE_SPACE,
                RBRACE, TokenType.WHITE_SPACE, STRING, TokenType.WHITE_SPACE, RUNE, TokenType.WHITE_SPACE, RAW_STRING),
                values.stream().map(Token::type).toList());
    }
    @Test public void everyTokenBoundaryResumesAtNonzeroOffsetWithIdenticalRanges() {
        String text = "package 世界\r\nfunc 𐐀_2() { /*\r\n{}*/ value := `raw\r\n[]`; println(\"\\\"}\") }";
        var full = tokens(text, 0, text.length(), 0);
        for (int i = 1; i < full.size(); i++) {
            Token current = full.get(i);
            assertEquals("restart at " + current.start(), full.subList(i, full.size()),
                    tokens(text, current.start(), text.length(), current.state()));
        }
    }
    @Test public void malformedAndTruncatedTextStillMakesLosslessProgress() {
        for (String text : List.of("\"unterminated\n}", "'\\\n}", "/* open {", "`raw open [", "\ud800", "𐐀_2", "")) {
            var values = tokens(text, 0, text.length(), 0);
            assertEquals(text, values.stream().map(Token::text).reduce("", String::concat));
        }
        String truncated = "x𐐀y";
        assertEquals("\ud801", tokens(truncated, 1, 2, 0).getFirst().text());
    }
    @Test public void delimitersAndExistingCompletionWordRangesRemainDistinct() {
        String text = "greeting.世界_2() greeting..Other [x] {y},;:";
        var values = tokens(text, 0, text.length(), 0);
        assertTrue(values.stream().anyMatch(t -> t.type() == IDENTIFIER && t.text().equals("世界_2")));
        assertEquals(3, values.stream().filter(t -> t.type() == SOURCE && t.text().equals(".")).count());
        assertEquals(List.of(LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE, COMMA, SEMICOLON, COLON),
                values.stream().map(Token::type).filter(t -> t != SOURCE && t != IDENTIFIER && t != TokenType.WHITE_SPACE).toList());
    }
}

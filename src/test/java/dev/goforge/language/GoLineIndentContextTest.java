package dev.goforge.language;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.psi.tree.IElementType;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import static dev.goforge.language.GoSourceLexer.*;

/** Actual lossless Go lexing; no application, editor, server or substituted Enter action. */
public final class GoLineIndentContextTest {
    private record Token(IElementType type, int start, int end) { }
    private static final class Tokens implements HighlighterIterator {
        final List<Token> tokens = new ArrayList<>(); int index;
        Tokens(String source) {
            var lexer = new GoSourceLexer(); lexer.start(source);
            while (lexer.getTokenType() != null) {
                tokens.add(new Token(lexer.getTokenType(), lexer.getTokenStart(), lexer.getTokenEnd())); lexer.advance();
            }
            index = tokens.size() - 1;
        }
        public TextAttributes getTextAttributes() { return null; }
        public int getStart() { return tokens.get(index).start(); }
        public int getEnd() { return tokens.get(index).end(); }
        public IElementType getTokenType() { return tokens.get(index).type(); }
        public void advance() { index++; }
        public void retreat() { index--; }
        public boolean atEnd() { return index < 0 || index >= tokens.size(); }
        public Document getDocument() { return null; }
    }
    private static GoLineIndentProvider.Opening opening(String text) {
        return GoLineIndentProvider.findOpening(new Tokens(text), text.length(), 20_000);
    }
    private static void expect(String text, IElementType type, int offset) {
        var found = opening(text); assertNotNull(found); assertSame(type, found.type()); assertEquals(offset, found.offset());
    }
    @Test public void callOpeningAndArgumentCommaHaveTheSameAnchor() {
        String text = "package p\nfunc f() {\n\tprintln(";
        expect(text, LPAREN, text.lastIndexOf('('));
        expect(text + "\n\t\t\"continuation\",\n", LPAREN, text.lastIndexOf('('));
    }
    @Test public void multilineNestedCallChoosesNearestUnclosedOpening() {
        String text = "func f() {\n\touter(\n\t\tinner("; expect(text, LPAREN, text.lastIndexOf('('));
    }
    @Test public void completedCallAndIndexLeaveTheirEnclosingBody() {
        String text = "func f() {\n\tx := call(values[1])\n"; expect(text, LBRACE, text.indexOf('{'));
    }
    @Test public void bodyInsideCallWinsOverOuterParenthesis() {
        String text = "func f() {\n\tapply(func() {\n"; expect(text, LBRACE, text.lastIndexOf('{'));
    }
    @Test public void completedFunctionLiteralLeavesItsOuterCall() {
        String text = "func f() {\n\tapply(func() { println(1) },\n"; expect(text, LPAREN, text.indexOf("apply(") + 5);
    }
    @Test public void stringsRunesRawAndCommentsAreOpaque() {
        String text = "func f() {\n\tcall(\n\t\t\"([{)]}\", ')', `)]}`, /* ) ] } */ // ) ] }\n";
        expect(text, LPAREN, text.indexOf("call(") + 4);
    }
    @Test public void bracketContinuationAndCompletedCompositeLiteralBalanceTogether() {
        String text = "func f() {\n\tvalues[func() int { return 1 }() +\n";
        expect(text, LBRACKET, text.indexOf('['));
    }
    @Test public void malformedMixedDelimitersDoNotInventAnAnchor() {
        assertNull(opening("func f() {\n\tcall([1)\n"));
    }
    @Test public void currentLineTokensAndOversizedContextsAreNotUsedAsAnchors() {
        String text = "func f() {\n\tcall(\n)"; int current = text.lastIndexOf(')');
        var found = GoLineIndentProvider.findOpening(new Tokens(text), current, 20_000);
        assertNotNull(found); assertSame(LPAREN, found.type());
        assertNull(GoLineIndentProvider.findOpening(new Tokens("(" + "x ".repeat(20)), 41, 10));
        assertNull(opening("package p\n"));
    }
}

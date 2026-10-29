package dev.goforge.language;

import com.intellij.lang.BracePair;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import static dev.goforge.language.GoSourceLexer.*;

public final class GoBraceMatcher implements PairedBraceMatcher {
    private static final BracePair[] PAIRS = {
            new BracePair(LPAREN, RPAREN, false), new BracePair(LBRACKET, RBRACKET, false),
            new BracePair(LBRACE, RBRACE, true)
    };
    @Override public BracePair[] getPairs() { return PAIRS.clone(); }
    @Override public boolean isPairedBracesAllowedBeforeType(IElementType left, IElementType next) {
        return next == null || next == TokenType.WHITE_SPACE || next == LINE_COMMENT || next == BLOCK_COMMENT
                || next == RPAREN || next == RBRACKET || next == RBRACE || next == COMMA || next == SEMICOLON || next == COLON;
    }
    @Override public int getCodeConstructStart(PsiFile file, int openingBraceOffset) { return openingBraceOffset; }
}

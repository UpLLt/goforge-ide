package dev.goforge.language;

import com.intellij.codeInsight.editorActions.enter.EnterBetweenBracesNoCommitDelegate;
import com.intellij.psi.tree.IElementType;

/** Reuses normal Enter commands and commit-free lexical indentation. */
public final class GoEnterBetweenBracesDelegate extends EnterBetweenBracesNoCommitDelegate {
    @Override public boolean isCommentType(IElementType type) {
        return type == GoSourceLexer.LINE_COMMENT || type == GoSourceLexer.BLOCK_COMMENT;
    }
    @Override protected boolean isBracePair(char left, char right) { return left == '{' && right == '}'; }
}

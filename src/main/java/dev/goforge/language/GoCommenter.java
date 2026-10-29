package dev.goforge.language;

import com.intellij.lang.CodeDocumentationAwareCommenter;
import com.intellij.psi.PsiComment;
import com.intellij.psi.tree.IElementType;

/** Go comments use the platform's selection, multi-caret and undo-aware actions. */
public final class GoCommenter implements CodeDocumentationAwareCommenter {
    @Override public String getLineCommentPrefix() { return "//"; }
    @Override public String getBlockCommentPrefix() { return "/*"; }
    @Override public String getBlockCommentSuffix() { return "*/"; }
    @Override public String getCommentedBlockCommentPrefix() { return null; }
    @Override public String getCommentedBlockCommentSuffix() { return null; }
    // The platform uses these lexical identities to reject wrapping a string
    // containing */ and to safely split around existing, non-nesting comments.
    @Override public IElementType getLineCommentTokenType() { return GoSourceLexer.LINE_COMMENT; }
    @Override public IElementType getBlockCommentTokenType() { return GoSourceLexer.BLOCK_COMMENT; }
    @Override public IElementType getDocumentationCommentTokenType() { return null; }
    @Override public String getDocumentationCommentPrefix() { return null; }
    @Override public String getDocumentationCommentLinePrefix() { return null; }
    @Override public String getDocumentationCommentSuffix() { return null; }
    @Override public boolean isDocumentationComment(PsiComment comment) { return false; }
}

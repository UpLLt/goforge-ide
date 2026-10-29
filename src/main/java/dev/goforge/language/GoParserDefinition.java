package dev.goforge.language;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

/** Lossless file container only. Parsing, diagnostics and refactoring belong to gopls. */
public final class GoParserDefinition implements ParserDefinition {
    public static final IFileElementType FILE = new IFileElementType(GoLanguage.INSTANCE);

    @Override public @NotNull Lexer createLexer(Project project) { return new GoSourceLexer(); }
    @Override public @NotNull PsiParser createParser(Project project) {
        return (root, builder) -> {
            var file = builder.mark();
            while (!builder.eof()) builder.advanceLexer();
            file.done(root);
            return builder.getTreeBuilt();
        };
    }
    @Override public @NotNull IFileElementType getFileNodeType() { return FILE; }
    @Override public @NotNull TokenSet getWhitespaceTokens() { return TokenSet.create(TokenType.WHITE_SPACE); }
    @Override public @NotNull TokenSet getCommentTokens() { return TokenSet.create(GoSourceLexer.LINE_COMMENT, GoSourceLexer.BLOCK_COMMENT); }
    @Override public @NotNull TokenSet getStringLiteralElements() { return TokenSet.create(GoSourceLexer.STRING, GoSourceLexer.RAW_STRING, GoSourceLexer.RUNE); }
    @Override public @NotNull PsiElement createElement(ASTNode node) { return new ASTWrapperPsiElement(node); }
    @Override public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) { return new GoFile(viewProvider); }
    @Override public SpaceRequirements spaceExistenceTypeBetweenTokens(ASTNode left, ASTNode right) {
        return SpaceRequirements.MAY;
    }
}

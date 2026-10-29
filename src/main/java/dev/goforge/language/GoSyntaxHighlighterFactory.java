package dev.goforge.language;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Supplies lexical identity to editor actions without overriding gopls semantic colors. */
public final class GoSyntaxHighlighterFactory extends SyntaxHighlighterFactory {
    @Override public @NotNull SyntaxHighlighter getSyntaxHighlighter(@Nullable Project project, @Nullable VirtualFile file) {
        return new SyntaxHighlighterBase() {
            @Override public @NotNull Lexer getHighlightingLexer() { return new GoSourceLexer(); }
            @Override public TextAttributesKey @NotNull [] getTokenHighlights(IElementType type) { return EMPTY; }
        };
    }
}

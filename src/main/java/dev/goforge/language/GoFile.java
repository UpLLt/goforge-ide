package dev.goforge.language;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jetbrains.annotations.NotNull;

/** Minimal source PSI on which LSP4IJ layers semantic-token elements. */
public final class GoFile extends PsiFileBase {
    public GoFile(@NotNull FileViewProvider viewProvider) { super(viewProvider, GoLanguage.INSTANCE); }
    @Override public @NotNull FileType getFileType() { return GoFileType.INSTANCE; }
    @Override public String toString() { return "GoForge source file"; }
}

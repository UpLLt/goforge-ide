package dev.goforge.language;

import com.intellij.lang.LanguageFormattingRestriction;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

/** Route automatic Go formatting to the awaited save action; manual formatting stays enabled. */
public final class GoSaveFormattingRestriction implements LanguageFormattingRestriction {
    @Override public boolean isFormatterAllowed(@NotNull PsiElement context) { return true; }
    @Override public boolean isAutoFormatAllowed(@NotNull PsiElement context) {
        var file=context.getContainingFile();
        return file==null || file.getFileType()!=GoFileType.INSTANCE;
    }
}

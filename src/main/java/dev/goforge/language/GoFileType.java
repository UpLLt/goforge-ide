package dev.goforge.language;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/** Stable file-type identity for existing mappings and projects. */
public final class GoFileType extends LanguageFileType {
    public static final GoFileType INSTANCE = new GoFileType();

    private GoFileType() { super(GoLanguage.INSTANCE); }
    @Override public @NotNull String getName() { return "GoForge Go"; }
    @Override public @NotNull String getDescription() { return "Go source (GoForge)"; }
    @Override public @NotNull String getDefaultExtension() { return "go"; }
    @Override public Icon getIcon() { return AllIcons.FileTypes.Text; }
}

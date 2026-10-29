package dev.goforge.language;

import com.intellij.lang.Language;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider;
import org.jetbrains.annotations.NotNull;

/** Go indentation uses tabs by default; project/editor settings can override this. */
public final class GoCodeStyleSettingsProvider extends LanguageCodeStyleSettingsProvider {
    @Override public @NotNull Language getLanguage() { return GoLanguage.INSTANCE; }
    @Override public String getCodeSample(@NotNull SettingsType type) { return "package main\n\nfunc main() {\n\tprintln(\"Go\")\n}\n"; }
    @Override protected void customizeDefaults(@NotNull CommonCodeStyleSettings settings,
                                               @NotNull CommonCodeStyleSettings.IndentOptions options) {
        options.USE_TAB_CHARACTER = true;
        options.TAB_SIZE = 4;
        options.INDENT_SIZE = 4;
        options.CONTINUATION_INDENT_SIZE = 4;
        settings.LINE_COMMENT_AT_FIRST_COLUMN = false;
        settings.LINE_COMMENT_ADD_SPACE = false;
        settings.BLOCK_COMMENT_ADD_SPACE = false;
    }
}

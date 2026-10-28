package dev.goforge.language;

import com.intellij.lang.Language;

/** The editor's Go language identity; gopls owns the semantic model. */
public final class GoLanguage extends Language {
    public static final GoLanguage INSTANCE = new GoLanguage();
    private GoLanguage() { super("GoForgeGo"); }
}

package dev.goforge.language;

import com.intellij.lang.LanguageParserDefinitions;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.LSPIJEditorUtils;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.features.semanticTokens.viewProvider.LSPSemanticTokensFileViewProvider;

public final class GoLanguageIntegrationTest extends BasePlatformTestCase {
    public void testGoSourceHasDedicatedLanguageAndPreservesText() {
        String source = "// a comment\npackage main\n\nfunc main() { println(\"世界\") }\n";
        var file = myFixture.addFileToProject("main.go", source);
        assertSame(GoLanguage.INSTANCE, file.getLanguage());
        assertSame(GoFileType.INSTANCE, file.getFileType());
        assertEquals(source, file.getNode().getText());
        assertInstanceOf(LanguageParserDefinitions.INSTANCE.forLanguage(GoLanguage.INSTANCE), GoParserDefinition.class);
        assertInstanceOf(file.getViewProvider(), LSPSemanticTokensFileViewProvider.class);
    }

    public void testGoMappingIsPreservedWithoutInstallSuggestionEligibility() {
        var file = myFixture.addFileToProject("main.go", "package main\n");
        assertSame(GoFileType.INSTANCE, FileTypeManager.getInstance().getFileTypeByExtension("go"));
        assertTrue(LanguageServersRegistry.getInstance().isFileSupported(file));
        // These are the exact eligibility checks in LSP4IJ 0.21's suggestion provider.
        // The provider itself skips all unit-test-mode executions, so assert the real preconditions.
        assertFalse(LSPIJEditorUtils.isPlainTextFile(file));
        assertFalse(LSPIJEditorUtils.isAbstractFileTypeFile(file));
        assertFalse(LSPIJEditorUtils.isTextMateFile(file));
    }

    public void testWordRangesBeforeSemanticTokensExcludeQualifierAndPunctuation() {
        String source = "package main\nfunc main() { greeting.He(); greeting..Other(); greeting.世界_2() }\n";
        var file = myFixture.addFileToProject("main.go", source);
        var document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
        assertNotNull(document);
        for (String word : new String[]{"He", "Other", "世界_2"}) {
            int caret = source.indexOf(word) + word.length();
            var range = LSPIJUtils.getWordRangeAt(document, file, caret);
            assertNotNull(range);
            assertEquals(word, document.getText(range));
        }
        int dot = source.indexOf("..");
        assertEquals(".", file.findElementAt(dot).getText());
        assertEquals(".", file.findElementAt(dot + 1).getText());
        // LSP4IJ intentionally reports no word after a single punctuation token.
        assertNull(LSPIJUtils.getWordRangeAt(document, file, dot + 1));
    }
}

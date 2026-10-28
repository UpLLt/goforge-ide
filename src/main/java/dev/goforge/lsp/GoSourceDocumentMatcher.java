package dev.goforge.lsp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.redhat.devtools.lsp4ij.DocumentMatcher;
import org.jetbrains.annotations.NotNull;

/** Applies LSP4IJ's light-file exclusion even for direct language-server discovery. */
public final class GoSourceDocumentMatcher implements DocumentMatcher {
    @Override public boolean match(@NotNull VirtualFile file, @NotNull Project project) {
        // Debugger expressions and completion copies are not Go source documents.
        // LSP4IJ's registry excludes these too, but direct getLanguageServers calls
        // consult this mapping without first checking registry.isFileSupported.
        return !(file instanceof LightVirtualFile);
    }
}

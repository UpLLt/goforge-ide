package dev.goforge.testing;

import com.intellij.execution.Location;
import com.intellij.execution.PsiLocation;
import com.intellij.execution.testframework.sm.runner.SMTestLocator;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

/** Only session-owned opaque hints resolve, and only while the indexed source is still current. */
public final class GoTestLocator implements SMTestLocator {
    private final GoTestRunRegistry registry;
    public GoTestLocator(GoTestRunRegistry registry) { this.registry = registry; }
    @Override public @NotNull List<Location> getLocation(@NotNull String protocol, @NotNull String path,
                                                        @NotNull Project project, @NotNull GlobalSearchScope scope) {
        if (!protocol.equals(GoTestRunRegistry.PROTOCOL) || project != registry.project() || project.isDisposed()) return List.of();
        var target = registry.navigationTarget(protocol + "://" + path);
        if (target == null) return List.of();
        return ReadAction.compute(() -> {
            var file = LocalFileSystem.getInstance().findFileByNioFile(target.source().file());
            var base = project.getBaseDir();
            if (file == null || base == null || !file.isValid() || !scope.contains(file)) return List.of();
            String canonicalFile = file.getCanonicalPath(), canonicalBase = base.getCanonicalPath();
            if (canonicalFile == null || canonicalBase == null) return List.of();
            Path current = Path.of(canonicalFile).toAbsolutePath().normalize();
            if (!current.equals(target.source().file()) || !current.startsWith(Path.of(canonicalBase).toAbsolutePath().normalize())) return List.of();
            var document = registry.cachedDocument(target.source().file());
            if (document == null || !document.getText().equals(target.source().text())
                    || !PsiDocumentManager.getInstance(project).isCommitted(document)) return List.of();
            var psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile == null || !psiFile.getText().equals(target.source().text())) return List.of();
            int offset = target.declaration().nameOffset();
            String name = target.declaration().name();
            if (offset < 0 || offset + name.length() > document.getTextLength()
                    || !document.getText().regionMatches(offset, name, 0, name.length())) return List.of();
            // Semantic-token PSI may represent the whole document as one element.
            // Keep the validated AST name offset in the navigation descriptor.
            return List.of(new PsiLocation<>(project, psiFile) {
                @Override public OpenFileDescriptor getOpenFileDescriptor() {
                    return file.isValid() ? new OpenFileDescriptor(project, file, offset) : null;
                }
            });
        });
    }
    @Override public @NotNull ModificationTracker getLocationCacheModificationTracker(@NotNull Project project) {
        return ModificationTracker.EVER_CHANGED;
    }
}

package dev.goforge.debug;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.WrappingRunConfiguration;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import dev.goforge.testing.GoTestRunRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Refuses a changed selection after the normal platform save-before-debug boundary. */
public final class GoSelectedTestDebugGuard {
    private static final Key<GoSelectedTestDebugGuard> KEY = Key.create("goforge.debug.selected.test.sources");
    private record Entry(VirtualFile file, long fileStamp, Document document, long documentStamp, String text) { }
    private final Project project;
    private final List<Entry> entries;
    private GoSelectedTestDebugGuard(Project project, List<Entry> entries) {
        this.project = project;
        this.entries = List.copyOf(entries);
    }

    public static GoSelectedTestDebugGuard capture(Project project,
            Map<String, GoTestRunRegistry.PackageSources> sources) throws ExecutionException {
        List<Entry> entries = ReadAction.compute(() -> {
            if (project.isDisposed()) return List.of();
            var captured = new ArrayList<Entry>();
            for (var pkg : sources.values()) for (var snapshot : pkg.files()) {
                var file = LocalFileSystem.getInstance().findFileByNioFile(snapshot.file());
                var document = file == null ? null : FileDocumentManager.getInstance().getCachedDocument(file);
                if (file == null || !file.isValid() || document == null
                        || FileDocumentManager.getInstance().isDocumentUnsaved(document)
                        || !document.getText().equals(snapshot.text())) return List.of();
                captured.add(new Entry(file, file.getModificationStamp(), document,
                        document.getModificationStamp(), snapshot.text()));
            }
            return captured;
        });
        if (entries.isEmpty()) throw stale();
        return new GoSelectedTestDebugGuard(project, entries);
    }

    public void bind(DAPRunConfiguration configuration) { configuration.putCopyableUserData(KEY, this); }

    public void validate() throws ExecutionException {
        boolean valid = ReadAction.compute(() -> !project.isDisposed() && entries.stream().allMatch(entry ->
                entry.file().isValid() && entry.file().getModificationStamp() == entry.fileStamp()
                && entry.document().getModificationStamp() == entry.documentStamp()
                && !FileDocumentManager.getInstance().isDocumentUnsaved(entry.document())
                && entry.document().getText().equals(entry.text())));
        if (!valid) throw stale();
    }

    public static void validate(ExecutionEnvironment environment) throws ExecutionException {
        if (environment.getProject().isDisposed()) throw new ExecutionException("The debug project was closed.");
        var profile = WrappingRunConfiguration.unwrapRunProfile(environment.getRunProfile());
        if (profile instanceof DAPRunConfiguration configuration) {
            var guard = configuration.getCopyableUserData(KEY);
            if (guard != null) guard.validate();
        }
    }

    private static ExecutionException stale() {
        return new ExecutionException("The selected test sources are unsaved, changed or unavailable. "
                + "Save the files and select Debug again; no debug process was started.");
    }
}

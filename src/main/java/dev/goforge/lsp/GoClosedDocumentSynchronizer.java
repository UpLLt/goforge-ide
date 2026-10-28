package dev.goforge.lsp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandEvent;
import com.intellij.openapi.command.CommandListener;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Persists command edits to initially clean Go files that have no materialized editor. */
@Service(Service.Level.PROJECT)
public final class GoClosedDocumentSynchronizer implements Disposable, CommandListener, DocumentListener {
    private final Project project;
    private final GoOpenDocumentSynchronizer openDocuments;
    private final Map<Document, OwnedFile> changedFiles = new IdentityHashMap<>();
    private final Map<Document, VirtualFile> changedGoDocuments = new IdentityHashMap<>();
    private final Set<Document> observedDocuments = Collections.newSetFromMap(new IdentityHashMap<>());
    private int commandDepth;
    private boolean saving;
    private boolean disposed;

    public GoClosedDocumentSynchronizer(Project project) {
        this.project = project;
        this.openDocuments = new GoOpenDocumentSynchronizer(project, this::isOwnedGoDocument);
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(CommandListener.TOPIC, this);
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(this, this);
    }

    public static GoClosedDocumentSynchronizer getInstance(Project project) {
        return project.getService(GoClosedDocumentSynchronizer.class);
    }

    @Override public void commandStarted(@NotNull CommandEvent event) {
        if (disposed || saving || event.getProject() != project) return;
        if (commandDepth++ == 0) {
            changedFiles.clear();
            changedGoDocuments.clear();
            observedDocuments.clear();
        }
    }

    @Override public void beforeDocumentChange(@NotNull DocumentEvent event) {
        if (disposed || saving || commandDepth == 0 || project.isDisposed()
                || !ApplicationManager.getApplication().isDispatchThread()
                || CommandProcessor.getInstance().getCurrentCommandProject() != project) return;
        var document = event.getDocument();
        // Check the original state once, before this command makes the document dirty.
        if (!observedDocuments.add(document)) return;
        var manager = FileDocumentManager.getInstance();
        var file = manager.getFile(document);
        if (!isOwnedGoDocument(document, file)) return;
        changedGoDocuments.put(document, file);
        if (!manager.isDocumentUnsaved(document) && hasNoEditors(document)) {
            changedFiles.put(document, new OwnedFile(file, file.getPath(),
                    ProjectRootModificationTracker.getInstance(project).getModificationCount()));
        }
    }

    @Override public void commandFinished(@NotNull CommandEvent event) {
        if (disposed || saving || event.getProject() != project || commandDepth == 0) return;
        if (--commandDepth != 0) return;
        var batch = new IdentityHashMap<>(changedFiles);
        var changed = new IdentityHashMap<>(changedGoDocuments);
        changedFiles.clear();
        changedGoDocuments.clear();
        observedDocuments.clear();
        if (project.isDisposed()) return;
        saving = true;
        try {
            var manager = FileDocumentManager.getInstance();
            for (var entry : batch.entrySet()) {
                var document = entry.getKey();
                if (isStillEligible(document, entry.getValue()) && manager.isDocumentUnsaved(document)) {
                    // The whole workspace-edit command has completed. Saving only this
                    // changed closed file triggers LSP4IJ's watched-files notification path.
                    manager.saveDocument(document);
                }
            }
        } finally {
            saving = false;
        }
        openDocuments.synchronize(changed);
    }

    private static boolean hasNoEditors(Document document) {
        // Any materialized editor, including one in another project, must keep its buffer.
        return EditorFactory.getInstance().getEditors(document).length == 0;
    }

    private boolean isStillEligible(Document document, OwnedFile owned) {
        // Ownership was checked inside the write command. If roots or the path
        // changed, preserve the buffer instead of consulting the project index on EDT.
        var file = owned.file();
        return hasNoEditors(document) && isWritableGoDocument(document, file)
                && owned.path().equals(file.getPath())
                && owned.rootsVersion() == ProjectRootModificationTracker.getInstance(project).getModificationCount();
    }

    private boolean isOwnedGoDocument(Document document, VirtualFile file) {
        if (!isWritableGoDocument(document, file)) return false;
        var index = ProjectFileIndex.getInstance(project);
        if (index.isExcluded(file)) return false;
        if (index.isInContent(file)) return true;
        var projectDirectory = ProjectUtil.guessProjectDir(project);
        return projectDirectory != null && VfsUtilCore.isAncestor(projectDirectory, file, true);
    }

    private static boolean isWritableGoDocument(Document document, VirtualFile file) {
        return file != null && file.isValid() && file.isInLocalFileSystem() && file.isWritable()
                && document.isWritable() && "go".equals(file.getExtension());
    }

    private record OwnedFile(VirtualFile file, String path, long rootsVersion) {}

    @Override public void dispose() {
        disposed = true;
        commandDepth = 0;
        changedFiles.clear();
        changedGoDocuments.clear();
        observedDocuments.clear();
        openDocuments.dispose();
    }
}

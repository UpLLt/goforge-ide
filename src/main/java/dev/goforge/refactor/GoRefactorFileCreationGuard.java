package dev.goforge.refactor;

import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.vfs.AsyncFileListener;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.redhat.devtools.lsp4ij.LanguageServerWrapper;
import com.redhat.devtools.lsp4ij.features.files.operations.FileOperationsManager;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Records the origin of each creation before LSP4IJ queues its notification.
 * gopls may asynchronously insert a package header after didCreateFiles sees an
 * empty file. Refactoring and Undo/Redo already supply the complete contents.
 * Per-event queues, rather than a path flag, preserve ordinary creation at a
 * reused path even when notifications have not yet reached the dispatcher.
 */
public final class GoRefactorFileCreationGuard {
    private volatile Notifications notifications = new Notifications();

    public GoRefactorFileCreationGuard(LanguageServerWrapper wrapper) {
        VirtualFileManager.getInstance().addAsyncFileListener(events -> {
            if (!wrapper.isActive() || wrapper.isDisposed() || !wrapper.canCreateFilesSupported()) return null;
            return new AsyncFileListener.ChangeApplier() {
                @Override public void beforeVfsChange() {
                    if (!wrapper.isActive() || wrapper.isDisposed() || !wrapper.canCreateFilesSupported()) return;
                    var current = notifications;
                    // Same public filter implementation/capabilities used by
                    // the actual LSPFileListener, including its URI glob rules.
                    var operations = new FileOperationsManager(wrapper);
                    operations.setServerCapabilities(wrapper.getServerCapabilitiesSync());
                    var undo = UndoManager.getInstance(wrapper.getProject());
                    boolean replay = undo.isUndoInProgress() || undo.isRedoInProgress();
                    for (var event : events) {
                        String path; boolean directory;
                        if (event instanceof VFileCreateEvent create) {
                            path = create.getPath(); directory = create.isDirectory();
                        } else if (event instanceof VFileCopyEvent copy) {
                            path = copy.getNewParent().getPath() + "/" + copy.getNewChildName();
                            directory = copy.getFile().isDirectory();
                        } else continue;
                        if (operations.canDidCreateFiles(Path.of(path).toUri(), directory))
                            current.record(path, path.endsWith(".go") && (event.getRequestor() == GoRefactorApply.class || replay));
                    }
                }
            };
        }, wrapper);
    }

    /** Old dispatchers retain only their old queue, never a later connection's decisions. */
    public Predicate<String> beginConnection() {
        var next = new Notifications();
        notifications = next;
        return next::consume;
    }

    static final class Notifications {
        private final Map<Path, ArrayDeque<Boolean>> creations = new HashMap<>();
        synchronized void record(String path, boolean supplied) {
            creations.computeIfAbsent(Path.of(path).toAbsolutePath().normalize(), ignored -> new ArrayDeque<>()).addLast(supplied);
        }
        /** Called once for each file in the wrapper's ordered outgoing notification. */
        synchronized boolean consume(String uri) {
            final Path path;
            try { path = GoRefactorWorkspaceEdit.uriPath(uri); }
            catch (IllegalArgumentException ignored) { return false; }
            var queue = creations.get(path);
            if (queue == null || queue.isEmpty()) return false;
            boolean supplied = queue.removeFirst();
            if (queue.isEmpty()) creations.remove(path);
            return supplied;
        }
    }
}

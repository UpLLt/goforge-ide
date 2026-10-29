package dev.goforge.testing;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import dev.goforge.run.GoRunConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** One-use first-run snapshot guard, shared across the runner's configuration clone. */
public final class GoTestContextSelection {
    private static final Key<Guard> KEY = Key.create("goforge.test.context.snapshot");
    private static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;
    private static final class Guard {
        final VirtualFile file;
        final String text;
        final long documentStamp;
        final GoTestSelection selection;
        volatile long verifiedFileStamp = Long.MIN_VALUE;
        volatile boolean started;
        Guard(VirtualFile file, String text, long documentStamp, GoTestSelection selection) {
            this.file = file; this.text = text; this.documentStamp = documentStamp; this.selection = selection;
        }
    }
    private record State(boolean valid, long fileStamp) { }
    private GoTestContextSelection() { }
    public static void bind(GoRunConfiguration configuration, VirtualFile file, String text, long stamp) {
        configuration.putCopyableUserData(KEY, new Guard(file, text, stamp, configuration.getTestSelection()));
    }
    /** Explicit tree reruns select a current name, rather than reusing the original caret snapshot. */
    public static void clear(GoRunConfiguration configuration) { configuration.putCopyableUserData(KEY, null); }
    /** The execution copy and the saved configuration consume the same initial snapshot after process creation. */
    public static void copyBinding(GoRunConfiguration source, GoRunConfiguration destination) {
        destination.putCopyableUserData(KEY, source.getCopyableUserData(KEY));
    }
    /** Run before and after background preparation, and once immediately before actual process creation. */
    public static void validateBeforeRun(GoRunConfiguration configuration) throws ExecutionException {
        Guard guard = configuration.getCopyableUserData(KEY);
        if (guard == null || guard.started || !Objects.equals(guard.selection, configuration.getTestSelection())) return;
        State state = ReadAction.compute(() -> {
            if (!guard.file.isValid()) return new State(false, -1);
            Document document = FileDocumentManager.getInstance().getCachedDocument(guard.file);
            if (document != null && (document.getModificationStamp() != guard.documentStamp
                    || !document.getText().equals(guard.text) || FileDocumentManager.getInstance().isDocumentUnsaved(document)))
                return new State(false, -1);
            return new State(true, guard.file.getModificationStamp());
        });
        if (!state.valid()) throw stale();
        if (ApplicationManager.getApplication().isDispatchThread()) {
            if (guard.verifiedFileStamp != state.fileStamp()) throw stale();
            return;
        }
        try {
            Path path = Path.of(guard.file.getPath());
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_SOURCE_BYTES) throw stale();
            String saved = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
            if (!saved.equals(guard.text)) throw stale();
            boolean current = ReadAction.compute(() -> guard.file.isValid()
                    && guard.file.getModificationStamp() == state.fileStamp()
                    && (FileDocumentManager.getInstance().getCachedDocument(guard.file) == null
                    || FileDocumentManager.getInstance().getCachedDocument(guard.file).getModificationStamp() == guard.documentStamp));
            if (!current) throw stale();
            guard.verifiedFileStamp = state.fileStamp();
        } catch (ExecutionException error) { throw error; }
        catch (Exception error) { throw new ExecutionException("Could not verify the selected test source; save it and select the test again.", error); }
    }
    /** Call only after a process was successfully created. Future named reruns resolve current sources normally. */
    public static void started(GoRunConfiguration configuration) {
        Guard guard = configuration.getCopyableUserData(KEY);
        if (guard != null) guard.started = true;
    }
    private static ExecutionException stale() {
        return new ExecutionException("The selected test source changed or is not saved. Save this file and select Run again; no package fallback was started.");
    }
}

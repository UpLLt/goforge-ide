package dev.goforge.lsp;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import com.redhat.devtools.lsp4ij.LanguageServerWrapper;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;
import com.redhat.devtools.lsp4ij.DocumentContentSynchronizer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/** Connects changed, materialized Go buffers without saving their contents. */
final class GoOpenDocumentSynchronizer {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final Project project;
    private final BiPredicate<Document, VirtualFile> ownsDocument;
    private final Map<Document, VirtualFile> pending = new IdentityHashMap<>();
    private final Map<Document, ConnectionAttempt> attempts = new ConcurrentHashMap<>();
    private boolean synchronizing;

    GoOpenDocumentSynchronizer(Project project, BiPredicate<Document, VirtualFile> ownsDocument) {
        this.project = project;
        this.ownsDocument = ownsDocument;
    }

    void synchronize(Map<Document, VirtualFile> changed) {
        if (project.isDisposed()) return;
        for (var entry : changed.entrySet()) {
            var wrapper = isCandidate(entry.getKey(), entry.getValue())
                    ? wrapperFor(entry.getValue()) : null;
            if (wrapper != null) {
                var opened = wrapper.getOpenedDocument(wrapper.toUri(entry.getValue()));
                if (opened == null || opened.getSynchronizer() == null || attempts.containsKey(entry.getKey())) {
                    pending.put(entry.getKey(), entry.getValue());
                }
            }
        }
        pending.entrySet().removeIf(entry -> !isCandidate(entry.getKey(), entry.getValue()));
        attempts.entrySet().removeIf(entry -> !entry.getValue().file.isValid());
        if (synchronizing) return; // Modal progress pumps events; accumulate reentrant edits without another modal.
        if (pending.isEmpty()) return;
        if (!ApplicationManager.getApplication().isDispatchThread()
                || ApplicationManager.getApplication().isWriteAccessAllowed()) {
            report(new IllegalStateException("Go buffer synchronization requires the end of the write command."));
            return;
        }
        synchronizing = true;
        try {
            while (!project.isDisposed()) {
                pending.entrySet().removeIf(entry -> !isCandidate(entry.getKey(), entry.getValue()));
                if (pending.isEmpty()) break;
                var batch = new IdentityHashMap<>(pending);
                var failure = new AtomicReference<Throwable>();
                try {
                    boolean completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                        try {
                            connectAndAwait(() -> connect(batch), ProgressManager.getInstance().getProgressIndicator(),
                                    TIMEOUT, project::isDisposed);
                        } catch (Throwable error) {
                            failure.set(error);
                        }
                    }, "Synchronizing Go buffers", false, project);
                    if (!completed && failure.get() == null) failure.set(new ProcessCanceledException());
                } catch (Throwable error) {
                    failure.set(error);
                }
                if (failure.get() != null) {
                    report(failure.get());
                    break;
                }
                batch.keySet().forEach(document -> {
                    pending.remove(document);
                    attempts.remove(document);
                });
                // Modal progress can process command callbacks. Drain newly collected
                // documents before returning to the next user command, without recursion.
            }
        } finally {
            synchronizing = false;
        }
    }

    private boolean hasProjectEditor(Document document) {
        return EditorFactory.getInstance().getEditors(document, project).length != 0;
    }

    private boolean isCandidate(Document document, VirtualFile file) {
        // These documents were already owned when their command edited them. The
        // authoritative project-index recheck runs in connect's background read action.
        return file.isValid() && file.isInLocalFileSystem() && file.isWritable()
                && document.isWritable() && "go".equals(file.getExtension()) && hasProjectEditor(document);
    }

    private LanguageServerWrapper wrapperFor(VirtualFile file) {
        return LanguageServiceAccessor.getInstance(project).getStartedServers().stream()
                .filter(wrapper -> wrapper.getProject() == project && wrapper.isActive()
                        && "goforge.gopls".equals(wrapper.getServerDefinition().getId()))
                .findFirst().orElse(null);
    }

    private CompletableFuture<?> connect(Map<Document, VirtualFile> batch) {
        var futures = new ArrayList<CompletableFuture<?>>();
        for (var entry : batch.entrySet()) {
            var file = entry.getValue();
            var document = entry.getKey();
            if (!ownsDocument.test(document, file)) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "The edited Go file no longer belongs to this project: " + file.getName()));
            }
            var wrapper = wrapperFor(file);
            var psiFile = file.isValid() ? PsiManager.getInstance(project).findFile(file) : null;
            if (wrapper == null || psiFile == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Go language server or file is no longer available."));
            }
            long stamp = document.getModificationStamp();
            var previous = attempts.get(document);
            var currentOpened = wrapper.getOpenedDocument(wrapper.toUri(file));
            var currentSynchronizer = currentOpened != null ? currentOpened.getSynchronizer() : null;
            if (previous != null && previous.wrapper == wrapper) {
                if (!previous.completion.isDone()) {
                    if (stamp != previous.stamp) {
                        previous.diverged = true;
                        return CompletableFuture.failedFuture(reopenRequired(file));
                    }
                    futures.add(previous.completion);
                    continue;
                }
                if (previous.diverged && currentSynchronizer == previous.synchronizer) {
                    return CompletableFuture.failedFuture(reopenRequired(file));
                }
            }
            var attempt = new ConnectionAttempt(file, wrapper, stamp);
            attempts.put(document, attempt);
            var connection = LanguageServiceAccessor.getInstance(project).getLanguageServers(psiFile,
                    features -> features.getServerWrapper() == wrapper,
                    features -> features.getServerWrapper() == wrapper).thenCompose(servers -> {
                var opened = wrapper.getOpenedDocument(wrapper.toUri(file));
                if (servers.stream().noneMatch(server -> server.getServerWrapper() == wrapper)
                        || opened == null || opened.getSynchronizer() == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("gopls did not connect " + file.getName()));
                }
                attempt.synchronizer = opened.getSynchronizer();
                if (attempt.diverged || document.getModificationStamp() != stamp) {
                    attempt.diverged = true;
                    return CompletableFuture.failedFuture(reopenRequired(file));
                }
                var synchronizer = opened.getSynchronizer();
                return synchronizer.getDidOpenFuture().thenCompose(server -> {
                    if (server == null) return CompletableFuture.failedFuture(
                            new IllegalStateException("gopls didOpen did not complete for " + file.getName()));
                    return synchronizer.flushPendingChanges();
                });
            });
            connection.whenComplete((unused, error) -> {
                if (error == null) attempt.completion.complete(null);
                else attempt.completion.completeExceptionally(error);
            });
            futures.add(attempt.completion);
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private static IllegalStateException reopenRequired(VirtualFile file) {
        return new IllegalStateException("The buffer changed before its initial connection completed: "
                + file.getName() + ". Close and reopen the affected tab to establish a fresh connection.");
    }

    private static final class ConnectionAttempt {
        final VirtualFile file;
        final LanguageServerWrapper wrapper;
        final long stamp;
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        volatile DocumentContentSynchronizer synchronizer;
        volatile boolean diverged;

        ConnectionAttempt(VirtualFile file, LanguageServerWrapper wrapper, long stamp) {
            this.file = file;
            this.wrapper = wrapper;
            this.stamp = stamp;
        }
    }

    void dispose() {
        pending.clear();
        attempts.clear();
    }

    /** Test seam: create connections under a short read action, then wait off all UI/read/write locks. */
    static void connectAndAwait(Supplier<CompletableFuture<?>> connector, ProgressIndicator indicator,
                                Duration timeout, BooleanSupplier disposed) throws Exception {
        var application = ApplicationManager.getApplication();
        if (application.isDispatchThread() || application.isReadAccessAllowed() || application.isWriteAccessAllowed()) {
            throw new IllegalStateException("Go connection waits must run on a background thread outside read/write actions.");
        }
        CompletableFuture<?> future = ReadAction.compute(connector::get);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (disposed.getAsBoolean()) throw new ProcessCanceledException();
            if (indicator != null) indicator.checkCanceled();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("Timed out connecting Go buffers; synchronization is still pending.");
            try {
                future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
                return;
            } catch (TimeoutException waiting) {
                // Recheck cancellation and the bounded deadline without holding a platform lock.
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof Exception cause) throw cause;
                throw failed;
            }
        }
    }

    private void report(Throwable error) {
        if (project.isDisposed()) return;
        String detail = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        NotificationGroupManager.getInstance().getNotificationGroup("GoForge")
                .createNotification("Go buffer synchronization incomplete",
                        detail + " Unsaved buffers were preserved; synchronization remains pending.", NotificationType.ERROR)
                .notify(project);
    }
}

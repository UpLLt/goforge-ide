package dev.goforge.lsp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real platform modal/lock behavior with controlled connection futures.
 * No language server or native IDE window is started. These tests validate the
 * connection barrier, not discovery of a real inactive tab's gopls wrapper.
 */
@SuppressWarnings("deprecation")
public final class GoOpenDocumentSynchronizerTest extends BasePlatformTestCase {
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    public void testModalBarrierReleasesReadLockAndCompletesBeforeNextOperation() throws Exception {
        var events = new CopyOnWriteArrayList<String>();
        var connected = new CompletableFuture<Void>();
        var returned = new AtomicBoolean();
        var writeRan = new AtomicBoolean();
        var error = runInModalBackground(() -> {
            var app = ApplicationManager.getApplication();
            assertFalse(app.isDispatchThread());
            assertFalse(app.isReadAccessAllowed());
            assertFalse(app.isWriteAccessAllowed());
            GoOpenDocumentSynchronizer.connectAndAwait(() -> {
                assertTrue("Only connection creation needs read access", app.isReadAccessAllowed());
                assertFalse(app.isDispatchThread());
                events.add("create connection");
                // This write must run while the worker is waiting for didOpen.
                // Holding the read lock during future.get would prevent it.
                app.invokeLater(() -> {
                    try {
                        WriteAction.run(() -> {
                            assertFalse("The barrier must not return before its future completes", returned.get());
                            assertFalse(connected.isDone());
                            writeRan.set(true);
                            events.add("write acquired while waiting");
                            connected.complete(null);
                        });
                    } catch (Throwable failure) {
                        connected.completeExceptionally(failure);
                    }
                }, ModalityState.defaultModalityState());
                return connected;
            }, new EmptyProgressIndicator(), TIMEOUT, () -> false);
            assertFalse(app.isReadAccessAllowed());
            returned.set(true);
            events.add("next semantic operation");
        });
        assertNull(error);
        assertTrue(writeRan.get());
        assertTrue(returned.get());
        assertEquals(List.of("create connection", "write acquired while waiting", "next semantic operation"), events);
    }

    public void testConnectionPreservesPhysicalOpenUnsavedBuffer() throws Exception {
        EdtTestUtil.runInEdtAndWait(() -> {
            var directory = FileUtil.createTempDirectory("goforge-open-sync-", null, true);
            var path = directory.toPath().resolve("pending.go");
            String original = "package example\n\nvar Hello = 1\n";
            String changed = original.replace("Hello", "Welcome");
            Files.writeString(path, original);
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            assertNotNull(file);
            var manager = FileDocumentManager.getInstance();
            var document = manager.getDocument(file);
            assertNotNull(document);
            var editor = EditorFactory.getInstance().createEditor(document, getProject(), PlainTextFileType.INSTANCE, false);
            try {
                CommandProcessor.getInstance().runUndoTransparentAction(() ->
                        WriteAction.run(() -> document.setText(changed)));
                assertTrue(manager.isDocumentUnsaved(document));
                var snapshot = new AtomicReference<String>();
                var error = runInModalBackground(() ->
                        GoOpenDocumentSynchronizer.connectAndAwait(() -> {
                            snapshot.set(document.getText());
                            return CompletableFuture.completedFuture(null);
                        }, new EmptyProgressIndicator(), TIMEOUT, () -> false));
                assertNull(error);
                assertEquals(changed, snapshot.get());
                assertEquals(changed, document.getText());
                assertEquals(original, Files.readString(path));
                assertTrue(manager.isDocumentUnsaved(document));
                assertTrue(LanguageServiceAccessor.getInstance(getProject()).getStartedServers().isEmpty());
            } finally {
                EditorFactory.getInstance().releaseEditor(editor);
                manager.reloadFromDisk(document);
                FileUtil.delete(directory);
            }
        });
    }

    public void testExceptionalConnectionIsNotReportedAsSuccess() throws Exception {
        var expected = new IOException("Controlled didOpen failure");
        var error = runInModalBackground(() ->
                GoOpenDocumentSynchronizer.connectAndAwait(() -> CompletableFuture.failedFuture(expected),
                        new EmptyProgressIndicator(), TIMEOUT, () -> false));
        assertSame(expected, error);
    }

    public void testCancellationStopsWaitingWithoutClaimingCompletion() throws Exception {
        var indicator = new EmptyProgressIndicator();
        var pending = new CompletableFuture<Void>();
        var error = runInModalBackground(() ->
                GoOpenDocumentSynchronizer.connectAndAwait(() -> {
                    indicator.cancel();
                    return pending;
                }, indicator, TIMEOUT, () -> false));
        assertInstanceOf(error, ProcessCanceledException.class);
        assertFalse(pending.isDone());
        pending.complete(null);
    }

    public void testDisposalStopsWaitingWithoutClaimingCompletion() throws Exception {
        var disposed = new AtomicBoolean();
        var pending = new CompletableFuture<Void>();
        var error = runInModalBackground(() ->
                GoOpenDocumentSynchronizer.connectAndAwait(() -> {
                    disposed.set(true);
                    return pending;
                }, new EmptyProgressIndicator(), TIMEOUT, disposed::get));
        assertInstanceOf(error, ProcessCanceledException.class);
        assertFalse(pending.isDone());
        pending.complete(null);
    }

    public void testTimeoutLeavesConnectionIncomplete() throws Exception {
        var pending = new CompletableFuture<Void>();
        var error = runInModalBackground(() ->
                GoOpenDocumentSynchronizer.connectAndAwait(() -> pending, new EmptyProgressIndicator(),
                        Duration.ofMillis(30), () -> false));
        assertInstanceOf(error, TimeoutException.class);
        assertFalse(pending.isDone());
        pending.complete(null);
    }

    public void testReadActionCallerIsRejectedBeforeCreatingConnection() throws Exception {
        var invoked = new AtomicBoolean();
        var expected = new AtomicReference<Throwable>();
        var workerError = runInModalBackground(() -> ReadAction.run(() -> {
            try {
                GoOpenDocumentSynchronizer.connectAndAwait(() -> {
                    invoked.set(true);
                    return CompletableFuture.completedFuture(null);
                }, new EmptyProgressIndicator(), TIMEOUT, () -> false);
            } catch (Throwable error) {
                expected.set(error);
            }
        }));
        assertNull(workerError);
        assertInstanceOf(expected.get(), IllegalStateException.class);
        assertFalse(invoked.get());
    }

    public void testEdtAndWriteActionCallersAreRejectedBeforeConnection() throws Exception {
        EdtTestUtil.runInEdtAndWait(() -> {
            assertRejectedUiCall();
            WriteAction.run(this::assertRejectedUiCall);
        });
    }

    private void assertRejectedUiCall() {
        var invoked = new AtomicBoolean();
        try {
            GoOpenDocumentSynchronizer.connectAndAwait(() -> {
                invoked.set(true);
                return CompletableFuture.completedFuture(null);
            }, new EmptyProgressIndicator(), TIMEOUT, () -> false);
            fail("UI/write-context connection waits must be rejected");
        } catch (IllegalStateException expected) {
            assertFalse(invoked.get());
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }
    }

    private Throwable runInModalBackground(Worker worker) throws Exception {
        var failure = new AtomicReference<Throwable>();
        EdtTestUtil.runInEdtAndWait(() -> {
            boolean completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                try { worker.run(); }
                catch (Throwable error) { failure.set(error); }
            }, "Verify Go connection barrier", false, getProject());
            assertTrue(completed);
        });
        return failure.get();
    }

    @FunctionalInterface
    private interface Worker { void run() throws Exception; }
}

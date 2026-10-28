package dev.goforge.lsp;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.codeInsight.daemon.impl.UpdateHighlightersUtil;
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.DocumentContentSynchronizer;
import com.redhat.devtools.lsp4ij.LanguageServerItem;
import com.redhat.devtools.lsp4ij.LanguageServerWrapper;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;
import com.redhat.devtools.lsp4ij.OpenedDocument;
import com.redhat.devtools.lsp4ij.client.features.FileUriSupport;
import com.redhat.devtools.lsp4ij.features.diagnostics.LSPDiagnosticsApplier;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.net.URI;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Exercises GoForge's real client notification hook and LSP4IJ cache/version path.
 * No language-server process is started. An empty collector is appropriate only
 * in the clearing scenario, where the real canonical cache is asserted empty.
 * In the rejected-version scenarios the cache stays nonempty and the hook must
 * not schedule a refresh at all; the real existing markup must remain intact.
 * These tests do not claim coverage of rendering new nonempty diagnostics.
 */
@SuppressWarnings("deprecation")
public final class GoplsDiagnosticsRefreshTest extends BasePlatformTestCase {
    private static final String MESSAGE = "undefined: Welcome";
    private static final String IDENTIFIER = "lsp4ij.publish";
    private static final int TEST_GROUP = 42421;

    public void testRepeatedEmptyPublicationClearsAfterDocumentStampRace() throws Exception {
        runScenario(fixture -> {
            long oldStamp = fixture.document.getModificationStamp();
            fixture.client.publishDiagnostics(fixture.publication(List.of(), 0));
            assertTrue(fixture.opened.getDiagnostics().isEmpty());

            // Stay in one EDT turn so the queued UI application cannot precede
            // this unrelated edit. The initial empty refresh loses its stamp.
            WriteCommandAction.runWriteCommandAction(getProject(), () ->
                    fixture.document.insertString(fixture.document.getTextLength(), "\n"));
            assertTrue(oldStamp != fixture.document.getModificationStamp());
            assertNotNull(errorHighlight(fixture.document));

            // The real synchronizer is deliberately not attached as a document
            // listener; version 0 isolates the refresh race from wire delivery.
            fixture.client.publishDiagnostics(fixture.publication(List.of(), 0));
            NonBlockingReadActionImpl.waitForAsyncTaskCompletion();
            assertTrue(fixture.opened.getDiagnostics().isEmpty());
            assertNull("The repeated accepted empty publication must retry the lost clear",
                    errorHighlight(fixture.document));
        });
    }

    public void testRejectedOldEmptyPreservesCanonicalErrorAndVisibleMarkup() throws Exception {
        runScenario(fixture -> {
            fixture.client.publishDiagnostics(fixture.publication(List.of(), -1));
            NonBlockingReadActionImpl.waitForAsyncTaskCompletion();
            assertEquals(List.of(fixture.error), List.copyOf(fixture.opened.getDiagnostics()));
            assertNotNull("A rejected older empty payload must not clear the accepted error",
                    errorHighlight(fixture.document));
        });
    }

    public void testRejectedOldEmptyDoesNotReapplyCachedRangeAfterEdit() throws Exception {
        runScenario(fixture -> {
            WriteCommandAction.runWriteCommandAction(getProject(), () ->
                    fixture.document.insertString(0, "\n"));
            var shifted = errorHighlight(fixture.document);
            assertNotNull(shifted);
            int shiftedStart = shifted.getStartOffset();
            int shiftedEnd = shifted.getEndOffset();
            assertTrue("The platform must have shifted the existing error with the edit", shiftedStart > 0);

            fixture.client.publishDiagnostics(fixture.publication(List.of(), -1));
            NonBlockingReadActionImpl.waitForAsyncTaskCompletion();
            assertEquals(List.of(fixture.error), List.copyOf(fixture.opened.getDiagnostics()));
            var remaining = errorHighlight(fixture.document);
            assertNotNull("The rejected payload must leave the existing error visible", remaining);
            assertEquals("Cached old coordinates must not be reapplied to the edited document",
                    shiftedStart, remaining.getStartOffset());
            assertEquals(shiftedEnd, remaining.getEndOffset());
        });
    }

    private void runScenario(Consumer<Fixture> scenario) throws Exception {
        EdtTestUtil.runInEdtAndWait(() -> {
            int previousGroup = LSPDiagnosticsApplier.GROUP_ID;
            LSPDiagnosticsApplier.GROUP_ID = TEST_GROUP;
            try {
                scenario.accept(prepareFixture());
            } finally {
                NonBlockingReadActionImpl.waitForAsyncTaskCompletion();
                LSPDiagnosticsApplier.GROUP_ID = previousGroup;
            }
        });
    }

    private Fixture prepareFixture() {
        // LSP URI resolution uses the local file system, not the fixture's temp:// VFS.
        java.io.File directory;
        VirtualFile virtualFile;
        try {
            directory = FileUtil.createTempDirectory("goforge-diagnostics-", null, true);
            var path = Files.writeString(directory.toPath().resolve("diagnostics-case.txt"),
                    "Welcome\n// unrelated tail\n");
            virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            assertNotNull(virtualFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Disposer.register(getTestRootDisposable(), () -> FileUtil.delete(directory));
        var file = PsiManager.getInstance(getProject()).findFile(virtualFile);
        assertNotNull(file);
        var document = FileDocumentManager.getInstance().getDocument(virtualFile);
        assertNotNull(document);
        assertTrue("The fixture must not start external language servers",
                LanguageServiceAccessor.getInstance(getProject()).getStartedServers().isEmpty());
        var definition = LanguageServersRegistry.getInstance().getServerDefinition("goforge.gopls");
        assertNotNull(definition);

        // Override only document lookup. The real wrapper's updateDiagnostics,
        // the real OpenedDocument, and the real synchronizer version filter run.
        class FixtureWrapper extends LanguageServerWrapper {
            private URI fixtureUri;
            private OpenedDocument fixtureOpened;
            FixtureWrapper() { super(GoplsDiagnosticsRefreshTest.this.getProject(), definition); }
            @Override public OpenedDocument getOpenedDocument(URI uri, boolean force) {
                return fixtureUri != null && fixtureUri.equals(uri) ? fixtureOpened : null;
            }
        }
        var wrapper = new FixtureWrapper();
        Disposer.register(getTestRootDisposable(), wrapper);
        var uri = FileUriSupport.getFileUri(file.getVirtualFile(), wrapper.getClientFeatures());
        assertNotNull(uri);
        String uriText = FileUriSupport.toString(uri, wrapper.getClientFeatures());
        assertNotNull(uriText);
        var resolvedFile = FileUriSupport.findFileByUri(uriText, wrapper.getClientFeatures());
        assertEquals(file.getVirtualFile(), resolvedFile);
        assertNotNull(resolvedFile);
        assertSame(document, FileDocumentManager.getInstance().getDocument(resolvedFile));
        var synchronizer = new DocumentContentSynchronizer(wrapper, uriText,
                file.getVirtualFile(), document, null, null);
        Disposer.register(getTestRootDisposable(), synchronizer);
        var opened = new OpenedDocument(new LanguageServerItem(null, wrapper), file.getVirtualFile(), synchronizer);
        wrapper.fixtureUri = uri;
        wrapper.fixtureOpened = opened;

        var client = new GoplsLanguageClient(getProject());
        Disposer.register(getTestRootDisposable(), client);
        client.setServerWrapper(wrapper);
        client.connect(new UnstartedLanguageServer());
        assertFalse(wrapper.isActive());

        var error = new Diagnostic(new Range(new Position(0, 0), new Position(0, 7)), MESSAGE);
        error.setSeverity(DiagnosticSeverity.Error);
        assertTrue(opened.getDiagnosticsForServer().update(IDENTIFIER, List.of(error)));
        var highlight = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
                .range(0, 7).description(MESSAGE).createUnconditionally();
        UpdateHighlightersUtil.setHighlightersToEditor(getProject(), document, 0,
                document.getTextLength(), List.of(highlight), null, TEST_GROUP);
        assertNotNull(errorHighlight(document));
        return new Fixture(document, opened, client, error, uriText);
    }

    private RangeHighlighter errorHighlight(Document document) {
        for (var highlighter : DocumentMarkupModel.forDocument(document, getProject(), false).getAllHighlighters()) {
            var info = HighlightInfo.fromRangeHighlighter(highlighter);
            if (info != null && MESSAGE.equals(info.getDescription())) return highlighter;
        }
        return null;
    }

    private record Fixture(Document document, OpenedDocument opened, GoplsLanguageClient client,
                           Diagnostic error, String uri) {
        PublishDiagnosticsParams publication(List<Diagnostic> diagnostics, int version) {
            var params = new PublishDiagnosticsParams(uri, diagnostics);
            params.setVersion(version);
            return params;
        }
    }

    /** connect only installs handlers; it must not initialize or contact a server. */
    private static final class UnstartedLanguageServer implements LanguageServer {
        @Override public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            throw new AssertionError("This fixture must not initialize a language server");
        }
        @Override public CompletableFuture<Object> shutdown() { return CompletableFuture.completedFuture(null); }
        @Override public void exit() {}
        @Override public TextDocumentService getTextDocumentService() {
            throw new AssertionError("This fixture must not send text-document requests");
        }
        @Override public WorkspaceService getWorkspaceService() {
            throw new AssertionError("This fixture must not send workspace requests");
        }
    }
}

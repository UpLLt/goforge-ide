package dev.goforge.lsp;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.codeInsight.daemon.impl.UpdateHighlightersUtil;
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.DocumentContentSynchronizer;
import com.redhat.devtools.lsp4ij.LanguageServerItem;
import com.redhat.devtools.lsp4ij.LanguageServerWrapper;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;
import com.redhat.devtools.lsp4ij.OpenedDocument;
import com.redhat.devtools.lsp4ij.features.diagnostics.LSPDiagnosticsApplier;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.List;

/**
 * Isolated correctness experiment for LSP4IJ 0.21's cache/refresh handoff.
 *
 * <p>This drives the real cache, async applier and platform markup model. No
 * language-server process is started: the collector has no started servers,
 * so its result is the empty highlight list appropriate for a clearing update.
 * The initial visible error is seeded through the actual platform API.</p>
 *
 * <p>The race test expects the final error to be cleared. A failure is evidence
 * of this controlled mechanism, not proof that it caused the earlier GUI
 * incident. The class is excluded from the regular product test suite and
 * enabled only with -PgoforgeDiagnosticExperiment=true.</p>
 */
@SuppressWarnings("deprecation")
public final class LspDiagnosticsRefreshRaceTest extends BasePlatformTestCase {
    private static final String MESSAGE = "undefined: Welcome";
    private static final String IDENTIFIER = "lsp4ij.publish";
    private static final int EXPERIMENT_GROUP = 42420;

    public void testLatestEmptyDiagnosticsClearStableDocument() throws Exception {
        EdtTestUtil.runInEdtAndWait(() -> {
            int previousGroup = LSPDiagnosticsApplier.GROUP_ID;
            LSPDiagnosticsApplier.GROUP_ID = EXPERIMENT_GROUP;
            try {
                var fixture = prepareFixture();
                assertTrue(fixture.opened.updateDiagnostics(IDENTIFIER, List.of(), null));
                NonBlockingReadActionImpl.waitForAsyncTaskCompletion();
                assertTrue(fixture.opened.getDiagnostics().isEmpty());
                assertFalse("Stable-document control must clear the existing highlighter",
                        hasErrorHighlight(fixture.document));
            } finally {
                NonBlockingReadActionImpl.waitForAsyncTaskCompletion();
                LSPDiagnosticsApplier.GROUP_ID = previousGroup;
            }
        });
    }

    public void testLatestEmptyDiagnosticsStillClearAfterInterveningDocumentChange() throws Exception {
        EdtTestUtil.runInEdtAndWait(() -> {
            int previousGroup = LSPDiagnosticsApplier.GROUP_ID;
            LSPDiagnosticsApplier.GROUP_ID = EXPERIMENT_GROUP;
            try {
                var fixture = prepareFixture();
                long capturedStamp = fixture.document.getModificationStamp();

                // This synchronously updates the cache to empty, captures the
                // current stamp and queues the real asynchronous UI refresh.
                assertTrue(fixture.opened.updateDiagnostics(IDENTIFIER, List.of(), null));
                assertTrue(fixture.opened.getDiagnostics().isEmpty());

                // Keep both actions in this one EDT turn. A finishOnUiThread
                // callback cannot run before this unrelated edit changes the
                // stamp. The edit is outside the diagnostic's range.
                WriteCommandAction.runWriteCommandAction(getProject(), () ->
                        fixture.document.insertString(fixture.document.getTextLength(), "\n"));
                assertTrue(capturedStamp != fixture.document.getModificationStamp());
                assertTrue("The unrelated edit must not remove the seeded error itself",
                        hasErrorHighlight(fixture.document));

                // Model the server correctly publishing empty diagnostics
                // again for the now-current document. Null bypasses version
                // filtering; this experiment isolates refresh/cache ordering.
                assertFalse("Identical empty diagnostics are currently deduplicated by LSP4IJ",
                        fixture.opened.updateDiagnostics(IDENTIFIER, List.of(), null));
                NonBlockingReadActionImpl.waitForAsyncTaskCompletion();

                assertTrue(fixture.opened.getDiagnostics().isEmpty());
                assertFalse("The latest empty diagnostics must clear the visible error even if an earlier refresh was skipped",
                        hasErrorHighlight(fixture.document));
            } finally {
                NonBlockingReadActionImpl.waitForAsyncTaskCompletion();
                LSPDiagnosticsApplier.GROUP_ID = previousGroup;
            }
        });
    }

    private Fixture prepareFixture() {
        var file = myFixture.configureByText(PlainTextFileType.INSTANCE, "Welcome\n// unrelated tail\n");
        var document = myFixture.getEditor().getDocument();
        assertTrue("The experiment must not start an external language server",
                LanguageServiceAccessor.getInstance(getProject()).getStartedServers().isEmpty());

        var definition = LanguageServersRegistry.getInstance().getServerDefinition("goforge.gopls");
        assertNotNull(definition);
        // Construction and getServerCapabilitiesSync do not start the server.
        var wrapper = new LanguageServerWrapper(getProject(), definition);
        Disposer.register(getTestRootDisposable(), wrapper);
        var server = new LanguageServerItem(null, wrapper);
        // Do not attach this synchronizer as a document listener or open a
        // network session. Its real Document provides the schedule-time stamp.
        var synchronizer = new DocumentContentSynchronizer(wrapper, file.getVirtualFile().getUrl(),
                file.getVirtualFile(), document, null, null);
        var opened = new OpenedDocument(server, file.getVirtualFile(), synchronizer);
        var diagnostic = new Diagnostic(new Range(new Position(0, 0), new Position(0, 7)), MESSAGE);
        diagnostic.setSeverity(DiagnosticSeverity.Error);
        assertTrue(opened.getDiagnosticsForServer().update(IDENTIFIER, List.of(diagnostic)));
        assertFalse(wrapper.isActive());

        var highlight = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
                .range(0, 7).description(MESSAGE).createUnconditionally();
        UpdateHighlightersUtil.setHighlightersToEditor(getProject(), document, 0,
                document.getTextLength(), List.of(highlight), null, EXPERIMENT_GROUP);
        assertTrue("The experiment must begin with a real visible markup entry", hasErrorHighlight(document));
        return new Fixture(document, opened);
    }

    private boolean hasErrorHighlight(Document document) {
        for (var highlighter : DocumentMarkupModel.forDocument(document, getProject(), false).getAllHighlighters()) {
            var info = HighlightInfo.fromRangeHighlighter(highlighter);
            if (info != null && MESSAGE.equals(info.getDescription())) return true;
        }
        return false;
    }

    private record Fixture(Document document, OpenedDocument opened) {}
}

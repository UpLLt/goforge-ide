package dev.goforge.lsp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.RunAll;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.LanguageServerWrapper;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.client.features.FileUriSupport;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition;
import dev.goforge.refactor.GoDocumentVersionEndpoint;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.io.Closeable;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real selected editor state and the outgoing/incoming endpoint chain, without a process or UI driver. */
public final class GoFormattingCaretIndentEndpointTest extends BasePlatformTestCase {
    private static final String SOURCE = "package p\n\nfunc sample() {\n\t\n}";
    private final List<VirtualFile> files = new ArrayList<>();
    private LanguageServerDefinition gopls;
    private boolean oldEnabled, oldKeep;
    private Path directory;
    private VirtualFile contentRoot;

    @Override protected void setUp() throws Exception {
        super.setUp();
        gopls = LanguageServersRegistry.getInstance().getServerDefinition("goforge.gopls");
        assertNotNull(gopls); oldEnabled = gopls.isEnabled(getProject()); gopls.setEnabled(false, getProject());
        oldKeep = EditorSettingsExternalizable.getInstance().isKeepTrailingSpacesOnCaretLine();
        EditorSettingsExternalizable.getInstance().setKeepTrailingSpacesOnCaretLine(true);
        directory = Files.createTempDirectory("goforge-caret-indent-");
        contentRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory);
        assertNotNull(contentRoot); PsiTestUtil.addContentRoot(getModule(), contentRoot);
    }

    @Override protected void tearDown() throws Exception {
        var project = getProject();
        RunAll.runAll(
                () -> { for (var file : files) FileEditorManager.getInstance(project).closeFile(file); },
                () -> EditorSettingsExternalizable.getInstance().setKeepTrailingSpacesOnCaretLine(oldKeep),
                () -> { if (contentRoot != null) PsiTestUtil.removeContentEntry(getModule(), contentRoot); },
                () -> { if (directory != null) FileUtil.delete(directory.toFile()); },
                () -> super.tearDown(),
                () -> { if (gopls != null) gopls.setEnabled(oldEnabled, project); });
    }

    public void testFirstBackgroundRequestUsesSelectionEventAndKeepsIndependentFinalNewlineEdit() throws Exception {
        var source = open("first.go", SOURCE, 3, 1);
        assertSame(source.editor, GoFormattingCaretIndentContext.getInstance(getProject()).currentEditor());
        TextEdit indent = edit(3, 0, 3, 1, ""), newline = edit(4, 1, 4, 1, "\n");
        var response = new CompletableFuture<Object>();
        var chain = chain(source, response);
        var result = requestInBackground(chain, source);
        response.complete(List.of(indent, newline));
        await(result::isDone);
        var edits = (List<?>) result.get();
        assertEquals(1, edits.size()); assertSame(newline, edits.getFirst());
        assertEquals(SOURCE, source.editor.getDocument().getText());
        assertEquals(1, source.editor.getCaretModel().getOffset() - source.editor.getDocument().getLineStartOffset(3));
    }

    public void testProductionLauncherWrapsRealLspWireAndPreservesOtherEdits() throws Exception {
        var source = open("wire.go", SOURCE, 3, 1);
        TextEdit indent = edit(3, 0, 3, 1, ""), newline = edit(4, 1, 4, 1, "\n");
        try (var wire = new Wire(List.of(indent, newline))) {
            wire.proxy.getTextDocumentService().didOpen(opened(source));
            var result = new AtomicReference<CompletableFuture<List<? extends TextEdit>>>();
            var sent = ApplicationManager.getApplication().executeOnPooledThread(() ->
                    result.set(wire.proxy.getTextDocumentService().formatting(params(source))));
            await(sent::isDone); sent.get(1, TimeUnit.SECONDS); await(() -> result.get().isDone());
            var edits = result.get().get(1, TimeUnit.SECONDS);
            assertEquals(List.of(newline), edits);
            assertEquals(source.uri, wire.lastUri);
            assertEquals(SOURCE, source.editor.getDocument().getText());
        }
    }

    public void testBackgroundResponseCanRecheckCaretAndProtectItsIndent() throws Exception {
        var source = open("response-thread.go", SOURCE, 3, 1);
        var pending = new CompletableFuture<Object>();
        var result = requestInBackground(chain(source, pending), source);
        TextEdit newline = edit(4, 1, 4, 1, "\n");
        var completion = ApplicationManager.getApplication().executeOnPooledThread(() ->
                pending.complete(List.of(edit(3, 0, 3, 1, ""), newline)));
        await(completion::isDone); completion.get(1, TimeUnit.SECONDS); await(result::isDone);
        assertEquals(List.of(newline), result.get(1, TimeUnit.SECONDS));
        assertEquals(SOURCE, source.editor.getDocument().getText());
    }

    public void testMultipleCaretsKeepOnlyTheirWhitespaceLines() throws Exception {
        String text = "package p\nfunc sample() {\n\t\n  \n \n}";
        var source = open("multi.go", text, 2, 1);
        assertNotNull(source.editor.getCaretModel().addCaret(source.editor.logicalToVisualPosition(new LogicalPosition(3, 2))));
        TextEdit first = edit(2, 0, 2, 1, ""), second = edit(3, 0, 3, 2, ""), unselected = edit(4, 0, 4, 1, "");
        assertEquals(List.of(unselected), response(source, List.of(first, second, unselected)));
    }

    public void testRawStringsCommentsSelectionsAndNonGoRemainUnchanged() throws Exception {
        for (var item : List.of(
                new String[]{"raw.go", "package p\nvar s = `\n\t\n`"},
                new String[]{"comment.go", "package p\n/*\n\t\n*/"},
                new String[]{"notes.txt", "first\nsecond\n\t\nlast"})) {
            var source = open(item[0], item[1], 2, 1);
            var edits = List.of(edit(2, 0, 2, 1, ""));
            assertSame(edits, response(source, edits));
        }
        var source = open("selection.go", SOURCE, 3, 1);
        source.editor.getSelectionModel().setSelection(source.editor.getDocument().getLineStartOffset(3), source.editor.getCaretModel().getOffset());
        var edits = List.of(edit(3, 0, 3, 1, ""));
        assertSame(edits, response(source, edits));
    }

    public void testOtherSelectedFileAndDisabledCaretWhitespacePreferenceBypass() throws Exception {
        var source = open("inactive.go", SOURCE, 3, 1);
        open("active.go", SOURCE, 3, 1);
        var edits = List.of(edit(3, 0, 3, 1, ""));
        assertSame(edits, response(source, edits));
        FileEditorManager.getInstance(getProject()).openTextEditor(new OpenFileDescriptor(getProject(), source.file, 3, 1), true);
        EditorSettingsExternalizable.getInstance().setKeepTrailingSpacesOnCaretLine(false);
        assertSame(edits, response(source, edits));
    }

    public void testChangedDocumentVersionCaretAndSelectionDoNotAdaptOldResponse() throws Exception {
        for (int change = 0; change < 4; change++) {
            var source = open("changed" + change + ".go", SOURCE, 3, 1);
            var future = new CompletableFuture<Object>();
            Endpoint endpoint = chain(source, future);
            var result = requestInBackground(endpoint, source);
            if (change == 0) WriteCommandAction.runWriteCommandAction(getProject(), () -> source.editor.getDocument().insertString(0, "// changed\n"));
            if (change == 1) endpoint.notify("textDocument/didChange", new DidChangeTextDocumentParams(
                    new VersionedTextDocumentIdentifier(source.uri, 2), List.of(new TextDocumentContentChangeEvent(SOURCE))));
            if (change == 2) source.editor.getCaretModel().moveToOffset(0);
            if (change == 3) source.editor.getSelectionModel().setSelection(0, source.editor.getCaretModel().getOffset());
            var edits = List.of(edit(3, 0, 3, 1, "")); future.complete(edits);
            await(result::isDone); assertSame(edits, result.get());
        }
    }

    public void testCrossLineCoalescedInvalidOverlappingAndNonWhitespaceEditsRemainUnchanged() throws Exception {
        var source = open("conservative.go", SOURCE, 3, 1);
        for (var edits : List.of(
                List.of(edit(3, 0, 4, 1, "\n}\n")),
                List.of(edit(0, 0, 4, 1, "package p\n\nfunc sample() {\n\n}\n")),
                List.of(edit(3, 0, 3, 99, "")),
                List.of(edit(3, 0, 3, 1, ""), edit(3, 0, 3, 1, " ")),
                List.of(edit(3, 0, 3, 1, "println(1)")))) {
            assertSame(edits, response(source, edits));
        }
    }

    public void testUnsentDocumentChangeIsNotMistakenForKnownServerVersion() throws Exception {
        var source = open("unsent.go", SOURCE, 3, 1);
        var pending = new CompletableFuture<Object>();
        Endpoint endpoint = chain(source, pending);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> source.editor.getDocument().replaceString(0, 9, "package q"));
        var result = requestInBackground(endpoint, source);
        var edits = List.of(edit(3, 0, 3, 1, "")); pending.complete(edits);
        assertSame(edits, result.get(1, TimeUnit.SECONDS));
    }

    public void testIncrementalOverlayChangesAreValidatedBeforeProtection() throws Exception {
        var source = open("incremental.go", SOURCE, 3, 1);
        var pending = new CompletableFuture<Object>();
        Endpoint endpoint = chain(source, pending);
        int start = source.editor.getDocument().getLineStartOffset(3);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> source.editor.getDocument().replaceString(start, start + 1, "  "));
        source.editor.getCaretModel().moveToOffset(start + 2);
        var change = new TextDocumentContentChangeEvent();
        change.setRange(new Range(new Position(3, 0), new Position(3, 1))); change.setText("  ");
        endpoint.notify("textDocument/didChange", new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(source.uri, 2), List.of(change)));
        var result = requestInBackground(endpoint, source);
        TextEdit newline = edit(4, 1, 4, 1, "\n"); pending.complete(List.of(edit(3, 0, 3, 2, ""), newline));
        assertEquals(List.of(newline), result.get(1, TimeUnit.SECONDS));
    }

    public void testRangeRequestsUntrackedOverlayAndErrorsKeepTheirProtocolMeaning() throws Exception {
        var source = open("protocol.go", SOURCE, 3, 1);
        var future = new CompletableFuture<Object>();
        Endpoint endpoint = chain(source, future);
        assertSame(future, endpoint.request("textDocument/rangeFormatting", new DocumentRangeFormattingParams()));
        endpoint.notify("textDocument/didClose", new DidCloseTextDocumentParams(new TextDocumentIdentifier(source.uri)));
        assertSame(future, endpoint.request("textDocument/formatting", params(source)));
        var failure = new IOException("formatter disconnected");
        var failed = new CompletableFuture<Object>();
        var result = requestInBackground(chain(source, failed), source); failed.completeExceptionally(failure);
        try { result.join(); fail("Expected formatter error"); } catch (CompletionException actual) { assertSame(failure, actual.getCause()); }
    }

    public void testCancellationFlowsBothDirections() throws Exception {
        var source = open("cancel.go", SOURCE, 3, 1);
        var outgoing = new CompletableFuture<Object>();
        var consumer = requestInBackground(chain(source, outgoing), source);
        assertTrue(consumer.cancel(true)); assertTrue(outgoing.isCancelled());
        var incoming = new CompletableFuture<Object>();
        var result = requestInBackground(chain(source, incoming), source);
        incoming.cancel(false); assertTrue(result.isCancelled());
    }

    private record Source(VirtualFile file, Editor editor, String uri) {}
    private Source open(String name, String text, int line, int column) throws IOException {
        Path path = directory.resolve(name); Files.writeString(path, text);
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        assertNotNull(file); files.add(file);
        Editor editor = FileEditorManager.getInstance(getProject()).openTextEditor(new OpenFileDescriptor(getProject(), file, line, column), true);
        assertNotNull(editor); editor.getCaretModel().moveToOffset(editor.getDocument().getLineStartOffset(line) + column);
        assertSame(editor, FileEditorManager.getInstance(getProject()).getSelectedTextEditor());
        return new Source(file, editor, file.toNioPath().toUri().toString());
    }
    private Endpoint chain(Source source, CompletableFuture<Object> response) {
        var versions = new ConcurrentHashMap<String, Integer>();
        Endpoint delegate = new Endpoint() {
            @Override public CompletableFuture<?> request(String method, Object parameter) { return response; }
            @Override public void notify(String method, Object parameter) {}
        };
        Endpoint chain = new GoFormattingCaretIndentEndpoint(new GoDocumentVersionEndpoint(delegate, versions), getProject(), FileUriSupport.DEFAULT, versions);
        chain.notify("textDocument/didOpen", opened(source));
        return chain;
    }
    private Object response(Source source, List<TextEdit> edits) throws Exception {
        var pending = new CompletableFuture<Object>(); var result = requestInBackground(chain(source, pending), source);
        pending.complete(edits); await(result::isDone); return result.get(1, TimeUnit.SECONDS);
    }
    private CompletableFuture<?> requestInBackground(Endpoint endpoint, Source source) throws Exception {
        var result = new AtomicReference<CompletableFuture<?>>();
        var task = ApplicationManager.getApplication().executeOnPooledThread(() -> result.set(endpoint.request("textDocument/formatting", params(source))));
        await(task::isDone); task.get(1, TimeUnit.SECONDS); return result.get();
    }
    private static void await(java.util.function.BooleanSupplier condition) {
        PlatformTestUtil.waitWithEventsDispatching("Formatting endpoint did not finish", condition, 10);
    }
    private static DocumentFormattingParams params(Source source) {
        return new DocumentFormattingParams(new TextDocumentIdentifier(source.uri), new FormattingOptions(4, false));
    }
    private static DidOpenTextDocumentParams opened(Source source) {
        return new DidOpenTextDocumentParams(new TextDocumentItem(source.uri, "go", 1, source.editor.getDocument().getText()));
    }
    private static TextEdit edit(int startLine, int startColumn, int endLine, int endColumn, String text) {
        return new TextEdit(new Range(new Position(startLine, startColumn), new Position(endLine, endColumn)), text);
    }

    private final class Wire implements AutoCloseable, LanguageServer, TextDocumentService, WorkspaceService {
        private final PipedInputStream clientInput = new PipedInputStream(65536), serverInput = new PipedInputStream(65536);
        private final PipedOutputStream clientOutput, serverOutput;
        private final ExecutorService executor = Executors.newCachedThreadPool(r -> { var t = new Thread(r, "goforge-indent-wire"); t.setDaemon(true); return t; });
        private final Future<?> clientListener, serverListener;
        private final LanguageServerWrapper wrapper;
        private final GoplsClientFeatures features = new GoplsClientFeatures();
        private final List<TextEdit> edits;
        private final LanguageServer proxy;
        private volatile String lastUri;
        Wire(List<TextEdit> edits) throws IOException {
            this.edits = edits;
            clientOutput = new PipedOutputStream(serverInput); serverOutput = new PipedOutputStream(clientInput);
            var definition = new LanguageServerDefinition("goforge.test.indent", "Indent wire", "Test only", false, 60, false) {
                @Override public StreamConnectionProvider createConnectionProvider(com.intellij.openapi.project.Project project) {
                    throw new AssertionError("No process should be started");
                }
            };
            wrapper = new LanguageServerWrapper(getProject(), definition);
            features.setServerWrapper(wrapper); features.setServerDefinition(definition);
            var server = new Launcher.Builder<LanguageClient>().setLocalService(this).setRemoteInterface(LanguageClient.class)
                    .setInput(serverInput).setOutput(serverOutput).setExecutorService(executor).create();
            var client = features.<LanguageServer>createLauncherBuilder().setLocalService(new LanguageClient() {
                @Override public void telemetryEvent(Object object) {}
                @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}
                @Override public void showMessage(MessageParams messageParams) {}
                @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) { return CompletableFuture.completedFuture(null); }
                @Override public void logMessage(MessageParams message) {}
            }).setRemoteInterface(LanguageServer.class).setInput(clientInput).setOutput(clientOutput).setExecutorService(executor).create();
            proxy = client.getRemoteProxy(); serverListener = server.startListening(); clientListener = client.startListening();
        }
        @Override public void close() throws Exception {
            RunAll.runAll(
                    () -> { for (Closeable stream : List.of(clientInput, serverInput, clientOutput, serverOutput)) try { stream.close(); } catch (IOException ignored) {} },
                    () -> clientListener.cancel(true), () -> serverListener.cancel(true),
                    () -> executor.shutdownNow(), () -> features.dispose(), () -> Disposer.dispose(wrapper));
        }
        @Override public CompletableFuture<InitializeResult> initialize(InitializeParams params) { return CompletableFuture.completedFuture(new InitializeResult(new ServerCapabilities())); }
        @Override public CompletableFuture<Object> shutdown() { return CompletableFuture.completedFuture(null); }
        @Override public void exit() {}
        @Override public TextDocumentService getTextDocumentService() { return this; }
        @Override public WorkspaceService getWorkspaceService() { return this; }
        @Override public void didOpen(DidOpenTextDocumentParams params) {}
        @Override public void didChange(DidChangeTextDocumentParams params) {}
        @Override public void didClose(DidCloseTextDocumentParams params) {}
        @Override public void didSave(DidSaveTextDocumentParams params) {}
        @Override public void didChangeConfiguration(DidChangeConfigurationParams params) {}
        @Override public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {}
        @Override public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
            lastUri = params.getTextDocument().getUri(); return CompletableFuture.completedFuture(edits);
        }
    }
}

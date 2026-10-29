package dev.goforge.language;

import com.intellij.codeInsight.actions.onSave.FormatOnSaveOptions;
import com.intellij.codeInsight.actions.onSave.OptimizeImportsOnSaveOptions;
import com.intellij.formatting.service.FormattingServiceUtil;
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveManager;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.RunAll;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;
import com.redhat.devtools.lsp4ij.features.formatting.LSPFormattingOnlyService;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition;
import com.redhat.devtools.lsp4ij.templates.ServerMappingSettings;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.services.*;
import org.jdom.Element;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real SaveAll -> awaited Go action -> current LSP server over an in-memory LSP wire.
 * The controlled server supplies edits; it does not pretend to execute gopls formatting semantics. */
public final class GoFormatOnSaveTest extends BasePlatformTestCase {
    private LanguageServerDefinition gopls, fixtureDefinition;
    private boolean oldEnabled;
    private FormatOnSaveOptions oldOptions;
    private Path directory;
    private VirtualFile contentRoot;
    private final List<Editor> editors = new ArrayList<>();
    private WireServer server;
    private volatile boolean formattingEnabled = true;

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue(LanguageServiceAccessor.getInstance(getProject()).getStartedServers().isEmpty());
        gopls = LanguageServersRegistry.getInstance().getServerDefinition("goforge.gopls");
        assertNotNull(gopls);oldEnabled = gopls.isEnabled(getProject());gopls.setEnabled(false, getProject());
        oldOptions = FormatOnSaveOptions.getInstance(getProject()).clone();
        copyState(FormatOnSaveOptions.getInstance(getProject()), new FormatOnSaveOptions(getProject()));
        directory = Files.createTempDirectory("goforge-save-format-");
        contentRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory);assertNotNull(contentRoot);
        PsiTestUtil.addContentRoot(getModule(), contentRoot);
    }

    @Override protected void tearDown() throws Exception {
        var project = getProject();
        RunAll.runAll(
                () -> { if (fixtureDefinition != null) LanguageServersRegistry.getInstance().removeServerDefinition(project, fixtureDefinition); },
                () -> LanguageServiceAccessor.getInstance(project).projectClosing(project),
                () -> { if (server != null) server.stop(); },
                () -> { for (var editor : editors) EditorFactory.getInstance().releaseEditor(editor); },
                () -> { if (oldOptions != null) copyState(FormatOnSaveOptions.getInstance(project), oldOptions); },
                () -> { if (contentRoot != null) PsiTestUtil.removeContentEntry(getModule(), contentRoot); },
                () -> { if (directory != null) FileUtil.delete(directory.toFile()); },
                () -> super.tearDown(),
                () -> { if (gopls != null) gopls.setEnabled(oldEnabled, project); });
    }

    public void testRegisteredDefaultsSelectOnlyGoAndDoNotEnableImportOptimization() {
        var options = new FormatOnSaveOptions(getProject());
        assertTrue(options.isRunOnSaveEnabled());assertFalse(options.isAllFileTypesSelected());
        assertTrue(options.isFileTypeSelected(GoFileType.INSTANCE));
        assertFalse(options.isFileTypeSelected(PlainTextFileType.INSTANCE));
        assertFalse(new OptimizeImportsOnSaveOptions(getProject()).isRunOnSaveEnabled());
    }

    public void testSerializedExplicitDisabledValueOverridesNewDefaults() {
        var options = new FormatOnSaveOptions(getProject());options.setRunOnSaveEnabled(false);
        var restored = new FormatOnSaveOptions(getProject());
        load(restored, XmlSerializer.serialize(options.getState()));
        assertFalse(restored.isRunOnSaveEnabled());
        assertTrue(restored.isFileTypeSelected(GoFileType.INSTANCE));
        assertFalse(restored.isAllFileTypesSelected());
    }

    public void testLoadedExplicitFileTypeSelectionAndAllTypesArePreserved() {
        var options = new FormatOnSaveOptions(getProject());
        load(options, state(true, false, PlainTextFileType.INSTANCE.getName()));
        assertTrue(options.isRunOnSaveEnabled());assertFalse(options.isFileTypeSelected(GoFileType.INSTANCE));
        assertTrue(options.isFileTypeSelected(PlainTextFileType.INSTANCE));
        var restored = new FormatOnSaveOptions(getProject());load(restored, XmlSerializer.serialize(options.getState()));
        assertFalse(restored.isFileTypeSelected(GoFileType.INSTANCE));assertTrue(restored.isFileTypeSelected(PlainTextFileType.INSTANCE));
        load(restored, state(true, true));assertTrue(restored.isAllFileTypesSelected());
        assertTrue(restored.isFileTypeSelected(GoFileType.INSTANCE));assertTrue(restored.isFileTypeSelected(PlainTextFileType.INSTANCE));
    }

    public void testSaveAllUsesAwaitedGoActionAndPersistsLspEdits() throws Exception {
        var file = file("main.go", "package p\nvar value = 0");
        startServer(file);
        var lspFormatter = FormattingServiceUtil.findService(LSPFormattingOnlyService.class);
        assertNotNull(lspFormatter);assertTrue("ready LSP formatting capability", lspFormatter.canFormat(file.psi));
        // Save reformats a whole file. false/false instead asks for ad-hoc fragment
        // formatting, which deliberately excludes a document-only LSP formatter.
        assertInstanceOf(FormattingServiceUtil.findService(file.psi, true, true), LSPFormattingOnlyService.class);
        server.formatted = "package p\n\nvar value = 2\n";
        change(file, "package p\nvar value=2");
        save(file);
        await(() -> server.requests.get() == 1 && file.document.getText().equals(server.formatted)
                && !manager().isDocumentUnsaved(file.document));
        assertEquals(server.formatted, Files.readString(file.path));
        assertEquals(file.file.toNioPath().toUri().toString(), server.lastUri);
        assertEquals("package p\nvar value=2", server.lastInput);
    }

    public void testExplicitDisabledSavePreservesUnformattedTextWithoutRequest() throws Exception {
        var file = file("main.go", "package p\nvar value = 0");startServer(file);
        FormatOnSaveOptions.getInstance(getProject()).setRunOnSaveEnabled(false);
        change(file, "package p\nvar value=2");save(file);
        assertEquals(0, server.requests.get());assertEquals("package p\nvar value=2", Files.readString(file.path));
    }

    public void testExplicitSelectionExcludingGoPreventsFormattingRequest() throws Exception {
        var file = file("main.go", "package p\nvar value = 0");startServer(file);
        load(FormatOnSaveOptions.getInstance(getProject()), state(true, false, PlainTextFileType.INSTANCE.getName()));
        change(file, "package p\nvar value=2");save(file);
        assertEquals(0, server.requests.get());assertEquals("package p\nvar value=2", Files.readString(file.path));
    }

    public void testNonGoSaveDoesNotRequestGoFormatting() throws Exception {
        var go = file("main.go", "package p");startServer(go);
        var text = file("notes.txt", "text");change(text, "text without final newline");save(text);
        assertEquals(0, server.requests.get());assertEquals("text without final newline", Files.readString(text.path));
    }

    public void testNoReadyServerSaveDoesNotInventNewlineOrRewriteRawSource() throws Exception {
        var file = file("main.go", "package p");String incomplete = "package p\nvar s = `unfinished";
        change(file, incomplete);save(file);
        assertEquals(incomplete, Files.readString(file.path));assertEquals(incomplete, file.document.getText());
        assertTrue(LanguageServiceAccessor.getInstance(getProject()).getStartedServers().isEmpty());
    }

    public void testStoppedServerSaveSafelyPreservesUnformattedText() throws Exception {
        var file = file("main.go", "package p");startServer(file);
        LanguageServersRegistry.getInstance().removeServerDefinition(getProject(), fixtureDefinition);fixtureDefinition = null;
        server.stop();change(file, "package p\nvar value=2");save(file);
        assertEquals(0, server.requests.get());assertEquals("package p\nvar value=2", Files.readString(file.path));
    }

    private record Source(Path path, VirtualFile file, Document document, Editor editor, PsiFile psi) {}
    public void testDelayedResponseKeepsSavePendingUntilBufferAndDiskAreFormatted() throws Exception {
        var file=file("main.go","package p\nvar value = 0");startServer(file);
        server.delayed=new CompletableFuture<>();
        change(file,"package p\nvar value=2");triggerSave(file);
        await(() -> server.requests.get()==1);
        assertTrue("real SaveAll must await the unresolved formatting response", onSave().hasPendingActions());
        assertEquals("package p\nvar value=2",file.document.getText());
        assertEquals("initial save may write input, never a fabricated formatted result","package p\nvar value=2",Files.readString(file.path));
        server.delayed.complete(server.edits("package p\n\nvar value = 2\n"));
        await(() -> !onSave().hasPendingActions());
        assertEquals("package p\n\nvar value = 2\n",file.document.getText());
        assertEquals(file.document.getText(),Files.readString(file.path));
        assertFalse(manager().isDocumentUnsaved(file.document));assertEquals(1,server.requests.get());
    }

    public void testTypingCancelsPendingResponseAndDoesNotSaveNewDirtyTyping() throws Exception {
        var file=file("main.go","package p\nvar value = 0");startServer(file);
        // Simulate a server that receives cancellation but still returns a late response.
        server.delayed=new CompletableFuture<>() { @Override public boolean cancel(boolean mayInterrupt) { return false; } };
        change(file,"package p\nvar value=2");triggerSave(file);await(() -> server.requests.get()==1);
        assertTrue(onSave().hasPendingActions());
        change(file,"package p\nvar value=3 // newer typing");
        await(() -> !onSave().hasPendingActions());
        assertTrue(server.delayed.complete(server.edits("package p\n\nvar value = 2\n")));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals("package p\nvar value=3 // newer typing",file.document.getText());
        assertEquals("package p\nvar value=2",Files.readString(file.path));
        assertTrue("cancelled save must not save later user edits",manager().isDocumentUnsaved(file.document));
    }

    public void testClosedEditorWithReadyOverlayFormatsAndPersistsOnce() throws Exception {
        var file=file("main.go","package p\nvar value = 0");startServer(file);
        server.formatted="package p\n\nvar value = 2\n";change(file,"package p\nvar value=2");
        EditorFactory.getInstance().releaseEditor(file.editor);editors.remove(file.editor);
        assertEquals(0,EditorFactory.getInstance().getEditors(file.document,getProject()).length);
        save(file);assertEquals(1,server.requests.get());
        assertEquals(server.formatted,file.document.getText());assertEquals(server.formatted,Files.readString(file.path));
        assertFalse(manager().isDocumentUnsaved(file.document));
    }

    public void testServerFormattingFailureDoesNotChangeSavedInput() throws Exception {
        var file=file("main.go","package p");startServer(file);
        server.delayed=new CompletableFuture<>();change(file,"package p\nvar s = `unfinished");
        triggerSave(file);await(() -> server.requests.get()==1);
        server.delayed.completeExceptionally(new ResponseErrorException(new ResponseError(ResponseErrorCode.RequestFailed,"controlled invalid Go",null)));
        await(() -> !onSave().hasPendingActions());
        assertEquals("package p\nvar s = `unfinished",file.document.getText());
        assertEquals(file.document.getText(),Files.readString(file.path));
    }

    public void testInvalidOverlappingEditsAreRejectedBeforeAnyWrite() throws Exception {
        var file=file("main.go","package p");startServer(file);
        server.delayed=new CompletableFuture<>();change(file,"package p\nvar x=1");triggerSave(file);
        await(() -> server.requests.get()==1);
        server.delayed.complete(List.of(new TextEdit(new Range(new Position(1,0),new Position(1,3)),"const"),
                new TextEdit(new Range(new Position(1,1),new Position(1,4)),"BAD")));
        await(() -> !onSave().hasPendingActions());
        assertEquals("package p\nvar x=1",file.document.getText());assertEquals(file.document.getText(),Files.readString(file.path));
    }

    public void testSettingsDisabledWhileResponseIsPendingRejectLateEdits() throws Exception {
        var file=file("main.go","package p");startServer(file);
        server.delayed=new CompletableFuture<>();change(file,"package p\nvar x=1");triggerSave(file);
        await(() -> server.requests.get()==1);FormatOnSaveOptions.getInstance(getProject()).setRunOnSaveEnabled(false);
        server.delayed.complete(server.edits("package p\n\nvar x = 1\n"));await(() -> !onSave().hasPendingActions());
        assertEquals("package p\nvar x=1",file.document.getText());assertEquals(file.document.getText(),Files.readString(file.path));
    }

    public void testAutomaticRoutingDoesNotDisableManualFormatterOrNonGo() throws Exception {
        // Materialize both files before waiting for indexing/feature readiness: creating
        // another PSI file after the readiness barrier can temporarily disable LSP formatting.
        var plain=file("notes.txt","text");var go=file("main.go","package p");startServer(go);
        var restriction=new GoSaveFormattingRestriction();
        assertTrue(restriction.isFormatterAllowed(go.psi));assertFalse(restriction.isAutoFormatAllowed(go.psi));
        assertTrue(restriction.isFormatterAllowed(plain.psi));assertTrue(restriction.isAutoFormatAllowed(plain.psi));
        assertEquals(com.redhat.devtools.lsp4ij.client.ExecuteLSPFeatureStatus.NOW,
                com.redhat.devtools.lsp4ij.client.indexing.ProjectIndexingManager.canExecuteLSPFeature(go.psi));
        assertInstanceOf(FormattingServiceUtil.findService(go.psi,true,true),LSPFormattingOnlyService.class);
        FormatOnSaveOptions.getInstance(getProject()).setRunOnSaveEnabled(false);
        server.formatted="package p\n\nvar x = 1\n";change(go,"package p\nvar x=1");
        await(() -> "package p\nvar x=1".equals(server.documents.get(go.file.toNioPath().toUri().toString())));
        triggerAction(go,"ReformatCode");
        await(() -> server.requests.get()==1 && server.formatted.equals(go.document.getText()));
        assertEquals("package p\nvar x=1",server.lastInput);
        assertFalse(FormatOnSaveOptions.getInstance(getProject()).isRunOnSaveEnabled());
    }

    public void testChangedLinesOnlyWithActiveVcsNeverExpandsToWholeFile() throws Exception {
        var file=file("main.go","package p");startServer(file);
        com.intellij.testFramework.ServiceContainerUtil.replaceService(
            com.intellij.openapi.application.ApplicationManager.getApplication(),
            com.intellij.codeInsight.actions.VcsFacade.class,new com.intellij.codeInsight.actions.VcsFacade() {
                @Override public boolean hasActiveVcss(Project project) { return true; }
            },getTestRootDisposable());
        var state=state(true,false,GoFileType.INSTANCE.getName());
        state.addContent(new Element("option").setAttribute("name","myFormatOnlyChangedLines").setAttribute("value","true"));
        load(FormatOnSaveOptions.getInstance(getProject()),state);
        assertTrue(FormatOnSaveOptions.getInstance(getProject()).isFormatOnlyChangedLines());
        change(file,"package p\nvar x=1");save(file);
        assertEquals(0,server.requests.get());assertEquals("package p\nvar x=1",Files.readString(file.path));
    }

    public void testDisabledLspFeatureDoesNotRequestFormatting() throws Exception {
        var file=file("main.go","package p");startServer(file);formattingEnabled=false;
        change(file,"package p\nvar x=1");save(file);
        assertEquals(0,server.requests.get());assertEquals("package p\nvar x=1",Files.readString(file.path));
    }

    public void testFeatureDisabledDuringPendingResponsePreventsApplication() throws Exception {
        var file=file("main.go","package p");startServer(file);
        server.delayed=new CompletableFuture<>();change(file,"package p\nvar x=1");triggerSave(file);
        await(() -> server.requests.get()==1);formattingEnabled=false;
        server.delayed.complete(server.edits("package p\n\nvar x = 1\n"));await(() -> !onSave().hasPendingActions());
        assertEquals("package p\nvar x=1",file.document.getText());assertEquals(file.document.getText(),Files.readString(file.path));
    }

    private Source file(String name, String text) throws Exception {
        Path path = directory.resolve(name);Files.writeString(path, text);
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);assertNotNull(file);
        Document document = manager().getDocument(file);assertNotNull(document);
        Editor editor = EditorFactory.getInstance().createEditor(document, getProject(), file.getFileType(), false);editors.add(editor);
        PsiFile psi = PsiManager.getInstance(getProject()).findFile(file);assertNotNull(psi);
        return new Source(path, file, document, editor, psi);
    }
    private static FileDocumentManager manager() { return FileDocumentManager.getInstance(); }
    private void change(Source file, String text) { WriteCommandAction.runWriteCommandAction(getProject(), () -> file.document.setText(text)); }
    private static void await(java.util.function.BooleanSupplier condition) { PlatformTestUtil.waitWithEventsDispatching("Save/LSP operation did not finish", condition, 15); }
    private ActionsOnSaveManager onSave() { return getProject().getService(ActionsOnSaveManager.class); }
    private void save(Source file) { triggerSave(file);await(() -> !onSave().hasPendingActions()); }
    private void triggerSave(Source file) { triggerAction(file,"SaveAll"); }
    private void triggerAction(Source file,String actionId) {
        var action = ActionManager.getInstance().getAction(actionId);assertNotNull(action);
        var builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject()).add(CommonDataKeys.VIRTUAL_FILE, file.file);
        if (!file.editor.isDisposed()) builder.add(CommonDataKeys.EDITOR,file.editor)
                .add(PlatformCoreDataKeys.CONTEXT_COMPONENT,file.editor.getContentComponent());
        var context=builder.build();
        var event = new TestActionEvent(context, action);
        ActionUtil.performActionDumbAwareWithCallbacks(action, event);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void copyState(FormatOnSaveOptions target, FormatOnSaveOptions source) {
        ((PersistentStateComponent) target).loadState(source.getState());
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void load(FormatOnSaveOptions options, Element xml) {
        Object current = options.getState();Object state = XmlSerializer.deserialize(xml, current.getClass());
        ((PersistentStateComponent) options).loadState(state);
    }
    private static Element state(boolean enabled, boolean all, String... types) {
        var root = new Element("State");root.addContent(new Element("option").setAttribute("name", "myRunOnSave").setAttribute("value", Boolean.toString(enabled)));
        root.addContent(new Element("option").setAttribute("name", "myAllFileTypesSelected").setAttribute("value", Boolean.toString(all)));
        var set = new Element("set");for (String type : types) set.addContent(new Element("option").setAttribute("value", type));
        root.addContent(new Element("option").setAttribute("name", "mySelectedFileTypes").addContent(set));return root;
    }
    private void startServer(Source source) throws Exception {
        server = new WireServer();
        fixtureDefinition = new LanguageServerDefinition("goforge.test.save-format", "Save formatting fixture", "In-memory test LSP", false, 60, false) {
            @Override public StreamConnectionProvider createConnectionProvider(Project project) { return server; }
            @Override public com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures createClientFeatures() {
                return new com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures().setFormattingFeature(
                    new com.redhat.devtools.lsp4ij.client.features.LSPFormattingFeature() {
                        @Override public boolean isEnabled(PsiFile file) { return formattingEnabled && super.isEnabled(file); }
                    });
            }
        };
        LanguageServersRegistry.getInstance().addServerDefinition(getProject(), fixtureDefinition,
                List.of(ServerMappingSettings.createFileTypeMappingSettings(GoFileType.INSTANCE.getName(), "go")));
        var future = LanguageServiceAccessor.getInstance(getProject()).getLanguageServers(source.psi,
                feature -> feature.getServerWrapper().getServerDefinition() == fixtureDefinition, null);
        await(future::isDone);assertEquals(1, future.get(1, TimeUnit.SECONDS).size());
        await(() -> server.documents.containsKey(source.file.toNioPath().toUri().toString()));
        await(() -> com.redhat.devtools.lsp4ij.client.indexing.ProjectIndexingManager.canExecuteLSPFeature(source.psi)
                == com.redhat.devtools.lsp4ij.client.ExecuteLSPFeatureStatus.NOW);
    }

    private static final class WireServer implements StreamConnectionProvider, LanguageServer, TextDocumentService, WorkspaceService {
        private final PipedInputStream clientInput = new PipedInputStream(64 * 1024);
        private final PipedInputStream serverInput = new PipedInputStream(64 * 1024);
        private final PipedOutputStream clientOutput;
        private final PipedOutputStream serverOutput;
        private final ExecutorService executor = Executors.newCachedThreadPool(r -> { var t = new Thread(r, "goforge-save-format-fixture");t.setDaemon(true);return t; });
        private Future<?> listener;
        final Map<String, String> documents = new ConcurrentHashMap<>();
        final AtomicInteger requests = new AtomicInteger();
        volatile String formatted, lastUri, lastInput;
        volatile CompletableFuture<List<? extends TextEdit>> delayed;
        WireServer() throws IOException { clientOutput = new PipedOutputStream(serverInput);serverOutput = new PipedOutputStream(clientInput); }
        @Override public void start() {
            var launcher = new Launcher.Builder<LanguageClient>().setLocalService(this).setRemoteInterface(LanguageClient.class)
                    .setInput(serverInput).setOutput(serverOutput).setExecutorService(executor).create();
            listener = launcher.startListening();
        }
        @Override public InputStream getInputStream() { return clientInput; }
        @Override public OutputStream getOutputStream() { return clientOutput; }
        @Override public void stop() {
            for (Closeable stream : List.of(clientInput, serverInput, clientOutput, serverOutput)) { try { stream.close(); } catch (IOException ignored) {} }
            if (listener != null) listener.cancel(true);executor.shutdownNow();
        }
        @Override public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            var capabilities = new ServerCapabilities();capabilities.setDocumentFormattingProvider(true);capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
            return CompletableFuture.completedFuture(new InitializeResult(capabilities));
        }
        @Override public CompletableFuture<Object> shutdown() { return CompletableFuture.completedFuture(null); }
        @Override public void exit() {}
        @Override public TextDocumentService getTextDocumentService() { return this; }
        @Override public WorkspaceService getWorkspaceService() { return this; }
        @Override public void didOpen(DidOpenTextDocumentParams params) { documents.put(params.getTextDocument().getUri(), params.getTextDocument().getText()); }
        @Override public void didChange(DidChangeTextDocumentParams params) { documents.put(params.getTextDocument().getUri(), params.getContentChanges().getLast().getText()); }
        @Override public void didClose(DidCloseTextDocumentParams params) { documents.remove(params.getTextDocument().getUri()); }
        @Override public void didSave(DidSaveTextDocumentParams params) {}
        @Override public void didChangeConfiguration(DidChangeConfigurationParams params) {}
        @Override public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {}
        @Override public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
            lastUri = params.getTextDocument().getUri();lastInput = documents.get(lastUri);requests.incrementAndGet();
            if (delayed != null) return delayed;
            if (formatted == null) return CompletableFuture.failedFuture(new ResponseErrorException(new ResponseError(ResponseErrorCode.RequestFailed, "Test formatter response was not configured", null)));
            return CompletableFuture.completedFuture(edits(formatted));
        }
        List<? extends TextEdit> edits(String replacement) {
            var lines=lastInput.split("\n",-1);var end=new Position(lines.length-1,lines[lines.length-1].length());
            return List.of(new TextEdit(new Range(new Position(0,0),end),replacement));
        }
    }
}

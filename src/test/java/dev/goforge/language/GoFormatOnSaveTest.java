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
    private OptimizeImportsOnSaveOptions oldImportOptions;
    private boolean importFixture;
    private volatile boolean importFeatureEnabled=true;
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
        oldImportOptions=OptimizeImportsOnSaveOptions.getInstance(getProject()).clone();
        copyImportState(OptimizeImportsOnSaveOptions.getInstance(getProject()),new OptimizeImportsOnSaveOptions(getProject()));
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
                () -> { if (oldImportOptions != null) copyImportState(OptimizeImportsOnSaveOptions.getInstance(project), oldImportOptions); },
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

    public void testExplicitCrLfFileKeepsCrLfWhenFormattingIsDisabled() throws Exception {
        var file=file("crlf.go","package p\r\nvar value = 0");startServer(file);
        FormatOnSaveOptions.getInstance(getProject()).setRunOnSaveEnabled(false);
        String input="package p\nvar value=2";change(file,input);save(file);
        assertEquals(0,server.requests.get());assertEquals(input,file.document.getText());
        assertEquals("package p\r\nvar value=2",Files.readString(file.path));
        assertEquals("\r\n",file.file.getDetectedLineSeparator());
    }

    public void testFormattingUsesNormalizedLspTextAndPreservesExplicitCrLfOnDisk() throws Exception {
        var file=file("crlf.go","package p\r\nvar value = 0");startServer(file);
        server.formatted="package p\n\nvar value = 2\n";
        String input="package p\nvar value=2";change(file,input);save(file);
        assertEquals(1,server.requests.get());assertEquals(input,server.lastInput);
        assertEquals(server.formatted,file.document.getText());
        assertEquals("package p\r\n\r\nvar value = 2\r\n",Files.readString(file.path));
        assertEquals("\r\n",file.file.getDetectedLineSeparator());
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

    public void testOptimizeImportsWorksWhenFormattingIsDisabledAndSaveWaitsForIt() throws Exception {
        var file=file("imports.go","package p\nvar value = 0");startImportServer(file);
        FormatOnSaveOptions.getInstance(getProject()).setRunOnSaveEnabled(false);enableImports();
        String input="package p\nvar value=fmt.Sprint(2)";change(file,input);
        server.importDelayed=new CompletableFuture<>();triggerSave(file);await(()->server.importRequests.get()==1);
        assertTrue(onSave().hasPendingActions());assertEquals(input,file.document.getText());assertEquals(input,Files.readString(file.path));
        String organized="package p\nimport \"fmt\"\nvar value=fmt.Sprint(2)";
        server.importDelayed.complete(server.importActions(organized));await(()->!onSave().hasPendingActions());
        assertEquals(0,server.requests.get());assertEquals(1,server.importRequests.get());
        assertEquals(List.of(CodeActionKind.SourceOrganizeImports),server.lastImportOnly);assertTrue(server.lastImportDiagnostics.isEmpty());
        assertEquals(organized,file.document.getText());assertEquals(organized,Files.readString(file.path));
        assertFalse(manager().isDocumentUnsaved(file.document));
    }

    public void testImportsThenFormattingUsesTheUpdatedOverlayExactlyOnce() throws Exception {
        var file=file("imports.go","package p\nvar value = 0");startImportServer(file);enableImports();
        server.organized="package p\nimport \"fmt\"\nvar value=fmt.Sprint(2)";
        server.formatted="package p\n\nimport \"fmt\"\n\nvar value = fmt.Sprint(2)\n";
        change(file,"package p\nvar value=fmt.Sprint(2)");save(file);
        assertEquals(1,server.importRequests.get());assertEquals(1,server.requests.get());
        assertEquals(server.organized,server.lastInput);assertTrue(server.lastFormatVersion>server.lastImportVersion);
        assertEquals(server.formatted,file.document.getText());assertEquals(server.formatted,Files.readString(file.path));
        var undo=com.intellij.openapi.command.undo.UndoManager.getInstance(getProject());
        var editor=com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(file.editor);
        undo.undo(editor);assertEquals("package p\nvar value=fmt.Sprint(2)",file.document.getText());
        assertEquals(server.formatted,Files.readString(file.path));undo.redo(editor);assertEquals(server.formatted,file.document.getText());
    }

    public void testDisabledOrUnselectedImportPreferenceDoesNotRequestImports() throws Exception {
        var file=file("imports.go","package p\nvar value = 0");startImportServer(file);
        FormatOnSaveOptions.getInstance(getProject()).setRunOnSaveEnabled(false);
        change(file,"package p\nvar value=fmt.Sprint(2)");save(file);assertEquals(0,server.importRequests.get());
        loadImportState(state(true,false,PlainTextFileType.INSTANCE.getName()));
        change(file,"package p\nvar value=fmt.Sprint(3)");save(file);assertEquals(0,server.importRequests.get());
        assertEquals("package p\nvar value=fmt.Sprint(3)",Files.readString(file.path));
    }

    public void testTypingCancelsPendingImportsWithoutSavingNewTyping() throws Exception {
        var file=file("imports.go","package p\nvar value = 0");startImportServer(file);enableImports();
        FormatOnSaveOptions.getInstance(getProject()).setRunOnSaveEnabled(false);
        server.importDelayed=new CompletableFuture<>(){@Override public boolean cancel(boolean interrupt){return false;}};
        String initial="package p\nvar value=fmt.Sprint(2)";change(file,initial);triggerSave(file);await(()->server.importRequests.get()==1);
        change(file,initial+" // newer typing");await(()->!onSave().hasPendingActions());
        server.importDelayed.complete(server.importActions("package p\nimport \"fmt\"\nvar value=fmt.Sprint(2)"));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals(initial+" // newer typing",file.document.getText());assertEquals(initial,Files.readString(file.path));
        assertTrue(manager().isDocumentUnsaved(file.document));
    }

    public void testDisablingImportPreferenceRejectsPendingResponse() throws Exception {
        var file=file("imports.go","package p\nvar value = 0");startImportServer(file);enableImports();
        FormatOnSaveOptions.getInstance(getProject()).setRunOnSaveEnabled(false);server.importDelayed=new CompletableFuture<>();
        String input="package p\nvar value=fmt.Sprint(2)";change(file,input);triggerSave(file);await(()->server.importRequests.get()==1);
        OptimizeImportsOnSaveOptions.getInstance(getProject()).setRunOnSaveEnabled(false);
        server.importDelayed.complete(server.importActions("package p\nimport \"fmt\"\nvar value=fmt.Sprint(2)"));await(()->!onSave().hasPendingActions());
        assertEquals(input,file.document.getText());assertEquals(input,Files.readString(file.path));
    }

    public void testDisablingCodeActionFeatureRejectsPendingImports() throws Exception {
        var file=file("imports.go","package p\nvar value = 0");startImportServer(file);enableImports();
        FormatOnSaveOptions.getInstance(getProject()).setRunOnSaveEnabled(false);server.importDelayed=new CompletableFuture<>();
        String input="package p\nvar value=fmt.Sprint(2)";change(file,input);triggerSave(file);await(()->server.importRequests.get()==1);
        importFeatureEnabled=false;
        server.importDelayed.complete(server.importActions("package p\nimport \"fmt\"\nvar value=fmt.Sprint(2)"));await(()->!onSave().hasPendingActions());
        assertEquals(input,file.document.getText());assertEquals(input,Files.readString(file.path));
    }

    public void testInvalidImportCommandDoesNotExecuteOrFallThroughToFormatting() throws Exception {
        var file=file("imports.go","package p\nvar value = 0");startImportServer(file);enableImports();
        server.importDelayed=new CompletableFuture<>();String input="package p\nvar value=2";change(file,input);triggerSave(file);
        await(()->server.importRequests.get()==1);
        server.importDelayed.complete(List.of(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(new Command("Forbidden","must.not.execute"))));
        await(()->!onSave().hasPendingActions());assertEquals(0,server.requests.get());assertEquals(0,server.commands.get());assertEquals(0,server.resolutions.get());
        assertEquals(input,file.document.getText());assertEquals(input,Files.readString(file.path));
    }

    public void testImportOnlySavePreservesCrLfAndSupportsUndoRedo() throws Exception {
        var file=file("imports.go","package p\r\nvar value = 0");startImportServer(file);enableImports();
        FormatOnSaveOptions.getInstance(getProject()).setRunOnSaveEnabled(false);
        String input="package p\nvar value=fmt.Sprint(2)";server.organized="package p\nimport \"fmt\"\nvar value=fmt.Sprint(2)";
        change(file,input);save(file);String saved="package p\r\nimport \"fmt\"\r\nvar value=fmt.Sprint(2)";
        assertEquals(saved,Files.readString(file.path));assertEquals(server.organized,file.document.getText());
        var undo=com.intellij.openapi.command.undo.UndoManager.getInstance(getProject());
        var editor=com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(file.editor);
        assertTrue(undo.isUndoAvailable(editor));undo.undo(editor);assertEquals(input,file.document.getText());assertEquals(saved,Files.readString(file.path));
        assertTrue(undo.isRedoAvailable(editor));undo.redo(editor);assertEquals(server.organized,file.document.getText());assertEquals(saved,Files.readString(file.path));
    }

    public void testImportOnlyNoOverlayDoesNotStartTheServer() throws Exception {
        var file=file("imports.go","package p\nvar value = 0");enableImports();
        FormatOnSaveOptions.getInstance(getProject()).setRunOnSaveEnabled(false);
        String input="package p\nvar value=fmt.Sprint(2)";change(file,input);save(file);
        assertEquals(input,Files.readString(file.path));assertTrue(LanguageServiceAccessor.getInstance(getProject()).getStartedServers().isEmpty());
    }

    public void testFormattingFailureAfterValidImportsRetainsOnlyTheValidImportEdit() throws Exception {
        var file=file("imports.go","package p\nvar value = 0");startImportServer(file);enableImports();
        server.organized="package p\nimport \"fmt\"\nvar value=fmt.Sprint(2)";
        server.delayed=CompletableFuture.failedFuture(new IllegalStateException("controlled format failure"));
        change(file,"package p\nvar value=fmt.Sprint(2)");save(file);
        assertEquals(1,server.importRequests.get());assertEquals(1,server.requests.get());
        assertEquals(server.organized,file.document.getText());assertEquals(server.organized,Files.readString(file.path));
    }

    public void testChangedLinesFormattingPreferenceDoesNotDisableExplicitImportOptimization() throws Exception {
        var file=file("imports.go","package p\nvar value = 0");startImportServer(file);enableImports();
        com.intellij.testFramework.ServiceContainerUtil.replaceService(
            com.intellij.openapi.application.ApplicationManager.getApplication(),
            com.intellij.codeInsight.actions.VcsFacade.class,new com.intellij.codeInsight.actions.VcsFacade(){
                @Override public boolean hasActiveVcss(Project project){return true;}
            },getTestRootDisposable());
        var state=state(true,false,GoFileType.INSTANCE.getName());
        state.addContent(new Element("option").setAttribute("name","myFormatOnlyChangedLines").setAttribute("value","true"));
        load(FormatOnSaveOptions.getInstance(getProject()),state);
        server.organized="package p\nimport \"fmt\"\nvar value=fmt.Sprint(2)";
        change(file,"package p\nvar value=fmt.Sprint(2)");save(file);
        assertEquals(1,server.importRequests.get());assertEquals(0,server.requests.get());
        assertEquals(server.organized,file.document.getText());assertEquals(server.organized,Files.readString(file.path));
    }

    public void testNoOpImportsStillFormatTheSameCurrentOverlay() throws Exception {
        var file=file("imports.go","package p\nvar value = 0");startImportServer(file);enableImports();
        String input="package p\nvar value=2";server.formatted="package p\n\nvar value = 2\n";
        change(file,input);save(file);
        assertEquals(1,server.importRequests.get());assertEquals(1,server.requests.get());
        assertEquals(server.lastImportVersion,server.lastFormatVersion);assertEquals(input,server.lastInput);
        assertEquals(server.formatted,Files.readString(file.path));
        var undo=com.intellij.openapi.command.undo.UndoManager.getInstance(getProject());
        var editor=com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(file.editor);
        undo.undo(editor);assertEquals(input,file.document.getText());undo.redo(editor);assertEquals(server.formatted,file.document.getText());
    }

    public void testStaleImportVersionRejectsAllEditsAndDoesNotFormat() throws Exception {
        var file=file("imports.go","package p\nvar value = 0");startImportServer(file);enableImports();
        String input="package p\nvar value=fmt.Sprint(2)";
        server.importDelayed=new CompletableFuture<>();change(file,input);triggerSave(file);await(()->server.importRequests.get()==1);
        var response=server.importActions("package p\nimport \"fmt\"\nvar value=fmt.Sprint(2)");
        response.getFirst().getRight().getEdit().getDocumentChanges().getFirst().getLeft().getTextDocument().setVersion(server.lastImportVersion-1);
        server.importDelayed.complete(response);await(()->!onSave().hasPendingActions());
        assertEquals(0,server.requests.get());assertEquals(input,file.document.getText());assertEquals(input,Files.readString(file.path));
    }

    public void testTypingWhileFormattingKeepsAlreadyAppliedImportsAndRejectsLateFormat() throws Exception {
        var file=file("imports.go","package p\nvar value = 0");startImportServer(file);enableImports();
        String input="package p\nvar value=fmt.Sprint(2)";
        server.organized="package p\nimport \"fmt\"\nvar value=fmt.Sprint(2)";
        server.delayed=new CompletableFuture<>(){@Override public boolean cancel(boolean interrupt){return false;}};
        change(file,input);triggerSave(file);await(()->server.requests.get()==1);
        assertEquals(server.organized,file.document.getText());assertEquals(input,Files.readString(file.path));
        String typed=server.organized+" // typing after import edits";change(file,typed);await(()->!onSave().hasPendingActions());
        server.delayed.complete(server.edits("package p\n\nimport \"fmt\"\n\nvar value = fmt.Sprint(2)\n"));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals(typed,file.document.getText());assertEquals(input,Files.readString(file.path));assertTrue(manager().isDocumentUnsaved(file.document));
        var undo=com.intellij.openapi.command.undo.UndoManager.getInstance(getProject());
        var editor=com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(file.editor);
        undo.undo(editor);assertEquals(server.organized,file.document.getText());
        undo.undo(editor);assertEquals(input,file.document.getText());
    }

    private void enableImports(){loadImportState(state(true,false,GoFileType.INSTANCE.getName()));}
    public void testGoAppearsInActualOptimizeImportsSaveTypeProviderAndHasManualOptimizer() throws Exception {
        var settings=new com.intellij.openapi.options.ex.Settings(List.of()){
            @Override protected org.jetbrains.concurrency.Promise<? super Object> selectImpl(com.intellij.openapi.options.Configurable value){return org.jetbrains.concurrency.Promises.resolvedPromise(null);}
            @Override protected com.intellij.openapi.options.Configurable getConfigurableWithInitializedUiComponentImpl(com.intellij.openapi.options.Configurable value,boolean create){return value;}
            @Override protected void checkModifiedImpl(com.intellij.openapi.options.Configurable value){}
            @Override protected void setSearchText(String value){}
        };
        var contextType=com.intellij.ide.actionsOnSave.ActionOnSaveContext.class;
        var constructor=contextType.getDeclaredConstructor(Project.class,com.intellij.openapi.options.ex.Settings.class,com.intellij.openapi.Disposable.class);
        constructor.setAccessible(true);var context=constructor.newInstance(getProject(),settings,getTestRootDisposable());
        var infoType=Class.forName("com.intellij.codeInsight.actions.onSave.OptimizeImportsOnSaveActionInfo");
        var infoConstructor=infoType.getDeclaredConstructor(contextType);infoConstructor.setAccessible(true);var info=infoConstructor.newInstance(context);
        var collect=infoType.getDeclaredMethod("addApplicableFileTypes",Collection.class);collect.setAccessible(true);
        var types=new ArrayList<com.intellij.openapi.fileTypes.FileType>();collect.invoke(info,types);
        assertTrue("The actual save type chooser must include Go",types.contains(GoFileType.INSTANCE));
        var source=file("manual.go","package p\n");
        var optimizers=FormattingServiceUtil.findImportsOptimizingService(source.psi).getImportOptimizers(source.psi);
        assertTrue(optimizers.stream().anyMatch(value->value instanceof GoImportOptimizer));
        assertFalse(com.intellij.lang.LanguageFormatting.INSTANCE.isAutoFormatAllowed(source.psi));
    }

    public void testManualImportsUsePlatformProcessorAndUndoWithSavePreferenceDisabled() throws Exception {
        var source=file("manual.go","package p\nvar value = 0");startImportServer(source);
        FormatOnSaveOptions.getInstance(getProject()).setRunOnSaveEnabled(false);
        OptimizeImportsOnSaveOptions.getInstance(getProject()).setRunOnSaveEnabled(false);
        String input="package p\nvar value=fmt.Sprint(1)";change(source,input);
        server.organized="package p\nimport \"fmt\"\nvar value=fmt.Sprint(1)";
        var processor=CompletableFuture.runAsync(()->new com.intellij.codeInsight.actions.OptimizeImportsProcessor(getProject(),source.psi).runWithoutProgress(),
                com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService());
        await(processor::isDone);processor.get(1,TimeUnit.SECONDS);
        assertEquals(server.organized,source.document.getText());assertEquals(input,server.lastImportInput);
        assertEquals(1,server.importRequests.get());assertEquals(0,server.requests.get());
        assertEquals(List.of(CodeActionKind.SourceOrganizeImports),server.lastImportOnly);
        assertEquals(0,server.commands.get());assertEquals(0,server.resolutions.get());
        var undo=com.intellij.openapi.command.undo.UndoManager.getInstance(getProject());
        var editor=com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(source.editor);
        undo.undo(editor);assertEquals(input,source.document.getText());undo.redo(editor);assertEquals(server.organized,source.document.getText());
        assertTrue(manager().isDocumentUnsaved(source.document));
    }

    public void testManualPreparedImportsRejectInterveningTyping() throws Exception {
        var source=file("manual.go","package p\nvar value=fmt.Sprint(1)");startImportServer(source);
        server.organized="package p\nimport \"fmt\"\nvar value=fmt.Sprint(1)";
        Runnable prepared=prepareManual(source);String edited=source.document.getText()+"\n// new user input";change(source,edited);
        WriteCommandAction.runWriteCommandAction(getProject(),prepared);assertEquals(edited,source.document.getText());
        assertNull(((com.intellij.lang.ImportOptimizer.CollectingInfoRunnable)prepared).getUserNotificationInfo());
    }

    public void testManualPreparedImportsRejectDisabledFeature() throws Exception {
        var source=file("manual.go","package p\nvar value=fmt.Sprint(1)");startImportServer(source);
        server.organized="package p\nimport \"fmt\"\nvar value=fmt.Sprint(1)";Runnable prepared=prepareManual(source);
        importFeatureEnabled=false;WriteCommandAction.runWriteCommandAction(getProject(),prepared);
        assertEquals("package p\nvar value=fmt.Sprint(1)",source.document.getText());
    }

    public void testManualImportCancellationDoesNotApplyLateServerResponse() throws Exception {
        var source=file("manual.go","package p\nvar value=fmt.Sprint(1)");startImportServer(source);
        server.importDelayed=new CompletableFuture<>();var indicator=new com.intellij.openapi.progress.EmptyProgressIndicator();
        var prepared=CompletableFuture.supplyAsync(()->com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(
                ()->com.intellij.openapi.application.ReadAction.compute(()->new GoImportOptimizer().processFile(source.psi)),indicator),
                com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService());
        await(()->server.importRequests.get()==1);indicator.cancel();await(prepared::isDone);
        var failed=org.junit.Assert.assertThrows(ExecutionException.class,()->prepared.get(1,TimeUnit.SECONDS));
        assertTrue(failed.getCause() instanceof com.intellij.openapi.progress.ProcessCanceledException);
        server.importDelayed.complete(server.importActions("package p\nimport \"fmt\"\nvar value=fmt.Sprint(1)"));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();assertEquals("package p\nvar value=fmt.Sprint(1)",source.document.getText());
    }

    public void testManualImportsNoOpLeavesDocumentStampAndUndoUntouched() throws Exception {
        var source=file("manual.go","package p\n");startImportServer(source);
        long stamp=source.document.getModificationStamp();Runnable prepared=prepareManual(source);
        assertSame(com.intellij.openapi.util.EmptyRunnable.getInstance(),prepared);
        assertEquals(stamp,source.document.getModificationStamp());assertEquals(1,server.importRequests.get());
    }

    private Runnable prepareManual(Source source)throws Exception{
        var future=CompletableFuture.supplyAsync(()->com.intellij.openapi.application.ReadAction.nonBlocking(
                ()->new GoImportOptimizer().processFile(source.psi)).executeSynchronously(),
                com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService());
        await(future::isDone);return future.get(1,TimeUnit.SECONDS);
    }

    private void startImportServer(Source source)throws Exception{importFixture=true;startServer(source);}
    @SuppressWarnings({"rawtypes","unchecked"})
    private static void copyImportState(OptimizeImportsOnSaveOptions target,OptimizeImportsOnSaveOptions source){((PersistentStateComponent)target).loadState(source.getState());}
    @SuppressWarnings({"rawtypes","unchecked"})
    private void loadImportState(Element xml){var options=OptimizeImportsOnSaveOptions.getInstance(getProject());Object current=options.getState();Object state=XmlSerializer.deserialize(xml,current.getClass());((PersistentStateComponent)options).loadState(state);}

    private Source file(String name, String text) throws Exception {
        Path path = directory.resolve(name);Files.writeString(path, text);
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);assertNotNull(file);
        Document document = manager().getDocument(file);assertNotNull(document);
        // A one-line fixture has no separator to detect. Declare its byte contract explicitly
        // instead of inheriting the host default (CRLF on Windows); disk assertions stay exact.
        file.setDetectedLineSeparator(text.contains("\r\n") ? "\r\n" : "\n");
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
                var features=importFixture?new dev.goforge.lsp.GoplsClientFeatures():new com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures();
                if(importFixture)features.setCodeActionFeature(new com.redhat.devtools.lsp4ij.client.features.LSPCodeActionFeature(){
                    @Override public boolean isEnabled(PsiFile file){return importFeatureEnabled&&super.isEnabled(file);}
                });
                return features.setFormattingFeature(
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
        final AtomicInteger importRequests=new AtomicInteger(),commands=new AtomicInteger(),resolutions=new AtomicInteger();
        final Map<String,Integer> versions=new ConcurrentHashMap<>();
        volatile String organized,lastImportUri,lastImportInput;
        volatile int lastImportVersion,lastFormatVersion;
        volatile List<String> lastImportOnly;
        volatile List<Diagnostic> lastImportDiagnostics;
        volatile CompletableFuture<List<org.eclipse.lsp4j.jsonrpc.messages.Either<Command,CodeAction>>> importDelayed;
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
            var capabilities = new ServerCapabilities();capabilities.setDocumentFormattingProvider(true);capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);capabilities.setCodeActionProvider(new CodeActionOptions(List.of(CodeActionKind.SourceOrganizeImports)));
            return CompletableFuture.completedFuture(new InitializeResult(capabilities));
        }
        @Override public CompletableFuture<Object> shutdown() { return CompletableFuture.completedFuture(null); }
        @Override public void exit() {}
        @Override public TextDocumentService getTextDocumentService() { return this; }
        @Override public WorkspaceService getWorkspaceService() { return this; }
        @Override public void didOpen(DidOpenTextDocumentParams params) { documents.put(params.getTextDocument().getUri(), params.getTextDocument().getText());versions.put(params.getTextDocument().getUri(),params.getTextDocument().getVersion()); }
        @Override public void didChange(DidChangeTextDocumentParams params) { documents.put(params.getTextDocument().getUri(), params.getContentChanges().getLast().getText());versions.put(params.getTextDocument().getUri(),params.getTextDocument().getVersion()); }
        @Override public void didClose(DidCloseTextDocumentParams params) { documents.remove(params.getTextDocument().getUri()); }
        @Override public void didSave(DidSaveTextDocumentParams params) {}
        @Override public void didChangeConfiguration(DidChangeConfigurationParams params) {}
        @Override public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {}
        @Override public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
            lastUri = params.getTextDocument().getUri();lastInput = documents.get(lastUri);lastFormatVersion=versions.getOrDefault(lastUri,-1);requests.incrementAndGet();
            if (delayed != null) return delayed;
            if (formatted == null) return CompletableFuture.failedFuture(new ResponseErrorException(new ResponseError(ResponseErrorCode.RequestFailed, "Test formatter response was not configured", null)));
            return CompletableFuture.completedFuture(edits(formatted));
        }
        @Override public CompletableFuture<List<org.eclipse.lsp4j.jsonrpc.messages.Either<Command,CodeAction>>> codeAction(CodeActionParams params){
            lastImportUri=params.getTextDocument().getUri();lastImportInput=documents.get(lastImportUri);lastImportVersion=versions.get(lastImportUri);
            lastImportOnly=params.getContext().getOnly();lastImportDiagnostics=params.getContext().getDiagnostics();importRequests.incrementAndGet();
            return importDelayed!=null?importDelayed:CompletableFuture.completedFuture(organized==null?List.of():importActions(organized));
        }
        @Override public CompletableFuture<CodeAction> resolveCodeAction(CodeAction action){resolutions.incrementAndGet();return CompletableFuture.failedFuture(new AssertionError("Unexpected resolve"));}
        @Override public CompletableFuture<Object> executeCommand(ExecuteCommandParams params){commands.incrementAndGet();return CompletableFuture.failedFuture(new AssertionError("Unexpected command"));}
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<org.eclipse.lsp4j.jsonrpc.messages.Either<Command,CodeAction>> importActions(String replacement){
            var lines=lastImportInput.split("\n",-1);var end=new Position(lines.length-1,lines[lines.length-1].length());
            var action=new CodeAction("Organize Imports");action.setKind(CodeActionKind.SourceOrganizeImports);
            // The pinned runtime's wire serializer requires Either<TextEdit, SnippetTextEdit>;
            // the platform compile API still declares List<TextEdit>.
            var document=new TextDocumentEdit(new VersionedTextDocumentIdentifier(lastImportUri,lastImportVersion),
                (List)List.of(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(new TextEdit(new Range(new Position(0,0),end),replacement))));
            action.setEdit(new WorkspaceEdit(List.of(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(document))));
            return List.of(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(action));
        }
        List<? extends TextEdit> edits(String replacement) {
            var lines=lastInput.split("\n",-1);var end=new Position(lines.length-1,lines[lines.length-1].length());
            return List.of(new TextEdit(new Range(new Position(0,0),end),replacement));
        }
    }
}

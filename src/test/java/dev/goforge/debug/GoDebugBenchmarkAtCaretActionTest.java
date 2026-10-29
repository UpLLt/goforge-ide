package dev.goforge.debug;

import com.google.gson.JsonParser;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.RunAll;
import com.intellij.util.ThrowableRunnable;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import dev.goforge.run.GoCommand;
import dev.goforge.run.GoRunConfiguration;
import dev.goforge.run.GoRunConfigurationType;
import dev.goforge.settings.GoProjectSettings;
import dev.goforge.testing.GoTestRunRegistry;
import dev.goforge.testing.GoTestSelection;
import dev.goforge.testing.GoTestSourceIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Real action/Task/RunManager/source guard. External discovery and final process dispatch are replaced. */
public final class GoDebugBenchmarkAtCaretActionTest extends BasePlatformTestCase {
    private static final String TEXT = "package p\nimport \"testing\"\nfunc BenchmarkOne(b *testing.B) { println(42) }\nfunc TestOne(t *testing.T) {}\n";
    private Path directory; private VirtualFile root; private Document document;
    private RunManager manager; private List<RunnerAndConfigurationSettings> original; private RunnerAndConfigurationSettings selected;
    private final AtomicReference<String> dialog = new AtomicReference<>();
    private final List<String> stages = new CopyOnWriteArrayList<>();
    private LanguageServerDefinition gopls;
    private LanguageServiceAccessor accessor;
    private boolean oldGoplsEnabled;
    @Override protected void setUp() throws Exception {
        super.setUp();
        accessor = LanguageServiceAccessor.getInstance(getProject());
        assertNoLanguageServer("fixture must start without retained language-server wrappers");
        gopls = LanguageServersRegistry.getInstance().getServerDefinition("goforge.gopls");
        assertNotNull(gopls);
        oldGoplsEnabled = gopls.isEnabled(getProject());
        // These tests inject discovery or replay results; opening the real editor must
        // not launch an unrelated gopls instance. Restore only after fixture editors close.
        gopls.setEnabled(false, getProject());
        assertFalse(gopls.isEnabled(getProject()));
        directory = Files.createTempDirectory(Files.createDirectories(Path.of(getProject().getBasePath())), "benchmark-caret-").toRealPath();
        Files.writeString(directory.resolve("fixture_test.go"), TEXT);
        root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory); assertNotNull(root);
        PsiTestUtil.addContentRoot(getModule(), root);
        var file = root.findChild("fixture_test.go"); assertNotNull(file); myFixture.openFileInEditor(file);
        document = myFixture.getEditor().getDocument(); myFixture.getEditor().getCaretModel().moveToOffset(TEXT.indexOf("42"));
        manager = RunManager.getInstance(getProject()); original = List.copyOf(manager.getAllSettings()); selected = manager.getSelectedConfiguration();
        TestDialogManager.setTestDialog(message -> { dialog.set(message); return Messages.OK; }, getTestRootDisposable());
    }
    @Override protected void runTestRunnable(ThrowableRunnable<Throwable> testRunnable) throws Throwable {
        Throwable testFailure = null;
        try {
            super.runTestRunnable(testRunnable);
        } catch (Throwable failure) {
            testFailure = failure;
            throw failure;
        } finally {
            try { assertNoLanguageServer("fixture actions must not create language-server wrappers"); }
            catch (Throwable isolationFailure) {
                if (testFailure != null) testFailure.addSuppressed(isolationFailure);
                else throw isolationFailure;
            }
        }
    }
    private void assertNoLanguageServer(String stage) {
        if (accessor != null) assertTrue(stage, accessor.getStartedServers().isEmpty());
    }
    @Override protected void tearDown() throws Exception {
        var project = getProject();
        RunAll.runAll(
                () -> { if (manager != null && original != null) { for (var item : List.copyOf(manager.getAllSettings())) if (!original.contains(item)) manager.removeConfiguration(item); manager.setSelectedConfiguration(selected); } },
                () -> { if (document != null) FileDocumentManager.getInstance().saveDocument(document); },
                () -> { var editors = FileEditorManager.getInstance(project); for (var file : editors.getOpenFiles()) editors.closeFile(file); },
                // Assert before normal accessor shutdown: cleanup must never hide a leak.
                () -> assertNoLanguageServer("fixture cleanup must not retain language-server wrappers"),
                () -> { if (accessor != null) accessor.projectClosing(project); },
                () -> { if (root != null) PsiTestUtil.removeContentEntry(getModule(), root); },
                () -> { if (directory != null) FileUtil.delete(directory.toFile()); },
                () -> super.tearDown(),
                () -> { if (gopls != null) gopls.setEnabled(oldGoplsEnabled, project); });
    }
    public void testSavedCaretCreatesOneGuardedTemporaryBenchmarkWithoutPriorRun() throws Exception {
        var dispatch = new AtomicReference<RunnerAndConfigurationSettings>(); var background = new AtomicBoolean();
        var action = new GoDebugBenchmarkAtCaretAction((request, indicator) -> {
            background.set(!ApplicationManager.getApplication().isDispatchThread() && !ApplicationManager.getApplication().isReadAccessAllowed());
            assertEquals(TEXT.indexOf("42"), request.offset()); return prepared(request);
        }, settings -> { assertTrue(ApplicationManager.getApplication().isDispatchThread()); assertSame(settings, manager.getSelectedConfiguration()); dispatch.set(settings); }, stages::add);
        click(action); await(() -> dispatch.get() != null || dialog.get() != null); assertNull(dialog.get()); assertTrue(background.get());
        var settings = dispatch.get(); assertNotNull(settings); assertTrue(settings.isTemporary()); assertEquals(original.size() + 1, manager.getAllSettings().size());
        var config = (DAPRunConfiguration) settings.getConfiguration(); var launch = JsonParser.parseString(config.getLaunchConfiguration()).getAsJsonObject();
        assertEquals("test", launch.get("mode").getAsString());
        assertEquals(List.of("-test.run=^$", "-test.bench=^BenchmarkOne$", "-test.count=1", "-test.benchtime=1x"), launch.getAsJsonArray("args").asList().stream().map(value -> value.getAsString()).toList());
        var environment = ExecutionEnvironmentBuilder.create(getProject(), DefaultDebugExecutor.getDebugExecutorInstance(), config).build();
        GoSelectedTestDebugGuard.validate(environment);
        String previousTags = GoProjectSettings.getInstance(getProject()).buildTags;
        try {
            GoProjectSettings.getInstance(getProject()).buildTags = "changed-after-configuration";
            assertThrows(ExecutionException.class, () -> GoSelectedTestDebugGuard.validate(environment));
        } finally { GoProjectSettings.getInstance(getProject()).buildTags = previousTags; }
        edit(TEXT + "// changed\n");
        assertThrows(ExecutionException.class, () -> GoSelectedTestDebugGuard.validate(environment));
    }
    public void testUnsavedCaretRefusesBeforePreparationWithoutSaving() {
        var prepared = new AtomicInteger(); edit(TEXT + "// unsaved\n");
        click(new GoDebugBenchmarkAtCaretAction((request, indicator) -> { prepared.incrementAndGet(); return prepared(request); }, ignored -> fail("dispatched"), stages::add));
        assertEquals(0, prepared.get()); assertNotNull(dialog.get()); assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document)); assertUnregistered();
    }
    public void testSavedSourceChangedDuringPreparationRefusesRegistration() {
        click(new GoDebugBenchmarkAtCaretAction((request, indicator) -> {
            var result = prepared(request);
            ApplicationManager.getApplication().invokeAndWait(() -> { edit(TEXT + "// changed\n"); FileDocumentManager.getInstance().saveDocument(document); });
            return result;
        }, ignored -> fail("dispatched"), stages::add));
        await(() -> dialog.get() != null); assertUnregistered();
    }
    public void testSettingsChangedDuringPreparationRefuseRegistration() {
        String previous = GoProjectSettings.getInstance(getProject()).buildTags;
        try {
            click(new GoDebugBenchmarkAtCaretAction((request, indicator) -> {
                var result = prepared(request);
                ApplicationManager.getApplication().invokeAndWait(() -> GoProjectSettings.getInstance(getProject()).buildTags = "changed");
                return result;
            }, ignored -> fail("dispatched"), stages::add));
            await(() -> dialog.get() != null); assertUnregistered();
        } finally { GoProjectSettings.getInstance(getProject()).buildTags = previous; }
    }
    public void testVfsRenameDuringPreparationRefusesWithoutRegistration() {
        click(new GoDebugBenchmarkAtCaretAction((request, indicator) -> {
            var result = prepared(request);
            long oldStamp = request.file().getModificationStamp();
            ApplicationManager.getApplication().invokeAndWait(() -> WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                try { request.file().rename(this, "renamed_test.go"); }
                catch (java.io.IOException error) { throw new RuntimeException(error); }
            }));
            assertEquals(oldStamp, request.file().getModificationStamp());
            assertSame(request.document(), FileDocumentManager.getInstance().getDocument(request.file()));
            assertEquals(TEXT, request.document().getText());
            assertFalse(request.sourcePath().equals(request.file().getPath()));
            return result;
        }, ignored -> fail("dispatched"), stages::add));
        await(() -> dialog.get() != null); assertUnregistered();
    }
    public void testCreatedConfigurationRefusesVfsMoveBeforeLaunch() throws Exception {
        var dispatch = new AtomicReference<RunnerAndConfigurationSettings>();
        click(new GoDebugBenchmarkAtCaretAction(this::preparedForTest, dispatch::set, stages::add));
        await(() -> dispatch.get() != null || dialog.get() != null); assertNull(dialog.get()); assertNotNull(dispatch.get());
        var config = (DAPRunConfiguration) dispatch.get().getConfiguration();
        var environment = ExecutionEnvironmentBuilder.create(getProject(), DefaultDebugExecutor.getDebugExecutorInstance(), config).build();
        GoSelectedTestDebugGuard.validate(environment);
        VirtualFile file = FileDocumentManager.getInstance().getFile(document); assertNotNull(file);
        long oldStamp = file.getModificationStamp(); String oldPath = file.getPath();
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            try { file.move(this, root.createChildDirectory(this, "moved")); }
            catch (java.io.IOException error) { throw new RuntimeException(error); }
        });
        assertEquals(oldStamp, file.getModificationStamp()); assertSame(document, FileDocumentManager.getInstance().getDocument(file));
        assertEquals(TEXT, document.getText()); assertFalse(oldPath.equals(file.getPath()));
        assertThrows(ExecutionException.class, () -> GoSelectedTestDebugGuard.validate(environment));
    }
    public void testCreatedConfigurationRefusesOtherPackageSourceMove() throws Exception {
        Path helperPath = directory.resolve("helper_test.go"); Files.writeString(helperPath, "package p\n");
        VirtualFile helper = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(helperPath); assertNotNull(helper);
        Document helperDocument = FileDocumentManager.getInstance().getDocument(helper); assertNotNull(helperDocument);
        var dispatch = new AtomicReference<RunnerAndConfigurationSettings>();
        click(new GoDebugBenchmarkAtCaretAction(this::preparedForTest, dispatch::set, stages::add));
        await(() -> dispatch.get() != null || dialog.get() != null); assertNull(dialog.get()); assertNotNull(dispatch.get());
        var config = (DAPRunConfiguration) dispatch.get().getConfiguration();
        var environment = ExecutionEnvironmentBuilder.create(getProject(), DefaultDebugExecutor.getDebugExecutorInstance(), config).build();
        GoSelectedTestDebugGuard.validate(environment);
        long oldStamp = helper.getModificationStamp(); String oldPath = helper.getPath();
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            try { helper.move(this, root.createChildDirectory(this, "moved")); }
            catch (java.io.IOException error) { throw new RuntimeException(error); }
        });
        assertEquals(oldStamp, helper.getModificationStamp()); assertSame(helperDocument, FileDocumentManager.getInstance().getDocument(helper));
        assertEquals("package p\n", helperDocument.getText()); assertFalse(oldPath.equals(helper.getPath()));
        assertThrows(ExecutionException.class, () -> GoSelectedTestDebugGuard.validate(environment));
    }
    public void testCancellationAfterPreparationLeavesSelectionUnchanged() {
        click(new GoDebugBenchmarkAtCaretAction((request, indicator) -> { var result = prepared(request); indicator.cancel(); return result; }, ignored -> fail("dispatched"), stages::add));
        await(() -> stages.contains("cancelled") || dialog.get() != null); assertNull(dialog.get()); assertUnregistered();
    }
    public void testUnsupportedCaretRefusesWithNoPackageFallback() {
        myFixture.getEditor().getCaretModel().moveToOffset(0);
        click(new GoDebugBenchmarkAtCaretAction((request, indicator) -> prepared(request), ignored -> fail("dispatched"), stages::add));
        await(() -> dialog.get() != null); assertTrue(dialog.get(), dialog.get().contains("top-level Benchmark")); assertUnregistered();
    }
    public void testNoEditorDisablesActionAndDoesNotPrepare() {
        var action = new GoDebugBenchmarkAtCaretAction((request, indicator) -> { fail("prepared"); return null; }, ignored -> fail("dispatched"), stages::add);
        var event = AnActionEvent.createFromDataContext("benchmark-test", new Presentation(), SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject()).build());
        action.update(event); assertFalse(event.getPresentation().isEnabled()); action.actionPerformed(event); assertUnregistered();
    }
    private GoDebugBenchmarkAtCaretAction.Prepared prepared(GoDebugBenchmarkAtCaretAction.Request request) throws Exception {
        var declaration = new GoTestSourceIndex.Declaration("BenchmarkOne", "benchmark", TEXT.indexOf("func"), TEXT.indexOf("func TestOne"), TEXT.indexOf("BenchmarkOne"));
        var snapshot = new GoTestSourceIndex.Snapshot(directory.resolve("fixture_test.go"), "", TEXT, List.of(declaration));
        var selection = GoBenchmarkAtCaretPlan.select(snapshot, directory.resolve("fixture_test.go"), request.text(), request.offset());
        var snapshots = new ArrayList<>(List.of(snapshot));
        Path helper = directory.resolve("helper_test.go");
        if (Files.exists(helper)) snapshots.add(new GoTestSourceIndex.Snapshot(helper, "", Files.readString(helper), List.of()));
        var sources = Map.of("example.test/caret", new GoTestRunRegistry.PackageSources(directory, snapshots));
        GoBenchmarkAtCaretPlan.requireActiveSource(sources, snapshot, selection);
        Path canonicalSource = request.resolveSource();
        var guard = GoSelectedTestDebugGuard.capture(getProject(), sources).withAdditionalCheck(() -> request.validateSource(canonicalSource));
        var factory = ConfigurationTypeUtil.findConfigurationType(GoRunConfigurationType.class).getConfigurationFactories()[0];
        var source = new GoRunConfiguration(getProject(), factory, "BenchmarkOne"); source.setCommand(GoCommand.TEST);
        source.setDirectory(directory.toString()); source.setTarget("."); source.setTestSelection(selection);
        return new GoDebugBenchmarkAtCaretAction.Prepared(source, new GoDebugSelectedTestAction.Prepared(directory.toString(), GoBenchmarkAtCaretPlan.launch(directory.toString(), selection, List.of()), guard));
    }
    private GoDebugBenchmarkAtCaretAction.Prepared preparedForTest(GoDebugBenchmarkAtCaretAction.Request request,
            com.intellij.openapi.progress.ProgressIndicator indicator) throws Exception { return prepared(request); }
    private void click(GoDebugBenchmarkAtCaretAction action) {
        var data = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject()).add(CommonDataKeys.EDITOR, myFixture.getEditor()).build();
        var event = AnActionEvent.createFromDataContext("benchmark-test", new Presentation(), data); action.actionPerformed(event);
    }
    private void edit(String text) { WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(text)); }
    private void assertUnregistered() { assertEquals(original, manager.getAllSettings()); assertSame(selected, manager.getSelectedConfiguration()); }
    private static void await(BooleanSupplier condition) { PlatformTestUtil.waitWithEventsDispatching("Benchmark-at-caret action did not finish", condition, 15); }
}

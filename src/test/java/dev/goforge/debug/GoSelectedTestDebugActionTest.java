package dev.goforge.debug;

import com.google.gson.JsonParser;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.testframework.sm.runner.SMTestProxy;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.execution.ParametersListUtil;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import dev.goforge.run.GoCommand;
import dev.goforge.run.GoRunConfiguration;
import dev.goforge.run.GoRunConfigurationType;
import dev.goforge.testing.GoTestRunRegistry;
import dev.goforge.testing.GoTestConsoleProperties;
import dev.goforge.testing.GoTestContextSelection;
import dev.goforge.testing.GoTestEvents;
import dev.goforge.testing.GoTestSelection;
import dev.goforge.testing.GoTestSelector;
import dev.goforge.testing.GoTestSourceIndex;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Actual clicked action, background Task, real source guard and RunManager. No Go/Delve process is spawned. */
public final class GoSelectedTestDebugActionTest extends BasePlatformTestCase {
    private static final String TEXT = "package target\nimport \"testing\"\nfunc TestOne(t *testing.T) {}\nfunc BenchmarkOne(b *testing.B) {}\n";
    private Path directory;
    private VirtualFile contentRoot;
    private Document document;
    private RunManager manager;
    private List<RunnerAndConfigurationSettings> originalSettings;
    private RunnerAndConfigurationSettings originalSelection;
    private final AtomicReference<String> dialog = new AtomicReference<>();
    private final List<String> stages = new CopyOnWriteArrayList<>();

    @Override protected void setUp() throws Exception {
        super.setUp();
        Path base = Files.createDirectories(Path.of(getProject().getBasePath()));
        directory = Files.createTempDirectory(base, "selected-debug-action-").toRealPath();
        Files.writeString(directory.resolve("fixture_test.go"), TEXT);
        contentRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory);
        assertNotNull(contentRoot);
        PsiTestUtil.addContentRoot(getModule(), contentRoot);
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory.resolve("fixture_test.go"));
        assertNotNull(file);
        document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        manager = RunManager.getInstance(getProject());
        originalSettings = List.copyOf(manager.getAllSettings());
        originalSelection = manager.getSelectedConfiguration();
        TestDialogManager.setTestDialog(message -> { dialog.set(message); return Messages.OK; }, getTestRootDisposable());

    }

    @Override protected void tearDown() throws Exception {
        try {
            if (manager != null) {
                for (var settings : List.copyOf(manager.getAllSettings()))
                    if (!originalSettings.contains(settings)) manager.removeConfiguration(settings);
                manager.setSelectedConfiguration(originalSelection);
            }
            if (document != null) FileDocumentManager.getInstance().saveDocument(document);
            if (contentRoot != null) PsiTestUtil.removeContentEntry(getModule(), contentRoot);
            if (directory != null) FileUtil.delete(directory.toFile());
        } finally { super.tearDown(); }
    }

    public void testClickPreparesOffEdtThenRegistersAndSelectsGuardedTemporaryConfigurationBeforeDispatch() throws Exception {
        var source = configuration();
        source.setGoArguments("-short -parallel=3");
        source.getEnvironment().put("GOFORGE_ACTION_MARKER", "preserved");
        var preparedOffEdt = new AtomicBoolean();
        var dispatchedOnEdt = new AtomicBoolean();
        var dispatched = new AtomicReference<RunnerAndConfigurationSettings>();
        var selectedAtDispatch = new AtomicReference<RunnerAndConfigurationSettings>();
        var action = action(source, (config, indicator, progress) -> {
            preparedOffEdt.set(!ApplicationManager.getApplication().isDispatchThread()
                    && !ApplicationManager.getApplication().isReadAccessAllowed());
            return prepared(config);
        }, settings -> {
            dispatchedOnEdt.set(ApplicationManager.getApplication().isDispatchThread());
            selectedAtDispatch.set(manager.getSelectedConfiguration());
            dispatched.set(settings);
        });
        click(action);
        await(() -> dispatched.get() != null || dialog.get() != null);
        assertNull(dialog.get());
        assertTrue(preparedOffEdt.get());
        assertTrue(dispatchedOnEdt.get());
        var settings = dispatched.get();
        assertNotNull(settings);
        assertSame(settings, selectedAtDispatch.get());
        assertTrue(settings.isTemporary());
        assertTrue(manager.getAllSettings().contains(settings));
        assertEquals(originalSettings.size() + 1, manager.getAllSettings().size());
        var debug = (DAPRunConfiguration) settings.getConfiguration();
        var payload = JsonParser.parseString(debug.getLaunchConfiguration()).getAsJsonObject();
        assertEquals("test", payload.get("mode").getAsString());
        assertEquals(List.of("-test.short", "-test.parallel=3", "-test.run=^TestOne$/^dup#01$", "-test.count=1"),
                payload.getAsJsonArray("args").asList().stream().map(value -> value.getAsString()).toList());
        assertEquals("preserved", debug.getEnvData().getEnvs().get("GOFORGE_ACTION_MARKER"));
        assertEquals(List.of("TestOne/dup#01"), source.getTestSelection().testNames());
        assertEquals("-short -parallel=3", source.getGoArguments());
        var environment = ExecutionEnvironmentBuilder.create(getProject(), DefaultDebugExecutor.getDebugExecutorInstance(), debug).build();
        GoSelectedTestDebugGuard.validate(environment);
        edit(TEXT + "// changed after selected configuration was created\n");
        assertThrows(ExecutionException.class, () -> GoSelectedTestDebugGuard.validate(environment));
    }

    public void testCancelledPreparationReachesOnCancelAndCreatesNoConfigurationOrDispatch() {
        var dispatches = new AtomicInteger();
        var action = action(configuration(), (config, indicator, progress) -> {
            indicator.cancel();
            throw new CancellationException();
        }, settings -> dispatches.incrementAndGet());
        click(action);
        await(() -> stages.contains("onCancel"));
        assertUnlaunched(dispatches);
        assertNull(dialog.get());
    }

    public void testCancellationAfterPreparationResultStillPreventsOnSuccessLaunch() {
        var dispatches = new AtomicInteger();
        var action = action(configuration(), (config, indicator, progress) -> {
            var result = prepared(config);
            indicator.cancel();
            return result;
        }, settings -> dispatches.incrementAndGet());
        click(action);
        await(() -> stages.contains("onCancel"));
        assertUnlaunched(dispatches);
        assertNull(dialog.get());
    }

    public void testPreparationFailureReachesRealErrorDialogWithoutSelectingAConfiguration() {
        var dispatches = new AtomicInteger();
        var action = action(configuration(), (config, indicator, progress) -> {
            throw new ExecutionException("controlled selected-debug preparation failure");
        }, settings -> dispatches.incrementAndGet());
        click(action);
        await(() -> dialog.get() != null);
        assertTrue(dialog.get(), dialog.get().contains("controlled selected-debug preparation failure"));
        assertUnlaunched(dispatches);
    }

    public void testSourceEditAndSaveBetweenPreparationAndSuccessRefusesLaunch() throws Exception {
        var dispatches = new AtomicInteger();
        var writeSafeModality = ModalityState.current();
        var editComplete = new CompletableFuture<Void>();
        var action = action(configuration(), (config, indicator, progress) -> {
            var result = prepared(config);
            // Headless Task.queue pumps a nested modal loop. Schedule the real edit into it;
            // waiting until actionPerformed returns would deadlock the fixture.
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    edit(TEXT + "// changed during preparation\n");
                    FileDocumentManager.getInstance().saveDocument(document);
                    editComplete.complete(null);
                } catch (Throwable error) { editComplete.completeExceptionally(error); }
            }, writeSafeModality);
            editComplete.get(10, TimeUnit.SECONDS);
            return result;
        }, settings -> dispatches.incrementAndGet());
        click(action);
        await(() -> dialog.get() != null);
        assertTrue(dialog.get(), dialog.get().contains("selected test sources"));
        assertTrue(Files.readString(directory.resolve("fixture_test.go")).contains("changed during preparation"));
        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(document));
        assertUnlaunched(dispatches);
    }

    public void testDispatchFailureIsVisibleAfterTheRealTemporaryConfigurationIsSelected() {
        var attempts = new AtomicInteger();
        var selectedAtDispatch = new AtomicReference<RunnerAndConfigurationSettings>();
        var action = action(configuration(), (config, indicator, progress) -> prepared(config), settings -> {
            attempts.incrementAndGet();
            selectedAtDispatch.set(manager.getSelectedConfiguration());
            throw new IllegalStateException("controlled selected-debug dispatch failure");
        });
        click(action);
        await(() -> dialog.get() != null);
        assertEquals(1, attempts.get());
        assertTrue(dialog.get(), dialog.get().contains("controlled selected-debug dispatch failure"));
        assertNotNull(selectedAtDispatch.get());
        assertTrue(selectedAtDispatch.get().isTemporary());
        assertSame(selectedAtDispatch.get(), manager.getSelectedConfiguration());
        assertEquals(originalSettings.size() + 1, manager.getAllSettings().size());
    }


    public void testClickFromCompletedSelectedRerunClonePreservesSelectionAndConsumedCaretGuard() throws Exception {
        var original = configuration();
        original.setTestSelection(GoTestSelection.single("TestOne"));
        original.setGoArguments("-short -parallel=3");
        original.getEnvironment().put("GOFORGE_ACTION_MARKER", "rerun-preserved");
        var file = FileDocumentManager.getInstance().getFile(document);
        assertNotNull(file);
        GoTestContextSelection.bind(original, file, TEXT, document.getModificationStamp());
        GoTestContextSelection.started(original);
        var firstRegistry = new GoTestRunRegistry(getProject(), original, packageSources());
        var firstProperties = new GoTestConsoleProperties(original, DefaultRunExecutor.getRunExecutorInstance(), firstRegistry);
        Disposer.register(getTestRootDisposable(), firstProperties);
        var firstNode = completedNode(firstRegistry);
        var rerun = firstProperties.selectedConfiguration(firstNode);
        assertNotNull(rerun);
        assertNotSame(original, rerun);
        assertEquals(List.of("TestOne/dup#01"), rerun.getTestSelection().testNames());
        GoTestContextSelection.validateBeforeRun(rerun);

        // A second console is owned by the selected-rerun clone, not by the original caret run.
        var rerunRegistry = new GoTestRunRegistry(getProject(), rerun, packageSources());
        var rerunProperties = new GoTestConsoleProperties(rerun, DefaultRunExecutor.getRunExecutorInstance(), rerunRegistry);
        Disposer.register(getTestRootDisposable(), rerunProperties);
        var rerunNode = completedNode(rerunRegistry);
        assertNull(rerunProperties.selectedConfiguration(firstNode));
        var preparedSource = new AtomicReference<GoRunConfiguration>();
        var dispatched = new AtomicReference<RunnerAndConfigurationSettings>();
        var action = new GoDebugSelectedTestAction((config, indicator, progress) -> {
            GoTestContextSelection.validateBeforeRun(config);
            preparedSource.set(config);
            return prepared(config);
        }, dispatched::set, stages::add).withSelection(() -> rerunProperties.selectedConfiguration(rerunNode));
        click(action);
        await(() -> dispatched.get() != null || dialog.get() != null);
        assertNull(dialog.get());
        assertNotNull(dispatched.get());
        assertNotSame(rerun, preparedSource.get());
        assertEquals(List.of("TestOne/dup#01"), preparedSource.get().getTestSelection().testNames());
        assertEquals(".", preparedSource.get().getTarget());
        assertSame(dispatched.get(), manager.getSelectedConfiguration());
        var debug = (DAPRunConfiguration) dispatched.get().getConfiguration();
        assertEquals("rerun-preserved", debug.getEnvData().getEnvs().get("GOFORGE_ACTION_MARKER"));
        var payload = JsonParser.parseString(debug.getLaunchConfiguration()).getAsJsonObject();
        assertEquals(List.of("-test.short", "-test.parallel=3", "-test.run=^TestOne$/^dup#01$", "-test.count=1"),
                payload.getAsJsonArray("args").asList().stream().map(value -> value.getAsString()).toList());
        assertEquals(List.of("TestOne"), original.getTestSelection().testNames());
        assertEquals(List.of("TestOne/dup#01"), rerun.getTestSelection().testNames());
        assertTrue(stages.contains("launch_dispatched"));
    }

    public void testCompletedBenchmarkClickCreatesGuardedDapWithBothSelectorsAndTiming() throws Exception {
        var source=configuration();source.setTestSelection(GoTestSelection.benchmark("BenchmarkOne"));source.setGoArguments("-benchtime=1x -benchmem");
        var registry=new GoTestRunRegistry(getProject(),source,packageSources());
        var properties=new GoTestConsoleProperties(source,DefaultRunExecutor.getRunExecutorInstance(),registry);Disposer.register(getTestRootDisposable(),properties);
        var node=completedBenchmarkNode(registry);
        var rerun=properties.selectedConfiguration(node);assertNotNull(rerun);assertNotSame(source,rerun);
        assertEquals(GoCommand.TEST,rerun.getCommand());assertEquals(GoTestSelection.benchmark("BenchmarkOne"),rerun.getTestSelection());
        assertFalse(rerun.getTestSelection().rerun());
        assertEquals(List.of("-run=^$","-bench=^BenchmarkOne$"),GoTestSelector.arguments(rerun.getTestSelection()));
        assertEquals("-benchtime=1x -benchmem",rerun.getGoArguments());assertEquals(directory.toString(),rerun.getDirectory());assertEquals(".",rerun.getTarget());
        assertNull(registry.target(node.getLocationUrl()));assertTrue(registry.knownTargets().isEmpty());
        assertEquals(GoTestSelection.benchmark("BenchmarkOne"),source.getTestSelection());assertEquals("-benchtime=1x -benchmem",source.getGoArguments());
        var dispatched=new AtomicReference<RunnerAndConfigurationSettings>();
        var action=new GoDebugSelectedTestAction((config,indicator,progress)->{
            GoTestContextSelection.validateBeforeRun(rerun);
            assertEquals(GoTestSelection.benchmark("BenchmarkOne"),config.getTestSelection());return prepared(config);
        },dispatched::set,stages::add).withSelection(()->properties.selectedDebugConfiguration(node));
        click(action);await(()->dispatched.get()!=null||dialog.get()!=null);assertNull(dialog.get());assertNotNull(dispatched.get());
        var debug=(DAPRunConfiguration)dispatched.get().getConfiguration();var launch=JsonParser.parseString(debug.getLaunchConfiguration()).getAsJsonObject();
        assertEquals(List.of("-test.benchtime=1x","-test.benchmem","-test.run=^$","-test.bench=^BenchmarkOne$","-test.count=1"),launch.getAsJsonArray("args").asList().stream().map(v->v.getAsString()).toList());
        assertTrue(dispatched.get().isTemporary());assertSame(dispatched.get(),manager.getSelectedConfiguration());
        var environment=ExecutionEnvironmentBuilder.create(getProject(),DefaultDebugExecutor.getDebugExecutorInstance(),debug).build();GoSelectedTestDebugGuard.validate(environment);
        GoTestContextSelection.validateBeforeRun(rerun);
        edit(TEXT+"// benchmark changed after preparation\n");assertThrows(ExecutionException.class,()->GoSelectedTestDebugGuard.validate(environment));
        assertThrows(ExecutionException.class,()->GoTestContextSelection.validateBeforeRun(rerun));
        assertEquals(GoTestSelection.benchmark("BenchmarkOne"),source.getTestSelection());assertEquals("-benchtime=1x -benchmem",source.getGoArguments());
    }
    public void testBenchmarkEditDuringPreparationRefusesLaunchAndRegistration() throws Exception {
        var source=configuration();source.setTestSelection(GoTestSelection.benchmark("BenchmarkOne"));
        var dispatches=new AtomicInteger();var modality=ModalityState.current();var editComplete=new CompletableFuture<Void>();
        var action=action(source,(config,indicator,progress)->{
            var result=prepared(config);
            ApplicationManager.getApplication().invokeLater(()->{try{edit(TEXT+"// changed benchmark\n");FileDocumentManager.getInstance().saveDocument(document);editComplete.complete(null);}catch(Throwable e){editComplete.completeExceptionally(e);}},modality);
            editComplete.get(10,TimeUnit.SECONDS);return result;
        },settings->dispatches.incrementAndGet());
        click(action);await(()->dialog.get()!=null);assertTrue(dialog.get(),dialog.get().contains("selected test sources"));assertUnlaunched(dispatches);
    }
    private SMTestProxy completedBenchmarkNode(GoTestRunRegistry registry) {
        var events=new GoTestEvents(registry::locationHint);String nodeId=null;
        for(String action:List.of("run","bench"))for(var emission:events.accept(Map.of("Action",action,"Package","example.com/action","Test","BenchmarkOne"))){registry.observe(emission);if(emission.testName().equals("BenchmarkOne"))nodeId=emission.nodeId();}
        registry.markTerminated();assertNotNull(nodeId);
        String url=registry.locationHint("example.com/action","BenchmarkOne",nodeId,false);
        var target=registry.debugTarget(url);assertNotNull(target);assertEquals(GoTestEvents.Status.BENCHMARK_COMPLETED,target.status());
        return new SMTestProxy("example.com/action::BenchmarkOne",false,url);
    }
    private SMTestProxy completedNode(GoTestRunRegistry registry) {
        var events = new GoTestEvents(registry::locationHint);
        for (var action : List.of("run", "pass")) {
            for (var emission : events.accept(Map.of("Action", action, "Package", "example.com/action", "Test", "TestOne/dup#01")))
                registry.observe(emission);
        }
        registry.markTerminated();
        var target = registry.knownTargets().stream().filter(value -> value.fullName().equals("TestOne/dup#01")).findFirst().orElseThrow();
        assertEquals(GoTestEvents.Status.PASSED, target.status());
        return new SMTestProxy("example.com/action::TestOne/dup#01", false, target.locationUrl());
    }

    private GoDebugSelectedTestAction action(GoRunConfiguration configuration,
            GoDebugSelectedTestAction.Preparation preparation, Consumer<RunnerAndConfigurationSettings> launcher) {
        // Match the mounted toolbar: a per-console supplier, with no SM tree keys in the event.
        return new GoDebugSelectedTestAction(preparation, launcher, stages::add).withSelection(() -> configuration);
    }

    private void click(GoDebugSelectedTestAction action) {
        var context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject()).build();
        var event = AnActionEvent.createFromDataContext("GoForgeDebugActionTest", new Presentation(), context);
        action.update(event);
        assertTrue(event.getPresentation().getDescription(), event.getPresentation().isEnabled());
        action.actionPerformed(event);
    }

    private GoDebugSelectedTestAction.Prepared prepared(GoRunConfiguration configuration) throws Exception {
        var guard = GoSelectedTestDebugGuard.capture(getProject(), packageSources());
        var launch = GoSelectedTestDebugPlan.launch(directory.toString(), configuration.getTestSelection(),
                ParametersListUtil.parse(configuration.getGoArguments()));
        return new GoDebugSelectedTestAction.Prepared(directory.toString(), launch, guard);
    }

    private Map<String, GoTestRunRegistry.PackageSources> packageSources() {
        var declaration = new GoTestSourceIndex.Declaration("TestOne", "test", TEXT.indexOf("func "),
                TEXT.indexOf('}', TEXT.indexOf("func ")) + 1, TEXT.indexOf("TestOne"));
        var snapshot = new GoTestSourceIndex.Snapshot(directory.resolve("fixture_test.go"),
                GoTestSourceIndex.sha256(TEXT.getBytes(StandardCharsets.UTF_8)), TEXT, List.of(declaration,
                    new GoTestSourceIndex.Declaration("BenchmarkOne", "benchmark", TEXT.indexOf("func BenchmarkOne"), TEXT.length(), TEXT.indexOf("BenchmarkOne"))));
        return Map.of("example.com/action", new GoTestRunRegistry.PackageSources(directory, List.of(snapshot)));
    }

    private GoRunConfiguration configuration() {
        var factory = ConfigurationTypeUtil.findConfigurationType(GoRunConfigurationType.class).getConfigurationFactories()[0];
        var configuration = new GoRunConfiguration(getProject(), factory, "selected action test");
        configuration.setCommand(GoCommand.TEST);
        configuration.setDirectory(directory.toString());
        configuration.setTarget(".");
        configuration.setTestSelection(GoTestSelection.rerun(List.of("TestOne/dup#01")));
        return configuration;
    }

    private void edit(String text) {
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(text));
    }

    private void assertUnlaunched(AtomicInteger dispatches) {
        assertEquals(0, dispatches.get());
        assertEquals(originalSettings, manager.getAllSettings());
        assertSame(originalSelection, manager.getSelectedConfiguration());
    }

    private static void await(BooleanSupplier finished) {
        PlatformTestUtil.waitWithEventsDispatching("Selected debug action did not reach a terminal callback", finished, 15);
    }
}

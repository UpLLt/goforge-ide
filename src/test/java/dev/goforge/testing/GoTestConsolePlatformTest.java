package dev.goforge.testing;

import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.WrappingRunConfiguration;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.testframework.AbstractTestProxy;
import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.execution.testframework.TestTreeView;
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil;
import com.intellij.execution.testframework.sm.runner.SMTestProxy;
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.goforge.run.GoCommand;
import dev.goforge.run.GoRunConfiguration;
import dev.goforge.run.GoRunConfigurationType;
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

public final class GoTestConsolePlatformTest extends BasePlatformTestCase {
    private Path directory;
    private VirtualFile root;
    private PropertiesComponent testSettings;
    private String originalHidePassed;
    private static final String HIDE_PASSED_KEY = "GoForgeSupport.hidePassedTests";
    private static final String SOURCE = "package target\nimport \"testing\"\nfunc TestOne(t *testing.T) {}\nfunc TestTwo(t *testing.T) {}\n";
    @Override protected void setUp() throws Exception {
        super.setUp();
        testSettings = PropertiesComponent.getInstance();
        originalHidePassed = testSettings.getValue(HIDE_PASSED_KEY);
        Path projectBase = Path.of(getProject().getBasePath());
        Files.createDirectories(projectBase);
        assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectBase));
        directory = Files.createTempDirectory(projectBase, "test-target-console-").toRealPath();
        root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory);
        assertNotNull(root); PsiTestUtil.addContentRoot(getModule(), root);
    }
    @Override protected void tearDown() throws Exception {
        try { if (root != null) PsiTestUtil.removeContentEntry(getModule(), root); if (directory != null) FileUtil.delete(directory.toFile()); }
        finally {
            try { super.tearDown(); }
            finally { if (testSettings != null) testSettings.setValue(HIDE_PASSED_KEY, originalHidePassed); }
        }
    }
    public void testRealServiceMessagesCreateLocatedProxiesAndSelectedActionClonesObservedSubtree() throws Exception {
        var captured = new AtomicReference<GoRunConfiguration>(); var session = session(null, captured);
        session.send("start", "example.com/target", "");
        session.send("run", "example.com/target", "TestOne");
        session.send("run", "example.com/target", "TestOne/a+b/dup#01");
        session.send("pass", "example.com/target", "TestOne/a+b/dup#01");
        session.send("pass", "example.com/target", "TestOne"); session.send("pass", "example.com/target", "");
        session.finish(0);
        SMTestProxy child = session.node("TestOne/a+b/dup#01");
        assertNotNull(child.getLocationUrl()); assertTrue(child.getLocationUrl().startsWith("goforge-test://"));
        var location = child.getLocation(getProject(), GlobalSearchScope.projectScope(getProject()));
        assertNotNull(location); assertEquals(SOURCE.indexOf("TestOne"), location.getPsiElement().getTextOffset());
        var event = session.event(child); var selected = session.result.getActions()[0];
        selected.update(event); assertTrue(event.getPresentation().isEnabled()); selected.actionPerformed(event);
        assertNotNull(captured.get()); assertEquals(List.of("TestOne/a+b/dup#01"), captured.get().getTestSelection().testNames());
        assertTrue(captured.get().getTestSelection().rerun()); assertEquals(".", captured.get().getTarget());
        assertNull(session.configuration.getTestSelection()); assertEquals("./...", session.configuration.getTarget());
        var sourceAction = session.result.getActions()[1]; sourceAction.update(event);
        assertTrue(event.getPresentation().isEnabled());
        assertEquals("Go to containing test declaration", event.getPresentation().getText());
        session.result.getActions()[2].update(event); assertTrue(event.getPresentation().isEnabled());
    }
    public void testUnboundContextActionStillAcceptsADataContextSelectionSnapshot() throws Exception {
        var session = session(null, new AtomicReference<>());
        session.send("start", "example.com/target", ""); session.send("run", "example.com/target", "TestOne");
        session.send("pass", "example.com/target", "TestOne"); session.send("pass", "example.com/target", ""); session.finish(0);
        var node = session.node("TestOne");
        var data = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject())
                .add(TestTreeView.MODEL_DATA_KEY, session.console.getResultsViewer())
                .add(AbstractTestProxy.DATA_KEYS, new AbstractTestProxy[]{node}).build();
        var event = AnActionEvent.createFromDataContext("GoForgeTest", new Presentation(), data);
        assertNotNull(GoTestConsoleProperties.selectedConfiguration(event));
        var absent = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject())
                .add(TestTreeView.MODEL_DATA_KEY, session.console.getResultsViewer()).build();
        assertNull(GoTestConsoleProperties.selectedConfiguration(AnActionEvent.createFromDataContext("GoForgeTest", new Presentation(), absent)));
    }
    public void testMountedActionsUseRealTreeSelectionWithNoTreeDataKeysOnBackgroundThread() throws Exception {
        var captured = new AtomicReference<GoRunConfiguration>(); var session = session(null, captured);
        session.send("start", "example.com/target", ""); session.send("run", "example.com/target", "TestOne");
        session.send("run", "example.com/target", "TestOne/dup"); session.send("pass", "example.com/target", "TestOne/dup");
        session.send("run", "example.com/target", "TestOne/dup#01"); session.send("pass", "example.com/target", "TestOne/dup#01");
        session.send("pass", "example.com/target", "TestOne"); session.send("pass", "example.com/target", ""); session.finish(0);
        session.select(session.node("TestOne/dup#01"));
        assertEquals("TestOne/dup#01", session.properties.selectedConfiguration().getTestSelection().testNames().getFirst());
        for (var action : session.result.getActions()) {
            var event = session.toolbarEvent();
            assertNull(event.getData(TestTreeView.MODEL_DATA_KEY));
            assertNull(event.getData(AbstractTestProxy.DATA_KEY)); assertNull(event.getData(AbstractTestProxy.DATA_KEYS));
            ApplicationManager.getApplication().executeOnPooledThread(() -> action.update(event)).get(10, TimeUnit.SECONDS);
            assertTrue(action.getTemplatePresentation().getText(), event.getPresentation().isEnabled());
        }
        session.result.getActions()[0].actionPerformed(session.toolbarEvent());
        assertEquals(List.of("TestOne/dup#01"), captured.get().getTestSelection().testNames());
        assertEquals(List.of("-run=^TestOne$/^dup#01$", "-count=1"),
                GoTestSelector.arguments(captured.get().getTestSelection()));
    }
    public void testMountedActionsClearOnEmptyMultipleAndUnmappedTreeSelection() throws Exception {
        var captured = new AtomicReference<GoRunConfiguration>(); var session = session(null, captured);
        session.send("start", "example.com/target", "");
        for (String name : List.of("TestOne", "TestTwo")) {
            session.send("run", "example.com/target", name); session.send("pass", "example.com/target", name);
        }
        session.send("fail", "example.com/target", ""); session.finish(1);
        var first = session.select(session.node("TestOne"));
        var second = session.select(session.node("TestTwo"));
        var tree = session.console.getResultsViewer().getTreeView();
        // SM defaults to single selection. Exercise the guard for a tree using
        // a supported multi-selection mode without changing the product default.
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.setSelectionPaths(new TreePath[]{first, second});
        assertEquals(2, tree.getSelectionCount()); assertMountedActionsDisabled(session);
        tree.clearSelection(); assertMountedActionsDisabled(session);
        var synthetic = session.console.getResultsViewer().getTestsRootNode().getAllTests().stream()
                .filter(node -> node.isLeaf() && node.isDefect() && node.getLocationUrl() == null).findFirst().orElseThrow();
        session.select(synthetic); assertMountedActionsDisabled(session);
        session.select(session.console.getResultsViewer().getTestsRootNode()); assertMountedActionsDisabled(session);
        session.result.getActions()[0].actionPerformed(session.toolbarEvent()); assertNull(captured.get());
        session.select(session.node("TestOne"));
        session.result.getActions()[0].actionPerformed(session.toolbarEvent());
        assertEquals(List.of("TestOne"), captured.get().getTestSelection().testNames());
    }
    public void testMountedSelectionIsPerConsoleAndExpiresWhenConsoleIsDisposed() throws Exception {
        var firstCapture = new AtomicReference<GoRunConfiguration>(); var secondCapture = new AtomicReference<GoRunConfiguration>();
        var first = session(null, firstCapture); var second = session(null, secondCapture);
        for (var session : List.of(first, second)) {
            session.send("start", "example.com/target", "");
            for (String name : List.of("TestOne", "TestTwo")) {
                session.send("run", "example.com/target", name); session.send("pass", "example.com/target", name);
            }
            session.send("pass", "example.com/target", ""); session.finish(0);
        }
        first.select(first.node("TestOne")); second.select(second.node("TestTwo"));
        first.result.getActions()[0].actionPerformed(second.toolbarEvent());
        second.result.getActions()[0].actionPerformed(first.toolbarEvent());
        assertEquals(List.of("TestOne"), firstCapture.get().getTestSelection().testNames());
        assertEquals(List.of("TestTwo"), secondCapture.get().getTestSelection().testNames());
        Disposer.dispose(first.console); firstCapture.set(null);
        assertMountedActionsDisabled(first);
        first.result.getActions()[0].actionPerformed(first.toolbarEvent()); assertNull(firstCapture.get());
        var event = second.toolbarEvent(); second.result.getActions()[0].update(event); assertTrue(event.getPresentation().isEnabled());
    }
    private void assertMountedActionsDisabled(Session session) throws Exception {
        for (var action : session.result.getActions()) {
            var event = session.toolbarEvent();
            ApplicationManager.getApplication().executeOnPooledThread(() -> action.update(event)).get(10, TimeUnit.SECONDS);
            assertFalse(action.getTemplatePresentation().getText(), event.getPresentation().isEnabled());
        }
        assertNull(session.properties.selectedConfiguration());
    }
    public void testCrossDirectorySelectionPreservesRelativeModfileAndSerializedGoFlags() throws Exception {
        Path origin = directory.getParent();
        var captured = new AtomicReference<GoRunConfiguration>();
        var session = session(null, captured, origin, "-modfile=explicit.mod -short", "-modfile=from_goenv.mod -pgo=off");
        session.send("start", "example.com/target", ""); session.send("run", "example.com/target", "TestOne");
        session.send("pass", "example.com/target", "TestOne"); session.send("pass", "example.com/target", ""); session.finish(0);
        var event = session.event(session.node("TestOne")); session.result.getActions()[0].actionPerformed(event);
        var selected = captured.get(); assertNotNull(selected);
        assertEquals(directory.toString(), selected.getDirectory());
        assertEquals(List.of("-modfile=" + origin.resolve("explicit.mod"), "-short"), ParametersListUtil.parse(selected.getGoArguments()));
        assertEquals(List.of("-modfile=" + origin.resolve("from_goenv.mod"), "-pgo=off"), GoTestSelector.splitGoFlags(selected.getEnvironment().get("GOFLAGS")));
        assertEquals("-modfile=explicit.mod -short", session.configuration.getGoArguments());
        assertFalse(session.configuration.getEnvironment().containsKey("GOFLAGS"));
        var xml = new org.jdom.Element("configuration"); selected.writeExternal(xml);
        var restored = session.configuration.clone(); restored.readExternal(xml);
        assertEquals(selected.getGoArguments(), restored.getGoArguments());
        assertEquals(selected.getEnvironment(), restored.getEnvironment());
        assertEquals(selected.getTestSelection(), restored.getTestSelection());
    }
    public void testCrossDirectoryUnsupportedCommandsDisableActionsWithTheSpecificReason() throws Exception {
        var session = session(null, new AtomicReference<>(), directory.getParent(), "-toolexec=./wrapper", "");
        session.send("start", "example.com/target", ""); session.send("run", "example.com/target", "TestOne");
        session.send("fail", "example.com/target", "TestOne"); session.send("fail", "example.com/target", ""); session.finish(1);
        var event = session.event(session.node("TestOne"));
        for (var action : List.of(session.result.getActions()[0], session.result.getRestartActions()[0], session.result.getActions()[2])) {
            action.update(event); assertFalse(event.getPresentation().isEnabled());
            assertTrue(event.getPresentation().getDescription().contains("-toolexec"));
        }
    }
    public void testRebasedExplicitArgumentsUsePlatformQuotingForLiteralPaths() {
        // Windows forbids double quotes in filenames; backslashes there separate
        // path components. Keep path ownership and arbitrary argv quoting distinct.
        String relative=dev.goforge.tools.HostTools.isWindows()
                ? "中文 folder/a'b space.mod" : "中文 folder/a'b\"c\\literal.mod";
        var rewritten = GoTestRerunArguments.rebase(new GoTestRerunArguments.Context(directory.getParent(), ""), directory,
                List.of("-modfile", relative, "-pkgdir=cache with space\\folder"));
        assertEquals("-modfile="+directory.getParent().resolve(relative),rewritten.arguments().getFirst());
        String encoded = ParametersListUtil.join(rewritten.arguments());
        assertEquals(rewritten.arguments(), ParametersListUtil.parse(encoded));
        var literalArguments=List.of("-custom=中文 a'b\"c\\literal", "C:\\space folder\\trailing\\", "");
        assertEquals(literalArguments,ParametersListUtil.parse(ParametersListUtil.join(literalArguments)));
    }
    public void testMountedFailedRestartUsesRealFailedProxiesAndDistinctTopLevelNames() throws Exception {
        var session = session(null, new AtomicReference<>());
        session.send("start", "example.com/target", ""); session.send("run", "example.com/target", "TestOne");
        session.send("run", "example.com/target", "TestOne/bad"); session.send("fail", "example.com/target", "TestOne/bad");
        session.send("fail", "example.com/target", "TestOne"); session.send("run", "example.com/target", "TestTwo");
        session.send("pass", "example.com/target", "TestTwo"); session.send("fail", "example.com/target", ""); session.finish(1);
        assertFalse(session.console.getResultsViewer().getTestsRootNode().getAllTests().stream()
                .anyMatch(node -> node.isLeaf() && node.isDefect() && node.getLocationUrl() == null));
        assertEquals(1, session.result.getRestartActions().length);
        var action = (GoTestConsoleProperties.GoRerunFailedTestsAction) session.result.getRestartActions()[0];
        var event = session.event(session.node("TestOne/bad")); action.update(event); assertTrue(event.getPresentation().isEnabled());
        var environment = ExecutionEnvironmentBuilder.create(getProject(), DefaultRunExecutor.getRunExecutorInstance(), session.configuration).build();
        var profile = (WrappingRunConfiguration<?>) action.getRunProfile(environment);
        assertNotNull(profile); var rerun = (GoRunConfiguration) profile.getPeer();
        assertEquals(List.of("TestOne"), rerun.getTestSelection().testNames()); assertTrue(rerun.getTestSelection().rerun());
        assertNull(session.configuration.getTestSelection());
    }
    public void testRunningSuiteAndInfrastructureFailureCannotBecomeSelectedOrFailedTargets() throws Exception {
        var session = session(null, new AtomicReference<>());
        session.send("start", "example.com/target", ""); session.send("run", "example.com/target", "TestOne");
        PlatformTestUtil.waitWithEventsDispatching("test start", () -> session.find("TestOne") != null, 5);
        assertNull(session.properties.selectedConfiguration(session.find("TestOne")));
        session.send("pass", "example.com/target", "TestOne"); session.send("fail", "example.com/target", ""); session.finish(1);
        var synthetic = session.console.getResultsViewer().getTestsRootNode().getAllTests().stream()
            .filter(node -> node.getLocationUrl() == null && node.isDefect() && !node.isSuite()).findFirst().orElseThrow();
        assertNull(session.properties.selectedConfiguration(synthetic));
        var action = session.result.getRestartActions()[0]; var event = session.event(synthetic); action.update(event);
        assertFalse(event.getPresentation().isEnabled());
    }
    public void testRequestedMissingSubtestProducesRealFailedSmNodeDespitePackagePass() throws Exception {
        var session = session(GoTestSelection.single("TestOne/missing"), new AtomicReference<>());
        session.send("start", "example.com/target", ""); session.send("run", "example.com/target", "TestOne");
        session.send("pass", "example.com/target", "TestOne"); session.send("pass", "example.com/target", ""); session.finish(0);
        assertEquals(List.of("TestOne/missing"), session.registry.missingRequestedTests());
        assertTrue(session.console.getResultsViewer().getTestsRootNode().isDefect());
        assertTrue(session.console.getResultsViewer().getTestsRootNode().getAllTests().stream()
                .anyMatch(test -> test.getErrorMessage() != null && test.getErrorMessage().contains("not observed")));
    }
    public void testFailedRerunDoesNotSilentlyDropAnUnmappedSecondPackage() throws Exception {
        var session = session(null, new AtomicReference<>());
        for (String pkg : List.of("example.com/target", "example.com/unmapped")) {
            session.send("start", pkg, ""); session.send("run", pkg, "TestOne");
            session.send("fail", pkg, "TestOne"); session.send("fail", pkg, "");
        }
        session.finish(1);
        var event = session.event(session.node("TestOne")); session.result.getRestartActions()[0].update(event);
        assertFalse(event.getPresentation().isEnabled());
    }

    public void testFailedRerunDoesNotDropAnotherPackagesCompileFailure() throws Exception {
        var session = session(null, new AtomicReference<>());
        session.send("start", "example.com/target", ""); session.send("run", "example.com/target", "TestOne");
        session.send("fail", "example.com/target", "TestOne"); session.send("fail", "example.com/target", "");
        session.emit(session.events.accept(Map.of("Action", "build-fail", "ImportPath", "example.com/unbuilt")));
        session.emit(session.events.accept(Map.of("Action", "fail", "Package", "example.com/unbuilt", "FailedBuild", "example.com/unbuilt")));
        session.finish(1);
        assertUnmappedFailureBlocksFailedRerun(session);
    }

    public void testFailedRerunDoesNotDropAnotherPackagesTestMainFailure() throws Exception {
        var session = session(null, new AtomicReference<>());
        session.send("start", "example.com/target", ""); session.send("run", "example.com/target", "TestOne");
        session.send("fail", "example.com/target", "TestOne"); session.send("fail", "example.com/target", "");
        session.send("start", "example.com/testmain", ""); session.send("fail", "example.com/testmain", "");
        session.finish(1);
        assertUnmappedFailureBlocksFailedRerun(session);
    }

    public void testInterruptedPackageWithUnknownProcessFailureDoesNotOfferPartialFailedRerun() throws Exception {
        var session = session(null, new AtomicReference<>());
        session.send("start", "example.com/target", ""); session.send("run", "example.com/target", "TestOne");
        session.finish(130);
        assertUnmappedFailureBlocksFailedRerun(session);
    }

    private void assertUnmappedFailureBlocksFailedRerun(Session session) throws Exception {
        var node = session.node("TestOne");
        PlatformTestUtil.waitWithEventsDispatching("unmapped failed SM leaf", () ->
                session.console.getResultsViewer().getTestsRootNode().getAllTests().stream()
                        .anyMatch(test -> test.isLeaf() && test.isDefect() && test.getLocationUrl() == null), 5);
        var action = (GoTestConsoleProperties.GoRerunFailedTestsAction) session.result.getRestartActions()[0];
        var event = session.event(node); action.update(event);
        assertFalse(event.getPresentation().isEnabled());
        var environment = ExecutionEnvironmentBuilder.create(getProject(), DefaultRunExecutor.getRunExecutorInstance(), session.configuration).build();
        assertNull(action.getRunProfile(environment));
    }

    public void testLocatorRejectsChangedForgedAndExpiredHints() throws Exception {
        var session = session(null, new AtomicReference<>());
        session.send("start", "example.com/target", ""); session.send("run", "example.com/target", "TestOne");
        session.send("pass", "example.com/target", "TestOne"); session.send("pass", "example.com/target", ""); session.finish(0);
        var node = session.node("TestOne"); String hint = node.getLocationUrl().substring("goforge-test://".length());
        var locator = session.properties.getTestLocator(); var scope = GlobalSearchScope.projectScope(getProject());
        assertEquals(1, locator.getLocation("goforge-test", hint, getProject(), scope).size());
        assertTrue(locator.getLocation("file", directory.resolve("fixture_test.go").toString(), getProject(), scope).isEmpty());
        assertTrue(locator.getLocation("goforge-test", hint + "forged", getProject(), scope).isEmpty());
        var document = session.registry.cachedDocument(directory.resolve("fixture_test.go"));
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(0, "// changed\n"));
        PsiDocumentManager.getInstance(getProject()).commitDocument(document);
        assertTrue(locator.getLocation("goforge-test", hint, getProject(), scope).isEmpty());
        session.registry.close(); assertTrue(locator.getLocation("goforge-test", hint, getProject(), scope).isEmpty());
    }
    public void testUnsupportedAndCancelledSelectionRemainVisibleWithoutInventingPasses() throws Exception {
        var session = session(GoTestSelection.single("TestOne"), new AtomicReference<>());
        session.send("start", "example.com/target", ""); session.send("run", "example.com/target", "TestOne");
        session.finish(130);
        assertTrue(session.console.getResultsViewer().getTestsRootNode().isDefect());
        assertEquals(GoTestEvents.Status.INCOMPLETE, session.registry.knownTargets().getFirst().status());
        assertTrue(session.registry.missingRequestedTests().isEmpty());
        assertFalse(GoTestRunRegistry.ordinaryTestName("BenchmarkOne/sub")); assertFalse(GoTestRunRegistry.ordinaryTestName("FuzzOne/seed"));
    }
    private Session session(GoTestSelection selection, AtomicReference<GoRunConfiguration> captured) throws Exception {
        return session(selection, captured, directory, "", "");
    }
    private Session session(GoTestSelection selection, AtomicReference<GoRunConfiguration> captured,
                            Path workingDirectory, String arguments, String actualGoFlags) throws Exception {
        Path file = directory.resolve("fixture_test.go"); Files.writeString(file, SOURCE);
        VirtualFile virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file); assertNotNull(virtual);
        FileDocumentManager.getInstance().getDocument(virtual);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        var snapshot = new GoTestSourceIndex.Snapshot(file, "fixture", SOURCE, List.of(
            new GoTestSourceIndex.Declaration("TestOne", "test", SOURCE.indexOf("func TestOne"), SOURCE.indexOf("func TestTwo"), SOURCE.indexOf("TestOne")),
            new GoTestSourceIndex.Declaration("TestTwo", "test", SOURCE.indexOf("func TestTwo"), SOURCE.length(), SOURCE.indexOf("TestTwo"))));
        var factory = ConfigurationTypeUtil.findConfigurationType(GoRunConfigurationType.class).getConfigurationFactories()[0];
        var configuration = new GoRunConfiguration(getProject(), factory, "package fixture");
        configuration.setCommand(GoCommand.TEST); configuration.setDirectory(workingDirectory.toString()); configuration.setGoArguments(arguments);
        configuration.setTarget(selection == null ? "./..." : "."); configuration.setTestSelection(selection);
        var registry = new GoTestRunRegistry(getProject(), configuration, Map.of("example.com/target", new GoTestRunRegistry.PackageSources(directory, List.of(snapshot))),
                new GoTestRerunArguments.Context(workingDirectory, actualGoFlags));
        var properties = new GoTestConsoleProperties(configuration, DefaultRunExecutor.getRunExecutorInstance(), registry, captured::set);
        // A passed proxy remains in getAllTests() even when the real JTree filters
        // it out. Explicitly expose passed nodes for actual selection assertions.
        TestConsoleProperties.HIDE_PASSED_TESTS.set(properties, false);
        var handler = new ReplayHandler();
        var console = (SMTRunnerConsoleView) SMTestRunnerConnectionUtil.createAndAttachConsole("GoForge", handler, properties);
        console.getComponent(); Disposer.register(getTestRootDisposable(), console);
        var result = new DefaultExecutionResult(console, handler);
        properties.mount(console, result, new dev.goforge.debug.GoDebugSelectedTestAction());
        handler.startNotify();
        return new Session(configuration, registry, properties, console, handler, result);
    }
    private final class Session {
        final GoRunConfiguration configuration; final GoTestRunRegistry registry; final GoTestConsoleProperties properties;
        final SMTRunnerConsoleView console; final ReplayHandler handler; final DefaultExecutionResult result; final GoTestEvents events;
        Session(GoRunConfiguration configuration, GoTestRunRegistry registry, GoTestConsoleProperties properties, SMTRunnerConsoleView console, ReplayHandler handler, DefaultExecutionResult result) {
            this.configuration = configuration; this.registry = registry; this.properties = properties; this.console = console; this.handler = handler; this.result = result;
            events = new GoTestEvents(registry::locationHint);
        }
        void send(String action, String pkg, String test) { emit(events.accept(Map.of("Action", action, "Package", pkg, "Test", test))); }
        void emit(List<GoTestEvents.Emission> values) {
            for (var event : values) { registry.observe(event); if (!event.teamCityMessage().isBlank()) handler.send(event.teamCityMessage() + "\n"); }
        }
        void finish(int code) {
            emit(events.selectedTestsNotObserved(registry.missingRequestedTests())); emit(events.finishProcess(code)); registry.markTerminated(); handler.finish(code);
            PlatformTestUtil.waitWithEventsDispatching("SM tree finish", () -> !console.getResultsViewer().getTestsRootNode().isInProgress(), 5);
        }
        SMTestProxy find(String name) { return console.getResultsViewer().getTestsRootNode().getAllTests().stream().filter(node -> node.getName().equals("example.com/target::" + name)).findFirst().orElse(null); }
        SMTestProxy node(String name) { PlatformTestUtil.waitWithEventsDispatching("SM node", () -> find(name) != null, 5); return find(name); }
        AnActionEvent event(AbstractTestProxy node) {
            select(node);
            return toolbarEvent();
        }
        TreePath select(AbstractTestProxy node) {
            console.getResultsViewer().selectAndNotify(node);
            PlatformTestUtil.waitWithEventsDispatching("real SM tree selection", () ->
                    console.getResultsViewer().getTreeView().getSelectedTest() == node, 5);
            return console.getResultsViewer().getTreeView().getSelectionPath();
        }
        AnActionEvent toolbarEvent() {
            var data = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject()).build();
            return AnActionEvent.createFromDataContext("GoForgeTest", new Presentation(), data);
        }
    }
    private static final class ReplayHandler extends ProcessHandler {
        void send(String text) { notifyTextAvailable(text, ProcessOutputTypes.STDOUT); }
        void finish(int code) { notifyProcessTerminated(code); }
        @Override protected void destroyProcessImpl() { notifyProcessTerminated(130); }
        @Override protected void detachProcessImpl() { notifyProcessDetached(); }
        @Override public boolean detachIsDefault() { return false; }
        @Override public @Nullable OutputStream getProcessInput() { return null; }
    }
}

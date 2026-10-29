package dev.goforge.testing;

import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil;
import com.intellij.execution.testframework.sm.runner.SMTestProxy;
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.goforge.run.GoCommand;
import dev.goforge.run.GoRunConfiguration;
import dev.goforge.run.GoRunConfigurationType;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real OS handler stop + actual SM conversion, with deterministic JSON fixture output. */
public final class GoTestTerminationPlatformTest extends BasePlatformTestCase {
    private final List<GoTestProcessHandler> processes = new ArrayList<>();
    private Path directory;
    private VirtualFile root;
    private static final List<String> NAMES = List.of("TestPass", "TestFail", "TestSkip", "TestSlow", "TestLater");

    @Override protected void setUp() throws Exception {
        super.setUp();
        Path base = Path.of(getProject().getBasePath()); Files.createDirectories(base);
        assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base));
        directory = Files.createTempDirectory(base, "test-termination-").toRealPath();
        root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory); assertNotNull(root);
        PsiTestUtil.addContentRoot(getModule(), root);
    }
    @Override protected void tearDown() throws Exception {
        try {
            for (var handler : processes) {
                if (!handler.isStartNotified()) handler.startNotify();
                if (handler.getProcess().isAlive()) handler.killProcess();
                PlatformTestUtil.waitWithEventsDispatching("fixture process cleanup",
                        () -> !handler.getProcess().isAlive() && handler.isProcessTerminated(), 10);
            }
            if (root != null) PsiTestUtil.removeContentEntry(getModule(), root);
            if (directory != null) FileUtil.delete(directory.toFile());
        } finally { super.tearDown(); }
    }

    public void testActualStopMarksOnlyOpenNodesInterruptedAndRetainsRealFailures() throws Exception {
        var session = start("mixed", null); session.awaitReady();
        session.node("TestSlow/child");
        session.handler.destroyProcess(); session.awaitFinished();
        assertTrue(session.registry.wasLocallyTerminated());
        assertTrue(session.root().isInterrupted()); assertTrue(session.root().wasTerminated());
        assertTrue(session.node("TestPass").isPassed());
        assertTrue(session.node("TestSkip").isIgnored());
        assertTrue(session.node("TestFail").isDefect()); assertFalse(session.node("TestFail").wasTerminated());
        assertTrue(session.node("TestSlow").isInterrupted()); assertTrue(session.node("TestSlow/child").wasTerminated());
        assertEquals(1, session.console.getResultsViewer().getFailedTestCount());
        assertFalse(session.root().getAllTests().stream().anyMatch(node -> node.isLeaf() && node.isDefect() && node.getLocationUrl() == null));
        assertEquals(GoTestEvents.Status.INTERRUPTED, session.registry.target(session.node("TestSlow/child").getLocationUrl()).status());
        assertEquals(GoTestEvents.Status.PASSED, session.registry.target(session.node("TestPass").getLocationUrl()).status());
        assertEquals(List.of("TestSlow/child"), session.properties.selectedConfiguration(session.node("TestSlow/child")).getTestSelection().testNames());
        var event = toolbarEvent(); session.result.getRestartActions()[0].update(event);
        assertFalse(event.getPresentation().isEnabled()); assertTrue(event.getPresentation().getDescription().contains("terminated"));
        session.handler.destroyProcess(); session.handler.killProcess();
        assertEquals(1, session.console.getResultsViewer().getFailedTestCount());
    }

    public void testActualStopBeforeAnyGoEventLeavesAnInterruptedEmptyRoot() throws Exception {
        var session = start("empty", GoTestSelection.single("TestLater")); session.awaitReady();
        session.handler.destroyProcess(); session.awaitFinished();
        assertTrue(session.root().isInterrupted()); assertTrue(session.root().wasTerminated());
        assertFalse(session.root().isPassed()); assertTrue(session.root().getChildren().isEmpty());
        assertEquals(0, session.console.getResultsViewer().getFailedTestCount());
        assertEquals(List.of("TestLater"), session.registry.missingRequestedTests());
        assertTrue(session.registry.knownTargets().isEmpty());
    }

    public void testForceKillEscalationUsesTheSameTerminationProvenance() throws Exception {
        var session = start("force", null); session.awaitReady(); session.node("TestSlow");
        session.handler.destroyProcess();
        // On platforms supporting graceful JVM shutdown this reaches its hook;
        // elsewhere Stop can already have killed it. Both remain one local stop.
        PlatformTestUtil.waitWithEventsDispatching("graceful stop or process exit", () -> session.stopping.get() || session.handler.isProcessTerminated(), 10);
        if (session.handler.getProcess().isAlive()) session.handler.killProcess();
        session.awaitFinished();
        assertTrue(session.registry.wasLocallyTerminated()); assertTrue(session.node("TestSlow").isInterrupted());
        assertEquals(1, session.console.getResultsViewer().getFailedTestCount());
    }

    public void testSplitFinalPassIsDrainedBeforeLocallyStoppedRunFinishes() throws Exception {
        var session = start("buffered", null); session.awaitReady(); session.node("TestPass");
        session.handler.destroyProcess(); session.awaitFinished();
        assertTrue(session.registry.wasLocallyTerminated()); assertTrue(session.root().isInterrupted());
        assertTrue(session.node("TestPass").isPassed()); assertFalse(session.node("TestPass").wasTerminated());
        assertEquals(GoTestEvents.Status.PASSED, session.registry.target(session.node("TestPass").getLocationUrl()).status());
        assertEquals(0, session.console.getResultsViewer().getFailedTestCount());
    }

    public void testLateStopCannotRelabelNaturalCompletionOrAnotherConsole() throws Exception {
        var natural = start("natural", null); natural.awaitFinished();
        var stopped = start("empty", null); stopped.awaitReady(); stopped.handler.destroyProcess(); stopped.awaitFinished();
        natural.handler.destroyProcess(); natural.handler.killProcess();
        assertFalse(natural.registry.wasLocallyTerminated()); assertTrue(natural.root().isPassed());
        assertFalse(natural.root().wasTerminated());
        assertEquals(GoTestEvents.Status.PASSED, natural.registry.target(natural.node("TestPass").getLocationUrl()).status());
        assertTrue(stopped.root().wasTerminated());
        Disposer.dispose(stopped.console);
        assertTrue(natural.root().isPassed()); assertFalse(natural.registry.wasLocallyTerminated());
    }

    public void testStopAfterPackageTerminalKeepsFinalChildrenWhileTerminatingTheLiveRun() throws Exception {
        var session = start("terminal-but-alive", null); session.awaitReady();
        var passed = session.node("TestPass"); var failed = session.node("TestFail"); var skipped = session.node("TestSkip");
        var pkg = passed.getParent();
        PlatformTestUtil.waitWithEventsDispatching("package terminal before Stop", pkg::isFinal, 10);
        assertTrue(passed.isPassed()); assertTrue(failed.isDefect()); assertTrue(skipped.isIgnored());
        assertTrue(session.handler.getProcess().isAlive()); assertFalse(session.handler.isProcessTerminated());
        session.handler.destroyProcess(); session.awaitFinished();
        assertTrue(session.registry.wasLocallyTerminated()); assertTrue(session.root().wasTerminated());
        assertTrue(pkg.isFinal()); assertFalse(pkg.wasTerminated());
        assertTrue(passed.isPassed()); assertFalse(passed.wasTerminated());
        assertTrue(failed.isDefect()); assertFalse(failed.wasTerminated());
        assertTrue(skipped.isIgnored()); assertFalse(skipped.wasTerminated());
        assertEquals(1, session.console.getResultsViewer().getFailedTestCount());
        assertEquals(GoTestEvents.Status.PASSED, session.registry.target(passed.getLocationUrl()).status());
        assertEquals(GoTestEvents.Status.FAILED, session.registry.target(failed.getLocationUrl()).status());
        assertEquals(GoTestEvents.Status.SKIPPED, session.registry.target(skipped.getLocationUrl()).status());
    }

    public void testNullRegistryIsRejectedBeforeCommandLineCanSpawnAProcess() throws Exception {
        // An empty command would fail with ExecutionException if process startup
        // were reached. Registry validation must be the earlier failure instead.
        try {
            new GoTestProcessHandler(new GeneralCommandLine(), null);
            fail("A registry-less handler must not start");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("registry is required"));
        }
    }

    public void testUnrequestedBadExitAfterPassStillProducesInfrastructureFailure() throws Exception {
        var session = start("bad-exit", null); session.awaitFinished();
        assertFalse(session.registry.wasLocallyTerminated()); assertTrue(session.node("TestPass").isPassed());
        assertTrue(session.root().isDefect()); assertFalse(session.root().wasTerminated());
        assertEquals(1, session.console.getResultsViewer().getFailedTestCount());
        assertTrue(session.root().getAllTests().stream().anyMatch(node -> node.isLeaf() && node.isDefect() && node.getLocationUrl() == null));
    }

    public void testActualHandlerFinalizationDoesNotReportAnObservedSelectedBenchmarkMissing() throws Exception {
        var session=start("benchmark",GoTestSelection.benchmark("BenchmarkOne"),"-benchtime=7x -benchmem");session.awaitFinished();
        assertEquals(Integer.valueOf(0),session.handler.getExitCode());assertFalse(session.root().isDefect());
        assertTrue(session.node("BenchmarkOne").isPassed());assertTrue(session.registry.missingRequestedTests().isEmpty());
        assertTrue(session.registry.knownTargets().isEmpty());
        var rerun=session.properties.selectedConfiguration(session.node("BenchmarkOne"));assertNotNull(rerun);assertNotSame(session.configuration,rerun);
        assertEquals(GoCommand.TEST,rerun.getCommand());assertEquals(GoTestSelection.benchmark("BenchmarkOne"),rerun.getTestSelection());
        assertFalse(rerun.getTestSelection().rerun());
        assertEquals(List.of("-run=^$","-bench=^BenchmarkOne$"),GoTestSelector.arguments(rerun.getTestSelection()));
        assertEquals("-benchtime=7x -benchmem",rerun.getGoArguments());assertEquals(directory.toString(),rerun.getDirectory());assertEquals(".",rerun.getTarget());
        assertNull(session.registry.target(session.node("BenchmarkOne").getLocationUrl()));
        var verified=ApplicationManager.getApplication().executeOnPooledThread(()->{GoTestContextSelection.validateBeforeRun(rerun);return null;});
        PlatformTestUtil.waitWithEventsDispatching("terminated benchmark rerun source guard",verified::isDone,5);verified.get();
        GoTestContextSelection.validateBeforeRun(rerun);
        assertEquals(GoTestSelection.benchmark("BenchmarkOne"),session.configuration.getTestSelection());
        assertEquals("-benchtime=7x -benchmem",session.configuration.getGoArguments());assertEquals(directory.toString(),session.configuration.getDirectory());assertEquals(".",session.configuration.getTarget());
        assertEquals(0,session.console.getResultsViewer().getFailedTestCount());
    }
    public void testActualHandlerStillFailsASelectedBenchmarkWhichWasNeverObserved() throws Exception {
        var session=start("benchmark-missing",GoTestSelection.benchmark("BenchmarkOne"));session.awaitFinished();
        assertEquals(Integer.valueOf(0),session.handler.getExitCode());assertTrue(session.root().isDefect());
        assertEquals(List.of("BenchmarkOne"),session.registry.missingRequestedTests());
        assertTrue(session.console.getResultsViewer().getFailedTestCount()>0);
    }
    public void testActualStopKeepsAnObservedBenchmarkInterruptedWithoutInventingSelectionFailure() throws Exception {
        var session=start("benchmark-open",GoTestSelection.benchmark("BenchmarkOne"));session.awaitReady();
        session.node("BenchmarkOne");session.handler.destroyProcess();session.awaitFinished();
        assertTrue(session.registry.wasLocallyTerminated());assertTrue(session.node("BenchmarkOne").wasTerminated());
        assertTrue(session.registry.missingRequestedTests().isEmpty());assertEquals(0,session.console.getResultsViewer().getFailedTestCount());
    }

    private Session start(String mode, GoTestSelection selection) throws Exception {
        return start(mode, selection, "");
    }
    private Session start(String mode, GoTestSelection selection, String arguments) throws Exception {
        StringBuilder source = new StringBuilder("package p\nimport \"testing\"\n");
        var declarations = new ArrayList<GoTestSourceIndex.Declaration>();
        for (String name : NAMES) {
            int offset = source.length(); source.append("func ").append(name).append("(t *testing.T) {}\n");
            declarations.add(new GoTestSourceIndex.Declaration(name, "test", offset, source.length(), offset + 5));
        }
        if(selection!=null&&selection.kind()==GoTestSelection.Kind.BENCHMARK){
            int offset=source.length();source.append("func BenchmarkOne(b *testing.B) {}\n");
            declarations.add(new GoTestSourceIndex.Declaration("BenchmarkOne","benchmark",offset,source.length(),offset+5));
        }
        Path file = directory.resolve("fixture_test.go"); Files.writeString(file, source);
        var virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file); assertNotNull(virtual);
        FileDocumentManager.getInstance().getDocument(virtual);
        var snapshot = new GoTestSourceIndex.Snapshot(file, "fixture", source.toString(), declarations);
        var factory = ConfigurationTypeUtil.findConfigurationType(GoRunConfigurationType.class).getConfigurationFactories()[0];
        var configuration = new GoRunConfiguration(getProject(), factory, "termination fixture");
        configuration.setCommand(GoCommand.TEST); configuration.setDirectory(directory.toString());
        configuration.setTarget("."); configuration.setTestSelection(selection); configuration.setGoArguments(arguments);
        var registry = new GoTestRunRegistry(getProject(), configuration,
                Map.of("p", new GoTestRunRegistry.PackageSources(directory, List.of(snapshot))));
        var properties = new GoTestConsoleProperties(configuration, DefaultRunExecutor.getRunExecutorInstance(), registry);
        // IntelliJ's test class loader can have a null CodeSource location. This
        // fixture has only JDK dependencies and no nested classes, so copy its
        // actual compiled bytecode to an explicit isolated JVM classpath.
        Path classes = Files.createTempDirectory(directory, "fixture-classes-");
        Path fixtureClass = classes.resolve(GoTestTerminationFixture.class.getName().replace('.', '/') + ".class");
        Files.createDirectories(fixtureClass.getParent());
        try (var bytes = GoTestTerminationFixture.class.getResourceAsStream("GoTestTerminationFixture.class")) {
            assertNotNull("compiled fixture class resource", bytes);
            Files.copy(bytes, fixtureClass);
        }
        String java = Path.of(System.getProperty("java.home"), "bin", SystemInfo.isWindows ? "java.exe" : "java").toString();
        var handler = new GoTestProcessHandler(new GeneralCommandLine(java, "-cp", classes.toString(),
                GoTestTerminationFixture.class.getName(), mode).withWorkDirectory(directory.toString()), registry);
        processes.add(handler); handler.setShouldDestroyProcessRecursively(true);
        var ready = new AtomicBoolean(); var stopping = new AtomicBoolean();
        handler.addProcessListener(new ProcessAdapter() {
            @Override public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                if (event.getText().contains("FIXTURE_READY")) ready.set(true);
                if (event.getText().contains("FIXTURE_STOPPING")) stopping.set(true);
            }
        });
        var console = (SMTRunnerConsoleView) SMTestRunnerConnectionUtil.createAndAttachConsole("GoForge", handler, properties);
        console.getComponent(); Disposer.register(getTestRootDisposable(), console);
        var result = new DefaultExecutionResult(console, handler); properties.mount(console, result);
        handler.startNotify();
        return new Session(configuration, handler, registry, properties, console, result, ready, stopping);
    }
    private AnActionEvent toolbarEvent() {
        return AnActionEvent.createFromDataContext("GoForgeTest", new Presentation(),
                SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject()).build());
    }
    private record Session(GoRunConfiguration configuration, GoTestProcessHandler handler, GoTestRunRegistry registry, GoTestConsoleProperties properties,
                           SMTRunnerConsoleView console, DefaultExecutionResult result,
                           AtomicBoolean ready, AtomicBoolean stopping) {
        SMTestProxy.SMRootTestProxy root() { return console.getResultsViewer().getTestsRootNode(); }
        void awaitReady() { PlatformTestUtil.waitWithEventsDispatching("fixture ready", ready::get, 15); }
        void awaitFinished() {
            PlatformTestUtil.waitWithEventsDispatching("handler and SM finished", () -> handler.isProcessTerminated() && root().isFinal(), 15);
            assertFalse(handler.getProcess().isAlive());
        }
        SMTestProxy node(String name) {
            PlatformTestUtil.waitWithEventsDispatching("real SM node " + name,
                    () -> root().getAllTests().stream().anyMatch(node -> node.getName().equals("p::" + name)), 10);
            return root().getAllTests().stream().filter(node -> node.getName().equals("p::" + name)).findFirst().orElseThrow();
        }
    }
}

package dev.goforge.run;

import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.execution.actions.ExecutorAction;
import com.intellij.execution.actions.RunConfigurationProducer;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.notification.Notification;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.ExtensionTestUtil;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition;
import dev.goforge.settings.GoForgeSettings;
import dev.goforge.testing.GoTestContextSelection;
import dev.goforge.testing.GoTestSelection;
import dev.goforge.testing.GoTestSourceIndex;
import dev.goforge.tools.HostTools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** The actual producer, background Task, real Document/save boundary and configuration clone. */
public final class GoTestContextProducerTest extends BasePlatformTestCase {
    private Path directory;
    private VirtualFile contentRoot;
    private LanguageServerDefinition server;
    private boolean serverEnabled;
    private String previousGo;
    private String previousBuildTags;
    private static final String TEXT = "package fixture\n\nfunc TestOne(t *testing.T) {}\nfunc TestTwo(t *testing.T) {}\n";
    @Override protected void setUp() throws Exception {
        super.setUp();
        server = LanguageServersRegistry.getInstance().getServerDefinition("goforge.gopls");
        serverEnabled = server.isEnabled(getProject()); server.setEnabled(false, getProject());
        previousGo = GoForgeSettings.getInstance().goPath;
        previousBuildTags = dev.goforge.settings.GoProjectSettings.getInstance(getProject()).buildTags;
        GoForgeSettings.getInstance().goPath = Path.of(System.getProperty("java.home"), "bin", HostTools.executableName("java")).toString();
        directory = Files.createTempDirectory("goforge-context-").toRealPath();
        contentRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory); assertNotNull(contentRoot);
        PsiTestUtil.addContentRoot(myFixture.getModule(), contentRoot);
    }
    @Override protected void tearDown() throws Exception {
        try {
            var editors = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject());
            for (var file : editors.getOpenFiles()) {
                var document = FileDocumentManager.getInstance().getCachedDocument(file);
                if (document != null) FileDocumentManager.getInstance().saveDocument(document);
                editors.closeFile(file);
            }
            PsiTestUtil.removeContentEntry(myFixture.getModule(), contentRoot);
            server.setEnabled(serverEnabled, getProject()); GoForgeSettings.getInstance().goPath = previousGo;
            try (var paths = Files.walk(directory)) { for (var p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        } finally {
            dev.goforge.settings.GoProjectSettings.getInstance(getProject()).buildTags = previousBuildTags;
            super.tearDown();
        }
    }
    public void testTwoFunctionsHaveDistinctResolvedConfigurationsAndResolverRunsOffEdt() throws Exception {
        open(TEXT);
        AtomicBoolean offEdt = new AtomicBoolean();
        var producer = new GoRunConfigurationProducer((go,env,file,text,cancelled) -> {
            offEdt.set(!ApplicationManager.getApplication().isDispatchThread() && !ApplicationManager.getApplication().isReadAccessAllowed());
            return snapshot(file,text);
        });
        var one = produce(producer, TEXT.indexOf("TestOne") + 2);
        var two = produce(producer, TEXT.indexOf("TestTwo") + 2);
        assertFalse(producer.isConfigurationFromContext((GoRunConfiguration) one.getConfiguration(), context(TEXT.indexOf("TestTwo") + 2)));
        AtomicBoolean ranOne = new AtomicBoolean(), ranTwo = new AtomicBoolean();
        producer.onFirstRun(one, context(TEXT.indexOf("TestOne") + 2), () -> ranOne.set(true));
        await(ranOne::get);
        producer.onFirstRun(two, context(TEXT.indexOf("TestTwo") + 2), () -> ranTwo.set(true));
        await(ranTwo::get);
        assertTrue(offEdt.get());
        assertEquals(GoTestSelection.single("TestOne"), ((GoRunConfiguration) one.getConfiguration()).getTestSelection());
        assertEquals(GoTestSelection.single("TestTwo"), ((GoRunConfiguration) two.getConfiguration()).getTestSelection());
    }
    public void testOutsideRunnableFunctionRetainsPackageSelectionAfterSuccessfulParsing() throws Exception {
        open(TEXT);
        var producer = new GoRunConfigurationProducer((go,env,file,text,cancelled) -> snapshot(file,text));
        var config = produce(producer, 1); AtomicBoolean ran = new AtomicBoolean();
        producer.onFirstRun(config, context(1), () -> ran.set(true)); await(ran::get);
        assertNull(((GoRunConfiguration)config.getConfiguration()).getTestSelection());
        assertEquals(".", ((GoRunConfiguration)config.getConfiguration()).getTarget());
    }
    public void testSourceEditedWhileBackgroundResolutionIsPendingDoesNotRunPackage() throws Exception {
        open(TEXT);
        var released = new CompletableFuture<Void>(); var entered = new AtomicBoolean(); var finished = new AtomicBoolean();
        var editFailure = new AtomicReference<Throwable>();
        var staleNotification = new AtomicReference<Notification>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(Notifications.TOPIC, new Notifications() {
            @Override public void notify(Notification notification) {
                if (notification.getGroupId().equals("GoForge") && notification.getContent().contains("source changed during discovery"))
                    staleNotification.set(notification);
            }
        });
        var document = myFixture.getEditor().getDocument();
        var writeSafeModality = ModalityState.current();
        var producer = new GoRunConfigurationProducer((go,env,file,text,cancelled) -> {
            entered.set(true);
            // Headless ProgressManager can pump a nested modal EDT loop inside onFirstRun.
            // Preserve the test's write-safe modality; raw Swing dispatch cannot edit Documents.
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    WriteAction.run(() -> document.setText(TEXT.replace("TestOne", "TestChanged")));
                    released.complete(null);
                } catch (Throwable error) { released.completeExceptionally(error); }
            }, writeSafeModality);
            try {
                released.get(10, java.util.concurrent.TimeUnit.SECONDS);
                return snapshot(file,text);
            } catch (Exception error) { editFailure.set(error); throw error; }
            finally { finished.set(true); }
        });
        var configuration = produce(producer, TEXT.indexOf("TestOne")); AtomicBoolean ran = new AtomicBoolean();
        producer.onFirstRun(configuration, context(TEXT.indexOf("TestOne")), () -> ran.set(true));
        await(entered::get);
        await(finished::get);
        if (editFailure.get() != null) throw new AssertionError("Scheduled source edit failed", editFailure.get());
        // Wait for the actual onSuccess decision, rather than merely the resolver completing.
        await(() -> staleNotification.get() != null || ran.get());
        assertTrue(document.getText().contains("TestChanged"));
        assertFalse(ran.get());
        assertNotNull(staleNotification.get());
        staleNotification.get().expire();
    }
    public void testCancelledBackgroundResolutionNeverInvokesRunCallback() throws Exception {
        open(TEXT); AtomicBoolean finished = new AtomicBoolean(), ran = new AtomicBoolean();
        var producer = new GoRunConfigurationProducer((go,env,file,text,cancelled) -> {
            ProgressManager.getInstance().getProgressIndicator().cancel();
            assertTrue(cancelled.getAsBoolean()); finished.set(true); throw new java.util.concurrent.CancellationException();
        });
        var configuration = produce(producer, TEXT.indexOf("TestOne"));
        producer.onFirstRun(configuration, context(TEXT.indexOf("TestOne")), () -> ran.set(true));
        await(finished::get); PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); assertFalse(ran.get());
    }
    public void testGuardSurvivesRealCloneAndRequiresActualSavedDocument() throws Exception {
        VirtualFile file = open(TEXT);
        var original = configuration(); original.setTestSelection(GoTestSelection.single("TestOne"));
        var document = myFixture.getEditor().getDocument();
        String changed = TEXT + "// unsaved\n";
        WriteAction.run(() -> document.setText(changed));
        GoTestContextSelection.bind(original,file,changed,document.getModificationStamp());
        var clone = original.clone();
        var failure = backgroundFailure(() -> GoTestContextSelection.validateBeforeRun(clone));
        assertNotNull("Configuration clone must retain the source guard", failure);
        assertTrue(failure.getMessage().contains("not saved"));
        assertEquals(TEXT, Files.readString(directory.resolve("fixture_test.go")));
        FileDocumentManager.getInstance().saveDocument(document);
        assertNull(backgroundFailure(() -> GoTestContextSelection.validateBeforeRun(clone)));
        GoTestContextSelection.validateBeforeRun(clone); // final EDT check reads no file
        WriteAction.run(() -> document.setText(changed + "// later\n"));
        assertThrows(com.intellij.execution.ExecutionException.class, () -> GoTestContextSelection.validateBeforeRun(clone));
        GoTestContextSelection.started(clone);
        GoTestContextSelection.validateBeforeRun(original); // initial caret guard is consumed after successful spawn
    }
    public void testExplicitTreeRerunClearsOnlyCloneSnapshotConstraint() throws Exception {
        VirtualFile file = open(TEXT); var original = configuration(); original.setTestSelection(GoTestSelection.single("TestOne"));
        var document = myFixture.getEditor().getDocument(); GoTestContextSelection.bind(original,file,TEXT,document.getModificationStamp());
        var clone = original.clone(); GoTestContextSelection.clear(clone);
        WriteAction.run(() -> document.setText(TEXT + "// later\n"));
        GoTestContextSelection.validateBeforeRun(clone);
        assertThrows(com.intellij.execution.ExecutionException.class, () -> GoTestContextSelection.validateBeforeRun(original));
    }

    public void testActualCurrentFileChooserResolvesLaunchCaretWithoutCallingOnFirstRun() throws Exception {
        open(TEXT); var calls = new AtomicInteger(); var offEdt = new AtomicBoolean();
        installCurrentFileResolver((go, env, file, text, cancelled) -> {
            calls.incrementAndGet();
            offEdt.set(!ApplicationManager.getApplication().isDispatchThread() && !ApplicationManager.getApplication().isReadAccessAllowed());
            return snapshot(file, text);
        });
        var original = currentFileConfiguration();
        assertNull(original.getTestSelection()); // Platform creates this candidate from PsiFile, with no caret.
        var copy = original.clone();
        var pending = GoRunConfigurationProducer.capturePendingExecution(copy, environment(original, TEXT.indexOf("TestTwo") + 2, true));
        assertEquals(0, calls.get());
        assertNull(backgroundFailure(() -> GoRunConfigurationProducer.resolvePendingExecution(pending, () -> false)));
        assertTrue(offEdt.get()); assertEquals(1, calls.get());
        assertNull("Background resolution must not mutate the real run configuration", original.getTestSelection());
        assertEquals(GoTestSelection.single("TestTwo"), copy.getTestSelection());
        assertTrue(copy.createCommandLine().getParametersList().getList().contains("-run=^TestTwo$"));
        GoRunConfigurationProducer.commitPendingExecution(pending);
        assertEquals(GoTestSelection.single("TestTwo"), original.getTestSelection());
        assertEquals("TestTwo", original.getName());
        var xml = new org.jdom.Element("configuration"); original.writeExternal(xml);
        assertEquals("TestTwo", xml.getChild("goforge-test-selection").getChild("test").getAttributeValue("name"));
        assertFalse(GoRunConfigurationProducer.hasPendingContext(original));
        WriteAction.run(() -> myFixture.getEditor().getDocument().setText(TEXT + "// later\n"));
        assertThrows(com.intellij.execution.ExecutionException.class, () -> GoTestContextSelection.validateBeforeRun(original));
        GoTestContextSelection.started(copy);
        GoTestContextSelection.validateBeforeRun(original); // A real start consumes the shared initial guard.
    }

    public void testFileOnlyPackageContextDoesNotUseAnUnrelatedExecutionCaret() throws Exception {
        open(TEXT); installCurrentFileResolver((go, env, file, text, cancelled) -> snapshot(file, text));
        var original = currentFileConfiguration(); var copy = original.clone();
        var pending = GoRunConfigurationProducer.capturePendingExecution(copy, environment(original, TEXT.indexOf("TestOne"), false));
        assertNull(backgroundFailure(() -> GoRunConfigurationProducer.resolvePendingExecution(pending, () -> false)));
        GoRunConfigurationProducer.commitPendingExecution(pending);
        assertNull(original.getTestSelection()); assertEquals(".", original.getTarget());
        assertFalse(copy.createCommandLine().getParametersList().getList().stream().anyMatch(a -> a.startsWith("-run=")));
    }

    public void testExplicitPackageAndNamedConfigurationsAreNotRetargetedByCurrentFileMarker() throws Exception {
        open(TEXT);
        var original = configuration(); original.setName("chosen package");
        var copy = original.clone();
        assertNull(GoRunConfigurationProducer.capturePendingExecution(copy, environment(original, TEXT.indexOf("TestOne"), true)));
        assertNull(copy.getTestSelection()); assertEquals("chosen package", copy.getName());
        original.setTestSelection(GoTestSelection.single("TestTwo"));
        copy = original.clone();
        assertNull(GoRunConfigurationProducer.capturePendingExecution(copy, environment(original, TEXT.indexOf("TestOne"), true)));
        assertEquals(GoTestSelection.single("TestTwo"), copy.getTestSelection());
    }

    public void testCurrentFilePendingCancellationNeverResolvesOrCommitsAPackageFallback() throws Exception {
        open(TEXT); var calls = new AtomicInteger();
        installCurrentFileResolver((go, env, file, text, cancelled) -> { calls.incrementAndGet(); return snapshot(file, text); });
        var original = currentFileConfiguration(); var copy = original.clone();
        var pending = GoRunConfigurationProducer.capturePendingExecution(copy, environment(original, TEXT.indexOf("TestOne"), true));
        var failure = backgroundFailure(() -> GoRunConfigurationProducer.resolvePendingExecution(pending, () -> true));
        assertInstanceOf(failure, java.util.concurrent.CancellationException.class);
        assertThrows(java.util.concurrent.CancellationException.class, () -> GoRunConfigurationProducer.commitPendingExecution(pending));
        assertEquals(0, calls.get()); assertNull(original.getTestSelection());
        assertTrue(GoRunConfigurationProducer.hasPendingContext(original));
    }

    public void testCurrentFileSourceChangedAfterCaptureIsRejectedBeforeParsing() throws Exception {
        open(TEXT); var calls = new AtomicInteger();
        installCurrentFileResolver((go, env, file, text, cancelled) -> { calls.incrementAndGet(); return snapshot(file, text); });
        var original = currentFileConfiguration(); var copy = original.clone();
        var pending = GoRunConfigurationProducer.capturePendingExecution(copy, environment(original, TEXT.indexOf("TestOne"), true));
        WriteAction.run(() -> myFixture.getEditor().getDocument().setText(TEXT.replace("TestOne", "TestChanged")));
        var failure = backgroundFailure(() -> GoRunConfigurationProducer.resolvePendingExecution(pending, () -> false));
        assertInstanceOf(failure, com.intellij.execution.ExecutionException.class);
        assertTrue(failure.getMessage().contains("source changed")); assertEquals(0, calls.get());
        assertThrows(com.intellij.execution.ExecutionException.class, () -> GoRunConfigurationProducer.commitPendingExecution(pending));
        assertNull(original.getTestSelection()); assertNull(copy.getTestSelection());
    }

    public void testCurrentFileSourceChangesDuringResolutionCannotBecomeAPackageRun() throws Exception {
        open(TEXT); var safeModality = ModalityState.current(); var changed = new CompletableFuture<Void>();
        var document = myFixture.getEditor().getDocument();
        installCurrentFileResolver((go, env, file, text, cancelled) -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                try { WriteAction.run(() -> document.setText(TEXT + "// edit while resolving\n")); changed.complete(null); }
                catch (Throwable error) { changed.completeExceptionally(error); }
            }, safeModality);
            changed.get(10, java.util.concurrent.TimeUnit.SECONDS);
            return snapshot(file, text);
        });
        var original = currentFileConfiguration(); var copy = original.clone();
        var pending = GoRunConfigurationProducer.capturePendingExecution(copy, environment(original, TEXT.indexOf("TestOne"), true));
        var failure = backgroundFailure(() -> GoRunConfigurationProducer.resolvePendingExecution(pending, () -> false));
        assertInstanceOf(failure, com.intellij.execution.ExecutionException.class);
        assertTrue(failure.getMessage().contains("source changed"));
        assertNull(original.getTestSelection()); assertNull(copy.getTestSelection());
        assertThrows(com.intellij.execution.ExecutionException.class, () -> GoRunConfigurationProducer.commitPendingExecution(pending));
    }

    public void testPendingCommitDoesNotOverwriteConfigurationEditedDuringPreparation() throws Exception {
        open(TEXT); installCurrentFileResolver((go, env, file, text, cancelled) -> snapshot(file, text));
        var original = currentFileConfiguration(); var copy = original.clone();
        var pending = GoRunConfigurationProducer.capturePendingExecution(copy, environment(original, TEXT.indexOf("TestOne"), true));
        assertNull(backgroundFailure(() -> GoRunConfigurationProducer.resolvePendingExecution(pending, () -> false)));
        original.setName("user choice"); original.setGoArguments("-short");
        assertThrows(com.intellij.execution.ExecutionException.class, () -> GoRunConfigurationProducer.commitPendingExecution(pending));
        assertEquals("user choice", original.getName()); assertEquals("-short", original.getGoArguments());
        assertNull(original.getTestSelection()); assertTrue(GoRunConfigurationProducer.hasPendingContext(original));
    }

    public void testAnOlderCurrentFileAttemptCannotPublishOverTheNewerCaretRequest() throws Exception {
        open(TEXT); installCurrentFileResolver((go, env, file, text, cancelled) -> snapshot(file, text));
        var original = currentFileConfiguration(); var firstCopy = original.clone();
        var first = GoRunConfigurationProducer.capturePendingExecution(firstCopy, environment(original, TEXT.indexOf("TestOne"), true));
        var secondCopy = original.clone();
        var second = GoRunConfigurationProducer.capturePendingExecution(secondCopy, environment(original, TEXT.indexOf("TestTwo"), true));
        assertNull(backgroundFailure(() -> GoRunConfigurationProducer.resolvePendingExecution(first, () -> false)));
        assertNull(backgroundFailure(() -> GoRunConfigurationProducer.resolvePendingExecution(second, () -> false)));
        assertThrows(com.intellij.execution.ExecutionException.class, () -> GoRunConfigurationProducer.commitPendingExecution(first));
        GoRunConfigurationProducer.commitPendingExecution(second);
        assertEquals(GoTestSelection.single("TestTwo"), original.getTestSelection());
    }

    public void testActualCurrentFileStateRefusesUncapturedPreparationBeforeAnyResolverOrTool() throws Exception {
        open(TEXT); var calls = new AtomicInteger();
        installCurrentFileResolver((go, env, file, text, cancelled) -> { calls.incrementAndGet(); return snapshot(file, text); });
        var original = currentFileConfiguration();
        var environment = environment(original, TEXT.indexOf("TestOne"), true);
        var state = (GoTestCommandLineState) original.getState(environment.getExecutor(), environment);
        var failure = backgroundFailure(() -> state.prepare(() -> false));
        assertInstanceOf(failure, com.intellij.execution.ExecutionException.class);
        assertTrue(failure.getMessage(), failure.getMessage().contains("Capture the current Go test editor"));
        assertEquals(0, calls.get());
    }

    public void testCommittedRegistryRerunRetainsObservedDuplicateNameWithoutPendingCaret() throws Exception {
        open(TEXT); installCurrentFileResolver((go, env, file, text, cancelled) -> snapshot(file, text));
        var original = currentFileConfiguration(); var copy = original.clone();
        var pending = GoRunConfigurationProducer.capturePendingExecution(copy, environment(original, TEXT.indexOf("TestOne"), true));
        assertNull(backgroundFailure(() -> GoRunConfigurationProducer.resolvePendingExecution(pending, () -> false)));
        GoRunConfigurationProducer.commitPendingExecution(pending);
        var registry = new dev.goforge.testing.GoTestRunRegistry(getProject(), copy, java.util.Map.of("example.com/fixture",
                new dev.goforge.testing.GoTestRunRegistry.PackageSources(directory, List.of(snapshot(directory.resolve("fixture_test.go"), TEXT)))));
        var properties = new dev.goforge.testing.GoTestConsoleProperties(copy, DefaultRunExecutor.getRunExecutorInstance(), registry);
        try {
            assertFalse(GoRunConfigurationProducer.hasPendingContext(registry.originalConfiguration()));
            var hint = registry.locationHint("example.com/fixture", "TestOne/dup#01", "observed-child", false);
            registry.markTerminated();
            var proxy = new com.intellij.execution.testframework.sm.runner.SMTestProxy("TestOne/dup#01", false, hint);
            var rerun = properties.selectedConfiguration(proxy);
            assertNotNull(rerun);
            assertFalse(GoRunConfigurationProducer.hasPendingContext(rerun));
            assertEquals(GoTestSelection.rerun(List.of("TestOne/dup#01")), rerun.getTestSelection());
            assertNull(GoRunConfigurationProducer.capturePendingExecution(rerun.clone(), environment(rerun, TEXT.indexOf("TestTwo"), true)));
            assertTrue(rerun.createCommandLine().getParametersList().getList().contains("-run=^TestOne$/^dup#01$"));
        } finally { com.intellij.openapi.util.Disposer.dispose(properties); }
    }

    public void testCurrentFileToolbarWithoutEditorDataUsesOnlyTheMatchingSelectedEditor() throws Exception {
        var file = open(TEXT); installCurrentFileResolver((go, env, path, text, cancelled) -> snapshot(path, text));
        var selected = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject()).openTextEditor(
                new com.intellij.openapi.fileEditor.OpenFileDescriptor(getProject(), file), true);
        assertNotNull(selected); selected.getCaretModel().moveToOffset(TEXT.indexOf("TestTwo"));
        var original = currentFileConfiguration(); var copy = original.clone();
        var environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), original)
                .dataContext(SimpleDataContext.getProjectContext(getProject())).build();
        environment.setRunningCurrentFile(true);
        var pending = GoRunConfigurationProducer.capturePendingExecution(copy, environment);
        assertNull(backgroundFailure(() -> GoRunConfigurationProducer.resolvePendingExecution(pending, () -> false)));
        GoRunConfigurationProducer.commitPendingExecution(pending);
        assertEquals(GoTestSelection.single("TestTwo"), original.getTestSelection());
    }

    public void testCurrentFileWithAnotherSelectedEditorRefusesPackageFallback() throws Exception {
        open(TEXT); installCurrentFileResolver((go, env, file, text, cancelled) -> snapshot(file, text));
        var original = currentFileConfiguration(); var copy = original.clone();
        Path other = directory.resolve("other.go"); Files.writeString(other, "package fixture\n");
        var otherFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(other); assertNotNull(otherFile);
        var selected = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject()).openTextEditor(
                new com.intellij.openapi.fileEditor.OpenFileDescriptor(getProject(), otherFile), true);
        assertNotNull(selected);
        var environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), original)
                .dataContext(SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject()).add(CommonDataKeys.EDITOR, selected).build()).build();
        environment.setRunningCurrentFile(true);
        assertThrows(com.intellij.execution.ExecutionException.class,
                () -> GoRunConfigurationProducer.capturePendingExecution(copy, environment));
        assertNull(original.getTestSelection()); assertTrue(GoRunConfigurationProducer.hasPendingContext(original));
    }

    public void testFirstRunDoesNotOverwriteConfigurationEditedWhileResolving() throws Exception {
        open(TEXT); var safeModality = ModalityState.current(); var edited = new CompletableFuture<Void>();
        var original = new AtomicReference<GoRunConfiguration>(); var rejected = new AtomicReference<Notification>();
        watchConfigurationRejection(rejected);
        var producer = new GoRunConfigurationProducer((go, env, file, text, cancelled) -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    original.get().setName("new user name"); original.get().setGoArguments("-short");
                    edited.complete(null);
                } catch (Throwable error) { edited.completeExceptionally(error); }
            }, safeModality);
            edited.get(10, java.util.concurrent.TimeUnit.SECONDS);
            return snapshot(file, text);
        });
        var from = produce(producer, TEXT.indexOf("TestOne")); original.set((GoRunConfiguration) from.getConfiguration());
        var ran = new AtomicBoolean();
        producer.onFirstRun(from, context(TEXT.indexOf("TestOne")), () -> ran.set(true));
        await(() -> rejected.get() != null || ran.get());
        assertFalse(ran.get()); assertNotNull(rejected.get());
        assertEquals("new user name", original.get().getName()); assertEquals("-short", original.get().getGoArguments());
        assertNull(original.get().getTestSelection()); assertTrue(GoRunConfigurationProducer.hasPendingContext(original.get()));
        rejected.get().expire();
    }

    public void testFirstRunCannotReplaceANewerCapturedCurrentFileRequest() throws Exception {
        open(TEXT); var safeModality = ModalityState.current(); var replaced = new CompletableFuture<Void>();
        var original = new AtomicReference<GoRunConfiguration>(); var rejected = new AtomicReference<Notification>();
        var newer = new AtomicReference<GoRunConfigurationProducer.PendingExecution>(); var calls = new AtomicInteger();
        watchConfigurationRejection(rejected);
        var producer = new GoRunConfigurationProducer((go, env, file, text, cancelled) -> {
            if (calls.incrementAndGet() == 1) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        newer.set(GoRunConfigurationProducer.capturePendingExecution(original.get().clone(),
                                environment(original.get(), TEXT.indexOf("TestTwo"), true)));
                        replaced.complete(null);
                    } catch (Throwable error) { replaced.completeExceptionally(error); }
                }, safeModality);
                replaced.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            return snapshot(file, text);
        });
        var fileContext = new ConfigurationContext(myFixture.getFile());
        var from = producer.createConfigurationFromContext(fileContext); assertNotNull(from);
        original.set((GoRunConfiguration) from.getConfiguration()); var ran = new AtomicBoolean();
        producer.onFirstRun(from, fileContext, () -> ran.set(true));
        await(() -> rejected.get() != null || ran.get());
        assertFalse(ran.get()); assertNotNull(rejected.get()); assertNotNull(newer.get());
        assertNull(original.get().getTestSelection());
        assertNull(backgroundFailure(() -> GoRunConfigurationProducer.resolvePendingExecution(newer.get(), () -> false)));
        GoRunConfigurationProducer.commitPendingExecution(newer.get());
        assertEquals(GoTestSelection.single("TestTwo"), original.get().getTestSelection());
        rejected.get().expire();
    }

    private void watchConfigurationRejection(AtomicReference<Notification> target) {
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(Notifications.TOPIC, new Notifications() {
            @Override public void notify(Notification notification) {
                if (notification.getGroupId().equals("GoForge") && notification.getContent().contains("configuration changed"))
                    target.set(notification);
            }
        });
    }

    private void installCurrentFileResolver(GoRunConfigurationProducer.SourceResolver resolver) {
        ExtensionTestUtil.maskExtensions(RunConfigurationProducer.EP_NAME,
                List.of(new GoRunConfigurationProducer(resolver)), getTestRootDisposable());
    }
    private GoRunConfiguration currentFileConfiguration() {
        // This is the actual Platform 252 chooser factory, not a manually invoked onFirstRun callback.
        var settings = ExecutorAction.getRunConfigsForCurrentFile(myFixture.getFile(), true);
        var candidates = settings.stream().map(s -> s.getConfiguration()).filter(GoRunConfiguration.class::isInstance).toList();
        assertEquals(1, candidates.size());
        return (GoRunConfiguration) candidates.getFirst();
    }
    private ExecutionEnvironment environment(GoRunConfiguration original, int caret, boolean currentFile) throws Exception {
        var environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), original)
                .dataContext(context(caret).getDataContext()).build();
        environment.setRunningCurrentFile(currentFile);
        return environment;
    }
    private VirtualFile open(String text) throws Exception {
        Path path = directory.resolve("fixture_test.go"); Files.writeString(path,text);
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path); assertNotNull(file);
        myFixture.configureFromExistingVirtualFile(file); return file;
    }
    private ConfigurationContext context(int offset) {
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        PsiDocumentManager.getInstance(getProject()).commitDocument(myFixture.getEditor().getDocument());
        var data = SimpleDataContext.builder().add(CommonDataKeys.PROJECT,getProject()).add(CommonDataKeys.EDITOR,myFixture.getEditor())
                .add(CommonDataKeys.PSI_FILE,myFixture.getFile()).add(CommonDataKeys.PSI_ELEMENT,myFixture.getFile().findElementAt(offset))
                .add(CommonDataKeys.VIRTUAL_FILE,myFixture.getFile().getVirtualFile()).build();
        return ConfigurationContext.getFromContext(data);
    }
    private ConfigurationFromContext produce(GoRunConfigurationProducer producer,int offset) {
        var result = producer.createConfigurationFromContext(context(offset)); assertNotNull(result); return result;
    }
    private static GoTestSourceIndex.Snapshot snapshot(Path file,String text) {
        var declarations = new java.util.ArrayList<GoTestSourceIndex.Declaration>();
        for (String name : List.of("TestOne","TestTwo")) {
            int nameOffset = text.indexOf(name), start = text.lastIndexOf("func",nameOffset), end = text.indexOf('}',nameOffset)+1;
            declarations.add(new GoTestSourceIndex.Declaration(name,"test",start,end,nameOffset));
        }
        return new GoTestSourceIndex.Snapshot(file,GoTestSourceIndex.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),text,declarations);
    }
    private GoRunConfiguration configuration() {
        var factory = ConfigurationTypeUtil.findConfigurationType(GoRunConfigurationType.class).getConfigurationFactories()[0];
        var configuration = new GoRunConfiguration(getProject(),factory,"fixture"); configuration.setCommand(GoCommand.TEST); configuration.setDirectory(directory.toString());
        return configuration;
    }
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static Throwable backgroundFailure(Checked checked) {
        var done = new AtomicBoolean(); var failure = new AtomicReference<Throwable>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {try {checked.run();}catch(Throwable e){failure.set(e);}finally{done.set(true);}});
        await(done::get);return failure.get();
    }
    private static void await(java.util.function.BooleanSupplier condition) {
        PlatformTestUtil.waitWithEventsDispatching("Go test context work did not finish",condition,15);
    }

    private static final String BENCHMARK_TEXT="package fixture\nimport \"testing\"\nfunc BenchmarkOne(b *testing.B) {}\nfunc BenchmarkTwo(b *testing.B) {}\nfunc TestOne(t *testing.T) {}\n";
    private static GoTestSourceIndex.Snapshot benchmarkSnapshot(Path file,String text,String kind){
        var declarations=new java.util.ArrayList<GoTestSourceIndex.Declaration>();
        for(String name:List.of("BenchmarkOne","BenchmarkTwo","TestOne")){
            int nameOffset=text.indexOf(name),start=text.lastIndexOf("func",nameOffset),end=text.indexOf('}',nameOffset)+1;
            declarations.add(new GoTestSourceIndex.Declaration(name,name.startsWith("Benchmark")?kind:"test",start,end,nameOffset));
        }
        return new GoTestSourceIndex.Snapshot(file,GoTestSourceIndex.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),text,declarations);
    }
    public void testCurrentFileLaunchCaretRetainsBenchmarkKindThroughCommitAndSavedConfiguration() throws Exception {
        dev.goforge.settings.GoProjectSettings.getInstance(getProject()).buildTags="benchmark_fixture";
        open(BENCHMARK_TEXT);installCurrentFileResolver((go,env,file,text,cancelled)->benchmarkSnapshot(file,text,"benchmark"));
        var original=currentFileConfiguration();var copy=original.clone();
        var pending=GoRunConfigurationProducer.capturePendingExecution(copy,environment(original,BENCHMARK_TEXT.indexOf("BenchmarkTwo"),true));
        assertNull(backgroundFailure(()->GoRunConfigurationProducer.resolvePendingExecution(pending,()->false)));
        assertEquals(GoTestSelection.benchmark("BenchmarkTwo"),copy.getTestSelection());
        assertEquals(List.of("test","-tags=benchmark_fixture","-json","-run=^$","-bench=^BenchmarkTwo$","."),copy.createCommandLine().getParametersList().getList());
        GoRunConfigurationProducer.commitPendingExecution(pending);assertEquals(copy.getTestSelection(),original.getTestSelection());
        var xml=new org.jdom.Element("configuration");original.writeExternal(xml);var restored=configuration();restored.readExternal(xml);assertEquals(original.getTestSelection(),restored.getTestSelection());
    }
    public void testEditorContextFirstRunSelectsOnlyTheBenchmarkAtCaret() throws Exception {
        dev.goforge.settings.GoProjectSettings.getInstance(getProject()).buildTags="benchmark_fixture";
        open(BENCHMARK_TEXT);
        var producer=new GoRunConfigurationProducer((go,env,file,text,cancelled)->benchmarkSnapshot(file,text,"benchmark"));
        var from=produce(producer,BENCHMARK_TEXT.indexOf("BenchmarkOne"));var ran=new AtomicBoolean();
        producer.onFirstRun(from,context(BENCHMARK_TEXT.indexOf("BenchmarkOne")),()->ran.set(true));await(ran::get);
        var selected=(GoRunConfiguration)from.getConfiguration();assertEquals(GoTestSelection.benchmark("BenchmarkOne"),selected.getTestSelection());
        assertEquals(List.of("test","-tags=benchmark_fixture","-json","-run=^$","-bench=^BenchmarkOne$","."),selected.createCommandLine().getParametersList().getList());
    }
    public void testUnsupportedBenchmarkSpanFailsWithoutChoosingPackageAndStillAllowsTestDeclaration() throws Exception {
        open(BENCHMARK_TEXT);installCurrentFileResolver((go,env,file,text,cancelled)->benchmarkSnapshot(file,text,"unsupported-benchmark"));
        var original=currentFileConfiguration();var pending=GoRunConfigurationProducer.capturePendingExecution(original.clone(),environment(original,BENCHMARK_TEXT.indexOf("BenchmarkOne"),true));
        var error=backgroundFailure(()->GoRunConfigurationProducer.resolvePendingExecution(pending,()->false));assertNotNull(error);assertTrue(error.getMessage().contains("no package fallback"));
        var ordinary=currentFileConfiguration();var copy=ordinary.clone();var test=GoRunConfigurationProducer.capturePendingExecution(copy,environment(ordinary,BENCHMARK_TEXT.indexOf("TestOne"),true));
        assertNull(backgroundFailure(()->GoRunConfigurationProducer.resolvePendingExecution(test,()->false)));assertEquals(GoTestSelection.single("TestOne"),copy.getTestSelection());
    }
    public void testBenchmarkPendingSelectionRejectsNewConfigurationAndSourceEdits() throws Exception {
        open(BENCHMARK_TEXT);installCurrentFileResolver((go,env,file,text,cancelled)->benchmarkSnapshot(file,text,"benchmark"));
        var original=currentFileConfiguration();var copy=original.clone();var pending=GoRunConfigurationProducer.capturePendingExecution(copy,environment(original,BENCHMARK_TEXT.indexOf("BenchmarkOne"),true));
        assertNull(backgroundFailure(()->GoRunConfigurationProducer.resolvePendingExecution(pending,()->false)));
        original.setGoArguments("-benchtime=2x");assertThrows(com.intellij.execution.ExecutionException.class,()->GoRunConfigurationProducer.commitPendingExecution(pending));
        assertEquals("-benchtime=2x",original.getGoArguments());assertNull(original.getTestSelection());
        WriteAction.run(()->myFixture.getEditor().getDocument().setText(BENCHMARK_TEXT+"// changed\n"));
        assertThrows(com.intellij.execution.ExecutionException.class,()->GoTestContextSelection.validateBeforeRun(copy));
    }

}

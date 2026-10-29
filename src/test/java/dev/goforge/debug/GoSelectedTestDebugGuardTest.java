package dev.goforge.debug;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationProvider;
import dev.goforge.settings.GoForgeSettings;
import dev.goforge.testing.GoTestRunRegistry;
import dev.goforge.testing.GoTestSourceIndex;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Real Documents, disk saves, DAP clones and the descriptor's final source guard. No debugger is spawned. */
public final class GoSelectedTestDebugGuardTest extends BasePlatformTestCase {
    private static final String SOURCE = "package target\nimport \"testing\"\nfunc TestOne(t *testing.T) {}\n";
    private Path directory;
    private VirtualFile root;
    private VirtualFile file;
    private Document document;

    @Override protected void setUp() throws Exception {
        super.setUp();
        Path projectBase = Files.createDirectories(Path.of(getProject().getBasePath()));
        assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectBase));
        directory = Files.createTempDirectory(projectBase, "selected-debug-guard-").toRealPath();
        Files.writeString(directory.resolve("fixture_test.go"), SOURCE);
        root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory);
        assertNotNull(root);
        PsiTestUtil.addContentRoot(getModule(), root);
        file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory.resolve("fixture_test.go"));
        assertNotNull(file);
        document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(document));
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (document != null) FileDocumentManager.getInstance().saveDocument(document);
            if (root != null) PsiTestUtil.removeContentEntry(getModule(), root);
            if (directory != null) FileUtil.delete(directory.toFile());
        } finally { super.tearDown(); }
    }

    public void testCleanMappedCachedDocumentCanReachTheDebugValidationBoundary() throws Exception {
        var guard = GoSelectedTestDebugGuard.capture(getProject(), sources());
        guard.validate();
        var configuration = configuration();
        guard.bind(configuration);
        GoSelectedTestDebugGuard.validate(environment(configuration));
        assertSame(document, FileDocumentManager.getInstance().getCachedDocument(file));
        assertEquals(SOURCE, Files.readString(directory.resolve("fixture_test.go")));
    }

    public void testDirtyDocumentCannotBeCapturedEvenWhenDiskStillMatchesTheIndex() throws Exception {
        WriteCommandAction.writeCommandAction(getProject()).run(() -> {
            document.setText(SOURCE + "// unsaved edit\n");
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document));
            assertEquals(SOURCE, Files.readString(directory.resolve("fixture_test.go")));
            assertThrows(ExecutionException.class, () -> GoSelectedTestDebugGuard.capture(getProject(), sources()));
        });
    }

    public void testEditThenActualSaveStillRefusesThePreviouslyPreparedDescriptor() throws Exception {
        var configuration = configuration();
        GoSelectedTestDebugGuard.capture(getProject(), sources()).bind(configuration);
        var environment = environment(configuration);
        String changed = SOURCE.replace("TestOne", "TestRenamed");
        edit(changed);
        FileDocumentManager.getInstance().saveDocument(document);
        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(document));
        assertEquals(changed, Files.readString(directory.resolve("fixture_test.go")));
        assertThrows(ExecutionException.class, () -> GoSelectedTestDebugGuard.validate(environment));

        // An absent, explicit tool path prevents a real process even if this regression reappears.
        // The selected-source error must occur before tool resolution in the actual descriptor.
        String previousDelve = GoForgeSettings.getInstance().delvePath;
        GoForgeSettings.getInstance().delvePath = directory.resolve("intentionally-absent-delve").toString();
        try {
            var state = (DAPCommandLineState) configuration.getState(environment.getExecutor(), environment);
            var descriptor = state.getServerDescriptor();
            assertInstanceOf(descriptor, DelveDebugAdapterDescriptor.class);
            try {
                descriptor.startServer();
                fail("Changed selected test sources must be rejected before starting Delve");
            } catch (ExecutionException error) {
                assertTrue(error.getMessage(), error.getMessage().contains("selected test sources"));
            }
        } finally { GoForgeSettings.getInstance().delvePath = previousDelve; }
    }

    public void testActualDapConfigurationCloneRetainsTheGuard() throws Exception {
        var configuration = configuration();
        GoSelectedTestDebugGuard.capture(getProject(), sources()).bind(configuration);
        var clone = (DAPRunConfiguration) configuration.clone();
        assertNotSame(configuration, clone);
        GoSelectedTestDebugGuard.validate(environment(clone));
        edit(SOURCE + "// changed after clone\n");
        assertThrows(ExecutionException.class, () -> GoSelectedTestDebugGuard.validate(environment(clone)));
        assertThrows(ExecutionException.class, () -> GoSelectedTestDebugGuard.validate(environment(configuration)));
    }

    public void testEmptyUncachedAndMismatchedSourcesCannotAuthorizeDebug() throws Exception {
        assertThrows(ExecutionException.class, () -> GoSelectedTestDebugGuard.capture(getProject(), Map.of()));
        assertThrows(ExecutionException.class, () -> GoSelectedTestDebugGuard.capture(getProject(), sources(List.of())));

        Path uncached = directory.resolve("unopened_test.go");
        Files.writeString(uncached, SOURCE);
        var uncachedFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(uncached);
        assertNotNull(uncachedFile);
        assertNull(FileDocumentManager.getInstance().getCachedDocument(uncachedFile));
        var unknown = snapshot(uncached, SOURCE);
        assertThrows(ExecutionException.class, () -> GoSelectedTestDebugGuard.capture(getProject(), sources(List.of(unknown))));
        assertThrows(ExecutionException.class, () -> GoSelectedTestDebugGuard.capture(getProject(),
                sources(List.of(snapshot(directory.resolve("fixture_test.go"), SOURCE), unknown))));
        assertThrows(ExecutionException.class, () -> GoSelectedTestDebugGuard.capture(getProject(),
                sources(List.of(snapshot(directory.resolve("fixture_test.go"), SOURCE + "// different index\n")))));
    }

    public void testOrdinaryDapConfigurationHasNoSelectedSourceConstraint() throws Exception {
        var configuration = configuration();
        edit(SOURCE + "// ordinary debug is not a captured selected test\n");
        GoSelectedTestDebugGuard.validate(environment(configuration));
        GoSelectedTestDebugGuard.validate(environment((DAPRunConfiguration) configuration.clone()));
    }

    private void edit(String text) {
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(text));
    }

    private Map<String, GoTestRunRegistry.PackageSources> sources() {
        return sources(List.of(snapshot(directory.resolve("fixture_test.go"), SOURCE)));
    }

    private Map<String, GoTestRunRegistry.PackageSources> sources(List<GoTestSourceIndex.Snapshot> files) {
        return Map.of("example.com/selected-debug", new GoTestRunRegistry.PackageSources(directory, files));
    }

    private static GoTestSourceIndex.Snapshot snapshot(Path file, String text) {
        var declaration = new GoTestSourceIndex.Declaration("TestOne", "test", text.indexOf("func "),
                text.indexOf('}', text.indexOf("func ")) + 1, text.indexOf("TestOne"));
        return new GoTestSourceIndex.Snapshot(file, GoTestSourceIndex.sha256(text.getBytes(StandardCharsets.UTF_8)), text, List.of(declaration));
    }

    private DAPRunConfiguration configuration() {
        var factory = new DAPRunConfigurationProvider().getConfigurationFactory();
        var configuration = (DAPRunConfiguration) factory.createTemplateConfiguration(getProject());
        configuration.setServerId("goforge.delve");
        configuration.setDebugMode(DebugMode.LAUNCH);
        configuration.setWorkingDirectory(directory.toString());
        configuration.setLaunchConfiguration("{\"type\":\"go\",\"request\":\"launch\",\"mode\":\"test\",\"program\":\".\"}");
        return configuration;
    }

    private ExecutionEnvironment environment(DAPRunConfiguration configuration) throws ExecutionException {
        return ExecutionEnvironmentBuilder.create(getProject(), DefaultDebugExecutor.getDebugExecutorInstance(), configuration).build();
    }
}

package dev.goforge.debug;

import com.google.gson.JsonParser;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.openapi.util.io.FileUtil;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import dev.goforge.run.GoRunConfigurationType;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

public final class GoDebugConfigurationsTest extends BasePlatformTestCase {
    private Set<RunnerAndConfigurationSettings> original;
    private RunnerAndConfigurationSettings originalSelected;

    @Override protected void setUp() throws Exception {
        super.setUp();
        var manager = RunManager.getInstance(getProject());
        original = new HashSet<>(manager.getAllSettings());
        originalSelected = manager.getSelectedConfiguration();
    }

    @Override protected void tearDown() throws Exception {
        try {
            var manager = RunManager.getInstance(getProject());
            for (var settings : manager.getAllSettings())
                if (!original.contains(settings)) manager.removeConfiguration(settings);
            manager.setSelectedConfiguration(originalSelected);
        } finally { super.tearDown(); }
    }

    public void testApplicationCreatesPackageAndTestConfigurationsWithEscapedAbsolutePaths() throws Exception {
        Path directory = Files.createDirectory(tempDirectory().resolve("Go project \"quoted\" 世界")).toRealPath();
        var created = GoDebugConfigurations.addForProject(getProject(), directory, true);
        assertEquals(2, created.size());
        assertConfiguration(created.get(0), directory, false);
        assertConfiguration(created.get(1), directory, true);
    }

    public void testLibraryOnlyCreatesTestDebugConfiguration() throws Exception {
        Path directory = Files.createDirectory(tempDirectory().resolve("Go library")).toRealPath();
        var created = GoDebugConfigurations.addForProject(getProject(), directory, false);
        assertEquals(1, created.size());
        assertConfiguration(created.getFirst(), directory, true);
    }

    public void testAddingDebugConfigurationsPreservesSelectedRunConfiguration() throws Exception {
        var manager = RunManager.getInstance(getProject());
        var factory = ConfigurationTypeUtil.findConfigurationType(GoRunConfigurationType.class).getConfigurationFactories()[0];
        var run = manager.createConfiguration("Run new project", factory);
        manager.addConfiguration(run);
        manager.setSelectedConfiguration(run);
        GoDebugConfigurations.addForProject(getProject(), tempDirectory(), true);
        assertSame(run, manager.getSelectedConfiguration());
    }

    public void testSymlinkProjectDirectoryUsesCanonicalCwdAndProgram() throws Exception {
        Path root = tempDirectory();
        Path real = Files.createDirectory(root.resolve("real project")).toRealPath();
        Path link = Files.createSymbolicLink(root.resolve("linked project"), real);
        var created = GoDebugConfigurations.addForProject(getProject(), link, true);
        assertEquals(2, created.size());
        assertConfiguration(created.get(0), real, false);
        assertConfiguration(created.get(1), real, true);
    }

    public void testMissingProjectDirectoryDoesNotRegisterDebugConfigurations() throws Exception {
        var manager = RunManager.getInstance(getProject());
        var before = new HashSet<>(manager.getAllSettings());
        try {
            GoDebugConfigurations.addForProject(getProject(), tempDirectory().resolve("missing"), true);
            fail("Missing project directories cannot produce runnable debug configurations");
        } catch (java.nio.file.NoSuchFileException expected) {
            assertEquals(before, new HashSet<>(manager.getAllSettings()));
        }
    }

    private Path tempDirectory() throws Exception {
        return FileUtil.createTempDirectory("goforge-debug-config-", null, true).toPath();
    }

    private void assertConfiguration(RunnerAndConfigurationSettings settings, Path directory, boolean tests) throws Exception {
        assertTrue(RunManager.getInstance(getProject()).getAllSettings().contains(settings));
        var configuration = assertInstanceOf(settings.getConfiguration(), DAPRunConfiguration.class);
        assertEquals("DAPConfiguration", configuration.getType().getId());
        assertEquals("goforge.delve", configuration.getServerId());
        assertEquals(DebugMode.LAUNCH, configuration.getDebugMode());
        assertEquals(directory.toString(), configuration.getWorkingDirectory());
        assertEquals(tests ? "goforge_tests" : "goforge_package", configuration.getLaunchConfigurationId());
        var launch = JsonParser.parseString(configuration.getLaunchConfiguration()).getAsJsonObject();
        assertEquals("go", launch.get("type").getAsString());
        assertEquals("launch", launch.get("request").getAsString());
        assertEquals(tests ? "test" : "debug", launch.get("mode").getAsString());
        assertEquals(directory.toString(), launch.get("program").getAsString());
        assertEquals(directory.toString(), launch.get("cwd").getAsString());
        configuration.checkConfiguration();
    }
}

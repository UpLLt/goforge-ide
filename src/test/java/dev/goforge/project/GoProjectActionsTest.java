package dev.goforge.project;

import com.intellij.execution.RunManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.openapi.util.io.FileUtil;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import dev.goforge.run.GoCommand;
import dev.goforge.run.GoRunConfiguration;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import com.intellij.execution.RunnerAndConfigurationSettings;

public final class GoProjectActionsTest extends BasePlatformTestCase {
    private Set<RunnerAndConfigurationSettings> original;
    private RunnerAndConfigurationSettings originalSelected;

    @Override protected void setUp() throws Exception {
        super.setUp();
        original = new HashSet<>(RunManager.getInstance(getProject()).getAllSettings());
        originalSelected = RunManager.getInstance(getProject()).getSelectedConfiguration();
    }

    @Override protected void tearDown() throws Exception {
        try {
            var manager = RunManager.getInstance(getProject());
            for (var setting : manager.getAllSettings()) if (!original.contains(setting)) manager.removeConfiguration(setting);
            manager.setSelectedConfiguration(originalSelected);
        } finally { super.tearDown(); }
    }
    public void testProjectActionIsRegisteredWithoutAnAccountContext() {
        assertInstanceOf(ActionManager.getInstance().getAction("GoForge.NewProject"), NewGoProjectAction.class);
    }

    public void testApplicationHasRealRunTestAndBuildConfigurations() throws Exception {
        Path directory = FileUtil.createTempDirectory("Go project ", null, true).toPath().toRealPath();
        NewGoProjectAction.configureRuns(getProject(), directory, GoProjectScaffolder.Template.APPLICATION);
        var manager = RunManager.getInstance(getProject());
        var runs = manager.getAllSettings().stream().map(s -> s.getConfiguration())
            .filter(GoRunConfiguration.class::isInstance).map(GoRunConfiguration.class::cast).toList();
        assertEquals(3, runs.size());
        assertTrue(runs.stream().anyMatch(c -> c.getCommand() == GoCommand.TEST && "./...".equals(c.getTarget())));
        assertTrue(runs.stream().allMatch(c -> directory.toString().equals(c.getDirectory())));
        assertEquals(GoCommand.RUN, ((GoRunConfiguration) manager.getSelectedConfiguration().getConfiguration()).getCommand());
        var debug = manager.getAllSettings().stream().map(s -> s.getConfiguration())
                .filter(DAPRunConfiguration.class::isInstance).map(DAPRunConfiguration.class::cast).toList();
        assertEquals(2, debug.size());
        assertTrue(debug.stream().allMatch(c -> "goforge.delve".equals(c.getServerId())));
    }

    public void testLibraryDefaultsToTestsWithoutOfferingAnInvalidMainRun() throws Exception {
        Path directory = FileUtil.createTempDirectory("Go library ", null, true).toPath().toRealPath();
        NewGoProjectAction.configureRuns(getProject(), directory, GoProjectScaffolder.Template.LIBRARY);
        var manager = RunManager.getInstance(getProject());
        var runs = manager.getAllSettings().stream().map(s -> s.getConfiguration())
            .filter(GoRunConfiguration.class::isInstance).map(GoRunConfiguration.class::cast).toList();
        assertEquals(2, runs.size());
        assertFalse(runs.stream().anyMatch(c -> c.getCommand() == GoCommand.RUN));
        assertEquals(GoCommand.TEST, ((GoRunConfiguration) manager.getSelectedConfiguration().getConfiguration()).getCommand());
        var debug = manager.getAllSettings().stream().map(s -> s.getConfiguration())
                .filter(DAPRunConfiguration.class::isInstance).map(DAPRunConfiguration.class::cast).toList();
        assertEquals(1, debug.size());
        assertEquals("goforge_tests", debug.getFirst().getLaunchConfigurationId());
    }
}

package dev.goforge.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class GoTestProgramRunnerTest extends BasePlatformTestCase {
    private GoRunConfiguration configuration() {
        var factory = ConfigurationTypeUtil.findConfigurationType(GoRunConfigurationType.class).getConfigurationFactories()[0];
        var config = new GoRunConfiguration(getProject(), factory, "test prepared");
        config.setCommand(GoCommand.TEST);
        return config;
    }

    public void testActualRegisteredRunnerRoutesOnlyGoTestRuns() {
        var config = configuration();
        var runner = ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, config);
        assertNotNull(runner);
        assertEquals(GoTestProgramRunner.ID, runner.getRunnerId());
        assertFalse(runner.canRun(DefaultDebugExecutor.EXECUTOR_ID, config));
        config.setCommand(GoCommand.RUN);
        assertFalse(runner.canRun(DefaultRunExecutor.EXECUTOR_ID, config));
    }

    public void testActualStateCannotStartProcessWithoutBackgroundPreparation() throws Exception {
        var config = configuration();
        var executor = DefaultRunExecutor.getRunExecutorInstance();
        var environment = ExecutionEnvironmentBuilder.create(executor, config).build();
        var state = config.getState(executor, environment);
        assertInstanceOf(state, GoTestCommandLineState.class);
        try {
            state.execute(executor, new GoTestProgramRunner());
            fail("Unprepared test state started a process");
        } catch (ExecutionException expected) {
            assertTrue(expected.getMessage().contains("discovery has not completed"));
        }
    }

    public void testDiscoveryRejectsDispatchThreadBeforeToolInvocation() throws Exception {
        var config = configuration();
        var executor = DefaultRunExecutor.getRunExecutorInstance();
        var environment = ExecutionEnvironmentBuilder.create(executor, config).build();
        var state = (GoTestCommandLineState) config.getState(executor, environment);
        assertThrows(IllegalStateException.class, () -> state.prepare(() -> false));
    }
}

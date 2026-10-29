package dev.goforge.debug;

import com.google.gson.JsonParser;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import dev.goforge.run.GoCommand;
import dev.goforge.run.GoRunConfiguration;
import dev.goforge.run.GoRunConfigurationType;
import dev.goforge.testing.GoTestSelection;

import java.util.List;

public final class GoSelectedTestDebugConfigurationTest extends BasePlatformTestCase {
    public void testCreatesIndependentDelveTestConfigurationFromSelectedRun() {
        var factory = ConfigurationTypeUtil.findConfigurationType(GoRunConfigurationType.class).getConfigurationFactories()[0];
        var source = new GoRunConfiguration(getProject(), factory, "test origin");
        source.setCommand(GoCommand.TEST);
        source.setDirectory("/generated/test project");
        source.setTarget(".");
        source.setTestSelection(new GoTestSelection(List.of("TestCases/dup#01"), true));
        source.getEnvironment().put("GOFORGE_TEST_MARKER", "kept");
        var launch = GoSelectedTestDebugPlan.launch(source.getDirectory(), source.getTestSelection(), List.of());
        var settings = GoDebugSelectedTestAction.createConfiguration(source, source.getDirectory(), launch);
        var debug = (DAPRunConfiguration) settings.getConfiguration();
        assertEquals("goforge.delve", debug.getServerId());
        assertEquals(source.getDirectory(), debug.getWorkingDirectory());
        assertTrue(debug.getEnvData().isPassParentEnvs());
        assertEquals("kept", debug.getEnvData().getEnvs().get("GOFORGE_TEST_MARKER"));
        source.getEnvironment().put("GOFORGE_TEST_MARKER", "changed later");
        assertEquals("kept", debug.getEnvData().getEnvs().get("GOFORGE_TEST_MARKER"));
        var payload = JsonParser.parseString(debug.getLaunchConfiguration()).getAsJsonObject();
        assertEquals("test", payload.get("mode").getAsString());
        assertEquals("-test.run=^TestCases$/^dup#01$", payload.getAsJsonArray("args").get(0).getAsString());
        assertEquals("test origin", source.getName());
        assertEquals(".", source.getTarget());
    }
}

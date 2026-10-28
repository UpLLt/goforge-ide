package dev.goforge.debug;

import com.google.gson.JsonParser;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import dev.goforge.remote.*;
import java.util.*;

public final class GoRemoteDebugConfigurationsTest extends BasePlatformTestCase {
    private GoRemoteDebugPlan plan(String mode){return new GoRemoteDebugPlan(new GoRemoteProfile("fixture.invalid","",2222,"","/workspace","go",""),"/local","dlv",mode,".",mode.equals("attach")?42:0,Map.of("go.mod","a".repeat(64)),List.of(),mode.equals("attach")?Map.of():Map.of("TOKEN","fixture-value-not-persisted"));}
    public void testConfigurationPersistsOnlySessionIdAndNoCoordinatesOrEnvironment(){
        var plan=plan("debug");var setting=GoRemoteDebugConfigurations.create(getProject(),plan);var config=(DAPRunConfiguration)setting.getConfiguration();
        assertEquals("goforge.remoteDelve",config.getServerId());assertEquals(DebugMode.LAUNCH,config.getDebugMode());
        var json=JsonParser.parseString(config.getLaunchConfiguration()).getAsJsonObject();assertEquals(Set.of("type","request","goforgeRemoteSession"),json.keySet());
        assertFalse(config.getLaunchConfiguration().contains("fixture.invalid"));assertFalse(config.getLaunchConfiguration().contains("fixture-value-not-persisted"));
        assertSame(plan,GoRemoteDebugSessions.getInstance(getProject()).require(json.get("goforgeRemoteSession").getAsString()));
    }
    public void testAttachDescriptorStartsOwnedSshButUsesAttachClientSemantics()throws Exception {
        var setting=GoRemoteDebugConfigurations.create(getProject(),plan("attach"));var config=(DAPRunConfiguration)setting.getConfiguration();
        var environment=ExecutionEnvironmentBuilder.create(getProject(),DefaultDebugExecutor.getDebugExecutorInstance(),config).build();
        var state=(com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState)config.getState(DefaultDebugExecutor.getDebugExecutorInstance(),environment);
        assertInstanceOf(config,RemoteDapRunConfiguration.class);assertInstanceOf(config.clone(),RemoteDapRunConfiguration.class);
        var console=state.getConsoleBuilder().getConsole();
        try{assertInstanceOf(console,RemoteDapConsoleView.class);}finally{com.intellij.openapi.util.Disposer.dispose(console);}
        var descriptor=(RemoteDelveDebugAdapterDescriptor)state.getServerDescriptor();
        assertEquals(DebugMode.LAUNCH,state.getDebugMode());assertEquals(DebugMode.ATTACH,descriptor.clientMode());assertEquals("attach",state.getDAPParameters().get("request"));assertEquals("local",state.getDAPParameters().get("mode"));assertEquals(42,state.getDAPParameters().get("processId"));assertNull(descriptor.getServerReadyConfig(DebugMode.LAUNCH).getPort());
    }
    public void testGenericRestoredConfigurationCannotStartEvenWithLiveSessionId()throws Exception {
        var created=GoRemoteDebugConfigurations.create(getProject(),plan("debug"));
        var factory=new com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationProvider().getConfigurationFactory();
        var restored=(DAPRunConfiguration)factory.createTemplateConfiguration(getProject());
        ((DAPRunConfiguration)created.getConfiguration()).copyTo(restored);
        var environment=ExecutionEnvironmentBuilder.create(getProject(),DefaultDebugExecutor.getDebugExecutorInstance(),restored).build();
        var state=(com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState)restored.getState(DefaultDebugExecutor.getDebugExecutorInstance(),environment);
        var descriptor=(RemoteDelveDebugAdapterDescriptor)state.getServerDescriptor();
        assertEquals("debug",descriptor.getDapParameters().get("mode")); // The session itself is still live.
        try{descriptor.startServer();fail("generic restoration must require review before spawning SSH");}
        catch(com.intellij.execution.ExecutionException expected){assertTrue(expected.getMessage().contains("Go Remote"));}
    }
    public void testChangingAdapterRetainsItsStandardConsoleAndOrdinaryStdout()throws Exception {
        var created=GoRemoteDebugConfigurations.create(getProject(),plan("debug"));
        var config=(DAPRunConfiguration)created.getConfiguration();config.setServerId("goforge.delve");
        var environment=ExecutionEnvironmentBuilder.create(getProject(),DefaultDebugExecutor.getDebugExecutorInstance(),config).build();
        var state=(com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState)config.getState(DefaultDebugExecutor.getDebugExecutorInstance(),environment);
        assertFalse(state.getServerDescriptor() instanceof RemoteDelveDebugAdapterDescriptor);
        var console=(com.intellij.execution.impl.ConsoleViewImpl)state.getConsoleBuilder().getConsole();
        try{
            assertEquals(com.redhat.devtools.lsp4ij.dap.console.DAPConsoleView.class,console.getClass());
            console.getComponent();console.print("Local adapter listening\n",com.intellij.execution.ui.ConsoleViewContentType.NORMAL_OUTPUT);
            console.flushDeferredText();assertEquals("Local adapter listening\n",console.getText());
        }finally{com.intellij.openapi.util.Disposer.dispose(console);}
    }
    public void testLaunchParametersDoNotExposeEnvironmentToClientProtocolLogging()throws Exception {
        var setting=GoRemoteDebugConfigurations.create(getProject(),plan("debug"));var config=(DAPRunConfiguration)setting.getConfiguration();
        var environment=ExecutionEnvironmentBuilder.create(getProject(),DefaultDebugExecutor.getDebugExecutorInstance(),config).build();
        var state=(com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState)config.getState(DefaultDebugExecutor.getDebugExecutorInstance(),environment);
        assertFalse(state.getDAPParameters().containsKey("env"));assertFalse(state.getDAPParameters().containsKey("args"));
    }
    public void testExpiredSessionRequiresExplicitReviewInsteadOfConnectingRestoredProfile(){var sessions=new GoRemoteDebugSessions();String id=sessions.register(plan("debug"));sessions.dispose();try{sessions.require(id);fail("expired review must not reconnect");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("expired"));}}
}

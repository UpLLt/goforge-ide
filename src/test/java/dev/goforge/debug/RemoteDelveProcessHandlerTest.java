package dev.goforge.debug;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Key;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.goforge.remote.GoRemoteDebugPlan;
import dev.goforge.remote.GoRemoteProfile;
import org.jetbrains.annotations.NotNull;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real platform process handler and real harmless child. No SSH host/GUI/remote target is started. */
public final class RemoteDelveProcessHandlerTest extends BasePlatformTestCase {
    public void testDisplaySummaryDoesNotPrintCommandOrPrivateStdinAndProcessStillExchangesDataAndStops()throws Exception {
        var run=ApplicationManager.getApplication().executeOnPooledThread(()->{
            java.nio.file.Path classes=java.nio.file.Files.createTempDirectory("goforge-bridge-child-");
            java.nio.file.Path bytecode=classes.resolve("dev/goforge/debug/RemoteBridgeChild.class");
            java.nio.file.Files.createDirectories(bytecode.getParent());
            try(var input=RemoteBridgeChild.class.getResourceAsStream("RemoteBridgeChild.class")) {
                assertNotNull(input);java.nio.file.Files.copy(input,bytecode);
            }
            String javaExecutable=java.nio.file.Path.of(System.getProperty("java.home"),"bin",dev.goforge.tools.HostTools.executableName("java")).toString();
            var command=new GeneralCommandLine(javaExecutable,"-cp",classes.toString(),RemoteBridgeChild.class.getName(),"GOFORGE_BRIDGE_SOURCE_MARKER").withCharset(StandardCharsets.UTF_8);
            var handler=RemoteDelveDebugAdapterDescriptor.createBridgeProcessHandler(command,"Remote Delve via SSH fixture.invalid:2222 (package)");
            var system=new StringBuffer();var output=new StringBuffer();var ready=new CountDownLatch(1);var acknowledged=new CountDownLatch(1);
            handler.addProcessListener(new ProcessAdapter(){
                @Override public void onTextAvailable(@NotNull ProcessEvent event,@NotNull Key outputType){
                    if(ProcessOutputTypes.SYSTEM.equals(outputType))system.append(event.getText());
                    else {output.append(event.getText());if(output.toString().contains("READY"))ready.countDown();if(output.toString().contains("ACK"))acknowledged.countDown();}
                }
            });
            try {
                handler.startNotify();assertTrue("The actual command must run and produce output",ready.await(5,TimeUnit.SECONDS));
                assertEquals("Remote Delve via SSH fixture.invalid:2222 (package)",handler.getCommandLine());
                assertEquals(handler.getCommandLine(),handler.getCommandLineForLog());
                assertTrue(system.toString().contains(handler.getCommandLine()));assertFalse(system.toString().contains("GOFORGE_BRIDGE_SOURCE_MARKER"));assertFalse(system.toString().contains(RemoteBridgeChild.class.getName()));
                var input=handler.getProcessInput();assertNotNull(input);
                input.write("{\"files\":{\"private-source.go\":\"fixture-manifest\"},\"env\":{\"TOKEN\":\"fixture-private-value\"}}\n".getBytes(StandardCharsets.UTF_8));input.flush();
                assertTrue("The real stdin must remain writable",acknowledged.await(5,TimeUnit.SECONDS));
                handler.destroyProcess();assertTrue("Stop must still terminate the actual child",handler.waitFor(5000));assertFalse(handler.getProcess().isAlive());
                String console=system.toString()+output;assertFalse(console.contains("fixture-private-value"));assertFalse(console.contains("fixture-manifest"));assertFalse(console.contains("private-source.go"));
            }finally{
                if(handler.getProcess().isAlive()){handler.destroyProcess();handler.getProcess().destroyForcibly();handler.waitFor(5000);}
                try(var files=java.nio.file.Files.walk(classes)){for(var path:files.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}
            }
            return null;
        });
        run.get(20,TimeUnit.SECONDS);
    }
    public void testSummaryContainsOnlyEndpointAndModeNotArgumentsOrEnvironment(){
        var profile=new GoRemoteProfile("fixture.invalid","",2222,"","/private-workspace","go","");
        var launch=new GoRemoteDebugPlan(profile,"/local","dlv","exec","out/app",0,Map.of("go.mod","a".repeat(64)),List.of("private-argument"),Map.of("TOKEN","private-env"));
        assertEquals("Remote Delve via SSH fixture.invalid:2222 (executable)",RemoteDelveDebugAdapterDescriptor.displayLabel(launch));
        var attach=new GoRemoteDebugPlan(profile,"/local","dlv","attach",".",123,Map.of("go.mod","a".repeat(64)),List.of(),Map.of());
        assertEquals("Remote Delve via SSH fixture.invalid:2222 (attach PID 123)",RemoteDelveDebugAdapterDescriptor.displayLabel(attach));
    }
}

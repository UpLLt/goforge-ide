package dev.goforge.debug;

import com.intellij.execution.process.NopProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.TransportStreams;
import com.redhat.devtools.lsp4ij.dap.client.DAPClient;
import com.redhat.devtools.lsp4ij.dap.console.DAPConsoleView;
import com.redhat.devtools.lsp4ij.settings.ServerTrace;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.function.Supplier;

public final class RemoteDapConsoleViewTest extends BasePlatformTestCase {
    private RemoteDapConsoleView console;

    @Override protected void setUp() throws Exception {
        super.setUp();
        console = new RemoteDapConsoleView(getProject(), GlobalSearchScope.allScope(getProject()), true, false);
        console.getComponent();
    }

    @Override protected void tearDown() throws Exception {
        try { Disposer.dispose(console); } finally { super.tearDown(); }
    }

    public void testNativeVariablesPayloadEscapesUpstreamHeuristicButNotRemoteConsole() {
        String payload = "{\"seq\":22,\"type\":\"response\",\"command\":\"variables\",\"body\":{\"variables\":[{\"name\":\"t\",\"value\":\"*testing.T {common: testing.common {mu: (*sync.RWMutex)(0x1), ...\"}]}}";
        // The real pinned upstream class counts braces inside JSON strings and leaks this payload.
        assertEquals(payload, DAPConsoleView.getApplicableText(payload, ConsoleViewContentType.NORMAL_OUTPUT));
        console.print(payload, ConsoleViewContentType.NORMAL_OUTPUT);
        assertEquals("", text());
    }

    public void testFragmentedTransportRemainsAvailableWhileBridgeStderrAndStatusAreVisible() throws Exception {
        var process = new NopProcessHandler();
        // The upstream listener is package-private. Use its actual implementation to verify that
        // console classification cannot consume or change the independent protocol stream.
        var listenerClass = Class.forName("com.redhat.devtools.lsp4ij.dap.DAPProcessListener");
        var constructor = listenerClass.getDeclaredConstructor();constructor.setAccessible(true);
        var listener = (ProcessListener) constructor.newInstance();
        var inputMethod = listenerClass.getDeclaredMethod("getInputStream");inputMethod.setAccessible(true);
        var input = (InputStream) inputMethod.invoke(listener);
        process.addProcessListener(listener);
        console.attachToProcess(process);process.startNotify();
        String[] fragments = {"Content-Len", "gth: 67\r\n\r\n{\"type\":", "\"response\",\"value\":\"{世界", "...\"}", "\n"};
        for (String fragment : fragments) process.notifyTextAvailable(fragment, ProcessOutputTypes.STDOUT);
        process.notifyTextAvailable("SSH bridge diagnostic 世界\n", ProcessOutputTypes.STDERR);
        process.notifyTextAvailable("Disconnected successfully\n", ProcessOutputTypes.SYSTEM);
        byte[] bytes = String.join("", fragments).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try { assertEquals(String.join("", fragments), new String(input.readNBytes(bytes.length), java.nio.charset.StandardCharsets.UTF_8)); }
        finally { process.destroyProcess();input.close(); }
        assertEquals("SSH bridge diagnostic 世界\nDisconnected successfully\n", text());
    }

    public void testActualDapClientOutputEventsPreserveJsonBracesHeadersAndAllCategories() throws Exception {
        // Allocate only the test print/transport adapter without starting DAPDebugProcess's background
        // connection in its constructor. The production DAPClient constructor and output method run.
        // This exercises event-to-console routing, not a real SSH session or debug adapter.
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        var process = (OutputProcess) ((sun.misc.Unsafe)field.get(null)).allocateInstance(OutputProcess.class);
        process.console = console;
        Project project = getProject();
        var client = new DAPClient(process, Map.of(), true, DebugMode.LAUNCH, ServerTrace.off, null) {
            @Override public Project getProject() { return project; }
        };
        try {
            String[] categories = {"stdout", "stderr", "console", "important", "telemetry", null};
            StringBuilder expected = new StringBuilder();
            for (int index = 0; index < categories.length; index++) {
                String output = index + ": {\"program\":\"{世界\"}\nContent-Length: literal program text\n";
                var event = new OutputEventArguments();event.setCategory(categories[index]);event.setOutput(output);
                client.output(event);expected.append(output);
            }
            assertEquals(expected.toString(), text());
        } finally { client.dispose(); }
    }

    private String text() {
        // Process stdout/stderr ordering has its own pooled Alarm, independent of the EDT flush.
        // Wait for the actual upstream queue rather than assuming one EDT dispatch drains it.
        if (console.getState() instanceof com.intellij.execution.impl.ConsoleViewRunningState state)
            state.getStreamsSynchronizer().waitForAllFlushed();
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();console.flushDeferredText();
        return console.getText();
    }

    public static final class OutputProcess extends DAPDebugProcess {
        private RemoteDapConsoleView console;
        private OutputProcess() { super(null, null, null, true); }
        @Override public Supplier<TransportStreams> getStreamsSupplier() {
            return () -> new TransportStreams.DefaultTransportStreams(InputStream.nullInputStream(), OutputStream.nullOutputStream());
        }
        @Override public void print(String message, ConsoleViewContentType type) { console.print(message, type); }
    }
}

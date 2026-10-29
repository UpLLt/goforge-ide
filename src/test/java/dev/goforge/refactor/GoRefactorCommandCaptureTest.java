package dev.goforge.refactor;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.Test;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Controlled subprocess tests; the separate probe exercises real gopls. */
public class GoRefactorCommandCaptureTest {
    private static final String ORIGINAL = "package demo\n\nfunc F() {}\n";

    @Test public void rejectsOtherCommandsBeforeStartingAnyProcess() throws Exception {
        try (var fixture = new Fixture()) {
            var command = fixture.command(); command.setCommand("gopls.run_tests");
            assertThrows(IllegalArgumentException.class, () -> fixture.capture(List.of("/missing-capture-process"), command, () -> false, Duration.ofSeconds(2)));
            assertFalse(Files.exists(fixture.pid)); fixture.assertUnchanged();
        }
    }
    @Test public void rejectsOutsideLocationAndMalformedRangeBeforeStartingAnyProcess() throws Exception {
        try (var fixture = new Fixture()) {
            var command = new Command("invalid", GoRefactorCommandCapture.EXTRACT_TO_NEW_FILE,
                    List.of(new Location(fixture.root.resolveSibling("outside.go").toUri().toString(), new Range(new Position(0, 0), new Position(0, 1)))));
            assertThrows(IllegalArgumentException.class, () -> fixture.capture(List.of("/missing-capture-process"), command, () -> false, Duration.ofSeconds(2)));
            command.setArguments(List.of(new Location(fixture.file.toUri().toString(), new Range(new Position(20, 0), new Position(20, 1)))));
            assertThrows(IllegalArgumentException.class, () -> fixture.capture(List.of("/missing-capture-process"), command, () -> false, Duration.ofSeconds(2)));
            assertFalse(Files.exists(fixture.pid)); fixture.assertUnchanged();
        }
    }
    @Test public void cancellationBeforeStartDoesNotSpawn() throws Exception {
        try (var fixture = new Fixture()) {
            assertThrows(CancellationException.class, () -> fixture.capture(List.of("/missing-capture-process"), fixture.command(), () -> true, Duration.ofSeconds(2)));
            assertFalse(Files.exists(fixture.pid)); fixture.assertUnchanged();
        }
    }
    @Test(timeout=15000) public void capturesOneEditDeclinesItAndPreservesAllDiskFiles() throws Exception {
        try (var fixture = new Fixture()) {
            WorkspaceEdit edit = fixture.capture("success", () -> false, Duration.ofSeconds(6));
            assertEquals(3, edit.getDocumentChanges().size());
            assertEquals("create", edit.getDocumentChanges().get(1).getRight().getKind());
            fixture.assertStopped(); fixture.assertUnchanged(); assertFalse(Files.exists(fixture.root.resolve("new.go")));
        }
    }
    @Test(timeout=15000) public void repeatedApplyRequestsAreRejected() throws Exception {
        try (var fixture = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> fixture.capture("twice", () -> false, Duration.ofSeconds(6)));
            fixture.assertStopped(); fixture.assertUnchanged();
        }
    }
    @Test(timeout=15000) public void commandSuccessWithoutRefusalIsNotAcceptedAsCapturedPreview() throws Exception {
        try (var fixture = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> fixture.capture("success-after-rejection", () -> false, Duration.ofSeconds(6)));
            fixture.assertStopped(); fixture.assertUnchanged();
        }
    }
    @Test(timeout=15000) public void noEditAndUnrelatedCommandErrorsRemainFailures() throws Exception {
        for (String mode : List.of("no-edit", "wrong-error")) try (var fixture = new Fixture()) {
            assertThrows(org.eclipse.lsp4j.jsonrpc.ResponseErrorException.class, () -> fixture.capture(mode, () -> false, Duration.ofSeconds(6)));
            fixture.assertStopped(); fixture.assertUnchanged();
        }
    }
    @Test(timeout=15000) public void timeoutTerminatesThePrivateProcess() throws Exception {
        try (var fixture = new Fixture()) {
            assertThrows(TimeoutException.class, () -> fixture.capture("hang-initialize", () -> false, Duration.ofMillis(750)));
            fixture.assertStopped(); fixture.assertUnchanged();
        }
    }
    @Test(timeout=15000) public void cancellationStopsBlockedProtocolWritesWithoutSavingOverlays() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.sources = List.of(new GoRefactorWorkspaceEdit.Source(fixture.file.toUri().toString(), ORIGINAL + "//" + "x".repeat(2 * 1024 * 1024), 7), fixture.sources.get(1));
            var cancel = new AtomicBoolean(); ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                var future = worker.submit(() -> fixture.capture("block-documents", cancel::get, Duration.ofSeconds(8)));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Files.exists(fixture.phase) && System.nanoTime() < until) Thread.sleep(10);
                assertTrue("Private fake server never reached the blocked-read phase", Files.exists(fixture.phase));
                var child = ProcessHandle.of(Long.parseLong(Files.readString(fixture.pid))).orElseThrow();
                var processExit = child.onExit();
                Thread.sleep(100); cancel.set(true);
                var failure = assertThrows(ExecutionException.class, () -> future.get(4, TimeUnit.SECONDS));
                assertTrue(failure.getCause().toString(), failure.getCause() instanceof CancellationException);
                assertEquals("The recorded child must reach its OS exit completion", child.pid(), processExit.get(4, TimeUnit.SECONDS).pid());
                fixture.assertStopped(); fixture.assertUnchanged();
            } finally {
                cancel.set(true); worker.shutdownNow();
                assertTrue("Capture test worker did not terminate", worker.awaitTermination(4, TimeUnit.SECONDS));
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("goforge-capture-test-");
        final Path file = root.resolve("source.go"), pid = root.resolve("owned.pid"), phase = root.resolve("blocked.phase");
        List<GoRefactorWorkspaceEdit.Source> sources;
        Fixture() throws IOException {
            Files.writeString(file, ORIGINAL); Files.writeString(root.resolve("caller.go"), "package demo\nfunc Call(){F()}\n");
            sources = List.of(new GoRefactorWorkspaceEdit.Source(file.toUri().toString(), ORIGINAL, 7),
                    new GoRefactorWorkspaceEdit.Source(root.resolve("caller.go").toUri().toString(), Files.readString(root.resolve("caller.go")), 0));
        }
        Command command() { return new Command("Extract declarations to new file", GoRefactorCommandCapture.EXTRACT_TO_NEW_FILE,
                List.of(new Location(file.toUri().toString(), new Range(new Position(2, 5), new Position(2, 6))))); }
        WorkspaceEdit capture(String mode, java.util.function.BooleanSupplier cancel, Duration timeout) throws Exception {
            var jars = new LinkedHashSet<String>();
            for (Class<?> type : List.of(GoRefactorCommandCaptureTest.class, GoRefactorCommandCapture.class, Gson.class, LanguageServer.class, Launcher.class, org.junit.Assert.class))
                jars.add(classPathEntry(type));
            return capture(List.of(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(), "-cp", String.join(File.pathSeparator, jars),
                    FakeServer.class.getName(), mode, pid.toString(), phase.toString()), command(), cancel, timeout);
        }
        WorkspaceEdit capture(List<String> command, Command requested, java.util.function.BooleanSupplier cancel, Duration timeout) throws Exception {
            return GoRefactorCommandCapture.capture(command, System.getenv(), root, sources, requested, cancel, timeout);
        }
        void assertUnchanged() throws IOException {
            assertEquals(ORIGINAL, Files.readString(file)); assertEquals("package demo\nfunc Call(){F()}\n", Files.readString(root.resolve("caller.go")));
            assertFalse(Files.exists(root.resolve("new.go")));
        }
        void assertStopped() throws IOException {
            assertTrue("Child process did not record startup", Files.exists(pid));
            long id = Long.parseLong(Files.readString(pid)); assertFalse("Capture process remains alive: " + id, ProcessHandle.of(id).map(ProcessHandle::isAlive).orElse(false));
        }
        @Override public void close() throws IOException {
            if (Files.exists(pid)) {
                var child = ProcessHandle.of(Long.parseLong(Files.readString(pid)));
                if (child.isPresent() && child.get().isAlive()) {
                    child.get().destroyForcibly();
                    try { child.get().onExit().get(4, TimeUnit.SECONDS); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Interrupted waiting for capture fixture process", failure); }
                    catch (ExecutionException | TimeoutException failure) { throw new IOException("Capture fixture process did not terminate", failure); }
                }
            }
            // Keep traversal lifetime separate from deletion. Exhaustion normally
            // closes directory handles already; this is not a retry for failed deletion.
            List<Path> deletionOrder;
            try (var paths = Files.walk(root)) { deletionOrder = paths.sorted(Comparator.reverseOrder()).toList(); }
            for (var path : deletionOrder) Files.deleteIfExists(path);
        }
    }

    /** IntelliJ's plugin loader may have a CodeSource with no location; class resources retain the actual root. */
    private static String classPathEntry(Class<?> type) throws Exception {
        var domain = type.getProtectionDomain();
        var codeSource = domain == null ? null : domain.getCodeSource();
        URL location = codeSource == null ? null : codeSource.getLocation();
        if (location != null && location.getProtocol().equals("file")) return Path.of(location.toURI()).toString();
        String resourceName = type.getName().replace('.', '/') + ".class";
        URL resource = Objects.requireNonNull(type.getResource("/" + resourceName), "Missing class resource: " + type.getName());
        if (resource.getProtocol().equals("jar")) {
            // IntelliJ installs its own URLConnection implementation for jar:, not JarURLConnection.
            String external = resource.toExternalForm(); int separator = external.indexOf("!/");
            if (separator < 4) throw new IOException("Malformed jar class resource: " + resource);
            return Path.of(URI.create(external.substring(4, separator))).toString();
        }
        if (resource.getProtocol().equals("file")) {
            Path root = Path.of(resource.toURI());
            for (String ignored : resourceName.split("/")) root = root.getParent();
            return root.toString();
        }
        throw new IOException("Unsupported child-process class resource: " + resource);
    }

    /** Deterministic protocol fixture. It records only its own PID/phase, never source contents. */
    public static final class FakeServer {
        private static final Gson GSON = new Gson();
        public static void main(String[] args) throws Exception {
            String mode = args[0]; Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            if (!System.getenv().getOrDefault("GOFLAGS", "").contains("-mod=readonly") || !"local".equals(System.getenv("GOTOOLCHAIN"))) throw new AssertionError("Unrestricted Go environment");
            var input = new BufferedInputStream(System.in); var opened = new LinkedHashMap<String, Integer>();
            Integer executeId = null; int responses = 0; String refusal = null;
            JsonObject message;
            while ((message = read(input)) != null) {
                String method = message.has("method") ? message.get("method").getAsString() : "";
                int id = message.has("id") ? message.get("id").getAsInt() : -1;
                if (method.equals("initialize")) { if (!mode.equals("hang-initialize")) send(Map.of("jsonrpc", "2.0", "id", id, "result", Map.of("capabilities", Map.of()))); }
                else if (method.equals("initialized") && mode.equals("block-documents")) { Files.writeString(Path.of(args[2]), "blocked"); Thread.sleep(30000); }
                else if (method.equals("textDocument/didOpen")) {
                    var doc = message.getAsJsonObject("params").getAsJsonObject("textDocument"); opened.put(doc.get("uri").getAsString(), doc.get("version").getAsInt());
                } else if (method.equals("workspace/executeCommand")) {
                    executeId = id;
                    if (opened.size() != 2 || !opened.values().containsAll(List.of(7, 0))) throw new AssertionError("Snapshot overlays/versions missing");
                    if (mode.equals("no-edit")) { error(id, "no edit was produced"); continue; }
                    var location = message.getAsJsonObject("params").getAsJsonArray("arguments").get(0).getAsJsonObject();
                    String uri = location.get("uri").getAsString(), target = uri.substring(0, uri.lastIndexOf('/') + 1) + "new.go";
                    Map<String,Object> range = Map.of("start", Map.of("line", 2, "character", 0), "end", Map.of("line", 3, "character", 0));
                    var changes = List.of(Map.of("textDocument", Map.of("uri", uri, "version", 7), "edits", List.of(Map.of("range", range, "newText", ""))),
                            Map.of("kind", "create", "uri", target),
                            Map.of("textDocument", Map.of("uri", target, "version", 0), "edits", List.of(Map.of("range", Map.of("start", Map.of("line", 0, "character", 0), "end", Map.of("line", 0, "character", 0)), "newText", ORIGINAL))));
                    for (int request = 0; request < (mode.equals("twice") ? 2 : 1); request++) send(Map.of("jsonrpc", "2.0", "id", 100 + request, "method", "workspace/applyEdit", "params", Map.of("edit", Map.of("documentChanges", changes))));
                } else if (method.isEmpty() && id >= 100) {
                    var response = message.getAsJsonObject("result");
                    if (response.get("applied").getAsBoolean()) throw new AssertionError("Capture applied a request");
                    refusal = response.get("failureReason").getAsString(); responses++;
                    if (responses == (mode.equals("twice") ? 2 : 1)) {
                        if (mode.equals("success-after-rejection")) send(Map.of("jsonrpc", "2.0", "id", executeId, "result", Map.of()));
                        else error(executeId, mode.equals("wrong-error") ? "different failure" : "edits not applied because of " + refusal);
                    }
                } else if (method.equals("shutdown")) send(Map.of("jsonrpc", "2.0", "id", id, "result", Map.of()));
                else if (method.equals("exit")) return;
            }
        }
        private static void error(int id, String text) throws IOException { send(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", 0, "message", text))); }
        private static void send(Object value) throws IOException {
            byte[] bytes = GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
            System.out.write(("Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); System.out.write(bytes); System.out.flush();
        }
        private static JsonObject read(InputStream stream) throws IOException {
            int length = -1; StringBuilder line = new StringBuilder(); int value;
            while ((value = stream.read()) != -1) {
                if (value == '\n') {
                    String header = line.toString().trim(); line.setLength(0);
                    if (header.isEmpty()) break;
                    if (header.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(header.substring(15).trim());
                } else line.append((char) value);
            }
            if (value == -1) return null;
            if (length < 0 || length > 8 * 1024 * 1024) throw new IOException("Invalid fixture frame");
            return GSON.fromJson(new String(stream.readNBytes(length), StandardCharsets.UTF_8), JsonObject.class);
        }
    }
}

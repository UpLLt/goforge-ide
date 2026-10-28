package dev.goforge.navigation;

import com.google.gson.GsonBuilder;
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;
import dev.goforge.lsp.GoplsServerFactory;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Explicit local-tool probe, run by verifyNavigationProtocol, not part of the unit-test count. */
public final class GoplsNavigationProtocolProbe {
    private static final String SOURCE = """
            package probe

            type Greeter interface { Greet() string }
            type Person struct{}
            func (Person) Greet() string { return "hello" }
            func Hello() string { return "hello" }
            var f func() string = Hello
            func Call() string { return f() }
            """;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        Path tools = root.resolve(".tools/bin");
        Path temporary = Files.createTempDirectory("goforge-navigation-protocol-");
        Path output = root.resolve(".artifacts/navigation-protocol.json");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("started_at", Instant.now().toString());
        report.put("scope", "real gopls protocol through the production launcher hook; no IDE UI or complete GoLand parity claim");
        report.put("gopls_sha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(tools.resolve("gopls")))));
        var origins = new LinkedHashMap<String, String>();
        for (Class<?> type : List.of(LanguageServer.class, org.eclipse.lsp4j.jsonrpc.RemoteEndpoint.class,
                com.redhat.devtools.lsp4ij.server.DefaultLauncherBuilder.class, dev.goforge.lsp.GoplsClientFeatures.class)) {
            origins.put(type.getName(), type.getProtectionDomain().getCodeSource().getLocation().toString());
        }
        report.put("loaded_class_origins", origins);
        report.put("passed", false);
        try {
            Files.writeString(temporary.resolve("go.mod"), "module example.test/probe\n\ngo 1.25\n");
            Path source = temporary.resolve("probe.go");
            Files.writeString(source, SOURCE);
            var original = run(tools, temporary, source, new LSPClientFeatures(), "baseline");
            var adapted = run(tools, temporary, source, new GoplsServerFactory().createClientFeatures(), "adapted");
            report.put("baseline", original);
            report.put("adapted", adapted);
            require(((Map<?, ?>) original.get("function_name")).get("error_code").equals(0), "baseline function must expose known error");
            require(((Map<?, ?>) adapted.get("function_name")).get("locations").equals(List.of()), "function must have an empty implementation list");
            for (String key : List.of("interface", "interface_method", "concrete_method", "function_type_keyword")) {
                require(original.get(key).equals(adapted.get(key)), key + " changed during adaptation");
                require(!((List<?>) ((Map<?, ?>) adapted.get(key)).get("locations")).isEmpty(), key + " lost navigation targets");
            }
            require(original.get("references").equals(adapted.get("references")), "references changed");
            require(((List<?>) adapted.get("references")).size() == 2, "declaration and function-value reference must remain");
            require(original.get("variable_error").equals(adapted.get("variable_error")), "unrelated error was not preserved");
            require(((Map<?, ?>) adapted.get("variable_error")).containsKey("error_code"), "variable error was swallowed");
            require(((Map<?, ?>) adapted.get("invalid_position")).containsKey("error_code"), "bad-position error was swallowed");
            report.put("passed", true);
            report.put("function_declaration_keyword_note", "Results recorded without a success assertion: gopls may fail its func-token query; the adapter must not treat that unrelated error as the ordinary-function case.");
        } catch (Throwable failure) {
            report.put("failure", failure.toString());
            throw failure;
        } finally {
            try (var files = Files.walk(temporary)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            report.put("temporary_fixture_deleted", true);
            report.put("completed_at", Instant.now().toString());
            Files.createDirectories(output.getParent());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
            System.out.println(output);
        }
    }

    private static Map<String, Object> run(Path tools, Path directory, Path source, LSPClientFeatures features,
                                            String label) throws Exception {
        var command = new ProcessBuilder(tools.resolve("gopls").toString(), "serve").directory(directory.toFile());
        command.environment().put("PATH", tools + java.io.File.pathSeparator + System.getenv().getOrDefault("PATH", ""));
        command.redirectError(directory.resolve(label + ".stderr").toFile());
        var process = command.start();
        ExecutorService workers = Executors.newCachedThreadPool();
        var client = new LanguageClient() {
            @Override public void telemetryEvent(Object object) { }
            @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) { }
            @Override public void showMessage(MessageParams messageParams) { }
            @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public void logMessage(MessageParams message) { }
        };
        Future<?> listening = null;
        try {
            var launcher = features.<LanguageServer>createLauncherBuilder().setInput(process.getInputStream())
                    .setOutput(process.getOutputStream()).setExecutorService(workers).setRemoteInterface(LanguageServer.class)
                    .setLocalService(client).create();
            listening = launcher.startListening();
            LanguageServer server = launcher.getRemoteProxy();
            var params = new InitializeParams();
            params.setProcessId((int) ProcessHandle.current().pid());
            params.setRootUri(directory.toUri().toString());
            params.setCapabilities(new ClientCapabilities());
            server.initialize(params).get(30, TimeUnit.SECONDS);
            server.initialized(new InitializedParams());
            String uri = source.toUri().toString();
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "go", 1, SOURCE)));
            var results = new LinkedHashMap<String, Object>();
            results.put("function_name", implementation(server, uri, position("func Hello", 5)));
            results.put("interface", implementation(server, uri, position("type Greeter", 5)));
            results.put("interface_method", implementation(server, uri, position("type Greeter interface { Greet", 25)));
            results.put("concrete_method", implementation(server, uri, position("func (Person) Greet", 14)));
            results.put("function_type_keyword", implementation(server, uri, position("var f func", 6)));
            results.put("function_declaration_keyword", implementation(server, uri, position("func Hello", 0)));
            results.put("variable_error", implementation(server, uri, position("var f ", 4)));
            results.put("invalid_position", implementation(server, uri, new Position(999, 0)));
            var references = server.getTextDocumentService().references(new ReferenceParams(
                    new TextDocumentIdentifier(uri), position("func Hello", 5), new ReferenceContext(true)))
                    .get(30, TimeUnit.SECONDS);
            results.put("references", normalize(references));
            server.shutdown().get(10, TimeUnit.SECONDS);
            server.exit();
            require(process.waitFor(10, TimeUnit.SECONDS), "gopls did not stop");
            require(process.exitValue() == 0, "gopls exit was not zero");
            results.put("process_exit_code", process.exitValue());
            return results;
        } finally {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(3, TimeUnit.SECONDS);
            }
            process.getOutputStream().close();
            process.getInputStream().close();
            if (listening != null) listening.cancel(true);
            workers.shutdownNow();
        }
    }

    private static Map<String, Object> implementation(LanguageServer server, String uri, Position position) throws Exception {
        try {
            Either<List<? extends Location>, List<? extends LocationLink>> result = server.getTextDocumentService()
                    .implementation(new ImplementationParams(new TextDocumentIdentifier(uri), position)).get(30, TimeUnit.SECONDS);
            if (result == null) return Map.of("locations", List.of());
            require(result.isLeft(), "gopls returned unexpected LocationLinks");
            return Map.of("locations", normalize(result.getLeft()));
        } catch (ExecutionException error) {
            if (!(error.getCause() instanceof ResponseErrorException response)) throw error;
            return Map.of("error_code", response.getResponseError().getCode(), "error_message", response.getMessage());
        }
    }

    private static List<Map<String, Object>> normalize(List<? extends Location> locations) {
        return locations.stream().map(location -> Map.<String, Object>of(
                "file", Path.of(java.net.URI.create(location.getUri())).getFileName().toString(),
                "start_line", location.getRange().getStart().getLine(),
                "start_character", location.getRange().getStart().getCharacter(),
                "end_line", location.getRange().getEnd().getLine(),
                "end_character", location.getRange().getEnd().getCharacter())).toList();
    }

    private static Position position(String token, int relative) {
        int offset = SOURCE.indexOf(token) + relative;
        String before = SOURCE.substring(0, offset);
        return new Position((int) before.chars().filter(c -> c == '\n').count(), offset - before.lastIndexOf('\n') - 1);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

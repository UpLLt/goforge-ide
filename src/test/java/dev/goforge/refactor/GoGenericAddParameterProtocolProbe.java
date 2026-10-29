package dev.goforge.refactor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.goforge.coverage.GoCoverageProcess;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in real tools on generated projects; separate from JUnit totals and native UI acceptance. */
public final class GoGenericAddParameterProtocolProbe {
    private static final List<String> CASES = List.of(
            "inferred-middle-effects", "explicit-single-first-effects", "explicit-multiple-last-effects",
            "partial-instantiation-middle-effects", "dirty-unicode-parenthesized",
            "instantiated-function-value-refused", "inferred-function-value-refused",
            "missing-reference-refused", "outside-reference-refused",
            "type-parameter-int-shadow-refused", "type-parameter-bool-shadow-refused",
            "cancelled-after-references");

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Supply a fresh evidence directory.");
        Path tools = Path.of(System.getProperty("user.dir"), ".tools", "bin");
        Path out = Path.of(arguments[0]);
        if (Files.exists(out)) {
            try (var children = Files.list(out)) {
                if (children.findAny().isPresent()) throw new IllegalStateException("Use an empty evidence directory.");
            }
        }
        Files.createDirectories(out);
        var checks = new ArrayList<Object>();
        var report = new LinkedHashMap<String, Object>();
        report.put("passed", false);
        report.put("scope", "Actual pinned gopls references, production generic add-parameter planner and real Go tests on owned fixtures; no native UI or complete signature-parity claim");
        report.put("checks", checks);
        Throwable failure = null;
        try {
            runHelper(tools, out.resolve("helper"));
            report.put("helperEvidence", "helper/result.json; counted separately from the protocol cases and JUnit");
            for (String name : CASES) checks.add(run(tools, out, name));
            report.put("passed", true);
        } catch (Exception | Error problem) {
            failure = problem;
            report.put("failure", describe(problem));
            throw problem;
        } finally { writeEvidence(out.resolve("result.json"), report, failure); }
        System.out.println(checks.size() + " real generic add-parameter protocol cases passed; helper results are separate.");
    }

    private static void runHelper(Path tools, Path out) throws Exception {
        Files.createDirectories(out);
        Path directory = Files.createTempDirectory("goforge-generic-helper-");
        var record = new LinkedHashMap<String, Object>();
        record.put("passed", false);
        record.put("scope", "Permanent helper tests against current production GoAddParameterSource; not JUnit or gopls protocol cases");
        Throwable failure = null;
        try {
            byte[] resource;
            try (var stream = GoGenericAddParameterProtocolProbe.class.getResourceAsStream("add_parameter_generic_test.go")) {
                if (stream == null) throw new IllegalStateException("Missing permanent generic helper-test resource.");
                resource = stream.readAllBytes();
            }
            Files.writeString(directory.resolve("helper.go"), GoAddParameterSource.TEXT);
            Files.write(directory.resolve("helper_test.go"), resource);
            record.put("productionSourceSha256", sha(GoAddParameterSource.TEXT.getBytes(StandardCharsets.UTF_8)));
            record.put("testResourceSha256", sha(resource));
            var environment = GoSignatureSafety.checkerEnvironment(environment(tools));
            var result = GoCoverageProcess.run(List.of(tools.resolve("go").toString(), "test", "-json", "-count=1", "."),
                    directory, environment, () -> false, Duration.ofSeconds(90));
            Files.writeString(out.resolve("go-test.jsonl"), result.stdout());
            Files.writeString(out.resolve("go-test.stderr.txt"), result.stderr());
            requireSuccessfulGo(result, "helper tests");
            var names = new ArrayList<String>();
            int subtests = 0;
            for (String line : result.stdout().split("\n")) {
                if (line.isBlank()) continue;
                var event = JsonParser.parseString(line).getAsJsonObject();
                if (!event.has("Test") || !event.has("Action") || !event.get("Action").getAsString().equals("pass")) continue;
                String name = event.get("Test").getAsString();
                if (name.contains("/")) subtests++; else names.add(name);
            }
            if (names.isEmpty()) throw new AssertionError("No permanent helper tests ran.");
            record.put("topLevelPassed", names);
            record.put("subtestsPassed", subtests);
            record.put("goExit", result.exitCode());
            record.put("knownProcessesStopped", result.knownProcessesStopped());
            record.put("passed", true);
        } catch (Exception | Error problem) {
            failure = problem;
            record.put("failure", describe(problem));
            throw problem;
        } finally { cleanupFixture(directory, out.resolve("result.json"), record, failure); }
    }

    private static Map<String, Object> run(Path tools, Path out, String name) throws Exception {
        Path directory = Files.createTempDirectory("goforge-generic-parameter-");
        boolean single = name.equals("explicit-single-first-effects");
        boolean dirty = name.equals("dirty-unicode-parenthesized");
        boolean shadow = name.contains("-shadow-");
        boolean expectRefusal = name.endsWith("refused") || name.startsWith("cancelled");
        String typeParameter = name.equals("type-parameter-int-shadow-refused") ? "int"
                : name.equals("type-parameter-bool-shadow-refused") ? "bool" : single ? "T" : "A";
        String typeParameters = single ? "[T ~string]" : "[" + typeParameter + " ~string, B ~string]";
        String arguments = name.startsWith("inferred-") || name.equals("missing-reference-refused") ? ""
                : single ? "[string]" : name.startsWith("partial-") ? "[string]" : "[string, string]";
        String callee = "api.Combine" + arguments;
        if (dirty) callee = "(" + callee + ")";
        int insertion = single ? 0 : name.equals("explicit-multiple-last-effects") ? 2 : 1;
        var spec = name.equals("type-parameter-bool-shadow-refused")
                ? new GoAddParameter.Spec("extra", "bool", insertion, "true")
                : new GoAddParameter.Spec("extra", "int", insertion, "7");
        var disk = fixture(name, typeParameters, typeParameter, single, callee);
        var snapshot = new LinkedHashMap<>(disk);
        if (dirty) {
            snapshot.put("api/combine.go", disk.get("api/combine.go").replace("func Combine", "/* unsaved \u4e16\u754c\ud83d\ude42 */ func Combine"));
            snapshot.put("app/call.go", disk.get("app/call.go").replace("Sequence=\"\";", "\u524d\u7f6e:=\"\u4e16\u754c\ud83d\ude42\";_ = \u524d\u7f6e;Sequence=\"\";"));
        }
        var environment = environment(tools);
        var record = new LinkedHashMap<String, Object>();
        record.put("case", name); record.put("passed", false);
        var executor = Executors.newCachedThreadPool();
        Process process = null;
        Future<?> listening = null;
        var applyRequests = new AtomicInteger();
        Throwable failure = null;
        try {
            Files.writeString(directory.resolve("go.mod"), "module example.test/generic\n\ngo 1.26\n");
            for (var entry : disk.entrySet()) {
                Path path = directory.resolve(entry.getKey());
                Files.createDirectories(path.getParent()); Files.writeString(path, entry.getValue());
            }
            record.put("baselineGoTest", goTest(tools, directory, environment));
            var builder = new ProcessBuilder(tools.resolve("gopls").toString(), "serve")
                    .directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().putAll(environment); process = builder.start();
            var client = new LanguageClient() {
                public void telemetryEvent(Object value) { }
                public void publishDiagnostics(PublishDiagnosticsParams value) { }
                public void showMessage(MessageParams value) { }
                public void logMessage(MessageParams value) { }
                public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams value) { return CompletableFuture.completedFuture(null); }
                public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams value) {
                    applyRequests.incrementAndGet(); return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false));
                }
            };
            var launcher = new org.eclipse.lsp4j.jsonrpc.Launcher.Builder<LanguageServer>()
                    .setLocalService(client).setRemoteInterface(LanguageServer.class)
                    .setInput(process.getInputStream()).setOutput(process.getOutputStream()).setExecutorService(executor).create();
            listening = launcher.startListening(); var server = launcher.getRemoteProxy();
            var initialize = new InitializeParams(); initialize.setRootUri(directory.toUri().toString()); initialize.setCapabilities(new ClientCapabilities());
            server.initialize(initialize).get(30, TimeUnit.SECONDS); server.initialized(new InitializedParams());
            var sources = new ArrayList<GoRefactorWorkspaceEdit.Source>();
            for (var entry : snapshot.entrySet()) {
                String uri = directory.resolve(entry.getKey()).toUri().toString();
                server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "go", 7, entry.getValue())));
                sources.add(new GoRefactorWorkspaceEdit.Source(uri, entry.getValue(), 7));
            }
            String uri = directory.resolve("api/combine.go").toUri().toString();
            String declaration = snapshot.get("api/combine.go");
            Position selected = GoRefactorEdit.position(declaration, declaration.indexOf("func ") + 1);
            var prepared = server.getTextDocumentService().prepareRename(new GoChangeSignature.PrepareRequest(new TextDocumentIdentifier(uri), selected)).get(30, TimeUnit.SECONDS);
            if (prepared == null || !prepared.isSecond()
                    || !GoChangeSignature.preparedDeclaration(declaration, selected, prepared.getSecond()))
                throw new AssertionError("Generic declaration did not reach the independent Add Parameter entry.");
            if (GoChangeSignature.prepared(uri, declaration, selected, prepared.getSecond()) != null)
                throw new AssertionError("Generic declaration unexpectedly enabled the narrower Change Signature contract.");
            record.put("genericFuncEntryPrepared", true);
            record.put("preparePlaceholder", prepared.getSecond().getPlaceholder());
            var cancelled = new AtomicBoolean(); var referenceRequests = new AtomicInteger();
            try {
                var wire = GoAddParameter.plan(tools.resolve("go").toString(), environment, sources, uri, selected, spec, target -> {
                    referenceRequests.incrementAndGet();
                    var references = server.getTextDocumentService().references(new ReferenceParams(new TextDocumentIdentifier(target.getUri()),
                            target.getRange().getStart(), new ReferenceContext(false))).get(30, TimeUnit.SECONDS);
                    if (references == null) throw new AssertionError("Actual gopls returned no reference result.");
                    record.put("referenceCount", references.size());
                    if (references.size() != (name.contains("function-value") ? 3 : 2))
                        throw new AssertionError("Unexpected actual caller/reference coverage: " + references.size());
                    if (name.equals("missing-reference-refused")) return references.stream().filter(r -> !r.getUri().endsWith("app/call.go")).toList();
                    if (name.equals("outside-reference-refused")) {
                        var altered = new ArrayList<Location>(references);
                        altered.add(new Location(directory.resolve("uncaptured.go").toUri().toString(), new Range(new Position(), new Position())));
                        return altered;
                    }
                    if (name.startsWith("cancelled")) cancelled.set(true);
                    return references;
                }, cancelled::get);
                if (expectRefusal) throw new AssertionError("Unsupported generic request produced edits.");
                var gson = new GsonBuilder().setPrettyPrinting().create();
                String encoded = gson.toJson(wire);
                var plan = GoRefactorWorkspaceEdit.prepare(sources, gson.fromJson(encoded, WorkspaceEdit.class));
                if (!plan.equals(GoRefactorWorkspaceEdit.prepare(sources, wire))) throw new AssertionError("Protocol round-trip changed edits.");
                if (plan.files().size() != 3) throw new AssertionError("Expected declaration and two caller files.");
                assertDiskUnchanged(directory, disk);
                record.put("previewDiskUnchanged", true); record.put("wireRoundTripVerified", true);
                record.put("changedFiles", plan.files().size()); record.put("wireEdit", gson.fromJson(encoded, Object.class));
                var callArguments = new ArrayList<>(List.of("mark(\"A\")", "mark(\"B\")")); callArguments.add(insertion, "7");
                for (var edit : plan.files()) {
                    if (edit.uri().endsWith("api/combine.go") && !edit.edit().replacement().contains("func Combine" + typeParameters + "("))
                        throw new AssertionError("Type parameters or constraints changed.");
                    if (edit.uri().endsWith("app/call.go") && !edit.edit().replacement().contains(callee + "(" + String.join(", ", callArguments) + ")"))
                        throw new AssertionError("Type arguments, call shape or original argument order changed.");
                    Files.writeString(Path.of(java.net.URI.create(edit.uri())), edit.edit().replacement());
                }
                if (dirty && (!Files.readString(directory.resolve("api/combine.go")).contains("/* unsaved \u4e16\u754c\ud83d\ude42 */")
                        || !Files.readString(directory.resolve("app/call.go")).contains("\u524d\u7f6e:=\"\u4e16\u754c\ud83d\ude42\"")))
                    throw new AssertionError("Dirty Unicode overlays were not retained.");
                record.put("typeParametersAndArgumentsPreserved", true);
                record.put("afterGoTest", goTest(tools, directory, environment));
            } catch (CancellationException expected) {
                if (!name.startsWith("cancelled")) throw expected;
                record.put("cancelledBeforePlan", true);
            } catch (IllegalStateException expected) {
                String required = name.contains("function-value") ? "not a verified direct call"
                        : name.equals("missing-reference-refused") ? "absent from the direct-call reference set"
                        : name.equals("outside-reference-refused") ? "outside the captured project"
                        : shadow ? "shadow" : null;
                if (required == null || expected.getMessage() == null || !expected.getMessage().contains(required)) throw expected;
                record.put("refused", expected.getMessage());
            }
            if (referenceRequests.get() != (shadow ? 0 : 1)) throw new AssertionError("Unexpected reference-query phase.");
            record.put("referenceRequests", referenceRequests.get());
            if (expectRefusal) { assertDiskUnchanged(directory, disk); record.put("allDiskUnchanged", true); }
            if (applyRequests.get() != 0) throw new AssertionError("Unexpected server applyEdit request.");
            record.put("serverApplyEditRequests", 0);
            server.shutdown().get(5, TimeUnit.SECONDS); server.exit();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) throw new AssertionError("gopls did not exit cleanly.");
            record.put("goplsExit", process.exitValue()); record.put("passed", true);
            return record;
        } catch (Exception | Error problem) {
            failure = problem; record.put("failure", describe(problem)); throw problem;
        } finally {
            Throwable cleanup = null;
            try {
                if (process != null && process.isAlive()) {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS) && !process.destroyForcibly().waitFor(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("Owned gopls process remained alive.");
                }
                record.put("goplsAliveAfterCleanup", process != null && process.isAlive());
            } catch (Exception | Error problem) { cleanup = problem; }
            try { if (listening != null) listening.cancel(true); executor.shutdownNow(); }
            catch (Exception | Error problem) { cleanup = combine(cleanup, problem); }
            if (cleanup != null && failure != null) failure.addSuppressed(cleanup);
            cleanupFixture(directory, out.resolve(name + ".json"), record, failure == null ? cleanup : failure);
            if (failure == null && cleanup != null) rethrow(cleanup);
        }
    }

    private static LinkedHashMap<String, String> fixture(String name, String typeParameters, String firstType, boolean single, String callee) {
        var files = new LinkedHashMap<String, String>();
        files.put("api/combine.go", "package api\nfunc Combine" + typeParameters + "(first " + firstType + ", second " + (single ? firstType : "B")
                + ") string { return string(first)+string(second) }\n");
        String saved = name.equals("instantiated-function-value-refused") ? "var Saved = api.Combine[string, string]\n"
                : name.equals("inferred-function-value-refused") ? "var Saved func(string, string) string = api.Combine\n" : "";
        files.put("app/call.go", "package app\nimport \"example.test/generic/api\"\nvar Sequence string\n"
                + "func mark(s string) string { Sequence+=s;return s }\n"
                + "func Call() string { Sequence=\"\";return " + callee + "(mark(\"A\"), mark(\"B\")) }\n" + saved);
        files.put("app/call_test.go", "package app\nimport \"testing\"\n"
                + "func TestCall(t *testing.T) { if got:=Call();got!=\"AB\"||Sequence!=\"AB\" { t.Fatalf(\"value=%s order=%s\",got,Sequence) } }\n");
        files.put("api/combine_test.go", "package api_test\nimport (\"testing\";\"example.test/generic/api\")\n"
                + "func TestExternal(t *testing.T) { if got:=" + callee + "(\"C\", \"D\");got!=\"CD\" { t.Fatal(got) } }\n");
        return files;
    }
    private static Map<String, String> environment(Path tools) {
        var env = new HashMap<>(System.getenv());
        env.put("PATH", tools + java.io.File.pathSeparator + env.getOrDefault("PATH", ""));
        env.put("GOWORK", "off"); env.put("GOTOOLCHAIN", "local"); env.put("GOFLAGS", ""); env.put("GOENV", "off");
        env.put("GOPROXY", "off"); env.put("GOSUMDB", "off"); env.put("GOTELEMETRY", "off");
        return env;
    }
    private static Map<String, Object> goTest(Path tools, Path directory, Map<String, String> environment) throws Exception {
        var result = GoCoverageProcess.run(List.of(tools.resolve("go").toString(), "test", "-count=1", "./..."),
                directory, environment, () -> false, Duration.ofSeconds(60));
        requireSuccessfulGo(result, "generated fixture");
        return Map.of("exit", result.exitCode(), "stdout", result.stdout(), "stderr", result.stderr(), "knownProcessesStopped", result.knownProcessesStopped());
    }
    private static void requireSuccessfulGo(GoCoverageProcess.Result result, String label) {
        if (!Objects.equals(result.exitCode(), 0) || result.cancelled() || result.timedOut() || result.outputTruncated() || !result.knownProcessesStopped())
            throw new AssertionError(label + " failed: " + result);
    }
    private static void assertDiskUnchanged(Path directory, Map<String, String> disk) throws Exception {
        for (var entry : disk.entrySet()) if (!Files.readString(directory.resolve(entry.getKey())).equals(entry.getValue()))
            throw new AssertionError("Preview/refusal modified disk: " + entry.getKey());
    }
    private static void cleanupFixture(Path directory, Path evidence, Map<String, Object> record, Throwable original) throws Exception {
        Throwable cleanup = null;
        try {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
            record.put("fixtureDeleted", true);
        } catch (Exception | Error problem) {
            cleanup = problem; record.put("fixtureDeleted", false); record.put("passed", false);
            record.put("cleanupFailure", describe(problem)); if (original != null) original.addSuppressed(problem);
        }
        if (original != null) record.put("passed", false);
        writeEvidence(evidence, record, original == null ? cleanup : original);
        if (original == null && cleanup != null) rethrow(cleanup);
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static Throwable combine(Throwable first, Throwable next) { if (first == null) return next; first.addSuppressed(next); return first; }
    private static void rethrow(Throwable problem) throws Exception { if (problem instanceof Exception error) throw error; throw (Error) problem; }
    private static String describe(Throwable problem) { var text = new java.io.StringWriter(); problem.printStackTrace(new java.io.PrintWriter(text)); return text.toString(); }
    private static void writeEvidence(Path path, Map<String, Object> report, Throwable original) throws Exception {
        try { Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n"); }
        catch (Exception | Error problem) { if (original == null) throw problem; original.addSuppressed(problem); }
    }
}

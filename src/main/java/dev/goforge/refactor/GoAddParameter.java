package dev.goforge.refactor;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.goforge.coverage.GoCoverageProcess;
import dev.goforge.tools.HostTools;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Adds one literal-default parameter without reordering or evaluating caller expressions. */
public final class GoAddParameter {
    private GoAddParameter() { }
    public record Spec(String name, String type, int index, String defaultValue) {
        public Spec {
            if (name == null || type == null || defaultValue == null)
                throw new IllegalArgumentException("Enter a name, scalar type and literal default value.");
            name = name.strip(); type = type.strip(); defaultValue = defaultValue.strip();
            if (name.isEmpty() || name.length() > 256 || type.isEmpty() || type.length() > 32
                    || defaultValue.isEmpty() || defaultValue.length() > 4096 || index < 0 || index > 10000)
                throw new IllegalArgumentException("Enter a bounded parameter name/type, literal value and nonnegative insertion position.");
        }
    }
    private record Source(String uri, String text) { }
    private record Input(String mode, List<Source> sources, Location target, List<Location> references, Spec spec) { }
    private record Result(boolean passed, String reason, Location target, Map<String, List<TextEdit>> changes, int checked) { }

    /** Worker-only; returns versioned edits for the existing all-file preview/Apply pipeline. */
    public static WorkspaceEdit plan(String go, Map<String, String> environment,
            Collection<GoRefactorWorkspaceEdit.Source> snapshot, String uri, Position position, Spec spec,
            GoSignatureSafety.References references, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(spec); Objects.requireNonNull(references); Objects.requireNonNull(cancelled);
        checkCancelled(cancelled);
        var sources = new LinkedHashMap<Path, GoRefactorWorkspaceEdit.Source>();
        long characters = 0;
        for (var source : snapshot) {
            if (source == null || source.text() == null || sources.putIfAbsent(GoRefactorWorkspaceEdit.uriPath(source.uri()), source) != null)
                throw new IllegalArgumentException("Invalid or duplicate add-parameter snapshot source.");
            characters += source.text().length();
        }
        if (sources.isEmpty() || sources.size() > GoRefactorWorkspaceEdit.MAX_FILES
                || characters > GoRefactorWorkspaceEdit.MAX_CHARACTERS)
            throw new IllegalArgumentException("Add-parameter snapshot exceeds the refactoring limits.");
        var selected = sources.get(GoRefactorWorkspaceEdit.uriPath(uri));
        if (selected == null) throw new IllegalArgumentException("Parameter declaration is outside the snapshot.");
        GoRefactorEdit.offset(selected.text(), position);
        var inputs = sources.values().stream().map(s -> new Source(s.uri(), s.text())).toList();
        var target = new Location(selected.uri(), new Range(position, position));
        Path owned = Files.createTempDirectory("goforge-add-parameter-");
        try {
            Path source = owned.resolve("planner.go"), binary = owned.resolve(HostTools.executableName("planner"));
            Files.writeString(source, GoAddParameterSource.TEXT, StandardCharsets.UTF_8);
            var env = GoSignatureSafety.checkerEnvironment(environment);
            run(List.of(go, "build", "-trimpath", "-buildvcs=false", "-o", binary.toString(), source.toString()), owned, env, cancelled, Duration.ofSeconds(60));
            Result declaration = inspect(binary, owned, env, new Input("target", inputs, target, null, spec), cancelled);
            List<? extends Location> found = references.find(declaration.target());
            checkCancelled(cancelled);
            if (found == null || found.size() > 10000)
                throw new IllegalStateException("A complete bounded reference response is required before adding a parameter.");
            var normalized = new ArrayList<Location>();
            for (var ref : found) {
                if (ref == null || ref.getRange() == null) throw new IllegalStateException("Incomplete caller reference.");
                var caller = sources.get(GoRefactorWorkspaceEdit.uriPath(ref.getUri()));
                if (caller == null) throw new IllegalStateException("A parameter reference is outside the captured project; no edits were applied.");
                normalized.add(new Location(caller.uri(), ref.getRange()));
            }
            Result result = inspect(binary, owned, env, new Input("plan", inputs, target, normalized, spec), cancelled);
            if (result.changes() == null || result.changes().isEmpty() || result.checked() != normalized.size())
                throw new IllegalStateException("Incomplete add-parameter plan.");
            var edits = new ArrayList<Either<TextDocumentEdit, ResourceOperation>>();
            for (var entry : result.changes().entrySet()) {
                var original = sources.get(GoRefactorWorkspaceEdit.uriPath(entry.getKey()));
                if (original == null) throw new IllegalStateException("The parameter planner returned an uncaptured file.");
                edits.add(Either.forLeft(documentEdit(new VersionedTextDocumentIdentifier(original.uri(), original.version()), entry.getValue())));
            }
            var workspace = new WorkspaceEdit(edits);
            GoRefactorWorkspaceEdit.prepare(sources.values(), workspace); // enforce overlap/size/version contracts before returning
            checkCancelled(cancelled);
            return workspace;
        } finally {
            try (var paths = Files.walk(owned)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    /** Decode the shared wire schema using the actual loaded LSP4J model. */
    static TextDocumentEdit documentEdit(VersionedTextDocumentIdentifier id, List<TextEdit> edits) {
        // The compile API uses List<TextEdit>; shipped LSP4J 1.0 uses
        // List<Either<TextEdit, SnippetTextEdit>>. A direct constructor silently
        // stores the wrong list shape under 1.0 and fails at protocol encoding.
        // Both versions decode the same plain-edit JSON schema correctly.
        var gson = new Gson();
        var wire = new JsonObject();
        wire.add("textDocument", gson.toJsonTree(id));
        wire.add("edits", gson.toJsonTree(edits));
        return gson.fromJson(wire, TextDocumentEdit.class);
    }
    private static Result inspect(Path binary, Path owned, Map<String, String> env, Input input, BooleanSupplier cancelled) throws Exception {
        Path request = owned.resolve("request.json");
        Files.writeString(request, new Gson().toJson(input), StandardCharsets.UTF_8);
        String output = run(List.of(binary.toString(), request.toString()), owned, env, cancelled, Duration.ofSeconds(15));
        if (output.length() > 8 * 1024 * 1024) throw new IOException("Parameter planner output exceeded its limit.");
        Result result = new Gson().fromJson(output, Result.class);
        if (result == null || !result.passed() || result.target() == null)
            throw new IllegalStateException(result == null ? "Parameter planning failed." : result.reason());
        return result;
    }
    private static String run(List<String> command, Path owned, Map<String, String> env, BooleanSupplier cancelled, Duration timeout) throws Exception {
        var result = GoCoverageProcess.run(command, owned, env, cancelled, timeout);
        checkCancelled(cancelled);
        if (result.cancelled()) throw new CancellationException("Parameter planning cancelled; no edits were applied.");
        if (result.timedOut() || result.outputTruncated() || !result.knownProcessesStopped() || !Objects.equals(result.exitCode(), 0))
            throw new IOException("The local Go parameter planner could not finish safely; no edits were applied.");
        return result.stdout();
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Parameter planning cancelled; no edits were applied.");
    }
}

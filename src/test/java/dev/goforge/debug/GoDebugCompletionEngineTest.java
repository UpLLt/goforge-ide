package dev.goforge.debug;

import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class GoDebugCompletionEngineTest {
    @Test public void localsIncludeParametersAndExcludeShadowedAndRegisterNames() {
        var server = new FixtureServer();
        server.rows.put(1, List.of(variable("parameter", "string", "arg", 0, "parameter"),
                variable("message", "string", "hello", 0, "message"),
                variable("(message)", "int", "10", 0, "message"), variable("~r0", "int", "0", 0, "")));
        assertEquals(List.of("parameter", "message"), names(complete(server, "")));
        assertEquals(List.of("message"), names(complete(server, "mes")));
        assertTrue(complete(server, "Mes").isEmpty());
        assertFalse(server.references.contains(99));
    }

    @Test public void nestedStructSelectorsUseVariableReferences() {
        var server = fieldsServer();
        assertEquals(List.of("Name", "Nationality"), names(complete(server, "user.Na")));
        assertEquals(List.of("Street"), names(complete(server, "user.Address.St")));
        assertEquals(List.of(1, 10, 1, 10, 11), server.references);
    }

    @Test public void pointerSelectorsUnwrapOnlyAnonymousDereference() {
        var server = fieldsServer();
        server.rows.put(1, List.of(variable("pointer", "*main.User", "0x1", 20, "pointer")));
        server.rows.put(20, List.of(variable("", "main.User", "{...}", 10, "(*pointer)")));
        assertEquals(List.of("Name", "Nationality"), names(complete(server, "pointer.Na")));
    }

    @Test public void dynamicInterfacePayloadAndMetadataAreNotGoFields() {
        var server = fieldsServer();
        server.rows.put(10, List.of(variable("data", "main.User", "...", 11, "user.(main.User)"),
                variable("len()", "int", "2", 0, "len(user)"), variable("[0]", "int", "1", 0, "user[0]")));
        assertTrue(complete(server, "user.").isEmpty());
    }

    @Test public void nilAndCyclicPointersTerminateWithoutEvaluating() {
        var server = fieldsServer();
        server.rows.put(1, List.of(variable("nilPointer", "*main.User", "nil", 0, "nilPointer"),
                variable("cycle", "**main.User", "0x1", 20, "cycle")));
        server.rows.put(20, List.of(variable("", "*main.User", "0x1", 20, "(*cycle)")));
        assertTrue(complete(server, "nilPointer.").isEmpty());
        assertTrue(complete(server, "cycle.").isEmpty());
        assertEquals(List.of(1, 1, 20), server.references);
    }

    @Test public void changedFrameDiscardsLateResults() {
        var server = new FixtureServer();
        var pending = new CompletableFuture<VariablesResponse>();
        server.pending = pending;
        var current = new AtomicBoolean(true);
        var result = GoDebugCompletionEngine.complete(new GoDebugCompletionEngine.Context(server, 7, current::get),
                GoDebugCompletionEngine.query("mes"));
        current.set(false);
        pending.complete(response(variable("message", "string", "old frame", 0, "message")));
        assertThrows(CompletionException.class, result::join);
    }

    @Test public void cancelledCompletionDoesNotRequestFurtherSelectors() {
        var server = new FixtureServer();
        var pending = new CompletableFuture<VariablesResponse>();
        server.pending = pending;
        var result = GoDebugCompletionEngine.complete(new GoDebugCompletionEngine.Context(server, 7, () -> true),
                GoDebugCompletionEngine.query("user.Na"));
        result.cancel(true);
        pending.complete(response(variable("user", "main.User", "...", 10, "user")));
        assertEquals(List.of(1), server.references);
    }

    @Test public void literalsCommentsAndComputedReceiversDoNotBecomeRequests() {
        for (String text : List.of("\"mes", "`mes", "'m", "// mes", "/* mes", "make().Na", "items[0].Na", "1.23")) {
            assertNull(text, GoDebugCompletionEngine.query(text));
        }
        assertEquals(List.of("user", "Address"), GoDebugCompletionEngine.query("message + user.Address.St").receivers());
        assertEquals("名字", GoDebugCompletionEngine.query("user.名字").prefix());
        assertEquals("mes", GoDebugCompletionEngine.query("\"escaped \\\" literal\" + mes").prefix());
    }

    @Test public void unsupportedScopeFailureIsNotConvertedToInventedCandidates() {
        var server = new FixtureServer();
        server.scopeFailure = new IllegalStateException("unknown frame");
        var error = assertThrows(CompletionException.class, () -> complete(server, ""));
        assertSame(server.scopeFailure, error.getCause());
    }

    @Test public void underscorePrefixCompletesNamedBindingButNeverBlankIdentifier() {
        var server = new FixtureServer();
        server.rows.put(1, List.of(variable("_message", "string", "value", 0, "_message"),
                variable("_", "int", "0", 0, "_")));
        assertEquals(List.of("_message"), names(complete(server, "_")));
    }

    static List<GoDebugCompletionEngine.Candidate> complete(FixtureServer server, String text) {
        return GoDebugCompletionEngine.complete(new GoDebugCompletionEngine.Context(server, 7, () -> true),
                Objects.requireNonNull(GoDebugCompletionEngine.query(text))).join();
    }
    static List<String> names(List<GoDebugCompletionEngine.Candidate> candidates) {
        return candidates.stream().map(GoDebugCompletionEngine.Candidate::name).toList();
    }
    static FixtureServer fieldsServer() {
        var server = new FixtureServer();
        server.rows.put(1, List.of(variable("user", "main.User", "{...}", 10, "user"),
                variable("message", "string", "hello", 0, "message"),
                variable("messageCount", "int", "2", 0, "messageCount")));
        server.rows.put(10, List.of(variable("Name", "string", "Ada", 0, "user.Name"),
                variable("Nationality", "string", "GB", 0, "user.Nationality"),
                variable("Address", "main.Address", "{...}", 11, "user.Address")));
        server.rows.put(11, List.of(variable("Street", "string", "Main", 0, "user.Address.Street")));
        return server;
    }
    static Variable variable(String name, String type, String value, int reference, String expression) {
        var variable = new Variable();
        variable.setName(name); variable.setType(type); variable.setValue(value);
        variable.setVariablesReference(reference); variable.setEvaluateName(expression);
        return variable;
    }
    static VariablesResponse response(Variable... variables) {
        var response = new VariablesResponse(); response.setVariables(variables); return response;
    }
    static final class FixtureServer implements IDebugProtocolServer {
        final Map<Integer, List<Variable>> rows = new HashMap<>();
        final List<Integer> references = new ArrayList<>();
        CompletableFuture<VariablesResponse> pending;
        RuntimeException scopeFailure;
        int scopeRequests;
        @Override public CompletableFuture<ScopesResponse> scopes(ScopesArguments args) {
            scopeRequests++;
            assertEquals(7, args.getFrameId());
            if (scopeFailure != null) return CompletableFuture.failedFuture(scopeFailure);
            var locals = new Scope(); locals.setName("Locals"); locals.setVariablesReference(1);
            var registers = new Scope(); registers.setName("Registers"); registers.setVariablesReference(99);
            var response = new ScopesResponse(); response.setScopes(new Scope[]{locals, registers});
            return CompletableFuture.completedFuture(response);
        }
        @Override public CompletableFuture<VariablesResponse> variables(VariablesArguments args) {
            references.add(args.getVariablesReference());
            if (pending != null) return pending;
            return CompletableFuture.completedFuture(response(rows.getOrDefault(args.getVariablesReference(), List.of()).toArray(Variable[]::new)));
        }
        @Override public CompletableFuture<EvaluateResponse> evaluate(EvaluateArguments args) {
            throw new AssertionError("Completion must never evaluate an expression or call a function");
        }
        @Override public CompletableFuture<CompletionsResponse> completions(CompletionsArguments args) {
            throw new AssertionError("Delve does not implement CompletionsRequest");
        }
    }
}

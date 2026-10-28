package dev.goforge.debug;

import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Read-only DAP scope/variable traversal; never evaluates an expression or calls a function. */
final class GoDebugCompletionEngine {
    record Context(IDebugProtocolServer server, int frameId, BooleanSupplier current) {}
    record Candidate(String name, String type, String value) {}
    record Query(List<String> receivers, String prefix) {}

    static CompletableFuture<List<Candidate>> complete(Context context, Query query) {
        var traversal = new Traversal(context);
        CompletableFuture<List<Candidate>> result = traversal.roots().thenCompose(roots -> {
            if (query.receivers.isEmpty()) return CompletableFuture.completedFuture(roots);
            CompletableFuture<List<Variable>> fields = CompletableFuture.completedFuture(roots);
            for (String receiver : query.receivers) {
                fields = fields.thenCompose(variables -> {
                    Variable selected = variables.stream().filter(v -> receiver.equals(v.getName())).findFirst().orElse(null);
                    return selected == null ? CompletableFuture.completedFuture(List.of())
                            : traversal.fields(selected, new HashSet<>(), 0);
                });
            }
            return fields;
        }).thenApply(variables -> {
            traversal.checkCurrent();
            Map<String, Candidate> unique = new LinkedHashMap<>();
            for (var variable : variables) {
                String name = variable.getName();
                if (isIdentifier(name) && name.startsWith(query.prefix)) {
                    unique.putIfAbsent(name, new Candidate(name, variable.getType(), variable.getValue()));
                }
            }
            return List.copyOf(unique.values());
        });
        result.whenComplete((ignored, failure) -> { if (result.isCancelled()) traversal.cancelled.set(true); });
        return result;
    }

    private static final class Traversal {
        final Context context;
        final AtomicBoolean cancelled = new AtomicBoolean();
        int requests;
        Traversal(Context context) { this.context = context; }
        void checkCurrent() {
            if (cancelled.get() || !context.current.getAsBoolean()) throw new CancellationException("Debug frame changed");
        }
        CompletableFuture<List<Variable>> roots() {
            checkCurrent();
            var arguments = new ScopesArguments();
            arguments.setFrameId(context.frameId);
            return context.server.scopes(arguments).thenCompose(response -> {
                checkCurrent();
                CompletableFuture<List<Variable>> result = CompletableFuture.completedFuture(new ArrayList<>());
                if (response == null || response.getScopes() == null) return result;
                for (var scope : response.getScopes()) {
                    String name = scope.getName() == null ? "" : scope.getName().toLowerCase(Locale.ROOT);
                    String hint = scope.getPresentationHint();
                    // Delve's Locals scope contains parameters followed by local variables.
                    if (!(name.startsWith("locals") || name.startsWith("arguments")
                            || "locals".equals(hint) || "arguments".equals(hint))) continue;
                    result = result.thenCompose(found -> variables(scope.getVariablesReference()).thenApply(items -> {
                        found.addAll(items);
                        return found;
                    }));
                }
                return result;
            });
        }
        CompletableFuture<List<Variable>> variables(int reference) {
            checkCurrent();
            if (reference <= 0 || ++requests > 24) return CompletableFuture.completedFuture(List.of());
            var arguments = new VariablesArguments();
            arguments.setVariablesReference(reference);
            return context.server.variables(arguments).thenApply(response -> {
                checkCurrent();
                return response == null || response.getVariables() == null ? List.of() : Arrays.asList(response.getVariables());
            });
        }
        CompletableFuture<List<Variable>> fields(Variable parent, Set<Integer> seen, int depth) {
            if (depth >= 8 || parent.getVariablesReference() <= 0 || !seen.add(parent.getVariablesReference())) {
                return CompletableFuture.completedFuture(List.of());
            }
            return variables(parent.getVariablesReference()).thenCompose(children -> {
                // Delve represents pointer dereference as an anonymous child. Go
                // permits implicit dereference in selectors; interfaces do not.
                for (var child : children) {
                    String expression = child.getEvaluateName();
                    if ((child.getName() == null || child.getName().isEmpty()) && expression != null
                            && expression.startsWith("(*") && child.getVariablesReference() > 0) {
                        return fields(child, seen, depth + 1);
                    }
                }
                return CompletableFuture.completedFuture(children.stream().filter(child -> {
                    String name = child.getName(), expression = child.getEvaluateName();
                    // Excludes indexed entries, interface dynamic payloads, metadata,
                    // shadowed bindings and values that have no valid Go selector.
                    return isIdentifier(name) && expression != null && expression.endsWith("." + name);
                }).toList());
            });
        }
    }

    static Query query(String text) {
        if (insideLiteralOrComment(text)) return null;
        int start = identifierStart(text, text.length());
        String prefix = text.substring(start);
        if (!prefix.isEmpty() && !"_".equals(prefix) && !isIdentifier(prefix)) return null;
        var receivers = new ArrayList<String>();
        int cursor = start;
        while (cursor > 0 && text.charAt(cursor - 1) == '.') {
            int end = cursor - 1;
            int receiverStart = identifierStart(text, end);
            String receiver = text.substring(receiverStart, end);
            if (!isIdentifier(receiver)) return null;
            receivers.add(0, receiver);
            cursor = receiverStart;
            if (receivers.size() > 8) return null;
        }
        if (cursor > 0 && (text.charAt(cursor - 1) == ')' || text.charAt(cursor - 1) == ']'
                || text.charAt(cursor - 1) == '.' || Character.isDigit(text.charAt(cursor - 1)))) return null;
        return new Query(List.copyOf(receivers), prefix);
    }

    private static int identifierStart(String text, int end) {
        int start = end;
        while (start > 0) {
            int codePoint = text.codePointBefore(start);
            if (!(codePoint == '_' || Character.isLetter(codePoint) || Character.isDigit(codePoint))) break;
            start -= Character.charCount(codePoint);
        }
        return start;
    }

    static boolean isIdentifier(String name) {
        if (name == null || name.isEmpty() || "_".equals(name)) return false;
        int first = name.codePointAt(0);
        if (first != '_' && !Character.isLetter(first)) return false;
        return name.codePoints().allMatch(c -> c == '_' || Character.isLetter(c) || Character.isDigit(c));
    }

    private static boolean insideLiteralOrComment(String text) {
        char quote = 0;
        boolean lineComment = false, blockComment = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i), next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (lineComment) { if (c == '\n') lineComment = false; continue; }
            if (blockComment) { if (c == '*' && next == '/') { blockComment = false; i++; } continue; }
            if (quote != 0) {
                if (escaped) { escaped = false; continue; }
                if (c == '\\' && quote != '`') { escaped = true; continue; }
                if (c == quote) quote = 0;
            } else if (c == '/' && next == '/') { lineComment = true; i++; }
            else if (c == '/' && next == '*') { blockComment = true; i++; }
            else if (c == '"' || c == '\'' || c == '`') quote = c;
        }
        return quote != 0 || lineComment || blockComment;
    }
}

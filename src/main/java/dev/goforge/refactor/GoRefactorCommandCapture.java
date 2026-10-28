package dev.goforge.refactor;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Captures one allowlisted command in an isolated gopls session; never applies its edits. */
public final class GoRefactorCommandCapture {
    public static final String EXTRACT_TO_NEW_FILE = "gopls.extract_to_new_file";
    private GoRefactorCommandCapture() { }

    public static WorkspaceEdit capture(List<String> commandLine, Map<String, String> environment, Path projectRoot,
            Collection<GoRefactorWorkspaceEdit.Source> sources, Command requested, BooleanSupplier cancelled) throws Exception {
        return capture(commandLine, environment, projectRoot, sources, requested, cancelled, Duration.ofSeconds(30));
    }

    static WorkspaceEdit capture(List<String> commandLine, Map<String, String> environment, Path projectRoot,
            Collection<GoRefactorWorkspaceEdit.Source> sources, Command requested, BooleanSupplier cancelled,
            Duration timeout) throws Exception {
        Objects.requireNonNull(cancelled); Objects.requireNonNull(environment);
        // Validate before even starting a process, including the only command's complete argument shape.
        var inputs = List.copyOf(sources);
        var command = validate(projectRoot, inputs, requested);
        if (commandLine == null || commandLine.isEmpty() || commandLine.stream().anyMatch(s -> s == null || s.isBlank()))
            throw new IllegalArgumentException("A gopls command line is required.");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("A positive capture deadline is required.");
        checkCancelled(cancelled);
        long deadline = System.nanoTime() + timeout.toNanos();
        String refusal = "GoForge preview capture " + UUID.randomUUID() + ": edits were not applied";
        var count = new AtomicInteger(); var captured = new AtomicReference<WorkspaceEdit>();
        LanguageClient client = new LanguageClient() {
            @Override public void telemetryEvent(Object value) { }
            @Override public void publishDiagnostics(PublishDiagnosticsParams value) { }
            @Override public void showMessage(MessageParams value) { }
            @Override public void logMessage(MessageParams value) { }
            @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams value) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
                count.incrementAndGet();
                if (params != null && params.getEdit() != null) captured.compareAndSet(null, params.getEdit());
                var response = new ApplyWorkspaceEditResponse(false); response.setFailureReason(refusal);
                return CompletableFuture.completedFuture(response);
            }
            @Override public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
                return CompletableFuture.completedFuture(Collections.nCopies(params.getItems().size(), null));
            }
            @Override public CompletableFuture<Void> registerCapability(RegistrationParams params) { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<ShowDocumentResult> showDocument(ShowDocumentParams params) {
                return CompletableFuture.completedFuture(new ShowDocumentResult(false));
            }
        };
        var executor = Executors.newCachedThreadPool(r -> {
            var thread = new Thread(r, "GoForge refactoring capture"); thread.setDaemon(true); return thread;
        });
        Process process = null; Future<?> listener = null; LanguageServer server = null;
        var owned = new LinkedHashMap<Long, Owned>();
        boolean interrupted = false;
        try {
            var builder = new ProcessBuilder(commandLine).directory(projectRoot.toFile());
            builder.environment().clear(); dev.goforge.tools.HostTools.putEnvironment(builder.environment(),environment);
            // Package loading must not update go.mod/go.sum or download a different toolchain.
            dev.goforge.tools.HostTools.putEnvironment(builder.environment(), Map.of("GOFLAGS", dev.goforge.tools.HostTools.environmentValue(environment, "GOFLAGS") + " -mod=readonly"));
            builder.environment().put("GOTOOLCHAIN", "local");
            process = builder.start(); observe(process, owned);
            Process running = process;
            // Drain stderr without retaining source text, environment values or tool output in logs.
            executor.submit(() -> { try (var stream = running.getErrorStream()) { stream.transferTo(java.io.OutputStream.nullOutputStream()); } catch (IOException ignored) { } });
            var launcher = new Launcher.Builder<LanguageServer>().setRemoteInterface(LanguageServer.class)
                    .setLocalService(client).setInput(process.getInputStream()).setOutput(process.getOutputStream())
                    .setExecutorService(executor).create();
            listener = launcher.startListening(); server = launcher.getRemoteProxy(); LanguageServer remote = server;
            var workspace = new WorkspaceClientCapabilities(); workspace.setApplyEdit(true);
            var edit = new WorkspaceEditCapabilities(); edit.setDocumentChanges(true); edit.setResourceOperations(List.of("create"));
            workspace.setWorkspaceEdit(edit);
            var capabilities = new ClientCapabilities(); capabilities.setWorkspace(workspace);
            var initialize = new InitializeParams(); initialize.setRootUri(projectRoot.toUri().toString());
            initialize.setWorkspaceFolders(List.of(new WorkspaceFolder(projectRoot.toUri().toString(), "GoForge preview")));
            initialize.setCapabilities(capabilities);
            initialize.setInitializationOptions(Map.of("buildFlags", List.of("-mod=readonly"), "analyses", Map.of("unusedparams", false)));
            await(request(executor, () -> remote.initialize(initialize)), deadline, cancelled, process, owned);
            await(CompletableFuture.runAsync(() -> remote.initialized(new InitializedParams()), executor), deadline, cancelled, process, owned);
            for (var source : inputs) {
                var item = new TextDocumentItem(source.uri(), "go", source.version() == null ? 0 : source.version(), source.text());
                await(CompletableFuture.runAsync(() -> remote.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(item)), executor),
                        deadline, cancelled, process, owned);
            }
            try {
                await(request(executor, () -> remote.getWorkspaceService().executeCommand(command)), deadline, cancelled, process, owned);
                throw new IllegalStateException("The capture command did not acknowledge the rejected edit; no preview was accepted.");
            } catch (ResponseErrorException failure) {
                if (!Objects.equals("edits not applied because of " + refusal, failure.getResponseError().getMessage())) throw failure;
            }
            checkCancelled(cancelled);
            if (count.get() != 1 || captured.get() == null)
                throw new IllegalStateException("Expected exactly one complete workspace edit from the capture command.");
        } catch (InterruptedException failure) {
            interrupted = true; throw failure;
        } finally {
            interrupted |= Thread.interrupted();
            cleanup(process, server, listener, executor, owned);
            if (interrupted) Thread.currentThread().interrupt();
        }
        // Also reject any additional request dispatched while the private session was shutting down.
        checkCancelled(cancelled);
        if (count.get() != 1 || captured.get() == null)
            throw new IllegalStateException("The capture command emitted multiple or incomplete edits.");
        return captured.get();
    }

    private static ExecuteCommandParams validate(Path root, List<GoRefactorWorkspaceEdit.Source> sources, Command command) {
        if (command == null || !EXTRACT_TO_NEW_FILE.equals(command.getCommand()))
            throw new IllegalArgumentException("Only Extract declarations to new file can use command capture.");
        if (command.getArguments() == null || command.getArguments().size() != 1)
            throw new IllegalArgumentException("Extract-to-file requires one source location.");
        Path base = root.toAbsolutePath().normalize();
        var available = new LinkedHashMap<String, GoRefactorWorkspaceEdit.Source>(); long characters = 0;
        if (sources.isEmpty() || sources.size() > GoRefactorWorkspaceEdit.MAX_FILES)
            throw new IllegalArgumentException("Invalid Go-source snapshot size.");
        for (var source : sources) {
            String key = GoRefactorWorkspaceEdit.uriKey(source.uri()); Path path = Path.of(key);
            if (!path.startsWith(base) || !key.endsWith(".go") || source.text() == null || source.version() != null && source.version() < 0
                    || available.putIfAbsent(key, source) != null)
                throw new IllegalArgumentException("Capture requires unique project Go sources with valid versions.");
            characters += source.text().length();
            if (characters > GoRefactorWorkspaceEdit.MAX_CHARACTERS) throw new IllegalArgumentException("Capture snapshot exceeds 32 MiB.");
        }
        try {
            JsonObject location = new Gson().toJsonTree(command.getArguments().getFirst()).getAsJsonObject();
            if (!location.keySet().equals(Set.of("uri", "range"))) throw new IllegalArgumentException();
            String uri = location.get("uri").getAsString(); var source = available.get(GoRefactorWorkspaceEdit.uriKey(uri));
            if (source == null) throw new IllegalArgumentException();
            var range = new Gson().fromJson(location.get("range"), Range.class);
            int start = GoRefactorEdit.offset(source.text(), range.getStart()), end = GoRefactorEdit.offset(source.text(), range.getEnd());
            if (end < start) throw new IllegalArgumentException();
            // Send only the validated location, never arbitrary opaque command arguments.
            return new ExecuteCommandParams(EXTRACT_TO_NEW_FILE, List.of(new Location(source.uri(), range)));
        } catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid extract-to-file location within the captured Go sources.", failure); }
    }

    private static <T> CompletableFuture<T> request(Executor executor, Supplier<CompletableFuture<T>> send) {
        return CompletableFuture.supplyAsync(send, executor).thenCompose(value -> value);
    }
    private static <T> T await(Future<T> future, long deadline, BooleanSupplier cancelled, Process process, Map<Long, Owned> owned) throws Exception {
        while (true) {
            checkCancelled(cancelled); observe(process, owned);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("The isolated Go refactoring capture exceeded its deadline.");
            try { return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS); }
            catch (TimeoutException waiting) { if (!process.isAlive()) throw new IOException("The private gopls capture process exited before completing the preview."); }
            catch (ExecutionException failure) {
                Throwable cause = failure.getCause(); while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof Exception exception) throw exception;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(cause);
            }
        }
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Go refactoring capture cancelled; no edits were applied.");
    }
    private record Owned(ProcessHandle process, Optional<Instant> started) {
        boolean alive() { return process.isAlive() && process.info().startInstant().equals(started); }
    }
    private static void observe(Process process, Map<Long, Owned> owned) {
        owned.putIfAbsent(process.pid(), new Owned(process.toHandle(), process.info().startInstant()));
        process.descendants().forEach(child -> owned.putIfAbsent(child.pid(), new Owned(child, child.info().startInstant())));
    }
    private static void cleanup(Process process, LanguageServer server, Future<?> listener, ExecutorService executor, Map<Long, Owned> owned) {
        boolean interrupted = false;
        try {
            if (process != null) {
                observe(process, owned);
                if (server != null && process.isAlive()) {
                    try { request(executor, server::shutdown).get(1, TimeUnit.SECONDS); }
                    catch (InterruptedException failure) { interrupted = true; }
                    catch (Exception ignored) { }
                    try { CompletableFuture.runAsync(server::exit, executor).get(250, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException failure) { interrupted = true; }
                    catch (Exception ignored) { }
                }
                try { process.waitFor(250, TimeUnit.MILLISECONDS); } catch (InterruptedException failure) { interrupted = true; }
                observe(process, owned);
                for (var child : new ArrayList<>(owned.values()).reversed()) if (child.alive()) child.process().destroy();
                try { process.waitFor(250, TimeUnit.MILLISECONDS); } catch (InterruptedException failure) { interrupted = true; }
                for (var child : new ArrayList<>(owned.values()).reversed()) if (child.alive()) child.process().destroyForcibly();
                try { process.waitFor(500, TimeUnit.MILLISECONDS); } catch (InterruptedException failure) { interrupted = true; }
            }
        } finally {
            if (listener != null) listener.cancel(true);
            executor.shutdownNow();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}

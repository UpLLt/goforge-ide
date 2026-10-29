package dev.goforge.testing;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonStreamParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiManager;
import com.intellij.util.execution.ParametersListUtil;
import dev.goforge.run.GoRunConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Cancellable background-only package/source discovery using the actual run toolchain and environment. */
public final class GoTestPackageIndex {
    private GoTestPackageIndex() { }
    private static final Key<GoTestRerunArguments.Context> PREPARED_CONTEXT = Key.create("goforge.test.actual.command.context");
    public static GoTestRerunArguments.Context preparedContext(GoRunConfiguration configuration) {
        return configuration.getUserData(PREPARED_CONTEXT);
    }
    public static String preparedGoFlags(GoRunConfiguration configuration) {
        var value = preparedContext(configuration);
        if (value == null) throw new IllegalStateException("Resolve the effective Go flags before launching a selected test debugger.");
        return value.goFlags();
    }
    private static final Set<String> BUILD_BOOLEAN = Set.of("a", "n", "x", "race", "msan", "asan", "cover", "trimpath", "work", "modcacherw", "buildvcs");
    private static final Set<String> BUILD_VALUE = Set.of("asmflags", "buildmode", "compiler", "gccgoflags", "gcflags", "installsuffix", "ldflags", "mod", "modfile", "overlay", "p", "pgo", "pkgdir", "tags", "toolexec");
    private static final Set<String> TEST_BOOLEAN = Set.of("json", "v", "short", "failfast", "fullpath", "c", "benchmem");
    private static final Set<String> TEST_VALUE = Set.of("run", "skip", "bench", "benchtime", "count", "cpu", "list", "parallel", "timeout", "fuzz", "fuzztime", "fuzzminimizetime", "vet", "shuffle", "coverprofile", "covermode", "coverpkg", "blockprofile", "blockprofilerate", "cpuprofile", "memprofile", "memprofilerate", "mutexprofile", "mutexprofilefraction", "outputdir", "trace", "o");

    public static Map<String, GoTestRunRegistry.PackageSources> prepare(GoRunConfiguration configuration,
                                                                      BooleanSupplier cancelled) throws Exception {
        if (ApplicationManager.getApplication().isDispatchThread())
            throw new IllegalStateException("Go test source discovery must run in the background.");
        var line = configuration.createCommandLine();
        String go = line.getExePath();
        Map<String, String> environment = line.getEffectiveEnvironment();
        Path directory = Path.of(configuration.getDirectory()).toRealPath();
        checkCancelled(cancelled);
        String actualFlags = execute(go, List.of("env", "GOFLAGS"), environment, directory, cancelled).trim();
        if (configuration.getTestSelection() != null) GoTestSelector.validateGoFlags(actualFlags);
        configuration.putUserData(PREPARED_CONTEXT, new GoTestRerunArguments.Context(directory, actualFlags));
        try {
            if (GoTestSelector.splitGoFlags(actualFlags).stream().anyMatch(flag -> flag.matches("--?overlay(?:=.*)?")))
                throw new IllegalArgumentException("Source navigation is unavailable for Go overlay builds.");
            Path projectRoot = Path.of(configuration.getProject().getBasePath()).toRealPath();
            List<String> args = listArguments(line.getParametersList().getList(), ParametersListUtil.parse(configuration.getTarget()));
            String output = execute(go, args, environment, directory, cancelled);
            var packages = new LinkedHashMap<String, GoTestRunRegistry.PackageSources>();
            var parser = new JsonStreamParser(output);
            int filesRead = 0;
            long sourceBytes = 0;
            while (parser.hasNext()) {
                checkCancelled(cancelled);
                JsonElement item = parser.next();
                if (!item.isJsonObject()) throw new IllegalStateException("Go package discovery returned an invalid object.");
                JsonObject pkg = item.getAsJsonObject();
                if (pkg.has("Error")) throw new IllegalStateException("Go package discovery reported a package error.");
                String importPath = requiredString(pkg, "ImportPath");
                Path packageDirectory = Path.of(requiredString(pkg, "Dir")).toRealPath();
                if (!packageDirectory.startsWith(projectRoot)) continue;
                if (packages.containsKey(importPath)) throw new IllegalStateException("Go package discovery returned an ambiguous package identity.");
                var files = new ArrayList<GoTestSourceIndex.Snapshot>();
                var names = new LinkedHashSet<String>();
                for (String key : List.of("TestGoFiles", "XTestGoFiles")) {
                    if (!pkg.has(key)) continue;
                    for (JsonElement value : pkg.getAsJsonArray(key)) names.add(value.getAsString());
                }
                for (String name : names) {
                    checkCancelled(cancelled);
                    if (++filesRead > 1000) throw new IllegalStateException("Go test source index exceeds 1000 files; use a narrower package target.");
                    Path file = packageDirectory.resolve(name).toRealPath();
                    if (!file.startsWith(projectRoot) || !file.getParent().equals(packageDirectory) || !file.getFileName().toString().endsWith("_test.go"))
                        throw new IllegalStateException("Go package discovery returned a test source outside its project package.");
                    long bytes = Files.size(file); sourceBytes += bytes;
                    if (bytes > 2_097_152 || sourceBytes > 16_777_216) throw new IllegalStateException("Go test source exceeds the 2 MiB file or 16 MiB run index limit.");
                    String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
                    files.add(GoTestSourceIndex.inspect(go, environment, file, text, cancelled));
                    var virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
                    if (virtualFile != null) ReadAction.run(() -> {
                        FileDocumentManager.getInstance().getDocument(virtualFile);
                        PsiManager.getInstance(configuration.getProject()).findFile(virtualFile);
                    });
                }
                packages.put(importPath, new GoTestRunRegistry.PackageSources(packageDirectory, files));
            }
            if (configuration.getTestSelection() != null) {
                if (packages.size() != 1 || !packages.values().iterator().next().directory().equals(directory))
                    throw new IllegalStateException("The selected test must resolve to exactly its configured project package; choose Package/manual mode to run a different target.");
                var sources = packages.values().iterator().next().files();
                for (String selected : configuration.getTestSelection().testNames()) {
                    String top = selected.split("/", -1)[0];
                    int matches = 0;
                    for (var file : sources) if (configuration.getTestSelection().matchesDeclaration(file.named(top))) matches++;
                    if (matches != 1) throw new IllegalStateException("Selected test '" + top + "' is missing or ambiguous in the current package. Save and select the test again.");
                }
            }
            return Map.copyOf(packages);
        } catch (CancellationException cancelledException) { throw cancelledException; }
        catch (Exception unavailable) {
            if (configuration.getTestSelection() != null) throw unavailable;
            // Ordinary package execution remains usable; the console explicitly marks mapping unavailable.
            return Map.of();
        }
    }

    static List<String> listArguments(List<String> parameters, List<String> targets) {
        if (parameters.isEmpty() || !parameters.getFirst().equals("test") || targets.isEmpty())
            throw new IllegalArgumentException("Go package discovery requires a test invocation.");
        if (parameters.size() < targets.size() + 1 || !parameters.subList(parameters.size() - targets.size(), parameters.size()).equals(targets))
            throw new IllegalArgumentException("Go package targets are not in their expected argument positions.");
        var result = new ArrayList<String>(); result.add("list"); result.add("-json");
        List<String> flags = parameters.subList(1, parameters.size() - targets.size());
        for (int i = 0; i < flags.size(); i++) {
            String argument = flags.get(i);
            if (!argument.startsWith("-") || argument.equals("--") || argument.equals("-args"))
                throw new IllegalArgumentException("Source navigation is unavailable for this argument shape.");
            String flag = argument.substring(argument.startsWith("--") ? 2 : 1);
            int equals = flag.indexOf('='); boolean hasValue = equals >= 0;
            if (hasValue) flag = flag.substring(0, equals);
            String bare = flag.startsWith("test.") ? flag.substring(5) : flag;
            if (flag.equals("overlay")) throw new IllegalArgumentException("Source navigation is unavailable for Go overlay builds.");
            if (BUILD_BOOLEAN.contains(flag)) result.add(argument);
            else if (BUILD_VALUE.contains(flag)) {
                result.add(argument);
                if (!hasValue) {
                    if (++i >= flags.size()) throw new IllegalArgumentException("Missing build flag value for source discovery.");
                    result.add(flags.get(i));
                }
            } else if (TEST_BOOLEAN.contains(bare)) { /* test-only flag does not affect package selection */ }
            else if (TEST_VALUE.contains(bare)) {
                if (!hasValue && ++i >= flags.size()) throw new IllegalArgumentException("Missing test flag value.");
            } else throw new IllegalArgumentException("Source navigation is unavailable for unsupported flag: " + flag);
        }
        result.addAll(targets); return List.copyOf(result);
    }
    private static String requiredString(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive() || !object.get(key).getAsJsonPrimitive().isString())
            throw new IllegalStateException("Go package discovery is missing " + key + ".");
        String value = object.get(key).getAsString();
        if (value.isBlank()) throw new IllegalStateException("Go package discovery returned an empty " + key + ".");
        return value;
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("Go test discovery cancelled.");
    }
    private static String execute(String executable, List<String> arguments, Map<String, String> environment,
                                  Path directory, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        var command = new ArrayList<String>(); command.add(executable); command.addAll(arguments);
        var builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().clear(); builder.environment().putAll(environment);
        Process process = builder.start();
        var io = Executors.newFixedThreadPool(2, runnable -> { var thread = new Thread(runnable, "GoForge test discovery output"); thread.setDaemon(true); return thread; });
        Future<String> stdout = io.submit(() -> readBounded(process.getInputStream(), 16_777_216));
        Future<String> stderr = io.submit(() -> readBounded(process.getErrorStream(), 1_048_576));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        try {
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                checkCancelled(cancelled);
                if (System.nanoTime() > deadline) throw new IllegalStateException("Go test discovery timed out after 60 seconds.");
                if (stdout.isDone()) stdout.get();
                if (stderr.isDone()) stderr.get();
            }
            checkCancelled(cancelled);
            String output = stdout.get(2, TimeUnit.SECONDS);
            String errors = stderr.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw new IllegalStateException("Go " + arguments.getFirst() + " discovery failed (exit " + process.exitValue() + "). Check this project's Go toolchain and build settings.");
            return output;
        } finally {
            process.toHandle().descendants().forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); }
            process.getInputStream().close(); process.getErrorStream().close(); process.getOutputStream().close();
            io.shutdownNow();
        }
    }
    private static String readBounded(InputStream input, int limit) throws Exception {
        var output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) >= 0) {
            if (output.size() + count > limit) throw new IllegalStateException("Go test discovery output exceeded its size limit.");
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

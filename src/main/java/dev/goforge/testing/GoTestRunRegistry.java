package dev.goforge.testing;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import dev.goforge.run.GoRunConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One live console's identities. Imported result URLs cannot create entries or authorize paths. */
public final class GoTestRunRegistry implements AutoCloseable {
    public static final String PROTOCOL = "goforge-test";
    public record PackageSources(Path directory, List<GoTestSourceIndex.Snapshot> files) {
        public PackageSources { directory = directory.toAbsolutePath().normalize(); files = List.copyOf(files); }
    }
    public record Target(String packageName, String fullName, String topLevelName, String nodeId,
                         String locationUrl, Path directory, GoTestSourceIndex.Snapshot source,
                         GoTestSourceIndex.Declaration declaration, GoTestEvents.Status status) { }
    public record ObservedIdentity(String packageName, String fullName, boolean inferred, GoTestEvents.Status status) { }
    private static final class Observed {
        final String packageName, fullName, nodeId, token;
        boolean inferred;
        GoTestEvents.Status status = GoTestEvents.Status.RUNNING;
        Observed(String pkg, String name, String node, String token, boolean inferred) {
            packageName = pkg; fullName = name; nodeId = node; this.token = token; this.inferred = inferred;
        }
    }
    private final Project project;
    private final GoRunConfiguration original;
    private final GoTestRerunArguments.Context commandContext;
    private final Map<String, PackageSources> packages;
    private final GoTestSelectionObservation selectedNames;
    private final Map<Path, Document> documents = new LinkedHashMap<>();
    private final String runToken = UUID.randomUUID().toString();
    private final Map<String, Observed> nodes = new LinkedHashMap<>();
    private final Map<String, Observed> tokens = new LinkedHashMap<>();
    private boolean terminated, disposed, localTerminationRequested;

    public GoTestRunRegistry(Project project, GoRunConfiguration original, Map<String, PackageSources> packages) {
        this(project, original, packages, GoTestPackageIndex.preparedContext(original));
    }
    GoTestRunRegistry(Project project, GoRunConfiguration original, Map<String, PackageSources> packages,
                      GoTestRerunArguments.Context commandContext) {
        this.project = project; this.original = original.clone(); this.packages = Map.copyOf(packages);
        selectedNames = new GoTestSelectionObservation(this.original.getTestSelection(), this.packages.keySet());
        this.commandContext = commandContext;
        for (var pkg : packages.values()) for (var source : pkg.files()) {
            var file = LocalFileSystem.getInstance().findFileByNioFile(source.file());
            if (file != null) {
                var document = FileDocumentManager.getInstance().getCachedDocument(file);
                if (document != null) documents.put(source.file(), document);
            }
        }
    }
    public Project project() { return project; }
    public synchronized Document cachedDocument(Path path) { return disposed ? null : documents.get(path); }
    public GoRunConfiguration originalConfiguration() { return original.clone(); }
    public GoTestRerunArguments.Context commandContext() { return commandContext; }
    public GoTestSelection originalSelection() { return original.getTestSelection(); }
    public boolean hasSourceMapping() { return !packages.isEmpty(); }
    public synchronized boolean isTerminated() { return terminated && !disposed; }
    public synchronized boolean isDisposed() { return disposed; }
    public synchronized void markLocalTerminationRequested() {
        if (!terminated && !disposed) localTerminationRequested = true;
    }
    public synchronized boolean wasLocallyTerminated() { return localTerminationRequested && !disposed; }

    /** Called only for GoTestEvents-owned IDs, never from a displayed label or a location URL. */
    public synchronized String locationHint(String pkg, String name, String nodeId, boolean inferred) {
        if (disposed) return "";
        selectedNames.location(pkg, name, inferred);
        if (!ordinaryTestName(name)) return "";
        Observed n = nodes.get(nodeId);
        if (n == null) {
            n = new Observed(pkg, name, nodeId, UUID.randomUUID().toString(), inferred);
            nodes.put(nodeId, n); tokens.put(n.token, n);
        } else if (!n.packageName.equals(pkg) || !n.fullName.equals(name)) return "";
        if (!inferred) n.inferred = false;
        return PROTOCOL + "://" + runToken + "/" + n.token;
    }
    public synchronized void observe(GoTestEvents.Emission emission) {
        if (disposed || emission.testName() == null || emission.testName().isEmpty()) return;
        if (switch (emission.kind()) {
            case TEST_STARTED, TEST_PAUSED, TEST_RESUMED, TEST_FINISHED, TEST_FAILED, TEST_SKIPPED, TEST_INTERRUPTED -> false;
            default -> true;
        }) return; // Diagnostics/output must not overwrite an already observed result.
        Observed n = nodes.get(emission.nodeId());
        if (n != null && n.packageName.equals(emission.packageName()) && n.fullName.equals(emission.testName()))
            n.status = emission.status();
    }
    public synchronized List<String> missingRequestedTests() {
        return selectedNames.missing();
    }
    public synchronized void markTerminated() { terminated = true; }
    public synchronized ObservedIdentity identity(String url) {
        if (disposed || url == null || !url.startsWith(PROTOCOL + "://" + runToken + "/")) return null;
        Observed n = tokens.get(url.substring((PROTOCOL + "://" + runToken + "/").length()));
        return n == null ? null : new ObservedIdentity(n.packageName, n.fullName, n.inferred, n.status);
    }
    public synchronized Target target(String url) {
        if (disposed || url == null || !url.startsWith(PROTOCOL + "://" + runToken + "/")) return null;
        String token = url.substring((PROTOCOL + "://" + runToken + "/").length());
        Observed n = tokens.get(token);
        if (n == null || n.inferred) return null;
        PackageSources pkg = packages.get(n.packageName);
        if (pkg == null) return null;
        String top = n.fullName.split("/", -1)[0];
        GoTestSourceIndex.Snapshot source = null;
        GoTestSourceIndex.Declaration declaration = null;
        for (var file : pkg.files()) {
            var found = file.named(top);
            if (found != null) {
                if (declaration != null) return null; // ambiguity is not resolved by basename or iteration order
                source = file; declaration = found;
            }
        }
        if (declaration == null || !(declaration.kind().equals("test") || declaration.kind().equals("example"))) return null;
        return new Target(n.packageName, n.fullName, top, n.nodeId, url, pkg.directory(), source, declaration, n.status);
    }
    public synchronized List<Target> knownTargets() {
        var result = new ArrayList<Target>();
        for (Observed n : nodes.values()) {
            Target target = target(PROTOCOL + "://" + runToken + "/" + n.token);
            if (target != null) result.add(target);
        }
        return List.copyOf(result);
    }
    public static boolean ordinaryTestName(String name) {
        if (name == null || name.isEmpty()) return false;
        String top = name.split("/", -1)[0];
        return validPrefix(top, "Test") || validPrefix(top, "Example");
    }
    private static boolean validPrefix(String name, String prefix) {
        return name.startsWith(prefix) && (name.length() == prefix.length()
            || Character.getType(name.codePointAt(prefix.length())) != Character.LOWERCASE_LETTER);
    }
    @Override public synchronized void close() { disposed = true; nodes.clear(); tokens.clear(); documents.clear(); selectedNames.clear(); }
}

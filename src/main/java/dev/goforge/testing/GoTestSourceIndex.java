package dev.goforge.testing;

import com.google.gson.Gson;
import com.intellij.openapi.application.ApplicationManager;
import dev.goforge.coverage.GoCoverageProcess;
import dev.goforge.tools.HostTools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Original standard-library Go AST discovery; every subprocess runs off the EDT and read locks. */
public final class GoTestSourceIndex {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Gson JSON = new Gson();
    private static final ReentrantLock HELPER_LOCK = new ReentrantLock();
    private static final Map<String, Helper> HELPERS = new HashMap<>();
    private static final LinkedHashMap<String, Snapshot> SNAPSHOTS = new LinkedHashMap<>(32, .75f, true);
    private record Input(String file, String text) { }
    private record RawDeclaration(String name, String kind, int start, int end, int nameStart) { }
    private record Output(boolean passed, String reason, List<RawDeclaration> declarations) { }
    private record Helper(Path directory, Path binary, String sha256) { }

    public record Declaration(String name, String kind, int startOffset, int endOffset, int nameOffset) { }
    public record Snapshot(Path file, String sourceSha256, String text, List<Declaration> declarations) {
        public Snapshot { file = file.toAbsolutePath().normalize(); declarations = List.copyOf(declarations); }
        public Declaration containing(int offset) {
            return declarations.stream().filter(d -> offset >= d.startOffset() && offset < d.endOffset()).findFirst().orElse(null);
        }
        public Declaration named(String name) {
            var found = declarations.stream().filter(d -> d.name().equals(name)).toList();
            return found.size() == 1 ? found.getFirst() : null;
        }
    }
    private GoTestSourceIndex() { }
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            HELPER_LOCK.lock();
            try { for (var helper : HELPERS.values()) deleteOwned(helper.directory()); }
            finally { HELPER_LOCK.unlock(); }
        }, "GoForge source helper cleanup"));
    }

    public static Snapshot inspect(String go, Map<String, String> environment, Path file,
                                   String text, BooleanSupplier cancelled) throws Exception {
        requireBackground(); checkCancelled(cancelled);
        // FileDocumentManager presents all source with LF. Parse that exact representation
        // so Windows CRLF files get the same identifier offsets as their editor Documents.
        text = normalizeText(text);
        validateText(text);
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.getFileName().toString().endsWith("_test.go")) throw new IllegalArgumentException("Choose a Go test source file.");
        String sourceHash = sha256(text.getBytes(StandardCharsets.UTF_8));
        Path compiler = Path.of(go).toRealPath();
        String compilerIdentity = compiler + ":" + Files.size(compiler) + ":" + Files.getLastModifiedTime(compiler).toMillis();
        String contextHash = sha256(JSON.toJson(new TreeMap<>(environment)).getBytes(StandardCharsets.UTF_8));
        String sourceKey = normalized + "\n" + sourceHash + "\n" + compilerIdentity + "\n" + contextHash;
        synchronized (SNAPSHOTS) {
            Snapshot cached = SNAPSHOTS.get(sourceKey);
            if (cached != null) return cached;
        }
        Helper helper = helper(compiler.toString(), compilerIdentity, environment, cancelled);
        Path request = Files.createTempDirectory("goforge-test-source-request-");
        try {
            Path input = request.resolve("input.json");
            Files.writeString(input, JSON.toJson(new Input(normalized.toString(), text)), StandardCharsets.UTF_8);
            String output = run(List.of(helper.binary().toString(), input.toString()), request,
                    helperEnvironment(environment), cancelled, Duration.ofSeconds(15));
            Snapshot snapshot = decode(normalized, text, output);
            checkCancelled(cancelled);
            synchronized (SNAPSHOTS) {
                SNAPSHOTS.put(sourceKey, snapshot);
                while (SNAPSHOTS.size() > 32 || SNAPSHOTS.values().stream().mapToLong(s -> s.text().length()).sum() > MAX_BYTES * 2L)
                    SNAPSHOTS.remove(SNAPSHOTS.keySet().iterator().next());
            }
            return snapshot;
        } finally { deleteOwned(request); }
    }

    /** A bounded in-memory hint for menus. First-run resolution always rechecks the active toolchain. */
    public static Snapshot cached(Path file, String text) {
        text = normalizeText(text);
        Path normalized = file.toAbsolutePath().normalize();
        synchronized (SNAPSHOTS) {
            for (Snapshot snapshot : SNAPSHOTS.values())
                if (snapshot.file().equals(normalized) && snapshot.text().equals(text)) return snapshot;
        }
        return null;
    }

    public static String normalizeText(String text) {
        return text == null ? null : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    static Snapshot decode(Path file, String text, String json) throws IOException {
        validateText(text);
        Output output;
        try { output = JSON.fromJson(json, Output.class); }
        catch (RuntimeException error) { throw new IOException("The Go source indexer returned an invalid result.", error); }
        if (output == null || !output.passed() || output.declarations() == null)
            throw new IOException(output == null || output.reason() == null ? "Go source discovery failed." : output.reason());
        var result = new ArrayList<Declaration>();
        var names = new HashSet<String>();
        for (var raw : output.declarations()) {
            if (raw == null || raw.name() == null || raw.kind() == null
                    || !Set.of("test", "example").contains(raw.kind()) || !names.add(raw.name()))
                throw new IOException("Invalid or ambiguous Go source declaration.");
            int start = utf8ToUtf16(text, raw.start()), end = utf8ToUtf16(text, raw.end()), name = utf8ToUtf16(text, raw.nameStart());
            if (start < 0 || start > name || name >= end || end > text.length() || !text.startsWith(raw.name(), name)
                    || name + raw.name().length() > end)
                throw new IOException("Go source declaration does not match the captured text.");
            result.add(new Declaration(raw.name(), raw.kind(), start, end, name));
        }
        return new Snapshot(file, sha256(text.getBytes(StandardCharsets.UTF_8)), text, result);
    }

    public static int utf8ToUtf16(String text, int byteOffset) throws IOException {
        if (byteOffset < 0) throw new IOException("Negative Go source byte offset.");
        int bytes = 0;
        for (int i = 0; i < text.length();) {
            if (bytes == byteOffset) return i;
            int cp = text.codePointAt(i);
            if (Character.isSurrogate(text.charAt(i)) && (cp <= Character.MAX_VALUE || !Character.isHighSurrogate(text.charAt(i))))
                throw new IOException("Unpaired UTF-16 surrogate in Go source.");
            bytes += cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            if (bytes > byteOffset) throw new IOException("Go source byte offset is inside a UTF-8 character.");
            i += Character.charCount(cp);
        }
        if (bytes == byteOffset) return text.length();
        throw new IOException("Go source byte offset is outside the captured text.");
    }

    private static void validateText(String text) throws IOException {
        if (text == null || text.length() > MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IOException("Go test source exceeds the 8 MiB discovery limit.");
        utf8ToUtf16(text, text.getBytes(StandardCharsets.UTF_8).length);
    }
    private static Helper helper(String go, String compilerIdentity, Map<String, String> environment, BooleanSupplier cancelled) throws Exception {
        byte[] source;
        try (var stream = GoTestSourceIndex.class.getResourceAsStream("/goforge/test-source-index.go")) {
            if (stream == null) throw new IOException("The bundled Go test source indexer is missing.");
            source = stream.readAllBytes();
        }
        String key = compilerIdentity + ":" + sha256(source);
        while (!HELPER_LOCK.tryLock(50, TimeUnit.MILLISECONDS)) checkCancelled(cancelled);
        try {
            checkCancelled(cancelled);
            Helper cached = HELPERS.get(key);
            if (cached != null && Files.isRegularFile(cached.binary()) && sha256(Files.readAllBytes(cached.binary())).equals(cached.sha256())) return cached;
            if (cached != null) { HELPERS.remove(key); deleteOwned(cached.directory()); }
            Path owned = Files.createTempDirectory("goforge-test-source-helper-");
            try {
                Path path = owned.resolve("indexer.go"), binary = owned.resolve(HostTools.executableName("indexer"));
                Files.write(path, source);
                run(List.of(go, "build", "-trimpath", "-buildvcs=false", "-o", binary.toString(), path.toString()),
                        owned, helperEnvironment(environment), cancelled, Duration.ofSeconds(60));
                Helper built = new Helper(owned, binary, sha256(Files.readAllBytes(binary)));
                HELPERS.put(key, built);
                return built;
            } catch (Exception error) { deleteOwned(owned); throw error; }
        } finally { HELPER_LOCK.unlock(); }
    }
    static Map<String, String> helperEnvironment(Map<String, String> inherited) {
        var env = new HashMap<>(inherited);
        for (var keys : List.of(System.getenv().keySet(), inherited.keySet())) for (String key : keys)
            if (key.toUpperCase(Locale.ROOT).startsWith("GO")) HostTools.putEnvironment(env, Map.of(key, ""));
        HostTools.putEnvironment(env, Map.of("GOENV", "off", "GOWORK", "off", "GOTOOLCHAIN", "local", "GO111MODULE", "off",
                "GOPROXY", "off", "GOSUMDB", "off", "GOTELEMETRY", "off", "CGO_ENABLED", "0"));
        return env;
    }
    private static String run(List<String> command, Path owned, Map<String, String> env, BooleanSupplier cancelled, Duration timeout) throws Exception {
        var result = GoCoverageProcess.run(command, owned, env, cancelled, timeout);
        checkCancelled(cancelled);
        if (result.cancelled()) throw new CancellationException("Go test discovery was cancelled.");
        if (result.timedOut() || result.outputTruncated() || !result.knownProcessesStopped() || !Objects.equals(result.exitCode(), 0))
            throw new IOException("The Go test source indexer did not finish safely.");
        return result.stdout();
    }
    private static void requireBackground() {
        var app = ApplicationManager.getApplication();
        if (app != null && (app.isDispatchThread() || app.isReadAccessAllowed()))
            throw new IllegalStateException("Go source discovery must run in a background task outside read actions.");
    }
    private static void checkCancelled(BooleanSupplier cancelled) { if (cancelled.getAsBoolean()) throw new CancellationException("Go test discovery was cancelled."); }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void deleteOwned(Path directory) {
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { /* Owned temporary files are safe to retain if the OS still holds a handle. */ }
    }
}

package dev.goforge.refactor;

import com.google.gson.*;
import dev.goforge.coverage.GoCoverageProcess;
import dev.goforge.tools.HostTools;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Changes one scalar type only when the complete captured single module compiles before and after. */
public final class GoChangeScalarType {
    private static final List<String> FEATURE_KEYS=List.of("GOARM", "GOARM64", "GOAMD64", "GO386", "GOMIPS", "GOMIPS64", "GOPPC64", "GORISCV64", "GOWASM", "GOFIPS140");
    private GoChangeScalarType() { }
    public record Spec(boolean result, int index, String type) {
        public Spec {
            if (type == null) throw new IllegalArgumentException("Choose a predeclared scalar type.");
            type = type.strip();
            if (type.isEmpty() || type.length() > 32 || index < 0 || index > 10000 || result && index != 0)
                throw new IllegalArgumentException("Choose a bounded scalar type and existing parameter position; a result uses position zero.");
        }
    }
    public record Plan(WorkspaceEdit workspace, Metadata metadata, int compiledPackages, CompilerProof compiler) {
        public void requireCurrent() throws IOException { metadata.requireCurrent(); compiler.requireCurrent(); }
        public void requireCompiler(String go, Map<String,String> environment) throws IOException {
            if (!compiler.requested.equals(Path.of(go).toAbsolutePath().normalize()) || !compiler.environment.equals(environment))
                throw new IllegalStateException("The Go SDK or environment changed after type checking. Request a fresh preview.");
            requireCurrent();
        }
    }
    /** No process is launched by Apply: both the selected SDK and the GOENV bytes are frozen. */
    public static final class CompilerProof {
        private static final List<String> KEYS = List.of("GOENV", "GOFLAGS", "GOOS", "GOARCH", "GOHOSTOS", "GOHOSTARCH", "GOEXPERIMENT", "GOWORK", "GOPATH", "GOMODCACHE", "GOCACHE", "CGO_ENABLED", "GOROOT", "GOVERSION", "GO111MODULE");
        private final Path requested, real;
        private final Object key;
        private final byte[] digest;
        private final Metadata settingsFile;
        private final Map<String,String> environment;
        private final JsonObject effective;
        private CompilerProof(Path requested, Path real, Object key, byte[] digest, Metadata settingsFile,
                Map<String,String> environment, JsonObject effective) {
            this.requested=requested;this.real=real;this.key=key;this.digest=digest;this.settingsFile=settingsFile;
            this.environment=Map.copyOf(environment);this.effective=effective.deepCopy();
        }
        static CompilerProof capture(String go, Map<String,String> environment, Path root, BooleanSupplier cancelled) throws Exception {
            Path requested=Path.of(go).toAbsolutePath().normalize(),real=requested.toRealPath();
            Object key=Files.readAttributes(real,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
            byte[] digest=fingerprint(real);
            JsonObject effective=readSettings(go,environment,root,cancelled);
            var files=new LinkedHashMap<Path,Captured>();String goenv=effective.get("GOENV").getAsString();
            if(!goenv.equals("off")&&!goenv.isBlank())Metadata.capture(files,Path.of(goenv).toAbsolutePath().normalize());
            var proof=new CompilerProof(requested,real,key,digest,new Metadata(files),environment,effective);
            if(!effective.equals(readSettings(go,environment,root,cancelled)))changed();
            proof.requireCurrent();return proof;
        }
        private static JsonObject readSettings(String go,Map<String,String> environment,Path root,BooleanSupplier cancelled)throws Exception {
            var env=new HashMap<>(environment);HostTools.putEnvironment(env,Map.of("GOTOOLCHAIN","local","GOTELEMETRY","off"));
            var command=new ArrayList<>(List.of(go,"env","-json"));command.addAll(KEYS);command.addAll(FEATURE_KEYS);
            return JsonParser.parseString(run(command,root,env,cancelled,"Effective Go build settings could not be checked")).getAsJsonObject();
        }
        public void requireCurrent() throws IOException {
            if(!requested.toRealPath().equals(real)||!Objects.equals(Files.readAttributes(real,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey(),key)
                    ||!Arrays.equals(fingerprint(real),digest))changed();
            settingsFile.requireCurrent();
        }
        private static byte[] fingerprint(Path path)throws IOException {
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>128L*1024*1024)
                throw new IOException("The Go SDK executable is not a bounded regular file.");
            try {var hash=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] buffer=new byte[32768];int n;long bytes=0;while((n=in.read(buffer))!=-1){bytes+=n;if(bytes>128L*1024*1024)throw new IOException("The Go SDK executable exceeded its size limit.");hash.update(buffer,0,n);}}return hash.digest();}
            catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        }
        private static void changed(){throw new IllegalStateException("The Go SDK or effective settings changed after type checking. Request a fresh preview; no edits were applied.");}
    }
    private record Source(String uri, String text) { }
    private record Input(String mode, List<Source> sources, Location target, List<Location> references, Spec spec) { }
    record Result(boolean passed, String reason, Location target, Map<String, List<TextEdit>> changes, int checked, Boolean method) { }
    private record Captured(byte[] bytes, Object key) { }

    /** Metadata belongs to this plan, so a module edit cannot silently invalidate its compile proof. */
    public static final class Metadata {
        private final Map<Path, Captured> files;
        private Metadata(Map<Path, Captured> files) { this.files = Map.copyOf(files); }
        static Metadata capture(Path root, Collection<Path> sourcePaths) throws IOException {
            var files = new LinkedHashMap<Path, Captured>();
            capture(files, root.resolve("go.mod")); capture(files, root.resolve("go.sum"));
            if(Files.exists(root.resolve("vendor"),LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Vendored module trees are not supported by this isolated refactoring check.");
            capture(files,root.resolve("vendor"));
            if (files.get(root.resolve("go.mod")).bytes() == null)
                throw new IllegalArgumentException("This compile-checked refactoring requires a go.mod at the project root.");
            // A parent workspace would change gopls's package graph. Never replace it silently with GOWORK=off.
            for (Path dir = root; dir != null; dir = dir.getParent()) {
                capture(files, dir.resolve("go.work"));
                if (files.get(dir.resolve("go.work")).bytes() != null)
                    throw new IllegalArgumentException("Stop workspace analysis and open one standalone module before this compile-checked refactoring.");
            }
            for (Path path : sourcePaths) for (Path dir = path.getParent(); dir != null && !dir.equals(root); dir = dir.getParent()) {
                if (!dir.startsWith(root)) throw new IllegalArgumentException("A source is outside the project root.");
                Path mod = dir.resolve("go.mod");
                if (!files.containsKey(mod)) capture(files, mod);
                if (files.get(mod).bytes() != null)
                    throw new IllegalArgumentException("Nested modules are not supported by this compile-checked refactoring.");
            }
            var metadata = new Metadata(files); metadata.requireCurrent(); return metadata;
        }
        private static void capture(Map<Path, Captured> into, Path path) throws IOException {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { into.put(path, new Captured(null, null)); return; }
            var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || attrs.size() > 8 * 1024 * 1024)
                throw new IllegalArgumentException("Module metadata must be bounded regular files, without symlinks.");
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > 8 * 1024 * 1024) throw new IllegalArgumentException("Module metadata exceeded its limit.");
            into.put(path, new Captured(bytes, attrs.fileKey()));
        }
        public void requireCurrent() throws IOException {
            for (var entry : files.entrySet()) {
                Path path = entry.getKey(); var old = entry.getValue();
                if (old.bytes() == null) {
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) changed();
                } else {
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) changed();
                    var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attrs.size() != old.bytes().length || !Objects.equals(attrs.fileKey(), old.key())
                            || !Arrays.equals(Files.readAllBytes(path), old.bytes())) changed();
                }
            }
        }
        private static void changed() { throw new IllegalStateException("Module/workspace metadata changed after type checking. Request a fresh preview; no edits were applied."); }
        private void copyModule(Path root, Path destination) throws IOException {
            for (String name : List.of("go.mod", "go.sum")) {
                byte[] content = files.get(root.resolve(name)).bytes();
                if (content != null) Files.write(destination.resolve(name), content, StandardOpenOption.CREATE_NEW);
            }
        }
    }

    public static Plan plan(String go, Map<String, String> environment, Path root,
            Collection<GoRefactorWorkspaceEdit.Source> snapshot, String uri, Position position, Spec spec,
            GoSignatureSafety.References references, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(spec); Objects.requireNonNull(references);
        var method=new java.util.concurrent.atomic.AtomicReference<GoMethodParameterSafety.Request>();
        return compileCheckedPlan(go,environment,root,snapshot,uri,position,cancelled,(sources,owned,helperEnv)->{
            var inputs = sources.values().stream().map(s -> new Source(s.uri(), s.text())).toList();
            var target = new Location(sources.get(GoRefactorWorkspaceEdit.uriPath(uri)).uri(), new Range(position, position));
            Path helper = owned.resolve("planner.go"), binary = owned.resolve(HostTools.executableName("planner"));
            Files.writeString(helper, GoChangeScalarTypeSource.TEXT, StandardCharsets.UTF_8);
            run(List.of(go, "build", "-trimpath", "-buildvcs=false", "-o", binary.toString(), helper.toString()), owned, helperEnv, cancelled, "The local scalar type planner could not be compiled");
            Result declaration = inspect(binary, owned, helperEnv, new Input("target", inputs, target, null, spec), cancelled);
            List<? extends Location> found = references.find(declaration.target());
            checkCancelled(cancelled);
            if (found == null || found.size() > 10000) throw new IllegalStateException("A complete bounded reference response is required for the type change.");
            var normalized = new ArrayList<Location>();
            for (var ref : found) {
                if (ref == null || ref.getRange() == null) throw new IllegalStateException("Incomplete type-change reference.");
                var caller = sources.get(GoRefactorWorkspaceEdit.uriPath(ref.getUri()));
                if (caller == null) throw new IllegalStateException("A type-change reference is outside the captured module; no edits were applied.");
                normalized.add(new Location(caller.uri(), ref.getRange()));
            }
            Result proposed = inspect(binary, owned, helperEnv, new Input("plan", inputs, target, normalized, spec), cancelled);
            requireSameKind(declaration,proposed);
            if (declaration.method())method.set(new GoMethodParameterSafety.Request(declaration.target(),List.copyOf(normalized)));
            if (proposed.changes() == null || proposed.changes().size() != 1 || proposed.checked() != normalized.size())
                throw new IllegalStateException("Incomplete scalar type-change plan.");
            var edits = new ArrayList<Either<TextDocumentEdit, ResourceOperation>>();
            for (var entry : proposed.changes().entrySet()) {
                var original = sources.get(GoRefactorWorkspaceEdit.uriPath(entry.getKey()));
                if (original == null || entry.getValue().size() != 1) throw new IllegalStateException("Unexpected scalar type-change edit.");
                edits.add(Either.forLeft(GoAddParameter.documentEdit(new VersionedTextDocumentIdentifier(original.uri(), original.version()), entry.getValue())));
            }
            var workspace = new WorkspaceEdit(edits);
            return workspace;
        },(copy,owned,compileEnv)->{
            if(method.get()!=null)GoMethodParameterSafety.verify(go,root,snapshot,method.get(),copy,owned,compileEnv,cancelled);
        });
    }

    @FunctionalInterface interface CheckedPlanner {
        WorkspaceEdit plan(Map<Path,GoRefactorWorkspaceEdit.Source> sources,Path owned,Map<String,String> helperEnv) throws Exception;
    }
    @FunctionalInterface interface OriginalSnapshotProof {
        void verify(Path copy,Path owned,Map<String,String> compileEnvironment) throws Exception;
    }
    /** Shared compile-only proof; planner output never bypasses frozen source/metadata/SDK checks. */
    static Plan compileCheckedPlan(String go,Map<String,String> environment,Path root,
            Collection<GoRefactorWorkspaceEdit.Source> snapshot,String uri,Position position,
            BooleanSupplier cancelled,CheckedPlanner planner)throws Exception {
        return compileCheckedPlan(go,environment,root,snapshot,uri,position,cancelled,planner,null);
    }
    static Plan compileCheckedPlan(String go,Map<String,String> environment,Path root,
            Collection<GoRefactorWorkspaceEdit.Source> snapshot,String uri,Position position,
            BooleanSupplier cancelled,CheckedPlanner planner,OriginalSnapshotProof originalProof)throws Exception {
        Objects.requireNonNull(planner); Objects.requireNonNull(cancelled);
        checkCancelled(cancelled);
        for (String key : List.of("GOFLAGS", "GOOS", "GOARCH", "GOEXPERIMENT", "GOROOT"))
            if (!HostTools.environmentValue(environment, key).isBlank())
                throw new IllegalArgumentException("Compile-checked refactorings currently require default host build settings; clear " + key + " before requesting a preview.");
        String driver = HostTools.environmentValue(environment, "GOPACKAGESDRIVER");
        if (!driver.isBlank() && !driver.equals("off")) throw new IllegalArgumentException("Custom package drivers are not supported by this compile-only refactoring check.");
        String work = HostTools.environmentValue(environment, "GOWORK");
        if (!work.isBlank() && !work.equals("off")) throw new IllegalArgumentException("Active Go workspaces are not supported by this compile-only refactoring check.");
        root = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !root.toRealPath().equals(root))
            throw new IllegalArgumentException("Open a real module directory without symbolic-link path components.");
        var sources = new LinkedHashMap<Path, GoRefactorWorkspaceEdit.Source>();
        long characters = 0;
        for (var source : snapshot) {
            if (source == null || source.text() == null) throw new IllegalArgumentException("Incomplete compile-check source snapshot.");
            Path path = GoRefactorWorkspaceEdit.uriPath(source.uri());
            if (!path.startsWith(root) || path.equals(root) || !path.toString().endsWith(".go")
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path)
                    || sources.putIfAbsent(path, source) != null)
                throw new IllegalArgumentException("Compile-check sources must be unique regular Go files inside the module.");
            characters += source.text().length();
        }
        if (sources.isEmpty() || sources.size() > GoRefactorWorkspaceEdit.MAX_FILES || characters > GoRefactorWorkspaceEdit.MAX_CHARACTERS)
            throw new IllegalArgumentException("Compile-check snapshot exceeds the refactoring limits.");
        var selected = sources.get(GoRefactorWorkspaceEdit.uriPath(uri));
        if (selected == null) throw new IllegalArgumentException("Declaration is outside the captured module.");
        GoRefactorEdit.offset(selected.text(), position);
        var directories = new HashSet<Path>();
        for (Path path : sources.keySet()) directories.add(path.getParent());
        for (Path directory : directories) try (var children = Files.list(directory)) {
            if (children.anyMatch(path -> path.getFileName().toString().matches(".*\\.(?:s|S|syso|c|cc|cpp|cxx|h|f|F|m|mm|for|f90)")))
                throw new IllegalArgumentException("Assembly, native object and non-Go source files are outside the captured caller graph.");
        }
        Metadata metadata = Metadata.capture(root, sources.keySet());
        CompilerProof compiler = CompilerProof.capture(go,environment,root,cancelled);
        try (var scratch = GoRefactorScratch.create("goforge-refactor-check-")) {
            Path owned = scratch.path();
            var helperEnv=GoSignatureSafety.checkerEnvironment(environment);
            HostTools.putEnvironment(helperEnv,Map.of("GOPACKAGESDRIVER","off"));
            var workspace=planner.plan(Collections.unmodifiableMap(sources),owned,helperEnv);
            var prepared = GoRefactorWorkspaceEdit.prepare(sources.values(), workspace);
            Path copy = Files.createDirectory(owned.resolve("module"));
            metadata.copyModule(root, copy);
            for (var entry : sources.entrySet()) {
                Path destination = copy.resolve(root.relativize(entry.getKey()));
                Files.createDirectories(destination.getParent()); Files.writeString(destination, entry.getValue().text(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            var effectiveEnv = new HashMap<>(environment);
            HostTools.putEnvironment(effectiveEnv, Map.of("GOTOOLCHAIN", "local", "GOTELEMETRY", "off"));
            var effective = compiler.effective;
            if (!effective.get("GOFLAGS").getAsString().isBlank() || !effective.get("GOEXPERIMENT").getAsString().isBlank()
                    || !effective.get("GOOS").equals(effective.get("GOHOSTOS")) || !effective.get("GOARCH").equals(effective.get("GOHOSTARCH"))
                    || !Set.of("", "off").contains(effective.get("GOWORK").getAsString()))
                throw new IllegalStateException("Effective Go settings use flags, experiments, cross-compilation or a workspace. This operation requires default host settings.");
            if(!Set.of("","on").contains(effective.get("GO111MODULE").getAsString()))
                throw new IllegalStateException("Compile-checked refactorings require module mode; GO111MODULE=off is not supported.");
            var preserved=new ArrayList<>(List.of("GOPATH","GOMODCACHE","GOCACHE","CGO_ENABLED","GOROOT"));preserved.addAll(FEATURE_KEYS);
            for (String key : preserved) HostTools.putEnvironment(effectiveEnv, Map.of(key,effective.get(key).getAsString()));
            var compileEnv = compileEnvironment(effectiveEnv);
            disableNativeTools(compileEnv,owned);
            String modJson = run(List.of(go, "mod", "edit", "-json"), copy, compileEnv, cancelled, "Module metadata could not be read");
            var module = JsonParser.parseString(modJson).getAsJsonObject();
            if (module.has("Replace") && module.get("Replace").isJsonArray()) for (var replace : module.getAsJsonArray("Replace")) {
                var next = replace.getAsJsonObject().getAsJsonObject("New");
                if (!next.has("Version") || next.get("Version").getAsString().isBlank())
                    throw new IllegalStateException("Local module replacements are outside this captured single-module compile check.");
            }
            List<String> packages = compile(go, copy, owned.resolve("before"), compileEnv, sources.keySet().stream().map(root::relativize).toList(), cancelled, "The current captured module does not compile");
            if(originalProof!=null)originalProof.verify(copy,owned,Collections.unmodifiableMap(compileEnv));
            checkCancelled(cancelled);
            for (var file : prepared.files()) Files.writeString(copy.resolve(root.relativize(GoRefactorWorkspaceEdit.uriPath(file.uri()))), file.edit().replacement(), StandardCharsets.UTF_8);
            List<String> after = compile(go, copy, owned.resolve("after"), compileEnv, sources.keySet().stream().map(root::relativize).toList(), cancelled, "The proposed refactoring is incompatible with the captured function or callers");
            if (!packages.equals(after)) throw new IllegalStateException("The compiler package graph changed during type validation.");
            // Readonly module flags should leave these exact bytes untouched, even on a failed/cache-limited check.
            for (String name : List.of("go.mod", "go.sum")) {
                byte[] expected = metadata.files.get(root.resolve(name)).bytes();
                if (expected == null ? Files.exists(copy.resolve(name)) : !Arrays.equals(expected, Files.readAllBytes(copy.resolve(name))))
                    throw new IllegalStateException("The type check needed module metadata changes; synchronize dependencies before retrying.");
            }
            metadata.requireCurrent(); compiler.requireCurrent(); checkCancelled(cancelled);
            return new Plan(workspace, metadata, packages.size(), compiler);
        }
    }

    static Map<String, String> compileEnvironment(Map<String, String> environment) {
        var env = GoSignatureSafety.checkerEnvironment(environment);
        HostTools.putEnvironment(env, Map.of("GO111MODULE", "on", "GOPACKAGESDRIVER", "off", "GOFLAGS", "", "GOWORK", "off"));
        // Reuse the configured caches without downloading or invoking package drivers/toolchains.
        var preserved=new ArrayList<>(List.of("GOPATH","GOMODCACHE","GOCACHE","CGO_ENABLED","GOROOT"));preserved.addAll(FEATURE_KEYS);
        for (String key : preserved) {
            String value = HostTools.environmentValue(environment, key);
            if (!value.isBlank()) HostTools.putEnvironment(env, Map.of(key, value));
        }
        return env;
    }
    static void disableNativeTools(Map<String,String> environment,Path owned) {
        // Even go list may probe CC. Never execute a user/compiler helper while discovering cgo.
        String disabled=owned.resolve("native-tools-disabled").toAbsolutePath().toString();
        if(disabled.contains("\"")&&disabled.contains("'"))
            throw new IllegalArgumentException("The temporary path cannot safely represent a disabled native compiler.");
        String quoted=disabled.contains("\"")?"'"+disabled+"'":"\""+disabled+"\"";
        HostTools.putEnvironment(environment,Map.of("CC",quoted,"CXX",quoted,"FC",quoted,"PKG_CONFIG",quoted));
    }
    private static List<String> compile(String go, Path copy, Path outputs, Map<String, String> env, Collection<Path> captured,
            BooleanSupplier cancelled, String failure) throws Exception {
        // Keep the user's effective cgo build tags, but refuse any actual C compilation before -c.
        // A dependency may select different pure-Go APIs under cgo/!cgo, so forcing cgo=0 is unsound.
        String dependencies=run(List.of(go,"list","-deps","-test","-json","-mod=readonly","./..."),copy,env,cancelled,failure);
        var dependencyStream=new JsonStreamParser(dependencies);
        while(dependencyStream.hasNext()) {
            var dependency=dependencyStream.next().getAsJsonObject();
            if(dependency.has("CgoFiles")&&dependency.getAsJsonArray("CgoFiles").size()>0)
                throw new IllegalStateException("A captured package or dependency requires cgo compilation. Compile-checked refactorings currently support pure-Go compiler inputs only; no edits were applied.");
        }
        String listed = run(List.of(go, "list", "-json", "-mod=readonly", "./..."), copy, env, cancelled, failure);
        var stream = new JsonStreamParser(listed); var packages = new TreeSet<String>(); var included = new HashSet<Path>();
        while (stream.hasNext()) {
            var item = stream.next().getAsJsonObject();
            if (!item.has("Dir") || !item.has("ImportPath")) throw new IllegalStateException("Incomplete compiler package graph.");
            Path directory = Path.of(item.get("Dir").getAsString()).toAbsolutePath().normalize();
            if (!directory.startsWith(copy)) throw new IllegalStateException("A compiler package is outside the captured module.");
            for (String key : List.of("IgnoredGoFiles", "CgoFiles"))
                if (item.has(key) && item.getAsJsonArray(key).size() > 0) throw new IllegalStateException("Conditional or cgo sources are not covered by this default-host type check.");
            for (String key : List.of("GoFiles", "TestGoFiles", "XTestGoFiles")) if (item.has(key))
                for (var file : item.getAsJsonArray(key)) included.add(copy.relativize(directory.resolve(file.getAsString()).normalize()));
            packages.add(item.get("ImportPath").getAsString());
            if (packages.size() > 128) throw new IllegalStateException("Compile-checked refactorings support at most 128 captured packages.");
        }
        if (packages.isEmpty() || !included.equals(new HashSet<>(captured)))
            throw new IllegalStateException("Some captured Go sources are outside the compiler package graph; no partial type check is accepted.");
        Files.createDirectory(outputs); int index = 0; long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        for (String name : packages) {
            BooleanSupplier bounded = () -> cancelled.getAsBoolean() || System.nanoTime() > deadline;
            try {
                // -c compiles the test main but never executes package init, TestMain or any test.
                run(List.of(go, "test", "-c", "-vet=off", "-mod=readonly", "-trimpath", "-buildvcs=false", "-o", outputs.resolve("package-" + index++ + HostTools.executableName(".test")).toString(), name), copy, env, bounded, failure);
            } catch (CancellationException ex) {
                if (!cancelled.getAsBoolean() && System.nanoTime() > deadline)
                    throw new IllegalStateException("The compile-only type check exceeded its time limit; no edits were applied.");
                throw ex;
            }
        }
        return List.copyOf(packages);
    }
    private static Result inspect(Path binary, Path owned, Map<String, String> env, Input input, BooleanSupplier cancelled) throws Exception {
        Path request = owned.resolve("request.json"); Files.writeString(request, new Gson().toJson(input), StandardCharsets.UTF_8);
        String output = run(List.of(binary.toString(), request.toString()), owned, env, cancelled, "Scalar type planning failed");
        return decodeResult(output);
    }
    /** Both planner phases must explicitly classify the target before selecting its proof. */
    static Result decodeResult(String output) {
        var wire=JsonParser.parseString(output);
        if(!wire.isJsonObject())throw new IllegalStateException("Scalar type planning failed.");
        var kind=wire.getAsJsonObject().get("method");
        if(kind==null||!kind.isJsonPrimitive()||!kind.getAsJsonPrimitive().isBoolean())
            throw new IllegalStateException("The scalar planner did not classify the declaration.");
        Result result = new Gson().fromJson(wire, Result.class);
        if (!result.passed() || result.target() == null)
            throw new IllegalStateException(result.reason()==null?"Scalar type planning failed.":result.reason());
        return result;
    }
    static void requireSameKind(Result declaration,Result proposed) {
        if(declaration==null||proposed==null||declaration.method()==null||!declaration.method().equals(proposed.method()))
            throw new IllegalStateException("The scalar declaration kind changed during planning.");
    }
    private static String run(List<String> command, Path directory, Map<String, String> env, BooleanSupplier cancelled, String failure) throws Exception {
        var result = GoCoverageProcess.run(command, directory, env, cancelled, Duration.ofSeconds(60));
        checkCancelled(cancelled);
        if (result.cancelled()) throw new CancellationException("Scalar type planning cancelled; no edits were applied.");
        if (result.timedOut() || result.outputTruncated() || !result.knownProcessesStopped())
            throw new IOException(failure + ": the local process could not finish safely; no edits were applied.");
        if (!Objects.equals(result.exitCode(), 0)) {
            String detail = (result.stderr() + result.stdout()).strip();
            if (detail.length() > 1200) detail = detail.substring(0, 1200);
            throw new IllegalStateException(failure + ". This check is offline and requires already-cached dependencies. No edits were applied.\n" + detail);
        }
        return result.stdout();
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Scalar type planning cancelled; no edits were applied.");
    }
}

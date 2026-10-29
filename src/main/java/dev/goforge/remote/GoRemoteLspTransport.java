package dev.goforge.remote;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** SSH stdio transport with an ordered save barrier and protocol-only URI translation. */
public final class GoRemoteLspTransport implements AutoCloseable {
    private final GoRemoteAnalysisConfig config;
    private final GoRemoteLspMapping mapping;
    private final GoRemoteStdlibSources sources=new GoRemoteStdlibSources();
    private final GoRemoteStdlibNavigation navigation;
    private final Consumer<String> notice;
    private final Runnable onFailure;
    private final String runtimeWorkFile,remoteGowork;
    private final Process process;
    private final PipedInputStream requests=new PipedInputStream(128*1024),responses=new PipedInputStream(128*1024);
    private final PipedOutputStream clientOutput,clientInput;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final CompletableFuture<Void> ready=new CompletableFuture<>(),terminated=new CompletableFuture<>();
    private final Set<String> configurationIds=ConcurrentHashMap.newKeySet();
    private volatile long remotePid=-1;
    private volatile boolean initialized,failed;

    public GoRemoteLspTransport(GoRemoteAnalysisConfig config,Map<String,String> environment,Consumer<String> notice)throws IOException {
        this(config,environment,notice,() -> {});
    }
    public GoRemoteLspTransport(GoRemoteAnalysisConfig config,Map<String,String> environment,Consumer<String> notice,Runnable onFailure)throws IOException {
        this.config=config;this.notice=notice;this.onFailure=onFailure;
        runtimeWorkFile=config.workspace().enabled()?".goforge-runtime-"+UUID.randomUUID()+".work":"";
        remoteGowork=runtimeWorkFile.isEmpty()?"off":config.remoteRoot()+"/"+runtimeWorkFile;
        mapping=new GoRemoteLspMapping(config.localRoot(),config.remoteRoot(),runtimeWorkFile);
        requireMetadataCurrent(config);
        clientOutput=new PipedOutputStream(requests);clientInput=new PipedOutputStream(responses);
        String command=GoRemoteScripts.python(GoRemoteAnalysisScript.SOURCE,config.remoteRoot(),config.name(),config.gopls(),config.profile().goExecutable(),config.workspace().workFile(),runtimeWorkFile);
        var builder=new ProcessBuilder(config.profile().ssh(dev.goforge.tools.HostTools.resolve("ssh",environment),command,false)).directory(config.localRoot().toFile());
        dev.goforge.tools.HostTools.putEnvironment(builder.environment(),environment);process=builder.start();
        navigation=new GoRemoteStdlibNavigation(mapping,sources,p -> GoRemoteLspFraming.write(process.getOutputStream(),p),p -> GoRemoteLspFraming.write(clientInput,p));
        daemon(() -> pumpClient(),"GoForge remote LSP requests");
        daemon(() -> pumpServer(),"GoForge remote LSP responses");
        daemon(() -> {try(var input=process.getErrorStream()){input.transferTo(OutputStream.nullOutputStream());}catch(IOException ignored){}},"GoForge remote LSP stderr");
        daemon(() -> {
            try {int code=process.waitFor();if(!closed.get())fail("Remote analysis connection ended (SSH exit "+code+"). Check host verification, authentication and remote gopls/Python paths; start a new analysis session.");}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            finally {ready.completeExceptionally(new IOException("Remote analysis ended before the supervisor was ready."));closeAsync();}
        },"GoForge remote LSP lifetime");
    }
    public GoRemoteStdlibSources sources(){return sources;}
    public InputStream input(){return responses;}
    public OutputStream output(){return clientOutput;}
    public boolean alive(){return !closed.get()&&process.isAlive();}
    public boolean failed(){return failed;}
    public long remotePid(){return remotePid;}
    public long sshPid(){return process.pid();}
    public void awaitReady(Duration timeout)throws Exception {ready.get(timeout.toMillis(),TimeUnit.MILLISECONDS);}
    public void awaitStopped(Duration timeout)throws Exception {terminated.get(timeout.toMillis(),TimeUnit.MILLISECONDS);}
    private void pumpClient() {
        try {
            JsonObject packet;
            while(!closed.get()&&(packet=GoRemoteLspFraming.read(requests))!=null) {
                String method=string(packet,"method");
                if(packet.has("params")&&packet.get("params").isJsonObject()) {
                    var params=packet.getAsJsonObject("params");
                    if(params.has("textDocument")&&params.getAsJsonObject("textDocument").has("uri")) {
                        try {
                            String relative=mapping.relative(params.getAsJsonObject("textDocument").get("uri").getAsString());
                            if(!config.files().contains(relative)) {
                                reject(packet,"This file was excluded from the remote snapshot. Start a new snapshot to analyze it.");continue;
                            }
                            if(GoRemoteAnalysisReview.isMetadata(relative)) {
                                if(method.equals("textDocument/didChange"))throw new IOException("Workspace/module metadata was edited. Save it and request a fresh remote analysis preview.");
                                if(method.equals("textDocument/didOpen")&&params.getAsJsonObject("textDocument").has("text")
                                        &&!GoRemoteFiles.hash(params.getAsJsonObject("textDocument").get("text").getAsString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(config.metadata().get(relative)))
                                    throw new IOException("Unsaved workspace/module metadata differs from the reviewed snapshot. Save it and start analysis again.");
                            }
                        }catch(IllegalArgumentException outside){reject(packet,outside.getMessage());continue;}
                    }
                }
                if(method.equals("workspace/executeCommand")) {reject(packet,"Remote analysis does not execute gopls commands; use the explicit SSH task actions.");continue;}
                if(method.equals("workspace/didChangeWorkspaceFolders")||method.matches("workspace/(will|did)(Create|Rename|Delete)Files")) {
                    reject(packet,"Remote analysis has a fixed snapshot file set. Stop and create a new snapshot after adding, deleting or renaming files.");continue;
                }
                JsonObject mapped;
                try {mapped=mapping.rewrite(packet,true).getAsJsonObject();}
                catch(IllegalArgumentException boundary){reject(packet,boundary.getMessage());continue;}
                if(method.equals("initialize")) {
                    initializeWorkspace(mapped,mapping,config.localRoot().getFileName().toString());
                    sanitizeSettings(mapped,remoteGowork);
                }
                if(method.equals("workspace/didChangeConfiguration")||!packet.has("method")&&packet.has("id")&&configurationIds.remove(packet.get("id").toString()))sanitizeSettings(mapped,remoteGowork);
                if(method.equals("textDocument/didSave")||method.equals("workspace/didChangeWatchedFiles"))mapped=withSavedFiles(packet,mapped,method);
                mapped=navigation.outgoing(packet,mapped);
                if(mapped!=null)GoRemoteLspFraming.write(process.getOutputStream(),mapped);
            }
        } catch(Exception e){if(!closed.get())fail("Remote analysis stopped: "+safeError(e));}
        finally {closeAsync();}
    }
    private JsonObject withSavedFiles(JsonObject original,JsonObject mapped,String method)throws IOException {
        var uris=new LinkedHashSet<String>();
        if(method.equals("textDocument/didSave"))uris.add(original.getAsJsonObject("params").getAsJsonObject("textDocument").get("uri").getAsString());
        else for(var change:original.getAsJsonObject("params").getAsJsonArray("changes")) {
            var item=change.getAsJsonObject();
            if(item.get("type").getAsInt()!=2)throw new IOException("The project file set changed; create a fresh remote analysis snapshot.");
            uris.add(item.get("uri").getAsString());
        }
        var files=new JsonArray();
        for(String uri:uris) {
            String relative=mapping.relative(uri);
            if(!config.files().contains(relative))throw new IOException("Saved file was excluded from the snapshot; create a fresh remote analysis snapshot.");
            Path path=config.localRoot().resolve(relative),walk=config.localRoot();
            for(Path part:config.localRoot().relativize(path)) {walk=walk.resolve(part);if(Files.isSymbolicLink(walk))throw new IOException("A snapshot source became a symlink; analysis was stopped.");}
            if(!path.toRealPath().startsWith(config.localRoot().toRealPath()))throw new IOException("Saved file left the bound project.");
            byte[] bytes;
            try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));var stream=java.nio.channels.Channels.newInputStream(channel)) {bytes=stream.readNBytes(GoRemoteFiles.FILE_LIMIT+1);}
            if(bytes.length>GoRemoteFiles.FILE_LIMIT)throw new IOException("Saved file exceeds the 8 MiB snapshot limit.");
            if(GoRemoteAnalysisReview.isMetadata(relative)&&!GoRemoteFiles.hash(bytes).equals(config.metadata().get(relative)))
                throw new IOException("Workspace/module metadata changed. Save metadata and create a fresh reviewed analysis snapshot.");
            var row=new JsonObject();row.addProperty("path",relative);row.addProperty("data",Base64.getEncoder().encodeToString(bytes));files.add(row);
        }
        var params=new JsonObject();params.add("files",files);params.add("message",mapped);
        var envelope=new JsonObject();envelope.addProperty("jsonrpc","2.0");envelope.addProperty("method","goforge/snapshotSync");envelope.add("params",params);return envelope;
    }
    private void pumpServer() {
        try {
            JsonObject packet;
            while(!closed.get()&&(packet=GoRemoteLspFraming.read(process.getInputStream()))!=null) {
                String method=string(packet,"method");
                if(method.equals("goforge/bridgeReady")) {
                    var params=packet.getAsJsonObject("params");remotePid=params.get("pid").getAsLong();
                    if(params.has("stdlib")&&params.getAsJsonObject("stdlib").has("root"))sources.initialize(params.getAsJsonObject("stdlib"));
                    ready.complete(null);continue;
                }
                if(method.equals("goforge/moduleCatalog")) {
                    var catalog=packet.getAsJsonObject("params");sources.initializeModules(catalog);
                    notice.accept(catalog.has("error")?GoRemoteStdlibSources.required(catalog,"error")+"\n":
                            "Remote dependency source lookup ready for "+catalog.getAsJsonArray("modules").size()+" selected module(s).\n");
                    continue;
                }
                if(method.equals("goforge/bridgeFailure")) {fail("Remote analysis stopped: "+packet.getAsJsonObject("params").get("message").getAsString());break;}
                if(navigation.incoming(packet))continue;
                if(method.startsWith("goforge/"))continue;
                if(method.equals("window/logMessage")||method.equals("$/logTrace"))continue;
                if(method.equals("workspace/configuration")&&packet.has("id"))configurationIds.add(packet.get("id").toString());
                if(packet.has("result")&&packet.get("result").isJsonObject()&&packet.getAsJsonObject("result").has("capabilities")) {
                    var caps=packet.getAsJsonObject("result").getAsJsonObject("capabilities");caps.remove("executeCommandProvider");
                    if(!initialized){initialized=true;notice.accept("Remote Go analysis initialized for "+config.workspace().modules().size()+" module(s). Unsaved Go overlays are active; saved source updates only the owned snapshot. Workspace metadata stays fixed until a fresh review.\n");}
                }
                // Keep workspace diagnostics even if a related location is an external dependency.
                if(method.equals("textDocument/publishDiagnostics"))stripExternalRelatedLocations(packet);
                try {GoRemoteLspFraming.write(clientInput,mapping.rewrite(packet,false).getAsJsonObject());}
                catch(IllegalArgumentException boundary) {
                    notice.accept(boundary.getMessage()+"\n");
                    if(packet.has("id")) {
                        if(packet.has("method"))GoRemoteLspFraming.write(process.getOutputStream(),error(packet.get("id"),boundary.getMessage()));
                        else GoRemoteLspFraming.write(clientInput,error(packet.get("id"),boundary.getMessage()));
                    }
                }
            }
        } catch(Exception e){if(!closed.get())fail("Remote analysis protocol ended: "+safeError(e));}
        finally {closeAsync();}
    }
    private void stripExternalRelatedLocations(JsonObject packet) {
        var params=packet.getAsJsonObject("params");if(params==null||!params.has("diagnostics"))return;
        for(var diagnostic:params.getAsJsonArray("diagnostics")) {
            var d=diagnostic.getAsJsonObject();if(!d.has("relatedInformation"))continue;
            var retained=new JsonArray();for(var related:d.getAsJsonArray("relatedInformation")) {
                try{mapping.rewrite(related,false);retained.add(related);}catch(IllegalArgumentException outside){/* The primary workspace diagnostic is retained. */}
            }d.add("relatedInformation",retained);
        }
    }
    static void sanitizeSettings(JsonElement value) {
        sanitizeSettings(value,"off");
    }
    static void sanitizeSettings(JsonElement value,String gowork) {
        if(value==null)return;
        if(value.isJsonArray()) {value.getAsJsonArray().forEach(item -> sanitizeSettings(item,gowork));return;}
        if(!value.isJsonObject())return;
        var object=value.getAsJsonObject();
        if(object.has("env")) {var env=new JsonObject();env.addProperty("GOWORK",gowork);env.addProperty("GOENV","off");env.addProperty("GOFLAGS","-mod=readonly");env.addProperty("GOPACKAGESDRIVER","off");env.addProperty("GOTOOLCHAIN","local");env.addProperty("GOPROXY","off");env.addProperty("GOSUMDB","off");object.add("env",env);}
        // Absolute local build flags cannot describe the remote filesystem. Only build tags are forwarded.
        if(object.has("buildFlags")) {var flags=new JsonArray();for(var flag:object.getAsJsonArray("buildFlags"))if(flag.isJsonPrimitive()&&flag.getAsString().startsWith("-tags="))flags.add(flag);object.add("buildFlags",flags);}
        for(var entry:object.entrySet())sanitizeSettings(entry.getValue(),gowork);
    }
    static void initializeWorkspace(JsonObject packet,GoRemoteLspMapping mapping,String name) {
        var params=packet.getAsJsonObject("params");params.add("processId",JsonNull.INSTANCE);params.addProperty("trace","off");
        String root=mapping.uri(Path.of(mapping.localRoot()).toUri().toASCIIString(),true);
        params.addProperty("rootUri",root);params.addProperty("rootPath",mapping.remoteRoot());
        var folder=new JsonObject();folder.addProperty("uri",root);folder.addProperty("name",name);
        var folders=new JsonArray();folders.add(folder);params.add("workspaceFolders",folders);
    }
    static void requireMetadataCurrent(GoRemoteAnalysisConfig config)throws IOException {
        for(var entry:config.metadata().entrySet()) {
            Path path=config.localRoot().resolve(entry.getKey()),walk=config.localRoot();
            for(Path part:config.localRoot().relativize(path)){walk=walk.resolve(part);if(Files.isSymbolicLink(walk))throw new IOException("Analysis metadata became a symlink; request a fresh review.");}
            byte[] bytes;
            try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));var stream=java.nio.channels.Channels.newInputStream(channel)){bytes=stream.readNBytes(GoRemoteFiles.FILE_LIMIT+1);}
            if(bytes.length>GoRemoteFiles.FILE_LIMIT||!GoRemoteFiles.hash(bytes).equals(entry.getValue()))throw new IOException("Analysis metadata changed after review; request a fresh analysis snapshot.");
        }
        if(!config.workspace().enabled()&&Files.exists(config.localRoot().resolve("go.work"),LinkOption.NOFOLLOW_LINKS))throw new IOException("A go.work appeared after review; request a fresh analysis snapshot.");
    }
    private void reject(JsonObject packet,String reason)throws IOException {notice.accept(reason+"\n");if(packet.has("id"))GoRemoteLspFraming.write(clientInput,error(packet.get("id"),reason));}
    private static JsonObject error(JsonElement id,String reason) {var response=new JsonObject();response.addProperty("jsonrpc","2.0");response.add("id",id);var error=new JsonObject();error.addProperty("code",-32001);error.addProperty("message",reason);response.add("error",error);return response;}
    private static String string(JsonObject object,String key){return object.has(key)?object.get(key).getAsString():"";}
    private static String safeError(Exception e) {return e instanceof IOException||e instanceof IllegalArgumentException?Objects.toString(e.getMessage(),"I/O failure"):"Invalid protocol packet.";}
    private void fail(String reason) {if(!closed.get()){failed=true;onFailure.run();ready.completeExceptionally(new IOException(reason));notice.accept(reason+"\nLocal files were preserved. Stop remote analysis to return to local gopls.\n");}}
    private static void daemon(Runnable run,String name){var thread=new Thread(run,name);thread.setDaemon(true);thread.start();}
    public void closeAsync(){if(closed.compareAndSet(false,true)){navigation.close();daemon(this::shutdown,"GoForge remote LSP cleanup");}}
    @Override public void close(){closeAsync();try{awaitStopped(Duration.ofSeconds(6));}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();}}
    private void shutdown() {
        try {
            close(requests);close(clientOutput);
            // EOF lets the remote supervisor terminate the entire gopls process group.
            daemon(() -> close(process.getOutputStream()),"GoForge remote LSP stdin close");
            if(!process.waitFor(3,TimeUnit.SECONDS)) {process.destroy();if(!process.waitFor(1,TimeUnit.SECONDS))process.destroyForcibly();}
            process.waitFor(1,TimeUnit.SECONDS);
        }catch(InterruptedException e){process.destroyForcibly();Thread.currentThread().interrupt();}
        finally {close(process.getInputStream());close(process.getErrorStream());close(clientInput);ready.completeExceptionally(new IOException("Remote analysis stopped."));terminated.complete(null);}
    }
    private static void close(Closeable stream){try{stream.close();}catch(IOException ignored){}}
}

package dev.goforge.debug;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.redhat.devtools.lsp4ij.dap.client.files.DAPFile;
import com.redhat.devtools.lsp4ij.dap.client.files.DAPFileRegistry;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceArguments;
import org.eclipse.lsp4j.debug.StackTraceResponse;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Per-client, display-only selected source documents. It never changes a source root or writes a source file. */
final class GoRemoteDapSources implements AutoCloseable {
    static final int FILE_LIMIT=2*1024*1024, TOTAL_LIMIT=32*1024*1024, FILE_COUNT=128;
    record Identity(int reference,String relative,String version,String sha256,String module) {
        Identity(int reference,String relative,String version,String sha256){this(reference,relative,version,sha256,null);}
    }
    private final Project project;
    private final String configuration;
    private final long session;
    private final Map<Integer,Identity> identities=new HashMap<>();
    private final Map<Integer,CompletableFuture<DAPFile>> loaded=new HashMap<>();
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private final Set<DAPFile> files=ConcurrentHashMap.newKeySet();
    private int bytes;
    private volatile boolean closed;
    private long generation;
    private boolean paused;

    GoRemoteDapSources(Project project,String configuration,long session) {
        this.project=project;this.configuration=configuration;this.session=session;
    }
    IDebugProtocolServer wrap(IDebugProtocolServer server) {
        return (IDebugProtocolServer)Proxy.newProxyInstance(IDebugProtocolServer.class.getClassLoader(),
                new Class<?>[]{IDebugProtocolServer.class},(proxy,method,args)->{
                    try {
                        if(Set.of("continue_","next","stepIn","stepOut","restart","disconnect","terminate").contains(method.getName()))resume();
                        long epoch; synchronized(this){epoch=generation;}
                        Object value=method.invoke(server,args);
                        if(!method.getName().equals("stackTrace"))return value;
                        @SuppressWarnings("unchecked") var response=(CompletableFuture<StackTraceResponse>)value;
                        own(response);
                        return own(response.thenCompose(stack->prepare(stack,server,epoch)));
                    } catch(InvocationTargetException error) { throw error.getCause(); }
                });
    }
    CompletableFuture<StackTraceResponse> prepare(StackTraceResponse stack,IDebugProtocolServer server) {
        long epoch; synchronized(this){epoch=generation;}return prepare(stack,server,epoch);
    }
    private CompletableFuture<StackTraceResponse> prepare(StackTraceResponse stack,IDebugProtocolServer server,long epoch) {
        if(!current(epoch))return CompletableFuture.failedFuture(new CancellationException("Pause expired"));
        if(stack==null||stack.getStackFrames()==null)return CompletableFuture.completedFuture(stack);
        var work=new ArrayList<CompletableFuture<?>>();
        for(var frame:stack.getStackFrames()) {
            Source source=frame.getSource();
            if(source==null||source.getAdapterData()==null)continue;
            Identity identity;
            try { identity=identity(source); }
            catch(IllegalArgumentException invalid) { unavailable(source);continue; }
            var item=load(identity,server,epoch).handle((file,error)->{
                if(error!=null||!current(epoch))unavailable(source);
                else {source.setPath(null);source.setName(name(identity));}
                return null;
            });
            work.add(item);
        }
        return own(CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).thenApply(ignored->{
            if(!current(epoch))throw new CancellationException("Pause expired");return stack;
        }));
    }
    private synchronized CompletableFuture<DAPFile> load(Identity identity,IDebugProtocolServer server,long epoch) {
        if(!current(epoch))return CompletableFuture.failedFuture(new CancellationException("Debug session ended"));
        Identity previous=identities.get(identity.reference());
        if(previous!=null&&!previous.equals(identity))return CompletableFuture.failedFuture(new IllegalArgumentException("SDK reference changed"));
        var existing=loaded.get(identity.reference());if(existing!=null)return existing;
        if(identities.size()>=FILE_COUNT)return CompletableFuture.failedFuture(new IllegalArgumentException("SDK source count exceeded"));
        identities.put(identity.reference(),identity);
        var args=new SourceArguments();args.setSourceReference(identity.reference());
        CompletableFuture<DAPFile> result;
        var reserved=new java.util.concurrent.atomic.AtomicInteger();
        try {
            var request=own(server.source(args));
            result=own(request.thenCompose(response->{
                String text=response==null?null:response.getContent();int size=verify(identity,text);
                synchronized(this) {
                    if(!current(epoch))throw new CancellationException("Pause expired");
                    if(bytes+size>TOTAL_LIMIT)throw new IllegalArgumentException("SDK source cache exceeded");
                    bytes+=size;reserved.set(size);
                }
                return install(identity,text,epoch);
            }));
        } catch(RuntimeException error) { result=CompletableFuture.failedFuture(error); }
        loaded.put(identity.reference(),result);
        // Failure does not mark the upstream file loaded and a later pause may retry.
        result.whenComplete((file,error)->{if(error!=null)synchronized(this){loaded.remove(identity.reference());bytes=Math.max(0,bytes-reserved.getAndSet(0));}});
        return result;
    }
    private CompletableFuture<DAPFile> install(Identity identity,String text,long epoch) {
        var result=own(new CompletableFuture<DAPFile>());
        ApplicationManager.getApplication().invokeLater(()->{
            if(!current(epoch)||project.isDisposed()){result.cancel(false);return;}
            try {
                WriteAction.run(()->{synchronized(this){
                    if(!current(epoch))throw new CancellationException("Pause expired");
                    var file=DAPFileRegistry.getInstance().getOrCreateDAPFile(configuration,name(identity),project);files.add(file);
                    file.setContent(null,text.replace("\r\n","\n"),false);
                    var document=FileDocumentManager.getInstance().getDocument(file);
                    if(document==null)throw new IllegalStateException("SDK source document unavailable");
                    document.setReadOnly(true);
                    // The stock loader is bypassed only after verified content was installed.
                    file.shouldReload(session);result.complete(file);
                }});
            } catch(Throwable error) { result.completeExceptionally(error); }
        });
        return result;
    }
    static Identity identity(Source source) {
        try {
            JsonObject data=new Gson().toJsonTree(source.getAdapterData()).getAsJsonObject();
            boolean module=data.has("goforgeModuleSource");String marker=module?"goforgeModuleSource":"goforgeSdkSource";
            Set<String> keys=module?Set.of(marker,"module","version","relative","sha256"):Set.of(marker,"version","relative","sha256");
            if(!data.keySet().equals(keys)||!data.get(marker).isJsonPrimitive()
                    ||!data.getAsJsonPrimitive(marker).isNumber()
                    ||data.get(marker).getAsBigDecimal().compareTo(java.math.BigDecimal.ONE)!=0)throw new IllegalArgumentException();
            for(String key:List.of("relative","version","sha256"))if(!data.get(key).isJsonPrimitive()||!data.getAsJsonPrimitive(key).isString())throw new IllegalArgumentException();
            int reference=source.getSourceReference()==null?0:source.getSourceReference();
            String path=data.get("relative").getAsString(),version=data.get("version").getAsString(),hash=data.get("sha256").getAsString();
            if(reference<1000000||reference>=1000000+FILE_COUNT||path.length()>4096||!path.endsWith(".go")
                    ||version.isBlank()||version.length()>100||!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
            String[] parts=path.split("/",-1);if(parts.length>64)throw new IllegalArgumentException();
            for(String part:parts)if(part.isEmpty()||part.equals(".")||part.equals("..")||part.indexOf('\\')>=0)throw new IllegalArgumentException();
            if((path+version).chars().anyMatch(c->c<32||c==127))throw new IllegalArgumentException();
            String modulePath=null,origin="Selected remote SDK "+version;
            if(module){
                if(!data.get("module").isJsonPrimitive()||!data.getAsJsonPrimitive("module").isString())throw new IllegalArgumentException();
                modulePath=data.get("module").getAsString();
                if(modulePath.length()>4096||!modulePath.matches("[A-Za-z0-9._~+/-]+")
                        ||!version.matches("v[0-9]+[.][0-9]+[.][0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?(?:[+]incompatible)?"))throw new IllegalArgumentException();
                String[] moduleParts=modulePath.split("/",-1);if(moduleParts.length>64)throw new IllegalArgumentException();
                for(String part:moduleParts)if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException();
                origin="Selected remote module "+modulePath+"@"+version;
            }
            if(source.getPath()!=null||!path.equals(source.getName())||!origin.equals(source.getOrigin()))throw new IllegalArgumentException();
            return new Identity(reference,path,version,hash,modulePath);
        } catch(RuntimeException invalid) { throw new IllegalArgumentException("Invalid selected source capability"); }
    }
    static int verify(Identity identity,String text) {
        if(text==null||text.indexOf(0)>=0||text.length()>FILE_LIMIT)throw new IllegalArgumentException("Invalid SDK source content");
        byte[] data;
        try {
            var encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));
            data=new byte[encoded.remaining()];encoded.get(data);
        } catch(java.nio.charset.CharacterCodingException invalid) {throw new IllegalArgumentException("Invalid SDK source encoding");}
        if(data.length>FILE_LIMIT)throw new IllegalArgumentException("SDK source content too large");
        try {
            if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).equals(identity.sha256()))throw new IllegalArgumentException("SDK source checksum changed");
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        return data.length;
    }
    static String name(Identity identity){return (identity.module()==null?"Selected SDK "+identity.version():"Selected module "+identity.module()+"@"+identity.version())+" — "+identity.relative();}
    private static void unavailable(Source source){source.setPath(null);source.setSourceReference(0);source.setAdapterData(null);source.setPresentationHint(org.eclipse.lsp4j.debug.SourcePresentationHint.DEEMPHASIZE);source.setName("Selected source unavailable");}
    private <T> CompletableFuture<T> own(CompletableFuture<T> future) {
        pending.add(future);future.whenComplete((value,error)->pending.remove(future));
        if(closed)future.cancel(true);return future;
    }
    synchronized boolean current(long epoch){return !closed&&paused&&generation==epoch;}
    void newPause(){advance(true);}
    void resume(){advance(false);}
    private void advance(boolean nowPaused){
        synchronized(this){if(closed)return;generation++;paused=nowPaused;for(var future:pending)future.cancel(true);}
    }
    @Override public void close() {
        synchronized(this){if(closed)return;closed=true;loaded.clear();identities.clear();bytes=0;}
        for(var future:pending)future.cancel(true);
        ApplicationManager.getApplication().invokeLater(()->{
            WriteAction.run(()->{
                for(var file:files) {
                    if(!project.isDisposed())FileEditorManager.getInstance(project).closeFile(file);
                    var document=FileDocumentManager.getInstance().getCachedDocument(file);
                    file.setContent(null,"",false);
                    if(document!=null){
                        document.setReadOnly(false);
                        FileDocumentManager.getInstance().reloadFromDisk(document);
                        document.setReadOnly(true);
                        // Reload schedules a PSI commit. Finish it while this owned file is still valid.
                        if(!project.isDisposed())PsiDocumentManager.getInstance(project).commitDocument(document);
                    }
                    file.setValid(false);
                }
                files.clear();
            });
        });
    }
}

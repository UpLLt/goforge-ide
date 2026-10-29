package dev.goforge.remote;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Response capabilities for two read-only methods; the general URI/edit mapper stays strict. */
final class GoRemoteStdlibNavigation implements AutoCloseable {
    @FunctionalInterface interface Sender { void send(JsonObject packet)throws IOException; }
    private static final int MAX_PENDING=128,MAX_FILES=8,MAX_RESULT_BYTES=8*1024*1024;
    private final String prefix="goforge-nav-"+UUID.randomUUID()+"-";
    private final GoRemoteLspMapping mapping;
    private final GoRemoteStdlibSources sources;
    private final Sender remote,client;
    private final Map<String,Pending> pending=new HashMap<>();
    private final Map<String,String> originalIds=new HashMap<>();
    private final Map<String,Read> reads=new HashMap<>();
    private final ScheduledExecutorService timer;
    private long sequence;
    private boolean closed;
    private record Target(JsonObject location,String uriKey,GoRemoteStdlibSources.Target source,boolean link){}
    private record Read(Pending pending,GoRemoteStdlibSources.Target source){}
    private static final class Pending {
        final String wire,origin;final JsonElement original;final long deadline;
        JsonObject response;final List<Target> targets=new ArrayList<>();
        final Map<String,GoRemoteStdlibSources.Source> completed=new HashMap<>();
        final Map<String,GoRemoteStdlibSources.Target> paths=new LinkedHashMap<>();int bytes;
        Pending(String wire,String origin,JsonElement original,long deadline){this.wire=wire;this.origin=origin;this.original=original;this.deadline=deadline;}
    }
    GoRemoteStdlibNavigation(GoRemoteLspMapping mapping,GoRemoteStdlibSources sources,Sender remote,Sender client){
        this.mapping=mapping;this.sources=sources;this.remote=remote;this.client=client;
        timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"GoForge remote definition deadline");t.setDaemon(true);return t;});
        timer.scheduleWithFixedDelay(this::expire,100,100,TimeUnit.MILLISECONDS);
    }
    /** Returns null when this packet is rejected; every accepted packet is sent by the caller. */
    synchronized JsonObject outgoing(JsonObject original,JsonObject mapped)throws IOException {
        if(closed)throw new IOException("Remote source session stopped.");
        String method=method(original);
        if(method.startsWith("goforge/")||original.has("id")&&reserved(original.get("id"))) {
            if(original.has("id"))client.send(error(original.get("id"),"Reserved remote bridge protocol is unavailable to clients."));return null;
        }
        if(method.equals("$/cancelRequest")) {
            var params=original.getAsJsonObject("params");String wire=params==null?null:originalIds.get(key(params.get("id")));
            if(wire!=null){Pending entry=pending.get(wire);mapped.getAsJsonObject("params").addProperty("id",wire);remove(entry);}
            return mapped;
        }
        if(Set.of("textDocument/didOpen","textDocument/didChange","textDocument/didClose").contains(method)) {
            String origin=origin(original);
            for(Pending entry:List.copyOf(pending.values()))if(entry.origin.equals(origin))fail(entry,"The source document changed while its remote definition was loading.");
        }
        if(!Set.of("textDocument/definition","textDocument/typeDefinition").contains(method))return mapped;
        JsonElement id=original.get("id");
        if(id==null||id.isJsonNull()||!id.isJsonPrimitive()||!id.getAsJsonPrimitive().isNumber()&&!id.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("A definition request requires a JSON-RPC ID.");
        String origin=origin(original);
        if(pending.size()>=MAX_PENDING||originalIds.containsKey(key(id))) {client.send(error(id,"Too many or duplicate pending remote definition requests."));return null;}
        String wire=prefix+(++sequence);
        var entry=new Pending(wire,origin,id.deepCopy(),System.nanoTime()+TimeUnit.SECONDS.toNanos(15));
        pending.put(wire,entry);originalIds.put(key(id),wire);mapped.addProperty("id",wire);return mapped;
    }
    synchronized boolean incoming(JsonObject packet)throws IOException {
        if(closed)return true;
        if(Set.of("goforge/stdlibSource","goforge/moduleSource").contains(method(packet))){sourceResponse(method(packet),packet.getAsJsonObject("params"));return true;}
        if(packet.has("method")||!packet.has("id")||!reserved(packet.get("id")))return false;
        Pending entry=pending.get(packet.get("id").getAsString());
        if(entry==null)return true; // cancelled/expired old wire IDs never become a new capability
        if(entry.response!=null){fail(entry,"Remote definition replied more than once.");return true;}
        try {
            if(packet.has("error")) {var result=mapping.rewrite(packet,false).getAsJsonObject();result.add("id",entry.original);remove(entry);client.send(result);return true;}
            if(!packet.has("result"))throw new IllegalArgumentException("Remote definition has no result.");
            entry.response=packet.deepCopy();entry.response.add("id",entry.original);
            JsonElement value=entry.response.get("result");
            if(!value.isJsonNull()) {
                if(value.isJsonArray()) {if(value.getAsJsonArray().size()>256)throw new IllegalArgumentException("Remote definition has too many targets.");for(JsonElement location:value.getAsJsonArray())prepare(entry,location.getAsJsonObject());}
                else prepare(entry,value.getAsJsonObject());
            }
            if(entry.paths.isEmpty()){complete(entry);return true;}
            if(entry.paths.size()>MAX_FILES)throw new IllegalArgumentException("Remote definition exceeds the source file limit.");
            for(var target:entry.paths.values()){
                String readId=entry.wire+"/"+(++sequence);reads.put(readId,new Read(entry,target));
                var params=new JsonObject();params.addProperty("id",readId);params.addProperty("token",target.token());params.addProperty("path",target.path());
                var request=new JsonObject();request.addProperty("jsonrpc","2.0");request.addProperty("method",target.requestMethod());request.add("params",params);remote.send(request);
            }
        }catch(IllegalArgumentException|IllegalStateException invalid){fail(entry,Objects.toString(invalid.getMessage(),"Invalid remote definition response."));}
        return true;
    }
    private void prepare(Pending entry,JsonObject location){
        boolean link=location.has("targetUri");
        Set<String> allowed=link?Set.of("targetUri","targetRange","targetSelectionRange","originSelectionRange"):Set.of("uri","range");
        if(!allowed.containsAll(location.keySet())||link&&(!location.has("targetRange")||!location.has("targetSelectionRange"))||!link&&!location.has("range"))
            throw new IllegalArgumentException("Unexpected remote definition shape.");
        String key=link?"targetUri":"uri",uri=GoRemoteStdlibSources.required(location,key);
        try {location.addProperty(key,mapping.uri(uri,false));return;}catch(IllegalArgumentException outside){/* Only the matched definition target may use this exception. */}
        var target=sources.target(uri);entry.paths.put(target.key(),target);entry.targets.add(new Target(location,key,target,link));
        // The optional origin range is unnecessary to open the destination; don't pass unvalidated overlay ranges.
        location.remove("originSelectionRange");
    }
    private void sourceResponse(String method,JsonObject result)throws IOException {
        if(result==null)return;
        String id=GoRemoteStdlibSources.required(result,"id");Read read=reads.remove(id);if(read==null)return;
        Pending entry=read.pending();if(pending.get(entry.wire)!=entry)return;
        try {
            if(!method.equals(read.source().responseMethod()))throw new IllegalArgumentException("Remote source reply used the wrong capability kind.");
            if(result.has("error"))throw new IllegalArgumentException(GoRemoteStdlibSources.required(result,"error"));
            var source=sources.accept(read.source(),result);entry.bytes+=source.byteSize();
            if(entry.bytes>MAX_RESULT_BYTES)throw new IllegalArgumentException("Remote definition source size limit reached.");
            entry.completed.put(read.source().key(),source);
            if(entry.completed.size()!=entry.paths.size())return;
            for(Target target:entry.targets){
                var fetched=entry.completed.get(target.source().key());String text=fetched.text();
                if(target.link()){
                    var range=target.location().getAsJsonObject("targetRange");var selection=target.location().getAsJsonObject("targetSelectionRange");
                    GoRemoteStdlibSources.range(range,text);GoRemoteStdlibSources.range(selection,text);
                    if(GoRemoteStdlibSources.offset(selection.getAsJsonObject("start"),text)<GoRemoteStdlibSources.offset(range.getAsJsonObject("start"),text)
                            ||GoRemoteStdlibSources.offset(selection.getAsJsonObject("end"),text)>GoRemoteStdlibSources.offset(range.getAsJsonObject("end"),text))throw new IllegalArgumentException("Definition selection leaves its source range.");
                }else GoRemoteStdlibSources.range(target.location().getAsJsonObject("range"),text);
                target.location().addProperty(target.uriKey(),fetched.uri());
            }
            complete(entry);
        }catch(IllegalArgumentException|IllegalStateException invalid){fail(entry,Objects.toString(invalid.getMessage(),"Invalid remote source response."));}
    }
    private void complete(Pending entry)throws IOException {
        if(closed||pending.get(entry.wire)!=entry)return;
        if(System.nanoTime()>entry.deadline){fail(entry,"Remote definition source lookup timed out.");return;}
        remove(entry);client.send(entry.response);
    }
    private String origin(JsonObject packet){return mapping.relative(GoRemoteStdlibSources.required(packet.getAsJsonObject("params").getAsJsonObject("textDocument"),"uri"));}
    private boolean reserved(JsonElement id){return id!=null&&id.isJsonPrimitive()&&id.getAsJsonPrimitive().isString()&&id.getAsString().startsWith(prefix);}
    private static String key(JsonElement id){return id==null?"":id.toString();}
    private static String method(JsonObject packet){return packet.has("method")?packet.get("method").getAsString():"";}
    private void remove(Pending entry){pending.remove(entry.wire);originalIds.remove(key(entry.original),entry.wire);reads.entrySet().removeIf(item->item.getValue().pending()==entry);}
    private void fail(Pending entry,String reason)throws IOException {if(pending.get(entry.wire)!=entry)return;remove(entry);client.send(error(entry.original,reason));}
    private synchronized void expire(){if(closed)return;for(Pending entry:List.copyOf(pending.values()))if(System.nanoTime()>entry.deadline)try{fail(entry,"Remote definition source lookup timed out.");}catch(IOException ignored){close();}}
    private static JsonObject error(JsonElement id,String reason){var result=new JsonObject();result.addProperty("jsonrpc","2.0");result.add("id",id);var error=new JsonObject();error.addProperty("code",-32801);error.addProperty("message",reason);result.add("error",error);return result;}
    @Override public synchronized void close(){if(closed)return;closed=true;pending.clear();originalIds.clear();reads.clear();timer.shutdownNow();sources.close();}
}

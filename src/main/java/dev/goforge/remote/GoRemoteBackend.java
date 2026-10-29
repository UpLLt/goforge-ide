package dev.goforge.remote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class GoRemoteBackend {
    public enum GoTask { ENV, BUILD, TEST, RUN }
    public record Entry(String name,String kind,long bytes) { }
    private final String ssh;private final Path local;private final Map<String,String> environment;
    public GoRemoteBackend(String ssh,Path local,Map<String,String> environment) {
        this.ssh=ssh;this.local=local;this.environment=Map.copyOf(environment);
    }
    public JsonObject check(GoRemoteProfile p,BooleanSupplier cancel) throws Exception {return files(p,"check","",new byte[0],cancel);}
    public List<Entry> list(GoRemoteProfile p,String relative,BooleanSupplier cancel) throws Exception {
        if(!relative.isEmpty())GoRemoteProfile.relative(relative);
        var rows=files(p,"list",relative,new byte[0],cancel).getAsJsonArray("entries");var entries=new ArrayList<Entry>();
        for(var row:rows) {var v=row.getAsJsonObject();entries.add(new Entry(v.get("name").getAsString(),v.get("kind").getAsString(),v.get("bytes").getAsLong()));}
        return List.copyOf(entries);
    }
    public JsonObject upload(GoRemoteProfile p,String name,GoRemoteFiles.Snapshot snapshot,BooleanSupplier cancel) throws Exception {
        if(!name.matches("goforge-upload-[A-Za-z0-9-]+"))throw new IllegalArgumentException("Invalid snapshot name.");
        return files(p,"snapshot",name,snapshot.payload(),cancel);
    }
    public JsonObject analysisSnapshot(GoRemoteProfile p,String name,GoRemoteFiles.Snapshot snapshot,BooleanSupplier cancel) throws Exception {
        if(!name.matches("goforge-analysis-[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid analysis snapshot name.");
        if(snapshot.files().stream().noneMatch(f -> f.path().equals("go.mod")||f.path().equals("go.work")))throw new IllegalArgumentException("Remote analysis requires a project-root go.mod or a reviewed go.work.");
        return files(p,"analysis",name,snapshot.payload(),cancel);
    }
    public byte[] download(GoRemoteProfile p,String relative,BooleanSupplier cancel) throws Exception {
        GoRemoteProfile.relative(relative);var response=files(p,"download",relative,new byte[0],cancel);
        byte[] data=Base64.getDecoder().decode(response.get("data").getAsString());
        if(data.length>GoRemoteFiles.FILE_LIMIT||!GoRemoteFiles.hash(data).equals(response.get("sha256").getAsString()))throw new IOException("Downloaded content failed size/hash validation.");
        return data;
    }
    public GoRemoteProcess.Result go(GoRemoteProfile p,GoTask task,BooleanSupplier cancel,Consumer<String> output) throws Exception {
        String command=GoRemoteScripts.python(GoRemoteScripts.GO,p.remoteDirectory(),p.goExecutable(),task.name().toLowerCase(Locale.ROOT));
        return GoRemoteProcess.run(p.ssh(ssh,command,false),local,environment,new byte[0],true,cancel,Duration.ofMinutes(30),output);
    }
    private JsonObject files(GoRemoteProfile p,String operation,String relative,byte[] input,BooleanSupplier cancel) throws Exception {
        String command=GoRemoteScripts.python(GoRemoteScripts.FILES,p.remoteDirectory(),operation,relative);
        var result=GoRemoteProcess.run(p.ssh(ssh,command,false),local,environment,input,false,cancel,Duration.ofMinutes(2),ignored -> { });
        if(!result.success())throw new IOException(result.failure());
        try {return JsonParser.parseString(result.stdout()).getAsJsonObject();}
        catch(RuntimeException e) {throw new IOException("Remote helper did not return a valid response. Check Python3 and shell startup output.",e);}
    }
}

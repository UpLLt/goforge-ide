package dev.goforge.remote;

import com.google.gson.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Locale;

/** Maps protocol URI fields, never source text, completion insertions or diagnostic prose. */
public final class GoRemoteLspMapping {
    private final Path localPath, canonicalLocalPath;
    private final String local,remote;
    private final String runtimeWorkFile;
    private final java.util.concurrent.ConcurrentMap<String,String> clientUris=new java.util.concurrent.ConcurrentHashMap<>();
    public GoRemoteLspMapping(Path local,String remote) {
        this(local,remote,"");
    }
    GoRemoteLspMapping(Path local,String remote,String runtimeWorkFile) {
        if(!runtimeWorkFile.isEmpty()&&!runtimeWorkFile.matches("[.]goforge-runtime-[a-f0-9-]{36}[.]work"))throw new IllegalArgumentException("Invalid private workspace name.");
        this.runtimeWorkFile=runtimeWorkFile;
        localPath=local.toAbsolutePath().normalize();this.local=localPath.toString();
        String root=GoRemoteProfile.absoluteRemote(remote);
        while(root.endsWith("/"))root=root.substring(0,root.length()-1);
        this.remote=root;
        Path canonical=localPath;try{canonical=local.toRealPath();}catch(java.io.IOException ignored){}canonicalLocalPath=canonical;
    }
    public String localRoot(){return local;}
    public String remoteRoot(){return remote;}
    public String uri(String value,boolean toRemote) {
        URI uri;
        try {uri=URI.create(value);}catch(IllegalArgumentException e){throw new IllegalArgumentException("Invalid document URI.");}
        if(!"file".equals(uri.getScheme())||uri.getRawAuthority()!=null&&!uri.getRawAuthority().isEmpty()
                ||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new IllegalArgumentException("Remote analysis supports local file URIs only.");
        if(toRemote) {
            final Path path;
            try {path=Path.of(uri).toAbsolutePath().normalize();}
            catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid local document URI.");}
            Path base=path.startsWith(localPath)?localPath:canonicalLocalPath;
            if(!path.startsWith(base))throw new IllegalArgumentException("Document is outside the bound local project.");
            String relative=base.relativize(path).toString().replace(java.io.File.separatorChar,'/');
            if(!relative.isEmpty())GoRemoteProfile.relative(relative);
            String suffix=relative.isEmpty()?"":"/"+relative;
            clientUris.put(suffix,value);
            try{return new URI("file","",remote+suffix,null).toASCIIString();}
            catch(URISyntaxException e){throw new IllegalArgumentException("Invalid mapped document URI.");}
        }
        // Remote POSIX paths must never be interpreted by the local Windows filesystem.
        String path;
        try {path=new URI(null,null,uri.getPath(),null).normalize().getPath();
            while(path!=null && path.length()>1 && path.endsWith("/"))path=path.substring(0,path.length()-1);
        }
        catch(URISyntaxException e){throw new IllegalArgumentException("Invalid remote document URI.");}
        if(path==null||!path.equals(remote)&&!path.startsWith(remote+"/"))
            throw new IllegalArgumentException("Remote dependency is outside the analysis snapshot; external dependency navigation is unavailable.");
        String suffix=path.substring(remote.length());
        if(!runtimeWorkFile.isEmpty()) {
            if(suffix.equals("/"+runtimeWorkFile))suffix="/go.work";
            else if(suffix.equals("/"+runtimeWorkFile+".sum"))suffix="/go.work.sum";
        }
        if(!suffix.isEmpty())GoRemoteProfile.relative(suffix.substring(1));
        String known=clientUris.get(suffix);if(known!=null)return known;
        return (suffix.isEmpty()?localPath:localPath.resolve(suffix.substring(1))).toUri().toASCIIString();
    }
    public String relative(String localUri) {
        String mapped=URI.create(uri(localUri,true)).getPath();
        if(mapped.equals(remote))throw new IllegalArgumentException("Expected a file inside the project.");
        return GoRemoteProfile.relative(mapped.substring(remote.length()+1));
    }
    public JsonElement rewrite(JsonElement value,boolean toRemote) {return rewrite(value,toRemote,"");}
    private JsonElement rewrite(JsonElement value,boolean toRemote,String key) {
        if(value==null||value.isJsonNull())return JsonNull.INSTANCE;
        if(value.isJsonObject()) {
            var result=new JsonObject();
            for(var entry:value.getAsJsonObject().entrySet()) {
                String name=entry.getKey();
                // WorkspaceEdit.changes and change annotations can use URI map keys.
                String mapped=name.startsWith("file:")?uri(name,toRemote):name;
                result.add(mapped,rewrite(entry.getValue(),toRemote,name));
            }
            return result;
        }
        if(value.isJsonArray()) {var result=new JsonArray();for(var item:value.getAsJsonArray())result.add(rewrite(item,toRemote,key));return result;}
        String lower=key.toLowerCase(Locale.ROOT);
        if(value.getAsJsonPrimitive().isString()) {
            String text=value.getAsString();
            if((lower.endsWith("uri")||lower.endsWith("uris"))&&!text.isEmpty())return new JsonPrimitive(uri(text,toRemote));
            if(lower.equals("rootpath"))return new JsonPrimitive(toRemote?remote:local);
            if(lower.equals("pattern")||lower.equals("globpattern")) {
                String path=toRemote?text.replace(java.io.File.separatorChar,'/'):text;
                String from=toRemote?local.replace(java.io.File.separatorChar,'/'):remote;
                if(path.startsWith(from+"/"))return new JsonPrimitive((toRemote?remote:local.replace(java.io.File.separatorChar,'/'))+path.substring(from.length()));
            }
        }
        return value.deepCopy();
    }
}

package dev.goforge.remote;

import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable, bounded source snapshots belonging to exactly one SSH transport. No filesystem writes. */
public final class GoRemoteStdlibSources implements AutoCloseable {
    public static final int FILE_LIMIT=2*1024*1024, CACHE_LIMIT=32*1024*1024, FILE_COUNT=128;
    public record Source(String uri,String remotePath,String sdkVersion,String text,String sha256,int byteSize) { }
    private final String prefix="goforge-remote-source://"+UUID.randomUUID()+"/";
    private final Map<String,Source> byUri=new HashMap<>(),byPath=new HashMap<>();
    private String root,token,version;
    private int bytes;
    private boolean closed;
    private Runnable onClose=()->{};

    public synchronized void initialize(JsonObject sdk) {
        if(closed||root!=null)throw new IllegalArgumentException("Remote SDK identity was repeated or expired.");
        String path=required(sdk,"root"),identity=required(sdk,"token"),release=required(sdk,"version");
        if(!path.startsWith("/")||path.endsWith("/")||!identity.matches("[a-f0-9]{64}")||release.length()>100)
            throw new IllegalArgumentException("Invalid remote SDK identity.");
        segments(path.substring(1),false);
        root=path;token=identity;version=release;
    }
    public synchronized String token(){requireOpen();if(token==null)throw new IllegalArgumentException("Remote SDK source identity is unavailable.");return token;}
    public synchronized String relative(String uri) {
        token();
        try {
            URI parsed=URI.create(uri);
            if(!"file".equals(parsed.getScheme())||parsed.getRawAuthority()!=null&&!parsed.getRawAuthority().isEmpty()
                    ||parsed.getRawQuery()!=null||parsed.getRawFragment()!=null||parsed.getRawPath()==null
                    ||parsed.getRawPath().toLowerCase(Locale.ROOT).matches(".*%(2f|5c).*"))throw new IllegalArgumentException();
            String path=parsed.getPath();
            if(path==null||!path.startsWith(root+"/"))throw new IllegalArgumentException();
            String relative=path.substring(root.length()+1);segments(relative,true);return relative;
        }catch(RuntimeException invalid){throw new IllegalArgumentException("The definition is outside this session's verified remote GOROOT/src.");}
    }
    public synchronized Source accept(String relative,JsonObject response) {
        requireOpen();segments(relative,true);
        if(!token().equals(required(response,"token"))||!relative.equals(required(response,"path")))
            throw new IllegalArgumentException("Remote source response identity changed.");
        String encoded=required(response,"data"),digest=required(response,"sha256");
        if(encoded.length()>4*((FILE_LIMIT+2)/3)||!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Remote source exceeds its limit.");
        byte[] data;
        try{data=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Invalid remote source encoding.");}
        if(data.length>FILE_LIMIT||!GoRemoteFiles.hash(data).equals(digest))throw new IllegalArgumentException("Remote source content did not match its digest.");
        String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();}
        catch(java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("Remote Go source is not valid UTF-8.");}
        if(text.indexOf('\0')>=0)throw new IllegalArgumentException("Remote Go source contains NUL.");
        var existing=byPath.get(relative);
        if(existing!=null){if(!existing.sha256().equals(digest))throw new IllegalArgumentException("The remote SDK source changed; restart remote analysis.");return existing;}
        if(byUri.size()>=FILE_COUNT||bytes+data.length>CACHE_LIMIT)throw new IllegalArgumentException("Remote source cache limit reached; restart analysis to clear it.");
        var source=new Source(prefix+UUID.randomUUID(),root+"/"+relative,version,text.replace("\r\n","\n").replace('\r','\n'),digest,data.length);
        byUri.put(source.uri(),source);byPath.put(relative,source);bytes+=data.length;return source;
    }
    public synchronized Source find(String uri){return closed?null:byUri.get(uri);}
    public synchronized boolean isOpen(){return !closed;}
    public synchronized void whenClosed(Runnable action){if(closed)action.run();else onClose=Objects.requireNonNull(action);}
    @Override public void close(){Runnable callback;synchronized(this){if(closed)return;closed=true;byUri.clear();byPath.clear();bytes=0;callback=onClose;onClose=()->{};}callback.run();}
    private void requireOpen(){if(closed)throw new IllegalArgumentException("Remote source session has stopped.");}
    static String required(JsonObject value,String key){if(value==null||!value.has(key)||!value.get(key).isJsonPrimitive()||!value.getAsJsonPrimitive(key).isString())throw new IllegalArgumentException("Invalid remote source field: "+key);return value.get(key).getAsString();}
    static void segments(String path,boolean goFile){
        var parts=path.split("/",-1);
        if(parts.length>64||path.length()>4096||goFile&&!path.endsWith(".go"))throw new IllegalArgumentException("Invalid remote source path.");
        for(String part:parts)if(part.isEmpty()||part.equals(".")||part.equals("..")||part.indexOf('\\')>=0
                ||part.indexOf('\uFFFD')>=0||part.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid remote source path.");
    }
    static void range(JsonObject range,String text){
        if(range==null||!range.keySet().equals(Set.of("start","end")))throw new IllegalArgumentException("Invalid source range.");
        int start=offset(range.getAsJsonObject("start"),text),end=offset(range.getAsJsonObject("end"),text);
        if(start>end)throw new IllegalArgumentException("Reversed source range.");
    }
    static int offset(JsonObject position,String text){
        if(position==null||!position.keySet().equals(Set.of("line","character")))throw new IllegalArgumentException("Invalid source position.");
        int line=integer(position,"line"),character=integer(position,"character"),start=0;
        for(int i=0;i<line;i++){int next=text.indexOf('\n',start);if(next<0)throw new IllegalArgumentException("Source line is outside the fetched file.");start=next+1;}
        int end=text.indexOf('\n',start);if(end<0)end=text.length();
        if(character>end-start)throw new IllegalArgumentException("Source character is outside the fetched line.");
        int value=start+character;
        if(value>start&&value<end&&Character.isHighSurrogate(text.charAt(value-1))&&Character.isLowSurrogate(text.charAt(value)))throw new IllegalArgumentException("Source range splits a UTF-16 character.");
        return value;
    }
    private static int integer(JsonObject object,String key){
        var value=object.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!value.getAsString().matches("0|[1-9][0-9]{0,8}"))throw new IllegalArgumentException("Invalid source position.");
        return value.getAsInt();
    }
}

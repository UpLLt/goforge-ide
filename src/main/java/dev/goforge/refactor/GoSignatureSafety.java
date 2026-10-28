package dev.goforge.refactor;

import com.google.gson.Gson;
import dev.goforge.coverage.GoCoverageProcess;
import dev.goforge.tools.HostTools;
import org.eclipse.lsp4j.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Conservative AST proof for the pinned gopls signature transform's evaluation-order limitation. */
public final class GoSignatureSafety {
    private GoSignatureSafety() { }
    @FunctionalInterface public interface References { List<? extends Location> find(Location declaration) throws Exception; }
    private record Input(String mode,List<Source> sources,Location target,List<Location> references,String originalSignature,String requestedSignature) { }
    private record Source(String uri,String text,String after) { }
    private record Result(boolean passed,String reason,Location target,int checked) { }
    public static boolean required(String kind) {
        return Set.of("refactor.rewrite.moveParamLeft","refactor.rewrite.moveParamRight",
                "refactor.rewrite.removeUnusedParam","goforge.changeSignature").contains(kind == null ? "" : kind);
    }
    public static int verify(String go,Map<String,String> environment,Collection<GoRefactorWorkspaceEdit.Source> snapshot,
            String uri,Position position,GoRefactorWorkspaceEdit plan,References references,BooleanSupplier cancelled) throws Exception {
        return verify(go,environment,snapshot,uri,position,plan,null,null,references,cancelled);
    }
    public static int verify(String go,Map<String,String> environment,Collection<GoRefactorWorkspaceEdit.Source> snapshot,
            String uri,Position position,GoRefactorWorkspaceEdit plan,String originalSignature,String requestedSignature,
            References references,BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(references);Objects.requireNonNull(cancelled);
        if(cancelled.getAsBoolean())throw new CancellationException();
        var sources=new LinkedHashMap<Path,GoRefactorWorkspaceEdit.Source>();
        for(var source:snapshot)if(sources.putIfAbsent(GoRefactorWorkspaceEdit.uriPath(source.uri()),source)!=null)
            throw new IllegalArgumentException("Duplicate safety-check snapshot source.");
        var selected=sources.get(GoRefactorWorkspaceEdit.uriPath(uri));
        if(selected==null)throw new IllegalArgumentException("Signature selection is outside the snapshot.");
        var after=new HashMap<Path,String>();
        for(var file:plan.files()){
            if(file.created())throw new IllegalArgumentException("Signature changes cannot create files.");
            var key=GoRefactorWorkspaceEdit.uriPath(file.uri());var original=sources.get(key);
            if(original==null || !original.text().equals(file.edit().original()))throw new IllegalArgumentException("Signature plan differs from its snapshot.");
            after.put(key,file.edit().replacement());
        }
        var inputs=sources.entrySet().stream().map(e->new Source(e.getValue().uri(),e.getValue().text(),after.get(e.getKey()))).toList();
        var target=new Location(selected.uri(),new Range(position,position));
        Path owned=Files.createTempDirectory("goforge-signature-check-");
        try{
            Path source=owned.resolve("checker.go"),binary=owned.resolve(HostTools.executableName("checker"));
            Files.writeString(source,GoSignatureSafetySource.TEXT,StandardCharsets.UTF_8);
            var env=checkerEnvironment(environment);
            run(List.of(go,"build","-trimpath","-buildvcs=false","-o",binary.toString(),source.toString()),owned,env,cancelled,Duration.ofSeconds(60));
            Result declaration=inspect(binary,owned,env,new Input("target",inputs,target,null,originalSignature,requestedSignature),cancelled);
            List<? extends Location> found=references.find(declaration.target());
            if(cancelled.getAsBoolean())throw new CancellationException();
            if(found==null)throw new IllegalStateException("The Go server did not return a complete reference result.");
            if(found.size()>10000)throw new IllegalStateException("Signature change exceeds the 10,000-reference limit.");
            var normalized=new ArrayList<Location>();
            for(var ref:found){
                if(ref==null || ref.getRange()==null)throw new IllegalStateException("The Go server returned an incomplete reference.");
                var original=sources.get(GoRefactorWorkspaceEdit.uriPath(ref.getUri()));
                if(original==null)throw new IllegalStateException("A signature reference is outside the captured project; no edits were applied.");
                normalized.add(new Location(original.uri(),ref.getRange()));
            }
            return inspect(binary,owned,env,new Input("verify",inputs,target,normalized,originalSignature,requestedSignature),cancelled).checked();
        }finally{
            try(var paths=Files.walk(owned)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
    static Map<String,String> checkerEnvironment(Map<String,String> inherited) {
        var env=new HashMap<>(inherited);
        // The process runner inherits the host environment too. Explicit empty
        // values neutralize every inherited Go override before setting our own.
        for(var keys:List.of(System.getenv().keySet(),inherited.keySet()))for(String key:keys)
            if(key.toUpperCase(Locale.ROOT).startsWith("GO"))HostTools.putEnvironment(env,Map.of(key,""));
        HostTools.putEnvironment(env,Map.of("GOENV","off","GOWORK","off","GOTOOLCHAIN","local",
                "GO111MODULE","off","GOPROXY","off","GOSUMDB","off","GOTELEMETRY","off","CGO_ENABLED","0"));
        return env;
    }
    private static Result inspect(Path binary,Path owned,Map<String,String> env,Input input,BooleanSupplier cancelled)throws Exception {
        Path request=owned.resolve("request.json");Files.writeString(request,new Gson().toJson(input),StandardCharsets.UTF_8);
        String output=run(List.of(binary.toString(),request.toString()),owned,env,cancelled,Duration.ofSeconds(15));
        if(output.length()>65536)throw new IOException("Signature checker output exceeded its limit.");
        Result result=new Gson().fromJson(output,Result.class);
        if(result==null || !result.passed() || result.target()==null)
            throw new IllegalStateException(result==null?"Signature safety checking failed.":result.reason());
        return result;
    }
    private static String run(List<String> command,Path owned,Map<String,String> env,BooleanSupplier cancelled,Duration timeout)throws Exception {
        var result=GoCoverageProcess.run(command,owned,env,cancelled,timeout);
        if(result.cancelled() || cancelled.getAsBoolean())throw new CancellationException("Signature check cancelled; no edits were applied.");
        if(result.timedOut() || result.outputTruncated() || !result.knownProcessesStopped() || !Objects.equals(result.exitCode(),0))
            throw new IOException("The local Go signature checker could not finish safely; no edits were applied.");
        return result.stdout();
    }
}

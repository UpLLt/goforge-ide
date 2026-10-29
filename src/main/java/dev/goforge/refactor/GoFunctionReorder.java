package dev.goforge.refactor;

import com.google.gson.Gson;
import dev.goforge.coverage.GoCoverageProcess;
import dev.goforge.tools.HostTools;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Reorders scalar arguments of static private functions, preserving supported argument-call order. */
public final class GoFunctionReorder {
    private GoFunctionReorder() { }
    private record Source(String uri,String text) { }
    private record Input(String mode,List<Source> sources,Location target,List<Location> references,String signature) { }
    private record Result(boolean passed,String reason,Location target,Map<String,List<TextEdit>> changes,Integer checked) { }

    public static GoChangeScalarType.Plan plan(String go,Map<String,String> environment,Path root,
            Collection<GoRefactorWorkspaceEdit.Source> snapshot,String uri,Position position,String signature,
            GoSignatureSafety.References references,BooleanSupplier cancelled)throws Exception {
        Objects.requireNonNull(references);
        if(signature==null||signature.length()>16384||!signature.strip().startsWith("func("))
            throw new IllegalArgumentException("Enter a complete function signature with the existing scalar parameters reordered.");
        var proof=new AtomicReference<GoFunctionReorderSafety.Request>();
        return GoChangeScalarType.compileCheckedPlan(go,environment,root,snapshot,uri,position,cancelled,(sources,owned,helperEnv)->{
            var input=sources.values().stream().map(s->new Source(s.uri(),s.text())).toList();
            var target=new Location(sources.get(GoRefactorWorkspaceEdit.uriPath(uri)).uri(),new Range(position,position));
            Path helper=owned.resolve("function-planner.go"),binary=owned.resolve(HostTools.executableName("function-planner"));
            Files.writeString(helper,GoFunctionReorderSource.TEXT,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            run(List.of(go,"build","-trimpath","-buildvcs=false","-o",binary.toString(),helper.toString()),owned,helperEnv,cancelled);
            Result declaration=inspect(binary,owned,helperEnv,new Input("target",input,target,null,signature),cancelled);
            var found=references.find(declaration.target());
            if(cancelled.getAsBoolean())throw new CancellationException("Function reorder cancelled; no edits were applied.");
            if(found==null||found.size()>10000)throw new IllegalStateException("A complete bounded reference response is required.");
            var normalized=new ArrayList<Location>();
            for(var ref:found){
                if(ref==null||ref.getRange()==null)throw new IllegalStateException("Incomplete function reference.");
                var caller=sources.get(GoRefactorWorkspaceEdit.uriPath(ref.getUri()));
                if(caller==null)throw new IllegalStateException("A function reference is outside the captured module.");
                normalized.add(new Location(caller.uri(),ref.getRange()));
            }
            Result proposed=inspect(binary,owned,helperEnv,new Input("plan",input,target,normalized,signature),cancelled);
            if(!Objects.equals(declaration.target(),proposed.target())||proposed.changes()==null||proposed.changes().isEmpty()
                    ||proposed.checked()==null||proposed.checked()!=normalized.size())
                throw new IllegalStateException("The reorder planner did not cover the same declaration and complete reference set.");
            var edits=new ArrayList<Either<TextDocumentEdit,ResourceOperation>>();
            for(var entry:proposed.changes().entrySet()){
                var original=sources.get(GoRefactorWorkspaceEdit.uriPath(entry.getKey()));
                if(original==null||entry.getValue()==null||entry.getValue().isEmpty())throw new IllegalStateException("Unexpected function reorder edit.");
                edits.add(Either.forLeft(GoAddParameter.documentEdit(new VersionedTextDocumentIdentifier(original.uri(),original.version()),entry.getValue())));
            }
            proof.set(new GoFunctionReorderSafety.Request(declaration.target(),List.copyOf(normalized)));
            return new WorkspaceEdit(edits);
        },(copy,owned,compileEnv)->GoFunctionReorderSafety.verify(go,root,snapshot,Objects.requireNonNull(proof.get()),copy,owned,compileEnv,cancelled));
    }
    private static Result inspect(Path binary,Path owned,Map<String,String> env,Input input,BooleanSupplier cancelled)throws Exception{
        Path file=owned.resolve("function-"+input.mode()+".json");Files.writeString(file,new Gson().toJson(input),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        Result result=new Gson().fromJson(run(List.of(binary.toString(),file.toString()),owned,env,cancelled),Result.class);
        if(result==null||!result.passed()||result.target()==null||result.target().getRange()==null)
            throw new IllegalStateException(result==null||result.reason()==null?"The bounded function reorder planner did not finish.":result.reason());
        return result;
    }
    private static String run(List<String> command,Path directory,Map<String,String> environment,BooleanSupplier cancelled)throws Exception{
        var result=GoCoverageProcess.run(command,directory,environment,cancelled,Duration.ofSeconds(60));
        if(cancelled.getAsBoolean()||result.cancelled())throw new CancellationException("Function reorder cancelled; no edits were applied.");
        if(result.timedOut()||result.outputTruncated()||!result.knownProcessesStopped()||!Objects.equals(result.exitCode(),0)||result.stdout().length()>8*1024*1024)
            throw new IOException("The bounded function reorder helper could not finish; no edits were applied.");
        return result.stdout();
    }
}

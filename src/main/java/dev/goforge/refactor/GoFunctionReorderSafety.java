package dev.goforge.refactor;

import com.google.gson.Gson;
import dev.goforge.coverage.GoCoverageProcess;
import dev.goforge.tools.HostTools;
import org.eclipse.lsp4j.Location;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Worker-only type proof on the already compiled private snapshot; never executes project code. */
final class GoFunctionReorderSafety {
    private GoFunctionReorderSafety() { }
    record Request(Location target,List<Location> references) { }
    private record Source(String uri,String path,String text) { }
    private record Input(List<Source> sources,Request request,String packages,String arch) { }
    private record Result(boolean passed,String reason,Integer checked) { }

    static void verify(String go,Path root,Collection<GoRefactorWorkspaceEdit.Source> snapshot,Request request,
            Path copy,Path owned,Map<String,String> environment,BooleanSupplier cancelled)throws Exception {
        root=root.toAbsolutePath().normalize();
        var sources=new ArrayList<Source>();
        for(var source:snapshot){
            Path original=GoRefactorWorkspaceEdit.uriPath(source.uri());
            if(!original.startsWith(root)||original.equals(root))throw new IllegalArgumentException("Function reorder source is outside the captured module.");
            sources.add(new Source(source.uri(),copy.resolve(root.relativize(original)).toString(),source.text()));
        }
        String packages=run(List.of(go,"list","-deps","-export","-test","-json","-mod=readonly","./..."),copy,environment,cancelled);
        Path packageFile=owned.resolve("function-reorder-packages.json");Files.writeString(packageFile,packages,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        String arch=run(List.of(go,"env","GOARCH"),copy,environment,cancelled).strip();
        if(!arch.matches("[a-z0-9]{2,16}"))throw new IOException("Function reorder proof could not identify the compiler architecture.");
        Path helper=owned.resolve("function-reorder-proof.go"),binary=owned.resolve(HostTools.executableName("function-reorder-proof"));
        Files.writeString(helper,GoFunctionReorderSafetySource.TEXT,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        var helperEnv=GoSignatureSafety.checkerEnvironment(environment);
        run(List.of(go,"build","-trimpath","-buildvcs=false","-o",binary.toString(),helper.toString()),owned,helperEnv,cancelled);
        Path input=owned.resolve("function-reorder-proof.json");
        Files.writeString(input,new Gson().toJson(new Input(sources,request,packageFile.toString(),arch)),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        requireVerified(run(List.of(binary.toString(),input.toString()),owned,helperEnv,cancelled),request.references().size());
    }
    static void requireVerified(String output,int expectedReferences) {
        Result result=new Gson().fromJson(output,Result.class);
        if(expectedReferences<0||expectedReferences>10000||result==null||!result.passed()||result.checked()==null||result.checked()!=expectedReferences)
            throw new IllegalStateException(result==null?"The private function type proof did not finish.":result.reason()==null?"The typed function proof did not cover the complete reference set.":result.reason());
    }
    private static String run(List<String> command,Path directory,Map<String,String> environment,BooleanSupplier cancelled)throws Exception {
        var result=GoCoverageProcess.run(command,directory,environment,cancelled,Duration.ofSeconds(60));
        if(cancelled.getAsBoolean()||result.cancelled())throw new CancellationException("Private function reorder proof cancelled; no edits were applied.");
        if(result.timedOut()||result.outputTruncated()||!result.knownProcessesStopped()||!Objects.equals(result.exitCode(),0)||result.stdout().length()>8*1024*1024)
            throw new IOException("The bounded offline private function type proof could not finish; no edits were applied.");
        return result.stdout();
    }
}

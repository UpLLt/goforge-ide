package dev.goforge.remote;

import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public final class GoRemoteDebugPlanTest {
    private GoRemoteProfile profile(){return new GoRemoteProfile("fixture.invalid","",2222,"","/workspace space 'quote'","go","");}
    private GoRemoteDebugPlan plan(String mode,String target,int pid,List<String> args,Map<String,String> env){return new GoRemoteDebugPlan(profile(),"/local workspace","dlv",mode,target,pid,Map.of("go.mod","a".repeat(64),"main.go","b".repeat(64)),args,env);}
    @Test public void packageUsesRemotePathsAndSubstitutePathDirection(){var p=plan("debug","cmd/app",0,List.of(),Map.of());assertEquals("/workspace space 'quote'/cmd/app",p.dapArguments().get("program"));assertEquals(List.of(Map.of("from","/local workspace","to","/workspace space 'quote'")),p.dapArguments().get("substitutePath"));}
    @Test public void attachIsRemoteServersLocalPidAndDoesNotLaunchProgram(){var p=plan("attach",".",42,List.of(),Map.of());assertTrue(p.attach());assertEquals("attach",p.dapArguments().get("request"));assertEquals("local",p.dapArguments().get("mode"));assertEquals(42,p.dapArguments().get("processId"));assertFalse(p.dapArguments().containsKey("program"));}
    @Test public void executableAndArgumentsRemainLiteralData(){var p=plan("exec","out/app",0,List.of("space value",";$(not-a-shell)"),Map.of("USER_VALUE","fixture-private-value"));assertEquals("exec",p.dapArguments().get("mode"));assertEquals(List.of("space value",";$(not-a-shell)"),p.dapArguments().get("args"));assertTrue(p.startupJson().contains("fixture-private-value"));assertFalse(String.join(" ",p.command("ssh")).contains("fixture-private-value"));assertFalse(String.join(" ",p.command("ssh")).contains(";$(not-a-shell)"));}
    @Test public void sshKeepsHostVerificationAndNoForwarding(){var command=plan("debug",".",0,List.of(),Map.of()).command("ssh");assertTrue(command.contains("StrictHostKeyChecking=yes"));assertTrue(command.contains("ClearAllForwardings=yes"));assertTrue(command.contains("BatchMode=yes"));assertFalse(command.contains("-L"));}
    @Test public void traversalAndOptionInjectionRejected(){for(String target:List.of("../outside","/absolute","cmd/../outside","cmd//app"))assertThrows(IllegalArgumentException.class,()->plan("exec",target,0,List.of(),Map.of()));assertThrows(IllegalArgumentException.class,()->new GoRemoteDebugPlan(profile(),"/local","dlv --log","debug",".",0,Map.of("go.mod","a".repeat(64)),List.of(),Map.of()));}
    @Test public void existingPidAndModeAreValidated(){assertThrows(IllegalArgumentException.class,()->plan("attach",".",1,List.of(),Map.of()));assertThrows(IllegalArgumentException.class,()->plan("debug",".",42,List.of(),Map.of()));assertThrows(IllegalArgumentException.class,()->plan("remote",".",0,List.of(),Map.of()));}
    @Test public void attachCannotChangeExistingEnvironmentOrArguments(){assertThrows(IllegalArgumentException.class,()->plan("attach",".",42,List.of("x"),Map.of()));assertThrows(IllegalArgumentException.class,()->plan("attach",".",42,List.of(),Map.of("X","1")));}
    @Test public void environmentCannotRedirectToolchainOrLoader(){for(String key:List.of("GOOS","GOFLAGS","GOROOT","PATH","LD_PRELOAD"))assertThrows(IllegalArgumentException.class,()->plan("debug",".",0,List.of(),Map.of(key,"value")));assertThrows(IllegalArgumentException.class,()->plan("debug",".",0,List.of(),Map.of("BAD KEY","value")));}
    @Test public void environmentLinesPreserveEqualsAndDoNotParseShell(){assertEquals(Map.of("VALUE","a=b","LITERAL","$(no-shell)"),GoRemoteDebugOptions.environmentLines("VALUE=a=b\n\nLITERAL=$(no-shell)"));assertThrows(IllegalArgumentException.class,()->GoRemoteDebugOptions.environmentLines("no-equals"));}
    @Test public void modifiedSavedSourcesInvalidateReview()throws Exception {
        Path root=Files.createTempDirectory("goforge-debug-plan-");try{Files.writeString(root.resolve("go.mod"),"module fixture\ngo 1.23\n");Files.writeString(root.resolve("main.go"),"package main\nfunc main(){}\n");var p=GoRemoteDebugPlan.create(profile(),root,"dlv","debug",".",0,GoRemoteDebugSources.snapshot(root,()->false),List.of(),Map.of());p.verifyLocal();Files.writeString(root.resolve("main.go"),"package main\nfunc main(){println(1)}\n");assertThrows(java.io.IOException.class,p::verifyLocal);}finally{try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
    }
    @Test public void executableOutputsAreExcludedButSourceAndDataAreHashed()throws Exception {
        Path root=Files.createTempDirectory("goforge-debug-source-");try{Files.writeString(root.resolve("go.mod"),"module fixture\n");Files.writeString(root.resolve("main.go"),"package main\n");Files.writeString(root.resolve("data.txt"),"embedded data");Files.write(root.resolve("app"),new byte[]{0x7f,'E','L','F',0});Files.createSymbolicLink(root.resolve("linked.go"),root.resolve("main.go"));var snapshot=GoRemoteDebugSources.snapshot(root,()->false);assertEquals(Set.of("go.mod","main.go","data.txt"),snapshot.files().stream().map(GoRemoteFiles.FileData::path).collect(java.util.stream.Collectors.toSet()));assertTrue(snapshot.skipped().stream().anyMatch(s->s.contains("compiled executable")));assertTrue(snapshot.skipped().contains("linked.go"));}finally{try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
    }
    @Test public void sourceReviewRespondsToCancellation()throws Exception {Path root=Files.createTempDirectory("goforge-debug-cancel-");try{assertThrows(java.io.IOException.class,()->GoRemoteDebugSources.snapshot(root,()->true));}finally{Files.delete(root);}}
    @Test public void manifestLimitCountsUtf8BytesRatherThanJavaCharacters(){
        var files=new LinkedHashMap<String,String>();files.put("go.mod","a".repeat(64));
        for(int i=0;i<210;i++)files.put("界".repeat(80)+"/"+i+".go","b".repeat(64));
        var plan=new GoRemoteDebugPlan(profile(),"/local","dlv","debug",".",0,files,List.of(),Map.of());
        assertThrows(IllegalArgumentException.class,plan::startupJson);
    }

}

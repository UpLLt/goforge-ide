package dev.goforge.refactor;

import com.google.gson.GsonBuilder;
import dev.goforge.lsp.GoplsClientFeatures;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Real gopls cross-file resolved edits validated by the production planner, then independently Go-tested. */
public final class GoMultiFileRefactorProtocolProbe {
    private record Case(String kind,String selection,boolean unused,boolean overlay){}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(System.getProperty("user.dir")),tools=root.resolve(".tools/bin");
        Path reportPath=args.length==0?root.resolve(".artifacts/multifile-refactoring-protocol.json"):Path.of(args[0]);
        var report=new LinkedHashMap<String,Object>();var checks=new ArrayList<Object>();
        report.put("startedAt",Instant.now().toString());report.put("scope","Real pinned gopls; production multi-file validator; no IDE UI acceptance");report.put("passed",false);report.put("checks",checks);
        Path directory=Files.createTempDirectory("goforge-multifile-refactor-");
        try{
            for(var item:List.of(new Case("refactor.rewrite.moveParamRight","prefix",false,false),new Case("refactor.rewrite.moveParamLeft","count",false,false),new Case("refactor.rewrite.removeUnusedParam","unused",true,false),new Case("refactor.rewrite.moveParamRight","prefix",false,true))){
                var disk=new LinkedHashMap<String,String>();
                disk.put("format.go","package advanced\nimport \"strconv\"\nfunc Format(prefix string, "+(item.unused()?"unused bool, ":"")+"count int) string { return prefix + strconv.Itoa(count) }\n");
                disk.put("caller.go","package advanced\nfunc Call() string { return Format(\"n=\", "+(item.unused()?"true, ":"")+"7) }\n");
                disk.put("format_test.go","package advanced\nimport \"testing\"\nfunc TestFormat(t *testing.T) { if got:=Format(\"v=\", "+(item.unused()?"true, ":"")+"3);got!=\"v=3\"{t.Fatal(got)};if got:=Call();got!=\"n=7\"{t.Fatal(got)} }\n");
                Files.writeString(directory.resolve("go.mod"),"module example.test/multifile\n\ngo 1.26\n");
                for(var file:disk.entrySet())Files.writeString(directory.resolve(file.getKey()),file.getValue());
                var inputs=new LinkedHashMap<>(disk);if(item.overlay())inputs.put("caller.go","// unsaved 世界🙂\n"+inputs.get("caller.go"));
                var plan=resolve(tools,directory,item,inputs);
                if(plan.files().size()!=3)throw new AssertionError("Expected three changed files, got "+plan.files().size());
                for(var file:disk.entrySet())if(!Files.readString(directory.resolve(file.getKey())).equals(file.getValue()))throw new AssertionError("Preview changed disk");
                if(item.overlay()&&plan.files().stream().filter(x->x.uri().endsWith("caller.go")).noneMatch(x->x.edit().replacement().startsWith("// unsaved 世界🙂")))throw new AssertionError("Unsaved overlay was lost");
                for(var file:plan.files())Files.writeString(Path.of(java.net.URI.create(file.uri())),file.edit().replacement());
                var command=new ProcessBuilder(tools.resolve("go").toString(),"test","-count=1","./...").directory(directory.toFile()).redirectErrorStream(true);environment(command,tools);
                var process=command.start();try{
                    if(!process.waitFor(40,TimeUnit.SECONDS))throw new AssertionError("go test timeout");
                    String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                    if(process.exitValue()!=0)throw new AssertionError(output);
                    checks.add(Map.of("kind",item.kind(),"unsavedOverlay",item.overlay(),"changedFiles",plan.files().size(),"previewDidNotWrite",true,"productionValidator",true,"goTestExit",0));
                }finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
            }
            report.put("passed",true);
        }catch(Throwable failure){report.put("failure",failure.toString());throw failure;}
        finally{try(var paths=Files.walk(directory)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}report.put("fixtureDeleted",true);report.put("completedAt",Instant.now().toString());Files.createDirectories(reportPath.getParent());Files.writeString(reportPath,new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println(reportPath);}
    }
    private static GoRefactorWorkspaceEdit resolve(Path tools,Path directory,Case item,Map<String,String> inputs)throws Exception{
        var command=new ProcessBuilder(tools.resolve("gopls").toString(),"serve").directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);environment(command,tools);
        var process=command.start();var executor=Executors.newCachedThreadPool();Future<?> listening=null;
        try{
            var client=new LanguageClient(){
                public void telemetryEvent(Object o){}public void publishDiagnostics(PublishDiagnosticsParams p){}public void showMessage(MessageParams p){}public void logMessage(MessageParams p){}
                public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams p){return CompletableFuture.completedFuture(null);}
                public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams p){return CompletableFuture.failedFuture(new AssertionError("Preview must not request applyEdit"));}
            };
            var features=new GoplsClientFeatures();var params=new InitializeParams();params.setRootUri(directory.toUri().toString());params.setCapabilities(new ClientCapabilities());
            var workspace=new WorkspaceClientCapabilities();workspace.setApplyEdit(true);var capability=new WorkspaceEditCapabilities();capability.setDocumentChanges(true);workspace.setWorkspaceEdit(capability);params.getCapabilities().setWorkspace(workspace);features.initializeParams(params);
            var launcher=features.<LanguageServer>createLauncherBuilder().setInput(process.getInputStream()).setOutput(process.getOutputStream()).setRemoteInterface(LanguageServer.class).setLocalService(client).setExecutorService(executor).create();listening=launcher.startListening();var server=launcher.getRemoteProxy();
            server.initialize(params).get(30,TimeUnit.SECONDS);server.initialized(new InitializedParams());
            String uri=directory.resolve("format.go").toUri().toString(),source=inputs.get("format.go");
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri,"go",7,source)));
            if(item.overlay())server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(directory.resolve("caller.go").toUri().toString(),"go",11,inputs.get("caller.go"))));
            int start=source.indexOf(item.selection());var context=new CodeActionContext(List.of());context.setOnly(List.of(item.kind()));context.setTriggerKind(CodeActionTriggerKind.Invoked);
            var actions=server.getTextDocumentService().codeAction(new CodeActionParams(new TextDocumentIdentifier(uri),new Range(GoRefactorEdit.position(source,start),GoRefactorEdit.position(source,start+item.selection().length())),context)).get(30,TimeUnit.SECONDS);
            var action=actions.stream().filter(x->x.isRight()&&item.kind().equals(x.getRight().getKind())).map(x->x.getRight()).findFirst().orElseThrow(()->new AssertionError("No action "+item.kind()));
            if(action.getCommand()!=null)throw new AssertionError("Unexpected executeCommand action");
            var resolved=action.getEdit()==null?server.getTextDocumentService().resolveCodeAction(action).get(30,TimeUnit.SECONDS):action;
            if(resolved.getCommand()!=null)throw new AssertionError("Unexpected command after resolve");
            var sources=new ArrayList<GoRefactorWorkspaceEdit.Source>();
            for(var file:inputs.entrySet()){String fileUri=directory.resolve(file.getKey()).toUri().toString();Integer version=features.getSentDocumentVersion(fileUri);sources.add(new GoRefactorWorkspaceEdit.Source(fileUri,file.getValue(),version==null?0:version));}
            var plan=GoRefactorWorkspaceEdit.prepare(sources,resolved.getEdit());
            server.shutdown().get(5,TimeUnit.SECONDS);server.exit();if(!process.waitFor(5,TimeUnit.SECONDS)||process.exitValue()!=0)throw new AssertionError("gopls shutdown failed");return plan;
        }finally{if(process.isAlive()){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly().waitFor(2,TimeUnit.SECONDS);}if(listening!=null)listening.cancel(true);executor.shutdownNow();}
    }
    private static void environment(ProcessBuilder command,Path tools){command.environment().put("PATH",tools+java.io.File.pathSeparator+System.getenv().getOrDefault("PATH",""));command.environment().put("GOWORK","off");}
}

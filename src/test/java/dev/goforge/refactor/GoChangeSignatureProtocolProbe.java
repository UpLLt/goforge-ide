package dev.goforge.refactor;

import com.google.gson.GsonBuilder;
import dev.goforge.lsp.GoplsClientFeatures;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Generated packages and actual pinned gopls; runs the production AST guard and edit planner. */
public final class GoChangeSignatureProtocolProbe {
    private record Case(String name,String kind,String expression,boolean method,boolean overlay,boolean reject) { }
    public static void main(String[] arguments)throws Exception {
        Path root=Path.of(System.getProperty("user.dir")),tools=root.resolve(".tools/bin"),out=Path.of(arguments[0]);Files.createDirectories(out);
        var checks=new ArrayList<Object>();var report=new LinkedHashMap<String,Object>();report.put("passed",false);
        report.put("scope","Real gopls, production AST guard and all-file edit planner; generated fixtures only; no native IDE claim");report.put("checks",checks);
        try {
            for(var item:List.of(
                    new Case("cross-package-reorder","goforge.changeSignature","plain",false,false,false),
                    new Case("remove-two-unused","remove","plain",false,false,false),
                    new Case("dirty-cross-package-reorder","goforge.changeSignature","plain",false,true,false),
                    new Case("old-right-side-effects","refactor.rewrite.moveParamRight","effects",false,true,true),
                    new Case("old-left-side-effects","refactor.rewrite.moveParamLeft","effects",false,false,true),
                    new Case("new-signature-side-effects","goforge.changeSignature","effects",false,true,true),
                    new Case("index-evaluation","goforge.changeSignature","index",false,false,true),
                    new Case("channel-receive","goforge.changeSignature","receive",false,false,true),
                    new Case("dereference","goforge.changeSignature","pointer",false,false,true),
                    new Case("method-effects","refactor.rewrite.moveParamRight","effects",true,false,true),
                    new Case("safe-method","refactor.rewrite.moveParamRight","plain",true,false,false),
                    new Case("generic-upstream-no-preview","refactor.rewrite.moveParamRight","generic",false,false,true),
                    new Case("missing-references","goforge.changeSignature","plain",false,false,true),
                    new Case("missing-caller-and-edit","goforge.changeSignature","plain",false,false,true),
                    new Case("external-reference","goforge.changeSignature","plain",false,false,true),
                    new Case("null-reference-response","goforge.changeSignature","plain",false,false,true),
                    new Case("cancelled-reference","goforge.changeSignature","plain",false,false,true),
                    new Case("schema-add-parameter","goforge.changeSignature","schema-add",false,false,true),
                    new Case("schema-change-type","goforge.changeSignature","schema-type",false,false,true),
                    new Case("schema-change-results","goforge.changeSignature","schema-results",false,false,true),
                    new Case("schema-duplicate-parameter","goforge.changeSignature","schema-duplicate",false,false,true)))checks.add(run(tools,out,item));
            report.put("passed",true);
        }finally{Files.writeString(out.resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");}
        System.out.println(checks.size()+" real signature checks passed; UI remains separate.");
    }
    private static Map<String,Object> run(Path tools,Path out,Case item)throws Exception {
        Path directory=Files.createTempDirectory("goforge-signature-protocol-");var disk=fixture(item);var env=new HashMap<>(System.getenv());
        env.put("PATH",tools+java.io.File.pathSeparator+env.getOrDefault("PATH",""));env.put("GOWORK","off");env.put("GOTOOLCHAIN","local");
        var record=new LinkedHashMap<String,Object>();record.put("case",item.name());record.put("passed",false);
        Process process=null;var executor=Executors.newCachedThreadPool();Future<?> listening=null;
        try{
            Files.writeString(directory.resolve("go.mod"),"module example.test/signature\n\ngo 1.26\n");
            for(var entry:disk.entrySet()){Path file=directory.resolve(entry.getKey());Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());}
            var input=new LinkedHashMap<>(disk);if(item.overlay())input.put("app/call.go","// unsaved 世界🙂\n"+input.get("app/call.go"));
            var command=new ProcessBuilder(tools.resolve("gopls").toString(),"serve").directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);command.environment().putAll(env);process=command.start();
            var applied=new java.util.concurrent.atomic.AtomicInteger();
            var client=new LanguageClient(){
                public void telemetryEvent(Object p){}public void publishDiagnostics(PublishDiagnosticsParams p){}public void showMessage(MessageParams p){}public void logMessage(MessageParams p){}
                public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams p){return CompletableFuture.completedFuture(null);}
                public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams p){applied.incrementAndGet();return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false));}
            };
            var features=new GoplsClientFeatures();var initialize=new InitializeParams();initialize.setRootUri(directory.toUri().toString());initialize.setCapabilities(new ClientCapabilities());
            var workspace=new WorkspaceClientCapabilities();var workspaceEdits=new WorkspaceEditCapabilities();workspaceEdits.setDocumentChanges(true);workspace.setWorkspaceEdit(workspaceEdits);initialize.getCapabilities().setWorkspace(workspace);features.initializeParams(initialize);
            var launcher=features.<LanguageServer>createLauncherBuilder().setLocalService(client).setRemoteInterface(LanguageServer.class).setInput(process.getInputStream()).setOutput(process.getOutputStream()).setExecutorService(executor).create();listening=launcher.startListening();var server=launcher.getRemoteProxy();server.initialize(initialize).get(30,TimeUnit.SECONDS);server.initialized(new InitializedParams());
            String uri=directory.resolve("api/format.go").toUri().toString(),text=input.get("api/format.go");
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri,"go",7,text)));
            if(item.overlay())server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(directory.resolve("app/call.go").toUri().toString(),"go",11,input.get("app/call.go"))));
            WorkspaceEdit workspaceEdit;Position selection;String originalSignature=null,requestedSignature=null;
            if(item.kind().startsWith("refactor.")){
                String param=item.kind().endsWith("Left")?"count int":"prefix ";int offset=text.indexOf(param);selection=GoRefactorEdit.position(text,offset);
                var context=new CodeActionContext(List.of());context.setOnly(List.of(item.kind()));context.setTriggerKind(CodeActionTriggerKind.Invoked);
                var actions=server.getTextDocumentService().codeAction(new CodeActionParams(new TextDocumentIdentifier(uri),new Range(selection,selection),context)).get(30,TimeUnit.SECONDS);
                var action=actions.stream().filter(e->e.isRight()&&item.kind().equals(e.getRight().getKind())).findFirst().orElseThrow().getRight();
                var resolved=action.getEdit()==null?server.getTextDocumentService().resolveCodeAction(action).get(30,TimeUnit.SECONDS):action;
                if(resolved.getCommand()!=null){
                    record.put("resolved",resolved);
                    if(!item.name().equals("generic-upstream-no-preview"))throw new AssertionError("Unexpected command");
                    for(var file:disk.entrySet())if(!Files.readString(directory.resolve(file.getKey())).equals(file.getValue()))throw new AssertionError("Preview wrote a source");
                    record.put("upstreamCommandOnlyRejected",true);record.put("previewDiskUnchanged",true);record.put("serverApplyEditRequests",applied.get());if(applied.get()!=0)throw new AssertionError("Server tried to apply edits");
                    server.shutdown().get(5,TimeUnit.SECONDS);server.exit();if(!process.waitFor(5,TimeUnit.SECONDS)||process.exitValue()!=0)throw new AssertionError("gopls did not exit");record.put("passed",true);return record;
                }workspaceEdit=resolved.getEdit();
            }else{
                selection=GoRefactorEdit.position(text,text.indexOf("func ")+1);
                if(item.name().equals("cross-package-reorder")){
                    var blocked=server.getTextDocumentService().prepareRename(new PrepareRenameParams(new TextDocumentIdentifier(uri),selection)).get(30,TimeUnit.SECONDS);
                    if(blocked!=null)throw new AssertionError("Default prepareRename exposed the signature template");
                    var symbol=server.getTextDocumentService().prepareRename(new PrepareRenameParams(new TextDocumentIdentifier(uri),GoRefactorEdit.position(text,text.indexOf("Format")+1))).get(30,TimeUnit.SECONDS);
                    if(symbol==null || !symbol.isSecond() || !"Format".equals(symbol.getSecond().getPlaceholder()))throw new AssertionError("Ordinary symbol prepareRename was changed");
                    record.put("defaultFuncPrepareBlocked",true);record.put("ordinarySymbolPreparePreserved",true);
                }
                var prepared=server.getTextDocumentService().prepareRename(new GoChangeSignature.PrepareRequest(new TextDocumentIdentifier(uri),selection)).get(30,TimeUnit.SECONDS);
                var signature=GoChangeSignature.prepared(uri,text,selection,prepared.getSecond());if(signature==null)throw new AssertionError("Missing prepared signature");
                String value=item.kind().equals("remove")?"func(prefix string, count int, suffix string) string":"func(suffix string, prefix string, count int, unused bool, unused2 byte) string";
                originalSignature=signature.placeholder();requestedSignature=value;
                workspaceEdit=server.getTextDocumentService().rename(signature.request(value)).get(30,TimeUnit.SECONDS);
                // Validate the independent AST schema gate using a legitimate
                // plan, even if a future gopls accepted a wider rename contract.
                switch(item.expression()){
                    case "schema-add" -> requestedSignature="func(prefix string, count int, suffix string, added int) string";
                    case "schema-type" -> requestedSignature="func(prefix int, count int, suffix string) string";
                    case "schema-results" -> requestedSignature="func(prefix string, count int, suffix string) bool";
                    case "schema-duplicate" -> requestedSignature="func(prefix string, prefix string) string";
                }
            }
            var sources=new ArrayList<GoRefactorWorkspaceEdit.Source>();for(var file:input.entrySet()){String u=directory.resolve(file.getKey()).toUri().toString();Integer version=features.getSentDocumentVersion(u);sources.add(new GoRefactorWorkspaceEdit.Source(u,file.getValue(),version==null?0:version));}
            var plan=GoRefactorWorkspaceEdit.prepare(sources,workspaceEdit);record.put("changedFiles",plan.files().size());record.put("wireEdit",workspaceEdit);
            if(item.name().equals("missing-caller-and-edit"))plan=new GoRefactorWorkspaceEdit(plan.files().stream().filter(f->!f.uri().endsWith("app/call.go")).toList());
            var cancelled=new java.util.concurrent.atomic.AtomicBoolean();long checkStarted=System.nanoTime();
            try{
                int callers=GoSignatureSafety.verify(tools.resolve("go").toString(),env,sources,uri,selection,plan,originalSignature,requestedSignature,
                        target->{
                            var refs=server.getTextDocumentService().references(new ReferenceParams(new TextDocumentIdentifier(target.getUri()),target.getRange().getStart(),new ReferenceContext(false))).get(30,TimeUnit.SECONDS);
                            if(item.name().equals("null-reference-response"))return null;
                            if(item.name().equals("cancelled-reference"))cancelled.set(true);
                            if(item.name().equals("missing-references"))return List.of();
                            if(item.name().equals("missing-caller-and-edit"))return refs.stream().filter(r->!r.getUri().endsWith("app/call.go")).toList();
                            if(item.name().equals("external-reference")){var copy=new ArrayList<Location>(refs);copy.add(new Location(directory.resolve("uncaptured.go").toUri().toString(),new Range(new Position(),new Position())));return copy;}
                            return refs;
                        },cancelled::get);
                if(item.reject())throw new AssertionError("Unsafe signature transform was accepted");record.put("checkedCallers",callers);
            }catch(CancellationException expected){if(!item.name().equals("cancelled-reference"))throw expected;record.put("cancelledBeforeApply",true);
            }catch(IllegalStateException expected){if(!item.reject())throw expected;record.put("rejectedBeforeApply",expected.getMessage());}
            record.put("checkerMilliseconds",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-checkStarted));
            for(var file:disk.entrySet())if(!Files.readString(directory.resolve(file.getKey())).equals(file.getValue()))throw new AssertionError("Preview wrote a source");
            if(item.overlay()&&!sources.stream().filter(s->s.uri().endsWith("app/call.go")).findFirst().orElseThrow().text().startsWith("// unsaved 世界🙂"))throw new AssertionError("Dirty source lost");
            record.put("previewDiskUnchanged",true);record.put("dirtySnapshotRetained",item.overlay());
            if(!item.reject()){
                if(plan.files().size()<3)throw new AssertionError("Expected declaration and callers in two packages plus external test package");
                for(var file:plan.files())Files.writeString(Path.of(java.net.URI.create(file.uri())),file.edit().replacement());
                var test=new ProcessBuilder(tools.resolve("go").toString(),"test","-count=1","./...").directory(directory.toFile()).redirectErrorStream(true);test.environment().putAll(env);var child=test.start();
                try{if(!child.waitFor(45,TimeUnit.SECONDS))throw new AssertionError("Go test timeout");String output=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8);if(child.exitValue()!=0)throw new AssertionError(output);record.put("goTestExit",0);record.put("goTestOutput",output);}finally{if(child.isAlive())child.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
            }
            if(applied.get()!=0)throw new AssertionError("Server attempted application before preview");server.shutdown().get(5,TimeUnit.SECONDS);server.exit();if(!process.waitFor(5,TimeUnit.SECONDS)||process.exitValue()!=0)throw new AssertionError("gopls did not exit");record.put("serverApplyEditRequests",0);record.put("passed",true);return record;
        }finally{
            if(process!=null&&process.isAlive()){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}if(listening!=null)listening.cancel(true);executor.shutdownNow();
            try(var paths=Files.walk(directory)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}record.put("fixtureDeleted",true);
            Files.writeString(out.resolve(item.name()+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(record)+"\n");
        }
    }
    private static LinkedHashMap<String,String> fixture(Case item){
        var files=new LinkedHashMap<String,String>();String declaration=item.method()?"type Formatter struct{}\nfunc (f Formatter) Format":"func Format";
        boolean generic=item.expression().equals("generic");
        files.put("api/format.go","package api\nimport \"strconv\"\n"+declaration+(generic?"[T ~string]":"")+"(prefix "+(generic?"T":"string")+", count int, suffix "+(generic?"T":"string")+", unused bool, unused2 byte) string {return "+(generic?"string(prefix)":"prefix")+"+strconv.Itoa(count)+"+(generic?"string(suffix)":"suffix")+"}\n");
        String before="",args="\"a\",7,\"z\",false,1",extra="",finish="";
        switch(item.expression()){
            case "effects" -> {extra="var sequence string\nfunc mark(s string) string {sequence+=s;return s}\nfunc number() int {sequence+=\"n\";return 7}\n";before="sequence=\"\";";args="mark(\"a\"),number(),mark(\"z\"),false,1";finish="+sequence";}
            case "index" -> args="[]string{\"a\"}[0],7,\"z\",false,1";
            case "receive" -> {before="ch:=make(chan string,1);ch<-\"a\";";args="<-ch,7,\"z\",false,1";}
            case "pointer" -> {before="p:=new(string);*p=\"a\";";args="*p,7,\"z\",false,1";}
        }
        String call=item.method()?"f.Format":"api.Format"+(generic?"[string]":"");if(item.method())before="var f api.Formatter;"+before;
        files.put("app/call.go","package app\nimport \"example.test/signature/api\"\n"+extra+"func Call() string {"+before+"return "+call+"("+args+")"+finish+"}\n");
        files.put("app/call_test.go","package app\nimport \"testing\"\nfunc TestCall(t *testing.T){if got:=Call();got!=\"a7z"+(item.expression().equals("effects")?"anz":"")+"\"{t.Fatal(got)}}\n");
        files.put("api/format_test.go","package api_test\nimport (\"testing\";\"example.test/signature/api\")\nfunc TestFormat(t *testing.T){"+(item.method()?"var f api.Formatter;":"")+"if got:="+(item.method()?"f.Format":"api.Format"+(generic?"[string]":""))+"(\"b\",3,\"q\",false,1);got!=\"b3q\"{t.Fatal(got)}}\n");return files;
    }
}

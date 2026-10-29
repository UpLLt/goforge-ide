package dev.goforge.refactor;

import com.google.gson.GsonBuilder;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in real gopls + production planner on owned generated projects; no native acceptance claim. */
public final class GoAddParameterProtocolProbe {
    public static void main(String[] arguments) throws Exception {
        Path tools=Path.of(System.getProperty("user.dir"),".tools","bin");
        Path out=Path.of(arguments[0]);Files.createDirectories(out);
        if(Files.exists(out.resolve("result.json")))throw new IllegalStateException("Use a new evidence directory.");
        var checks=new ArrayList<Object>();var report=new LinkedHashMap<String,Object>();report.put("passed",false);report.put("checks",checks);
        report.put("scope","Actual pinned gopls references, production GoAddParameter planner and real go test on generated fixtures; no native or full-signature-parity claim");
        Throwable failure=null;
        try {
            for(String name:List.of("cross-package-middle-effects","dirty-unicode-callers","boolean-shadowing","function-value-refused","missing-reference-refused","outside-reference-refused","cancelled-after-references"))
                checks.add(run(tools,out,name));
            report.put("passed",true);
        }catch(Exception|Error problem){failure=problem;report.put("failure",describe(problem));throw problem;
        }finally{writeEvidence(out.resolve("result.json"),report,failure);}
        System.out.println(checks.size()+" real add-parameter protocol cases passed");
    }
    private static Map<String,Object> run(Path tools,Path out,String name)throws Exception {
        Path dir=Files.createTempDirectory("goforge-add-parameter-probe-");
        var disk=new LinkedHashMap<String,String>();
        disk.put("api/format.go","package api\nfunc Format(first string, second string) string { return first+second }\n");
        String pre=name.equals("boolean-shadowing")?"true := 31; _ = true;":"";
        disk.put("app/call.go","package app\nimport \"example.test/add/api\"\nvar Sequence string\nfunc mark(s string) string {Sequence+=s;return s}\nfunc Call() string {"+pre+"Sequence=\"\";return api.Format(mark(\"A\"),mark(\"B\"))}\n"+(name.equals("function-value-refused")?"var Saved = api.Format\n":""));
        disk.put("app/call_test.go","package app\nimport \"testing\"\nfunc TestCall(t *testing.T){if got:=Call();got!=\"AB\"||Sequence!=\"AB\"{t.Fatalf(\"%s/%s\",got,Sequence)}}\n");
        disk.put("api/format_test.go","package api_test\nimport (\"testing\";\"example.test/add/api\")\nfunc TestExternal(t *testing.T){if got:=api.Format(\"C\",\"D\");got!=\"CD\"{t.Fatal(got)}}\n");
        var snapshot=new LinkedHashMap<>(disk);
        if(name.equals("dirty-unicode-callers"))snapshot.put("app/call.go","// unsaved \u4e16\u754c\ud83d\ude42\n"+snapshot.get("app/call.go"));
        var env=new HashMap<>(System.getenv());env.put("PATH",tools+java.io.File.pathSeparator+env.getOrDefault("PATH",""));env.put("GOWORK","off");env.put("GOTOOLCHAIN","local");env.put("GOFLAGS","");env.put("GOENV","off");
        var record=new LinkedHashMap<String,Object>();record.put("case",name);record.put("passed",false);
        var executor=Executors.newCachedThreadPool();Process process=null;Future<?> listening=null;int[] applyRequests={0};Throwable failure=null;
        try {
            Files.writeString(dir.resolve("go.mod"),"module example.test/add\n\ngo 1.26\n");
            for(var entry:disk.entrySet()){Path file=dir.resolve(entry.getKey());Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());}
            var pb=new ProcessBuilder(tools.resolve("gopls").toString(),"serve").directory(dir.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);pb.environment().putAll(env);process=pb.start();
            var client=new LanguageClient(){
                public void telemetryEvent(Object p){}public void publishDiagnostics(PublishDiagnosticsParams p){}public void showMessage(MessageParams p){}public void logMessage(MessageParams p){}
                public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams p){return CompletableFuture.completedFuture(null);}
                public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams p){applyRequests[0]++;return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false));}
            };
            var launcher=new org.eclipse.lsp4j.jsonrpc.Launcher.Builder<LanguageServer>().setLocalService(client).setRemoteInterface(LanguageServer.class)
                    .setInput(process.getInputStream()).setOutput(process.getOutputStream()).setExecutorService(executor).create();
            listening=launcher.startListening();var server=launcher.getRemoteProxy();
            var initialize=new InitializeParams();initialize.setRootUri(dir.toUri().toString());initialize.setCapabilities(new ClientCapabilities());
            server.initialize(initialize).get(30,TimeUnit.SECONDS);server.initialized(new InitializedParams());
            var sources=new ArrayList<GoRefactorWorkspaceEdit.Source>();
            for(var entry:snapshot.entrySet()){
                String uri=dir.resolve(entry.getKey()).toUri().toString();server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri,"go",7,entry.getValue())));
                sources.add(new GoRefactorWorkspaceEdit.Source(uri,entry.getValue(),7));
            }
            String uri=dir.resolve("api/format.go").toUri().toString();String declaration=snapshot.get("api/format.go");
            Position selected=GoRefactorEdit.position(declaration,declaration.indexOf("func ")+1);
            var prepared=server.getTextDocumentService().prepareRename(new GoChangeSignature.PrepareRequest(new TextDocumentIdentifier(uri),selected)).get(30,TimeUnit.SECONDS);
            if(prepared==null||!prepared.isSecond()||GoChangeSignature.prepared(uri,declaration,selected,prepared.getSecond())==null)throw new AssertionError("No signature entry at the declaration func keyword");
            record.put("funcEntryPrepared",true);
            var cancelled=new AtomicBoolean();boolean expectRefusal=name.endsWith("refused")||name.startsWith("cancelled");
            try {
                var spec=name.equals("boolean-shadowing")?new GoAddParameter.Spec("extra","bool",2,"true"):new GoAddParameter.Spec("extra","int",1,"7");
                var wire=GoAddParameter.plan(tools.resolve("go").toString(),env,sources,uri,selected,spec,target->{
                    var refs=server.getTextDocumentService().references(new ReferenceParams(new TextDocumentIdentifier(target.getUri()),target.getRange().getStart(),new ReferenceContext(false))).get(30,TimeUnit.SECONDS);
                    record.put("referenceCount",refs==null?-1:refs.size());
                    if(name.equals("missing-reference-refused"))return List.of();
                    if(name.equals("outside-reference-refused")){var copy=new ArrayList<Location>(refs);copy.add(new Location(dir.resolve("outside.go").toUri().toString(),new Range(new Position(),new Position())));return copy;}
                    if(name.startsWith("cancelled"))cancelled.set(true);
                    return refs;
                },cancelled::get);
                if(expectRefusal)throw new AssertionError("Unsafe/cancelled request produced edits");
                // Exercise the actual loaded protocol model before applying any
                // source. Compiled TextEdit/Either generics can otherwise fail
                // only when the edit is encoded by the shipped LSP4J runtime.
                var gson=new GsonBuilder().setPrettyPrinting().create();
                String encoded=gson.toJson(wire);
                var roundTrip=gson.fromJson(encoded,WorkspaceEdit.class);
                var plan=GoRefactorWorkspaceEdit.prepare(sources,roundTrip);
                if(!plan.equals(GoRefactorWorkspaceEdit.prepare(sources,wire)))throw new AssertionError("Wire round-trip changed the planned edits");
                record.put("wireEdit",gson.fromJson(encoded,Object.class));record.put("wireRoundTripVerifiedBeforeApply",true);record.put("changedFiles",plan.files().size());
                if(plan.files().size()!=3)throw new AssertionError("Expected declaration, app caller and external test caller");
                for(var original:disk.entrySet())if(!Files.readString(dir.resolve(original.getKey())).equals(original.getValue()))throw new AssertionError("Preview modified disk");
                record.put("previewDiskUnchanged",true);
                for(var edit:plan.files())Files.writeString(Path.of(java.net.URI.create(edit.uri())),edit.edit().replacement());
                if(name.equals("dirty-unicode-callers")&&!Files.readString(dir.resolve("app/call.go")).startsWith("// unsaved \u4e16\u754c\ud83d\ude42"))throw new AssertionError("Unsaved Unicode snapshot lost");
                var test=new ProcessBuilder(tools.resolve("go").toString(),"test","-count=1","./...").directory(dir.toFile()).redirectErrorStream(true);test.environment().putAll(env);var child=test.start();
                try{if(!child.waitFor(45,TimeUnit.SECONDS))throw new AssertionError("go test timed out");String output=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8);if(child.exitValue()!=0)throw new AssertionError(output);record.put("goTestExit",0);record.put("goTestOutput",output);}finally{if(child.isAlive())child.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
            }catch(CancellationException expected){if(!name.startsWith("cancelled"))throw expected;record.put("cancelledBeforePlan",true);
            }catch(IllegalStateException expected){
                String required=switch(name){
                    case "function-value-refused" -> "not a verified direct call";
                    case "missing-reference-refused" -> "absent from the direct-call reference set";
                    case "outside-reference-refused" -> "outside the captured project";
                    default -> null;
                };
                if(required==null||expected.getMessage()==null||!expected.getMessage().contains(required))throw expected;
                record.put("refused",expected.getMessage());
            }
            if(expectRefusal){for(var original:disk.entrySet())if(!Files.readString(dir.resolve(original.getKey())).equals(original.getValue()))throw new AssertionError("Refused plan changed disk");record.put("allDiskUnchanged",true);}
            if(applyRequests[0]!=0)throw new AssertionError("Unexpected server applyEdit");record.put("serverApplyEditRequests",0);
            server.shutdown().get(5,TimeUnit.SECONDS);server.exit();if(!process.waitFor(5,TimeUnit.SECONDS)||process.exitValue()!=0)throw new AssertionError("gopls did not exit cleanly");
            record.put("passed",true);return record;
        }catch(Exception|Error problem){failure=problem;record.put("failure",describe(problem));throw problem;
        }finally{
            Throwable cleanupFailure=null;
            try{if(process!=null&&process.isAlive()){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS)&&!process.destroyForcibly().waitFor(5,TimeUnit.SECONDS))throw new IllegalStateException("Owned gopls process remained alive");}}
            catch(Exception|Error problem){cleanupFailure=problem;}
            try{if(listening!=null)listening.cancel(true);executor.shutdownNow();}
            catch(Exception|Error problem){cleanupFailure=combine(cleanupFailure,problem);}
            try{try(var paths=Files.walk(dir)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}record.put("fixtureDeleted",true);}
            catch(Exception|Error problem){cleanupFailure=combine(cleanupFailure,problem);record.put("fixtureDeleted",false);}
            if(cleanupFailure!=null){record.put("passed",false);record.put("cleanupFailure",describe(cleanupFailure));if(failure!=null)failure.addSuppressed(cleanupFailure);}
            writeEvidence(out.resolve(name+".json"),record,failure==null?cleanupFailure:failure);
            if(failure==null&&cleanupFailure!=null){if(cleanupFailure instanceof Exception exception)throw exception;throw (Error)cleanupFailure;}
        }
    }
    private static Throwable combine(Throwable first,Throwable next){if(first==null)return next;first.addSuppressed(next);return first;}
    private static String describe(Throwable problem){var text=new java.io.StringWriter();problem.printStackTrace(new java.io.PrintWriter(text));return text.toString();}
    private static void writeEvidence(Path path,Map<String,Object> report,Throwable original)throws Exception {
        try{Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");}
        catch(Exception|Error evidenceFailure){if(original==null)throw evidenceFailure;original.addSuppressed(evidenceFailure);}
    }
}

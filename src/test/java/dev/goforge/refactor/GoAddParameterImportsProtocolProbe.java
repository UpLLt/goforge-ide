package dev.goforge.refactor;

import com.google.gson.GsonBuilder;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Opt-in actual gopls plus compile-only planner probe on disposable projects; separate from JUnit and native UI. */
public final class GoAddParameterImportsProtocolProbe {
    private static final List<String> CASES=List.of("ordinary-cross-package","generic-default-import","dirty-unicode","default-cgo-parameter","implicit-scalar-package-refused","alias-shadow-refused","package-type-shadow-refused","typeparameter-shadow-refused","dot-import-refused","function-value-refused","missing-reference-refused","outside-reference-refused","init-and-testmain-not-run","metadata-change-after-preview","goenv-change-after-preview","sdk-change-after-preview","cancelled-after-references");
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Supply an empty evidence directory.");
        Path out=Path.of(args[0]);Files.createDirectories(out);try(var files=Files.list(out)){if(files.findAny().isPresent())throw new IllegalStateException("Evidence directory is not empty.");}
        var records=new ArrayList<Object>();var aggregate=new LinkedHashMap<String,Object>();aggregate.put("passed",false);aggregate.put("scope","Actual pinned gopls references and production import-aware Add Parameter compile-proof planner; every success compiled both snapshots including tests without executing project code; no native UI or full parity claim");aggregate.put("checks",records);aggregate.put("lspModelCodeSource",TextDocumentEdit.class.getProtectionDomain().getCodeSource().getLocation().toString());aggregate.put("plannerCodeSource",GoAddParameter.class.getProtectionDomain().getCodeSource().getLocation().toString());
        try{for(String name:CASES)records.add(run(out,name));aggregate.put("passed",true);}finally{write(out.resolve("result.json"),aggregate);}
        System.out.println(records.size()+" real import-aware add-parameter protocol cases passed.");
    }
    private static Map<String,Object> run(Path out,String name)throws Exception{
        Path tools=Path.of(System.getProperty("user.dir"),".tools","bin");
        Path dir=Files.createTempDirectory("goforge-add-import-protocol-").toRealPath();
        var env=new HashMap<>(System.getenv());env.keySet().removeIf(k->k.toUpperCase(Locale.ROOT).startsWith("GO"));
        env.put("PATH",tools+java.io.File.pathSeparator+env.getOrDefault("PATH",""));env.putAll(Map.of("GOENV","off","GOWORK","off","GOTOOLCHAIN","local","GOPROXY","off","GOSUMDB","off","GOTELEMETRY","off","CGO_ENABLED","0"));
        if(name.equals("goenv-change-after-preview")){Path config=dir.resolve("go-settings");Files.writeString(config,"CGO_ENABLED=0\n");env.put("GOENV",config.toString());}
        if(name.equals("default-cgo-parameter"))env.put("CGO_ENABLED","1");
        boolean generic=name.equals("generic-default-import")||name.equals("typeparameter-shadow-refused");
        String declaration="func F(first string, second string) string {return fmt.Sprint(first,second)}\n";
        if(generic)declaration="func F[T ~string](first T, second T) string {return fmt.Sprint(first,second)}\n";
        if(name.equals("typeparameter-shadow-refused"))declaration=declaration.replace("T","int");
        String importText="import \"fmt\"\n";
        if(name.equals("alias-shadow-refused")){importText="import int \"fmt\"\n";declaration=declaration.replace("fmt.Sprint","int.Sprint");}
        if(name.equals("dot-import-refused")){importText="import . \"fmt\"\n";declaration=declaration.replace("fmt.Sprint","Sprint");}
        var disk=new LinkedHashMap<String,String>();
        if(name.equals("implicit-scalar-package-refused")) {
            importText="import (\"fmt\";\"example.test/addimports/number\")\n";
            declaration=declaration.replace("return fmt.Sprint","_ = int.N; return fmt.Sprint");
            disk.put("number/n.go","package int\nconst N = 1\n");
        }
        disk.put("api/f.go","package api\n"+importText+declaration);
        if(name.equals("package-type-shadow-refused"))disk.put("api/shadow.go","package api\ntype int = string\n");
        String call=generic?"api.F[string]":"api.F";
        disk.put("app/call.go","package app\nimport api \"example.test/addimports/api\"\nvar Sequence string\nfunc mark(s string) string {Sequence+=s;return s}\nfunc Call() string {Sequence=\"\";return "+call+"(mark(\"A\"),mark(\"B\"))}\n"+(name.equals("function-value-refused")?"var Saved=api.F\n":""));
        disk.put("api/f_test.go","package api_test\nimport (\"testing\";\"example.test/addimports/api\")\nfunc TestExternal(t *testing.T){if got:="+call+"(\"C\",\"D\");got!=\"CD\"{t.Fatal(got)}}\n");
        disk.put("app/call_test.go","package app\nimport test \"testing\"\nfunc TestCall(t *test.T){if got:=Call();got!=\"AB\"||Sequence!=\"AB\"{t.Fatalf(\"%s/%s\",got,Sequence)}}\n");
        if(name.equals("init-and-testmain-not-run")){
            disk.put("api/f.go",disk.get("api/f.go")+"func init(){panic(\"planner executed package init\")}\n");
            disk.put("app/call_test.go",disk.get("app/call_test.go")+"func TestMain(m *test.M){panic(\"planner executed TestMain\")}\n");
        }
        var snapshot=new LinkedHashMap<>(disk);
        if(name.equals("dirty-unicode"))snapshot.put("app/call.go","// unsaved 世界🙂\n"+disk.get("app/call.go"));
        var record=new LinkedHashMap<String,Object>();record.put("case",name);record.put("passed",false);record.put("fixtureSources",snapshot);
        var executor=Executors.newCachedThreadPool();Process process=null;Future<?> listening=null;
        try{
            Files.writeString(dir.resolve("go.mod"),"module example.test/addimports\n\ngo 1.26\n");
            for(var e:disk.entrySet()){Path f=dir.resolve(e.getKey());Files.createDirectories(f.getParent());Files.writeString(f,e.getValue());}
            var builder=new ProcessBuilder(tools.resolve("gopls").toString(),"serve").directory(dir.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);builder.environment().putAll(env);process=builder.start();
            var applyRequests=new AtomicInteger();
            var client=new LanguageClient(){
                public void telemetryEvent(Object x){} public void publishDiagnostics(PublishDiagnosticsParams x){} public void showMessage(MessageParams x){} public void logMessage(MessageParams x){}
                public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams x){return CompletableFuture.completedFuture(null);}
                public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams x){applyRequests.incrementAndGet();return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false));}
            };
            var launcher=new org.eclipse.lsp4j.jsonrpc.Launcher.Builder<LanguageServer>().setLocalService(client).setRemoteInterface(LanguageServer.class).setInput(process.getInputStream()).setOutput(process.getOutputStream()).setExecutorService(executor).create();
            listening=launcher.startListening();var server=launcher.getRemoteProxy();var initialize=new InitializeParams();initialize.setRootUri(dir.toUri().toString());initialize.setCapabilities(new ClientCapabilities());server.initialize(initialize).get(30,TimeUnit.SECONDS);server.initialized(new InitializedParams());
            var sources=new ArrayList<GoRefactorWorkspaceEdit.Source>();for(var e:snapshot.entrySet()){
                String uri=dir.resolve(e.getKey()).toUri().toString();server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri,"go",7,e.getValue())));sources.add(new GoRefactorWorkspaceEdit.Source(uri,e.getValue(),7));
            }
            String uri=dir.resolve("api/f.go").toUri().toString(),text=snapshot.get("api/f.go");var position=GoRefactorEdit.position(text,text.indexOf("func F")+1);
            var prepared=server.getTextDocumentService().prepareRename(new GoChangeSignature.PrepareRequest(new TextDocumentIdentifier(uri),position)).get(30,TimeUnit.SECONDS);
            if(prepared==null||!prepared.isSecond()||!GoChangeSignature.preparedDeclaration(text,position,prepared.getSecond()))throw new AssertionError("Declaration menu entry unavailable.");
            record.put("preparePlaceholder",prepared.getSecond().getPlaceholder());
            var cancelled=new AtomicBoolean();String expected=switch(name){
                case "implicit-scalar-package-refused" -> "incompatible";
                case "alias-shadow-refused","package-type-shadow-refused","typeparameter-shadow-refused","dot-import-refused" -> "shadow";
                case "function-value-refused" -> "not a verified direct call";
                case "missing-reference-refused" -> "absent";
                case "outside-reference-refused" -> "outside";
                default -> null;
            };
            try{
                var spec=new GoAddParameter.Spec("extra","int",1,"7");
                if(name.equals("ordinary-cross-package")) {
                    try {GoAddParameter.plan(tools.resolve("go").toString(),env,sources,uri,position,spec,target->{throw new AssertionError("Unchecked imports queried references");},()->false);throw new AssertionError("Unchecked imports produced a plan");}
                    catch(GoAddParameter.CompilationRequired required){record.put("uncheckedPlannerRequiresCompileProof",true);}
                }
                var plan=GoAddParameter.compileCheckedPlan(tools.resolve("go").toString(),env,dir,sources,uri,position,spec,target->{
                    var refs=server.getTextDocumentService().references(new ReferenceParams(new TextDocumentIdentifier(target.getUri()),target.getRange().getStart(),new ReferenceContext(false))).get(30,TimeUnit.SECONDS);
                    if(refs==null)throw new AssertionError("Missing references.");record.put("references",refs);
                    if(name.equals("missing-reference-refused"))return List.of();
                    if(name.equals("outside-reference-refused")){var altered=new ArrayList<Location>(refs);altered.add(new Location(dir.resolve("outside.go").toUri().toString(),new Range(new Position(),new Position())));return altered;}
                    if(name.equals("cancelled-after-references"))cancelled.set(true);return refs;
                },cancelled::get);
                if(expected!=null||name.equals("cancelled-after-references"))throw new AssertionError("Expected refusal produced a plan.");
                var gson=new GsonBuilder().setPrettyPrinting().create();String wire=gson.toJson(plan.workspace());
                var preview=GoRefactorWorkspaceEdit.prepare(sources,gson.fromJson(wire,WorkspaceEdit.class));
                if(preview.files().size()!=3||plan.compiledPackages()!=2)throw new AssertionError("Expected declaration/two caller files and two compiled packages.");
                if(preview.files().stream().noneMatch(f->f.edit().replacement().contains("extra int")))throw new AssertionError("Missing new parameter.");
                plan.requireCurrent();record.put("compiledPackagesBeforeAndAfter",plan.compiledPackages());record.put("wire",gson.fromJson(wire,Object.class));record.put("changedFiles",3);
                if(name.equals("metadata-change-after-preview")){
                    Files.writeString(dir.resolve("go.mod"),"module example.test/changed\n\ngo 1.26\n");
                    try{plan.requireCurrent();throw new AssertionError("Changed metadata retained a compile proof.");}catch(IllegalStateException correct){if(!correct.getMessage().contains("metadata changed"))throw correct;record.put("metadataChangeRefused",true);}
                }
                if(name.equals("goenv-change-after-preview")){
                    Files.writeString(dir.resolve("go-settings"),"CGO_ENABLED=0\nGOARCH=386\n");
                    try{plan.requireCurrent();throw new AssertionError("Changed GOENV retained a compile proof.");}catch(IllegalStateException correct){record.put("goenvChangeRefused",true);}
                }
                if(name.equals("sdk-change-after-preview")){
                    try{plan.requireCompiler(tools.resolve("different-go").toString(),env);throw new AssertionError("Changed SDK retained a compile proof.");}catch(IllegalStateException correct){record.put("sdkSelectionChangeRefused",true);}
                    var changedEnvironment=new HashMap<>(env);changedEnvironment.put("GOARCH","386");
                    try{plan.requireCompiler(tools.resolve("go").toString(),changedEnvironment);throw new AssertionError("Changed environment retained a compile proof.");}catch(IllegalStateException correct){record.put("compilerEnvironmentChangeRefused",true);}
                }
                record.put("compileOnlyProjectCodeNotExecuted",true);
                for(var e:disk.entrySet())if(!Files.readString(dir.resolve(e.getKey())).equals(e.getValue()))throw new AssertionError("Planner wrote original source");
                if(Set.of("ordinary-cross-package","generic-default-import","dirty-unicode","default-cgo-parameter").contains(name)) {
                    for(var f:preview.files())Files.writeString(Path.of(java.net.URI.create(f.uri())),f.edit().replacement());
                    var command=new ProcessBuilder(tools.resolve("go").toString(),"test","-count=1","./...").directory(dir.toFile()).redirectErrorStream(true);command.environment().putAll(env);
                    Process tests=command.start();try{if(!tests.waitFor(45,TimeUnit.SECONDS))throw new AssertionError("Generated fixture tests timed out");String output=new String(tests.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);record.put("goTestOutput",output);record.put("goTestExit",tests.exitValue());if(tests.exitValue()!=0)throw new AssertionError(output);}finally{if(tests.isAlive())tests.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
                    if(name.equals("dirty-unicode")&&!Files.readString(dir.resolve("app/call.go")).startsWith("// unsaved 世界🙂"))throw new AssertionError("Dirty overlay lost");
                    for(var e:disk.entrySet())Files.writeString(dir.resolve(e.getKey()),e.getValue());
                    record.put("fixtureAppliedTestedAndRestored",true);
                }

            }catch(CancellationException correct){if(!name.equals("cancelled-after-references"))throw correct;record.put("cancelledBeforePlan",true);}
            catch(IllegalStateException refusal){if(expected==null||!refusal.getMessage().contains(expected))throw refusal;record.put("refused",refusal.getMessage());}
            for(var e:disk.entrySet())if(!Files.readString(dir.resolve(e.getKey())).equals(e.getValue()))throw new AssertionError("Planner changed original source "+e.getKey());
            record.put("originalSourceBytesUnchangedOrRestoredAfterExplicitFixtureTest",true);if(applyRequests.get()!=0)throw new AssertionError("Unexpected applyEdit request.");
            server.shutdown().get(5,TimeUnit.SECONDS);server.exit();if(!process.waitFor(5,TimeUnit.SECONDS)||process.exitValue()!=0)throw new AssertionError("gopls did not shut down.");record.put("goplsExit",0);record.put("passed",true);
            return record;
        }catch(Exception|Error failure){record.put("failure",failure.toString());throw failure;}
        finally{
            if(process!=null&&process.isAlive()){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS)){process.destroyForcibly();if(!process.waitFor(5,TimeUnit.SECONDS))throw new IllegalStateException("Owned gopls remained alive.");}}
            if(listening!=null)listening.cancel(true);executor.shutdownNow();
            try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}record.put("fixtureDeleted",true);record.put("goplsAliveAfterCleanup",process!=null&&process.isAlive());write(out.resolve(name+".json"),record);
        }
    }
    private static void write(Path path,Object value)throws Exception{Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(value)+"\n");}
}

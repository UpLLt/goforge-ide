package dev.goforge.refactor;

import com.google.gson.GsonBuilder;
import dev.goforge.tools.HostTools;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Opt-in actual gopls plus compile-only planner probe on disposable projects; separate from JUnit and native UI. */
public final class GoMethodAddParameterProtocolProbe {
    private static final List<String> CASES=List.of("value-imports","pointer-imports","dirty-unicode","init-and-testmain-not-run","interface-old-refused","interface-new-refused","interface-alias-refused","constraint-refused","anonymous-interface-refused","method-value-refused","method-expression-refused","promoted-refused","public-method-refused","public-receiver-refused","generic-receiver-refused","alias-receiver-refused","missing-reference-refused","outside-reference-refused","metadata-change-after-preview","goenv-change-after-preview","sdk-change-after-preview","default-cgo-parameter","cancelled-after-references");
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Supply an empty evidence directory.");
        Path out=Path.of(args[0]);Files.createDirectories(out);try(var files=Files.list(out)){if(files.findAny().isPresent())throw new IllegalStateException("Evidence directory is not empty.");}
        var records=new ArrayList<Object>();var aggregate=new LinkedHashMap<String,Object>();aggregate.put("passed",false);aggregate.put("scope","Actual pinned gopls references and production private-method typed/compile planner on owned generated projects; no native UI or full parity claim");aggregate.put("checks",records);aggregate.put("lspModelCodeSource",TextDocumentEdit.class.getProtectionDomain().getCodeSource().getLocation().toString());aggregate.put("plannerCodeSource",GoAddParameter.class.getProtectionDomain().getCodeSource().getLocation().toString());aggregate.put("typedProofCodeSource",GoMethodParameterSafety.class.getProtectionDomain().getCodeSource().getLocation().toString());aggregate.put("compilerProofCodeSource",GoChangeScalarType.class.getProtectionDomain().getCodeSource().getLocation().toString());aggregate.put("javaHome",System.getProperty("java.home"));aggregate.put("goExecutable",tool("go").toString());aggregate.put("goplsExecutable",tool("gopls").toString());
        try{for(String name:CASES)records.add(run(out,name));aggregate.put("passed",true);}finally{write(out.resolve("result.json"),aggregate);}
        System.out.println(records.size()+" real private-method add-parameter protocol cases passed.");
    }
    private static Map<String,Object> run(Path out,String name)throws Exception{
        Path go=tool("go"),gopls=tool("gopls");
        var scratch=GoRefactorScratch.create("goforge-private-method-protocol-");Path dir=scratch.path();
        var env=new HashMap<>(System.getenv());env.keySet().removeIf(k->k.toUpperCase(Locale.ROOT).startsWith("GO"));
        HostTools.putEnvironment(env,Map.of("PATH",go.getParent()+java.io.File.pathSeparator+gopls.getParent()+java.io.File.pathSeparator+HostTools.environmentValue(env,"PATH")));env.putAll(Map.of("GOENV","off","GOWORK","off","GOTOOLCHAIN","local","GOPROXY","off","GOSUMDB","off","GOTELEMETRY","off","CGO_ENABLED","0"));
        String cacheRoot=System.getProperty("goforge.probe.cacheRoot");
        if(cacheRoot!=null){Path cache=Path.of(cacheRoot).toRealPath();env.putAll(Map.of("GOCACHE",cache.resolve("go-cache").toString(),"GOMODCACHE",cache.resolve("go-modcache").toString(),"GOPATH",cache.resolve("gopath").toString()));}
        env.putAll(Map.of("GOPACKAGESDRIVER","off","GOVCS","*:off"));
        if(name.equals("goenv-change-after-preview")){Path config=dir.resolve("go-settings");Files.writeString(config,"CGO_ENABLED=0\n");env.put("GOENV",config.toString());}
        if(name.equals("default-cgo-parameter"))env.put("CGO_ENABLED","1");
        String methodName=name.equals("public-method-refused")?"Combine":"combine";
        String receiverName=name.equals("public-receiver-refused")?"Worker":"worker";
        String receiver=name.equals("pointer-imports")?"*"+receiverName:receiverName;
        String receiverDeclaration="type "+receiverName+" struct{ prefix string }\n";
        if(name.equals("generic-receiver-refused")){receiverDeclaration="type worker[T ~string] struct{prefix T}\n";receiver="worker[T]";receiverName="worker[string]";}
        if(name.equals("alias-receiver-refused")){receiverDeclaration+="type workerAlias = worker\n";receiver="workerAlias";}
        String declaration="func (w "+receiver+") "+methodName+"(first string, second string) string {return fmt.Sprint(w.prefix,first,second)}\n";
        var disk=new LinkedHashMap<String,String>();
        disk.put("api/method.go","package api\nimport \"fmt\"\n"+receiverDeclaration+declaration);
        String call="w."+methodName+"(mark(\"A\"),mark(\"B\"))";
        if(name.equals("method-expression-refused"))call="worker.combine(w,mark(\"A\"),mark(\"B\"))";
        String local=name.equals("promoted-refused")?"wrapped":receiverName;
        disk.put("api/call.go","package api\nvar Sequence string\nfunc mark(s string)string{Sequence+=s;return s}\nfunc Call()string{Sequence=\"\";w:="+local+"{};return "+call+"}\n");
        disk.put("api/method_test.go","package api\nimport \"testing\"\n"+"func TestCall(t *testing.T){if got:=Call();got!=\"AB\"||Sequence!=\"AB\"{t.Fatalf(\"%s/%s\",got,Sequence)};w:="+receiverName+"{};if got:=w."+methodName+"(\"C\",\"D\");got!=\"CD\"{t.Fatal(got)}}\n");
        String extra=switch(name){
            case "interface-old-refused" -> "type contract interface{combine(string,string)string}\nfunc check(){_,_=any(worker{}).(contract)}\n";
            case "interface-new-refused" -> "type contract interface{combine(string,int,string)string}\nfunc check(){_,_=any(worker{}).(contract)}\n";
            case "interface-alias-refused" -> "type contract interface{combine(string,string)string};type alias=contract;type embedded interface{alias}\n";
            case "constraint-refused" -> "func constrained[T interface{combine(string,string)string}](v T){}\n";
            case "anonymous-interface-refused" -> "func check(){_,_=any(worker{}).(interface{combine(string,string)string})}\n";
            case "method-value-refused" -> "var stored=worker{}.combine\n";
            case "promoted-refused" -> "type wrapped struct{worker}\n";
            default -> "";
        };
        disk.put("api/method.go",disk.get("api/method.go")+extra);
        if(name.equals("init-and-testmain-not-run")){
            disk.put("api/method.go",disk.get("api/method.go")+"func init(){panic(\"planner executed package init\")}\n");
            disk.put("api/method_test.go",disk.get("api/method_test.go")+"func TestMain(m *testing.M){panic(\"planner executed TestMain\")}\n");
        }
        var snapshot=new LinkedHashMap<>(disk);
        if(name.equals("dirty-unicode"))snapshot.put("api/call.go","// unsaved 世界🙂\n"+disk.get("api/call.go"));
        var record=new LinkedHashMap<String,Object>();record.put("case",name);record.put("passed",false);record.put("fixtureSources",snapshot);
        var executor=Executors.newCachedThreadPool();Process process=null;Future<?> listening=null;Throwable primaryFailure=null;
        try{
            Files.writeString(dir.resolve("go.mod"),"module example.test/addimports\n\ngo 1.26\n");
            for(var e:disk.entrySet()){Path f=dir.resolve(e.getKey());Files.createDirectories(f.getParent());Files.writeString(f,e.getValue());}
            var builder=new ProcessBuilder(gopls.toString(),"serve").directory(dir.toFile()).redirectError(out.resolve(name+"-gopls.log").toFile());builder.environment().clear();builder.environment().putAll(env);process=builder.start();
            var applyRequests=new AtomicInteger();
            Path targetPath=dir.resolve("api/method.go");var diagnosticsReady=new CompletableFuture<PublishDiagnosticsParams>();
            var observedDiagnostics=new java.util.concurrent.CopyOnWriteArrayList<Object>();
            var serverMessages=new java.util.concurrent.CopyOnWriteArrayList<String>();record.put("serverMessages",serverMessages);
            var client=new LanguageClient(){
                public void telemetryEvent(Object x){}
                public void publishDiagnostics(PublishDiagnosticsParams x){
                    if(x.getUri()!=null&&GoRefactorWorkspaceEdit.uriPath(x.getUri()).equals(targetPath)){
                        observedDiagnostics.add(Map.of("version",x.getVersion()==null?-1:x.getVersion(),"diagnostics",x.getDiagnostics()));
                        if(Objects.equals(x.getVersion(),7))diagnosticsReady.complete(x);
                    }
                }
                public void showMessage(MessageParams x){logMessage(x);} public void logMessage(MessageParams x){if(serverMessages.size()<100)serverMessages.add(String.valueOf(x.getMessage()).substring(0,Math.min(String.valueOf(x.getMessage()).length(),2000)));}
                public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams x){return CompletableFuture.completedFuture(null);}
                public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams x){applyRequests.incrementAndGet();return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false));}
            };
            var launcher=new org.eclipse.lsp4j.jsonrpc.Launcher.Builder<LanguageServer>().setLocalService(client).setRemoteInterface(LanguageServer.class).setInput(process.getInputStream()).setOutput(process.getOutputStream()).setExecutorService(executor).create();
            listening=launcher.startListening();var server=launcher.getRemoteProxy();var initialize=new InitializeParams();initialize.setRootUri(dir.toUri().toString());initialize.setWorkspaceFolders(List.of(new WorkspaceFolder(dir.toUri().toString(),"owned-method-fixture")));
            var capabilities=new ClientCapabilities();var textCapabilities=new TextDocumentClientCapabilities();var diagnosticCapabilities=new PublishDiagnosticsCapabilities();diagnosticCapabilities.setVersionSupport(true);textCapabilities.setPublishDiagnostics(diagnosticCapabilities);capabilities.setTextDocument(textCapabilities);initialize.setCapabilities(capabilities);
            server.initialize(initialize).get(30,TimeUnit.SECONDS);server.initialized(new InitializedParams());
            var sources=new ArrayList<GoRefactorWorkspaceEdit.Source>();for(var e:snapshot.entrySet()){
                String uri=dir.resolve(e.getKey()).toUri().toString();server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri,"go",7,e.getValue())));sources.add(new GoRefactorWorkspaceEdit.Source(uri,e.getValue(),7));
            }
            String uri=dir.resolve("api/method.go").toUri().toString(),text=snapshot.get("api/method.go");var position=GoRefactorEdit.position(text,text.indexOf("func (")+1);
            try{
                var diagnostic=diagnosticsReady.get(90,TimeUnit.SECONDS);
                if(diagnostic.getDiagnostics().stream().anyMatch(d->d.getSeverity()==DiagnosticSeverity.Error))throw new AssertionError("Generated method fixture has error diagnostics: "+diagnostic.getDiagnostics());
                record.put("versionedDiagnosticsReady",true);record.put("observedDiagnostics",observedDiagnostics);
            }catch(TimeoutException unavailable){record.put("observedDiagnostics",observedDiagnostics);throw new AssertionError("Fresh gopls did not publish version 7 diagnostics for the canonical method fixture.",unavailable);}
            var prepared=server.getTextDocumentService().prepareRename(new GoChangeSignature.PrepareRequest(new TextDocumentIdentifier(uri),position)).get(30,TimeUnit.SECONDS);
            if(prepared==null||!prepared.isSecond()||!GoChangeSignature.preparedDeclaration(text,position,prepared.getSecond()))throw new AssertionError("Declaration menu entry unavailable.");
            record.put("preparePlaceholder",prepared.getSecond().getPlaceholder());
            var cancelled=new AtomicBoolean();String expected=switch(name){
                case "interface-old-refused","interface-new-refused","interface-alias-refused","constraint-refused","anonymous-interface-refused","missing-reference-refused" -> "absent";
                case "method-value-refused" -> "not a verified direct call";
                case "method-expression-refused" -> "tuple-valued";
                case "promoted-refused" -> "promoted";
                case "public-method-refused" -> "private method";
                case "public-receiver-refused","generic-receiver-refused" -> "private non-generic";
                case "alias-receiver-refused" -> "non-alias";
                case "outside-reference-refused" -> "outside";
                default -> null;
            };
            try{
                var spec=new GoAddParameter.Spec("extra","int",1,"7");
                if(name.equals("value-imports")) {
                    try {GoAddParameter.plan(go.toString(),env,sources,uri,position,spec,target->{throw new AssertionError("Unchecked method queried references");},()->false);throw new AssertionError("Unchecked method produced a plan");}
                    catch(GoAddParameter.CompilationRequired required){GoRefactorScratch.requireCleanFailure(required);record.put("uncheckedMethodRequiresCompileProof",true);}
                }
                var plan=GoAddParameter.compileCheckedPlan(go.toString(),env,dir,sources,uri,position,spec,target->{
                    var refs=server.getTextDocumentService().references(new ReferenceParams(new TextDocumentIdentifier(target.getUri()),target.getRange().getStart(),new ReferenceContext(false))).get(30,TimeUnit.SECONDS);
                    if(refs==null)throw new AssertionError("Missing references.");record.put("references",refs);
                    if(name.equals("missing-reference-refused"))return List.of();
                    if(name.equals("outside-reference-refused")){var altered=new ArrayList<Location>(refs);altered.add(new Location(dir.resolve("outside.go").toUri().toString(),new Range(new Position(),new Position())));return altered;}
                    if(name.equals("cancelled-after-references"))cancelled.set(true);return refs;
                },cancelled::get);
                if(expected!=null||name.equals("cancelled-after-references"))throw new AssertionError("Expected refusal produced a plan.");
                var gson=new GsonBuilder().setPrettyPrinting().create();String wire=gson.toJson(plan.workspace());
                var preview=GoRefactorWorkspaceEdit.prepare(sources,gson.fromJson(wire,WorkspaceEdit.class));
                if(preview.files().size()!=3||plan.compiledPackages()!=1)throw new AssertionError("Expected declaration/two caller files and one compiled package.");
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
                    try{plan.requireCompiler(go.resolveSibling(HostTools.executableName("different-go")).toString(),env);throw new AssertionError("Changed SDK retained a compile proof.");}catch(IllegalStateException correct){record.put("sdkSelectionChangeRefused",true);}
                    var changedEnvironment=new HashMap<>(env);changedEnvironment.put("GOARCH","386");
                    try{plan.requireCompiler(go.toString(),changedEnvironment);throw new AssertionError("Changed environment retained a compile proof.");}catch(IllegalStateException correct){record.put("compilerEnvironmentChangeRefused",true);}
                }
                record.put("compileOnlyProjectCodeNotExecuted",true);
                for(var e:disk.entrySet())if(!Files.readString(dir.resolve(e.getKey())).equals(e.getValue()))throw new AssertionError("Planner wrote original source");
                if(Set.of("value-imports","pointer-imports","dirty-unicode","default-cgo-parameter").contains(name)) {
                    for(var f:preview.files())Files.writeString(Path.of(java.net.URI.create(f.uri())),f.edit().replacement());
                    var command=new ProcessBuilder(go.toString(),"test","-count=1","./...").directory(dir.toFile()).redirectErrorStream(true);command.environment().clear();command.environment().putAll(env);
                    Process tests=command.start();try{if(!tests.waitFor(45,TimeUnit.SECONDS))throw new AssertionError("Generated fixture tests timed out");String output=new String(tests.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);record.put("goTestOutput",output);record.put("goTestExit",tests.exitValue());if(tests.exitValue()!=0)throw new AssertionError(output);}finally{if(tests.isAlive())tests.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
                    if(name.equals("dirty-unicode")&&!Files.readString(dir.resolve("api/call.go")).startsWith("// unsaved 世界🙂"))throw new AssertionError("Dirty overlay lost");
                    for(var e:disk.entrySet())Files.writeString(dir.resolve(e.getKey()),e.getValue());
                    record.put("fixtureAppliedTestedAndRestored",true);
                }

            }catch(CancellationException correct){GoRefactorScratch.requireCleanFailure(correct);if(!name.equals("cancelled-after-references"))throw correct;record.put("cancelledBeforePlan",true);}
            catch(IllegalStateException refusal){GoRefactorScratch.requireCleanFailure(refusal);if(expected==null||!refusal.getMessage().contains(expected))throw refusal;record.put("refused",refusal.getMessage());}
            for(var e:disk.entrySet())if(!Files.readString(dir.resolve(e.getKey())).equals(e.getValue()))throw new AssertionError("Planner changed original source "+e.getKey());
            record.put("originalSourceBytesUnchangedOrRestoredAfterExplicitFixtureTest",true);if(applyRequests.get()!=0)throw new AssertionError("Unexpected applyEdit request.");
            server.shutdown().get(5,TimeUnit.SECONDS);server.exit();if(!process.waitFor(5,TimeUnit.SECONDS)||process.exitValue()!=0)throw new AssertionError("gopls did not shut down.");record.put("goplsExit",0);record.put("passed",true);
            return record;
        }catch(Exception|Error failure){primaryFailure=failure;record.put("failure",GoRefactorScratch.failureDetail(failure));throw failure;}
        finally{
            Exception cleanupFailure=null;
            try{
                if(process!=null&&process.isAlive()){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS)){process.destroyForcibly();if(!process.waitFor(5,TimeUnit.SECONDS))throw new IllegalStateException("Owned gopls remained alive.");}}
            }catch(Exception cleanup){cleanupFailure=cleanup;}
            if(listening!=null)listening.cancel(true);executor.shutdownNow();
            try{scratch.close();}
            catch(Exception cleanup){if(cleanupFailure==null)cleanupFailure=cleanup;else cleanupFailure.addSuppressed(cleanup);}
            record.put("fixtureDeleted",!Files.exists(dir));record.put("goplsAliveAfterCleanup",process!=null&&process.isAlive());
            if(cleanupFailure!=null){record.put("passed",false);record.put("cleanupFailure",cleanupFailure.toString());}
            try{write(out.resolve(name+".json"),record);}catch(Exception writing){if(cleanupFailure==null)cleanupFailure=writing;else cleanupFailure.addSuppressed(writing);}
            if(cleanupFailure!=null){if(primaryFailure!=null)primaryFailure.addSuppressed(cleanupFailure);else throw cleanupFailure;}
        }
    }

    private static Path tool(String name)throws Exception {
        String configured=System.getProperty("goforge.probe."+name);
        Path path=configured==null?Path.of(System.getProperty("user.dir"),".tools","bin",HostTools.executableName(name)):Path.of(configured);
        path=path.toRealPath();if(!Files.isRegularFile(path)||!Files.isExecutable(path))throw new IllegalArgumentException("Probe tool is not an executable regular file: "+name);return path;
    }
    private static void write(Path path,Object value)throws Exception{Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(value)+"\n");}
}

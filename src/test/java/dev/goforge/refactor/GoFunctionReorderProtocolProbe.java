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
public final class GoFunctionReorderProtocolProbe {
    private static final List<String> CASES=List.of("shared-state","static-imported-argument","constants-exact-conversion","typed-float-argument","byte-rune-aliases","temporary-name-collision","panic-first-argument","recover-in-argument","dirty-unicode","init-and-testmain-not-run","mutable-variable-refused","function-variable-refused","shadowed-function-refused","method-argument-refused","conversion-refused","builtin-refused","caller-type-shadow-refused","declaration-alias-refused","function-value-refused","return-refused","go-refused","defer-refused","label-refused","tuple-refused","ellipsis-refused","generic-argument-refused","missing-reference-refused","outside-reference-refused","metadata-change-after-preview","sdk-change-after-preview","cancelled-after-references");
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Supply an empty evidence directory.");
        Path out=Path.of(args[0]);Files.createDirectories(out);try(var files=Files.list(out)){if(files.findAny().isPresent())throw new IllegalStateException("Evidence directory is not empty.");}
        var records=new ArrayList<Object>();var aggregate=new LinkedHashMap<String,Object>();aggregate.put("passed",false);aggregate.put("scope","Actual pinned gopls references and production private-function typed/compile planner on owned generated projects; no native UI or full parity claim");aggregate.put("checks",records);aggregate.put("lspModelCodeSource",TextDocumentEdit.class.getProtectionDomain().getCodeSource().getLocation().toString());aggregate.put("plannerCodeSource",GoFunctionReorder.class.getProtectionDomain().getCodeSource().getLocation().toString());aggregate.put("typedProofCodeSource",GoFunctionReorderSafety.class.getProtectionDomain().getCodeSource().getLocation().toString());aggregate.put("compilerProofCodeSource",GoChangeScalarType.class.getProtectionDomain().getCodeSource().getLocation().toString());aggregate.put("javaHome",System.getProperty("java.home"));aggregate.put("goExecutable",tool("go").toString());aggregate.put("goplsExecutable",tool("gopls").toString());
        try{for(String name:CASES)records.add(run(out,name));aggregate.put("passed",true);}finally{write(out.resolve("result.json"),aggregate);}
        System.out.println(records.size()+" real private-function reorder protocol cases passed.");
    }
    private static Map<String,Object> run(Path out,String name)throws Exception{
        Path go=tool("go"),gopls=tool("gopls");
        var scratch=GoRefactorScratch.create("goforge-function-reorder-protocol-");Path dir=scratch.path();
        var env=new HashMap<>(System.getenv());env.keySet().removeIf(k->k.toUpperCase(Locale.ROOT).startsWith("GO"));
        HostTools.putEnvironment(env,Map.of("PATH",go.getParent()+java.io.File.pathSeparator+gopls.getParent()+java.io.File.pathSeparator+HostTools.environmentValue(env,"PATH")));env.putAll(Map.of("GOENV","off","GOWORK","off","GOTOOLCHAIN","local","GOPROXY","off","GOSUMDB","off","GOTELEMETRY","off","CGO_ENABLED","0"));
        String cacheRoot=System.getProperty("goforge.probe.cacheRoot");
        if(cacheRoot!=null){Path cache=Path.of(cacheRoot).toRealPath();env.putAll(Map.of("GOCACHE",cache.resolve("go-cache").toString(),"GOMODCACHE",cache.resolve("go-modcache").toString(),"GOPATH",cache.resolve("gopath").toString()));}
        env.putAll(Map.of("GOPACKAGESDRIVER","off","GOVCS","*:off"));
        if(name.equals("goenv-change-after-preview")){Path config=dir.resolve("go-settings");Files.writeString(config,"CGO_ENABLED=0\n");env.put("GOENV",config.toString());}
        if(name.equals("default-cgo-parameter"))env.put("CGO_ENABLED","1");
        String signature="func(b int, a int)";
        var disk=new LinkedHashMap<String,String>();
        disk.put("main.go","package main\nimport \"fmt\"\nfunc reorder(a int, b int){fmt.Printf(\"%d,%d|\",a,b)}\n");
        disk.put("helpers.go","package main\nimport \"fmt\"\nvar state int\nfunc g()int{state++;fmt.Print(\"g|\");return state}\nfunc h()int{state+=10;fmt.Print(\"h|\");return state}\nfunc main(){runCase()}\n");
        String call="reorder(g(),h())";String imports="";String prefix="";
        switch(name){
            case "static-imported-argument" -> {imports="import \"strings\"\n";call="reorder(strings.Compare(\"a\",\"b\"),h())";}
            case "constants-exact-conversion" -> {signature="func(b int64, a float32)";disk.put("main.go","package main\nimport \"fmt\"\nfunc reorder(a float32,b int64){fmt.Printf(\"%.9g,%d|\",a,b)}\n");call="reorder(0.1+0.2,1<<40)";}
            case "typed-float-argument" -> {signature="func(b int64, a float32)";disk.put("main.go","package main\nimport \"fmt\"\nfunc reorder(a float32,b int64){fmt.Printf(\"%.9g,%d|\",a,b)}\n");disk.put("helpers.go","package main\nimport \"fmt\"\nfunc g()float32{fmt.Print(\"g|\");return 0.1+0.2};func h()int64{fmt.Print(\"h|\");return 1<<40};func main(){runCase()}\n");}
            case "byte-rune-aliases" -> {signature="func(b rune, a byte)";disk.put("main.go","package main\nimport \"fmt\"\nfunc reorder(a byte,b rune){fmt.Printf(\"%d,%d|\",a,b)}\n");disk.put("helpers.go","package main\nfunc g()byte{return 255};func h()rune{return '界'};func main(){runCase()}\n");}
            case "temporary-name-collision" -> {prefix="var _goforgeArg0 int;_= _goforgeArg0;";disk.put("helpers.go",disk.get("helpers.go")+"var _goforgeArg1 int\n");}
            case "panic-first-argument" -> {disk.put("helpers.go",disk.get("helpers.go").replace("state++;fmt.Print(\"g|\");return state","state++;fmt.Print(\"g|\");panic(\"boom\")").replace("func main(){runCase()}","func main(){defer func(){fmt.Print(recover(),state)}();runCase()}"));}
            case "recover-in-argument" -> {disk.put("helpers.go",disk.get("helpers.go").replace("state++;fmt.Print(\"g|\");return state","state++;fmt.Printf(\"g-recover:%v|\",recover());return state").replace("func main(){runCase()}","func main(){defer func(){fmt.Print(recover())}();panicking()}\nfunc panicking(){defer runCase();panic(\"boom\")}"));}
            case "mutable-variable-refused" -> {prefix="n:=1;";call="reorder(n,h())";}
            case "function-variable-refused" -> {prefix="fn:=g;";call="reorder(fn(),h())";}
            case "shadowed-function-refused" -> {prefix="g:=func()int{return 4};";call="reorder(g(),h())";}
            case "method-argument-refused" -> {prefix="w:=worker{};";call="reorder(w.get(),h())";disk.put("helpers.go",disk.get("helpers.go")+"type worker struct{};func(worker)get()int{return 1}\n");}
            case "conversion-refused" -> {prefix="n:=int64(1);";call="reorder(int(n),h())";}
            case "builtin-refused" -> {prefix="xs:=[]int{1};";call="reorder(len(xs),h())";}
            case "caller-type-shadow-refused" -> prefix="type int = int64;";
            case "declaration-alias-refused" -> disk.put("main.go",disk.get("main.go")+"type int = int64\n");
            case "function-value-refused" -> disk.put("helpers.go",disk.get("helpers.go")+"var stored=reorder\n");
            case "return-refused" -> {disk.put("main.go",disk.get("main.go").replace("){fmt.Printf", ")int{fmt.Printf").replace("a,b)}","a,b);return a}"));call="return reorder(g(),h())";signature="func(b int,a int)int";}
            case "go-refused" -> call="go reorder(g(),h())";
            case "defer-refused" -> call="defer reorder(g(),h())";
            case "label-refused" -> {prefix="L: ";call="reorder(g(),h());if false{goto L}";}
            case "tuple-refused" -> {call="reorder(pair())";disk.put("helpers.go",disk.get("helpers.go")+"func pair()(int,int){return 1,2}\n");}
            case "ellipsis-refused" -> {disk.put("main.go",disk.get("main.go").replace("b int)","b ...int)"));prefix="xs:=[]int{2};";call="reorder(1,xs...)";signature="func(b ...int,a int)";}
            case "generic-argument-refused" -> {disk.put("helpers.go",disk.get("helpers.go")+"func generic[T ~int](v T)T{return v}\n");call="reorder(generic(1),h())";}
        }
        disk.put("calls.go","package main\n"+imports+"func runCase()"+(name.equals("return-refused")?"int":"")+"{"+prefix+call+"}\n");
        disk.put("fixture_test.go","package main\nimport \"testing\"\nfunc TestFixture(t *testing.T){}\n");
        if(name.equals("init-and-testmain-not-run")){disk.put("main.go",disk.get("main.go")+"func init(){panic(\"init was executed\")}\n");disk.put("fixture_test.go",disk.get("fixture_test.go")+"func TestMain(m *testing.M){panic(\"TestMain was executed\")}\n");}
        var snapshot=new LinkedHashMap<>(disk);if(name.equals("dirty-unicode"))snapshot.put("calls.go","// unsaved 世界🙂\n"+disk.get("calls.go"));
        var record=new LinkedHashMap<String,Object>();record.put("case",name);record.put("passed",false);record.put("fixtureSources",snapshot);
        var executor=Executors.newCachedThreadPool();Process process=null;Future<?> listening=null;Throwable primaryFailure=null;
        try{
            Files.writeString(dir.resolve("go.mod"),"module example.test/functionreorder\n\ngo 1.26\n");
            for(var e:disk.entrySet()){Path f=dir.resolve(e.getKey());Files.createDirectories(f.getParent());Files.writeString(f,e.getValue());}
            var builder=new ProcessBuilder(gopls.toString(),"serve").directory(dir.toFile()).redirectError(out.resolve(name+"-gopls.log").toFile());builder.environment().clear();builder.environment().putAll(env);process=builder.start();
            var applyRequests=new AtomicInteger();
            Path targetPath=dir.resolve("main.go");var diagnosticsReady=new CompletableFuture<PublishDiagnosticsParams>();
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
            String uri=dir.resolve("main.go").toUri().toString(),text=snapshot.get("main.go");var position=GoRefactorEdit.position(text,text.indexOf("func reorder")+1);
            try{
                var diagnostic=diagnosticsReady.get(90,TimeUnit.SECONDS);
                if(diagnostic.getDiagnostics().stream().anyMatch(d->d.getSeverity()==DiagnosticSeverity.Error))throw new AssertionError("Generated method fixture has error diagnostics: "+diagnostic.getDiagnostics());
                record.put("versionedDiagnosticsReady",true);record.put("observedDiagnostics",observedDiagnostics);
            }catch(TimeoutException unavailable){record.put("observedDiagnostics",observedDiagnostics);throw new AssertionError("Fresh gopls did not publish version 7 diagnostics for the canonical method fixture.",unavailable);}
            var prepared=server.getTextDocumentService().prepareRename(new GoChangeSignature.PrepareRequest(new TextDocumentIdentifier(uri),position)).get(30,TimeUnit.SECONDS);
            if(prepared==null||!prepared.isSecond()||!GoChangeSignature.preparedDeclaration(text,position,prepared.getSecond()))throw new AssertionError("Declaration menu entry unavailable.");
            record.put("preparePlaceholder",prepared.getSecond().getPlaceholder());
            var cancelled=new AtomicBoolean();String expected=switch(name){
                case "mutable-variable-refused" -> "each argument";
                case "function-variable-refused","shadowed-function-refused","conversion-refused","builtin-refused" -> "builtins, conversions";
                case "method-argument-refused" -> "method calls";
                case "caller-type-shadow-refused" -> "shadows";
                case "declaration-alias-refused" -> "without shadowing";
                case "function-value-refused","tuple-refused" -> "direct non-variadic";
                case "return-refused","go-refused","defer-refused" -> "expression-statement";
                case "label-refused" -> "labels";
                case "ellipsis-refused" -> "scalar parameters";
                case "generic-argument-refused" -> "argument function";
                case "missing-reference-refused" -> "reference set";
                case "outside-reference-refused" -> "outside";
                default -> null;
            };
            String beforeOutput=null;
            if(expected==null&&!name.equals("init-and-testmain-not-run"))beforeOutput=executeFixture(go,dir,env);
            record.put("beforeOutput",beforeOutput);
            String expectedOutput=switch(name){
                case "shared-state","temporary-name-collision","dirty-unicode" -> "g|h|1,11|";
                case "static-imported-argument" -> "h|-1,10|";
                case "constants-exact-conversion" -> "0.300000012,1099511627776|";
                case "typed-float-argument" -> "g|h|0.300000012,1099511627776|";
                case "byte-rune-aliases" -> "255,30028|";
                case "panic-first-argument" -> "g|boom1";
                case "recover-in-argument" -> "g-recover:<nil>|h|1,11|boom";
                default -> null;
            };
            if(expectedOutput!=null&&!expectedOutput.equals(beforeOutput))throw new AssertionError("Unexpected behavior baseline: "+beforeOutput);
            record.put("expectedOutput",expectedOutput);
            try{
                var plan=GoFunctionReorder.plan(go.toString(),env,dir,sources,uri,position,signature,target->{
                    var refs=server.getTextDocumentService().references(new ReferenceParams(new TextDocumentIdentifier(target.getUri()),target.getRange().getStart(),new ReferenceContext(false))).get(30,TimeUnit.SECONDS);
                    if(refs==null)throw new AssertionError("Missing references.");record.put("references",refs);
                    if(name.equals("missing-reference-refused"))return List.of();
                    if(name.equals("outside-reference-refused")){var altered=new ArrayList<Location>(refs);altered.add(new Location(dir.resolve("outside.go").toUri().toString(),new Range(new Position(),new Position())));return altered;}
                    if(name.equals("cancelled-after-references"))cancelled.set(true);return refs;
                },cancelled::get);
                if(expected!=null||name.equals("cancelled-after-references"))throw new AssertionError("Expected refusal produced a plan.");
                var gson=new GsonBuilder().setPrettyPrinting().create();String wire=gson.toJson(plan.workspace());
                var preview=GoRefactorWorkspaceEdit.prepare(sources,gson.fromJson(wire,WorkspaceEdit.class));
                if(preview.files().size()!=2||plan.compiledPackages()!=1)throw new AssertionError("Expected declaration and caller edits in one compiled package.");
                if(preview.files().stream().noneMatch(f->f.edit().replacement().contains("var _goforgeArg")))throw new AssertionError("Missing original-order temporary capture.");
                plan.requireCurrent();record.put("compiledPackagesBeforeAndAfter",plan.compiledPackages());record.put("wire",gson.fromJson(wire,Object.class));record.put("changedFiles",2);
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
                if(beforeOutput!=null&&!name.endsWith("after-preview")){
                    for(var f:preview.files())Files.writeString(Path.of(java.net.URI.create(f.uri())),f.edit().replacement());
                    String afterOutput=executeFixture(go,dir,env);record.put("afterOutput",afterOutput);
                    if(!beforeOutput.equals(afterOutput))throw new AssertionError("Generated program changed its observable call-order output: "+beforeOutput+" -> "+afterOutput);
                    for(var e:disk.entrySet())Files.writeString(dir.resolve(e.getKey()),e.getValue());
                    record.put("fixtureAppliedExecutedAndRestored",true);
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


    private static String executeFixture(Path go,Path directory,Map<String,String> env)throws Exception{
        var result=dev.goforge.coverage.GoCoverageProcess.run(List.of(go.toString(),"run","."),directory,env,()->false,java.time.Duration.ofSeconds(45));
        if(!Objects.equals(result.exitCode(),0)||result.timedOut()||result.outputTruncated()||!result.knownProcessesStopped())throw new AssertionError("Generated behavior fixture failed: "+result.stderr());return result.stdout();
    }
    private static Path tool(String name)throws Exception {
        String configured=System.getProperty("goforge.probe."+name);
        Path path=configured==null?Path.of(System.getProperty("user.dir"),".tools","bin",HostTools.executableName(name)):Path.of(configured);
        path=path.toRealPath();if(!Files.isRegularFile(path)||!Files.isExecutable(path))throw new IllegalArgumentException("Probe tool is not an executable regular file: "+name);return path;
    }
    private static void write(Path path,Object value)throws Exception{Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(value)+"\n");}
}

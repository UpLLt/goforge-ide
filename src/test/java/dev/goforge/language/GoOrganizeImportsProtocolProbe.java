package dev.goforge.language;

import com.google.gson.GsonBuilder;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in pinned-gopls protocol evidence on generated fixtures; does not claim native Save acceptance. */
public final class GoOrganizeImportsProtocolProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply an empty evidence directory.");
        Path out=Path.of(args[0]).toAbsolutePath();Files.createDirectories(out);
        try(var files=Files.list(out)){if(files.findAny().isPresent())throw new IllegalArgumentException("Evidence directory must be empty.");}
        Path tools=Path.of(System.getProperty("user.dir"),".tools","bin");
        Path fixture=Files.createDirectory(out.resolve("fixture"));
        var env=new HashMap<>(System.getenv());env.keySet().removeIf(k->k.toUpperCase(Locale.ROOT).startsWith("GO"));
        env.put("PATH",tools+File.pathSeparator+env.getOrDefault("PATH",""));
        env.putAll(Map.of("GOENV","off","GOWORK","off","GOPACKAGESDRIVER","off","GOFLAGS","","GOTOOLCHAIN","local","GOPROXY","off","GOSUMDB","off","GOTELEMETRY","off","CGO_ENABLED","0"));
        String initial="package imports\nfunc Message() string { return \"disk before unsaved buffer\" }\n";
        Files.writeString(fixture.resolve("go.mod"),"module example.test/organizeimports\n\ngo 1.26\n");
        Files.writeString(fixture.resolve("imports.go"),initial);
        Files.writeString(fixture.resolve("imports_test.go"),"package imports\nimport \"testing\"\nfunc TestMain(m *testing.M) { panic(\"compile-only proof must not execute tests\") }\n");
        var record=new LinkedHashMap<String,Object>();record.put("passed",false);
        record.put("scope","Actual pinned gopls direct versioned organize-imports responses through production envelope/range validators, followed by fresh-overlay formatting and compile-only verification. No native Save/UI or full parity claim.");
        var checks=new ArrayList<Object>();record.put("checks",checks);
        var executor=Executors.newCachedThreadPool();Process process=null;Future<?> listener=null;PrintWriter trace=null;OutputStream wire=null;
        try {
            trace=new PrintWriter(Files.newBufferedWriter(out.resolve("protocol.log")));
            var builder=new ProcessBuilder(tools.resolve("gopls").toString(),"serve").directory(fixture.toFile()).redirectError(out.resolve("gopls-stderr.log").toFile());
            builder.environment().clear();builder.environment().putAll(env);process=builder.start();
            wire=Files.newOutputStream(out.resolve("server-wire.bin"));final OutputStream recordedWire=wire;
            var input=new FilterInputStream(process.getInputStream()){
                @Override public int read()throws IOException{int value=in.read();if(value>=0)recordedWire.write(value);return value;}
                @Override public int read(byte[] bytes,int offset,int length)throws IOException{int count=in.read(bytes,offset,length);if(count>0)recordedWire.write(bytes,offset,count);return count;}
            };
            AtomicInteger applyRequests=new AtomicInteger();
            var client=new LanguageClient(){
                public void telemetryEvent(Object x){}public void publishDiagnostics(PublishDiagnosticsParams x){}
                public void showMessage(MessageParams x){}public void logMessage(MessageParams x){}
                public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams x){return CompletableFuture.completedFuture(null);}
                public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams x){applyRequests.incrementAndGet();return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false));}
            };
            var launcher=new Launcher.Builder<LanguageServer>().setLocalService(client).setRemoteInterface(LanguageServer.class)
                .setInput(input).setOutput(process.getOutputStream()).setExecutorService(executor).traceMessages(trace).create();
            listener=launcher.startListening();var server=launcher.getRemoteProxy();
            var capabilities=new ClientCapabilities();var textCapabilities=new TextDocumentClientCapabilities();
            var actions=new CodeActionCapabilities();actions.setDataSupport(true);
            actions.setCodeActionLiteralSupport(new CodeActionLiteralSupportCapabilities(new CodeActionKindCapabilities(List.of(CodeActionKind.SourceOrganizeImports))));
            actions.setResolveSupport(new CodeActionResolveSupportCapabilities(List.of("edit")));textCapabilities.setCodeAction(actions);capabilities.setTextDocument(textCapabilities);
            var workspace=new WorkspaceClientCapabilities();workspace.setWorkspaceEdit(new WorkspaceEditCapabilities());workspace.getWorkspaceEdit().setDocumentChanges(true);capabilities.setWorkspace(workspace);
            var initialize=new InitializeParams();initialize.setRootUri(fixture.toUri().toString());initialize.setCapabilities(capabilities);
            var initialized=server.initialize(initialize).get(30,TimeUnit.SECONDS);server.initialized(new InitializedParams());
            record.put("serverInfo",initialized.getServerInfo());String uri=fixture.resolve("imports.go").toUri().toString();
            String source="package imports\nimport \"os\"\n// 世界🙂 survives import edits.\nfunc Message() string {return fmt.Sprintf(\"%s\", \"🙂\")}\n";
            int version=7;server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri,"go",version,source)));
            String formatted=organizeAndFormat(server,uri,source,version,true,checks,"missing-and-unused-imports-unicode");
            if(!formatted.contains("\"fmt\"")||formatted.contains("\"os\"")||!formatted.contains("世界🙂")||!formatted.contains("fmt.Sprintf"))throw new AssertionError("Imports or unrelated Unicode text were incorrect.");
            if(!Files.readString(fixture.resolve("imports.go")).equals(initial))throw new AssertionError("Protocol request changed source on disk.");
            version+=2;
            server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(uri,version),List.of(new TextDocumentContentChangeEvent(formatted))));
            String noOp=organizeAndFormat(server,uri,formatted,version,false,checks,"already-organized-no-op");
            if(!noOp.equals(formatted))throw new AssertionError("No-op import/format request changed source.");
            version+=2;
            String alternate="package imports\nimport \"fmt\"\nfunc Message() string {return strings.TrimSpace(\" result \" )}\n";
            server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(uri,version),List.of(new TextDocumentContentChangeEvent(alternate))));
            String last=organizeAndFormat(server,uri,alternate,version,true,checks,"second-buffer-version-new-standard-import");
            if(!last.contains("\"strings\"")||last.contains("\"fmt\""))throw new AssertionError("Second overlay was not used.");
            if(applyRequests.get()!=0)throw new AssertionError("Unexpected server applyEdit request.");
            Files.writeString(fixture.resolve("imports.go"),last);
            var compile=new ProcessBuilder(tools.resolve("go").toString(),"test","-c","-mod=readonly","-o",out.resolve("proof.test").toString(),".")
                .directory(fixture.toFile()).redirectErrorStream(true).redirectOutput(out.resolve("compile-only.log").toFile());compile.environment().clear();compile.environment().putAll(env);
            Process compiler=compile.start();try{if(!compiler.waitFor(30,TimeUnit.SECONDS))throw new AssertionError("Compile-only validation timed out.");if(compiler.exitValue()!=0)throw new AssertionError("Organized source failed compile-only validation.");}
            finally{if(compiler.isAlive()){compiler.destroyForcibly();if(!compiler.waitFor(5,TimeUnit.SECONDS))throw new AssertionError("Owned compiler remained alive.");}}
            record.put("compileOnlyPassed",true);record.put("projectTestsExecuted",false);record.put("applyRequests",applyRequests.get());
            server.shutdown().get(5,TimeUnit.SECONDS);server.exit();if(!process.waitFor(5,TimeUnit.SECONDS)||process.exitValue()!=0)throw new AssertionError("gopls did not exit cleanly.");
            record.put("goplsExit",0);record.put("passed",true);System.out.println(checks.size()+" actual gopls organize-import protocol cases passed.");
        } catch(Exception|Error e){record.put("failure",e.toString());throw e;}
        finally {
            if(process!=null&&process.isAlive()){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
            if(listener!=null)listener.cancel(true);executor.shutdownNow();if(trace!=null)trace.close();if(wire!=null)wire.close();
            record.put("goplsAliveAfterCleanup",process!=null&&process.isAlive());
            Files.writeString(out.resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(record)+"\n");
        }
    }

    private static String organizeAndFormat(LanguageServer server,String uri,String source,int version,boolean expectChanges,List<Object> checks,String name)throws Exception{
        var context=new CodeActionContext(List.of());context.setOnly(List.of(CodeActionKind.SourceOrganizeImports));
        String[] lines=source.split("\n",-1);var params=new CodeActionParams(new TextDocumentIdentifier(uri),new Range(new Position(0,0),new Position(lines.length-1,lines[lines.length-1].length())),context);
        List<Either<Command,CodeAction>> actions=server.getTextDocumentService().codeAction(params).get(30,TimeUnit.SECONDS);
        var changes=GoSaveImportEdits.prepare(uri,source,version,actions);
        if(changes.isEmpty()==expectChanges)throw new AssertionError("Unexpected import changes in "+name);
        var row=new LinkedHashMap<String,Object>();row.put("case",name);row.put("version",version);row.put("actions",actions);row.put("validatedEdits",changes);row.put("input",source);
        String organized=apply(source,changes);row.put("organized",organized);
        // The ordered didChange is an actual protocol notification. Formatting must observe these
        // edits; this standalone probe does not claim the IDE synchronizer barrier was exercised.
        server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(uri,version+1),List.of(new TextDocumentContentChangeEvent(organized))));
        var edits=server.getTextDocumentService().formatting(new DocumentFormattingParams(new TextDocumentIdentifier(uri),new FormattingOptions(4,false))).get(30,TimeUnit.SECONDS);
        String formatted=apply(organized,GoSaveFormattingEdits.prepare(organized,edits==null?List.of():edits));
        row.put("formatted",formatted);row.put("passed",true);checks.add(row);return formatted;
    }
    private static String apply(String text,List<GoSaveFormattingEdits.Change> changes){var result=new StringBuilder(text);for(int i=changes.size()-1;i>=0;i--){var change=changes.get(i);result.replace(change.start(),change.end(),change.text());}return result.toString();}
}

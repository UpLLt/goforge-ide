package dev.goforge.refactor;

import com.google.gson.GsonBuilder;
import dev.goforge.lsp.GoplsClientFeatures;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Real gopls resolve-to-preview checks; fixture compilation is independent of IDE UI acceptance. */
public final class GoRefactorProtocolProbe {
    record Case(String name, String source, String selection, String kind, String resultMarker, int expected) { }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")); Path tools = root.resolve(".tools/bin");
        Path directory = Files.createTempDirectory("goforge-refactor-");
        Path reportPath = root.resolve(".artifacts/refactoring-protocol.json");
        var report = new LinkedHashMap<String,Object>(); var checks = new ArrayList<Object>();
        report.put("startedAt", Instant.now().toString()); report.put("scope", "Real local gopls through production client features and edit validator; no native UI/full parity claim"); report.put("checks", checks); report.put("passed", false);
        var cases = List.of(
            new Case("extract variable", "package demo\nfunc Sum(a int) int {\n return (a + 1) * 2\n}\n", "a + 1", "refactor.extract.variable", "newVar", 8),
            new Case("extract function", "package demo\nfunc Sum(a int) int {\n v := a + 1\n v *= 2\n return v\n}\n", "v := a + 1\n v *= 2", "refactor.extract.function", "newFunction", 8),
            new Case("extract constant", "package demo\nfunc Sum(a int) int {\n return a + 42\n}\n", "42", "refactor.extract.constant", "const", 45),
            new Case("inline function call", "package demo\nfunc twice(a int) int { return a * 2 }\nfunc Sum(a int) int {\n return twice(a)\n}\n", "twice(a)", "refactor.inline.call", "a * 2", 6)
        );
        try {
            Files.writeString(directory.resolve("go.mod"), "module example.test/refactor\n\ngo 1.26\n");
            for (var item : cases) {
                Path source = directory.resolve("sample.go"); Files.writeString(source,item.source());
                Files.writeString(directory.resolve("sample_test.go"), "package demo\nimport \"testing\"\nfunc TestResult(t *testing.T){ if got:=Sum(3);got!=" + item.expected() + " { t.Fatalf(\"got %d\",got) } }\n");
                String changed = run(tools, directory, source, item);
                if (!Files.readString(source).equals(item.source())) throw new AssertionError("Preview changed the real file");
                Files.writeString(source, changed);
                var command = new ProcessBuilder(tools.resolve("go").toString(), "test", "-count=1", "./...").directory(directory.toFile()).redirectErrorStream(true);
                command.environment().put("PATH",tools+java.io.File.pathSeparator+System.getenv().getOrDefault("PATH",""));
                var process=command.start(); if (!process.waitFor(40,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("go test timeout"); }
                String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                if(process.exitValue()!=0)throw new AssertionError(output);
                checks.add(Map.of("case",item.name(),"kind",item.kind(),"previewDidNotWrite",true,"resolvedAndValidated",true,"goTestExit",process.exitValue()));
            }
            // Unsaved contents must be the server's input, and resolving must remain read-only.
            var overlay = new Case("unsaved overlay", "package demo\nfunc Sum(a int) int {\n return (a + 2) * 2\n}\n", "a + 2", "refactor.extract.variable", "a + 2", 10);
            Path source=directory.resolve("sample.go");String disk="package demo\nfunc Sum(a int) int { return 0 }\n";Files.writeString(source,disk);
            String changed=run(tools,directory,source,overlay);if(!changed.contains("a + 2")||!Files.readString(source).equals(disk))throw new AssertionError("Overlay lost or preview saved");
            checks.add(Map.of("case","unsaved overlay","resolvedOverlay",true,"diskRetained",true));report.put("passed",true);
        } catch(Throwable error){report.put("failure",error.toString());throw error;}
        finally {
            try(var paths=Files.walk(directory)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
            report.put("fixtureDeleted",true);report.put("completedAt",Instant.now().toString());Files.createDirectories(reportPath.getParent());Files.writeString(reportPath,new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println(reportPath);
        }
    }
    private static String run(Path tools,Path directory,Path source,Case item)throws Exception {
        var command=new ProcessBuilder(tools.resolve("gopls").toString(),"serve").directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        command.environment().put("PATH",tools+java.io.File.pathSeparator+System.getenv().getOrDefault("PATH",""));var process=command.start();var executor=Executors.newCachedThreadPool();Future<?> listening=null;
        try {
            var client=new LanguageClient(){
                public void telemetryEvent(Object o){} public void publishDiagnostics(PublishDiagnosticsParams p){} public void showMessage(MessageParams p){} public void logMessage(MessageParams p){}
                public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams p){return CompletableFuture.completedFuture(null);}
                public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams p){throw new AssertionError("Resolving a preview must not call applyEdit");}
            };
            var features=new GoplsClientFeatures();var params=new InitializeParams();params.setRootUri(directory.toUri().toString());params.setCapabilities(new ClientCapabilities());
            var workspace=new WorkspaceClientCapabilities();workspace.setApplyEdit(true);var editCaps=new WorkspaceEditCapabilities();editCaps.setDocumentChanges(true);workspace.setWorkspaceEdit(editCaps);params.getCapabilities().setWorkspace(workspace);features.initializeParams(params);
            var launcher=features.<LanguageServer>createLauncherBuilder().setInput(process.getInputStream()).setOutput(process.getOutputStream()).setRemoteInterface(LanguageServer.class).setLocalService(client).setExecutorService(executor).create();listening=launcher.startListening();var server=launcher.getRemoteProxy();
            server.initialize(params).get(30,TimeUnit.SECONDS);server.initialized(new InitializedParams());String uri=source.toUri().toString();server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri,"go",7,item.source())));
            int from=item.source().indexOf(item.selection());if(from<0)throw new AssertionError("bad fixture selection");
            var context=new CodeActionContext(List.of());context.setOnly(List.of(item.kind()));
            var actions=server.getTextDocumentService().codeAction(new CodeActionParams(new TextDocumentIdentifier(uri),new Range(GoRefactorEdit.position(item.source(),from),GoRefactorEdit.position(item.source(),from+item.selection().length())),context)).get(30,TimeUnit.SECONDS);
            var selected=actions.stream().filter(x->x.isRight()&&item.kind().equals(x.getRight().getKind())).map(x->x.getRight()).findFirst().orElseThrow(()->new AssertionError("No "+item.kind()+" action: "+actions));
            var resolved=selected.getEdit()==null?server.getTextDocumentService().resolveCodeAction(selected).get(30,TimeUnit.SECONDS):selected;
            if(resolved.getCommand()!=null)throw new AssertionError("Unexpected command");
            var plan=GoRefactorEdit.prepare(uri,item.source(),features.getSentDocumentVersion(uri),resolved.getEdit());
            if(!plan.replacement().contains(item.resultMarker()))throw new AssertionError("Expected transformed source: "+plan.replacement());
            server.shutdown().get(5,TimeUnit.SECONDS);server.exit();if(!process.waitFor(5,TimeUnit.SECONDS)||process.exitValue()!=0)throw new AssertionError("gopls did not exit cleanly");return plan.replacement();
        } finally {if(process.isAlive()){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly().waitFor(2,TimeUnit.SECONDS);}if(listening!=null)listening.cancel(true);executor.shutdownNow();}
    }
}

package dev.goforge.refactor;

import com.google.gson.JsonParser;
import dev.goforge.lsp.GoplsClientFeatures;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.*;
import org.eclipse.lsp4j.jsonrpc.messages.*;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public final class GoSignatureEntryEndpointTest {
    private static final String URI="file:///fixture.go";
    private static Either3<Range,PrepareRenameResult,PrepareRenameDefaultBehavior> prepared(String title){return Either3.forSecond(new PrepareRenameResult(new Range(new Position(0,0),new Position(0,4)),title));}
    private record Wire(Launcher<LanguageServer> launcher,ByteArrayOutputStream output) {
        void answer(Object value){String text=output.toString(StandardCharsets.UTF_8);var frame=JsonParser.parseString(text.substring(text.indexOf("\r\n\r\n")+4)).getAsJsonObject();var response=new ResponseMessage();response.setId(frame.get("id").getAsString());response.setResult(value);launcher.getRemoteEndpoint().consume(response);}
    }
    private static Wire wire(){var output=new ByteArrayOutputStream();var launcher=new GoplsClientFeatures().<LanguageServer>createLauncherBuilder().setInput(new ByteArrayInputStream(new byte[0])).setOutput(output).setLocalService(new Object()).setRemoteInterface(LanguageServer.class).create();return new Wire(launcher,output);}
    @Test public void defaultPrepareRenameFuncTemplateIsNotExposed(){var wire=wire();var future=wire.launcher().getRemoteProxy().getTextDocumentService().prepareRename(new PrepareRenameParams(new TextDocumentIdentifier(URI),new Position()));wire.answer(prepared("func(a int, b string)"));assertNull(future.join());}
    @Test public void ordinaryPrepareRenameResponseIsPreserved(){var wire=wire();var future=wire.launcher().getRemoteProxy().getTextDocumentService().prepareRename(new PrepareRenameParams(new TextDocumentIdentifier(URI),new Position()));wire.answer(prepared("functional"));assertEquals("functional",future.join().getSecond().getPlaceholder());}
    @Test public void markedPrepareAndRenameStillUseTheActualWire(){
        var prepare=wire();var future=prepare.launcher().getRemoteProxy().getTextDocumentService().prepareRename(new GoChangeSignature.PrepareRequest(new TextDocumentIdentifier(URI),new Position()));prepare.answer(prepared("func(a int, b string)"));assertEquals("func(a int, b string)",future.join().getSecond().getPlaceholder());
        var rename=wire();var model=new GoChangeSignature(URI,new Range(new Position(),new Position(0,4)),"func(a int, b string)");var renamed=rename.launcher().getRemoteProxy().getTextDocumentService().rename(model.request("func(b string, a int)"));assertTrue(rename.output().toString(StandardCharsets.UTF_8).contains("textDocument/rename"));rename.answer(new WorkspaceEdit(Map.of()));assertNotNull(renamed.join());
    }
    @Test public void directSignatureRenameAndCommandsNeverReachWire(){
        for(String name:List.of("func(a int)","func /* comment */ (a int)"," func\n(a int)")){
            var wire=wire();var future=wire.launcher().getRemoteProxy().getTextDocumentService().rename(new RenameParams(new TextDocumentIdentifier(URI),new Position(),name));assertThrows(CompletionException.class,future::join);assertEquals(0,wire.output().size());
        }
        for(var command:List.of(new ExecuteCommandParams("gopls.change_signature",List.of()),new ExecuteCommandParams("gopls.apply_fix",List.of(Map.of("Fix","unusedparams"))))) {
            var wire=wire();var future=wire.launcher().getRemoteProxy().getWorkspaceService().executeCommand(command);assertThrows(CompletionException.class,future::join);assertEquals(0,wire.output().size());
        }
    }
    @Test public void normalRenameAndOtherFixesPassThrough(){
        var wire=wire();var rename=wire.launcher().getRemoteProxy().getTextDocumentService().rename(new RenameParams(new TextDocumentIdentifier(URI),new Position(),"functional"));assertTrue(wire.output().size()>0);wire.answer(new WorkspaceEdit(Map.of()));assertNotNull(rename.join());
        var command=wire();var done=command.launcher().getRemoteProxy().getWorkspaceService().executeCommand(new ExecuteCommandParams("gopls.apply_fix",List.of(Map.of("Fix","fillreturns"))));assertTrue(command.output().size()>0);command.answer(null);assertNull(done.join());
    }
    @Test public void prepareCancellationAndFailureArePreserved(){
        var pending=new CompletableFuture<Object>();Endpoint delegate=new Endpoint(){public CompletableFuture<?> request(String method,Object params){return pending;}public void notify(String method,Object params){}};
        var result=new GoSignatureEntryEndpoint(delegate).request("textDocument/prepareRename",new PrepareRenameParams());result.cancel(true);assertTrue(pending.isCancelled());
        var error=new IllegalStateException("transport failed");Endpoint failure=new Endpoint(){public CompletableFuture<?> request(String method,Object params){return CompletableFuture.failedFuture(error);}public void notify(String method,Object params){}};
        var failed=new GoSignatureEntryEndpoint(failure).request("textDocument/prepareRename",new PrepareRenameParams());assertSame(error,assertThrows(CompletionException.class,failed::join).getCause());
    }
}

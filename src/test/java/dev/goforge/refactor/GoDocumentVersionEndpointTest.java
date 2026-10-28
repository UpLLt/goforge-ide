package dev.goforge.refactor;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.Assert.*;

public class GoDocumentVersionEndpointTest {
    @Test public void tracksOnlySuccessfullyForwardedVersionsAndCloses() {
        var versions = new HashMap<String,Integer>();
        var seen = new ArrayList<String>();
        var endpoint = new GoDocumentVersionEndpoint(new Endpoint() {
            public CompletableFuture<?> request(String method,Object value) { return CompletableFuture.completedFuture(value); }
            public void notify(String method,Object value) { seen.add(method); }
        }, versions);
        endpoint.notify("textDocument/didOpen", new DidOpenTextDocumentParams(new TextDocumentItem("file:///test.go", "go", 1, "package p")));
        assertEquals(Integer.valueOf(1), versions.get("file:///test.go"));
        endpoint.notify("textDocument/didChange", new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier("file:///test.go", 8), List.of(new TextDocumentContentChangeEvent("package q"))));
        assertEquals(Integer.valueOf(8), versions.get("file:///test.go"));
        endpoint.notify("textDocument/didClose",new DidCloseTextDocumentParams(new TextDocumentIdentifier("file:///test.go")));
        assertTrue(versions.isEmpty()); assertEquals(3, seen.size());
    }
    @Test public void forwardingFailureDoesNotAdvanceVersion() {
        var versions = new HashMap<String,Integer>();
        var endpoint = new GoDocumentVersionEndpoint(new Endpoint() {
            public CompletableFuture<?> request(String method,Object value) { return CompletableFuture.failedFuture(new IllegalStateException()); }
            public void notify(String method,Object value) { throw new IllegalStateException("disconnected"); }
        }, versions);
        assertThrows(IllegalStateException.class, () -> endpoint.notify("textDocument/didOpen", new DidOpenTextDocumentParams(new TextDocumentItem("file:///test.go", "go", 1, ""))));
        assertTrue(versions.isEmpty());
    }
    @Test public void advertisesSideEffectFreeResolve() {
        var params = new InitializeParams(); params.setCapabilities(new ClientCapabilities());
        new dev.goforge.lsp.GoplsClientFeatures().initializeParams(params);
        assertEquals(Boolean.TRUE, params.getCapabilities().getTextDocument().getCodeAction().getDataSupport());
        assertEquals(List.of("edit"),params.getCapabilities().getTextDocument().getCodeAction().getResolveSupport().getProperties());
    }
}

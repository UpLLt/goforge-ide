package dev.goforge.refactor;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Records versions only after the corresponding outgoing notification was accepted. */
public final class GoDocumentVersionEndpoint implements Endpoint {
    private final Endpoint delegate;
    private final Map<String, Integer> versions;
    public GoDocumentVersionEndpoint(Endpoint delegate, Map<String, Integer> versions) { this.delegate = delegate; this.versions = versions; }
    @Override public CompletableFuture<?> request(String method, Object params) { return delegate.request(method, params); }
    @Override public void notify(String method, Object params) {
        delegate.notify(method, params);
        if ("textDocument/didOpen".equals(method) && params instanceof DidOpenTextDocumentParams open)
            versions.put(open.getTextDocument().getUri(), open.getTextDocument().getVersion());
        else if ("textDocument/didChange".equals(method) && params instanceof DidChangeTextDocumentParams change)
            versions.put(change.getTextDocument().getUri(), change.getTextDocument().getVersion());
        else if ("textDocument/didClose".equals(method) && params instanceof DidCloseTextDocumentParams close)
            versions.remove(close.getTextDocument().getUri());
    }
}

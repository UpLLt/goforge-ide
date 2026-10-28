package dev.goforge.refactor;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import java.util.concurrent.*;

/** Prevents unpreviewed gopls signature entry points; ordinary rename and other commands pass through. */
public final class GoSignatureEntryEndpoint implements Endpoint {
    private final Endpoint delegate;
    public GoSignatureEntryEndpoint(Endpoint delegate){this.delegate=delegate;}
    @Override public CompletableFuture<?> request(String method,Object params) {
        if("textDocument/rename".equals(method) && params instanceof RenameParams rename
                && !(params instanceof GoChangeSignature.RenameRequest) && signatureText(rename.getNewName()))
            return CompletableFuture.failedFuture(new IllegalStateException("Use Go Refactor on the func keyword to preview and validate a signature change."));
        if("workspace/executeCommand".equals(method) && params instanceof ExecuteCommandParams command
                && GoSignatureCodeActionFeature.guarded(new Command("",command.getCommand(),command.getArguments())))
            return CompletableFuture.failedFuture(new IllegalStateException("Use Go Refactor to validate signature changes before applying them."));
        var source=delegate.request(method,params);
        if(!"textDocument/prepareRename".equals(method) || params instanceof GoChangeSignature.PrepareRequest)return source;
        var result=new CompletableFuture<Object>();result.whenComplete((value,error)->{if(result.isCancelled())source.cancel(true);});
        source.whenComplete((value,error)->{
            if(error!=null){if(source.isCancelled())result.cancel(false);else result.completeExceptionally(error);return;}
            boolean signature=value instanceof Either3<?,?,?> either && either.isSecond()
                    && either.getSecond() instanceof PrepareRenameResult prepared && signatureText(prepared.getPlaceholder());
            result.complete(signature?null:value);
        });
        return result;
    }
    @Override public void notify(String method,Object params){delegate.notify(method,params);}
    private static boolean signatureText(String text) {
        if(text==null)return false;String value=text.stripLeading();
        return value.startsWith("func") && (value.length()==4 || !Character.isUnicodeIdentifierPart(value.codePointAt(4)));
    }
}

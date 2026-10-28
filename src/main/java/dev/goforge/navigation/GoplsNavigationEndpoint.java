package dev.goforge.navigation;

import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/**
 * Adapts one gopls-specific "not applicable" implementation response.
 *
 * gopls 0.23.0's implementsObj rejects ordinary function identifiers: they do
 * not have method-set implementations. LSP4IJ also asks for implementations as
 * part of Find Usages. Represent just that confirmed case as an empty result;
 * references, interfaces, methods and function-signature queries remain intact.
 * This endpoint is installed only on GoForge's gopls connection.
 */
public final class GoplsNavigationEndpoint implements Endpoint {
    private static final String IMPLEMENTATION = "textDocument/implementation";
    private static final Pattern ORDINARY_FUNCTION = Pattern.compile(
            "[\\p{L}_][\\p{L}\\p{Nd}_]* is a function, not a method "
                    + "\\(query at 'func' token to find matching signatures\\)");

    private final Endpoint delegate;

    public GoplsNavigationEndpoint(Endpoint delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
    }

    @Override public CompletableFuture<?> request(String method, Object parameter) {
        CompletableFuture<?> source = delegate.request(method, parameter);
        if (!IMPLEMENTATION.equals(method)) return source;

        CompletableFuture<Object> result = new CompletableFuture<>();
        result.whenComplete((ignored, error) -> {
            if (result.isCancelled()) source.cancel(true);
        });
        source.whenComplete((value, error) -> {
            if (error == null) {
                result.complete(value);
            } else if (isOrdinaryFunctionResponse(error)) {
                result.complete(Either.forLeft(List.of()));
            } else if (unwrap(error) instanceof CancellationException) {
                result.cancel(false);
            } else {
                // Retain all protocol/transport errors, including other code=0
                // failures, unknown objects, bad positions and future messages.
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    @Override public void notify(String method, Object parameter) {
        delegate.notify(method, parameter);
    }

    private static boolean isOrdinaryFunctionResponse(Throwable error) {
        if (!(unwrap(error) instanceof ResponseErrorException response)) return false;
        var detail = response.getResponseError();
        return detail != null && detail.getCode() == 0 && detail.getData() == null
                && detail.getMessage() != null && ORDINARY_FUNCTION.matcher(detail.getMessage()).matches();
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }
}

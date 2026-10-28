package dev.goforge.navigation;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.Assert.*;

public final class GoplsNavigationEndpointTest {
    private static final String METHOD = "textDocument/implementation";
    private static final String SUFFIX = " is a function, not a method (query at 'func' token to find matching signatures)";

    @Test public void ordinaryFunctionIsAnEmptyImplementationResult() {
        for (String name : List.of("Hello", "世界_2", "_helper")) {
            var source = CompletableFuture.failedFuture(error(0, name + SUFFIX, null));
            Object value = new GoplsNavigationEndpoint(endpoint(source)).request(METHOD, null).join();
            assertTrue(value instanceof Either<?, ?>);
            assertEquals(List.of(), ((Either<?, ?>) value).getLeft());
        }
    }

    @Test public void successfulInterfaceMethodAndSignatureLocationsAreUnchanged() {
        var locations = Either.forLeft(List.of(new Location("file:///test.go", new Range())));
        for (Object value : new Object[]{locations, Either.forRight(List.of()), null}) {
            assertSame(value, new GoplsNavigationEndpoint(endpoint(CompletableFuture.completedFuture(value)))
                    .request(METHOD, null).join());
        }
    }

    @Test public void unrecognizedErrorsRemainFailures() {
        for (Throwable error : List.of(
                error(-32603, "Hello" + SUFFIX, null),
                error(0, "Hello" + SUFFIX, "future response data"),
                error(0, "f is a var, not a type", null),
                error(0, "Hello denotes unknown object", null),
                error(0, "start: pos 0 is not in range", null),
                error(0, "Hello is a function, not a method", null),
                error(0, "Hello" + SUFFIX + "\nextra error", null),
                error(0, "invalid name" + SUFFIX, null),
                new IOException("gopls disconnected"))) {
            var result = new GoplsNavigationEndpoint(endpoint(CompletableFuture.failedFuture(error)))
                    .request(METHOD, null);
            try {
                result.join();
                fail("Expected original failure: " + error);
            } catch (CompletionException actual) {
                assertSame(error, actual.getCause());
            }
        }
    }

    @Test public void otherRequestMethodsBypassAdaptation() {
        var source = CompletableFuture.failedFuture(error(0, "Hello" + SUFFIX, null));
        for (String method : List.of("textDocument/references", "textDocument/definition", "initialize")) {
            assertSame(source, new GoplsNavigationEndpoint(endpoint(source)).request(method, null));
        }
    }

    @Test public void completionWrapperDoesNotPreventKnownResultAdaptation() {
        var source = CompletableFuture.failedFuture(new CompletionException(error(0, "Hello" + SUFFIX, null)));
        assertEquals(List.of(), ((Either<?, ?>) new GoplsNavigationEndpoint(endpoint(source))
                .request(METHOD, null).join()).getLeft());
    }

    @Test public void cancellingConsumerCancelsInFlightProtocolRequest() {
        var source = new CompletableFuture<>();
        var result = new GoplsNavigationEndpoint(endpoint(source)).request(METHOD, null);
        assertTrue(result.cancel(true));
        assertTrue(source.isCancelled());
        assertFalse(source.complete(List.of()));
    }

    @Test public void serverSideCancellationRemainsCancelled() {
        var source = new CompletableFuture<>();
        var result = new GoplsNavigationEndpoint(endpoint(source)).request(METHOD, null);
        source.cancel(false);
        assertTrue(result.isCancelled());
    }

    @Test public void methodParametersAndNotificationsAreForwardedExactly() {
        Object params = new Object();
        var sent = new java.util.ArrayList<Object>();
        var endpoint = new GoplsNavigationEndpoint(new Endpoint() {
            @Override public CompletableFuture<?> request(String method, Object parameter) {
                sent.add(method); sent.add(parameter);
                return CompletableFuture.completedFuture(null);
            }
            @Override public void notify(String method, Object parameter) {
                sent.add(method); sent.add(parameter);
            }
        });
        endpoint.request(METHOD, params).join();
        endpoint.notify("textDocument/didChange", params);
        assertEquals(List.of(METHOD, params, "textDocument/didChange", params), sent);
    }

    private static ResponseErrorException error(int code, String message, Object data) {
        return new ResponseErrorException(new ResponseError(code, message, data));
    }

    private static Endpoint endpoint(CompletableFuture<?> future) {
        return new Endpoint() {
            @Override public CompletableFuture<?> request(String method, Object parameter) { return future; }
            @Override public void notify(String method, Object parameter) { }
        };
    }
}

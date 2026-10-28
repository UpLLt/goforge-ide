package dev.goforge.refactor;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;

public class GoRefactorFileCreationEndpointTest {
    @Test public void filtersOnlySuppliedFilesWithoutMutatingTheOriginalBatch() {
        var seen = new ArrayList<Object>();
        var endpoint = new GoRefactorFileCreationEndpoint(delegate(seen), "file:///generated.go"::equals);
        var params = new CreateFilesParams(List.of(new FileCreate("file:///generated.go"), new FileCreate("file:///user.go")));
        endpoint.notify("workspace/didCreateFiles", params);
        assertEquals(2, params.getFiles().size());
        assertEquals(List.of(new FileCreate("file:///user.go")), ((CreateFilesParams) seen.getFirst()).getFiles());
        endpoint.notify("workspace/didCreateFiles", new CreateFilesParams(List.of(new FileCreate("file:///generated.go"))));
        assertEquals(1, seen.size());
    }

    @Test public void keepsWatcherNotificationsOtherMethodsAndRequestCancellation() {
        var seen = new ArrayList<Object>();
        var endpoint = new GoRefactorFileCreationEndpoint(delegate(seen), ignored -> true);
        var changes = new DidChangeWatchedFilesParams(List.of(new FileEvent("file:///generated.go", FileChangeType.Created)));
        endpoint.notify("workspace/didChangeWatchedFiles", changes);
        assertSame(changes, seen.getFirst());
        var request = new CompletableFuture<>();
        assertSame(request, endpoint.request("workspace/willCreateFiles", request));
        endpoint.request("workspace/willCreateFiles", request).cancel(true);
        assertTrue(request.isCancelled());
    }

    private static Endpoint delegate(List<Object> seen) {
        return new Endpoint() {
            @Override public CompletableFuture<?> request(String method, Object params) { return (CompletableFuture<?>) params; }
            @Override public void notify(String method, Object params) { seen.add(params); }
        };
    }
}

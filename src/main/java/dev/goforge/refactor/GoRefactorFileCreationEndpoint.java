package dev.goforge.refactor;

import org.eclipse.lsp4j.CreateFilesParams;
import org.eclipse.lsp4j.jsonrpc.Endpoint;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/** Retains ordinary creation and all watcher notifications, filtering only supplied Go files. */
public final class GoRefactorFileCreationEndpoint implements Endpoint {
    private final Endpoint delegate;
    private final Predicate<String> suppliedContents;

    public GoRefactorFileCreationEndpoint(Endpoint delegate, Predicate<String> suppliedContents) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
        this.suppliedContents = java.util.Objects.requireNonNull(suppliedContents);
    }

    @Override public CompletableFuture<?> request(String method, Object params) {
        return delegate.request(method, params);
    }

    @Override public void notify(String method, Object params) {
        if ("workspace/didCreateFiles".equals(method) && params instanceof CreateFilesParams creates
                && creates.getFiles() != null) {
            var ordinary = creates.getFiles().stream()
                    .filter(file -> file == null || !suppliedContents.test(file.getUri())).toList();
            if (ordinary.size() != creates.getFiles().size()) {
                if (!ordinary.isEmpty()) delegate.notify(method, new CreateFilesParams(ordinary));
                return;
            }
        }
        delegate.notify(method, params);
    }
}

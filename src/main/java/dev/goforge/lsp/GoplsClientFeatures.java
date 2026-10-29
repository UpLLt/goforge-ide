package dev.goforge.lsp;

import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;
import com.redhat.devtools.lsp4ij.server.DefaultLauncherBuilder;
import dev.goforge.navigation.GoplsNavigationEndpoint;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

/** Go-specific protocol adaptation through LSP4IJ's public launcher hook. */
public final class GoplsClientFeatures extends LSPClientFeatures {
    public GoplsClientFeatures(){setCodeActionFeature(new dev.goforge.refactor.GoSignatureCodeActionFeature());}
    private final java.util.concurrent.ConcurrentMap<String, Integer> sentVersions = new java.util.concurrent.ConcurrentHashMap<>();
    private dev.goforge.refactor.GoRefactorFileCreationGuard fileCreations;
    public Integer getSentDocumentVersion(String uri) { return sentVersions.get(uri); }

    @Override public void initializeParams(org.eclipse.lsp4j.InitializeParams params) {
        super.initializeParams(params);
        var capabilities = params.getCapabilities();
        if (capabilities == null) {
            capabilities = new org.eclipse.lsp4j.ClientCapabilities();
            params.setCapabilities(capabilities);
        }
        var text = capabilities.getTextDocument();
        if (text == null) {
            text = new org.eclipse.lsp4j.TextDocumentClientCapabilities();
            capabilities.setTextDocument(text);
        }
        var actions = text.getCodeAction();
        if (actions == null) {
            actions = new org.eclipse.lsp4j.CodeActionCapabilities();
            text.setCodeAction(actions);
        }
        // gopls requires both fields to return side-effect-free, resolvable edits.
        actions.setDataSupport(true);
        actions.setResolveSupport(new org.eclipse.lsp4j.CodeActionResolveSupportCapabilities(java.util.List.of("edit")));
    }

    @Override public <S extends LanguageServer> @NotNull Launcher.Builder<S> createLauncherBuilder() {
        return new DefaultLauncherBuilder<S>(this) {
            @Override @SuppressWarnings("unchecked") protected S createProxy(RemoteEndpoint endpoint) {
                sentVersions.clear();
                org.eclipse.lsp4j.jsonrpc.Endpoint creations = endpoint;
                // Standalone launchers have no IDE wrapper/VFS lifecycle.
                if (getServerWrapper() != null) {
                    if (fileCreations == null)
                        fileCreations = new dev.goforge.refactor.GoRefactorFileCreationGuard(getServerWrapper());
                    creations = new dev.goforge.refactor.GoRefactorFileCreationEndpoint(endpoint, fileCreations.beginConnection());
                }
                var navigation = new GoplsNavigationEndpoint(new dev.goforge.refactor.GoSignatureEntryEndpoint(new dev.goforge.refactor.GoDocumentVersionEndpoint(
                        creations, sentVersions)));
                org.eclipse.lsp4j.jsonrpc.Endpoint formatting = getServerWrapper() == null ? navigation
                        : new GoFormattingCaretIndentEndpoint(navigation, getProject(), GoplsClientFeatures.this, sentVersions);
                // Preserve LSP4J's default classloader selection for the usual
                // single-interface launcher (its explicit classLoader is null).
                if (localServices.size() == 1 && remoteInterfaces.size() == 1) {
                    return ServiceEndpoints.toServiceObject(formatting, remoteInterfaces.iterator().next());
                }
                return (S) ServiceEndpoints.toServiceObject(formatting,
                        new ArrayList<Class<?>>(remoteInterfaces), classLoader);
            }
        };
    }
}

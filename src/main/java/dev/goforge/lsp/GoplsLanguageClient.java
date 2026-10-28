package dev.goforge.lsp;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import com.redhat.devtools.lsp4ij.client.features.FileUriSupport;
import com.redhat.devtools.lsp4ij.features.diagnostics.LSPDiagnosticsApplier;
import dev.goforge.settings.GoForgeSettings;
import dev.goforge.settings.GoProjectSettings;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class GoplsLanguageClient extends LanguageClientImpl {
    public GoplsLanguageClient(@NotNull Project project) { super(project); }

    @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        if (isDisposed()) return;
        // Keep LSP4IJ's version checks and canonical diagnostics cache unchanged.
        super.publishDiagnostics(diagnostics);
        if (isDisposed()) return; // Includes project disposal while the notification was handled.
        var features = getClientFeatures();
        var file = FileUriSupport.findFileByUri(diagnostics.getUri(), features);
        if (file == null || !file.isValid()) return;
        var uri = FileUriSupport.getFileUri(file, features);
        var opened = features.getServerWrapper().getOpenedDocument(uri);
        if (opened == null || !opened.getDiagnostics().isEmpty()) return;
        var synchronizer = opened.getSynchronizer();
        if (synchronizer == null) return;
        // A previous empty-cache refresh may have lost its document-stamp race.
        // Retry that clear even when an identical publication was deduplicated. Only
        // the accepted empty cache triggers compensation, never notification data.
        // Nonempty diagnostics retain upstream behavior to avoid redrawing old ranges
        // after another edit. The applier still coalesces work and checks the stamp.
        LSPDiagnosticsApplier.getInstance(getProject()).scheduleRefresh(
                file, synchronizer.getDocument());
    }

    @Override protected Object createSettings() {
        return new com.google.gson.Gson().toJsonTree(Map.of("gopls", Map.of(
            "staticcheck", GoForgeSettings.getInstance().staticcheck,
            "buildFlags", GoProjectSettings.getInstance(getProject()).buildFlags(),
            "env", GoProjectSettings.getInstance(getProject()).environment(),
            "semanticTokens", true,
            "hints", Map.of("assignVariableTypes", true, "compositeLiteralFields", true,
                "compositeLiteralTypes", true, "constantValues", true, "functionTypeParameters", true,
                "parameterNames", true, "rangeVariableTypes", true))));
    }
}

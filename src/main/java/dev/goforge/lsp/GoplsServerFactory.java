package dev.goforge.lsp;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.LanguageServerFactory;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import dev.goforge.settings.GoForgeSettings;
import dev.goforge.settings.GoProjectSettings;
import dev.goforge.tools.GoToolResolver;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public final class GoplsServerFactory implements LanguageServerFactory {
    @Override public @NotNull LSPClientFeatures createClientFeatures() {
        return new GoplsClientFeatures();
    }

    @Override public @NotNull StreamConnectionProvider createConnectionProvider(@NotNull Project project) {
        var remote = dev.goforge.remote.GoRemoteAnalysisService.getInstance(project).connectionProvider();
        if (remote != null) return remote;
        var settings = GoForgeSettings.getInstance();
        var command = new GeneralCommandLine().withEnvironment(GoProjectSettings.getInstance(project).environment())
                .withCharset(java.nio.charset.StandardCharsets.UTF_8);
        var environment = command.getEffectiveEnvironment();
        String gopls = GoToolResolver.resolve(settings.goplsPath, "gopls", project.getBasePath(), environment);
        String go = GoToolResolver.resolve(settings.goPath, "go", project.getBasePath(), environment);
        command.withExePath(gopls).withParameters("serve");
        if (project.getBasePath() != null) command.withWorkDirectory(project.getBasePath());
        command.withEnvironment(dev.goforge.tools.HostTools.withPrependedPath(command.getEffectiveEnvironment(), Path.of(go).getParent()));
        return new OSProcessStreamConnectionProvider(command);
    }

    @Override public @NotNull LanguageClientImpl createLanguageClient(@NotNull Project project) {
        GoClosedDocumentSynchronizer.getInstance(project);
        return new GoplsLanguageClient(project);
    }
}

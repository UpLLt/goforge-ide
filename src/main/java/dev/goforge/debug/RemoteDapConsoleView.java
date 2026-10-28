package dev.goforge.debug;

import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.search.GlobalSearchScope;
import com.redhat.devtools.lsp4ij.dap.console.DAPConsoleView;
import org.jetbrains.annotations.NotNull;

/** Console for the SSH bridge, whose stdout is exclusively the DAP transport. */
final class RemoteDapConsoleView extends DAPConsoleView {
    RemoteDapConsoleView(Project project, GlobalSearchScope scope, boolean viewer, boolean filters) {
        super(project, scope, viewer, filters);
    }

    @Override public void print(@NotNull String text, @NotNull ConsoleViewContentType type) {
        // DAPProcessListener independently consumes every process-stdout fragment. Here NORMAL_OUTPUT
        // is that transport copy, never target output: DAPClient.output uses LOG_INFO/LOG_ERROR/etc.
        // Classify by stream ownership, not JSON syntax (a target can print JSON or unmatched braces).
        if (type != ConsoleViewContentType.NORMAL_OUTPUT) super.print(text, type);
    }
}

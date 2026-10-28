package dev.goforge.debug;

import com.intellij.lang.Language;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess;
import com.redhat.devtools.lsp4ij.dap.DAPDebuggerEditorsProvider;
import com.redhat.devtools.lsp4ij.dap.evaluation.DAPExpressionCodeFragment;
import dev.goforge.language.GoFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Keeps DAP expression documents available while edits are awaiting PSI commit. */
public final class GoDebuggerEditorsProvider extends DAPDebuggerEditorsProvider {
    public GoDebuggerEditorsProvider(@Nullable DAPDebugProcess debugProcess) {
        super(GoFileType.INSTANCE, debugProcess);
    }

    @Override protected @NotNull PsiFile createExpressionCodeFragment(@NotNull Project project,
            @NotNull String text, @NotNull FileType fileType, @NotNull Language language) {
        return new GoDebugExpressionFragment(project, text, getDebugProcess());
    }
}

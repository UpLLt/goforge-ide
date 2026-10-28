package dev.goforge.debug;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess;
import com.redhat.devtools.lsp4ij.dap.evaluation.DAPExpressionCodeFragment;
import dev.goforge.language.GoFileType;
import dev.goforge.language.GoLanguage;

import java.util.function.Supplier;

/** A GoForge-owned DAP fragment; ordinary Go files never use runtime completion. */
final class GoDebugExpressionFragment extends DAPExpressionCodeFragment {
    private final Supplier<GoDebugCompletionEngine.Context> completionContext;

    GoDebugExpressionFragment(Project project, String text, DAPDebugProcess process) {
        super(text, GoFileType.INSTANCE, GoLanguage.INSTANCE, process, project);
        completionContext = () -> {
            if (process == null || project.isDisposed() || process.getSession().isStopped()
                    || !process.getSession().isSuspended()) return null;
            var frame = process.getCurrentDapStackFrame();
            var suspension = process.getSession().getSuspendContext();
            if (frame == null || frame.getClient().getDebugProtocolServer() == null) return null;
            return new GoDebugCompletionEngine.Context(frame.getClient().getDebugProtocolServer(), frame.getFrameId(),
                    () -> !project.isDisposed() && !process.getSession().isStopped() && process.getSession().isSuspended()
                            && process.getSession().getSuspendContext() == suspension
                            && process.getCurrentDapStackFrame() == frame);
        };
    }

    GoDebugExpressionFragment(Project project, String text, Supplier<GoDebugCompletionEngine.Context> context) {
        super(text, GoFileType.INSTANCE, GoLanguage.INSTANCE, null, project);
        completionContext = context;
    }

    GoDebugCompletionEngine.Context completionContext() { return completionContext.get(); }

    @Override public boolean isPhysical() {
        // Preserve the event-enabled PSI/Document contract while paste awaits commit.
        return getViewProvider().isEventSystemEnabled();
    }
}

package dev.goforge.coverage;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

public final class ClearGoCoverageAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent event) { event.getPresentation().setEnabled(event.getProject() != null); }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() != null) GoCoverageService.getInstance(event.getProject()).clear();
    }
}

package dev.goforge.http;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

public final class OpenHttpClientAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent e) { e.getPresentation().setEnabled(e.getProject() != null); }
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        if (e.getProject() != null) HttpClientService.getInstance(e.getProject()).open(null, 0, false);
    }
}

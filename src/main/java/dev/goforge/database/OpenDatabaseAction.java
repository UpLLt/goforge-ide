package dev.goforge.database;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

public final class OpenDatabaseAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent event) { event.getPresentation().setEnabledAndVisible(event.getProject() != null); }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() == null) return;
        var window = ToolWindowManager.getInstance(event.getProject()).getToolWindow(DatabaseService.WINDOW_ID);
        if (window != null) window.activate(null);
    }
}

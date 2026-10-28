package dev.goforge.coverage;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public final class RunGoCoverageAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null && event.getProject().getBasePath() != null);
    }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        if (project == null || project.getBasePath() == null) return;
        Path base = Path.of(project.getBasePath()).toAbsolutePath().normalize();
        Path context = base;
        var selected = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (selected != null && selected.isInLocalFileSystem()) {
            Path path = Path.of(selected.getPath()).toAbsolutePath().normalize();
            if (path.startsWith(base)) context = selected.isDirectory() ? path : path.getParent();
        }
        GoCoverageService.getInstance(project).start(context);
    }
}

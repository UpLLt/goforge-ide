package dev.goforge.http;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

public final class RunHttpRequestAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent e) {
        var file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        e.getPresentation().setEnabledAndVisible(e.getProject() != null && e.getData(CommonDataKeys.EDITOR) != null && file != null
                && ("http".equals(file.getExtension()) || "rest".equals(file.getExtension())));
    }
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        var project = e.getProject(); var editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) return;
        int line = editor.getDocument().getLineNumber(editor.getCaretModel().getOffset());
        HttpClientService.getInstance(project).open(editor.getDocument().getText(), line, true);
    }
}

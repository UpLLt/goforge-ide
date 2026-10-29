package dev.goforge.lsp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

/** EDT-owned selection tracking; formatting callbacks never wait for the EDT. */
@Service(Service.Level.PROJECT)
public final class GoFormattingCaretIndentContext implements Disposable {
    private volatile Editor selectedEditor;

    public static GoFormattingCaretIndentContext getInstance(Project project) {
        return project.getService(GoFormattingCaretIndentContext.class);
    }

    Editor currentEditor() { return selectedEditor; }

    @Override public void dispose() { selectedEditor = null; }

    public static final class SelectionListener implements FileEditorManagerListener {
        @Override public void selectionChanged(@NotNull FileEditorManagerEvent event) {
            var project = event.getManager().getProject();
            if (!project.isDisposed()) {
                getInstance(project).selectedEditor = event.getNewEditor() instanceof TextEditor text ? text.getEditor() : null;
            }
        }
    }

    public static final class Startup implements StartupActivity.DumbAware {
        @Override public void runActivity(@NotNull Project project) {
            Runnable capture = () -> {
                if (!project.isDisposed())
                    getInstance(project).selectedEditor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            };
            if (ApplicationManager.getApplication().isDispatchThread()) capture.run();
            else ApplicationManager.getApplication().invokeLater(capture, project.getDisposed());
        }
    }
}

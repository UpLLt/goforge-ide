package dev.goforge.database;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class DatabaseToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        DatabaseService service = DatabaseService.getInstance(project);
        DatabasePanel panel = new DatabasePanel(project, service);
        var content = ContentFactory.getInstance().createContent(panel, "", false); content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content); service.attach(panel);
    }
}

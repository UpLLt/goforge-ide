package dev.goforge.coverage;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class GoCoverageToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        var service = GoCoverageService.getInstance(project);
        var panel = new GoCoveragePanel(service);
        toolWindow.getContentManager().addContent(ContentFactory.getInstance().createContent(panel, "", false));
        service.attach(panel);
    }
}

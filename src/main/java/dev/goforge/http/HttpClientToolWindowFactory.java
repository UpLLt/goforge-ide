package dev.goforge.http;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class HttpClientToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow window) {
        var service = HttpClientService.getInstance(project);
        var panel = new HttpClientPanel(project, service); service.attach(panel);
        window.getContentManager().addContent(ContentFactory.getInstance().createContent(panel, "", false));
    }
}

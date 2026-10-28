package dev.goforge.remote;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class GoRemoteToolWindowFactory implements ToolWindowFactory {
    public void createToolWindowContent(@NotNull Project project,@NotNull ToolWindow window) {
        var panel=new GoRemotePanel(project);var content=ContentFactory.getInstance().createContent(panel,"",false);content.setDisposer(panel);window.getContentManager().addContent(content);
    }
}

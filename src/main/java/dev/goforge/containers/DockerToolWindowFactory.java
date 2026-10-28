package dev.goforge.containers;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class DockerToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override public void createToolWindowContent(@NotNull Project project,@NotNull ToolWindow window) {
        var service=DockerService.getInstance(project);
        var panel=new DockerPanel(project,service);
        service.attach(panel);
        var tabs=new javax.swing.JTabbedPane();
        tabs.addTab("Docker",panel);tabs.addTab("Kubernetes",new KubernetesPanel(project));tabs.addTab("Dev Containers",new DevContainerPanel(project));
        window.getContentManager().addContent(ContentFactory.getInstance().createContent(tabs,"",false));
    }
}

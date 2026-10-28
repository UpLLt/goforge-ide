package dev.goforge.http;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;

@Service(Service.Level.PROJECT)
public final class HttpClientService implements Disposable {
    public static final String WINDOW_ID = "HTTP Client";
    private final Project project;
    final HttpRequestEngine engine = new HttpRequestEngine();
    private HttpClientPanel panel;
    volatile HttpCollectionRunner.Run collection;
    public HttpClientService(Project project) { this.project = project; }
    static HttpClientService getInstance(Project project) { return project.getService(HttpClientService.class); }
    void attach(HttpClientPanel panel) { this.panel = panel; }
    void open(String source, int line, boolean send) {
        var window = ToolWindowManager.getInstance(project).getToolWindow(WINDOW_ID);
        if (window != null) window.activate(() -> {
            if (!project.isDisposed() && panel != null && source != null) panel.load(source, line, send);
        });
    }
    @Override public void dispose() { var current=collection;if(current!=null)current.cancel();engine.close(); }
}

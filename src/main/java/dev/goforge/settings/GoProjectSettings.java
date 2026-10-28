package dev.goforge.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service(Service.Level.PROJECT)
@State(name = "GoForgeProject", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class GoProjectSettings implements PersistentStateComponent<GoProjectSettings> {
    public String buildTags = "";
    public String goos = "";
    public String goarch = "";
    public static GoProjectSettings getInstance(Project project) { return project.getService(GoProjectSettings.class); }
    @Override public GoProjectSettings getState() { return this; }
    @Override public void loadState(@NotNull GoProjectSettings state) { XmlSerializerUtil.copyBean(state, this); }
    public Map<String, String> environment() {
        var env = new LinkedHashMap<String, String>();
        if (goos != null && !goos.isBlank()) env.put("GOOS", goos);
        if (goarch != null && !goarch.isBlank()) env.put("GOARCH", goarch);
        return env;
    }
    public List<String> buildFlags() {
        return buildTags == null || buildTags.isBlank() ? List.of() : List.of("-tags=" + buildTags);
    }
    public Map<String, String> buildEnvironment(Map<String, String> inherited) {
        var env = new LinkedHashMap<>(inherited);
        dev.goforge.tools.HostTools.putEnvironment(env, environment());
        if (buildTags != null && !buildTags.isBlank())
            dev.goforge.tools.HostTools.putEnvironment(env, Map.of("GOFLAGS", (dev.goforge.tools.HostTools.environmentValue(env, "GOFLAGS") + " -tags=" + buildTags).trim()));
        return env;
    }
}

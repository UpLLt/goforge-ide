package dev.goforge.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

@State(name = "GoForgeSettings", storages = @Storage("goforge.xml"))
public final class GoForgeSettings implements PersistentStateComponent<GoForgeSettings> {
    public String goPath = "";
    public String goplsPath = "";
    public String delvePath = "";
    public boolean staticcheck = true;

    public static GoForgeSettings getInstance() {
        return ApplicationManager.getApplication().getService(GoForgeSettings.class);
    }
    @Override public GoForgeSettings getState() { return this; }
    @Override public void loadState(@NotNull GoForgeSettings state) { XmlSerializerUtil.copyBean(state, this); }
}

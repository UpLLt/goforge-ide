package dev.goforge.remote;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.PROJECT)
@State(name="GoForgeRemoteWorkspace",storages=@Storage(StoragePathMacros.WORKSPACE_FILE))
public final class GoRemoteSettings implements PersistentStateComponent<GoRemoteSettings> {
    public String host="",user="",keyFile="",remoteDirectory="",goExecutable="go",knownHostsFile="";
    public int port=22;
    public String goplsExecutable="gopls";
    public String delveExecutable="dlv";
    public static GoRemoteSettings getInstance(Project project) {return project.getService(GoRemoteSettings.class);}
    public GoRemoteSettings getState() {return this;}
    public void loadState(@NotNull GoRemoteSettings state) {XmlSerializerUtil.copyBean(state,this);}
    public GoRemoteProfile profile() {return new GoRemoteProfile(host,user,port,keyFile,remoteDirectory,goExecutable,knownHostsFile);}
    void save(GoRemoteProfile p) {host=p.host();user=p.user();port=p.port();keyFile=p.keyFile();remoteDirectory=p.remoteDirectory();goExecutable=p.goExecutable();knownHostsFile=p.knownHostsFile();}
}

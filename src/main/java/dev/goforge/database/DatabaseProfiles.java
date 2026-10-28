package dev.goforge.database;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.List;

@Service(Service.Level.PROJECT)
@State(name = "GoForgeDatabaseProfiles", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class DatabaseProfiles implements PersistentStateComponent<DatabaseProfiles.State> {
    public static final class State { public List<Entry> connections = new ArrayList<>(); }
    /** Deliberately contains no password, JDBC properties, SQL text or query results. */
    public static final class Entry {
        public String id = "", name = "", kind = "SQLITE", sqlitePath = ":memory:", host = "", database = "", username = "";
        public int port;
        public boolean verifyTls = true;
        public Entry() { }
        Entry(DatabaseProfile profile) {
            id = profile.id(); name = profile.name(); kind = profile.kind().name(); sqlitePath = profile.sqlitePath();
            host = profile.host(); port = profile.port(); database = profile.database(); username = profile.username(); verifyTls = profile.verifyTls();
        }
        DatabaseProfile profile() { return new DatabaseProfile(id, name, DatabaseProfile.Kind.valueOf(kind), sqlitePath, host, port, database, username, verifyTls); }
    }
    private List<DatabaseProfile> profiles = new ArrayList<>(List.of(DatabaseProfile.scratch()));
    public static DatabaseProfiles getInstance(Project project) { return project.getService(DatabaseProfiles.class); }
    public synchronized List<DatabaseProfile> profiles() { return List.copyOf(profiles); }
    public synchronized void put(DatabaseProfile profile) { profiles.removeIf(existing -> existing.id().equals(profile.id())); profiles.add(profile); }
    public synchronized void remove(String id) { profiles.removeIf(existing -> existing.id().equals(id)); }
    @Override public synchronized @NotNull State getState() {
        State state = new State(); profiles.forEach(profile -> state.connections.add(new Entry(profile))); return state;
    }
    @Override public synchronized void loadState(@NotNull State state) {
        List<DatabaseProfile> loaded = new ArrayList<>();
        if (state.connections != null) for (Entry entry : state.connections) {
            try { DatabaseProfile profile = entry.profile(); if (loaded.stream().noneMatch(p -> p.id().equals(profile.id()))) loaded.add(profile); }
            catch (RuntimeException ignored) { /* invalid external state never becomes a connection attempt */ }
        }
        profiles = loaded;
    }
}

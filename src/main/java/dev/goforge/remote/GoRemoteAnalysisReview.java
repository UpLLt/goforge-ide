package dev.goforge.remote;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Saved bytes and Go-parsed workspace topology shown together before any upload. */
public record GoRemoteAnalysisReview(GoRemoteFiles.Snapshot snapshot, GoRemoteDebugWorkspace workspace,
                                     Map<String,String> metadata) {
    public GoRemoteAnalysisReview {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(workspace);
        metadata=Map.copyOf(metadata);
        var hashes=new TreeMap<String,String>();
        snapshot.files().forEach(file -> hashes.put(file.path(),file.sha256()));
        workspace.requireFiles(hashes);
        var expected=new TreeMap<String,String>();
        hashes.forEach((path,hash) -> {if(isMetadata(path))expected.put(path,hash);});
        if(!metadata.equals(expected))throw new IllegalArgumentException("Analysis metadata must match the complete saved snapshot.");
        if(!workspace.enabled()&&hashes.containsKey("go.work"))throw new IllegalArgumentException("A project go.work must be reviewed before analysis.");
    }
    public static boolean isMetadata(String path) {
        String leaf=path.substring(path.lastIndexOf('/')+1);
        return Set.of("go.mod","go.sum","go.work","go.work.sum").contains(leaf);
    }
    public static GoRemoteAnalysisReview inspect(Path local, GoRemoteFiles.Snapshot snapshot, String go,
            Map<String,String> environment, BooleanSupplier cancelled) throws IOException {
        requireCurrent(local,snapshot,cancelled);
        var hashes=new TreeMap<String,String>();var metadata=new TreeMap<String,String>();
        snapshot.files().forEach(file -> {hashes.put(file.path(),file.sha256());if(isMetadata(file.path()))metadata.put(file.path(),file.sha256());});
        var workspace=GoRemoteDebugWorkspace.inspect(go,local,hashes,environment,cancelled);
        requireCurrent(local,snapshot,cancelled);
        return new GoRemoteAnalysisReview(snapshot,workspace,metadata);
    }
    static GoRemoteAnalysisReview singleModule(GoRemoteFiles.Snapshot snapshot) {
        var metadata=new TreeMap<String,String>();
        snapshot.files().forEach(file -> {if(isMetadata(file.path()))metadata.put(file.path(),file.sha256());});
        return new GoRemoteAnalysisReview(snapshot,GoRemoteDebugWorkspace.singleModule(),metadata);
    }
    static void requireCurrent(Path local,GoRemoteFiles.Snapshot snapshot,BooleanSupplier cancelled)throws IOException {
        if(!snapshot.files().equals(GoRemoteFiles.snapshot(local,cancelled).files()))
            throw new IOException("Local saved files changed during workspace review. Request a fresh remote analysis preview.");
    }
    public String description() {
        return workspace.enabled()?"go.work modules: "+String.join(", ",workspace.modules())
            +"\nLocal replacements: "+(workspace.localReplacements().isEmpty()?"none":String.join(", ",workspace.localReplacements()))
            :"Single module at the project root (GOWORK=off)";
    }
}

package dev.goforge.remote;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Session-only binding. Never persisted or automatically reconnected when a project opens. */
public record GoRemoteAnalysisConfig(GoRemoteProfile profile,Path localRoot,String remoteRoot,
                                     String gopls,String name,Set<String> files,
                                     GoRemoteDebugWorkspace workspace,Map<String,String> metadata) {
    public GoRemoteAnalysisConfig {
        Objects.requireNonNull(profile);localRoot=localRoot.toAbsolutePath().normalize();
        GoRemoteProfile.absoluteRemote(remoteRoot);validateExecutable(gopls);
        if(!profile.goExecutable().equals("go")&&!profile.goExecutable().endsWith("/go"))throw new IllegalArgumentException("Remote analysis needs the default go command or an absolute Go SDK bin/go path.");
        if(!name.matches("goforge-analysis-[a-f0-9-]{36}")||!remoteRoot.endsWith("/"+name))throw new IllegalArgumentException("Invalid analysis directory.");
        files=Set.copyOf(files);files.forEach(GoRemoteProfile::relative);
        Objects.requireNonNull(workspace);metadata=Map.copyOf(metadata);
        if(!files.stream().filter(GoRemoteAnalysisReview::isMetadata).collect(java.util.stream.Collectors.toSet()).equals(metadata.keySet())
                ||metadata.values().stream().anyMatch(hash -> !hash.matches("[a-f0-9]{64}")))
            throw new IllegalArgumentException("Invalid analysis metadata binding.");
        workspace.requireFiles(metadata);
    }
    public static String validateExecutable(String value) {
        if(value==null||value.isBlank())throw new IllegalArgumentException("Enter the remote gopls executable.");
        GoRemoteProfile.noControls(value);
        if(value.startsWith("/"))GoRemoteProfile.absoluteRemote(value);
        else if(!value.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*"))throw new IllegalArgumentException("Remote gopls must be a command name or absolute path, without arguments.");
        return value;
    }
    public static GoRemoteAnalysisConfig create(GoRemoteBackend backend,GoRemoteProfile profile,Path local,
                                                String gopls,GoRemoteFiles.Snapshot snapshot,BooleanSupplier cancel)throws Exception {
        return create(backend,profile,local,gopls,GoRemoteAnalysisReview.singleModule(snapshot),cancel);
    }
    public static GoRemoteAnalysisConfig create(GoRemoteBackend backend,GoRemoteProfile profile,Path local,
                                                String gopls,GoRemoteAnalysisReview review,BooleanSupplier cancel)throws Exception {
        validateExecutable(gopls);Path canonical=local.toRealPath();
        var snapshot=review.snapshot();GoRemoteAnalysisReview.requireCurrent(canonical,snapshot,cancel);
        String name="goforge-analysis-"+UUID.randomUUID();
        var uploaded=backend.analysisSnapshot(profile,name,snapshot,cancel);
        // Preview/upload may take time. Do not quietly bind stale saved bytes.
        var now=GoRemoteFiles.snapshot(canonical,cancel);
        if(!snapshot.files().equals(now.files()))throw new IOException("Local saved files changed during snapshot creation. Start remote analysis again. The new remote snapshot was retained for inspection.");
        var names=new HashSet<String>();snapshot.files().forEach(f -> names.add(f.path()));
        return new GoRemoteAnalysisConfig(profile,local.toAbsolutePath().normalize(),uploaded.get("directory").getAsString(),gopls,name,names,review.workspace(),review.metadata());
    }
}

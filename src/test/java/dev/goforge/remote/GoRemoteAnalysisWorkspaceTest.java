package dev.goforge.remote;

import com.google.gson.*;
import org.junit.Test;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public final class GoRemoteAnalysisWorkspaceTest {
    private static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("goforge-analysis-review-").toRealPath();
        Fixture()throws IOException {
            put("go.work","go 1.26\nuse (\n ./app\n ./lib\n)\n");
            put("app/go.mod","module example.com/app\ngo 1.26\n");
            put("lib/go.mod","module example.com/lib\ngo 1.26\n");
            put("app/main.go","package app\n");put("lib/value.go","package lib\n");
        }
        void put(String path,String text)throws IOException {Path target=root.resolve(path);Files.createDirectories(target.getParent());Files.writeString(target,text);}
        GoRemoteAnalysisReview review()throws IOException {
            var snapshot=GoRemoteFiles.snapshot(root,()->false);var metadata=new TreeMap<String,String>();
            snapshot.files().stream().filter(f->GoRemoteAnalysisReview.isMetadata(f.path())).forEach(f->metadata.put(f.path(),f.sha256()));
            return new GoRemoteAnalysisReview(snapshot,new GoRemoteDebugWorkspace("go.work",List.of("app","lib"),List.of()),metadata);
        }
        GoRemoteAnalysisConfig config()throws IOException {
            var review=review();var names=new TreeSet<String>();review.snapshot().files().forEach(f->names.add(f.path()));
            String name="goforge-analysis-"+UUID.randomUUID();
            return new GoRemoteAnalysisConfig(new GoRemoteProfile("fixture.invalid","",22,"","/remote","go",""),root,"/remote/"+name,"gopls",name,names,review.workspace(),review.metadata());
        }
        public void close()throws IOException {try(var paths=Files.walk(root)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    @Test public void workspaceReviewNeedsNoRootModuleAndListsEveryModule()throws Exception {
        try(var f=new Fixture()){var review=f.review();assertFalse(Files.exists(f.root.resolve("go.mod")));assertEquals(List.of("app","lib"),review.workspace().modules());assertEquals(Set.of("go.work","app/go.mod","lib/go.mod"),review.metadata().keySet());assertTrue(review.description().contains("app, lib"));GoRemoteAnalysisReview.requireCurrent(f.root,review.snapshot(),()->false);}
    }
    @Test public void existingSingleModuleEntryCannotSilentlyIgnoreWorkspace()throws Exception {
        try(var f=new Fixture()){f.put("go.mod","module example.com/root\ngo 1.26\n");assertThrows(IllegalArgumentException.class,()->GoRemoteAnalysisReview.singleModule(GoRemoteFiles.snapshot(f.root,()->false)));}
    }
    @Test public void metadataManifestCannotOmitAnotherReviewedModule()throws Exception {
        try(var f=new Fixture()){var review=f.review();var missing=new HashMap<>(review.metadata());missing.remove("lib/go.mod");assertThrows(IllegalArgumentException.class,()->new GoRemoteAnalysisReview(review.snapshot(),review.workspace(),missing));}
    }
    @Test public void sourceChangeBetweenReviewAndUploadInvalidatesWholeSnapshot()throws Exception {
        try(var f=new Fixture()){var review=f.review();f.put("lib/value.go","package lib\nvar Changed=1\n");assertThrows(IOException.class,()->GoRemoteAnalysisReview.requireCurrent(f.root,review.snapshot(),()->false));}
    }
    @Test public void moduleMetadataChangeBeforeStartFailsWithoutStartingSsh()throws Exception {
        try(var f=new Fixture()){var config=f.config();GoRemoteLspTransport.requireMetadataCurrent(config);f.put("lib/go.mod","module changed\ngo 1.26\n");assertThrows(IOException.class,()->GoRemoteLspTransport.requireMetadataCurrent(config));}
    }
    @Test public void sumMetadataIsPinnedWithWorkspaceTopology()throws Exception {
        try(var f=new Fixture()){f.put("go.work.sum","fixture sum\n");var config=f.config();assertTrue(config.metadata().containsKey("go.work.sum"));f.put("go.work.sum","changed sum\n");assertThrows(IOException.class,()->GoRemoteLspTransport.requireMetadataCurrent(config));}
    }
    @Test public void newRootWorkspaceCannotChangeAnExistingSingleModuleSession()throws Exception {
        try(var f=new Fixture()){Files.delete(f.root.resolve("go.work"));f.put("go.mod","module example.com/root\ngo 1.26\n");var review=GoRemoteAnalysisReview.singleModule(GoRemoteFiles.snapshot(f.root,()->false));var names=new HashSet<String>();review.snapshot().files().forEach(x->names.add(x.path()));String name="goforge-analysis-"+UUID.randomUUID();var config=new GoRemoteAnalysisConfig(new GoRemoteProfile("fixture.invalid","",22,"","/remote","go",""),f.root,"/remote/"+name,"gopls",name,names,review.workspace(),review.metadata());f.put("go.work","go 1.26\nuse .\n");assertThrows(IOException.class,()->GoRemoteLspTransport.requireMetadataCurrent(config));}
    }
    @Test public void initializeUsesOneCommonRootRatherThanOnlyTheOpenedModule()throws Exception {
        try(var f=new Fixture()){var mapping=new GoRemoteLspMapping(f.root,"/remote/snapshot");var packet=JsonParser.parseString("{\"params\":{\"processId\":12,\"trace\":\"verbose\",\"rootUri\":\"file:///remote/snapshot/app\",\"workspaceFolders\":[{\"uri\":\"file:///remote/snapshot/app\",\"name\":\"app\"}]}}").getAsJsonObject();GoRemoteLspTransport.initializeWorkspace(packet,mapping,"workspace");var p=packet.getAsJsonObject("params");assertTrue(p.get("processId").isJsonNull());assertEquals("off",p.get("trace").getAsString());assertEquals("file:///remote/snapshot",p.get("rootUri").getAsString());assertEquals(1,p.getAsJsonArray("workspaceFolders").size());assertEquals("file:///remote/snapshot",p.getAsJsonArray("workspaceFolders").get(0).getAsJsonObject().get("uri").getAsString());}
    }
    @Test public void settingsKeepReviewedPrivateWorkfileAndRemoveLocalOverrides() {
        String work="/remote/snapshot/.goforge-runtime-"+UUID.randomUUID()+".work";
        var settings=JsonParser.parseString("{\"result\":[{\"env\":{\"GOWORK\":\"/local/other.work\",\"PRIVATE_TOKEN\":\"secret\",\"GOFLAGS\":\"-modfile=other\"},\"buildFlags\":[\"-tags=integration\",\"-modfile=/local/other\"]}]}");
        GoRemoteLspTransport.sanitizeSettings(settings,work);var result=settings.getAsJsonObject().getAsJsonArray("result").get(0).getAsJsonObject();assertEquals(work,result.getAsJsonObject("env").get("GOWORK").getAsString());assertEquals("off",result.getAsJsonObject("env").get("GOENV").getAsString());assertEquals("-mod=readonly",result.getAsJsonObject("env").get("GOFLAGS").getAsString());assertFalse(settings.toString().contains("secret"));assertFalse(settings.toString().contains("/local/"));assertEquals("[\"-tags=integration\"]",result.getAsJsonArray("buildFlags").toString());
    }
    @Test public void privateWorkspaceDiagnosticsMapBackWithoutRewritingSourceStrings()throws Exception {
        try(var f=new Fixture()){String runtime=".goforge-runtime-"+UUID.randomUUID()+".work";var mapping=new GoRemoteLspMapping(f.root,"/remote/snapshot",runtime);assertEquals(f.root.resolve("go.work").toUri().toASCIIString(),mapping.uri("file:///remote/snapshot/"+runtime,false));assertEquals(f.root.resolve("go.work.sum").toUri().toASCIIString(),mapping.uri("file:///remote/snapshot/"+runtime+".sum",false));var text=new JsonObject();text.addProperty("message","file:///remote/snapshot/"+runtime);assertEquals(text,mapping.rewrite(text,false));assertThrows(IllegalArgumentException.class,()->mapping.uri("file:///elsewhere/"+runtime,false));}
    }
}

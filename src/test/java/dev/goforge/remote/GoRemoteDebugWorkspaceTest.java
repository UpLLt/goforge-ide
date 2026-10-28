package dev.goforge.remote;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GoRemoteDebugWorkspaceTest {
    private static final String HASH="a".repeat(64);
    private static final class Fixture implements AutoCloseable {
        final Path root;final Map<String,String> files=new TreeMap<>();
        Fixture()throws IOException {root=Files.createTempDirectory("goforge-workspace-").toRealPath();Files.writeString(root.resolve("go.work"),"go 1.26\n");files.put("go.work",HASH);}
        void module(String path)throws IOException {Path dir=root.resolve(path);Files.createDirectories(dir);Files.writeString(dir.resolve("go.mod"),"module fixture\n");files.put(GoRemoteDebugWorkspace.modFile(path),HASH);}
        GoRemoteDebugWorkspace read(String json,Map<String,String> moduleJson)throws IOException {
            return GoRemoteDebugWorkspace.inspect(root,files,(kind,file)->JsonParser.parseString(kind.equals("work")?json:moduleJson.getOrDefault(root.relativize(file).toString().replace('\\','/'),"{} ")).getAsJsonObject());
        }
        public void close()throws IOException {try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    @Test public void quotedUnicodeModulesNeedNoRootGoModAndKeepCommonMapping()throws Exception {
        try(var f=new Fixture()){f.module("app space");f.module("库");var w=f.read("{\"Use\":[{\"DiskPath\":\"./库\"},{\"DiskPath\":\"./app space\"}]}",Map.of());assertEquals(List.of("app space","库"),w.modules());assertTrue(w.enabled());w.requireFiles(f.files);
            var p=new GoRemoteDebugPlan(new GoRemoteProfile("fixture.invalid","",22,"","/remote","go",""),f.root.toString(),"dlv","debug","app space",0,f.files,List.of(),Map.of(),w);
            assertTrue(p.startupJson().contains("go.work"));assertEquals(List.of(Map.of("from",f.root.toString(),"to","/remote")),p.dapArguments().get("substitutePath"));}
    }
    @Test public void localReplacementsResolveAgainstDeclaringModule()throws Exception {
        try(var f=new Fixture()){f.module("app");f.module("shared");f.module("work replacement");var w=f.read("{\"Use\":[{\"DiskPath\":\"app\"}],\"Replace\":[{\"New\":{\"Path\":\"./work replacement\"}}]}",Map.of("app/go.mod","{\"Replace\":[{\"New\":{\"Path\":\"../shared\"}},{\"New\":{\"Path\":\"example.org/remote\",\"Version\":\"v1.0.0\"}}]}"));assertEquals(List.of("shared","work replacement"),w.localReplacements());}
    }
    @Test public void outsideAndAbsoluteUseCannotEscapeCommonSourceRoot()throws Exception {
        try(var f=new Fixture()){f.module("app");for(String path:List.of("../outside","/absolute","C:/absolute","C:\\absolute"))assertThrows(IOException.class,()->GoRemoteDebugWorkspace.boundedDirectory(f.root,f.root,path,f.files));}
    }
    @Test public void replacementOutsideRootFailsEvenWhenSourceModuleIsValid()throws Exception {
        try(var f=new Fixture()){f.module("app");assertThrows(IOException.class,()->f.read("{\"Use\":[{\"DiskPath\":\"app\"}]}",Map.of("app/go.mod","{\"Replace\":[{\"New\":{\"Path\":\"../../outside\"}}]}")));}
    }
    @Test public void excludedOrSymlinkModuleIsNotAccepted()throws Exception {
        try(var f=new Fixture()){f.module("real");Files.createSymbolicLink(f.root.resolve("linked"),f.root.resolve("real"));f.files.put("linked/go.mod",HASH);assertThrows(IOException.class,()->GoRemoteDebugWorkspace.boundedDirectory(f.root,f.root,"linked",f.files));f.files.remove("real/go.mod");assertThrows(IOException.class,()->GoRemoteDebugWorkspace.boundedDirectory(f.root,f.root,"real",f.files));}
    }
    @Test public void duplicateOrEmptyWorkspaceModulesAreRejected()throws Exception {
        try(var f=new Fixture()){f.module("app");assertThrows(IOException.class,()->f.read("{\"Use\":[]}",Map.of()));assertThrows(IOException.class,()->f.read("{\"Use\":[{\"DiskPath\":\"app\"},{\"DiskPath\":\"./app\"}]}",Map.of()));}
    }
    @Test public void everyModuleAndReplacementMustBeInHashManifest() {
        var w=new GoRemoteDebugWorkspace("go.work",List.of("app"),List.of("shared"));assertThrows(IllegalArgumentException.class,()->w.requireFiles(Map.of("go.work",HASH,"app/go.mod",HASH)));
        assertThrows(IllegalArgumentException.class,()->new GoRemoteDebugWorkspace("../go.work",List.of("app"),List.of()));
    }
    @Test public void sourceMutationAfterWorkspaceReviewInvalidatesAllModules()throws Exception {
        try(var f=new Fixture()){f.module("app");f.module("shared");var hashes=new TreeMap<String,String>();GoRemoteDebugSources.snapshot(f.root,()->false).files().forEach(file->hashes.put(file.path(),file.sha256()));
            var p=new GoRemoteDebugPlan(new GoRemoteProfile("fixture.invalid","",22,"","/remote","go",""),f.root.toString(),"dlv","debug","app",0,hashes,List.of(),Map.of(),new GoRemoteDebugWorkspace("go.work",List.of("app","shared"),List.of()));p.verifyLocal();Files.writeString(f.root.resolve("shared/go.mod"),"module changed\n");assertThrows(IOException.class,p::verifyLocal);}
    }
    @Test public void cancelledMetadataReviewNeverStartsConfiguredGo()throws Exception {
        try(var f=new Fixture()){f.module("app");var error=assertThrows(IOException.class,()->GoRemoteDebugWorkspace.inspect("/missing/no-go",f.root,f.files,Map.of(),()->true));assertTrue(error.getMessage().contains("cancelled"));}
    }
    @Test public void ordinaryModuleNeedsNoWorkspaceParserProcess()throws Exception {
        try(var f=new Fixture()){Files.delete(f.root.resolve("go.work"));f.files.remove("go.work");f.module(".");var w=GoRemoteDebugWorkspace.inspect("/missing/no-go",f.root,f.files,Map.of(),()->false);assertFalse(w.enabled());assertEquals(List.of("."),w.modules());}
    }
    @Test public void supplementaryAndHighBmpModuleNamesRemainExactMembers()throws Exception {
        try(var f=new Fixture()){f.module("𐀀");f.module("");var w=f.read("{\"Use\":[{\"DiskPath\":\"\"},{\"DiskPath\":\"𐀀\"}]}",Map.of());assertEquals(List.of("𐀀",""),w.modules());assertEquals(Set.of("𐀀",""),Set.copyOf(w.modules()));w.requireFiles(f.files);}
    }
}

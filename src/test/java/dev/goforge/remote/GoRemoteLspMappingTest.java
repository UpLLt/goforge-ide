package dev.goforge.remote;

import org.junit.Test;
import java.nio.file.Path;
import static org.junit.Assert.*;

public final class GoRemoteLspMappingTest {
    private final Path root=Path.of(System.getProperty("java.io.tmpdir"),"GoForge space 分析").toAbsolutePath();
    @Test public void remotePathsRemainPosixOnEveryLocalHost() {
        var mapping=new GoRemoteLspMapping(root,"/srv/go/project/");
        assertEquals("/srv/go/project",mapping.remoteRoot());
        String local=root.resolve("sub/你好.go").toUri().toASCIIString();
        assertEquals("file:///srv/go/project/sub/%E4%BD%A0%E5%A5%BD.go",mapping.uri(local,true));
        assertEquals(local,mapping.uri(mapping.uri(local,true),false));
        assertEquals("sub/你好.go",mapping.relative(local));
    }
    @Test public void unseenRemoteLocationsProduceNativeLocalFileUris() {
        var mapping=new GoRemoteLspMapping(root,"/srv/project");
        assertEquals(root.resolve("pkg/new file.go").toUri().toASCIIString(),mapping.uri("file:///srv/project/pkg/new%20file.go",false));
        assertEquals(root.toUri().toASCIIString(),mapping.uri("file:///srv/project",false));
        assertEquals(root.toUri().toASCIIString(),mapping.uri("file:///srv/project/",false));
    }
    @Test public void remoteTraversalBackslashesAndSiblingRootsCannotEscapeLocalBinding() {
        var mapping=new GoRemoteLspMapping(root,"/srv/project");
        for(String uri:new String[]{"file:///srv/project/../outside.go","file:///srv/project/%2e%2e/outside.go","file:///srv/project-other/a.go","file:///srv/project/sub%5c..%5cother.go"})
            assertThrows(IllegalArgumentException.class,()->mapping.uri(uri,false));
    }
    @Test public void rootPathsAndWatchPatternsRetainSeparateHostConventions() {
        var mapping=new GoRemoteLspMapping(root,"/srv/project");
        var settings=new com.google.gson.JsonObject();settings.addProperty("rootPath",root.toString());settings.addProperty("globPattern",root+"/**/*.go");
        var result=mapping.rewrite(settings,true).getAsJsonObject();
        assertEquals("/srv/project",result.get("rootPath").getAsString());assertEquals("/srv/project/**/*.go",result.get("globPattern").getAsString());
        assertEquals(root.toString(),mapping.rewrite(result,false).getAsJsonObject().get("rootPath").getAsString());
    }
}

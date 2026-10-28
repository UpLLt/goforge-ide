package dev.goforge.remote;

import com.google.gson.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static org.junit.Assert.*;

public final class GoRemoteAnalysisBridgeTest {
    private final Path localRoot=Path.of(System.getProperty("java.io.tmpdir"),"local","space project").toAbsolutePath();
    private final GoRemoteLspMapping mapping=new GoRemoteLspMapping(localRoot,"/remote/分析 ' snapshot");
    private String localUri(String name){return localRoot.resolve(name).toUri().toASCIIString();}
    @Test public void unicodeAndQuotedPathsRoundtripWithSegmentBoundary() {
        String local=localUri("sub/中.go");
        assertEquals(local,mapping.uri(mapping.uri(local,true),false));
        assertThrows(IllegalArgumentException.class,() -> mapping.uri(localRoot.resolveSibling("space project-other").resolve("main.go").toUri().toString(),true));
        assertThrows(IllegalArgumentException.class,() -> mapping.uri("file:///remote/dependency/mod.go",false));
        assertThrows(IllegalArgumentException.class,() -> mapping.uri("file://server/local/space%20project/a.go",true));
    }
    @Test public void locationsWorkspaceEditKeysAndResolveDataMapButSourceTextDoesNot() {
        var source=JsonParser.parseString("""
            {"data":{"command":{"arguments":[{"Location":{"uri":"file:///local/space%20project/a.go"}}]}},
             "changes":{"file:///local/space%20project/a.go":[{"newText":"file:///local/space%20project/literal.go"}]},
             "documentChanges":[{"textDocument":{"uri":"file:///local/space%20project/b.go","version":2},"edits":[]}],
             "text":"file:///local/space%20project/code.go","message":"file:///local/space%20project/prose.go"}
            """.replace("file:///local/space%20project/",localUri("a.go").replace("a.go","")));
        var mapped=mapping.rewrite(source,true).getAsJsonObject();
        assertEquals(source,mapping.rewrite(mapped,false));
        assertTrue(mapped.getAsJsonObject("changes").keySet().stream().allMatch(k -> k.contains("/remote/")));
        assertEquals(localUri("code.go"),mapped.get("text").getAsString());
        assertEquals(localUri("literal.go"),mapped.getAsJsonObject("changes").entrySet().iterator().next().getValue().getAsJsonArray().get(0).getAsJsonObject().get("newText").getAsString());
    }
    @Test public void initializeRootPathAndAbsoluteWatchPatternMap() {
        var source=new JsonObject();source.addProperty("rootPath",localRoot.toString());source.addProperty("globPattern",localRoot+"/**/*.go");
        var result=mapping.rewrite(source,true).getAsJsonObject();assertEquals(mapping.remoteRoot(),result.get("rootPath").getAsString());
        assertEquals(mapping.remoteRoot()+"/**/*.go",result.get("globPattern").getAsString());
    }
    @Test public void outsideRootAndTraversalAreRejected() {
        for(String uri:new String[]{localUri("../outside.go"),localUri("a.go")+"?x=1","untitled:fragment"})
            assertThrows(IllegalArgumentException.class,() -> mapping.uri(uri,true));
    }
    @Test public void framingCountsUtf8BytesAndAllowsFragmentedReads()throws Exception {
        var packet=JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"method\":\"fixture\",\"params\":\"你好🌍\"}").getAsJsonObject();
        var output=new ByteArrayOutputStream();GoRemoteLspFraming.write(output,packet);
        var fragmented=new FilterInputStream(new ByteArrayInputStream(output.toByteArray())) {
            public int read(byte[] b,int off,int len)throws IOException{return super.read(b,off,Math.min(len,2));}
        };
        assertEquals(packet,GoRemoteLspFraming.read(fragmented));assertNull(GoRemoteLspFraming.read(fragmented));
    }
    @Test public void malformedDuplicateAndOversizedFramesFail() {
        for(String frame:new String[]{"Content-Length: 999999999\r\n\r\n","Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}","login banner\n\n{}","Content-Length: 5\r\n\r\n{}"})
            assertThrows(IOException.class,() -> GoRemoteLspFraming.read(new ByteArrayInputStream(frame.getBytes(StandardCharsets.UTF_8))));
    }
    @Test public void localEnvironmentAndPathFlagsAreNeverForwarded() {
        var settings=JsonParser.parseString("{\"result\":[{\"env\":{\"TOKEN\":\"private\",\"GOROOT\":\"/local/go\"},\"buildFlags\":[\"-tags=integration\",\"-modfile=/local/private.mod\"]}]}");
        GoRemoteLspTransport.sanitizeSettings(settings);
        assertFalse(settings.toString().contains("private"));assertFalse(settings.toString().contains("GOROOT"));
        assertTrue(settings.toString().contains("-tags=integration"));assertTrue(settings.toString().contains("GOWORK"));
    }
    @Test public void remoteExecutableDoesNotAcceptInjectedOptions() {
        assertEquals("/opt/go tools/gopls",GoRemoteAnalysisConfig.validateExecutable("/opt/go tools/gopls"));
        for(String invalid:new String[]{"-remote=auto","gopls;touch x","gopls serve","gopls\n"})
            assertThrows(IllegalArgumentException.class,() -> GoRemoteAnalysisConfig.validateExecutable(invalid));
    }
}

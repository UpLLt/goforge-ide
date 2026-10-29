package dev.goforge.remote;

import com.google.gson.*;
import org.junit.Test;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class GoRemoteStdlibNavigationTest {
    private static final String TOKEN="a".repeat(64),TEXT="package fmt\n// 世界🙂\nfunc Println() {}\n";
    private static final class Fixture implements AutoCloseable {
        final GoRemoteLspMapping mapping=new GoRemoteLspMapping(Path.of(System.getProperty("java.io.tmpdir"),"stdlib fixture"),"/owned/project");
        final GoRemoteStdlibSources sources=new GoRemoteStdlibSources();
        final List<JsonObject> remote=new ArrayList<>(),client=new ArrayList<>();
        final GoRemoteStdlibNavigation nav=new GoRemoteStdlibNavigation(mapping,sources,remote::add,client::add);
        final String origin=Path.of(mapping.localRoot()).resolve("main.go").toUri().toASCIIString();
        Fixture(){sources.initialize(json("{root:'/sdk/src',token:'"+TOKEN+"',version:'go1.26.4'}"));}
        JsonObject request(String method,int id)throws Exception {var packet=json("{jsonrpc:'2.0',id:"+id+",method:'"+method+"',params:{textDocument:{uri:"+new Gson().toJson(origin)+"},position:{line:0,character:0}}}");return nav.outgoing(packet,mapping.rewrite(packet,true).getAsJsonObject());}
        JsonObject response(JsonObject request,String result)throws Exception {var packet=json("{jsonrpc:'2.0',result:"+result+"}");packet.add("id",request.get("id"));assertTrue(nav.incoming(packet));return packet;}
        void read(String text)throws Exception {var query=remote.getLast().getAsJsonObject("params");var result=source(query.get("path").getAsString(),text);result.add("id",query.get("id"));var packet=json("{method:'goforge/stdlibSource'}");packet.add("params",result);assertTrue(nav.incoming(packet));}
        public void close(){nav.close();}
    }
    static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    static JsonObject source(String path,String text){byte[] bytes=text.getBytes(StandardCharsets.UTF_8);var result=new JsonObject();result.addProperty("path",path);result.addProperty("token",TOKEN);result.addProperty("data",Base64.getEncoder().encodeToString(bytes));result.addProperty("sha256",GoRemoteFiles.hash(bytes));return result;}
    static String location(String uri){return "[{uri:'"+uri+"',range:{start:{line:2,character:5},end:{line:2,character:12}}}]";}
    @Test public void definitionFetchesExactContentAndRestoresClientIdWithoutBroadeningMapper()throws Exception {
        try(var f=new Fixture()){
            var request=f.request("textDocument/definition",17);assertNotEquals("17",request.get("id").getAsString());
            f.response(request,location("file:///sdk/src/fmt/print.go"));assertTrue(f.client.isEmpty());assertEquals(1,f.remote.size());
            f.read(TEXT);var result=f.client.getFirst();assertEquals(17,result.get("id").getAsInt());String uri=result.getAsJsonArray("result").get(0).getAsJsonObject().get("uri").getAsString();
            assertEquals(TEXT,f.sources.find(uri).text());assertThrows(IllegalArgumentException.class,()->f.mapping.uri(uri,true));
            assertThrows(IllegalArgumentException.class,()->f.mapping.rewrite(json("{edit:{changes:{'file:///sdk/src/fmt/print.go':[]}}}"),false));
        }
    }
    @Test public void methodAndDirectionAreRequiredEvenForMatchingIds()throws Exception {
        try(var f=new Fixture()){
            var ordinary=f.request("textDocument/references",3);assertEquals(3,ordinary.get("id").getAsInt());
            var unknown=json("{id:3,result:"+location("file:///sdk/src/fmt/print.go")+"}");assertFalse(f.nav.incoming(unknown));
            var request=f.request("textDocument/definition",4);var serverRequest=json("{method:'workspace/applyEdit',params:{}}");serverRequest.add("id",request.get("id"));assertFalse(f.nav.incoming(serverRequest));assertTrue(f.remote.isEmpty());
        }
    }
    @Test public void cancellationAndReusedOriginalIdsCannotResurrectOldResponse()throws Exception {
        try(var f=new Fixture()){
            var first=f.request("textDocument/definition",7);var cancel=json("{method:'$/cancelRequest',params:{id:7}}");
            assertEquals(first.get("id"),f.nav.outgoing(cancel,cancel.deepCopy()).getAsJsonObject("params").get("id"));
            var second=f.request("textDocument/typeDefinition",7);assertNotEquals(first.get("id"),second.get("id"));
            f.response(first,location("file:///sdk/src/fmt/print.go"));assertTrue(f.remote.isEmpty());f.response(second,"null");assertEquals(1,f.client.size());
        }
    }
    @Test public void documentChangesInvalidatePendingFetchAndLateBytes()throws Exception {
        try(var f=new Fixture()){
            var request=f.request("textDocument/definition",1);f.response(request,location("file:///sdk/src/fmt/print.go"));
            var change=json("{method:'textDocument/didChange',params:{textDocument:{uri:"+new Gson().toJson(f.origin)+",version:2},contentChanges:[{text:'changed'}]}}");f.nav.outgoing(change,f.mapping.rewrite(change,true).getAsJsonObject());
            assertTrue(f.client.getFirst().has("error"));f.read(TEXT);assertEquals(1,f.client.size());
        }
    }
    @Test public void typeDefinitionLinkHasValidatedRangesAndDropsUnneededOriginRange()throws Exception {
        try(var f=new Fixture()){
            var request=f.request("textDocument/typeDefinition",2);f.response(request,"[{targetUri:'file:///sdk/src/fmt/print.go',targetRange:{start:{line:2,character:0},end:{line:2,character:17}},targetSelectionRange:{start:{line:2,character:5},end:{line:2,character:12}},originSelectionRange:{start:{line:999,character:0},end:{line:999,character:1}}}]");f.read(TEXT);
            var location=f.client.getFirst().getAsJsonArray("result").get(0).getAsJsonObject();assertFalse(location.has("originSelectionRange"));assertTrue(location.get("targetUri").getAsString().startsWith("goforge-remote-source:"));
        }
    }
    @Test public void invalidRangesAndOutsideTargetsFailWholeResponse()throws Exception {
        for(String location:List.of("[{uri:'file:///sdk/src/fmt/print.go',range:{start:{line:1,character:6},end:{line:1,character:7}}}]",location("file:///sdk/src-other/print.go"),location("file:///modcache/fmt/print.go"))){
            try(var f=new Fixture()){var request=f.request("textDocument/definition",1);f.response(request,location);if(f.client.isEmpty())f.read(TEXT);assertTrue(f.client.getFirst().has("error"));}
        }
    }
    @Test public void cacheChecksDigestUtf8IdentityAndStop()throws Exception {
        try(var f=new Fixture()){
            var bad=source("fmt/print.go",TEXT);bad.addProperty("sha256","b".repeat(64));assertThrows(IllegalArgumentException.class,()->f.sources.accept("fmt/print.go",bad));
            var source=f.sources.accept("fmt/print.go",source("fmt/print.go",TEXT));assertNotNull(f.sources.find(source.uri()));
            assertThrows(IllegalArgumentException.class,()->f.sources.accept("fmt/print.go",source("fmt/print.go",TEXT+"//new\n")));
            f.close();assertNull(f.sources.find(source.uri()));assertThrows(IllegalArgumentException.class,()->f.sources.token());
        }
    }
    @Test public void virtualSnapshotRefusesAllWritePathsAndExpiresWithItsOwner()throws Exception {
        try(var f=new Fixture()){
            var source=f.sources.accept("fmt/print.go",source("fmt/print.go",TEXT));var file=new GoRemoteSourceFile(source,f.sources);
            assertFalse(file.isWritable());assertTrue(file.isValid());assertEquals(TEXT,file.getContent().toString());
            assertThrows(UnsupportedOperationException.class,()->file.setWritable(true));
            assertThrows(UnsupportedOperationException.class,()->file.setContent(this,"changed",false));
            assertThrows(java.io.IOException.class,()->file.getOutputStream(this,0,0));
            assertThrows(java.io.IOException.class,()->file.rename(this,"changed.go"));
            assertThrows(java.io.IOException.class,()->file.setBinaryContent(new byte[]{1},0,0));
            f.sources.close();assertFalse(file.isValid());
        }
    }
    @Test public void remotePathsNeverUseLocalWindowsSemanticsOrDecodeTraversalIntoAcceptance(){
        try(var f=new Fixture()){
            assertEquals("fmt/中文 file.go",f.sources.relative("file:///sdk/src/fmt/%E4%B8%AD%E6%96%87%20file.go"));
            for(String uri:List.of("file:///sdk/src/../outside.go","file:///sdk/src/fmt%2fprint.go","file://host/sdk/src/fmt/x.go","file:///sdk/src/fmt/x.go?x=1","file:///C:/sdk/src/x.go","file:///sdk/src/fmt/%ff.go","file:///sdk/src/fmt/x.go#part"))assertThrows(uri,IllegalArgumentException.class,()->f.sources.relative(uri));
        }
    }
    @Test public void rejectedServerControlsDoNotCreateFilesAndClientControlsNeverLeaveTransport()throws Exception {
        try(var f=new Fixture()){
            var injected=json("{id:1,method:'goforge/readStdlib',params:{path:'etc/passwd'}}");assertNull(f.nav.outgoing(injected,injected.deepCopy()));assertTrue(f.remote.isEmpty());assertTrue(f.client.getFirst().has("error"));
            var result=source("fmt/print.go",TEXT);result.addProperty("id","unsolicited");var packet=json("{method:'goforge/stdlibSource'}");packet.add("params",result);assertTrue(f.nav.incoming(packet));assertEquals(1,f.client.size());
        }
    }
}

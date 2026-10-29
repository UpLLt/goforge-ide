package dev.goforge.remote;

import com.google.gson.*;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.Assert.*;

public class GoRemoteModuleSourcesTest {
    static final String TOKEN="b".repeat(64), MODULE="example.test/Upper/dep", VERSION="v1.1.0";
    static final String ROOT="/cache/example.test/!upper/dep@v1.1.0", TEXT="package dep\nfunc Value() int { return 2 }\n";
    static JsonObject catalog(){return json("{cacheRoot:'/cache',modules:[{path:'"+MODULE+"',version:'"+VERSION+"',root:'"+ROOT+"',token:'"+TOKEN+"'}]}");}
    static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    static JsonObject data(String path,String token,String text){
        var result=new JsonObject();byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        result.addProperty("path",path);result.addProperty("token",token);result.addProperty("data",Base64.getEncoder().encodeToString(bytes));result.addProperty("sha256",GoRemoteFiles.hash(bytes));return result;
    }
    private static GoRemoteStdlibSources store(){var store=new GoRemoteStdlibSources();store.initialize(json("{root:'/sdk/src',token:'"+"a".repeat(64)+"',version:'go1.fixture'}"));store.initializeModules(catalog());return store;}
    @Test public void onlySelectedExactEscapedRootCanResolveAndOriginIsTyped(){
        try(var store=store()){
            var target=store.target("file://"+ROOT+"/dep.go");
            assertEquals(GoRemoteStdlibSources.Kind.MODULE,target.origin().kind());assertEquals("example.test/Upper/dep@v1.1.0",target.origin().label());
            var source=store.accept(target,data("dep.go",TOKEN,TEXT));assertEquals(ROOT+"/dep.go",source.remotePath());assertEquals(TEXT,source.text());
            for(String uri:List.of("file:///cache/example.test/!upper/dep@v1.0.0/dep.go","file:///cache/example.test/Upper/dep@v1.1.0/dep.go",
                    "file://host"+ROOT+"/dep.go","file://"+ROOT+"/../secret.go","file://"+ROOT+"/x%2fdep.go","file://"+ROOT+"/go.mod"))
                assertThrows(uri,IllegalArgumentException.class,()->store.target(uri));
            var wrong=data("dep.go","c".repeat(64),TEXT);assertThrows(IllegalArgumentException.class,()->store.accept(target,wrong));
        }
    }
    @Test public void ambiguousCatalogAndForgedCapabilitiesAreRejected(){
        var badRoot=catalog();badRoot.getAsJsonArray("modules").get(0).getAsJsonObject().addProperty("root","/cache/example.test/Upper/dep@v1.1.0");
        var badVersion=catalog();badVersion.getAsJsonArray("modules").get(0).getAsJsonObject().addProperty("version","v1.0.0");
        var replacement=catalog();replacement.getAsJsonArray("modules").get(0).getAsJsonObject().addProperty("replace","/elsewhere");
        for(JsonObject invalid:List.of(badRoot,badVersion,replacement)){
            try(var store=new GoRemoteStdlibSources()){assertThrows(IllegalArgumentException.class,()->store.initializeModules(invalid));}
        }
        try(var store=store()){
            var forged=new GoRemoteStdlibSources.Target(new GoRemoteStdlibSources.Origin(GoRemoteStdlibSources.Kind.MODULE,MODULE,VERSION),"/outside",TOKEN,"dep.go");
            assertThrows(IllegalArgumentException.class,()->store.accept(forged,data("dep.go",TOKEN,TEXT)));
        }
        var duplicate=catalog();duplicate.getAsJsonArray("modules").add(duplicate.getAsJsonArray("modules").get(0).deepCopy());
        try(var store=new GoRemoteStdlibSources()){assertThrows(IllegalArgumentException.class,()->store.initializeModules(duplicate));}
    }
    @Test public void delayedUnavailableRepeatedAndExpiredCatalogNeverRevokeSdkOrGrantModule() {
        try(var sources=new GoRemoteStdlibSources()) {
            sources.initialize(json("{root:'/sdk/src',token:'"+"a".repeat(64)+"',version:'go1.fixture'}"));
            assertTrue(assertThrows(IllegalArgumentException.class,()->sources.target("file://"+ROOT+"/dep.go")).getMessage().contains("still loading"));
            sources.initializeModules(json("{error:'Dependency discovery timed out'}"));
            assertTrue(assertThrows(IllegalArgumentException.class,()->sources.target("file://"+ROOT+"/dep.go")).getMessage().contains("timed out"));
            assertEquals(GoRemoteStdlibSources.Kind.SDK,sources.target("file:///sdk/src/fmt/print.go").origin().kind());
            assertThrows(IllegalArgumentException.class,()->sources.initializeModules(catalog()));
            sources.accept("fmt/print.go",data("fmt/print.go","a".repeat(64),TEXT));
            sources.close();assertThrows(IllegalArgumentException.class,()->sources.initializeModules(catalog()));
        }
        try(var newSession=new GoRemoteStdlibSources()) {
            assertTrue(assertThrows(IllegalArgumentException.class,()->newSession.target("file://"+ROOT+"/dep.go")).getMessage().contains("still loading"));
        }
    }
    @Test public void sdkAndModulesShareFileCountAndByteBudgets(){
        try(var store=store()){
            for(int i=0;i<127;i++)store.accept("fmt/f"+i+".go",data("fmt/f"+i+".go","a".repeat(64),"package fmt\n"));
            store.accept(store.target("file://"+ROOT+"/dep.go"),data("dep.go",TOKEN,TEXT));
            assertThrows(IllegalArgumentException.class,()->store.accept(store.target("file://"+ROOT+"/extra.go"),data("extra.go",TOKEN,TEXT)));
        }
        try(var store=store()){
            String text="x".repeat(GoRemoteStdlibSources.FILE_LIMIT);
            for(int i=0;i<16;i++)store.accept("fmt/f"+i+".go",data("fmt/f"+i+".go","a".repeat(64),text));
            assertThrows(IllegalArgumentException.class,()->store.accept(store.target("file://"+ROOT+"/dep.go"),data("dep.go",TOKEN,TEXT)));
        }
    }
    private static final class Fixture implements AutoCloseable {
        final GoRemoteStdlibSources sources=store();
        final GoRemoteLspMapping mapping=new GoRemoteLspMapping(Path.of(System.getProperty("java.io.tmpdir"),"module fixture"),"/owned/project");
        final List<JsonObject> remote=new ArrayList<>(),client=new ArrayList<>();
        final GoRemoteStdlibNavigation nav=new GoRemoteStdlibNavigation(mapping,sources,remote::add,client::add);
        final String origin=Path.of(mapping.localRoot()).resolve("main.go").toUri().toASCIIString();
        JsonObject request(int id)throws Exception{
            var request=json("{id:"+id+",method:'textDocument/definition',params:{textDocument:{uri:"+new Gson().toJson(origin)+"},position:{line:0,character:0}}}");
            return nav.outgoing(request,mapping.rewrite(request,true).getAsJsonObject());
        }
        void respond(JsonObject request,String uri)throws Exception{
            var response=json("{result:[{uri:'"+uri+"',range:{start:{line:1,character:5},end:{line:1,character:10}}}]}");response.add("id",request.get("id"));assertTrue(nav.incoming(response));
        }
        void fetched(String method)throws Exception{
            var query=remote.getLast().getAsJsonObject("params");var result=data(query.get("path").getAsString(),query.get("token").getAsString(),TEXT);result.add("id",query.get("id"));
            var response=json("{method:'"+method+"'}");response.add("params",result);assertTrue(nav.incoming(response));
        }
        public void close(){nav.close();}
    }
    @Test public void moduleResponseIsBoundToOriginalDefinitionAndMapperRemainsStrict()throws Exception{
        try(var f=new Fixture()){
            f.respond(f.request(17),"file://"+ROOT+"/dep.go");assertEquals("goforge/readModuleSource",f.remote.getFirst().get("method").getAsString());
            f.fetched("goforge/moduleSource");var response=f.client.getFirst();assertEquals(17,response.get("id").getAsInt());
            String uri=response.getAsJsonArray("result").get(0).getAsJsonObject().get("uri").getAsString();assertEquals(TEXT,f.sources.find(uri).text());
            assertThrows(IllegalArgumentException.class,()->f.mapping.uri(uri,true));
            assertThrows(IllegalArgumentException.class,()->f.mapping.rewrite(json("{edit:{changes:{'file://"+ROOT+"/dep.go':[]}}}"),false));
        }
    }
    @Test public void wrongResponseKindCannotSubstituteSdkOrModulePermission()throws Exception{
        try(var f=new Fixture()){
            f.respond(f.request(1),"file://"+ROOT+"/dep.go");f.fetched("goforge/stdlibSource");assertTrue(f.client.getFirst().has("error"));
            assertThrows(IllegalArgumentException.class,()->f.sources.target("file:///cache/unselected@v1.0.0/x.go"));
        }
    }
    @Test public void overlayCancellationAndStopInvalidateOutstandingModuleReads()throws Exception{
        for(String mode:List.of("change","cancel","stop"))try(var f=new Fixture()){
            var request=f.request(9);f.respond(request,"file://"+ROOT+"/dep.go");
            if(mode.equals("stop"))f.nav.close();
            else{
                var packet=mode.equals("cancel")?json("{method:'$/cancelRequest',params:{id:9}}"):
                        json("{method:'textDocument/didChange',params:{textDocument:{uri:"+new Gson().toJson(f.origin)+",version:2},contentChanges:[{text:'changed'}]}}");
                f.nav.outgoing(packet,f.mapping.rewrite(packet,true).getAsJsonObject());
            }
            int responses=f.client.size();f.fetched("goforge/moduleSource");assertEquals(responses,f.client.size());
            assertTrue(f.client.isEmpty()||f.client.getFirst().has("error"));
        }
    }
}

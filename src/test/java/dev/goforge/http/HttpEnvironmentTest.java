package dev.goforge.http;

import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public final class HttpEnvironmentTest {
    @Test public void explicitFilesPrivatePrecedenceNestedLeavesAndEnvironmentSwitch()throws Exception{
        Path root=Files.createTempDirectory("goforge-http-env-");try{
            Path normal=root.resolve("env.json"),secret=root.resolve("private.json");Files.writeString(normal,"{\"dev\":{\"api\":{\"host\":\"http://localhost:1\"},\"token\":\"public\",\"enabled\":true},\"test\":{\"api.host\":\"http://localhost:2\"}}");Files.writeString(secret,"{\"dev\":{\"token\":\"fixture-private\"}}");
            var env=HttpEnvironment.load(normal,secret);assertEquals(List.of("dev","test"),env.names());assertEquals("fixture-private",env.select("dev").variables().get("token"));assertEquals("true",env.select("dev").variables().get("enabled"));assertEquals("http://localhost:2",env.select("test").variables().get("api.host"));assertFalse(env.select("test").variables().containsKey("token"));assertTrue(env.select("dev").secrets().contains("fixture-private"));assertFalse(env.toString().contains("fixture-private"));
        }finally{delete(root);}
    }
    @Test public void aliasesResolveWithoutPrintingPrivateValues()throws Exception{
        Path root=Files.createTempDirectory("goforge-http-env-");try{Path file=root.resolve("private.json");Files.writeString(file,"{\"dev\":{\"token\":\"fixture-secret\",\"auth\":\"Bearer {{token}}\"}}");var selected=HttpEnvironment.load(null,file).select("dev");assertEquals("Bearer fixture-secret",selected.variables().get("auth"));assertTrue(selected.secrets().contains("Bearer fixture-secret"));assertEquals("dev",selected.toString());}finally{delete(root);}
    }
    @Test public void snapshotsAreReadOnlyAndDoNotFollowImplicitFiles()throws Exception{
        Path root=Files.createTempDirectory("goforge-http-env-");try{Path file=root.resolve("http-client.private.env.json");String bytes="{\"dev\":{\"token\":\"first\"}}";Files.writeString(file,bytes);assertTrue(HttpEnvironment.load(null,null).names().isEmpty());var env=HttpEnvironment.load(null,file);Files.writeString(file,"{\"dev\":{\"token\":\"second\"}}");assertEquals("first",env.select("dev").variables().get("token"));assertEquals("second",HttpEnvironment.load(null,file).select("dev").variables().get("token"));assertEquals(1,Files.list(root).count());}finally{delete(root);}
    }
    @Test public void missingEnvironmentMalformedJsonAndUnsupportedLeavesFailWithoutContents()throws Exception{
        Path root=Files.createTempDirectory("goforge-http-env-");try{Path file=root.resolve("private.json");for(String text:List.of("{fixture-secret", "{\"dev\":{\"x\":[\"fixture-secret\"]}}","{\"dev\":{\"x\":null}}","{\"No environment\":{}}")){Files.writeString(file,text);var error=assertThrows(Exception.class,()->HttpEnvironment.load(null,file));assertFalse(error.getMessage().contains("fixture-secret"));}assertThrows(IllegalArgumentException.class,()->HttpEnvironment.empty().select("missing"));}finally{delete(root);}
    }
    @Test public void inlineOverrideAndMissingVariableAreValidatedBeforeRequests(){
        var request=HttpRequestFile.parse("@host = http://localhost:3\nGET {{host}}/\nX-Token: {{token}}\n",Map.of("host","http://localhost:1","token","fixture-only")).getFirst();assertEquals(3,request.uri().getPort());assertEquals("fixture-only",request.headers().getFirst().value());
        assertThrows(IllegalArgumentException.class,()->HttpRequestFile.parse("GET {{missing}}/",Map.of()));
    }
    @Test public void privacyHandlesEscapingAndShortSecretsWithoutRecursiveGrowth(){
        var filter=new HttpPrivacy(List.of("a","secret /value","token\"value"));String redacted=filter.redact("a secret%20%2Fvalue token\\\"value");assertEquals("[redacted] [redacted] [redacted]",redacted);assertFalse(filter.header("Set-Cookie","session=unknown").contains("unknown"));
    }
    @Test public void flattenedCollisionsAreRejected()throws Exception{
        Path root=Files.createTempDirectory("goforge-http-env-");try{Path file=root.resolve("env.json");Files.writeString(file,"{\"dev\":{\"api.host\":\"one\",\"api\":{\"host\":\"two\"}}}");assertThrows(IllegalArgumentException.class,()->HttpEnvironment.load(file,null));}finally{delete(root);}
    }
    private static void delete(Path root)throws Exception{try(var files=Files.walk(root)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
}

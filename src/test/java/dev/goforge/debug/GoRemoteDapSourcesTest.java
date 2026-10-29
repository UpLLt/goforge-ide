package dev.goforge.debug;

import org.eclipse.lsp4j.debug.Source;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

public final class GoRemoteDapSourcesTest {
    static String hash(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    static Source source(String path,int ref,String text)throws Exception{
        var source=new Source();source.setName(path);source.setSourceReference(ref);source.setOrigin("Selected remote SDK go1.26.4");
        source.setAdapterData(new LinkedHashMap<>(Map.of("goforgeSdkSource",1,"relative",path,"version","go1.26.4","sha256",hash(text))));return source;
    }
    @Test public void exactCapabilityAndUnicodeContentAreAccepted()throws Exception{
        String text="package fmt\n// 世界\n";var id=GoRemoteDapSources.identity(source("fmt/print.go",1000000,text));
        assertEquals("fmt/print.go",id.relative());assertEquals(text.getBytes(StandardCharsets.UTF_8).length,GoRemoteDapSources.verify(id,text));
    }
    @Test public void DeserializedWireMarkerOneIsAcceptedEvenWhenObjectNumbersAreDoubles()throws Exception{
        var original=source("fmt/print.go",1000000,"package fmt\n");
        var decoded=new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(original),Source.class);
        assertEquals("fmt/print.go",GoRemoteDapSources.identity(decoded).relative());
    }
    @Test public void traversalAbsoluteControlsAndNonGoPathsAreRejected()throws Exception{
        for(String p:List.of("/fmt/print.go","../a.go","fmt/../a.go","fmt//a.go","fmt\\a.go","fmt/\na.go","fmt/a.txt"))
            assertThrows(IllegalArgumentException.class,()->GoRemoteDapSources.identity(source(p,1000000,"x")));
    }
    @Test public void ReferenceAndExactOriginCannotBeReplaced()throws Exception{
        for(int ref:List.of(0,1,999999,1000128))assertThrows(IllegalArgumentException.class,()->GoRemoteDapSources.identity(source("fmt/a.go",ref,"x")));
        var a=source("fmt/a.go",1000000,"x");a.setPath("/tmp/a.go");assertThrows(IllegalArgumentException.class,()->GoRemoteDapSources.identity(a));
        var b=source("fmt/a.go",1000000,"x");b.setOrigin("Remote SDK");assertThrows(IllegalArgumentException.class,()->GoRemoteDapSources.identity(b));
    }
    @Test public void MetadataRequiresExactTypesAndVersionMarker()throws Exception{
        for(Object marker:List.of("1",1.5,true)){
            var s=source("fmt/a.go",1000000,"x");@SuppressWarnings("unchecked")var data=(Map<String,Object>)s.getAdapterData();data.put("goforgeSdkSource",marker);
            assertThrows(IllegalArgumentException.class,()->GoRemoteDapSources.identity(s));
        }
        var s=source("fmt/a.go",1000000,"x");@SuppressWarnings("unchecked")var data=(Map<String,Object>)s.getAdapterData();data.put("extra","untrusted");assertThrows(IllegalArgumentException.class,()->GoRemoteDapSources.identity(s));
    }
    @Test public void ChangedNullNulAndMalformedUnicodeContentAreRejected()throws Exception{
        var id=GoRemoteDapSources.identity(source("fmt/a.go",1000000,"package fmt\n"));
        for(String value:List.of("changed","a\0b","\uD800"))assertThrows(IllegalArgumentException.class,()->GoRemoteDapSources.verify(id,value));
        assertThrows(IllegalArgumentException.class,()->GoRemoteDapSources.verify(id,null));
    }
    @Test public void ByteLimitIsNotOnlyACharacterLimit()throws Exception{
        String value="界".repeat(GoRemoteDapSources.FILE_LIMIT/3+1);var id=GoRemoteDapSources.identity(source("fmt/a.go",1000000,value));
        assertThrows(IllegalArgumentException.class,()->GoRemoteDapSources.verify(id,value));
    }
}

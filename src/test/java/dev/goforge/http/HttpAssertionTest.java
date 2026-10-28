package dev.goforge.http;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public final class HttpAssertionTest {
    private HttpRequestEngine.Result response(String json,boolean truncated){return new HttpRequestEngine.Result(201,Map.of("Content-Type",List.of("application/json"),"X-Many",List.of("one","two")),new HttpRequestEngine.Body(json.getBytes(StandardCharsets.UTF_8),truncated),1,"HTTP_1_1");}
    @Test public void directivesAttachToTheirRequestAndDoNotEnterBody(){
        var requests=HttpRequestFile.parse("# @expect status 201\n# @expect header Content-Type \"application/json\"\n# @expect json /name \"{{name}}\"\nPOST http://localhost/\n\n{\"name\":\"{{name}}\"}\n### Next\n# @expect status 204\nDELETE http://localhost/\n",Map.of("name","fixture"));
        assertEquals(3,requests.getFirst().assertions().size());assertEquals(1,requests.get(1).assertions().size());assertEquals("{\"name\":\"fixture\"}",new String(requests.getFirst().body(),StandardCharsets.UTF_8));assertTrue(requests.getFirst().assertions().stream().allMatch(a->a.evaluate(response("{\"name\":\"fixture\"}",false)).passed()));
    }
    @Test public void typedJsonPointersSupportArraysEscapesAndRoot(){
        var r=response("{\"a/b\":[{\"~key\":null}],\"ok\":true,\"n\":2}",false);
        assertTrue(HttpAssertion.parse("json /a~1b/0/~0key null",0,Map.of()).evaluate(r).passed());assertTrue(HttpAssertion.parse("json /ok true",0,Map.of()).evaluate(r).passed());assertTrue(HttpAssertion.parse("json /n 2",0,Map.of()).evaluate(r).passed());assertTrue(HttpAssertion.parse("json \"\" {\"a/b\":[{\"~key\":null}],\"ok\":true,\"n\":2}",0,Map.of()).evaluate(r).passed());assertFalse(HttpAssertion.parse("json /n \"2\"",0,Map.of()).evaluate(r).passed());
    }
    @Test public void missingInvalidAndTruncatedJsonCannotPass(){
        var a=HttpAssertion.parse("json /ok true",1,Map.of());assertFalse(a.evaluate(response("{}",false)).passed());assertFalse(a.evaluate(response("not json",false)).passed());assertFalse(a.evaluate(response("{\"ok\":true}",true)).passed());
    }
    @Test public void headerNamesAreInsensitiveAndValuesNeverAppearInFailure(){
        assertTrue(HttpAssertion.parse("header x-many \"two\"",0,Map.of()).evaluate(response("{}",false)).passed());var failed=HttpAssertion.parse("header Authorization \"fixture-secret\"",3,Map.of()).evaluate(response("{}",false));assertFalse(failed.passed());assertFalse(failed.toString().contains("fixture-secret"));assertEquals(3,failed.sourceLine());
    }
    @Test public void rejectsUnsupportedCodeAndMalformedDeclarations(){
        for(String value:List.of("status true","script alert(1)","json $.name 1","json /a~2 1","header X unquoted","status 999"))assertThrows(value,IllegalArgumentException.class,()->HttpAssertion.parse(value,0,Map.of()));
        assertThrows(IllegalArgumentException.class,()->HttpRequestFile.parse("# @expect\nGET http://localhost/"));
    }
    @Test public void assertionExpectedSecretDoesNotLeakInObjectOrResult(){
        var assertion=HttpAssertion.parse("json /token \"{{secret}}\"",0,Map.of("secret","fixture-private"));assertFalse(assertion.toString().contains("fixture-private"));var result=assertion.evaluate(response("{\"token\":\"other\"}",false));assertFalse(result.toString().contains("fixture-private"));assertFalse(result.toString().contains("other"));
    }
}

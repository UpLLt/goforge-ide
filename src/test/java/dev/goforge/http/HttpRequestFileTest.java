package dev.goforge.http;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class HttpRequestFileTest {
    @Test public void parsesMultipleRequestsVariablesHeadersAndBodies() {
        var requests = HttpRequestFile.parse("@host = http://localhost:8080\n@base = {{host}}/v1\n### Create\nPOST {{base}}/items HTTP/1.1\nContent-Type: application/json\nX-Trace: one\nX-Trace: two\n\n{\"name\":\"GoForge\"}\n### Read\nGET {{base}}/items\n");
        assertEquals(2, requests.size());
        assertEquals("Create", requests.getFirst().name());
        assertEquals("http://localhost:8080/v1/items", requests.getFirst().uri().toString());
        assertEquals(3, requests.getFirst().headers().size());
        assertEquals("{\"name\":\"GoForge\"}", new String(requests.getFirst().body(), StandardCharsets.UTF_8));
        assertEquals("1.1", requests.getFirst().version());
        assertEquals("GET", requests.get(1).method());
        assertEquals(0, requests.get(1).body().length);
    }
    @Test public void retainsEmptyLinesAndUnicodeBody() {
        var request = HttpRequestFile.parse("POST http://localhost/\r\n\r\n你好\r\n\r\nworld\r\n").getFirst();
        assertEquals("你好\n\nworld\n", new String(request.body(), StandardCharsets.UTF_8));
    }
    @Test public void rejectsUndefinedAndCyclicVariables() {
        for (String source : new String[]{"GET {{missing}}", "@a = {{b}}\n@b = {{a}}\nGET {{a}}"}) rejected(source);
    }
    @Test public void rejectsNonHttpAndCredentialUrls() {
        for (String url : new String[]{"file:///etc/passwd", "http://user:secret@host/", "/relative", "https://host/#fragment", "http://host:99999/"}) rejected("GET " + url);
    }
    @Test public void refusesUnsupportedHttpVersionAndPreservesLeadingBodyBlankLines() {
        rejected("GET http://localhost/ HTTP/1.0");
        var request = HttpRequestFile.parse("POST http://localhost/\n\n\nfirst").getFirst();
        assertEquals("\nfirst", new String(request.body(), StandardCharsets.UTF_8));
    }
    @Test public void refusesScriptsAndIncludesInsteadOfSendingThemAsBodies() {
        for (String body : new String[]{"< ./secret", "> {% script %}", "{% requestScript %}"})
            rejected("POST http://localhost/\n\n" + body);
    }
    @Test public void respectsRequestBoundariesAndSourceLines() {
        var requests = HttpRequestFile.parse("# comment\nGET http://localhost/one\n\n### second\nDELETE http://localhost/two\n");
        assertEquals(1, requests.getFirst().startLine());
        assertEquals(3, requests.getFirst().endLine());
        assertEquals(4, requests.get(1).startLine());
    }
    @Test public void rejectsMalformedHeadersAndUnboundedExpansion() {
        rejected("GET http://localhost/\nnot a header");
        rejected("@a = " + "x".repeat(1_000_000) + "\n@b = {{a}}{{a}}\nGET http://localhost/{{b}}{{b}}");
    }
    @Test public void recognizesBothNoRedirectCommentFormsBeforeTheRequest() {
        for(String comment:new String[]{"// @no-redirect","# @no-redirect","  // @no-redirect  "}){
            var request=HttpRequestFile.parse(comment+"\nGET http://localhost/next").getFirst();
            assertTrue(request.noRedirect());assertEquals(1,request.startLine());assertEquals(0,request.body().length);
        }
    }
    @Test public void noRedirectBelongsOnlyToItsRequestAndNotAnEmptyEarlierBlock() {
        var requests=HttpRequestFile.parse("// @no-redirect\nGET http://localhost/one\n### two\nGET http://localhost/two\n### empty\n# @no-redirect\n### three\nGET http://localhost/three");
        assertEquals(3,requests.size());assertTrue(requests.getFirst().noRedirect());
        assertFalse(requests.get(1).noRedirect());assertFalse(requests.get(2).noRedirect());
    }
    @Test public void noRedirectCoexistsWithAssertionsVariablesAndOrdinaryComments() {
        var request=HttpRequestFile.parse("@host = http://localhost\n# Ordinary comment\n# @expect status 302\n// @no-redirect\n# @expect header Location \"/next\"\nGET {{host}}/start").getFirst();
        assertTrue(request.noRedirect());assertEquals(2,request.assertions().size());
        assertEquals("http://localhost/start",request.uri().toString());
        assertFalse(HttpRequestFile.parse("// This description mentions @no-redirect\n# Another normal comment\nGET http://localhost/").getFirst().noRedirect());
    }
    @Test public void rejectsMalformedNoRedirectInsteadOfSilentlyFollowing() {
        for(String directive:new String[]{"# @no-redirect true","// @no-redirect false","# @no-redirect=1","// @no-redirects","# @no-redirect extra text"}){
            var error=assertThrows(IllegalArgumentException.class,()->HttpRequestFile.parse(directive+"\nGET http://localhost/"));
            assertTrue(error.getMessage().startsWith("Line 1: Malformed @no-redirect"));
        }
    }
    @Test public void headerValuesAndBodyDirectiveTextDoNotChangeRedirectPolicy() {
        var request=HttpRequestFile.parse("POST http://localhost/\nX-Note: # @no-redirect\n\n// @no-redirect\n# @no-redirect true").getFirst();
        assertFalse(request.noRedirect());assertEquals("# @no-redirect",request.headers().getFirst().value());
        assertEquals("// @no-redirect\n# @no-redirect true",new String(request.body(),StandardCharsets.UTF_8));
        rejected("GET http://localhost/\n// @no-redirect");
        rejected("GET http://localhost/\n# @no-redirect");
    }
    @Test public void existingRequestConstructorsKeepRedirectOptOutDisabled() {
        var uri=java.net.URI.create("http://localhost/");
        var plain=new HttpRequestFile.Request("name","GET",uri,java.util.List.of(),new byte[0],0,1,null);
        var assertions=java.util.List.of(HttpAssertion.parse("status 200",0,java.util.Map.of()));
        var checked=new HttpRequestFile.Request("name","GET",uri,java.util.List.of(),new byte[0],0,1,null,assertions);
        var optedOut=new HttpRequestFile.Request("name","GET",uri,java.util.List.of(),new byte[0],0,1,null,assertions,true);
        assertFalse(plain.noRedirect());assertFalse(checked.noRedirect());assertTrue(optedOut.noRedirect());
        assertEquals(assertions,checked.assertions());assertEquals(assertions,optedOut.assertions());
    }
    private static void rejected(String source) {
        assertThrows(IllegalArgumentException.class, () -> HttpRequestFile.parse(source));
    }
}

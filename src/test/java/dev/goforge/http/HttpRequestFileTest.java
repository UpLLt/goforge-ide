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
    private static void rejected(String source) {
        assertThrows(IllegalArgumentException.class, () -> HttpRequestFile.parse(source));
    }
}

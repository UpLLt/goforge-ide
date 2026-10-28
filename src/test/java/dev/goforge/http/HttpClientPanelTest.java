package dev.goforge.http;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class HttpClientPanelTest extends BasePlatformTestCase {
    public void testCaseInsensitiveMediaTypeAndExplicitCharacterSet() {
        var latin = new HttpRequestEngine.Result(200, Map.of("Content-Type", List.of("Text/Plain; Charset=ISO-8859-1")),
                new HttpRequestEngine.Body(new byte[]{(byte) 0xe9}, false), 1, "HTTP_1_1");
        assertEquals("é", HttpClientPanel.displayBody(latin));
        var json = new HttpRequestEngine.Result(200, Map.of("content-type", List.of("Application/JSON")),
                new HttpRequestEngine.Body("{}".getBytes(StandardCharsets.UTF_8), false), 1, "HTTP_1_1");
        assertEquals("{}", HttpClientPanel.displayBody(json));
    }
    public void testBinaryResponseIsNotRenderedAsCorruptText() {
        var result = new HttpRequestEngine.Result(200, Map.of(), new HttpRequestEngine.Body(new byte[]{0, 1, 2}, false), 1, "HTTP_1_1");
        assertTrue(HttpClientPanel.displayBody(result).startsWith("Binary response (3 bytes)"));
    }
}

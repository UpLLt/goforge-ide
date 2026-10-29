package dev.goforge.testing;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public final class GoTestSourceIndexTest {
    @Test public void utf8OffsetsMapExactlyAcrossCrLfUnicodeAndSurrogatePairs() throws Exception {
        String text = "a\r\n前🙂func Test名";
        for (int i = 0; i <= text.length(); i++) {
            if (i > 0 && i < text.length() && Character.isLowSurrogate(text.charAt(i))) continue;
            assertEquals(i, GoTestSourceIndex.utf8ToUtf16(text, text.substring(0, i).getBytes(StandardCharsets.UTF_8).length));
        }
        assertThrows(IOException.class, () -> GoTestSourceIndex.utf8ToUtf16(text, 5));
        assertThrows(IOException.class, () -> GoTestSourceIndex.utf8ToUtf16(text, -1));
        assertThrows(IOException.class, () -> GoTestSourceIndex.utf8ToUtf16(text, 1000));
        assertThrows(IOException.class, () -> GoTestSourceIndex.utf8ToUtf16("bad\ud800", 4));
    }
    @Test public void declaredIdentifierMustMatchActualSourceBytes() throws Exception {
        String text = "package p\r\n// 🙂\r\nfunc Test前(t *testing.T) {}\r\n";
        int start = text.indexOf("func"), name = text.indexOf("Test前"), end = text.indexOf('}') + 1;
        String json = "{\"passed\":true,\"declarations\":[{\"name\":\"Test前\",\"kind\":\"test\",\"start\":"
                + bytes(text,start) + ",\"end\":" + bytes(text,end) + ",\"nameStart\":" + bytes(text,name) + "}]}";
        var snapshot = GoTestSourceIndex.decode(Path.of("fixture_test.go"), text, json);
        assertEquals(name, snapshot.named("Test前").nameOffset());
        assertSame(snapshot.named("Test前"), snapshot.containing(end - 1));
        assertNull(snapshot.containing(end));
        assertNull(snapshot.containing(start - 1));
        assertThrows(IOException.class, () -> GoTestSourceIndex.decode(Path.of("fixture_test.go"), text, json.replace("Test前", "TestElse")));
    }
    @Test public void malformedOrFailedOutputNeverBecomesPackageFallback() {
        assertThrows(IOException.class, () -> GoTestSourceIndex.decode(Path.of("x_test.go"), "package x", "{broken"));
        assertThrows(IOException.class, () -> GoTestSourceIndex.decode(Path.of("x_test.go"), "package x", "{\"passed\":false,\"reason\":\"syntax error\"}"));
        assertThrows(IOException.class, () -> GoTestSourceIndex.decode(Path.of("x_test.go"), "package x", "{\"passed\":true}"));
    }
    @Test public void snapshotsKeepFileIdentityAndImmutableNames() {
        var left = new GoTestSourceIndex.Snapshot(Path.of("left/a_test.go"), "hash", "text",
                List.of(new GoTestSourceIndex.Declaration("TestSame", "test", 0, 4, 0)));
        var right = new GoTestSourceIndex.Snapshot(Path.of("right/a_test.go"), "hash", "text", left.declarations());
        assertNotEquals(left.file(), right.file());
        assertThrows(UnsupportedOperationException.class, () -> left.declarations().clear());
        assertNull(left.named("Unknown"));
    }
    @Test public void helperBuildNeverInheritsGoFlagsWorkspaceOrCrossCompilation() {
        var inherited = Map.of("GOFLAGS", "-exec malicious -run TestWrong", "GOWORK", "/private/work", "GOOS", "windows", "GOARCH", "386", "PATH", "fixture-path");
        var actual = GoTestSourceIndex.helperEnvironment(inherited);
        assertEquals("", actual.get("GOFLAGS")); assertEquals("off", actual.get("GOWORK"));
        assertEquals("", actual.get("GOOS")); assertEquals("", actual.get("GOARCH"));
        assertEquals("local", actual.get("GOTOOLCHAIN")); assertEquals("off", actual.get("GOENV"));
        assertEquals("fixture-path", actual.get("PATH")); assertEquals("windows", inherited.get("GOOS"));
    }
    @Test public void parserInputUsesTheSameLineSeparatorsAsAnEditorDocument() {
        assertEquals("package p\n// 前🙂\nfunc TestA() {}\n", GoTestSourceIndex.normalizeText("package p\r\n// 前🙂\rfunc TestA() {}\r\n"));
    }
    private static int bytes(String s, int offset) { return s.substring(0, offset).getBytes(StandardCharsets.UTF_8).length; }

    @Test public void decoderPreservesBenchmarkKindAndRefusesNameKindConfusion() throws Exception {
        String text="package p\nfunc BenchmarkOne(b *testing.B) {}\n";
        String json="{\"passed\":true,\"declarations\":[{\"name\":\"BenchmarkOne\",\"kind\":\"benchmark\",\"start\":10,\"end\":43,\"nameStart\":15}]}";
        var snapshot=GoTestSourceIndex.decode(Path.of("x_test.go"),text,json);
        assertEquals("benchmark",snapshot.containing(20).kind());assertTrue(GoTestSelection.benchmark("BenchmarkOne").matchesDeclaration(snapshot.named("BenchmarkOne")));
        assertFalse(GoTestSelection.single("TestOne").matchesDeclaration(snapshot.named("BenchmarkOne")));
        assertThrows(IOException.class,()->GoTestSourceIndex.decode(Path.of("x_test.go"),text,json.replace("\"kind\":\"benchmark\"","\"kind\":\"test\"")));
        var unavailable=GoTestSourceIndex.decode(Path.of("x_test.go"),text,json.replace("\"kind\":\"benchmark\"","\"kind\":\"unsupported-benchmark\""));
        assertFalse(GoTestSelection.benchmark("BenchmarkOne").matchesDeclaration(unavailable.named("BenchmarkOne")));
    }

}

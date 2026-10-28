package dev.goforge.coverage;

import com.google.gson.Gson;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public final class GoCoverageSourceMapTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void sameBasenameInDifferentPackagesResolvesOnlyByExactImportPath() throws Exception {
        Path root = temporary.newFolder("module").toPath().toRealPath();
        Path sub = Files.createDirectory(root.resolve("sub"));
        Files.writeString(root.resolve("same.go"), "package root\n"); Files.writeString(sub.resolve("same.go"), "package sub\n");
        var map = GoCoverageSourceMap.parse(new StringReader(pkg(root, root, "example.test/m", "same.go")
                + pkg(root, sub, "example.test/m/sub", "same.go")), root);
        assertEquals(root.resolve("same.go"), map.resolve("example.test/m/same.go").path());
        assertEquals(sub.resolve("same.go"), map.resolve("example.test/m/sub/same.go").path());
        assertNull(map.resolve("same.go"));
        assertNull(map.resolve("other.module/same.go"));
    }
    @Test public void outsideModuleAndTraversalMetadataAreRejected() throws Exception {
        Path root = temporary.newFolder("module").toPath();
        Path other = temporary.newFolder("other").toPath();
        Files.writeString(other.resolve("same.go"), "package p\n");
        assertThrows(IOException.class, () -> GoCoverageSourceMap.parse(new StringReader(pkg(other, other, "other", "same.go")), root));
        assertThrows(IOException.class, () -> GoCoverageSourceMap.parse(new StringReader(pkg(root, root, "m", "../other/same.go")), root));
    }
    @Test public void symbolicLinkCannotRedirectAProfileToAnotherModule() throws Exception {
        Path root = temporary.newFolder("module").toPath(), other = temporary.newFolder("other").toPath();
        Path target = Files.writeString(other.resolve("same.go"), "package p\n");
        Files.createSymbolicLink(root.resolve("same.go"), target);
        assertThrows(IOException.class, () -> GoCoverageSourceMap.parse(new StringReader(pkg(root, root, "m", "same.go")), root));
    }
    @Test public void capturedSourceIsStableWhenDiskChangesAndNormalizesCrLf() throws Exception {
        Path root = temporary.newFolder("module").toPath();
        Path file = Files.writeString(root.resolve("same.go"), "package p\r\n");
        var map = GoCoverageSourceMap.parse(new StringReader(pkg(root, root, "m", "same.go")), root);
        Files.writeString(file, "package changed\n");
        assertTrue(map.resolve("m/same.go").matches("package p\n"));
        assertFalse(map.resolve("m/same.go").matches(Files.readString(file)));
    }
    @Test public void malformedPackageJsonIsNotGuessed() throws Exception {
        Path root = temporary.newFolder("module").toPath();
        assertThrows(IOException.class, () -> GoCoverageSourceMap.parse(new StringReader("{broken"), root));
        assertThrows(IOException.class, () -> GoCoverageSourceMap.parse(new StringReader("{}"), root));
    }
    private static String pkg(Path module, Path directory, String importPath, String file) {
        return new Gson().toJson(Map.of("Dir", directory.toString(), "ImportPath", importPath,
                "Module", Map.of("Dir", module.toString()), "GoFiles", List.of(file))) + "\n";
    }
}

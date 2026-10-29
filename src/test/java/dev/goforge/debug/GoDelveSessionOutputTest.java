package dev.goforge.debug;

import org.junit.Test;
import java.nio.file.*;
import java.util.Map;
import static org.junit.Assert.*;

public class GoDelveSessionOutputTest {
    @Test public void onlyDefaultLocalGeneratedOutputsAreOwned() {
        assertTrue(GoDelveSessionOutput.required(Map.of("mode", "test"), true));
        assertTrue(GoDelveSessionOutput.required(Map.of("mode", "debug"), true));
        assertTrue(GoDelveSessionOutput.required(Map.of(), true));
        for (String mode : new String[]{"exec", "remote", "core", "replay"}) assertFalse(GoDelveSessionOutput.required(Map.of("mode", mode), true));
        assertFalse(GoDelveSessionOutput.required(Map.of("mode", "test"), false));
        assertFalse(GoDelveSessionOutput.required(Map.of("output", ""), true));
        assertFalse(GoDelveSessionOutput.required(Map.of("output", "mine.exe"), true));
    }
    @Test public void removesOnlyItsExactGeneratedFileAndEmptyDirectory() throws Exception {
        Path parent = Files.createTempDirectory("delve-output-test-");
        Path other = Files.writeString(parent.resolve("__debug_bin-user"), "preserve");
        var owned = GoDelveSessionOutput.create(parent);
        Files.writeString(owned.binary(), "generated");
        owned.close(); owned.close();
        assertFalse(Files.exists(owned.directory())); assertEquals("preserve", Files.readString(other));
        Files.delete(other); Files.delete(parent);
    }
    @Test public void absentOutputAndFailedBuildAreSafe() throws Exception {
        Path parent = Files.createTempDirectory("delve-output-test-");
        var owned = GoDelveSessionOutput.create(parent); owned.close();
        assertFalse(Files.exists(owned.directory())); Files.delete(parent);
    }
    @Test public void unexpectedFileIsRetainedAndCleanupFailureVisible() throws Exception {
        Path parent = Files.createTempDirectory("delve-output-test-"); var owned = GoDelveSessionOutput.create(parent);
        Files.writeString(owned.binary(), "generated"); Path other = Files.writeString(owned.directory().resolve("unrelated"), "preserve");
        assertThrows(java.io.IOException.class, owned::close);
        assertFalse(Files.exists(owned.binary())); assertEquals("preserve", Files.readString(other));
        Files.delete(other); Files.delete(owned.directory()); Files.delete(parent);
    }
    @Test public void nonRegularOutputIsRetained() throws Exception {
        Path parent = Files.createTempDirectory("delve-output-test-"); var owned = GoDelveSessionOutput.create(parent);
        Files.createDirectory(owned.binary());
        assertThrows(java.io.IOException.class, owned::close); assertTrue(Files.isDirectory(owned.binary()));
        Files.delete(owned.binary()); Files.delete(owned.directory()); Files.delete(parent);
    }
    @Test public void movedDirectoryCannotCauseCleanupOfItsReplacement() throws Exception {
        Path parent = Files.createTempDirectory("delve-output-test-"); var owned = GoDelveSessionOutput.create(parent);
        Path moved = owned.directory().resolveSibling("moved"); Files.writeString(owned.binary(), "generated");
        if (System.getProperty("os.name").startsWith("Windows")) {
            assertThrows(java.io.IOException.class, () -> Files.move(owned.directory(), moved));
            owned.close();
        } else {
            Files.move(owned.directory(), moved); Files.createDirectory(owned.directory());
            Files.writeString(owned.binary(), "replacement");
            assertThrows(java.io.IOException.class, owned::close);
            assertEquals("replacement", Files.readString(owned.binary())); assertFalse(Files.exists(moved.resolve("debug")));
            Files.delete(owned.binary()); Files.delete(owned.directory()); Files.delete(moved);
        }
        Files.delete(parent);
    }
    @Test public void realPlatformLocalWrapperRetainsExactSafeCleanup() throws Exception {
        Path parent = GoDelveSessionOutput.localPath(Files.createTempDirectory("delve-routing-test-"));
        var provider = new com.intellij.platform.core.nio.fs.MultiRoutingFileSystemProvider(parent.getFileSystem().provider());
        Path wrapped = provider.wrapDelegatePath(parent);
        try (var wrapperStream = Files.newDirectoryStream(wrapped)) {
            assertFalse("Pinned wrapper erases the secure stream interface", wrapperStream instanceof SecureDirectoryStream<?>);
        }
        Path sibling = Files.writeString(parent.resolve("keep"), "preserved");
        var owned = GoDelveSessionOutput.create(wrapped);
        assertSame(parent.getFileSystem().provider(), owned.directory().getFileSystem().provider());
        Files.writeString(owned.binary(), "generated"); owned.close();
        assertFalse(Files.exists(owned.directory())); assertEquals("preserved", Files.readString(sibling));
        Files.delete(sibling); Files.delete(parent);
    }
    @Test public void realPlatformForeignBackendCannotBeUnwrappedAsLocal() throws Exception {
        Path parent = GoDelveSessionOutput.localPath(Files.createTempDirectory("delve-routing-test-"));
        Path archive = parent.resolve("foreign.zip");
        var provider = new com.intellij.platform.core.nio.fs.MultiRoutingFileSystemProvider(parent.getFileSystem().provider());
        Path wrapped = provider.wrapDelegatePath(parent);
        try (var foreign = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
            var routing = (com.intellij.platform.core.nio.fs.MultiRoutingFileSystem) wrapped.getFileSystem();
            routing.computeBackend(parent.toString(), true, true, (local, previous) -> foreign);
            assertSame(foreign, routing.getBackend(parent.toString()));
            assertThrows(java.io.IOException.class, () -> GoDelveSessionOutput.create(wrapped));
            try (var files = Files.list(parent)) { assertEquals(1, files.count()); }
            try (var files = Files.list(foreign.getPath("/"))) { assertEquals(0, files.count()); }
        }
        Files.delete(archive); Files.delete(parent);
    }
}

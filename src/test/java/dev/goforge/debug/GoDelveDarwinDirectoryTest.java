package dev.goforge.debug;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.junit.Test;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class GoDelveDarwinDirectoryTest {
    private static final Path DIRECTORY = Path.of("parent", "owned");
    private static final class FakeApi implements GoDelveDarwinDirectory.Api {
        final List<String> events = new ArrayList<>();
        boolean failMetadata, replaced;
        int fileMode = 0100644, unlinkError;
        public int open(String path, int flags) { assertEquals(0x01100100, flags); events.add("parent"); return 0; }
        public int openat(int fd, String name, int flags) { assertEquals(0, fd); assertEquals("owned", name); assertEquals(0x01100100, flags); events.add("child"); return 1; }
        public int fstat(int fd, Pointer stat) {
            assertEquals(1, fd);
            if (failMetadata) { Native.setLastError(13); return -1; }
            write(stat, 42, 0040700); return 0;
        }
        public int fstatat(int fd, String name, Pointer stat, int flags) {
            assertEquals(0x20, flags);
            if (fd == 0) { assertEquals("owned", name); write(stat, replaced ? 99 : 42, 0040700); }
            else { assertEquals(1, fd); assertEquals("debug", name); write(stat, 123, fileMode); }
            return 0;
        }
        public int unlinkat(int fd, String name, int flags) {
            events.add("unlink:" + fd + ":" + name + ":" + flags);
            if (unlinkError != 0) { Native.setLastError(unlinkError); return -1; }
            return 0;
        }
        public int close(int fd) { events.add("close:" + fd); Native.setLastError(99); return 0; }
        private static void write(Pointer stat, long inode, int mode) { stat.setInt(0, 7); stat.setShort(4, (short) mode); stat.setLong(8, inode); }
    }
    @Test public void usesExactRelativeNamesAndClosesFdZeroExactlyOnce() throws Exception {
        var api = new FakeApi(); var owned = GoDelveDarwinDirectory.open(DIRECTORY, api);
        owned.removeFile("debug"); owned.removeDirectory(); owned.close(); owned.close();
        assertEquals(List.of("parent", "child", "unlink:1:debug:0", "unlink:0:owned:128", "close:1", "close:0"), api.events);
    }
    @Test public void failedInitializationClosesBothHandlesWithoutLosingOriginalErrno() {
        var api = new FakeApi(); api.failMetadata = true;
        IOException failure = assertThrows(IOException.class, () -> GoDelveDarwinDirectory.open(DIRECTORY, api));
        assertTrue(failure.getMessage().endsWith("Darwin errno 13"));
        assertEquals(List.of("parent", "child", "close:1", "close:0"), api.events);
    }
    @Test public void nonRegularOutputAndReplacedDirectoryAreNeverUnlinked() throws Exception {
        var api = new FakeApi();
        try (var owned = GoDelveDarwinDirectory.open(DIRECTORY, api)) {
            api.fileMode = 0120777;
            assertThrows(IOException.class, () -> owned.removeFile("debug"));
            api.replaced = true;
            assertThrows(IOException.class, owned::removeDirectory);
        }
        assertEquals(List.of("parent", "child", "close:1", "close:0"), api.events);
    }
    @Test public void unlinkFailureIsVisibleAndPathTraversalCannotReachTheApi() throws Exception {
        var api = new FakeApi();
        try (var owned = GoDelveDarwinDirectory.open(DIRECTORY, api)) {
            for (String invalid : List.of("", ".", "..", "../debug", "debug/file", "bad\0name"))
                assertThrows(IOException.class, () -> owned.removeFile(invalid));
            assertEquals(List.of("parent", "child"), api.events);
            api.unlinkError = 13;
            IOException failure = assertThrows(IOException.class, () -> owned.removeFile("debug"));
            assertTrue(failure.getMessage().endsWith("Darwin errno 13"));
        }
        assertEquals(List.of("parent", "child", "unlink:1:debug:0", "close:1", "close:0"), api.events);
    }
}

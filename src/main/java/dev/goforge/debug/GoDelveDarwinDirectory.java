package dev.goforge.debug;

import com.sun.jna.FunctionMapper;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Exact fd-relative cleanup for the macOS JBR, whose NIO stream is not secure. */
final class GoDelveDarwinDirectory implements AutoCloseable {
    private static final int DIRECTORY_FLAGS = 0x00100000 | 0x00000100 | 0x01000000;
    private static final int NOFOLLOW = 0x20, REMOVE_DIRECTORY = 0x80, ENOENT = 2;
    private static final int TYPE_MASK = 0170000, DIRECTORY = 0040000, REGULAR = 0100000;
    private final Api api;
    private final int parentFd, directoryFd;
    private final String name;
    private final Identity identity;
    private boolean closed;

    interface Api extends Library {
        int open(String path, int flags);
        int openat(int fd, String name, int flags);
        int fstat(int fd, Pointer stat);
        int fstatat(int fd, String name, Pointer stat, int flags);
        int unlinkat(int fd, String name, int flags);
        int close(int fd);
    }
    private static final class NativeApi {
        static final Api INSTANCE = load();
        private static Api load() {
            if (Native.POINTER_SIZE != 8) throw new UnsupportedOperationException("Darwin cleanup requires a 64-bit runtime.");
            String arch = System.getProperty("os.arch", "");
            boolean intel = arch.equals("x86_64") || arch.equals("amd64");
            if (!intel && !arch.equals("aarch64") && !arch.equals("arm64"))
                throw new UnsupportedOperationException("Unsupported Darwin runtime architecture.");
            // Darwin x86_64 uses the INODE64 symbol suffix; arm64 has only the 64-bit ABI.
            FunctionMapper names = (library, method) -> intel && (method.getName().equals("fstat") || method.getName().equals("fstatat"))
                    ? method.getName() + "$INODE64" : method.getName();
            return Native.load("c", Api.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8", Library.OPTION_FUNCTION_MAPPER, names));
        }
    }
    private record Identity(int device, long inode, int mode) {
        boolean directory() { return (mode & TYPE_MASK) == DIRECTORY; }
        boolean sameDirectory(Identity other) { return other != null && directory() && other.directory() && device == other.device && inode == other.inode; }
    }
    static GoDelveDarwinDirectory open(Path directory) throws IOException { return open(directory, NativeApi.INSTANCE); }
    static GoDelveDarwinDirectory open(Path directory, Api api) throws IOException {
        String name = component(directory.getFileName().toString());
        int parent = api.open(directory.getParent().toString(), DIRECTORY_FLAGS);
        if (parent < 0) throw error("open parent directory");
        int child = -1;
        try {
            child = api.openat(parent, name, DIRECTORY_FLAGS);
            if (child < 0) throw error("open owned directory");
            Identity identity = stat(api, child, null, false);
            if (!identity.sameDirectory(stat(api, parent, name, false)))
                throw new IOException("Generated debug directory identity changed while opening it.");
            return new GoDelveDarwinDirectory(api, parent, child, name, identity);
        } catch (IOException | RuntimeException | Error failure) {
            closeOnFailure(api, child, failure); closeOnFailure(api, parent, failure);
            throw failure;
        }
    }
    private GoDelveDarwinDirectory(Api api, int parent, int child, String name, Identity identity) {
        this.api = api; parentFd = parent; directoryFd = child; this.name = name; this.identity = identity;
    }
    /** Never follows a link or scans a directory; unlinkat removes only this exact name. */
    void removeFile(String binaryName) throws IOException {
        requireOpen(); component(binaryName);
        Identity file = stat(api, directoryFd, binaryName, true);
        if (file == null) return;
        if ((file.mode & TYPE_MASK) != REGULAR)
            throw new IOException("Generated debug output was replaced by a non-regular file; retained owned directory.");
        if (api.unlinkat(directoryFd, binaryName, 0) != 0) {
            int errno = Native.getLastError();
            if (errno != ENOENT) throw error("unlink generated output", errno);
        }
    }
    /** Identity is checked immediately before removal; AT_REMOVEDIR never removes nonempty content. */
    void removeDirectory() throws IOException {
        requireOpen();
        if (!identity.sameDirectory(stat(api, parentFd, name, false)))
            throw new IOException("Debug directory was moved or replaced; no directory was removed.");
        if (api.unlinkat(parentFd, name, REMOVE_DIRECTORY) != 0) throw error("remove empty owned directory");
    }
    private void requireOpen() throws IOException { if (closed) throw new IOException("Debug directory handles are closed."); }
    static String component(String value) throws IOException {
        if (value.isEmpty() || value.equals(".") || value.equals("..") || value.indexOf('/') >= 0 || value.indexOf('\0') >= 0)
            throw new IOException("Debug cleanup requires one exact path component.");
        return value;
    }
    private static Identity stat(Api api, int fd, String name, boolean missingAllowed) throws IOException {
        // Apple SDK __DARWIN_STRUCT_STAT64: sizeof=144; dev=0, mode=4, inode=8.
        try (Memory bytes = new Memory(144)) {
            int result = name == null ? api.fstat(fd, bytes) : api.fstatat(fd, name, bytes, NOFOLLOW);
            if (result != 0) {
                int errno = Native.getLastError();
                if (missingAllowed && errno == ENOENT) return null;
                throw error("read owned directory entry", errno);
            }
            return new Identity(bytes.getInt(0), bytes.getLong(8), Short.toUnsignedInt(bytes.getShort(4)));
        }
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        if (api.close(directoryFd) != 0) failure = error("close owned directory");
        if (api.close(parentFd) != 0) {
            IOException parentFailure = error("close parent directory");
            if (failure == null) failure = parentFailure; else failure.addSuppressed(parentFailure);
        }
        if (failure != null) throw failure;
    }
    private static void closeOnFailure(Api api, int fd, Throwable failure) {
        if (fd >= 0 && api.close(fd) != 0) failure.addSuppressed(error("close directory after initialization failure"));
    }
    private static IOException error(String action) { return error(action, Native.getLastError()); }
    private static IOException error(String action, int errno) { return new IOException("Cannot " + action + ": Darwin errno " + errno); }
}

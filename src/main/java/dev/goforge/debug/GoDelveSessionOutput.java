package dev.goforge.debug;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/** One generated output in an exclusively created directory; never searches a project for binaries. */
final class GoDelveSessionOutput implements AutoCloseable {
    private final Path directory;
    private final Path binaryName;
    private final DirectoryStream<Path> stream;
    private final SecureDirectoryStream<Path> secure;
    private final WindowsLease windows;
    private final GoDelveDarwinDirectory darwin;
    private boolean closed;
    private boolean released;
    private final Object directoryKey;

    static boolean required(Map<String, Object> parameters, boolean launch) {
        if (!launch || parameters.containsKey("output")) return false;
        Object mode = parameters.get("mode");
        return mode == null || mode.equals("") || mode.equals("debug") || mode.equals("test");
    }

    static GoDelveSessionOutput create(Path parent) throws IOException {
        Path directory = Files.createTempDirectory(localPath(parent).toRealPath(), "goforge-delve-");
        try { return new GoDelveSessionOutput(directory); }
        catch (IOException | RuntimeException | Error failure) {
            try { Files.delete(directory); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    /** IntelliJ's local routing wrapper hides SecureDirectoryStream. Unwrap only its verified local backend. */
    static Path localPath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        if (absolute.getFileSystem() instanceof com.intellij.platform.core.nio.fs.MultiRoutingFileSystem routing) {
            var provider = routing.provider();
            String key = absolute.toString();
            if (routing.getBackend(key).provider() != provider.myLocalProvider)
                throw new IOException("Generated debug output requires a local filesystem backend.");
            Path nativePath = provider.toDelegatePath(absolute);
            if (nativePath.getFileSystem().provider() != provider.myLocalProvider
                    || routing.getBackend(key).provider() != provider.myLocalProvider)
                throw new IOException("The debug temporary directory routing changed.");
            return nativePath;
        }
        return absolute;
    }
    private GoDelveSessionOutput(Path directory) throws IOException {
        this.directory = directory;
        boolean isWindows = System.getProperty("os.name", "").startsWith("Windows");
        binaryName = directory.getFileSystem().getPath(isWindows ? "debug.exe" : "debug");
        if (isWindows) {
            // An open handle without FILE_SHARE_DELETE prevents directory replacement while owned.
            windows = WindowsLease.open(directory); darwin = null; stream = null; secure = null; directoryKey = null;
        } else {
            windows = null;
            var opened = Files.newDirectoryStream(directory);
            if (!(opened instanceof SecureDirectoryStream<Path> relative)) {
                opened.close();
                if (!System.getProperty("os.name", "").startsWith("Mac"))
                    throw new IOException("The debug temporary directory does not support safe relative cleanup.");
                darwin = GoDelveDarwinDirectory.open(directory); stream = null; secure = null; directoryKey = null;
                return;
            }
            darwin = null; stream = opened; secure = relative;
            try {
                directoryKey = relative.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey();
                if (directoryKey == null) throw new IOException("Debug directory identity is unavailable.");
            } catch (IOException | RuntimeException | Error failure) {
                try { opened.close(); } catch (IOException close) { failure.addSuppressed(close); }
                throw failure;
            }
        }
    }
    Path directory() { return directory; }
    Path binary() { return directory.resolve(binaryName); }

    /** Called only after the adapter and every observed descendant have exited. Idempotent. */
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        if (darwin != null) {
            IOException failure = null;
            try { darwin.removeFile(binaryName.toString()); darwin.removeDirectory(); }
            catch (IOException error) { failure = error; }
            finally {
                try { release(); }
                catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            if (failure != null) throw failure;
            return;
        }
        IOException failure = null;
        try {
            BasicFileAttributes attributes;
            try { attributes = secure != null
                    ? secure.getFileAttributeView(binaryName, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes()
                    : Files.readAttributes(binary(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
            catch (NoSuchFileException absent) { attributes = null; }
            if (attributes != null) {
                if (!attributes.isRegularFile() || attributes.isSymbolicLink())
                    throw new IOException("Generated debug output was replaced by a non-regular file; retained " + directory);
                if (secure != null) secure.deleteFile(binaryName);
                else {
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                    while (true) {
                        try {
                            var current = Files.readAttributes(binary(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                            if (!current.isRegularFile() || current.isSymbolicLink()) throw new IOException("Generated output is no longer a regular file.");
                            Files.delete(binary()); break;
                        } catch (NoSuchFileException absent) { break; }
                        catch (AccessDeniedException busy) {
                            if (System.nanoTime() >= deadline) throw busy;
                            try { Thread.sleep(50); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Debug output cleanup was interrupted.", interrupted); }
                        }
                    }
                }
            }
        } catch (IOException e) { failure = e; }
        finally {
            try { release(); }
            catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        // Empty-directory removal cannot follow a symlink or recursively erase unexpected contents.
        if (failure == null) {
            try {
                if (secure != null) {
                    var current = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!current.isDirectory() || !directoryKey.equals(current.fileKey())) throw new IOException("Debug directory was moved or replaced; no directory was removed.");
                }
                Files.delete(directory);
            } catch (IOException e) { failure = e; }
        }
        if (failure != null) throw failure;
    }

    synchronized void release() throws IOException {
        if (released) return;
        released = true;
        if (stream != null) stream.close();
        if (windows != null) windows.close();
        if (darwin != null) darwin.close();
    }

    private interface Kernel32 extends StdCallLibrary {
        Pointer CreateFileW(WString path, int access, int share, Pointer security, int disposition, int flags, Pointer template);
        boolean GetFileInformationByHandleEx(Pointer handle, int type, Pointer buffer, int size);
        boolean CloseHandle(Pointer handle);
        int GetLastError();
    }
    private static final class WindowsApi {
        static final Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);
    }
    private record WindowsLease(Pointer handle) implements AutoCloseable {
        static WindowsLease open(Path directory) throws IOException {
            String path = directory.toString();
            if (!path.startsWith("\\\\?\\")) path = path.startsWith("\\\\") ? "\\\\?\\UNC\\" + path.substring(2) : "\\\\?\\" + path;
            Kernel32 api = WindowsApi.INSTANCE;
            Pointer handle = api.CreateFileW(new WString(path), 0x80, 3, null, 3, 0x02000000 | 0x00200000, null);
            if (handle == null || Pointer.nativeValue(handle) == -1L)
                throw new IOException("Cannot protect the debug temporary directory: Win32 " + api.GetLastError());
            try (Memory basic = new Memory(40)) {
                if (!api.GetFileInformationByHandleEx(handle, 0, basic, 40))
                    throw new IOException("Cannot inspect the debug temporary directory: Win32 " + api.GetLastError());
                int flags = basic.getInt(32);
                if ((flags & 0x10) == 0 || (flags & 0x400) != 0) throw new IOException("Unsafe debug temporary directory.");
                return new WindowsLease(handle);
            } catch (IOException | RuntimeException | Error failure) {
                if (!api.CloseHandle(handle)) failure.addSuppressed(new IOException("Failed to close debug directory handle."));
                throw failure;
            }
        }
        public void close() throws IOException {
            if (!WindowsApi.INSTANCE.CloseHandle(handle)) throw new IOException("Failed to close debug directory handle.");
        }
    }
}

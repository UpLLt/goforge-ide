package dev.goforge.refactor;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import dev.goforge.tools.HostTools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

/** Physical identity used for refactoring guards; all Windows metadata comes from one no-follow handle. */
final class GoRefactorFileIdentity {
    record Identity(Object key, long size, FileTime modified) { }
    record WindowsKey(long volume, long fileIdLow, long fileIdHigh) { }
    interface Kernel32 extends StdCallLibrary {
        Pointer CreateFileW(WString path, int access, int share, Pointer security, int disposition, int flags, Pointer template);
        int GetFileType(Pointer handle);
        boolean GetFileInformationByHandleEx(Pointer handle, int type, Pointer buffer, int size);
        boolean CloseHandle(Pointer handle);
        int GetLastError();
    }
    private static final class WindowsApi {
        static final Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);
    }
    private GoRefactorFileIdentity() { }

    static Identity read(Path path, boolean directory) throws IOException {
        if (HostTools.isWindows()) {
            try { return readWindows(windowsPath(path.toAbsolutePath().normalize().toString()), directory, WindowsApi.INSTANCE); }
            catch (LinkageError failure) { throw new IOException("Windows physical-file identity is unavailable; no refactoring was applied.", failure); }
        }
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (directory ? !attributes.isDirectory() : !attributes.isRegularFile())
            throw new IOException("Refactoring source is no longer a physical " + (directory ? "directory." : "regular file."));
        return new Identity(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime());
    }

    /** Preserve wide and long drive/UNC names; device namespaces are not source files. */
    static String windowsPath(String absolute) throws IOException {
        String path = absolute.replace('/', '\\');
        if (path.regionMatches(true, 0, "\\\\?\\UNC\\", 0, 8) || path.matches("^\\\\\\\\\\?\\\\[A-Za-z]:\\\\.*")) return path;
        if (path.startsWith("\\\\?\\") || path.startsWith("\\\\.\\")) throw new IOException("Unsupported Windows source namespace.");
        if (path.startsWith("\\\\")) return "\\\\?\\UNC\\" + path.substring(2);
        if (path.matches("^[A-Za-z]:\\\\.*")) return "\\\\?\\" + path;
        throw new IOException("Windows source identity requires an absolute drive or UNC path.");
    }

    static Identity readWindows(String path, boolean directory, Kernel32 api) throws IOException {
        final int readAttributes = 0x80, shareReadWriteDelete = 7, openExisting = 3;
        final int openReparsePoint = 0x00200000, backupSemantics = 0x02000000;
        Pointer handle = api.CreateFileW(new WString(path), readAttributes, shareReadWriteDelete, null,
                openExisting, openReparsePoint | backupSemantics, null);
        if (handle == null || Pointer.nativeValue(handle) == -1L) throw error(api, "CreateFileW");
        Throwable original = null;
        try {
            if (api.GetFileType(handle) != 1) throw new IOException("Refactoring source handle is not a disk file.");
            try (var id = new Memory(24); var basic = new Memory(40); var standard = new Memory(24)) {
                id.clear(); basic.clear(); standard.clear();
                if (!api.GetFileInformationByHandleEx(handle, 18, id, 24)) throw error(api, "FileIdInfo");
                if (!api.GetFileInformationByHandleEx(handle, 0, basic, 40)) throw error(api, "FileBasicInfo");
                if (!api.GetFileInformationByHandleEx(handle, 1, standard, 24)) throw error(api, "FileStandardInfo");
                return decode(id.getByteArray(0, 24), basic.getByteArray(0, 40), standard.getByteArray(0, 24), directory);
            }
        } catch (IOException | RuntimeException | Error failure) {
            original = failure; throw failure;
        } finally {
            if (!api.CloseHandle(handle)) {
                var closeFailure = error(api, "CloseHandle");
                if (original != null) original.addSuppressed(closeFailure); else throw closeFailure;
            }
        }
    }

    /** Windows FILE_ID_INFO, FILE_BASIC_INFO and FILE_STANDARD_INFO ABI, independent of host byte order. */
    static Identity decode(byte[] idBytes, byte[] basicBytes, byte[] standardBytes, boolean directory) throws IOException {
        if (idBytes.length != 24 || basicBytes.length != 40 || standardBytes.length != 24)
            throw new IOException("Incomplete Windows physical-file metadata.");
        var id = ByteBuffer.wrap(idBytes).order(ByteOrder.LITTLE_ENDIAN);
        var basic = ByteBuffer.wrap(basicBytes).order(ByteOrder.LITTLE_ENDIAN);
        var standard = ByteBuffer.wrap(standardBytes).order(ByteOrder.LITTLE_ENDIAN);
        int attributes = basic.getInt(32); boolean isDirectory = (attributes & 0x10) != 0;
        if ((attributes & 0x400) != 0 || standard.get(20) != 0 || isDirectory != (standard.get(21) != 0)
                || isDirectory != directory)
            throw new IOException("Refactoring source is a reparse point, pending deletion, or not the expected physical file kind.");
        long size = standard.getLong(8);
        if (size < 0) throw new IOException("Invalid Windows source size.");
        long unixTicks = basic.getLong(16) - 116_444_736_000_000_000L;
        var modified = FileTime.from(Instant.ofEpochSecond(Math.floorDiv(unixTicks, 10_000_000L),
                Math.floorMod(unixTicks, 10_000_000L) * 100L));
        return new Identity(new WindowsKey(id.getLong(0), id.getLong(8), id.getLong(16)), size, modified);
    }
    private static IOException error(Kernel32 api, String operation) {
        return new IOException("Windows physical-file identity " + operation + " failed (Win32 error " + api.GetLastError() + "); no edits were applied.");
    }
}

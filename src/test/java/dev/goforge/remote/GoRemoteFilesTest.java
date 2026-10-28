package dev.goforge.remote;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.Base64;
import static org.junit.Assert.*;

public class GoRemoteFilesTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void previewFreezesSavedBytesAndListsExcludedHiddenGeneratedAndLinkedPaths() throws Exception {
        Path root=temp.newFolder().toPath();Files.writeString(root.resolve("main.go"),"before");Files.writeString(root.resolve(".env"),"secret");
        Files.createDirectory(root.resolve("build"));Files.writeString(root.resolve("build/output"),"generated");
        Files.createSymbolicLink(root.resolve("link"),root.resolve("main.go"));
        var plan=GoRemoteFiles.snapshot(root,() -> false);Files.writeString(root.resolve("main.go"),"after");
        assertEquals(1,plan.files().size());assertEquals("before",new String(Base64.getDecoder().decode(plan.files().getFirst().data())));
        assertTrue(plan.skipped().containsAll(java.util.List.of(".env","build/","link")));
    }
    @Test public void downloadCreatesNewFileButNeverReplacesExisting() throws Exception {
        Path root=temp.newFolder().toPath();Files.createDirectory(root.resolve("nested"));byte[] bytes="new".getBytes();
        Path target=GoRemoteFiles.writeNew(root,"nested/file.txt",bytes);assertArrayEquals(bytes,Files.readAllBytes(target));
        assertThrows(FileAlreadyExistsException.class,() -> GoRemoteFiles.writeNew(root,"nested/file.txt","replace".getBytes()));assertArrayEquals(bytes,Files.readAllBytes(target));
    }
    @Test public void downloadRejectsSymlinkedParentAndLeaf() throws Exception {
        Path root=temp.newFolder().toPath(),outside=temp.newFolder().toPath();Files.createSymbolicLink(root.resolve("escape"),outside);
        assertThrows(java.io.IOException.class,() -> GoRemoteFiles.writeNew(root,"escape/new",new byte[1]));assertFalse(Files.exists(outside.resolve("new")));
        Files.writeString(outside.resolve("original"),"safe");Files.createSymbolicLink(root.resolve("leaf"),outside.resolve("original"));
        assertThrows(java.io.IOException.class,() -> GoRemoteFiles.writeNew(root,"leaf",new byte[1]));assertEquals("safe",Files.readString(outside.resolve("original")));
    }
    @Test public void downloadRejectsTraversalBeforeAnyWrite() throws Exception {
        Path root=temp.newFolder().toPath();assertThrows(IllegalArgumentException.class,() -> GoRemoteFiles.writeNew(root,"../outside",new byte[1]));assertEquals(0,root.toFile().list().length);
    }
    @Test public void boundedSnapshotFailsInsteadOfSilentlyOmittingOversizedFile() throws Exception {
        Path root=temp.newFolder().toPath();try(var file=new java.io.RandomAccessFile(root.resolve("large").toFile(),"rw")){file.setLength(GoRemoteFiles.FILE_LIMIT+1L);}
        assertThrows(java.io.IOException.class,() -> GoRemoteFiles.snapshot(root,() -> false));
    }
    @Test public void cancellationStopsSnapshotBeforeReadingFiles() throws Exception {
        Path root=temp.newFolder().toPath();Files.writeString(root.resolve("file"),"data");assertThrows(java.io.IOException.class,() -> GoRemoteFiles.snapshot(root,() -> true));
    }
}

package dev.goforge.refactor;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.*;

/** ABI/ownership contracts run on every host; physical reads use the native handle path only on Windows. */
public class GoRefactorFileIdentityTest {
    private static final long UNIX_EPOCH_TICKS = 116_444_736_000_000_000L;
    private static ByteBuffer little(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static byte[] id() {
        byte[] bytes=new byte[24];little(bytes).putLong(0,0xFEDCBA9876543210L).putLong(8,0x0123456789ABCDEFL).putLong(16,0x7766554433221100L);return bytes;
    }
    private static byte[] basic() { byte[] bytes=new byte[40];little(bytes).putLong(16,UNIX_EPOCH_TICKS+12_345_678L);return bytes; }
    private static byte[] standard() { byte[] bytes=new byte[24];little(bytes).putLong(8,0x1_0000_0005L);return bytes; }

    @Test public void decoderPreservesAll128IdentifierBitsVolumeLargeSizeAndSubmicrosecondTime()throws Exception {
        var identity=GoRefactorFileIdentity.decode(id(),basic(),standard(),false);
        assertEquals(new GoRefactorFileIdentity.WindowsKey(0xFEDCBA9876543210L,0x0123456789ABCDEFL,0x7766554433221100L),identity.key());
        assertEquals(0x1_0000_0005L,identity.size());assertEquals(FileTime.from(Instant.ofEpochSecond(1,234_567_800L)),identity.modified());
        byte[] otherId=id();little(otherId).putLong(16,0x7766554433221101L);
        assertNotEquals(identity.key(),GoRefactorFileIdentity.decode(otherId,basic(),standard(),false).key());
        little(otherId).putLong(0,2);assertNotEquals(identity.key(),GoRefactorFileIdentity.decode(otherId,basic(),standard(),false).key());
        byte[] beforeEpoch=basic();little(beforeEpoch).putLong(16,UNIX_EPOCH_TICKS-1);
        assertEquals(FileTime.from(Instant.ofEpochSecond(-1,999_999_900L)),GoRefactorFileIdentity.decode(id(),beforeEpoch,standard(),false).modified());
    }
    @Test public void decoderRejectsReparseDeletionWrongKindTruncatedAndNegativeSize()throws Exception {
        byte[] reparse=basic();little(reparse).putInt(32,0x400);
        assertThrows(IOException.class,()->GoRefactorFileIdentity.decode(id(),reparse,standard(),false));
        byte[] deleting=standard();deleting[20]=1;
        assertThrows(IOException.class,()->GoRefactorFileIdentity.decode(id(),basic(),deleting,false));
        byte[] directoryBasic=basic(),directoryStandard=standard();little(directoryBasic).putInt(32,0x10);directoryStandard[21]=1;
        assertThrows(IOException.class,()->GoRefactorFileIdentity.decode(id(),directoryBasic,directoryStandard,false));
        assertThrows(IOException.class,()->GoRefactorFileIdentity.decode(id(),directoryBasic,standard(),true));
        assertNotNull(GoRefactorFileIdentity.decode(id(),directoryBasic,directoryStandard,true).key());
        assertThrows(IOException.class,()->GoRefactorFileIdentity.decode(new byte[23],basic(),standard(),false));
        byte[] negative=standard();little(negative).putLong(8,-1);
        assertThrows(IOException.class,()->GoRefactorFileIdentity.decode(id(),basic(),negative,false));
    }
    @Test public void widePathPreservesUnicodeLongDriveAndUncNamesAndRejectsDeviceNamespaces()throws Exception {
        assertEquals("\\\\?\\C:\\work\\世界 go.go",GoRefactorFileIdentity.windowsPath("C:/work/世界 go.go"));
        assertEquals("\\\\?\\UNC\\server\\share\\世界.go",GoRefactorFileIdentity.windowsPath("\\\\server\\share\\世界.go"));
        String longPath="C:\\"+"long-segment\\".repeat(30)+"source.go";
        assertEquals("\\\\?\\"+longPath,GoRefactorFileIdentity.windowsPath(longPath));
        assertEquals("\\\\?\\"+longPath,GoRefactorFileIdentity.windowsPath("\\\\?\\"+longPath));
        assertEquals("\\\\?\\UNC\\server\\share\\file.go",GoRefactorFileIdentity.windowsPath("\\\\?\\UNC\\server\\share\\file.go"));
        assertEquals("\\\\?\\unc\\server\\share\\file.go",GoRefactorFileIdentity.windowsPath("\\\\?\\unc\\server\\share\\file.go"));
        for(String invalid:List.of("relative.go","C:relative.go","\\\\.\\pipe\\source","\\\\?\\GLOBALROOT\\Device\\source"))
            assertThrows(invalid,IOException.class,()->GoRefactorFileIdentity.windowsPath(invalid));
    }
    @Test public void nativeBridgeRequestsWideSharedNoFollowHandleAndAlwaysClosesIt()throws Exception {
        var api=new FakeKernel32();String path="\\\\?\\C:\\work\\世界 go.go";
        var identity=GoRefactorFileIdentity.readWindows(path,false,api);
        assertEquals(path,api.path);assertEquals(0x80,api.access);assertEquals(7,api.share);assertEquals(3,api.disposition);
        assertEquals(0x02200000,api.flags);assertEquals(List.of(18,0,1),api.types);assertEquals(1,api.closeCalls);
        assertEquals(GoRefactorFileIdentity.decode(api.id,api.basic,api.standard,false),identity);
    }
    @Test public void everyMetadataFailureAndTypeRefusalClosesTheOwnedHandle()throws Exception {
        for(int type:List.of(18,0,1)){
            var api=new FakeKernel32();api.failType=type;
            var error=assertThrows(IOException.class,()->GoRefactorFileIdentity.readWindows("C:\\source.go",false,api));
            assertTrue(error.getMessage(),error.getMessage().contains("Win32 error 5"));assertEquals(1,api.closeCalls);
        }
        var reparse=new FakeKernel32();little(reparse.basic).putInt(32,0x400);
        assertThrows(IOException.class,()->GoRefactorFileIdentity.readWindows("C:\\source.go",false,reparse));assertEquals(1,reparse.closeCalls);
        var nonDisk=new FakeKernel32();nonDisk.fileType=3;
        assertThrows(IOException.class,()->GoRefactorFileIdentity.readWindows("C:\\source.go",false,nonDisk));assertEquals(1,nonDisk.closeCalls);assertTrue(nonDisk.types.isEmpty());
    }
    @Test public void invalidHandlesAreNotClosedAndCloseFailuresPreserveThePrimaryError()throws Exception {
        for(Pointer invalid:Arrays.asList(null,Pointer.createConstant(-1L))){
            var api=new FakeKernel32();api.handle=invalid;
            assertThrows(IOException.class,()->GoRefactorFileIdentity.readWindows("C:\\source.go",false,api));assertEquals(0,api.closeCalls);
        }
        var closeOnly=new FakeKernel32();closeOnly.closeSucceeds=false;
        var closeError=assertThrows(IOException.class,()->GoRefactorFileIdentity.readWindows("C:\\source.go",false,closeOnly));
        assertTrue(closeError.getMessage(),closeError.getMessage().contains("CloseHandle"));assertEquals(1,closeOnly.closeCalls);
        var both=new FakeKernel32();both.failType=18;both.closeSucceeds=false;
        var primary=assertThrows(IOException.class,()->GoRefactorFileIdentity.readWindows("C:\\source.go",false,both));
        assertTrue(primary.getMessage(),primary.getMessage().contains("FileIdInfo"));assertEquals(1,primary.getSuppressed().length);
        assertTrue(primary.getSuppressed()[0].getMessage().contains("CloseHandle"));assertEquals(1,both.closeCalls);
    }
    @Test public void physicalHostIdentitySeesCrossDirectoryHardlinksAndFreshSharedMetadata()throws Exception {
        Path root=Files.createTempDirectory("goforge-file-identity-");
        try{
            Path first=Files.createDirectory(root.resolve("first")).resolve("世界 source.go");
            Path alias=Files.createDirectory(root.resolve("second")).resolve("alias.go");
            Path independent=root.resolve("independent.go");
            Files.writeString(first,"old");Files.createLink(alias,first);
            Files.writeString(first,"new longer content\n");
            var modified=FileTime.fromMillis(1_700_000_002_000L);Files.setLastModifiedTime(first,modified);
            Files.writeString(independent,Files.readString(first));Files.setLastModifiedTime(independent,modified);
            var one=GoRefactorFileIdentity.read(first,false);var two=GoRefactorFileIdentity.read(alias,false);
            assertNotNull(one.key());assertEquals(one,two);assertEquals(Files.size(first),one.size());assertEquals(modified,one.modified());
            assertNotEquals(one.key(),GoRefactorFileIdentity.read(independent,false).key());
            assertThrows(IOException.class,()->GoRefactorFileIdentity.read(root,false));
            assertThrows(IOException.class,()->GoRefactorFileIdentity.read(first,true));
            // Windows execution exercises the real native handle path; other hosts
            // verify their own filesystem path without claiming Windows acceptance.
            System.out.println("physical identity host="+System.getProperty("os.name")+" keyType="+one.key().getClass().getSimpleName());
        }finally{remove(root);}
    }
    @Test public void physicalHostDirectoryReplacementAndLongUnicodePathHaveStableDistinctIdentities()throws Exception {
        Path root=Files.createTempDirectory("goforge-file-identity-");
        try{
            Path parent=Files.createDirectory(root.resolve("parent"));Object before=GoRefactorFileIdentity.read(parent,true).key();assertNotNull(before);
            Files.move(parent,root.resolve("old-parent"));Files.createDirectory(parent);
            assertNotEquals(before,GoRefactorFileIdentity.read(parent,true).key());
            Path nested=parent;
            for(int i=0;i<4;i++)nested=Files.createDirectory(nested.resolve("long-"+"a".repeat(60)));
            Path source=nested.resolve("世界 source.go");Files.writeString(source,"package demo\n");assertTrue(source.toString().length()>260);
            var identity=GoRefactorFileIdentity.read(source,false);assertNotNull(identity.key());assertEquals(13,identity.size());
            // Moving/deleting immediately after read also proves no owned native
            // handle remains open with incompatible sharing or directory lifetime.
            Path moved=nested.resolve("moved.go");Files.move(source,moved);Files.delete(moved);
        }finally{remove(root);}
    }
    private static void remove(Path root)throws IOException {
        List<Path> paths;try(var walk=Files.walk(root)){paths=walk.sorted(Comparator.reverseOrder()).toList();}
        for(var path:paths)Files.delete(path);
    }
    private static final class FakeKernel32 implements GoRefactorFileIdentity.Kernel32 {
        Pointer handle=Pointer.createConstant(42);byte[] id=id(),basic=basic(),standard=standard();
        String path;int access,share,disposition,flags,failType=-1,fileType=1,closeCalls;boolean closeSucceeds=true;
        final List<Integer> types=new ArrayList<>();
        public Pointer CreateFileW(WString path,int access,int share,Pointer security,int disposition,int flags,Pointer template){
            assertNull(security);assertNull(template);this.path=path.toString();this.access=access;this.share=share;this.disposition=disposition;this.flags=flags;return handle;
        }
        public int GetFileType(Pointer handle){assertEquals(this.handle,handle);return fileType;}
        public boolean GetFileInformationByHandleEx(Pointer handle,int type,Pointer buffer,int size){
            assertEquals(this.handle,handle);types.add(type);if(type==failType)return false;
            byte[] data=switch(type){case 18->id;case 0->basic;case 1->standard;default->throw new AssertionError("unexpected metadata class "+type);};
            assertEquals(data.length,size);buffer.write(0,data,0,data.length);return true;
        }
        public boolean CloseHandle(Pointer handle){assertEquals(this.handle,handle);closeCalls++;return closeSucceeds;}
        public int GetLastError(){return 5;}
    }
}

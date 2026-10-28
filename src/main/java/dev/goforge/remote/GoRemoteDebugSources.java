package dev.goforge.remote;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Saved source/data manifest, excluding known executable output formats and generated/hidden directories. */
public final class GoRemoteDebugSources {
    private GoRemoteDebugSources() {}
    public static GoRemoteFiles.Snapshot snapshot(Path directory,BooleanSupplier cancelled)throws IOException {
        Path root=directory.toRealPath();var files=new ArrayList<GoRemoteFiles.FileData>();var skipped=new ArrayList<String>();long[] total={0};
        Files.walkFileTree(root,new SimpleFileVisitor<>(){
            private void check()throws IOException{if(cancelled.getAsBoolean())throw new IOException("Debug review cancelled.");}
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attr)throws IOException {
                check();String name=dir.getFileName().toString();if(!dir.equals(root)&&(name.startsWith(".")||Set.of("build","target","node_modules","__pycache__").contains(name))){skipped.add(root.relativize(dir)+"/");return FileVisitResult.SKIP_SUBTREE;}return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attr)throws IOException {
                check();String name=root.relativize(path).toString().replace('\\','/');
                if(!attr.isRegularFile()||path.getFileName().toString().startsWith(".")){skipped.add(name);return FileVisitResult.CONTINUE;}
                if(!path.toRealPath().startsWith(root))throw new IOException("Source left the local project.");
                byte[] content;
                try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));var stream=java.nio.channels.Channels.newInputStream(channel)){
                    byte[] header=stream.readNBytes(4);
                    if(!name.endsWith(".syso")&&executable(header)){skipped.add(name+" (compiled executable)");return FileVisitResult.CONTINUE;}
                    byte[] rest=stream.readNBytes(GoRemoteFiles.FILE_LIMIT+1);content=new byte[header.length+rest.length];System.arraycopy(header,0,content,0,header.length);System.arraycopy(rest,0,content,header.length,rest.length);
                }
                if(content.length>GoRemoteFiles.FILE_LIMIT||files.size()>=512||(total[0]+=content.length)>GoRemoteFiles.TOTAL_LIMIT)throw new IOException("Remote debugging is limited to 512 source/data files, 8 MiB/file and 32 MiB total.");
                files.add(new GoRemoteFiles.FileData(name,"",GoRemoteFiles.hash(content),content.length));return FileVisitResult.CONTINUE;
            }
        });files.sort(Comparator.comparing(GoRemoteFiles.FileData::path));return new GoRemoteFiles.Snapshot(List.copyOf(files),List.copyOf(skipped),total[0]);
    }
    static boolean executable(byte[] b){if(b.length<4)return false;int n=((b[0]&255)<<24)|((b[1]&255)<<16)|((b[2]&255)<<8)|(b[3]&255);return n==0x7f454c46||n==0xfeedface||n==0xfeedfacf||n==0xcefaedfe||n==0xcffaedfe||n==0xcafebabe||n==0xbebafeca||(b[0]=='M'&&b[1]=='Z');}
}

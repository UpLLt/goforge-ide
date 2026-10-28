package dev.goforge.remote;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Transfer plans contain immutable saved-file bytes; leaf destinations use CREATE_NEW + NOFOLLOW.
 * A real SecureDirectoryStream anchors parent traversal when the provider supports it.
 * IntelliJ's wrapped provider can omit that optional API: the fallback rejects symlinks and
 * checks canonical parent identity before/after opening, but is not an atomic directory-rename guarantee.
 */
public final class GoRemoteFiles {
    public static final int FILE_LIMIT=8*1024*1024, TOTAL_LIMIT=32*1024*1024, FILE_COUNT_LIMIT=2000;
    private static final Set<String> EXCLUDED=Set.of("build","target","node_modules","__pycache__");
    public record FileData(String path,String data,String sha256,long bytes) { }
    public record Snapshot(List<FileData> files,List<String> skipped,long bytes) {
        public byte[] payload() { return new Gson().toJson(Map.of("files",files)).getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    }
    private GoRemoteFiles() { }
    public static Snapshot snapshot(Path project,BooleanSupplier cancelled) throws IOException {
        Path root=project.toRealPath();var files=new ArrayList<FileData>();var skipped=new ArrayList<String>();long[] total={0};
        Files.walkFileTree(root,new SimpleFileVisitor<>() {
            private void check() throws IOException { if(cancelled.getAsBoolean())throw new IOException("Snapshot cancelled."); }
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes a) throws IOException {
                check();String name=dir.getFileName().toString();
                if(!dir.equals(root)&&(name.startsWith(".")||EXCLUDED.contains(name))) { skipped.add(root.relativize(dir)+"/");return FileVisitResult.SKIP_SUBTREE; }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes a) throws IOException {
                check();String path=root.relativize(file).toString().replace('\\','/');
                if(!a.isRegularFile()||file.getFileName().toString().startsWith(".")) { skipped.add(path);return FileVisitResult.CONTINUE; }
                GoRemoteProfile.relative(path);
                if(a.size()>FILE_LIMIT||files.size()>=FILE_COUNT_LIMIT||total[0]+a.size()>TOTAL_LIMIT)
                    throw new IOException("Snapshot exceeds 2000 files, 8 MiB/file or 32 MiB total; choose a smaller local project.");
                byte[] bytes;
                bytes=withParent(root,path,(directory,name) -> {
                    try (var channel=directory.newByteChannel(name,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));var stream=java.nio.channels.Channels.newInputStream(channel)) {
                        return stream.readNBytes(FILE_LIMIT+1);
                    }
                });
                if(bytes.length>FILE_LIMIT||total[0]+bytes.length>TOTAL_LIMIT)throw new IOException("File grew beyond snapshot bounds.");
                total[0]+=bytes.length;files.add(new FileData(path,Base64.getEncoder().encodeToString(bytes),hash(bytes),bytes.length));
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(FileData::path));skipped.sort(String::compareTo);
        if(files.isEmpty())throw new IOException("No regular, non-hidden files to upload.");
        return new Snapshot(List.copyOf(files),List.copyOf(skipped),total[0]);
    }
    public static Path writeNew(Path project,String relative,byte[] bytes) throws IOException {
        GoRemoteProfile.relative(relative);if(bytes.length>FILE_LIMIT)throw new IOException("Download exceeds 8 MiB.");
        Path root=project.toRealPath();
        return withParent(root,relative,(directory,name) -> {
            try(var channel=directory.newByteChannel(name,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS));var stream=java.nio.channels.Channels.newOutputStream(channel)) {stream.write(bytes);}
            return root.resolve(relative);
        });
    }
    @FunctionalInterface private interface DirectoryAccess {java.nio.channels.SeekableByteChannel newByteChannel(Path name,Set<? extends OpenOption> options) throws IOException;}
    @FunctionalInterface private interface InDirectory<T> {T apply(DirectoryAccess directory,Path name) throws IOException;}
    private static <T> T withParent(Path root,String relative,InDirectory<T> operation) throws IOException {
        try(var stream=Files.newDirectoryStream(root)) {
            if(!(stream instanceof SecureDirectoryStream<Path> secure))return withCheckedParent(root,relative,operation);
            var opened=new ArrayList<SecureDirectoryStream<Path>>();var current=secure;Path path=Path.of(relative);
            try {
                for(int i=0;i<path.getNameCount()-1;i++) {
                    current=current.newDirectoryStream(path.getName(i),LinkOption.NOFOLLOW_LINKS);opened.add(current);
                }
                var selected=current;
                return operation.apply(selected::newByteChannel,path.getFileName());
            } catch(NoSuchFileException missing) {throw new IOException("The source or destination parent no longer exists; create destination folders explicitly first.",missing);}
            finally {for(var child:opened.reversed())child.close();}
        }
    }
    private static <T> T withCheckedParent(Path root,String relative,InDirectory<T> operation) throws IOException {
        Path path=Path.of(relative),parent=root;
        for(int i=0;i<path.getNameCount()-1;i++) {
            parent=parent.resolve(path.getName(i));
            var attributes=Files.readAttributes(parent,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attributes.isDirectory()||attributes.isSymbolicLink())throw new IOException("The transfer path contains a symlink or non-directory.");
        }
        Path selected=parent;
        Object key=Files.readAttributes(selected,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
        verifyParent(root,selected,key);
        return operation.apply((name,options) -> {
            verifyParent(root,selected,key);
            var channel=Files.newByteChannel(selected.resolve(name),options);
            try {verifyParent(root,selected,key);return channel;}
            catch(IOException changed) {channel.close();throw changed;}
        },path.getFileName());
    }
    private static void verifyParent(Path root,Path parent,Object key) throws IOException {
        Path current=root;
        for(Path component:root.relativize(parent)) {
            current=current.resolve(component);
            if(Files.isSymbolicLink(current))throw new IOException("Transfer parent changed to a symlink.");
        }
        var attributes=Files.readAttributes(parent,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isDirectory()||attributes.isSymbolicLink()||!parent.toRealPath().startsWith(root)
                ||key!=null&&!key.equals(attributes.fileKey()))throw new IOException("Transfer parent changed or left the project.");
    }
    static String hash(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }
}

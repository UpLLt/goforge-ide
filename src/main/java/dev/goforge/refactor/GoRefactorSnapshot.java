package dev.goforge.refactor;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.vfs.VFileProperty;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** A bounded project snapshot taken before requesting code actions, including unsaved buffers. */
final class GoRefactorSnapshot {
    // A final write-boundary check must also see files not yet refreshed into VFS.
    // Bound direct directory listings independently of the Go source/text limits.
    private static final int MAX_INVENTORY_DIRECTORIES=1024, MAX_INVENTORY_CHILDREN=16384;
    private enum ChildKind { DIRECTORY, FILE, SYMLINK, OTHER }
    private record DirectoryEntry(VirtualFile file,String path,Path realPath,Object key,Map<String,ChildKind> children) { }
    private static final class InventoryBudget {
        private int children;
        void next(){if(++children>MAX_INVENTORY_CHILDREN)throw new IllegalStateException("Project exceeds the bounded refactoring directory inventory. Open a smaller project.");}
    }
    record DiskIdentity(Object key, long size, java.nio.file.attribute.FileTime modified) {
        static DiskIdentity read(Path path) throws IOException {
            var value = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!value.isRegularFile()) throw new IOException("Refactoring target is no longer a regular file.");
            return new DiskIdentity(value.fileKey(), value.size(), value.lastModifiedTime());
        }
    }
    record Entry(VirtualFile file, Document document, String path, Path realPath, DiskIdentity disk,
                 String text, long stamp, long fileStamp, boolean unsaved) { }
    private final Project project;
    private final VirtualFile directory;
    private final String directoryPath;
    private final Path realRoot;
    private final long rootsVersion;
    private final List<Entry> entries;
    private final List<DirectoryEntry> directories;
    private final Map<Path, Entry> byPath;
    record NewFile(String uri,Path path,VirtualFile parent,Path realParent,Object parentKey,String name){}
    private final Map<Path,NewFile> newFiles=new HashMap<>();

    private GoRefactorSnapshot(Project project, VirtualFile directory, Path realRoot, long rootsVersion, List<Entry> entries,List<DirectoryEntry> directories) {
        this.project=project;this.directory=directory;this.directoryPath=directory.getPath();this.realRoot=realRoot;
        this.rootsVersion=rootsVersion;this.entries=List.copyOf(entries);this.byPath=new HashMap<>();
        this.directories=List.copyOf(directories);
        for(var entry:entries) byPath.put(entry.file().toNioPath().toAbsolutePath().normalize(),entry);
    }
    static GoRefactorSnapshot capture(Project project, ProgressIndicator indicator) throws IOException {
        VirtualFile directory=ReadAction.compute(()->ProjectUtil.guessProjectDir(project));
        if(directory==null||!directory.isInLocalFileSystem())throw new IllegalStateException("Open a local Go project before refactoring.");
        // Refresh on the worker, outside a read action, so external changes are visible before the baseline.
        directory.refresh(false,true);
        return capture(project,directory,indicator);
    }
    /** Caller has already refreshed this physical project directory outside a read action. */
    static GoRefactorSnapshot capture(Project project, VirtualFile directory, ProgressIndicator indicator) throws IOException {
        Path realRoot=directory.toNioPath().toRealPath();
        return ReadAction.compute(()->{
            long rootsVersion=ProjectRootModificationTracker.getInstance(project).getModificationCount();
            var result=new ArrayList<Entry>();var directories=new ArrayList<DirectoryEntry>();var inventoryBudget=new InventoryBudget();
            var pending=new ArrayDeque<VirtualFile>();pending.add(directory);
            long characters=0;int visited=0;var realPaths=new HashSet<Path>();var diskKeys=new HashSet<Object>();var index=ProjectFileIndex.getInstance(project);
            while(!pending.isEmpty()){
                indicator.checkCanceled();var file=pending.removeFirst();
                if(++visited>100000)throw new IllegalStateException("Project exceeds the bounded refactoring snapshot. Open a smaller project.");
                if(file.is(VFileProperty.SYMLINK)||index.isExcluded(file))continue;
                if(file.isDirectory()){
                    if(directories.size()>=MAX_INVENTORY_DIRECTORIES)throw new IllegalStateException("Project exceeds the 1,024-directory refactoring inventory limit. Open a smaller project.");
                    try{
                        Path path=file.toNioPath(),real=path.toRealPath();var attributes=directoryAttributes(path);
                        if(!real.startsWith(realRoot))throw new IllegalStateException("A refactoring directory resolves outside the project.");
                        var children=directoryChildren(path,inventoryBudget);var virtualChildren=file.getChildren();
                        var known=new HashMap<String,VirtualFile>();for(var child:virtualChildren)known.put(child.getName(),child);
                        // capture(directory,...) callers promise a refreshed root. Refuse an
                        // already-unseen physical source/parent instead of blessing an incomplete baseline.
                        for(var child:children.entrySet())if(child.getValue()==ChildKind.DIRECTORY
                                ||child.getValue()==ChildKind.FILE&&child.getKey().endsWith(".go")){
                            var virtual=known.get(child.getKey());
                            if(virtual==null||virtual.isDirectory()!=(child.getValue()==ChildKind.DIRECTORY))throw inventoryChanged();
                        }
                        directories.add(new DirectoryEntry(file,file.getPath(),real,attributes.fileKey(),children));
                        Collections.addAll(pending,virtualChildren);continue;
                    }catch(IOException failure){throw new IllegalStateException("Cannot capture a stable project directory. Refresh and request the refactoring again.",failure);}
                }
                if(!"go".equals(file.getExtension())||!file.isInLocalFileSystem())continue;
                if(result.size()>=GoRefactorWorkspaceEdit.MAX_FILES)throw new IllegalStateException("Project exceeds the 2,048 Go-file snapshot limit.");
                try {
                    Path real=file.toNioPath().toRealPath();
                    if(!real.startsWith(realRoot)||!realPaths.add(real))throw new IllegalStateException("Go source resolves outside the project or aliases another source.");
                    var disk=DiskIdentity.read(file.toNioPath());
                    if(disk.key()!=null&&!diskKeys.add(disk.key()))throw new IllegalStateException("Go sources contain hard-link aliases; no refactoring was requested.");
                    if(disk.size()>8*1024*1024)throw new IllegalStateException("A Go source exceeds the 8 MiB refactoring limit.");
                    var manager=FileDocumentManager.getInstance();var document=manager.getDocument(file);
                    if(document==null)throw new IllegalStateException("Cannot read Go source: "+file.getName());
                    String text=document.getText();characters+=text.length();
                    if(characters>GoRefactorWorkspaceEdit.MAX_CHARACTERS)throw new IllegalStateException("Project exceeds the 32 MiB Go-source snapshot limit.");
                    result.add(new Entry(file,document,file.getPath(),real,disk,text,document.getModificationStamp(),file.getModificationStamp(),manager.isDocumentUnsaved(document)));
                }catch(IOException failure){throw new IllegalStateException("Cannot capture a stable Go source: "+file.getName(),failure);}
            }
            var snapshot=new GoRefactorSnapshot(project,directory,realRoot,rootsVersion,result,directories);
            snapshot.requireInventoryCurrent();
            return snapshot;
        });
    }
    private static BasicFileAttributes directoryAttributes(Path path)throws IOException{
        var value=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!value.isDirectory())throw new IOException("Refactoring source parent is no longer a physical directory.");
        return value;
    }
    private static Map<String,ChildKind> directoryChildren(Path path,InventoryBudget budget)throws IOException{
        var children=new HashMap<String,ChildKind>();
        try(var stream=Files.newDirectoryStream(path)){
            for(var child:stream){
                com.intellij.openapi.progress.ProgressManager.checkCanceled();
                budget.next();var attributes=Files.readAttributes(child,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                var kind=attributes.isSymbolicLink()?ChildKind.SYMLINK:attributes.isDirectory()?ChildKind.DIRECTORY:
                        attributes.isRegularFile()?ChildKind.FILE:ChildKind.OTHER;
                children.put(child.getFileName().toString(),kind);
            }
        }
        return Map.copyOf(children);
    }
    private static IllegalStateException inventoryChanged(){
        return new IllegalStateException("The project file inventory changed after the request. Refresh and request a fresh refactoring preview; no edits were applied.");
    }
    /** Fixed captured parents only: no VFS refresh, source reads or discovery walk in the write command. */
    private void requireInventoryCurrent(){
        var budget=new InventoryBudget();
        for(var captured:directories){
            try{
                var file=captured.file();Path path=file.toNioPath();
                if(!file.isValid()||!captured.path().equals(file.getPath())||!path.toRealPath().equals(captured.realPath())
                        ||!Objects.equals(directoryAttributes(path).fileKey(),captured.key())
                        ||!directoryChildren(path,budget).equals(captured.children()))throw inventoryChanged();
            }catch(IOException|DirectoryIteratorException failure){throw new IllegalStateException("The project file inventory is no longer available. Request a fresh refactoring preview; no edits were applied.",failure);}
        }
    }
    List<Entry> entries(){return entries;}
    Entry entry(String uri){var entry=byPath.get(GoRefactorWorkspaceEdit.uriPath(uri));if(entry==null)throw new IllegalArgumentException("The edit target is outside the project snapshot.");return entry;}
    String displayName(Entry entry){return directory.toNioPath().relativize(entry.file().toNioPath()).toString();}
    String displayName(String uri){return directory.toNioPath().relativize(Path.of(GoRefactorWorkspaceEdit.uriKey(uri))).toString();}
    Path directory(){return directory.toNioPath();}
    NewFile newFile(String uri){var target=newFiles.get(GoRefactorWorkspaceEdit.uriPath(uri));if(target==null)throw new IllegalStateException("New file was not validated for this preview.");return target;}
    /** Resolve proposed new files on the worker; existing parents only, no symlink escape or overwrite. */
    void prepareTargets(GoRefactorWorkspaceEdit plan)throws IOException {
        newFiles.clear();var names=new HashSet<String>();
        for(var edit:plan.files())if(edit.created()){
            Path path=Path.of(GoRefactorWorkspaceEdit.uriKey(edit.uri()));Path parentPath=path.getParent();
            if(parentPath==null||!path.startsWith(directory.toNioPath())||Files.exists(path,LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("New Go files must stay inside the project and must not already exist.");
            Path realParent=parentPath.toRealPath();
            if(!realParent.startsWith(realRoot)||!Files.isDirectory(realParent))throw new IllegalArgumentException("The new file parent is outside this project.");
            var parent=com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(parentPath);
            if(parent==null||!parent.isValid()||!parent.isDirectory()||!parent.isWritable()
                    ||ReadAction.compute(()->ProjectFileIndex.getInstance(project).isExcluded(parent)))
                throw new IllegalArgumentException("The new file parent is not a writable project directory.");
            String name=path.getFileName().toString();String identity=realParent.resolve(name).toString();
            if(!com.intellij.openapi.util.SystemInfo.isFileSystemCaseSensitive)identity=java.text.Normalizer.normalize(identity,java.text.Normalizer.Form.NFD).toLowerCase(java.util.Locale.ROOT);
            if(!names.add(identity))throw new IllegalArgumentException("Two new files resolve to the same filesystem name.");
            Object parentKey=Files.readAttributes(parentPath,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
            newFiles.put(path,new NewFile(edit.uri(),path,parent,realParent,parentKey,name));
        }
    }
    void requireCurrent(){
        if(project.isDisposed()||!directory.isValid()||!directoryPath.equals(directory.getPath())
                ||ProjectRootModificationTracker.getInstance(project).getModificationCount()!=rootsVersion)
            throw new IllegalStateException("The project roots changed. Request the refactoring again.");
        requireInventoryCurrent();
        var manager=FileDocumentManager.getInstance();
        for(var entry:entries){
            if(!entry.file().isValid()||!entry.path().equals(entry.file().getPath())||entry.file().getModificationStamp()!=entry.fileStamp()
                    ||!Objects.equals(manager.getFile(entry.document()),entry.file())||entry.document().getModificationStamp()!=entry.stamp()
                    ||!entry.text().equals(entry.document().getText()))
                throw new IllegalStateException("A Go source changed after the request: " + entry.file().getName()
                        + " (valid="+entry.file().isValid()+", path="+entry.path().equals(entry.file().getPath())
                        +", fileStamp="+(entry.file().getModificationStamp()==entry.fileStamp())
                        +", documentFile="+Objects.equals(manager.getFile(entry.document()),entry.file())
                        +", documentStamp="+(entry.document().getModificationStamp()==entry.stamp())
                        +", text="+entry.text().equals(entry.document().getText())+"). Request a fresh refactoring preview.");
        }
    }
    /** Final affected-file identity check inside the write command, after queued UI callbacks have run. */
    void requireTargetDiskCurrent(GoRefactorWorkspaceEdit plan) {
        for (var edit : plan.files()) {
            try {
                if(edit.created()){
                    var target=newFile(edit.uri());
                    if(!target.parent().isValid()||!target.parent().isWritable()||!target.path().getParent().toRealPath().equals(target.realParent())
                            ||!Objects.equals(Files.readAttributes(target.path().getParent(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey(),target.parentKey())
                            ||Files.exists(target.path(),LinkOption.NOFOLLOW_LINKS)||target.parent().findChild(target.name())!=null)
                        throw new IllegalStateException("A new-file target now exists or its parent changed. No edits were applied.");
                }else{
                    var entry = entry(edit.uri());
                    if (!entry.file().toNioPath().toRealPath().equals(entry.realPath())
                            || !DiskIdentity.read(entry.file().toNioPath()).equals(entry.disk()))
                        throw new IllegalStateException("A refactoring target changed on disk. No edits were applied.");
                }
            } catch (IOException failure) { throw new IllegalStateException("A refactoring target is no longer available. No edits were applied.", failure); }
        }
    }
    /** Run on a worker before showing/applying the preview; final buffer checks still run in the write command. */
    void requireDiskCurrent() throws IOException {
        if(!directory.toNioPath().toRealPath().equals(realRoot))throw new IllegalStateException("The project directory moved.");
        requireInventoryCurrent();
        for(var entry:entries) if(!entry.file().toNioPath().toRealPath().equals(entry.realPath())
                ||!DiskIdentity.read(entry.file().toNioPath()).equals(entry.disk()))
            throw new IllegalStateException("A Go file changed on disk. Refresh the project and request the refactoring again.");
    }
}

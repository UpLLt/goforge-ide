package dev.goforge.remote;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import java.nio.file.Path;

/** Go overlays are allowed; workspace topology is always reviewed from saved metadata. */
final class GoRemoteAnalysisDocuments {
    private GoRemoteAnalysisDocuments(){}
    static void requireSavedMetadata(Path directory) {
        Path root=directory.toAbsolutePath().normalize();
        ApplicationManager.getApplication().runReadAction(() -> {
            var manager=FileDocumentManager.getInstance();
            for(var document:manager.getUnsavedDocuments()) {
                if(isMetadata(root,manager.getFile(document)))
                    throw new IllegalStateException("Save go.work, go.mod and sum-file edits before reviewing remote analysis. Go source overlays may remain unsaved.");
            }
        });
    }
    static boolean isMetadata(Path directory,com.intellij.openapi.vfs.VirtualFile file) {
        if(file==null||!file.isInLocalFileSystem())return false;
        final Path path;
        try{path=file.toNioPath().toAbsolutePath().normalize();}
        catch(UnsupportedOperationException nonPhysical){
            // In-memory VFS files can report themselves as local. Their display path does
            // not establish ownership of any saved file in the reviewed source root.
            return false;
        }
        Path root=directory.toAbsolutePath().normalize();
        return path.startsWith(root)&&GoRemoteAnalysisReview.isMetadata(root.relativize(path).toString().replace('\\','/'));
    }
}

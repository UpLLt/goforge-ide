package dev.goforge.remote;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import java.nio.file.Path;

/** Remote builds use saved source; a language-server overlay is not a runnable binary. */
public final class GoRemoteDebugDocuments {
    private GoRemoteDebugDocuments() {}
    public static void requireSaved(String root) {
        Path base=Path.of(root).toAbsolutePath().normalize();final Path canonical;
        try{canonical=base.toRealPath();}catch(java.io.IOException e){throw new IllegalStateException("Local debug workspace is unavailable.",e);}
        ReadAction.run(()->{
            var manager=FileDocumentManager.getInstance();
            for(var document:manager.getUnsavedDocuments()){
                var file=manager.getFile(document);
                if(file!=null&&file.isInLocalFileSystem()&&(file.toNioPath().toAbsolutePath().normalize().startsWith(base)||file.toNioPath().toAbsolutePath().normalize().startsWith(canonical)))
                    throw new IllegalStateException("Save or revert unsaved project files before remote debugging. No files were saved automatically.");
            }
        });
    }
}

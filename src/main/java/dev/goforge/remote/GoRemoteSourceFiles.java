package dev.goforge.remote;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Project editor ownership for immutable per-transport SDK snapshots; never initiates a remote read. */
final class GoRemoteSourceFiles implements AutoCloseable {
    private final Project project;
    private final Set<GoRemoteStdlibSources> stores=ConcurrentHashMap.newKeySet();
    private final Map<String,GoRemoteSourceFile> files=new ConcurrentHashMap<>();
    private volatile boolean closed;
    GoRemoteSourceFiles(Project project){this.project=project;}
    void register(GoRemoteStdlibSources store){
        if(closed||project.isDisposed()){store.close();return;}
        stores.add(store);store.whenClosed(()->remove(store));
        if(closed||project.isDisposed())store.close();
    }
    GoRemoteSourceFile find(String uri){
        if(closed||project.isDisposed())return null;
        for(var store:stores){
            var source=store.find(uri);if(source==null)continue;
            var file=files.computeIfAbsent(uri,key->new GoRemoteSourceFile(source,store));
            if(closed||!store.isOpen()||!stores.contains(store)){files.remove(uri,file);return null;}
            Document document=FileDocumentManager.getInstance().getDocument(file);
            if(document!=null)document.setReadOnly(true);
            return file;
        }
        return null;
    }
    private void remove(GoRemoteStdlibSources store){
        stores.remove(store);
        var expired=files.values().stream().filter(file->!file.isValid()).toList();
        files.entrySet().removeIf(entry->!entry.getValue().isValid());
        ApplicationManager.getApplication().invokeLater(()->{
            if(project.isDisposed())return;
            var editors=FileEditorManager.getInstance(project);
            for(var file:expired)editors.closeFile(file);
        });
    }
    @Override public void close(){closed=true;List.copyOf(stores).forEach(GoRemoteStdlibSources::close);stores.clear();}
}

package dev.goforge.remote;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.redhat.devtools.lsp4ij.client.features.FileUriSupport;
import java.net.URI;
import java.util.function.Supplier;

/** Only already-verified session registry entries resolve; resolving a URI never starts SSH or fetches a file. */
public final class GoRemoteSourceFileUriSupport implements FileUriSupport {
    private final Supplier<Project> project;
    public GoRemoteSourceFileUriSupport(Supplier<Project> project){this.project=project;}
    @Override public URI getFileUri(VirtualFile file){return file instanceof GoRemoteSourceFile source?URI.create(source.sourceUri()):DEFAULT.getFileUri(file);}
    @Override public VirtualFile findFileByUri(String uri){
        if(uri!=null&&uri.startsWith("goforge-remote-source:")){
            Project selected=project.get();return selected==null||selected.isDisposed()?null:GoRemoteAnalysisService.getInstance(selected).sourceFile(uri);
        }
        return DEFAULT.findFileByUri(uri);
    }
    @Override public String toString(VirtualFile file){return file instanceof GoRemoteSourceFile source?source.sourceUri():DEFAULT.toString(file);}
    @Override public String toString(URI uri,boolean encoded){return DEFAULT.toString(uri,encoded);}
}

package dev.goforge.remote;

import com.intellij.testFramework.LightVirtualFile;
import dev.goforge.language.GoFileType;
import java.io.IOException;
import java.io.OutputStream;

/** A display-only immutable snapshot; it is never an LSP source document or a local filesystem path. */
public final class GoRemoteSourceFile extends LightVirtualFile {
    private final GoRemoteStdlibSources.Source source;
    private final GoRemoteStdlibSources owner;
    GoRemoteSourceFile(GoRemoteStdlibSources.Source source,GoRemoteStdlibSources owner){
        super(source.remotePath().substring(source.remotePath().lastIndexOf('/')+1),GoFileType.INSTANCE,source.text());
        this.source=source;this.owner=owner;super.setWritable(false);
    }
    public String sourceUri(){return source.uri();}
    public String remotePath(){return source.remotePath();}
    @Override public String getPath(){return source==null?super.getPath():"Remote "+source.origin().label()+" "+source.remotePath();}
    @Override public boolean isWritable(){return false;}
    @Override public boolean isValid(){return owner==null?super.isValid():owner.isOpen()&&super.isValid();}
    @Override public void setWritable(boolean value){if(value)throw new UnsupportedOperationException("Remote source is read-only.");}
    @Override public void setContent(Object requestor,CharSequence content,boolean fireEvent){throw new UnsupportedOperationException("Remote source is immutable.");}
    @Override public OutputStream getOutputStream(Object requestor,long stamp,long timestamp)throws IOException{throw readonly();}
    @Override public void rename(Object requestor,String name)throws IOException{throw readonly();}
    @Override public void delete(Object requestor)throws IOException{throw readonly();}
    @Override public void move(Object requestor,com.intellij.openapi.vfs.VirtualFile parent)throws IOException{throw readonly();}
    @Override public void setBinaryContent(byte[] bytes,long stamp,long timestamp)throws IOException{throw readonly();}
    @Override public void setBinaryContent(byte[] bytes,long stamp,long timestamp,Object requestor)throws IOException{throw readonly();}
    private static IOException readonly(){return new IOException("Remote source is read-only.");}
}

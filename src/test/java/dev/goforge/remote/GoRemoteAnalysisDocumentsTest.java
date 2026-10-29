package dev.goforge.remote;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Physical metadata buffers must be saved; Go overlays and unrelated in-memory files are preserved. */
public final class GoRemoteAnalysisDocumentsTest extends BasePlatformTestCase {
    private Path root;
    private final List<Document> documents=new ArrayList<>();
    @Override protected void setUp()throws Exception {
        super.setUp();root=FileUtil.createTempDirectory("goforge-analysis-documents-",null,true).toPath().toRealPath();
    }
    @Override protected void tearDown()throws Exception {
        try{
            for(var document:documents)manager().reloadFromDisk(document);
            if(root!=null)FileUtil.delete(root.toFile());
        }finally{super.tearDown();}
    }
    private static FileDocumentManager manager(){return FileDocumentManager.getInstance();}
    private Document physical(String name,String text)throws Exception {
        Path path=root.resolve(name);Files.createDirectories(path.getParent());Files.writeString(path,text);
        VirtualFile file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);assertNotNull(file);
        assertEquals(path,file.toNioPath().toAbsolutePath().normalize());
        Document document=manager().getDocument(file);assertNotNull(document);documents.add(document);return document;
    }
    private void edit(Document document,String text){WriteCommandAction.runWriteCommandAction(getProject(),()->document.setText(text));}

    public void testDirtyGoSourceIsAllowedButDirtyModuleMetadataRequiresSave()throws Exception {
        var source=physical("app/main.go","package app\n");
        var module=physical("app/go.mod","module fixture\n");
        edit(source,"package app\nvar Overlay=1\n");
        GoRemoteAnalysisDocuments.requireSavedMetadata(root);
        edit(module,"module changed\n");
        assertTrue(manager().isDocumentUnsaved(module));
        assertThrows(IllegalStateException.class,()->GoRemoteAnalysisDocuments.requireSavedMetadata(root));
        assertEquals("module fixture\n",Files.readString(root.resolve("app/go.mod")));
        assertEquals("module changed\n",module.getText());
        manager().saveDocument(module);GoRemoteAnalysisDocuments.requireSavedMetadata(root);
        assertEquals("module changed\n",Files.readString(root.resolve("app/go.mod")));
        assertTrue(manager().isDocumentUnsaved(source));
        assertEquals("package app\n",Files.readString(root.resolve("app/main.go")));
    }
    public void testDirtyWorkspaceAndSumAreMetadataButUnrelatedFilesAreNot()throws Exception {
        var work=physical("go.work","go 1.26\nuse ./app\n");
        var sum=physical("app/go.sum","initial\n");
        var note=physical("notes.txt","module text\n");
        for(var document:List.of(work,sum)){
            assertTrue(GoRemoteAnalysisDocuments.isMetadata(root,manager().getFile(document)));
            String saved=document.getText();edit(document,saved+"changed\n");
            assertThrows(IllegalStateException.class,()->GoRemoteAnalysisDocuments.requireSavedMetadata(root));
            assertEquals(saved,Files.readString(manager().getFile(document).toNioPath()));
            assertTrue(manager().isDocumentUnsaved(document));
            manager().saveDocument(document);GoRemoteAnalysisDocuments.requireSavedMetadata(root);
        }
        assertFalse(GoRemoteAnalysisDocuments.isMetadata(root,manager().getFile(note)));
        assertFalse(GoRemoteAnalysisDocuments.isMetadata(root.resolve("other"),manager().getFile(work)));
        edit(note,"unrelated unsaved\n");GoRemoteAnalysisDocuments.requireSavedMetadata(root);
        assertTrue(manager().isDocumentUnsaved(note));
    }
    public void testNonPhysicalLocalMetadataDoesNotClaimADiskFileOrBlockReview(){
        var file=myFixture.addFileToProject("go.work","go 1.26\n").getVirtualFile();
        assertTrue(file.isInLocalFileSystem());
        assertThrows(UnsupportedOperationException.class,file::toNioPath);
        assertFalse(GoRemoteAnalysisDocuments.isMetadata(root,file));
        var document=manager().getDocument(file);assertNotNull(document);documents.add(document);
        edit(document,"go 1.26\nuse ./unsaved\n");
        GoRemoteAnalysisDocuments.requireSavedMetadata(root);
        assertTrue(manager().isDocumentUnsaved(document));
        assertEquals("go 1.26\nuse ./unsaved\n",document.getText());
    }
}

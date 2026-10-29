package dev.goforge.remote;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.goforge.lsp.GoSourceDocumentMatcher;
import java.net.URI;
import org.junit.Assert;

public class GoRemoteSourceFilesPlatformTest extends BasePlatformTestCase {
    private GoRemoteSourceFiles files;
    private GoRemoteStdlibSources store;
    private GoRemoteStdlibSources.Source source;
    @Override protected void setUp()throws Exception{
        super.setUp();files=new GoRemoteSourceFiles(getProject());store=new GoRemoteStdlibSources();
        store.initialize(GoRemoteStdlibNavigationTest.json("{root:'/sdk/src',token:'"+"a".repeat(64)+"',version:'go1.fixture'}"));
        source=store.accept("fmt/print.go",GoRemoteStdlibNavigationTest.source("fmt/print.go","package fmt\nfunc Println() {}\n"));files.register(store);
    }
    @Override protected void tearDown()throws Exception{try{files.close();PlatformTestUtil.dispatchAllEventsInIdeEventQueue();}finally{super.tearDown();}}
    public void testSnapshotOpensAsReadOnlyGoDocumentAndRefusesWrite()throws Exception{
        var file=files.find(source.uri());assertNotNull(file);assertFalse(file.isInLocalFileSystem());
        var document=FileDocumentManager.getInstance().getDocument(file);assertNotNull(document);assertFalse(document.isWritable());
        var editor=FileEditorManager.getInstance(getProject()).openTextEditor(new OpenFileDescriptor(getProject(),file,17),true);assertNotNull(editor);
        assertEquals(source.text(),editor.getDocument().getText());assertEquals(17,editor.getCaretModel().getOffset());
        Assert.assertThrows(RuntimeException.class,()->WriteCommandAction.runWriteCommandAction(getProject(),()->document.insertString(0,"changed")));
        FileDocumentManager.getInstance().saveDocument(document);assertEquals(source.text(),file.getContent().toString());
        assertFalse(new GoSourceDocumentMatcher().match(file,getProject()));
    }
    public void testStopInvalidatesExactUriAndClosesOnlyOwnedSourceTab()throws Exception{
        var ordinary=myFixture.configureByText("local.go","package local\n").getVirtualFile();
        var file=files.find(source.uri());assertNotNull(FileEditorManager.getInstance(getProject()).openTextEditor(new OpenFileDescriptor(getProject(),file),true));
        store.close();PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertNull(files.find(source.uri()));assertFalse(file.isValid());assertFalse(FileEditorManager.getInstance(getProject()).isFileOpen(file));
        assertTrue(ordinary.isValid());assertTrue(FileEditorManager.getInstance(getProject()).isFileOpen(ordinary));
    }
    public void testDifferentTransportSameRemotePathCannotResolveOldOpaqueIdentity(){
        String first=source.uri();store.close();
        var next=new GoRemoteStdlibSources();next.initialize(GoRemoteStdlibNavigationTest.json("{root:'/sdk/src',token:'"+"a".repeat(64)+"',version:'go1.fixture'}"));
        var second=next.accept("fmt/print.go",GoRemoteStdlibNavigationTest.source("fmt/print.go",source.text()));files.register(next);
        assertFalse(first.equals(second.uri()));assertNull(files.find(first));assertNotNull(files.find(second.uri()));
    }
    public void testClientUriSupportResolvesPublishedRegistryEntriesThroughLspPsiFactory(){
        var other=new GoRemoteStdlibSources();other.initialize(GoRemoteStdlibNavigationTest.json("{root:'/sdk/src',token:'"+"a".repeat(64)+"',version:'go1.fixture'}"));
        var snapshot=other.accept("fmt/print.go",GoRemoteStdlibNavigationTest.source("fmt/print.go",source.text()));
        GoRemoteAnalysisService.getInstance(getProject()).registerSourceStore(other);
        try{
            var uris=new GoRemoteSourceFileUriSupport(()->getProject());var file=uris.findFileByUri(snapshot.uri());assertNotNull(file);
            assertEquals(URI.create(snapshot.uri()),uris.getFileUri(file));assertEquals(snapshot.uri(),uris.toString(file));
            var location=new org.eclipse.lsp4j.Location(snapshot.uri(),new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(1,5),new org.eclipse.lsp4j.Position(1,12)));
            var target=com.redhat.devtools.lsp4ij.features.LSPPsiElementFactory.toPsiElement(location,uris,getProject());
            assertNotNull(target);assertEquals(file,target.getContainingFile().getVirtualFile());
            assertNull(uris.findFileByUri("goforge-remote-source://unknown/unknown"));
            other.close();assertNull(uris.findFileByUri(snapshot.uri()));
        }finally{other.close();}
    }
}

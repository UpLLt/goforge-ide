package dev.goforge.remote;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.junit.Assert;

/** Editor lifetime only; no language server, SSH connection or global settings are changed. */
public final class GoRemoteModuleSourcePlatformTest extends BasePlatformTestCase {
    private GoRemoteStdlibSources store;
    private GoRemoteSourceFiles files;
    private GoRemoteStdlibSources.Source module, sdk;
    @Override protected void setUp()throws Exception{
        super.setUp();store=new GoRemoteStdlibSources();files=new GoRemoteSourceFiles(getProject());
        store.initialize(GoRemoteModuleSourcesTest.json("{root:'/sdk/src',token:'"+"a".repeat(64)+"',version:'go1.fixture'}"));
        store.initializeModules(GoRemoteModuleSourcesTest.catalog());
        module=store.accept(store.target("file://"+GoRemoteModuleSourcesTest.ROOT+"/dep.go"),GoRemoteModuleSourcesTest.data("dep.go",GoRemoteModuleSourcesTest.TOKEN,GoRemoteModuleSourcesTest.TEXT));
        sdk=store.accept("fmt/print.go",GoRemoteModuleSourcesTest.data("fmt/print.go","a".repeat(64),"package fmt\nfunc Println() {}\n"));
        files.register(store);
    }
    @Override protected void tearDown()throws Exception{
        try{files.close();PlatformTestUtil.dispatchAllEventsInIdeEventQueue();}finally{super.tearDown();}
    }
    public void testModuleLabelCaretDocumentAndVfsStayReadOnly(){
        var file=files.find(module.uri());assertNotNull(file);assertFalse(file.isInLocalFileSystem());
        assertTrue(file.getPath().contains("example.test/Upper/dep@v1.1.0"));
        var editor=FileEditorManager.getInstance(getProject()).openTextEditor(new OpenFileDescriptor(getProject(),file,17),true);assertNotNull(editor);
        assertEquals(module.text(),editor.getDocument().getText());assertEquals(17,editor.getCaretModel().getOffset());
        assertFalse(editor.getDocument().isWritable());assertFalse(file.isWritable());
        Assert.assertThrows(RuntimeException.class,()->WriteCommandAction.runWriteCommandAction(getProject(),()->editor.getDocument().insertString(0,"changed")));
        FileDocumentManager.getInstance().saveDocument(editor.getDocument());assertEquals(module.text(),file.getContent().toString());
        assertFalse(new dev.goforge.lsp.GoSourceDocumentMatcher().match(file,getProject()));
    }
    public void testStopClosesModuleAndSdkTabsButPreservesOrdinaryTab(){
        var ordinary=myFixture.configureByText("local.txt","preserve").getVirtualFile();
        var moduleFile=files.find(module.uri());var sdkFile=files.find(sdk.uri());
        var editors=FileEditorManager.getInstance(getProject());
        assertNotNull(editors.openTextEditor(new OpenFileDescriptor(getProject(),moduleFile),true));
        assertNotNull(editors.openTextEditor(new OpenFileDescriptor(getProject(),sdkFile),true));
        store.close();PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertNull(files.find(module.uri()));assertNull(files.find(sdk.uri()));
        assertFalse(moduleFile.isValid());assertFalse(sdkFile.isValid());assertFalse(editors.isFileOpen(moduleFile));assertFalse(editors.isFileOpen(sdkFile));
        assertTrue(ordinary.isValid());assertTrue(editors.isFileOpen(ordinary));
    }
    public void testModuleOpaqueUriResolvesThroughLspPsiFactoryAndExpires(){
        GoRemoteAnalysisService.getInstance(getProject()).registerSourceStore(store);
        var uris=new GoRemoteSourceFileUriSupport(()->getProject());var file=uris.findFileByUri(module.uri());assertNotNull(file);
        var location=new org.eclipse.lsp4j.Location(module.uri(),new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(1,5),new org.eclipse.lsp4j.Position(1,10)));
        var element=com.redhat.devtools.lsp4ij.features.LSPPsiElementFactory.toPsiElement(location,uris,getProject());
        assertNotNull(element);assertEquals(file,element.getContainingFile().getVirtualFile());
        store.close();assertNull(uris.findFileByUri(module.uri()));
        try(var next=new GoRemoteStdlibSources()){
            next.initializeModules(GoRemoteModuleSourcesTest.catalog());
            var copy=next.accept(next.target("file://"+GoRemoteModuleSourcesTest.ROOT+"/dep.go"),GoRemoteModuleSourcesTest.data("dep.go",GoRemoteModuleSourcesTest.TOKEN,module.text()));
            assertFalse(copy.uri().equals(module.uri()));assertNull(next.find(module.uri()));
        }
    }
}

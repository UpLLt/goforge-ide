package dev.goforge.debug;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.TransportStreams;
import com.redhat.devtools.lsp4ij.dap.client.DAPClient;
import com.redhat.devtools.lsp4ij.dap.client.DAPStackFrame;
import com.redhat.devtools.lsp4ij.settings.ServerTrace;
import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class GoRemoteDapSourcesPlatformTest extends BasePlatformTestCase {
    private final List<GoRemoteDapSources> owners=new ArrayList<>();
    private final List<DAPClient> clients=new ArrayList<>();
    @Override protected void tearDown()throws Exception{
        new com.intellij.testFramework.RunAll(()->{for(var owner:owners)owner.close();PlatformTestUtil.dispatchAllEventsInIdeEventQueue();},
                ()->{for(var client:clients)client.dispose();},()->super.tearDown()).run();
    }
    private DAPClient client(String config)throws Exception{
        var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        var process=(FakeProcess)((sun.misc.Unsafe)field.get(null)).allocateInstance(FakeProcess.class);process.identity=System.nanoTime();Project project=getProject();
        var client=new GoRemoteDapClient(project,process,Map.of("name",config),true,DebugMode.LAUNCH,null);clients.add(client);return client;
    }
    private GoRemoteDapSources owner(DAPClient client){var owner=new GoRemoteDapSources(getProject(),client.getConfigName(),client.getSessionId());owners.add(owner);owner.newPause();return owner;}
    private static StackTraceResponse stack(String path,int reference,String text)throws Exception{
        var frame=new StackFrame();frame.setId(1);frame.setName("fmt.callback");frame.setLine(2);frame.setColumn(1);frame.setSource(GoRemoteDapSourcesTest.source(path,reference,text));
        var response=new StackTraceResponse();response.setStackFrames(new StackFrame[]{frame});return response;
    }
    private static IDebugProtocolServer content(String text){return new IDebugProtocolServer(){@Override public CompletableFuture<SourceResponse> source(SourceArguments args){var value=new SourceResponse();value.setContent(text);return CompletableFuture.completedFuture(value);}};}
    private static <T>T await(CompletableFuture<T> future)throws Exception{PlatformTestUtil.waitWithEventsDispatching("SDK source did not load",future::isDone,5);return future.get(1,TimeUnit.SECONDS);}
    public void testSelectedModuleUsesActualReadonlyNavigationAndStopInvalidatesSnapshot()throws Exception{
        String text="package dep\n// selected v1.1.0 世界\n";var client=client("module-"+UUID.randomUUID());var owner=owner(client);
        var response=stack("dep.go",1000000,text);response.getStackFrames()[0].setSource(GoRemoteDapSourcesTest.moduleSource("example.test/dep","v1.1.0","dep.go",1000000,text));
        response=await(owner.prepare(response,content(text)));assertEquals("Selected module example.test/dep@v1.1.0 — dep.go",response.getStackFrames()[0].getSource().getName());
        var position=new DAPStackFrame(client,response.getStackFrames()[0]).getSourcePosition();assertNotNull(position);position.createNavigatable(getProject()).navigate(true);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();var editor=FileEditorManager.getInstance(getProject()).getSelectedTextEditor();assertNotNull(editor);
        var document=FileDocumentManager.getInstance().getDocument(position.getFile());assertSame(document,editor.getDocument());assertEquals(1,editor.getCaretModel().getLogicalPosition().line);assertEquals(0,editor.getCaretModel().getLogicalPosition().column);
        assertEquals(text,document.getText());assertFalse(document.isWritable());assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(document));
        org.junit.Assert.assertThrows(com.intellij.openapi.editor.ReadOnlyModificationException.class,()->WriteAction.run(()->document.insertString(0,"bad")));
        owner.close();PlatformTestUtil.dispatchAllEventsInIdeEventQueue();assertFalse(position.getFile().isValid());assertFalse(FileEditorManager.getInstance(getProject()).isFileOpen(position.getFile()));assertEquals("",document.getText());assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(document));
    }
    public void testActualDapStackUsesVerifiedReadonlyDocumentAtExactLine()throws Exception{
        String text="package fmt\n// line two 世界\n";var client=client("sdk-"+UUID.randomUUID());var owner=owner(client);var response=await(owner.prepare(stack("fmt/print.go",1000000,text),content(text)));
        var position=new DAPStackFrame(client,response.getStackFrames()[0]).getSourcePosition();assertNotNull(position);assertEquals(1,position.getLine());
        var document=FileDocumentManager.getInstance().getDocument(position.getFile());assertNotNull(document);assertEquals(text,document.getText());assertFalse(document.isWritable());assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(document));
        // LightVirtualFile positions are line based and legally report offset -1. Verify real navigation.
        position.createNavigatable(getProject()).navigate(true);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        var editor=FileEditorManager.getInstance(getProject()).getSelectedTextEditor();assertNotNull(editor);
        assertSame(document,editor.getDocument());assertSame(position.getFile(),FileDocumentManager.getInstance().getFile(editor.getDocument()));
        assertEquals(1,editor.getCaretModel().getLogicalPosition().line);assertEquals(0,editor.getCaretModel().getLogicalPosition().column);assertEquals(text.indexOf("//"),editor.getCaretModel().getOffset());
        org.junit.Assert.assertThrows(com.intellij.openapi.editor.ReadOnlyModificationException.class,()->WriteAction.run(()->document.insertString(0,"bad")));
        assertEquals("PLAIN_TEXT",position.getFile().getFileType().getName());
    }
    public void testSameBasenameAndSameNamedUserConfigurationRemainSessionIsolated()throws Exception{
        String a="package fmt\n// first\n",b="package io\n// second\n";var first=client("Remote debug .");var second=client("Remote debug .");assertFalse(first.getConfigName().equals(second.getConfigName()));var firstOwner=owner(first);
        var left=await(firstOwner.prepare(stack("fmt/same.go",1000000,a),content(a)));var right=await(firstOwner.prepare(stack("io/same.go",1000001,b),content(b)));
        var other=await(owner(second).prepare(stack("fmt/same.go",1000000,b),content(b)));
        var f1=new DAPStackFrame(first,left.getStackFrames()[0]).getSourcePosition().getFile();var f2=new DAPStackFrame(first,right.getStackFrames()[0]).getSourcePosition().getFile();var f3=new DAPStackFrame(second,other.getStackFrames()[0]).getSourcePosition().getFile();
        assertNotSame(f1,f2);assertNotSame(f1,f3);assertEquals(a,FileDocumentManager.getInstance().getDocument(f1).getText());assertEquals(b,FileDocumentManager.getInstance().getDocument(f3).getText());
    }
    public void testFailedSourceReadDoesNotPoisonSubsequentLoad()throws Exception{
        String text="package fmt\n// second\n";var client=client("sdk-"+UUID.randomUUID());var owner=owner(client);
        IDebugProtocolServer failure=new IDebugProtocolServer(){@Override public CompletableFuture<SourceResponse> source(SourceArguments args){return CompletableFuture.failedFuture(new IllegalArgumentException("expired"));}};
        var failed=await(owner.prepare(stack("fmt/print.go",1000000,text),failure));assertEquals(Integer.valueOf(0),failed.getStackFrames()[0].getSource().getSourceReference());
        var retry=await(owner.prepare(stack("fmt/print.go",1000000,text),content(text)));var position=new DAPStackFrame(client,retry.getStackFrames()[0]).getSourcePosition();assertEquals(text,FileDocumentManager.getInstance().getDocument(position.getFile()).getText());
    }
    public void testStopCancelsPendingReadsWithoutCreatingLateDocument()throws Exception{
        String text="package fmt\n// pending\n";var client=client("sdk-"+UUID.randomUUID());var owner=owner(client);var pending=new CompletableFuture<SourceResponse>();
        IDebugProtocolServer delayed=new IDebugProtocolServer(){@Override public CompletableFuture<SourceResponse> source(SourceArguments args){return pending;}};
        var result=owner.prepare(stack("fmt/print.go",1000000,text),delayed);owner.close();PlatformTestUtil.dispatchAllEventsInIdeEventQueue();assertTrue(pending.isCancelled());assertTrue(result.isCompletedExceptionally());
        var late=new SourceResponse();late.setContent(text);assertFalse(pending.complete(late));
    }
    public void testResumeAfterSourceResponseBeforeEdtInstallRejectsLateDocument()throws Exception{
        String text="package fmt\n// queued\n";var client=client("sdk-"+UUID.randomUUID());var owner=owner(client);
        var result=owner.prepare(stack("fmt/print.go",1000000,text),content(text));
        // source() has synchronously completed but its EDT installation is queued.
        assertFalse(result.isDone());owner.resume();PlatformTestUtil.dispatchAllEventsInIdeEventQueue();assertTrue(result.isCompletedExceptionally());
        owner.newPause();var next=await(owner.prepare(stack("fmt/print.go",1000000,text),content(text)));
        var position=new DAPStackFrame(client,next.getStackFrames()[0]).getSourcePosition();assertEquals(text,FileDocumentManager.getInstance().getDocument(position.getFile()).getText());
    }
    public void testActualContinueServerMethodRevokesQueuedSourceInstall()throws Exception{
        String text="package fmt\n// queued\n";var client=client("sdk-"+UUID.randomUUID());var owner=owner(client);
        var raw=new IDebugProtocolServer(){@Override public CompletableFuture<ContinueResponse> continue_(ContinueArguments args){return CompletableFuture.completedFuture(new ContinueResponse());}};
        var wrapped=owner.wrap(raw);var result=owner.prepare(stack("fmt/print.go",1000000,text),content(text));
        wrapped.continue_(new ContinueArguments());PlatformTestUtil.dispatchAllEventsInIdeEventQueue();assertTrue(result.isCompletedExceptionally());
    }
    public void testStopClosesAndInvalidatesOnlyItsOwnedDocuments()throws Exception{
        String text="package fmt\n// snapshot\n";var client=client("sdk-"+UUID.randomUUID());var owner=owner(client);var loaded=await(owner.prepare(stack("fmt/print.go",1000000,text),content(text)));
        var file=new DAPStackFrame(client,loaded.getStackFrames()[0]).getSourcePosition().getFile();var unrelated=new com.intellij.testFramework.LightVirtualFile("unrelated.txt","keep");var document=FileDocumentManager.getInstance().getDocument(file);assertNotNull(document);var editors=FileEditorManager.getInstance(getProject());
        try {editors.openFile(file,true);editors.openFile(unrelated,true);owner.close();PlatformTestUtil.dispatchAllEventsInIdeEventQueue();assertFalse(editors.isFileOpen(file));assertFalse(file.isValid());assertTrue(editors.isFileOpen(unrelated));assertEquals("",document.getText());assertFalse(document.isWritable());assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(document));assertTrue(com.intellij.psi.PsiDocumentManager.getInstance(getProject()).isCommitted(document));}
        finally {editors.closeFile(unrelated);}
    }
    public static final class FakeProcess extends DAPDebugProcess {
        private long identity;
        private FakeProcess(){super(null,null,null,true);}
        @Override public long getSessionId(){return identity;}
        @Override public Supplier<TransportStreams> getStreamsSupplier(){return ()->new TransportStreams.DefaultTransportStreams(InputStream.nullInputStream(),OutputStream.nullOutputStream());}
    }
}

package dev.goforge.remote;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.util.*;

public final class GoRemoteDebugIntegrationTest extends BasePlatformTestCase {
    public void testDebugTabScrollsFieldsAndKeepsReviewButtonVisibleWithoutConnecting(){
        var panel=new GoRemotePanel(getProject());
        try{
            var tabs=(JTabbedPane)panel.getComponent(0);assertEquals("Go debug",tabs.getTitleAt(3));tabs.setSelectedIndex(3);panel.setSize(900,280);layout(panel);
            var page=(JPanel)tabs.getSelectedComponent();var scroll=(JScrollPane)((BorderLayout)page.getLayout()).getLayoutComponent(BorderLayout.CENTER);var actions=(JPanel)((BorderLayout)page.getLayout()).getLayoutComponent(BorderLayout.SOUTH);
            assertTrue(scroll.getViewport().getHeight()>80);assertTrue(scroll.getViewport().getViewSize().height>scroll.getViewport().getExtentSize().height);
            assertEquals("Review and debug…",((JButton)actions.getComponent(0)).getText());assertTrue(actions.getY()>=scroll.getY()+scroll.getHeight());assertTrue(actions.getY()+actions.getHeight()<=page.getHeight());
        }finally{panel.dispose();}
    }
    public void testUnsavedPhysicalDocumentIsRejectedWithoutSavingOrChangingIt()throws Exception {
        var root=FileUtil.createTempDirectory("goforge-debug-unsaved-",null,true).toPath().toRealPath();var path=root.resolve("saved-state.txt");Files.writeString(path,"saved\n");
        var file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);assertNotNull(file);var manager=FileDocumentManager.getInstance();var document=manager.getDocument(file);assertNotNull(document);
        try{
            WriteAction.run(()->document.setText("unsaved\n"));assertTrue(manager.isDocumentUnsaved(document));
            try{GoRemoteDebugDocuments.requireSaved(root.toString());fail("Unsaved source must require an explicit save/revert");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("Save or revert"));}
            assertEquals("saved\n",Files.readString(path));assertEquals("unsaved\n",document.getText());assertTrue(manager.isDocumentUnsaved(document));
        }finally{WriteAction.run(()->document.setText("saved\n"));manager.saveDocument(document);}
        GoRemoteDebugDocuments.requireSaved(root.toString());
    }
    public void testUnrelatedUnsavedDocumentDoesNotBlockSelectedWorkspace()throws Exception {
        var root=FileUtil.createTempDirectory("goforge-debug-selected-",null,true).toPath().toRealPath();var other=FileUtil.createTempDirectory("goforge-debug-other-",null,true).toPath().toRealPath();var path=other.resolve("note.txt");Files.writeString(path,"saved");
        var file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);assertNotNull(file);var manager=FileDocumentManager.getInstance();var document=manager.getDocument(file);assertNotNull(document);
        try{WriteAction.run(()->document.setText("unsaved"));GoRemoteDebugDocuments.requireSaved(root.toString());assertTrue(manager.isDocumentUnsaved(document));}finally{WriteAction.run(()->document.setText("saved"));manager.saveDocument(document);}
    }
    public void testWorkspaceReviewRejectsUnsavedMetadataAndOtherModuleWithoutSaving()throws Exception {
        var root=FileUtil.createTempDirectory("goforge-debug-workspace-unsaved-",null,true).toPath().toRealPath();
        Files.createDirectories(root.resolve("second module"));
        var paths=java.util.List.of(root.resolve("go.work"),root.resolve("second module/other.go"));
        var manager=FileDocumentManager.getInstance();
        for(var path:paths){
            Files.writeString(path,"saved\n");var file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);assertNotNull(file);var document=manager.getDocument(file);assertNotNull(document);
            try{WriteAction.run(()->document.setText("unsaved\n"));
                try{GoRemoteDebugDocuments.requireSaved(root.toString());fail("All workspace modules and go.work must be saved before review");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("Save or revert"));}
                assertEquals("saved\n",Files.readString(path));assertEquals("unsaved\n",document.getText());assertTrue(manager.isDocumentUnsaved(document));
            }finally{WriteAction.run(()->document.setText("saved\n"));manager.saveDocument(document);}
        }
        GoRemoteDebugDocuments.requireSaved(root.toString());
    }
    public void testSessionStoreIsBoundedAndDisposeInvalidatesAllReviews(){
        var service=new GoRemoteDebugSessions();var plan=new GoRemoteDebugPlan(new GoRemoteProfile("fixture.invalid","",22,"","/workspace","go",""),"/local","dlv","debug",".",0,Map.of("go.mod","a".repeat(64)),java.util.List.of(),Map.of());
        String first=service.register(plan),last=first;for(int i=0;i<16;i++)last=service.register(plan);
        try{service.require(first);fail("Old session must expire rather than retain all private options forever");}catch(IllegalStateException expected){}
        assertSame(plan,service.require(last));service.dispose();try{service.require(last);fail("Disposed session must be invalid");}catch(IllegalStateException expected){}
    }
    private void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container container)layout(container);}
}

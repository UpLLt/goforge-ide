package dev.goforge.remote;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import javax.swing.*;
import java.awt.*;

public final class GoRemoteIntegrationTest extends BasePlatformTestCase {
    public void testProjectSettingsRoundtripContainsOnlyCoordinates() {
        var state=new GoRemoteSettings();state.host="fixture.invalid";state.user="developer";state.remoteDirectory="/workspace";state.port=2222;
        var service=GoRemoteSettings.getInstance(getProject());service.loadState(state);
        var p=service.profile();assertEquals("fixture.invalid",p.host());assertEquals("/workspace",p.remoteDirectory());assertEquals(2222,p.port());
        assertEquals("",p.keyFile());assertEquals("",p.knownHostsFile());
        service.loadState(new GoRemoteSettings());
    }
    public void testToolWindowPanelHasWorkspaceAndConnectionWithoutContactingAnyHost() {
        var settings=GoRemoteSettings.getInstance(getProject());settings.loadState(new GoRemoteSettings());
        var panel=new GoRemotePanel(getProject());
        try {assertInstanceOf(panel.getComponent(0),JTabbedPane.class);var tabs=(JTabbedPane)panel.getComponent(0);
            assertEquals("Workspace",tabs.getTitleAt(0));assertEquals("Connection",tabs.getTitleAt(1));assertEquals(1,tabs.getSelectedIndex());
        }finally{panel.dispose();}
    }
    public void testShortConnectionPanelScrollsAllFieldsWithFixedActionButtons() {
        GoRemoteSettings.getInstance(getProject()).loadState(new GoRemoteSettings());
        var panel=new GoRemotePanel(getProject());
        try {
            var tabs=(JTabbedPane)panel.getComponent(0);tabs.setSelectedIndex(1);
            panel.setSize(900,280);layoutTree(panel);
            var connection=(JPanel)tabs.getSelectedComponent();
            var scroll=(JScrollPane)((BorderLayout)connection.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            var actions=(JPanel)((BorderLayout)connection.getLayout()).getLayoutComponent(BorderLayout.SOUTH);
            assertTrue("Connection fields need a real visible viewport",scroll.getViewport().getHeight()>80);
            assertTrue("The form must be scrollable at a short tool-window height",scroll.getViewport().getViewSize().height>scroll.getViewport().getExtentSize().height);
            for(Component button:actions.getComponents()) {
                assertInstanceOf(button,JButton.class);
                Rectangle bounds=SwingUtilities.convertRectangle(actions,button.getBounds(),connection);
                assertTrue("Save/Test buttons stay fully within the panel",connection.getBounds().getSize().height>=bounds.y+bounds.height);
                assertTrue("Buttons cannot overlap the scrolling fields",bounds.y>=scroll.getY()+scroll.getHeight());
                assertTrue(button.getHeight()>15);
            }
            var content=(JPanel)scroll.getViewport().getView();
            var form=(JPanel)((BorderLayout)content.getLayout()).getLayoutComponent(BorderLayout.NORTH);
            var lastField=(JTextField)form.getComponent(form.getComponentCount()-1);
            assertEquals("go",lastField.getText());
            Rectangle target=SwingUtilities.convertRectangle(form,lastField.getBounds(),content);
            content.scrollRectToVisible(target);layoutTree(scroll);
            assertTrue("The final Go executable field is reachable by scrolling",scroll.getViewport().getViewRect().contains(target));
        } finally {panel.dispose();}
    }
    public void testShortWorkspacePanelKeepsActionsAndFileAndLogPanesVisible() {
        var panel=new GoRemotePanel(getProject());
        try {
            var tabs=(JTabbedPane)panel.getComponent(0);tabs.setSelectedIndex(0);
            panel.setSize(900,280);layoutTree(panel);
            var workspace=(JPanel)tabs.getSelectedComponent();
            var split=(com.intellij.ui.OnePixelSplitter)((BorderLayout)workspace.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            assertTrue("File listing must have room for a header and rows",split.getFirstComponent().getHeight()>70);
            assertTrue("Log pane remains readable",split.getSecondComponent().getHeight()>70);
            assertTrue(split.getFirstComponent().getWidth()>150);assertTrue(split.getSecondComponent().getWidth()>150);
            var top=(JPanel)((BorderLayout)workspace.getLayout()).getLayoutComponent(BorderLayout.NORTH);
            var buttons=(JPanel)((BorderLayout)top.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            for(Component button:buttons.getComponents()) {
                assertTrue(button.getHeight()>15);
                Rectangle bounds=SwingUtilities.convertRectangle(buttons,button.getBounds(),workspace);
                assertTrue("Every action stays above the file/log split",bounds.y+bounds.height<=split.getY());
            }
        } finally {panel.dispose();}
    }
    public void testInvalidConnectionShowsFeedbackWithoutConnecting() {
        GoRemoteSettings.getInstance(getProject()).loadState(new GoRemoteSettings());
        var panel=new GoRemotePanel(getProject());
        try {
            var tabs=(JTabbedPane)panel.getComponent(0);
            var connection=(JPanel)tabs.getSelectedComponent();
            var actions=(JPanel)((BorderLayout)connection.getLayout()).getLayoutComponent(BorderLayout.SOUTH);
            ((JButton)actions.getComponent(1)).doClick();
            assertEquals("The result pane becomes visible even for profile validation errors",0,tabs.getSelectedIndex());
            var workspace=(JPanel)tabs.getSelectedComponent();
            var split=(com.intellij.ui.OnePixelSplitter)((BorderLayout)workspace.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            var log=(JTextArea)((JScrollPane)split.getSecondComponent()).getViewport().getView();
            assertTrue(log.getText().contains("Invalid connection/action:"));
        } finally {panel.dispose();}
    }
    public void testRemoteAnalysisIsExplicitAndNotRestoredWithProfile() {
        var settings=GoRemoteSettings.getInstance(getProject());var saved=new GoRemoteSettings();saved.host="fixture.invalid";saved.remoteDirectory="/fixture";saved.goplsExecutable="/opt/go/bin/gopls";settings.loadState(saved);
        var analysis=GoRemoteAnalysisService.getInstance(getProject());
        assertNull("Restoring coordinates must never connect a user host",analysis.active());
        assertNull(analysis.connectionProvider());assertEquals("Local Go analysis",analysis.status());
        var panel=new GoRemotePanel(getProject());
        try {
            var tabs=(JTabbedPane)panel.getComponent(0);assertEquals("Go analysis",tabs.getTitleAt(2));tabs.setSelectedIndex(2);panel.setSize(900,280);layoutTree(panel);
            var pane=(JPanel)tabs.getSelectedComponent();var actions=(JPanel)((BorderLayout)pane.getLayout()).getLayoutComponent(BorderLayout.SOUTH);
            assertEquals("Start remote analysis…",((JButton)actions.getComponent(0)).getText());assertEquals("Stop remote analysis",((JButton)actions.getComponent(1)).getText());
            var scroll=(JScrollPane)((BorderLayout)pane.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            assertTrue(scroll.getViewport().getHeight()>70);assertTrue(actions.getY()>=scroll.getY()+scroll.getHeight());
            assertNull(analysis.active());
        }finally{panel.dispose();settings.loadState(new GoRemoteSettings());}
    }
    private static void layoutTree(Container component) {
        component.doLayout();
        for(Component child:component.getComponents())if(child instanceof Container container)layoutTree(container);
    }
}

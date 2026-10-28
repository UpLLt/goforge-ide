package dev.goforge.containers;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.OnePixelSplitter;
import javax.swing.*;
import java.awt.*;
import java.util.List;

public final class DockerPanelTest extends BasePlatformTestCase {
    public void testInitialLayoutLeavesInventoryAndOutputUsable() {
        var panel=new DockerPanel(getProject(),DockerService.getInstance(getProject()));
        var split=(OnePixelSplitter)((BorderLayout)panel.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        try { panel.setSize(1000,600);panel.doLayout();split.doLayout(); assertTrue(split.getFirstComponent().getHeight()>100); assertTrue(split.getSecondComponent().getHeight()>80); }
        finally { split.dispose(); }
    }
    public void testSwitchingContextClearsOldInventoryAndRejectsLateOldSnapshot() {
        var panel=new DockerPanel(getProject(),DockerService.getInstance(getProject()));
        panel.setContexts(List.of(new DockerData.Context("one","",true,""),new DockerData.Context("two","",false,"")));
        var row=new DockerData.Container("a".repeat(64),"fixture","image","running","Up","none");
        panel.inventory("one","Connected",List.of(row),List.of());
        JTable table=find(panel,JTable.class).getFirst(); assertEquals(1,table.getRowCount());
        find(panel,JComboBox.class).getFirst().setSelectedIndex(1); assertEquals(0,table.getRowCount());
        panel.inventory("one","Late stale connection",List.of(row),List.of()); assertEquals(0,table.getRowCount());
        panel.inventory("two","Connected",List.of(row),List.of()); assertEquals(1,table.getRowCount());
        assertEquals("a".repeat(64),table.getValueAt(0,5));
    }
    public void testBusyDisablesMutationAndKeepsCancellationAvailable() {
        var panel=new DockerPanel(getProject(),DockerService.getInstance(getProject())); panel.busy(true,"Working");
        assertEquals("Exactly one cancellation action must remain available",1L,find(panel,JButton.class).stream().filter(b->"Cancel".equals(b.getText())).count());
        for(JComboBox<?> selector:find(panel,JComboBox.class))assertFalse("All selectors must be disabled during an operation",selector.isEnabled());
        for(JButton button:find(panel,JButton.class)) { if("Cancel".equals(button.getText())) assertTrue(button.isEnabled()); else if(button.getText()!=null && !button.getText().isBlank()) assertFalse(button.getText(),button.isEnabled()); }
    }
    public void testSelectingContainerActionDoesNotRunUntilTheExplicitButtonIsPressed() {
        var service=DockerService.getInstance(getProject());var panel=new DockerPanel(getProject(),service);
        JComboBox<?> selector=find(panel,JComboBox.class).stream().filter(c->"container-action".equals(c.getName())).findFirst().orElseThrow();
        JTextArea output=find(panel,JTextArea.class).getFirst();panel.output("No action has run");
        for(int i=0;i<selector.getItemCount();i++){selector.setSelectedIndex(i);assertEquals("Selection must not execute a container action","No action has run",output.getText());assertFalse(service.isBusy());}
        selector.setSelectedItem("Stop");find(panel,JButton.class).stream().filter(b->"Run action".equals(b.getText())).findFirst().orElseThrow().doClick();
        assertEquals("Explicit execution still requires a selected container","Select one container first.",output.getText());assertFalse(service.isBusy());
        panel.busy(true,"Working");assertFalse(selector.isEnabled());panel.busy(false,"Ready");assertTrue(selector.isEnabled());
    }
    private static <T> java.util.List<T> find(Container parent,Class<T> type) {
        var found=new java.util.ArrayList<T>(); for(Component child:parent.getComponents()) { if(type.isInstance(child))found.add(type.cast(child)); if(child instanceof Container c)found.addAll(find(c,type)); } return found;
    }
}

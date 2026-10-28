package dev.goforge.containers;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import javax.swing.*;
import java.awt.*;
import java.util.List;

/** Tests actual viewport sizes after the enclosing tool-window tabs consume their height. */
public final class ContainersCompactLayoutTest extends BasePlatformTestCase {
    public void testDockerListAndOutputRemainVisibleAt280Pixels(){
        var panel=new DockerPanel(getProject(),DockerService.getInstance(getProject()));layout(panel);
        JTable table=find(panel,JTable.class).getFirst();JScrollPane list=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,table);
        assertTrue("Docker first data row must be fully visible: "+list.getViewport().getExtentSize(),list.getViewport().getExtentSize().height>=table.getRowHeight());
        JTextArea output=find(panel,JTextArea.class).getFirst();assertReadableOutput(output,2);
    }
    public void testKubernetesTableAndOutputRemainVisibleAt280Pixels(){
        var panel=new KubernetesPanel(getProject());layout(panel);JTable table=find(panel,JTable.class).getFirst();JScrollPane list=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,table);
        assertTrue("Kubernetes data row must be visible",list.getViewport().getExtentSize().height>=table.getRowHeight());assertReadableOutput(find(panel,JTextArea.class).getFirst(),2);
    }
    public void testDevContainerReviewHasUsableScrollableAreaAt280Pixels(){var panel=new DevContainerPanel(getProject());layout(panel);assertReadableOutput(find(panel,JTextArea.class).getFirst(),5);}
    private void layout(JPanel panel){JTabbedPane toolTabs=new JTabbedPane();toolTabs.addTab("Containers",panel);toolTabs.setSize(900,280);for(int i=0;i<3;i++)layoutTree(toolTabs);}
    private void layoutTree(Container component){component.doLayout();for(Component child:component.getComponents())if(child instanceof Container c)layoutTree(c);}
    private void assertReadableOutput(JTextArea area,int lines){JScrollPane scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,area);int height=area.getFontMetrics(area.getFont()).getHeight();assertTrue("Expected "+lines+" visible output rows; actual viewport="+scroll.getViewport().getExtentSize(),scroll.getViewport().getExtentSize().height>=lines*height);}
    private static <T> List<T> find(Container parent,Class<T> type){var result=new java.util.ArrayList<T>();for(Component child:parent.getComponents()){if(type.isInstance(child))result.add(type.cast(child));if(child instanceof Container c)result.addAll(find(c,type));}return result;}
}

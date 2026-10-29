package dev.goforge.http;

import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.sun.net.httpserver.HttpServer;
import javax.swing.*;
import java.awt.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real Swing action wiring and local HTTP requests; no native GUI acceptance is implied. */
public final class HttpCollectionPanelTest extends BasePlatformTestCase {
    public void testRunAllReparsesEditedSourceAndPublishesEveryResult()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var hits=new AtomicInteger();server.createContext("/",exchange->{hits.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();var service=new HttpClientService(getProject());
        try{var panel=new HttpClientPanel(getProject(),service);String request="# @expect status 200\nGET http://127.0.0.1:"+server.getAddress().getPort()+"/";panel.load(request,0,false);source(panel).setText(request+"\n### appended\n"+request);button(panel,"Run All").doClick();JTable table=find(panel,JTable.class).getFirst();await(()->service.collection==null&&table.getRowCount()==2);assertEquals(2,hits.get());assertEquals(HttpCollectionRunner.Status.PASSED,table.getValueAt(0,1));assertEquals(HttpCollectionRunner.Status.PASSED,table.getValueAt(1,1));assertTrue(button(panel,"Save Summary…").isEnabled());}
        finally{service.dispose();server.stop(0);}
    }
    public void testNextFailureNavigatesToFailedAssertionButDoesNotMisnavigateEditedSource()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{exchange.sendResponseHeaders(404,-1);exchange.close();});server.start();var service=new HttpClientService(getProject());
        try{var panel=new HttpClientPanel(getProject(),service);panel.load("### absent\n# @expect status 200\nGET http://127.0.0.1:"+server.getAddress().getPort()+"/",0,false);button(panel,"Send").doClick();JTable table=find(panel,JTable.class).getFirst();await(()->service.collection==null&&table.getRowCount()==1);assertEquals(HttpCollectionRunner.Status.FAILED,table.getValueAt(0,1));button(panel,"Next Failure").doClick();assertEquals(source(panel).getLineStartOffset(1),source(panel).getCaretPosition());source(panel).insert("# edit\n",0);source(panel).setCaretPosition(0);button(panel,"Next Failure").doClick();assertEquals(0,source(panel).getCaretPosition());assertTrue(find(panel,JLabel.class).stream().anyMatch(label->label.getText()!=null&&label.getText().startsWith("Source has changed")));}
        finally{service.dispose();server.stop(0);}
    }
    public void testBusyControlsAndNativeActionCancelLeaveLaterRequestNotRun()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var workers=Executors.newCachedThreadPool();server.setExecutor(workers);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var hits=new AtomicInteger();server.createContext("/",exchange->{hits.incrementAndGet();try{exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write('x');exchange.getResponseBody().flush();entered.countDown();release.await(5,TimeUnit.SECONDS);}catch(Exception ignored){}finally{exchange.close();}});server.start();var service=new HttpClientService(getProject());
        try{var panel=new HttpClientPanel(getProject(),service);String request="GET http://127.0.0.1:"+server.getAddress().getPort()+"/";panel.load(request+"\n### later\n"+request,0,false);button(panel,"Run All").doClick();await(()->entered.getCount()==0);for(String caption:List.of("Open .http…","Send","Run All","Run Selected…","Environments…","Next Failure"))assertFalse(caption,button(panel,caption).isEnabled());assertTrue(button(panel,"Cancel").isEnabled());for(var combo:find(panel,JComboBox.class))assertFalse(combo.isEnabled());button(panel,"Cancel").doClick();JTable table=find(panel,JTable.class).getFirst();await(()->service.collection==null&&table.getRowCount()==2);assertEquals(HttpCollectionRunner.Status.CANCELLED,table.getValueAt(0,1));assertEquals(HttpCollectionRunner.Status.NOT_RUN,table.getValueAt(1,1));assertEquals(1,hits.get());assertTrue(button(panel,"Send").isEnabled());assertFalse(button(panel,"Cancel").isEnabled());}
        finally{release.countDown();service.dispose();server.stop(0);workers.shutdownNow();workers.awaitTermination(3,TimeUnit.SECONDS);}
    }
    public void testCollectionAndSourceViewportRemainVisibleAt280Pixels(){
        var service=new HttpClientService(getProject());try{var panel=new HttpClientPanel(getProject(),service);var outer=new JTabbedPane();outer.addTab("HTTP Client",panel);outer.setSize(1000,280);for(int i=0;i<4;i++)layoutTree(outer);JTable table=find(panel,JTable.class).getFirst();JScrollPane resultScroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,table);assertTrue("One complete result row must be visible",resultScroll.getViewport().getExtentSize().height>=table.getRowHeight());JTextArea source=source(panel);JScrollPane sourceScroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,source);assertTrue("Source must retain at least two visible lines",sourceScroll.getViewport().getExtentSize().height>=source.getFontMetrics(source.getFont()).getHeight()*2);for(String label:List.of("Run All","Cancel","Environments…","Save Summary…")){JButton button=button(panel,label);assertTrue("Toolbar action must fit horizontally: "+label,button.getX()+button.getWidth()<=button.getParent().getWidth());}}finally{service.dispose();}
    }
    public void testRedirectToggleDefaultsOffAndRequestDirectiveOverridesEnabledRunAll()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var followed=new AtomicInteger();
        server.createContext("/start",e->{e.getResponseHeaders().set("Location","/final");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/final",e->{followed.incrementAndGet();e.sendResponseHeaders(204,-1);e.close();});server.start();var service=new HttpClientService(getProject());
        try{var panel=new HttpClientPanel(getProject(),service);var toggle=find(panel,JCheckBox.class).stream().filter(b->"http-follow-redirects".equals(b.getName())).findFirst().orElseThrow();assertFalse(toggle.isSelected());
            String request="GET http://127.0.0.1:"+server.getAddress().getPort()+"/start";panel.load(request,0,false);button(panel,"Send").doClick();var table=find(panel,JTable.class).getFirst();await(()->service.collection==null&&table.getRowCount()==1);assertEquals(302,table.getValueAt(0,2));assertEquals(0,followed.get());
            toggle.doClick();panel.load("# @expect status 204\n"+request+"\n### opt out\n// @no-redirect\n# @expect status 302\n"+request,0,false);button(panel,"Run All").doClick();assertFalse(toggle.isEnabled());await(()->service.collection==null&&table.getRowCount()==2);assertTrue(toggle.isEnabled());assertEquals(1,followed.get());assertEquals(204,table.getValueAt(0,2));assertEquals(302,table.getValueAt(1,2));assertEquals(HttpCollectionRunner.Status.PASSED,table.getValueAt(0,1));assertEquals(HttpCollectionRunner.Status.PASSED,table.getValueAt(1,1));
        }finally{service.dispose();server.stop(0);}
    }
    public void testRedirectControlsWrapWithoutClippingAtCompactWidths(){
        var service=new HttpClientService(getProject());try{var panel=new HttpClientPanel(getProject(),service);
            for(int width:List.of(1000,600)){
                panel.setSize(width,280);for(int i=0;i<4;i++)layoutTree(panel);
                var toggle=find(panel,JCheckBox.class).stream().filter(b->"http-follow-redirects".equals(b.getName())).findFirst().orElseThrow();
                for(Component control:find(panel,JButton.class))assertToolbarControlVisible(control,width);
                assertToolbarControlVisible(toggle,width);boolean previous=toggle.isSelected();toggle.doClick();assertEquals(!previous,toggle.isSelected());
                var table=find(panel,JTable.class).getFirst();var resultScroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,table);
                var requestSource=source(panel);var sourceScroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,requestSource);
                var bars=find(panel,JComponent.class).stream().filter(c->"http-action-bars".equals(c.getName())).findFirst().orElseThrow();
                var split=find(panel,JComponent.class).stream().filter(c->"http-request-result-split".equals(c.getName())).findFirst().orElseThrow();
                String sizes="width="+width+", toolbar="+bars.getSize()+", split="+split.getSize()+", sourceViewport="+sourceScroll.getViewport().getExtentSize()+", resultViewport="+resultScroll.getViewport().getExtentSize()+", rowHeight="+table.getRowHeight()+", twoSourceLines="+(requestSource.getFontMetrics(requestSource.getFont()).getHeight()*2);
                System.out.println("HTTP compact layout: "+sizes);
                assertTrue("Result row visible: "+sizes,resultScroll.getViewport().getExtentSize().height>=table.getRowHeight());
                assertTrue("Two source lines visible: "+sizes,sourceScroll.getViewport().getExtentSize().height>=requestSource.getFontMetrics(requestSource.getFont()).getHeight()*2);
            }
        }finally{service.dispose();}
    }
    private static void assertToolbarControlVisible(Component control,int width){
        // Ignore tab/scrollbar infrastructure buttons; the owned toolbar rows are direct children of the two-row bar.
        if(!(control.getParent().getLayout() instanceof FlowLayout))return;
        assertTrue("visible at "+width+": "+control,control.isVisible());assertTrue(control.getX()>=0&&control.getY()>=0);
        assertTrue("right edge at "+width+": "+control,control.getX()+control.getWidth()<=control.getParent().getWidth());
        assertTrue("bottom edge at "+width+": "+control,control.getY()+control.getHeight()<=control.getParent().getHeight());
    }
    private static JTextArea source(Container panel){return find(panel,JTextArea.class).stream().filter(area->"HTTP request source".equals(area.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();}
    private static JButton button(Container panel,String text){return find(panel,JButton.class).stream().filter(button->text.equals(button.getText())).findFirst().orElseThrow();}
    private static void layoutTree(Container component){component.doLayout();for(Component child:component.getComponents())if(child instanceof Container container)layoutTree(container);}
    private static <T> List<T> find(Container parent,Class<T> type){var result=new java.util.ArrayList<T>();for(Component child:parent.getComponents()){if(type.isInstance(child))result.add(type.cast(child));if(child instanceof Container container)result.addAll(find(container,type));}return result;}
    private static void await(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<deadline){PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();Thread.sleep(5);}assertTrue("HTTP panel action did not reach expected state",condition.getAsBoolean());}
}

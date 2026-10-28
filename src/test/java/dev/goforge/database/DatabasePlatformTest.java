package dev.goforge.database;

import com.intellij.openapi.util.JDOMUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public class DatabasePlatformTest extends BasePlatformTestCase {
    public void testNonSecretProfilesRoundTripAndInvalidExternalSettingsAreDropped() {
        DatabaseProfiles store = new DatabaseProfiles();
        var profile = new DatabaseProfile("pg", "Work database", DatabaseProfile.Kind.POSTGRESQL, "", "localhost", 5432, "data", "developer", true);
        store.put(profile);
        String xml = JDOMUtil.writeElement(XmlSerializer.serialize(store.getState()));
        assertFalse(xml.contains("password")); assertFalse(xml.contains("SELECT")); assertFalse(xml.contains("jdbc:"));
        DatabaseProfiles restored = new DatabaseProfiles(); restored.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(store.getState()), DatabaseProfiles.State.class));
        assertEquals(store.profiles(), restored.profiles());
        var corrupted = new DatabaseProfiles.Entry(); corrupted.id = "unsafe"; corrupted.name = "Bad URL"; corrupted.kind = "POSTGRESQL";
        corrupted.host = "host?password=do-not-persist"; corrupted.port = 5432; corrupted.database = "data";
        var state = restored.getState(); state.connections.add(corrupted); restored.loadState(state);
        assertEquals(store.profiles(), restored.profiles());
    }
    public void testCreatingWindowDoesNotConnectCreateFileOrRunSql() throws Exception {
        var directory = Files.createTempDirectory("goforge-database-ui-"); var file = directory.resolve("not-opened.db");
        var store = DatabaseProfiles.getInstance(getProject());
        store.put(new DatabaseProfile("file", "Local", DatabaseProfile.Kind.SQLITE, file.toString(), "", 0, "", "", true));
        var service = DatabaseService.getInstance(getProject()); var panel = new DatabasePanel(getProject(), service);
        try { service.attach(panel); assertFalse(service.isConnected()); assertFalse(Files.exists(file)); }
        finally { panel.dispose(); Files.deleteIfExists(file); Files.delete(directory); }
    }
    public void testResultFirstRowVisibleAfterInitialAndNarrowerLayout() {
        var service = DatabaseService.getInstance(getProject()); var panel = new DatabasePanel(getProject(), service);
        try {
            panel.showResult(new DatabaseSession.Result(List.of(new DatabaseSession.Grid(List.of("id", "value"), List.of(List.of("1", "first row")), false)), List.of(), 4, false, List.of()));
            for (Dimension size : List.of(new Dimension(1100, 600), new Dimension(900, 440))) {
                panel.setSize(size); layout(panel); JTable table = findTable(panel); assertNotNull(table);
                assertEquals("first row", table.getValueAt(0, 1));
                JViewport viewport = (JViewport) table.getParent();
                assertTrue("first row clipped at " + size, viewport.getExtentSize().height >= table.getRowHeight());
                assertFalse(table.isCellEditable(0, 0));
            }
        } finally { panel.dispose(); }
    }
    public void testSelectSqlQuotesIdentifiersAndDoesNotExecute() {
        var table = new DatabaseSession.Table("database", "schema", "odd\"name", "TABLE");
        assertEquals("SELECT * FROM \"schema\".\"odd\"\"name\";", DatabasePanel.selectSql(table, DatabaseProfile.Kind.POSTGRESQL));
        assertEquals("SELECT * FROM `database`.`odd\"name`;", DatabasePanel.selectSql(table, DatabaseProfile.Kind.MYSQL));
        assertEquals("SELECT * FROM \"a;b\";", DatabasePanel.selectSql(new DatabaseSession.Table(null, null, "a;b", "TABLE"), DatabaseProfile.Kind.SQLITE));
    }
    public void testCompact280PixelToolWindowKeepsSqlAndResultRowUsable() {
        var service = DatabaseService.getInstance(getProject()); var panel = new DatabasePanel(getProject(), service);
        try {
            service.attach(panel);
            panel.render(false, true, false, "Connected to Scratch SQLite — autocommit on.");
            JTextArea sql = findSql(panel); assertNotNull(sql);
            for (Dimension size : List.of(new Dimension(1100, 280), new Dimension(900, 280))) {
                panel.setSize(size); layout(panel);
                JButton execute = findButton(panel, "Execute");
                for (String caption : List.of("Execute", "Explain", "Complete", "Cancel", "Apply")) {
                    JButton button = findButton(panel, caption); assertNotNull(caption, button);
                    assertFullyVisible(caption, button, size);
                    assertSame(caption + " must stay in the SQL command row", execute.getParent(), button.getParent());
                    assertTrue(layoutDetails(caption, button, size), button.getY() < execute.getY() + execute.getHeight());
                }
                JComboBox<?> transaction = findTransactionOperation(panel); assertNotNull(transaction);
                assertFullyVisible("Transaction selector", transaction, size);
                assertSame(execute.getParent(), transaction.getParent());
                assertTrue(layoutDetails("Transaction selector", transaction, size), transaction.getY() < execute.getY() + execute.getHeight());
                JButton connect = findButton(panel, "Connect"), limits = findButton(panel, "Limits…");
                assertNotNull(limits); assertSame("Limits belongs to the connection row", connect.getParent(), limits.getParent());
                assertNotSame("Limits must not consume the narrower SQL row", execute.getParent(), limits.getParent());
                for (Component component : limits.getParent().getComponents()) {
                    assertFullyVisible("Connection control", component, size);
                    assertTrue(layoutDetails("Connection control", component, size), component.getY() < connect.getY() + connect.getHeight());
                }
                JViewport editorViewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, sql);
                assertTrue("SQL editor collapsed before first query at " + size,
                        editorViewport.getExtentSize().height >= sql.getFontMetrics(sql.getFont()).getHeight() * 2);
                panel.showResult(new DatabaseSession.Result(List.of(new DatabaseSession.Grid(List.of("id", "value"), List.of(List.of("1", "first")), false)), List.of(), 3, false, List.of()));
                layout(panel); JTable table = findTable(panel); assertNotNull(table);
                assertTrue("SQL editor collapsed after query at " + size,
                        editorViewport.getExtentSize().height >= sql.getFontMetrics(sql.getFont()).getHeight() * 2);
                assertTrue("Result row clipped at " + size, ((JViewport) table.getParent()).getExtentSize().height >= table.getRowHeight());
            }
        } finally { panel.dispose(); }
    }
    public void testTransactionControlsRespectConnectionBusyAndTransactionState() {
        var service = DatabaseService.getInstance(getProject()); var panel = new DatabasePanel(getProject(), service);
        try {
            service.attach(panel);
            JComboBox<?> operation = findTransactionOperation(panel); JButton apply = findButton(panel, "Apply");
            assertNotNull(operation); assertNotNull(apply); assertFalse(operation.isEnabled()); assertFalse(apply.isEnabled());
            assertEquals("Apply database transaction", apply.getAccessibleContext().getAccessibleName());
            panel.render(false, true, false, "Autocommit");
            assertEquals("Begin", operation.getSelectedItem()); assertTrue(operation.isEnabled()); assertTrue(apply.isEnabled());
            operation.setSelectedItem("Commit"); assertFalse(apply.isEnabled());
            operation.setSelectedItem("Rollback"); assertFalse(apply.isEnabled());
            operation.setSelectedItem("Begin"); assertTrue(apply.isEnabled());
            panel.render(true, true, false, "Beginning"); assertFalse(operation.isEnabled()); assertFalse(apply.isEnabled());
            panel.render(false, true, true, "Transaction open");
            assertEquals("Rollback", operation.getSelectedItem()); assertTrue(apply.isEnabled());
            operation.setSelectedItem("Begin"); assertFalse(apply.isEnabled());
            operation.setSelectedItem("Commit"); assertTrue(apply.isEnabled());
            panel.render(false, true, true, "Query finished"); assertEquals("Commit", operation.getSelectedItem());
            panel.render(true, true, true, "Committing"); assertFalse(operation.isEnabled()); assertFalse(apply.isEnabled());
            panel.render(false, true, false, "Committed"); assertEquals("Begin", operation.getSelectedItem()); assertTrue(apply.isEnabled());
            panel.render(false, false, false, "Disconnected"); assertFalse(operation.isEnabled()); assertFalse(apply.isEnabled());
            assertFalse("Rendering or choosing must not connect", service.isConnected());
        } finally { panel.dispose(); }
    }
    public void testExplicitTransactionApplyActuallyCommitsAndRollsBackScratchSqlite() throws Exception {
        var service = DatabaseService.getInstance(getProject()); var panel = new DatabasePanel(getProject(), service);
        try {
            connectScratch(service, panel);
            executeAndWait(service, panel, "CREATE TABLE transaction_demo(value INTEGER)");
            JComboBox<?> operation = findTransactionOperation(panel); JButton apply = findButton(panel, "Apply");
            // An unavailable operation cannot trigger a transaction merely by being selected.
            operation.setSelectedItem("Commit"); assertFalse(apply.isEnabled()); apply.doClick();
            operation.setSelectedItem("Begin"); assertTrue(apply.isEnabled());
            apply.doClick(); waitFor(() -> "Rollback".equals(operation.getSelectedItem()) && apply.isEnabled());
            executeAndWait(service, panel, "INSERT INTO transaction_demo VALUES(1)");
            operation.setSelectedItem("Commit"); operation.setSelectedItem("Rollback");
            executeAndWait(service, panel, "SELECT COUNT(*) FROM transaction_demo");
            assertEquals("Selection alone must not roll back", "1", findTable(panel).getValueAt(0, 0));
            assertEquals("Rollback", operation.getSelectedItem());
            apply.doClick(); waitFor(() -> "Begin".equals(operation.getSelectedItem()) && apply.isEnabled());
            executeAndWait(service, panel, "SELECT COUNT(*) FROM transaction_demo");
            assertEquals("0", findTable(panel).getValueAt(0, 0));

            apply.doClick(); waitFor(() -> "Rollback".equals(operation.getSelectedItem()) && apply.isEnabled());
            executeAndWait(service, panel, "INSERT INTO transaction_demo VALUES(2)");
            operation.setSelectedItem("Commit");
            executeAndWait(service, panel, "SELECT COUNT(*) FROM transaction_demo");
            assertEquals("1", findTable(panel).getValueAt(0, 0));
            assertEquals("Selection alone must not commit", "Commit", operation.getSelectedItem());
            apply.doClick(); waitFor(() -> "Begin".equals(operation.getSelectedItem()) && apply.isEnabled());
            // A later rollback cannot remove the row committed through the visible Apply action.
            apply.doClick(); waitFor(() -> "Rollback".equals(operation.getSelectedItem()) && apply.isEnabled());
            apply.doClick(); waitFor(() -> "Begin".equals(operation.getSelectedItem()) && apply.isEnabled());
            executeAndWait(service, panel, "SELECT value FROM transaction_demo");
            assertEquals("2", findTable(panel).getValueAt(0, 0));
        } finally { closeService(service, panel); }
    }
    public void testExplainAndCompletionEntriesDoNotRunOnWindowCreationAndPlanIsVisible() {
        var service=DatabaseService.getInstance(getProject()); var panel=new DatabasePanel(getProject(),service);
        try {
            service.attach(panel); assertFalse(findButton(panel,"Explain").isEnabled()); assertFalse(findButton(panel,"Complete").isEnabled());
            panel.render(false,true,false,"Connected"); assertTrue(findButton(panel,"Explain").isEnabled()); assertTrue(findButton(panel,"Complete").isEnabled());
            panel.showPlan(new DatabaseSession.Result(List.of(new DatabaseSession.Grid(List.of("detail"),List.of(List.of("actual plan display fixture")),false)),List.of(),1,false,List.of("ANALYZE is not enabled")));
            assertEquals("Plan 1",findTabs(panel).getTitleAt(0)); assertFalse(service.isConnected());
        } finally { panel.dispose(); }
    }
    public void testActualAsyncCompletionRunsCallbackOnEdtAndRejectsEditedSql() throws Exception {
        var service=DatabaseService.getInstance(getProject()); var panel=new DatabasePanel(getProject(),service);
        try {
            connectScratch(service,panel);
            service.execute("CREATE TABLE people(name TEXT)",10,3); waitFor(() -> findButton(panel,"Execute").isEnabled());
            JTextArea sql=findSql(panel);String source="SELECT na FROM people";int caret=9;
            sql.setText(source);sql.setCaretPosition(caret);long revision=panel.sqlRevision(), generation=service.connectionGeneration();
            AtomicBoolean done=new AtomicBoolean();AtomicReference<SqlCompletion.Result> response=new AtomicReference<>();
            service.complete(source,caret,result -> {
                assertTrue(SwingUtilities.isEventDispatchThread()); response.set(result);
                panel.applyCompletion(source,caret,revision,generation,result,result.items().getFirst());done.set(true);
            });
            sql.setText("SELECT user_edit"); waitFor(done::get);
            assertEquals("\"name\"",response.get().items().getFirst().text());assertEquals("SELECT user_edit",sql.getText());
        } finally { closeService(service,panel); }
    }
    public void testActualCompletionAppliesOnceAndCannotCrossReconnect() throws Exception {
        var service=DatabaseService.getInstance(getProject());var panel=new DatabasePanel(getProject(),service);
        try {
            connectScratch(service,panel);service.execute("CREATE TABLE people(name TEXT)",10,3);waitFor(() -> findButton(panel,"Execute").isEnabled());
            JTextArea sql=findSql(panel);String source="SELECT na FROM people";int caret=9;sql.setText(source);sql.setCaretPosition(caret);
            long revision=panel.sqlRevision(),generation=service.connectionGeneration();AtomicReference<SqlCompletion.Result> answer=new AtomicReference<>();
            service.complete(source,caret,result -> {answer.set(result);panel.applyCompletion(source,caret,revision,generation,result,result.items().getFirst());});
            waitFor(() -> answer.get()!=null);assertEquals("SELECT \"name\" FROM people",sql.getText());
            service.disconnect(false);waitFor(() -> findButton(panel,"Connect").isEnabled());
            service.connect(DatabaseProfile.scratch(),new char[0]);waitFor(() -> findButton(panel,"Execute").isEnabled());
            sql.setText(source);sql.setCaretPosition(caret);
            panel.applyCompletion(source,caret,panel.sqlRevision(),generation,answer.get(),answer.get().items().getFirst());
            assertEquals(source,sql.getText());
        } finally {closeService(service,panel);}
    }
    public void testCancelledCompletionCannotPublishAlreadyQueuedSuccess() throws Exception {
        var service=DatabaseService.getInstance(getProject());var panel=new DatabasePanel(getProject(),service);
        try {
            connectScratch(service,panel);AtomicBoolean published=new AtomicBoolean();
            service.complete("SELECT * FROM ",14,result -> published.set(true));service.cancel();
            waitFor(() -> findButton(panel,"Execute").isEnabled());assertFalse(published.get());
        } finally {closeService(service,panel);}
    }
    private void connectScratch(DatabaseService service,DatabasePanel panel) throws Exception {
        service.attach(panel);service.connect(DatabaseProfile.scratch(),new char[0]);waitFor(() -> findButton(panel,"Execute").isEnabled());assertTrue(service.isConnected());
    }
    private static void executeAndWait(DatabaseService service, DatabasePanel panel, String statement) throws Exception {
        service.execute(statement, 10, 3); waitFor(() -> findButton(panel, "Execute").isEnabled());
    }
    private void closeService(DatabaseService service,DatabasePanel panel) throws Exception {
        if(service.isConnected()){service.disconnect(false);waitFor(() -> findButton(panel,"Connect").isEnabled());}panel.dispose();
    }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline){com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();Thread.sleep(5);}
        assertTrue("Database UI callback did not complete",condition.getAsBoolean());
    }
    private static JButton findButton(Container c,String text){if(c instanceof JButton b && text.equals(b.getText()))return b;for(Component child:c.getComponents())if(child instanceof Container container){JButton b=findButton(container,text);if(b!=null)return b;}return null;}
    private static JTabbedPane findTabs(Container c){if(c instanceof JTabbedPane p)return p;for(Component child:c.getComponents())if(child instanceof Container container){JTabbedPane p=findTabs(container);if(p!=null)return p;}return null;}
    private static JComboBox<?> findTransactionOperation(Container c) {
        if (c instanceof JComboBox<?> combo && "Database transaction operation".equals(combo.getAccessibleContext().getAccessibleName())) return combo;
        for (Component child : c.getComponents()) if (child instanceof Container container) { JComboBox<?> combo = findTransactionOperation(container); if (combo != null) return combo; }
        return null;
    }
    private static void assertFullyVisible(String name, Component component, Dimension panelSize) {
        Rectangle bounds = component.getBounds(); Container parent = component.getParent();
        assertTrue(layoutDetails(name, component, panelSize), bounds.width > 0 && bounds.height > 0
                && bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= parent.getWidth()
                && bounds.y + bounds.height <= parent.getHeight());
    }
    private static String layoutDetails(String name, Component component, Dimension panelSize) {
        return name + " at panel " + panelSize + ": bounds=" + component.getBounds()
                + ", preferred=" + component.getPreferredSize() + ", parent=" + component.getParent().getBounds();
    }
    private static JTextArea findSql(Container component) {
        if (component instanceof JTextArea area && "Database SQL statement".equals(area.getAccessibleContext().getAccessibleName())) return area;
        for (Component child : component.getComponents()) if (child instanceof Container container) { JTextArea area = findSql(container); if (area != null) return area; }
        return null;
    }
    private static void layout(Container component) { component.doLayout(); for (Component child : component.getComponents()) if (child instanceof Container container) layout(container); }
    private static JTable findTable(Container component) {
        if (component instanceof JTable table) return table;
        for (Component child : component.getComponents()) if (child instanceof Container container) { JTable table = findTable(container); if (table != null) return table; }
        return null;
    }
}

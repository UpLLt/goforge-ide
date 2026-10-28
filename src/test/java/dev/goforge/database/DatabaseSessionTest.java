package dev.goforge.database;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real SQLite JDBC tests; no database server, user data or mock query results. */
public class DatabaseSessionTest {
    private DatabaseSession session;
    static final String LONG_QUERY = "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<1000000000) SELECT sum(x) FROM n";
    @Before public void setUp() throws Exception { session = new DatabaseSession(); await(session.connect(DatabaseProfile.scratch(), new char[0])); }
    @After public void tearDown() throws Exception { if (session != null) await(session.disconnect()); }
    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(8, TimeUnit.SECONDS); }
    private DatabaseSession.Result sql(String text) throws Exception { return await(session.execute(text, 500, 5)); }
    private String scalar(String text) throws Exception { return sql(text).grids().getFirst().rows().getFirst().getFirst(); }

    @Test public void realCreateInsertQueryUpdateAndDeleteCounts() throws Exception {
        sql("CREATE TABLE people(id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
        assertEquals(List.of(2L), sql("INSERT INTO people(name) VALUES('Ada'),('Lin')").updateCounts());
        var selected = sql("SELECT id,name FROM people ORDER BY id").grids().getFirst();
        assertEquals(List.of("id", "name"), selected.columns()); assertEquals(List.of("1", "Ada"), selected.rows().getFirst());
        assertEquals(List.of(1L), sql("UPDATE people SET name='Grace' WHERE id=1").updateCounts());
        assertEquals("Grace", scalar("SELECT name FROM people WHERE id=1"));
        assertEquals(List.of(1L), sql("DELETE FROM people WHERE id=2").updateCounts());
        assertEquals("1", scalar("SELECT count(*) FROM people"));
    }
    @Test public void schemaColumnsKeysAndWildcardNamesUseRealMetadata() throws Exception {
        sql("CREATE TABLE items_table(id INTEGER PRIMARY KEY, label TEXT NOT NULL)"); sql("CREATE TABLE itemsXtable(wrong TEXT)");
        sql("CREATE VIEW item_view AS SELECT id,label FROM items_table");
        var schema = await(session.schema()); assertEquals(3, schema.tables().size());
        var table = schema.tables().stream().filter(t -> t.name().equals("items_table")).findFirst().orElseThrow();
        var columns = await(session.columns(table)); assertEquals(2, columns.size()); assertEquals("id", columns.getFirst().name());
        assertTrue(columns.getFirst().primaryKey()); assertFalse(columns.get(1).nullable());
        assertTrue(schema.tables().stream().anyMatch(t -> t.name().equals("item_view") && t.type().equals("VIEW")));
    }
    @Test public void truncatesRowsCellsAndBinaryAndPreservesSqlNull() throws Exception {
        var rows = await(session.execute("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<10) SELECT x FROM n", 3, 5)).grids().getFirst();
        assertEquals(3, rows.rows().size()); assertTrue(rows.truncated());
        var values = sql("SELECT NULL AS n, replace(hex(zeroblob(3000)),'0','x') AS big, zeroblob(600) AS binary").grids().getFirst().rows().getFirst();
        assertEquals("NULL", values.get(0)); assertTrue(values.get(1).endsWith("[cell truncated]")); assertTrue(values.get(2).endsWith("[binary truncated]"));
    }
    @Test public void explicitTransactionsCommitAndRollback() throws Exception {
        sql("CREATE TABLE data(value TEXT)"); await(session.begin()); sql("INSERT INTO data VALUES('rollback')");
        assertTrue(session.inTransaction()); await(session.finishTransaction(false)); assertEquals("0", scalar("SELECT count(*) FROM data"));
        await(session.begin()); sql("INSERT INTO data VALUES('commit')"); await(session.finishTransaction(true));
        assertFalse(session.inTransaction()); assertEquals("commit", scalar("SELECT value FROM data"));
    }
    @Test public void disconnectRollsBackFileTransactionAndReleasesFile() throws Exception {
        await(session.disconnect()); Path directory = Files.createTempDirectory("goforge-database-"); Path file = directory.resolve("数据库 with spaces.db");
        var profile = new DatabaseProfile("file", "Test file", DatabaseProfile.Kind.SQLITE, file.toString(), "", 0, "", "", true);
        try {
            session = new DatabaseSession(); await(session.connect(profile, new char[0])); sql("CREATE TABLE data(value INTEGER)");
            await(session.begin()); sql("INSERT INTO data VALUES(1)"); await(session.disconnect()); assertTrue(session.isClosed());
            session = new DatabaseSession(); await(session.connect(profile, new char[0])); assertEquals("0", scalar("SELECT count(*) FROM data"));
            await(session.disconnect()); Files.delete(file); assertFalse(Files.exists(file));
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory); }
    }
    @Test public void sqlErrorsAndRejectedBatchesDoNotBreakConnectionOrExecuteSecondStatement() throws Exception {
        assertThrows(ExecutionException.class, () -> sql("SELECT * FROM absent_table"));
        assertThrows(ExecutionException.class, () -> sql("CREATE TABLE never_created(x); INSERT INTO never_created VALUES(1)"));
        assertTrue(await(session.schema()).tables().isEmpty()); assertEquals("42", scalar("SELECT 42"));
    }
    @Test public void cancelsActiveQueryAndAllowsSubsequentWork() throws Exception {
        var running = session.execute(LONG_QUERY, 20, 30); waitForStatement();
        assertThrows(ExecutionException.class, () -> await(session.execute("SELECT 1", 1, 5)));
        session.cancel(); ExecutionException error = assertThrows(ExecutionException.class, () -> await(running));
        assertTrue(error.getCause().getMessage().contains("cancelled")); assertEquals("7", scalar("SELECT 7"));
    }
    @Test public void timeoutInterruptsActualSqliteWork() throws Exception {
        long start = System.nanoTime(); var running = session.execute(LONG_QUERY, 20, 1);
        ExecutionException error = assertThrows(ExecutionException.class, () -> await(running));
        assertTrue(error.getCause().getMessage().contains("time limit"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5000); assertEquals("9", scalar("SELECT 9"));
    }
    @Test public void disconnectDuringQueryCancelsAndRejectsFutureOperations() throws Exception {
        var running = session.execute(LONG_QUERY, 20, 30); waitForStatement(); await(session.disconnect());
        assertTrue(running.isCompletedExceptionally()); assertTrue(session.isClosed());
        assertThrows(ExecutionException.class, () -> await(session.schema()));
    }
    @Test public void connectionConsumesAndWipesCallerSecretEvenWhenClosed() throws Exception {
        await(session.disconnect()); char[] secret = "discard-me".toCharArray();
        assertThrows(ExecutionException.class, () -> await(session.connect(DatabaseProfile.scratch(), secret)));
        assertArrayEquals(new char[secret.length], secret);
    }
    @Test public void keyedEditUsesParametersAndWaitsForExplicitCommitOrRollback() throws Exception {
        sql("CREATE TABLE data(id INTEGER PRIMARY KEY, label TEXT)"); sql("INSERT INTO data VALUES(1,'original')");
        var table = await(session.schema()).tables().getFirst();
        await(session.stageUpdate(table, Map.of("id", "1"), "label", "quoted '; DROP TABLE data; --"));
        assertTrue(session.inTransaction()); assertEquals("quoted '; DROP TABLE data; --", scalar("SELECT label FROM data"));
        await(session.finishTransaction(false)); assertEquals("original", scalar("SELECT label FROM data"));
        await(session.stageUpdate(table, Map.of("id", "1"), "label", "saved")); await(session.finishTransaction(true));
        assertEquals("saved", scalar("SELECT label FROM data"));
    }
    @Test public void missingKeysAndUnknownColumnsCannotStageBroadUpdates() throws Exception {
        sql("CREATE TABLE data(id INTEGER PRIMARY KEY, label TEXT)"); sql("INSERT INTO data VALUES(1,'original')");
        var table = await(session.schema()).tables().getFirst();
        assertThrows(ExecutionException.class, () -> await(session.stageUpdate(table, Map.of(), "label", "wrong")));
        assertThrows(ExecutionException.class, () -> await(session.stageUpdate(table, Map.of("id", "1"), "nonexistent", "wrong")));
        assertThrows(ExecutionException.class, () -> await(session.stageUpdate(table, Map.of("id", "1 OR 1=1"), "label", "wrong")));
        assertFalse(session.inTransaction()); assertEquals("original", scalar("SELECT label FROM data"));
    }
    @Test public void compositeKeyRequiresAllComponentsAndCanSetSqlNull() throws Exception {
        sql("CREATE TABLE data(part INTEGER, item INTEGER, label TEXT, PRIMARY KEY(part,item))");
        sql("INSERT INTO data VALUES(1,1,'keep'),(1,2,'change')"); var table = await(session.schema()).tables().getFirst();
        assertThrows(ExecutionException.class, () -> await(session.stageUpdate(table, Map.of("part", "1"), "label", "wrong")));
        await(session.stageUpdate(table, Map.of("part", "1", "item", "2"), "label", null)); await(session.finishTransaction(true));
        assertEquals("keep", scalar("SELECT label FROM data WHERE item=1")); assertEquals("1", scalar("SELECT count(*) FROM data WHERE label IS NULL"));
    }
    private void waitForStatement() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!session.hasActiveStatement() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(session.hasActiveStatement()); Thread.sleep(80);
    }
}

package dev.goforge.database;

import org.junit.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real JDBC SQLite metadata/plans. No simulated database rows. */
public class DatabaseAssistTest {
    private DatabaseSession db;
    private static <T> T await(CompletableFuture<T> f) throws Exception { return f.get(8, TimeUnit.SECONDS); }
    @Before public void connect() throws Exception { db = new DatabaseSession(); await(db.connect(DatabaseProfile.scratch(), new char[0])); }
    @After public void disconnect() throws Exception { await(db.disconnect()); }
    private void sql(String s) throws Exception { await(db.execute(s, 100, 3)); }
    private SqlCompletion.Result complete(String s) throws Exception { int i=s.indexOf('|'); return await(db.complete(s.replace("|", ""),i)); }
    @Test public void actualTableColumnAliasAndQuotedNames() throws Exception {
        sql("CREATE TABLE \"odd%_table\" (id INTEGER PRIMARY KEY, \"odd\"\"name\" TEXT)");
        assertEquals("\"odd%_table\"", complete("SELECT * FROM odd|").items().getFirst().text());
        assertEquals("\"odd\"\"name\"", complete("SELECT a.odd| FROM \"odd%_table\" AS a").items().getFirst().text());
    }
    @Test public void ddlRefreshDoesNotReuseCachedSchemaOrColumns() throws Exception {
        sql("CREATE TABLE items(id INTEGER)"); assertTrue(complete("SELECT n| FROM items").items().isEmpty());
        sql("ALTER TABLE items ADD COLUMN new_column TEXT"); assertEquals("\"new_column\"", complete("SELECT n| FROM items").items().getFirst().text());
        sql("DROP TABLE items"); assertTrue(complete("SELECT * FROM it|").items().isEmpty());
    }
    @Test public void explainShowsActualIndexPlanWithoutEvaluatingQuery() throws Exception {
        sql("CREATE TABLE items(id INTEGER PRIMARY KEY, label TEXT)"); sql("INSERT INTO items VALUES(1,'one')");
        var result=await(db.explain("SELECT * FROM items WHERE id=1",100,3));
        assertFalse(result.grids().isEmpty()); assertTrue(result.grids().getFirst().rows().toString().contains("SEARCH")); assertTrue(result.updateCounts().isEmpty());
        // This expression would raise a SQLite integer-overflow error if SELECT executed.
        assertFalse(await(db.explain("SELECT abs(-9223372036854775808)",100,3)).grids().isEmpty());
        assertThrows(ExecutionException.class, () -> await(db.execute("SELECT abs(-9223372036854775808)",100,3)));
        assertTrue(result.warnings().getFirst().contains("ANALYZE is not enabled"));
    }
    @Test public void explainRejectsWritesBatchesAnalyzeAndExecutableComments() {
        for (var kind : DatabaseProfile.Kind.values()) for (String s : List.of("EXPLAIN ANALYZE SELECT 1", "ANALYZE", "DELETE FROM items", "SELECT 1; DELETE FROM items", "WITH x AS (DELETE FROM items RETURNING *) SELECT * FROM x", "SELECT * INTO new_table FROM items"))
            assertThrows(s, IllegalArgumentException.class, () -> SqlExplain.statement(s,kind));
        assertThrows(IllegalArgumentException.class, () -> SqlExplain.statement("SELECT 1 /*! INTO OUTFILE 'x' */",DatabaseProfile.Kind.MYSQL));
        assertTrue(SqlExplain.statement("SELECT 'ANALYZE'", DatabaseProfile.Kind.POSTGRESQL).startsWith("EXPLAIN (ANALYZE FALSE, FORMAT TEXT)"));
        assertTrue(SqlExplain.statement("WITH x AS (SELECT 1) SELECT * FROM x",DatabaseProfile.Kind.MYSQL).startsWith("EXPLAIN FORMAT=JSON"));
    }
    @Test public void completionDoesNotExecuteUserSqlAndRejectsOverlappingWork() throws Exception {
        sql("CREATE TABLE items(id INTEGER)"); complete("DROP TABLE items|");
        assertFalse(await(db.schema()).tables().isEmpty());
        var running=db.execute(DatabaseSessionTest.LONG_QUERY,10,5);
        assertThrows(ExecutionException.class, () -> await(db.complete("SELECT * FROM ",14)));
        db.cancel(); assertThrows(ExecutionException.class, () -> await(running));
        assertFalse(complete("SELECT * FROM it|").items().isEmpty());
    }
    @Test public void disconnectAndNewSessionDoNotShareMetadata() throws Exception {
        sql("CREATE TABLE old_table(id INTEGER)"); assertFalse(complete("SELECT * FROM old|").items().isEmpty());
        await(db.disconnect()); assertThrows(ExecutionException.class, () -> await(db.complete("SELECT ",7)));
        db=new DatabaseSession(); await(db.connect(DatabaseProfile.scratch(),new char[0]));
        assertTrue(complete("SELECT * FROM old|").items().isEmpty());
    }
    @Test public void metadataReadIsAsyncAndCancellationDiscardsLockedSnapshot() throws Exception {
        var directory=java.nio.file.Files.createTempDirectory("goforge-schema-lock-");var file=directory.resolve("fixture.db");
        await(db.disconnect()); db=new DatabaseSession();
        try {
            await(db.connect(new DatabaseProfile("file","Own fixture",DatabaseProfile.Kind.SQLITE,file.toString(),"",0,"","",true),new char[0]));
            sql("CREATE TABLE locked_table(id INTEGER)");
            try(var blocker=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);var statement=blocker.createStatement()) {
                statement.execute("BEGIN EXCLUSIVE");
                long started=System.nanoTime();var pending=db.complete("SELECT * FROM ",14);
                assertTrue("metadata read blocked caller",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<500);
                Thread.sleep(80);assertFalse("fixture must hold the schema read",pending.isDone());
                db.cancel();statement.execute("ROLLBACK");
                assertThrows(ExecutionException.class,()->await(pending));
            }
            assertFalse(complete("SELECT * FROM locked|").items().isEmpty());
        } finally {await(db.disconnect());java.nio.file.Files.deleteIfExists(file);java.nio.file.Files.delete(directory);}
    }
}

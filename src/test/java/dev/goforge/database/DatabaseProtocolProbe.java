package dev.goforge.database;

import java.util.Map;
import java.util.concurrent.*;

/** Invoked only by verify-database.py against its newly created loopback containers. */
public final class DatabaseProtocolProbe {
    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(12, TimeUnit.SECONDS); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static DatabaseSession.Result sql(DatabaseSession db, String statement) throws Exception { return await(db.execute(statement, 50, 5)); }
    private static String scalar(DatabaseSession db, String statement) throws Exception { return sql(db, statement).grids().getFirst().rows().getFirst().getFirst(); }
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"1".equals(System.getenv("GOFORGE_DATABASE_FIXTURE"))) throw new IllegalArgumentException("Use scripts/verify-database.py; fixture context required.");
        var kind = DatabaseProfile.Kind.valueOf(args[0]); int port = Integer.parseInt(args[1]);
        String secret = System.getenv("GOFORGE_DATABASE_PASSWORD"); if (secret == null) throw new IllegalArgumentException("Fixture secret missing.");
        var profile = new DatabaseProfile("probe", "Temporary fixture", kind, "", "127.0.0.1", port, "goforge_fixture", "goforge_fixture", false);
        DatabaseSession db = new DatabaseSession();
        try {
            String product = await(db.connect(profile, secret.toCharArray()));
            sql(db, "CREATE TABLE goforge_probe (id INTEGER PRIMARY KEY, label VARCHAR(120) NOT NULL)");
            check(sql(db, "INSERT INTO goforge_probe VALUES (1, 'first'), (2, 'second')").updateCounts().equals(java.util.List.of(2L)), "insert count");
            check(scalar(db, "SELECT label FROM goforge_probe WHERE id=1").equals("first"), "read data");
            var schema = await(db.schema()); var table = schema.tables().stream().filter(t -> t.name().equals("goforge_probe")).findFirst().orElseThrow();
            var columns = await(db.columns(table)); check(columns.size() == 2 && columns.getFirst().primaryKey(), "real metadata columns/key");
            String completionSql = "SELECT p.la FROM goforge_probe p";
            var suggestions = await(db.complete(completionSql, completionSql.indexOf("la") + 2));
            check(suggestions.items().stream().anyMatch(item -> item.text().equals(kind == DatabaseProfile.Kind.MYSQL ? "`label`" : "\"label\"")), "actual alias column completion");
            sql(db, "ALTER TABLE goforge_probe ADD COLUMN new_column INTEGER");
            String changedSql = "SELECT p.new FROM goforge_probe p";
            check(await(db.complete(changedSql, changedSql.indexOf("new") + 3)).items().stream().anyMatch(item -> item.text().contains("new_column")), "fresh schema after DDL");
            String planSql = kind == DatabaseProfile.Kind.POSTGRESQL ? "SELECT pg_sleep(20) FROM goforge_probe WHERE id=1" : "SELECT SLEEP(20) FROM goforge_probe WHERE id=1";
            long planStart = System.nanoTime(); var plan = await(db.explain(planSql, 50, 3));
            check(!plan.grids().isEmpty() && plan.updateCounts().isEmpty(), "actual estimated query plan");
            check(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - planStart) < 5, "explain must not run sleep query");
            await(db.begin()); sql(db, "UPDATE goforge_probe SET label='discard' WHERE id=1"); await(db.finishTransaction(false));
            check(scalar(db, "SELECT label FROM goforge_probe WHERE id=1").equals("first"), "rollback");
            await(db.stageUpdate(table, Map.of("id", "1"), "label", "bound ' quote")); check(db.inTransaction(), "row edit must be staged"); await(db.finishTransaction(true));
            check(scalar(db, "SELECT label FROM goforge_probe WHERE id=1").equals("bound ' quote"), "keyed edit commit");
            try { await(db.stageUpdate(table, Map.of("id", "9999"), "label", "missing")); throw new AssertionError("absent key accepted"); }
            catch (ExecutionException expected) { check(!db.inTransaction(), "failed keyed edit rollback"); }
            String slow = kind == DatabaseProfile.Kind.POSTGRESQL ? "SELECT pg_sleep(30)" : "SELECT SLEEP(30)";
            long started = System.nanoTime(); var running = db.execute(slow, 10, 1);
            try { await(running); throw new AssertionError("timeout did not stop query"); }
            catch (ExecutionException expected) { check(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started) < 8, "timeout bound"); }
            check(scalar(db, "SELECT count(*) FROM goforge_probe").equals("2"), "connection after timeout");
            var cancelled = db.execute(slow, 10, 20);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!db.hasActiveStatement() && System.nanoTime() < deadline) Thread.sleep(5);
            Thread.sleep(150); db.cancel();
            try { await(cancelled); throw new AssertionError("cancel did not stop query"); } catch (ExecutionException expected) { }
            check(scalar(db, "SELECT count(*) FROM goforge_probe").equals("2"), "connection after cancel");
            await(db.begin()); sql(db, "DELETE FROM goforge_probe WHERE id=2"); await(db.disconnect()); check(db.isClosed(), "disconnect");
            db = new DatabaseSession(); await(db.connect(profile, secret.toCharArray()));
            check(scalar(db, "SELECT count(*) FROM goforge_probe").equals("2"), "disconnect rolls back"); sql(db, "DROP TABLE goforge_probe");
            System.out.println("{\"passed\":true,\"kind\":\"" + kind.name() + "\",\"checks\":[\"connect\",\"create/insert/select\",\"schema/columns/key\",\"actual alias column completion\",\"fresh schema after DDL\",\"estimated query plan without executing sleep\",\"rollback\",\"parameterized keyed edit/commit\",\"missing-key rollback\",\"actual query timeout\",\"actual cancel\",\"reuse after cancel\",\"disconnect rollback/reconnect\",\"cleanup\"]}");
        } finally { await(db.disconnect()); }
    }
}

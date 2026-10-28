package dev.goforge.database;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SqlCompletionTest {
    private static final DatabaseSession.Table USERS = new DatabaseSession.Table(null, "public", "users", "TABLE");
    private static final DatabaseSession.Table TEAM = new DatabaseSession.Table(null, "public", "teams", "TABLE");
    private static final DatabaseSession.Column NAME = new DatabaseSession.Column("name", "TEXT", 12, 0, true, false);
    private static final DatabaseSession.Column ODD = new DatabaseSession.Column("odd\"column", "TEXT", 12, 0, true, false);
    private SqlCompletion.Result complete(String marked, DatabaseProfile.Kind kind, DatabaseSession.Schema schema, Map<DatabaseSession.Table, List<DatabaseSession.Column>> columns) {
        int caret = marked.indexOf('|'); String sql = marked.replace("|", "");
        return SqlCompletion.complete(SqlCompletion.context(sql, caret, kind), schema, columns, kind);
    }
    private SqlCompletion.Result complete(String sql) {
        return complete(sql, DatabaseProfile.Kind.POSTGRESQL, new DatabaseSession.Schema(List.of(USERS, TEAM), false), Map.of(USERS, List.of(NAME, ODD), TEAM, List.of(NAME)));
    }
    @Test public void tableContextProducesQualifiedCurrentSchemaNames() {
        assertEquals(List.of("\"public\".\"users\""), complete("SELECT * FROM us|").items().stream().map(SqlCompletion.Item::text).toList());
        assertEquals(List.of("\"users\""), complete("SELECT * FROM public.us|").items().stream().map(SqlCompletion.Item::text).toList());
    }
    @Test public void aliasesResolveAfterCaretAndColumnsReplaceOnlyIdentifier() {
        var r = complete("SELECT U.na|me FROM public.users U");
        assertEquals(List.of("\"name\""), r.items().stream().map(SqlCompletion.Item::text).toList());
        assertEquals(9, r.start()); assertEquals(13, r.end());
        var atStart=complete("SELECT |name FROM users");assertEquals(7,atStart.start());assertEquals(11,atStart.end());
    }
    @Test public void quotedAliasAndIdentifierEscapesRemainExact() {
        var r = complete("SELECT \"Exact Alias\".\"odd|\" FROM users AS \"Exact Alias\"");
        assertEquals("\"odd\"\"column\"", r.items().getFirst().text());
        assertTrue(complete("SELECT exactalias.n| FROM users AS \"Exact Alias\"").items().isEmpty());
    }
    @Test public void joinedUnqualifiedColumnsAreQualifiedToAvoidAmbiguity() {
        assertEquals(List.of("\"t\".\"name\"", "\"u\".\"name\""), complete("SELECT na| FROM users u JOIN teams t ON u.name=t.name").items().stream().map(SqlCompletion.Item::text).toList());
    }
    @Test public void commentsValuesAndDollarQuotesDoNotLeakAliases() {
        for (String sql : List.of("SELECT 'FROM users u |'", "-- FROM users |", "SELECT $$FROM users |$$", "SELECT /* FROM users | */ 1"))
            assertTrue(sql, complete(sql).items().isEmpty());
    }
    @Test public void unsupportedNestedCteAndMultipleStatementsAreExplicit() {
        for (String sql : List.of("WITH x AS (SELECT * FROM users) SELECT | FROM x", "SELECT | FROM (SELECT * FROM users) x", "SELECT | FROM users; SELECT * FROM teams")) {
            var result = complete(sql); assertTrue(result.items().isEmpty()); assertFalse(result.note().isBlank());
        }
    }
    @Test public void mysqlQuotesUseBackticksAndCatalogScope() {
        var table = new DatabaseSession.Table("db", null, "odd`table", "TABLE");
        var r = complete("SELECT * FROM `odd|", DatabaseProfile.Kind.MYSQL, new DatabaseSession.Schema(List.of(table), false), Map.of());
        assertEquals("`db`.`odd``table`", r.items().getFirst().text());
    }
    @Test public void schemaAmbiguityRequiresExplicitQualification() {
        var context = SqlCompletion.context("SELECT u.n FROM users u", 10, DatabaseProfile.Kind.POSTGRESQL);
        var schema = new DatabaseSession.Schema(List.of(USERS, new DatabaseSession.Table(null, "other", "users", "TABLE")), false);
        assertThrows(IllegalArgumentException.class, () -> SqlCompletion.referenced(context, schema, DatabaseProfile.Kind.POSTGRESQL));
    }
    @Test public void suggestionLimitAndSchemaTruncationAreVisible() {
        var tables = new ArrayList<DatabaseSession.Table>(); for (int i = 0; i < 110; i++) tables.add(new DatabaseSession.Table(null, null, "table"+i, "TABLE"));
        var r = complete("SELECT * FROM |", DatabaseProfile.Kind.SQLITE, new DatabaseSession.Schema(tables, true), Map.of());
        assertEquals(100, r.items().size()); assertTrue(r.note().contains("100")); assertTrue(r.note().contains("2000"));
    }
}

package dev.goforge.database;

import java.util.Set;

/** Query-plan requests only. Never adds ANALYZE TRUE or executes the original query separately. */
public final class SqlExplain {
    private SqlExplain() { }
    public static String statement(String sql, DatabaseProfile.Kind kind) {
        String checked = SqlStatementText.single(sql, kind);
        if (kind == DatabaseProfile.Kind.MYSQL && SqlTokens.scan(checked, kind).stream()
                .filter(t -> t.kind() == SqlTokens.Kind.COMMENT).anyMatch(t -> checked.startsWith("/*!", t.start()) || checked.startsWith("/*M!", t.start())))
            throw new IllegalArgumentException("Explain does not accept executable MariaDB/MySQL comments.");
        var tokens = SqlTokens.scan(checked, kind).stream().filter(t -> t.kind() != SqlTokens.Kind.COMMENT).toList();
        if (tokens.isEmpty() || !(tokens.getFirst().word("SELECT") || tokens.getFirst().word("WITH")))
            throw new IllegalArgumentException("Explain accepts one SELECT query, optionally with a read-only WITH clause. Do not add EXPLAIN or ANALYZE.");
        for (var token : tokens) {
            if (Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "INTO", "CALL", "COPY", "CREATE", "ALTER", "DROP", "ANALYZE", "EXPLAIN", "FOR", "LOCK", "OUTFILE", "DUMPFILE").contains(token.upper()))
                throw new IllegalArgumentException("Explain supports read-only SELECT/WITH queries without write clauses or ANALYZE options.");
        }
        return switch (kind) {
            case SQLITE -> "EXPLAIN QUERY PLAN " + checked;
            case POSTGRESQL -> "EXPLAIN (ANALYZE FALSE, FORMAT TEXT) " + checked;
            case MYSQL -> "EXPLAIN FORMAT=JSON " + checked;
        };
    }
}

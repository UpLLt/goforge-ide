package dev.goforge.database;

import java.util.Locale;
import java.util.Set;

/** A conservative statement boundary check, not a dialect parser or an authorization filter. */
public final class SqlStatementText {
    private SqlStatementText() { }
    public static String single(String sql, DatabaseProfile.Kind kind) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("Enter SQL or select one statement to execute.");
        if (sql.length() > 1_000_000) throw new IllegalArgumentException("SQL exceeds the 1 MB console limit.");
        boolean ended = false, content = false;
        String first = null;
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if ((c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') || (c == '#' && kind == DatabaseProfile.Kind.MYSQL)) {
                int end = sql.indexOf('\n', i); i = end < 0 ? sql.length() : end + 1; continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int depth = 1; i += 2;
                while (i < sql.length() && depth > 0) {
                    if (i + 1 < sql.length() && sql.startsWith("/*", i)) { depth++; i += 2; }
                    else if (i + 1 < sql.length() && sql.startsWith("*/", i)) { depth--; i += 2; }
                    else i++;
                }
                if (depth != 0) throw new IllegalArgumentException("Unclosed SQL comment.");
                continue;
            }
            if (c == ';') { ended = true; i++; continue; }
            if (ended) throw new IllegalArgumentException("Execute one statement at a time. Select the statement first; scripts and trigger bodies need a separate SQL client.");
            content = true;
            if (first == null) {
                int end = i;
                while (end < sql.length() && Character.isLetter(sql.charAt(end))) end++;
                first = sql.substring(i, end).toUpperCase(Locale.ROOT);
                if (Set.of("BEGIN", "START", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE").contains(first))
                    throw new IllegalArgumentException("Use Begin Transaction, Commit or Rollback so the console can track transaction state.");
            }
            if (c == '\'' || c == '"' || c == '`' || c == '[') {
                char end = c == '[' ? ']' : c; boolean closed = false;
                boolean escapes = kind == DatabaseProfile.Kind.MYSQL || (kind == DatabaseProfile.Kind.POSTGRESQL
                        && c == '\'' && i > 0 && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e'));
                i++;
                while (i < sql.length()) {
                    char q = sql.charAt(i++);
                    if (q == '\\' && escapes && i < sql.length()) { i++; continue; }
                    if (q == end) {
                        if (i < sql.length() && sql.charAt(i) == end) { i++; continue; }
                        closed = true; break;
                    }
                }
                if (!closed) throw new IllegalArgumentException("Unclosed SQL quoted value or identifier.");
                continue;
            }
            if (c == '$' && kind == DatabaseProfile.Kind.POSTGRESQL) {
                int end = sql.indexOf('$', i + 1);
                if (end >= 0 && sql.substring(i + 1, end).matches("(?:[A-Za-z_][A-Za-z_0-9]*)?")) {
                    String delimiter = sql.substring(i, end + 1);
                    int closing = sql.indexOf(delimiter, end + 1);
                    if (closing < 0) throw new IllegalArgumentException("Unclosed PostgreSQL dollar quote.");
                    i = closing + delimiter.length(); continue;
                }
            }
            i++;
        }
        if (!content) throw new IllegalArgumentException("Enter a SQL statement; comments alone are not executable.");
        return sql;
    }
}

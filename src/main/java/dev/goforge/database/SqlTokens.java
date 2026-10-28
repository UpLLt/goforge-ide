package dev.goforge.database;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Offset-preserving lexical assistance only; not a SQL grammar or an authorization filter. */
final class SqlTokens {
    enum Kind { WORD, IDENTIFIER, VALUE, COMMENT, SYMBOL }
    record Token(Kind kind, String text, int start, int end, boolean closed) {
        boolean name() { return kind == Kind.WORD || kind == Kind.IDENTIFIER; }
        boolean word(String value) { return kind == Kind.WORD && text.equalsIgnoreCase(value); }
        String upper() { return kind == Kind.WORD ? text.toUpperCase(Locale.ROOT) : ""; }
    }
    static List<Token> scan(String sql, DatabaseProfile.Kind dialect) {
        if (sql == null || sql.length() > 1_000_000) throw new IllegalArgumentException("SQL exceeds the 1 MB console limit.");
        List<Token> result = new ArrayList<>();
        for (int i = 0; i < sql.length();) {
            int start = i; char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (sql.startsWith("--", i) || (c == '#' && dialect == DatabaseProfile.Kind.MYSQL)) {
                int end = sql.indexOf('\n', i); i = end < 0 ? sql.length() : end;
                result.add(new Token(Kind.COMMENT, "", start, i, true)); continue;
            }
            if (sql.startsWith("/*", i)) {
                int depth = 1; i += 2;
                while (i < sql.length() && depth > 0) {
                    if (sql.startsWith("/*", i)) { depth++; i += 2; }
                    else if (sql.startsWith("*/", i)) { depth--; i += 2; }
                    else i++;
                }
                result.add(new Token(Kind.COMMENT, "", start, i, depth == 0)); continue;
            }
            if (c == '$' && dialect == DatabaseProfile.Kind.POSTGRESQL) {
                int end = sql.indexOf('$', i + 1);
                if (end >= 0 && sql.substring(i + 1, end).matches("(?:[A-Za-z_][A-Za-z_0-9]*)?")) {
                    String delimiter = sql.substring(i, end + 1); int close = sql.indexOf(delimiter, end + 1);
                    i = close < 0 ? sql.length() : close + delimiter.length();
                    result.add(new Token(Kind.VALUE, "", start, i, close >= 0)); continue;
                }
            }
            if (c == '\'' || c == '"' || c == '`' || c == '[') {
                char finish = c == '[' ? ']' : c; boolean closed = false; StringBuilder value = new StringBuilder(); i++;
                boolean escapes = dialect == DatabaseProfile.Kind.MYSQL || (dialect == DatabaseProfile.Kind.POSTGRESQL
                        && c == '\'' && start > 0 && (sql.charAt(start - 1) == 'e' || sql.charAt(start - 1) == 'E'));
                while (i < sql.length()) {
                    char next = sql.charAt(i++);
                    if (next == '\\' && escapes && i < sql.length()) { value.append(sql.charAt(i++)); continue; }
                    if (next == finish) {
                        if (i < sql.length() && sql.charAt(i) == finish) { value.append(finish); i++; }
                        else { closed = true; break; }
                    } else value.append(next);
                }
                // MySQL double quotes are string literals unless ANSI_QUOTES is enabled.
                Kind kind = c == '\'' || (c == '"' && dialect == DatabaseProfile.Kind.MYSQL) ? Kind.VALUE : Kind.IDENTIFIER;
                result.add(new Token(kind, value.toString(), start, i, closed)); continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) i++;
                result.add(new Token(Kind.WORD, sql.substring(start, i), start, i, true));
            } else { i++; result.add(new Token(Kind.SYMBOL, String.valueOf(c), start, i, true)); }
        }
        return List.copyOf(result);
    }
}

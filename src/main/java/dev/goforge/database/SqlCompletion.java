package dev.goforge.database;

import java.util.*;

/** Bounded, current-schema identifier completion for flat table references; not a SQL semantic engine. */
public final class SqlCompletion {
    public record Item(String text, String detail) { @Override public String toString() { return text + "    " + detail; } }
    public record Result(int start, int end, List<Item> items, String note) { }
    record Name(String text, boolean quoted) { }
    record Reference(List<Name> path, Name alias) { }
    record Context(int start, int end, String prefix, Name qualifier, boolean tables, List<Reference> references, String blocked) { }
    private static final Set<String> RESERVED = Set.of("SELECT", "FROM", "WHERE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "OUTER", "ON", "USING", "GROUP", "ORDER", "BY", "HAVING", "LIMIT", "OFFSET", "UNION", "EXCEPT", "INTERSECT", "SET", "VALUES", "RETURNING", "AND", "OR", "AS", "FOR", "WINDOW");
    private SqlCompletion() { }
    static Context context(String sql, int caret, DatabaseProfile.Kind kind) {
        if (caret < 0 || caret > sql.length()) throw new IllegalArgumentException("SQL caret is outside the document.");
        var all = SqlTokens.scan(sql, kind); int start = caret, end = caret; String prefix = "";
        for (var token : all) {
            if (token.start() <= caret && (caret < token.end() || (caret == token.end() && (token.name() || !token.closed() || token.kind() == SqlTokens.Kind.COMMENT)))) {
                if (token.kind() == SqlTokens.Kind.VALUE || token.kind() == SqlTokens.Kind.COMMENT)
                    return new Context(caret, caret, "", null, false, List.of(), "Completion is not offered inside SQL values or comments.");
                if (token.name()) {
                    start = token.start(); end = token.end();
                    var partial = SqlTokens.scan(sql.substring(start, caret), kind);
                    prefix = partial.isEmpty() ? "" : partial.getFirst().text();
                }
            }
        }
        var tokens = all.stream().filter(t -> t.kind() != SqlTokens.Kind.COMMENT).toList();
        for (int i = 0; i < tokens.size() - 1; i++) if (symbol(tokens.get(i), ";"))
            return new Context(start, end, prefix, null, false, List.of(), "Completion accepts one SQL statement; select or isolate it in the console.");
        int depth = 0; boolean nested = false;
        for (var t : tokens) { if (symbol(t, "(")) depth++; if (t.word("SELECT") && depth > 0) nested = true; if (symbol(t, ")")) depth--; }
        if (nested || (!tokens.isEmpty() && tokens.getFirst().word("WITH")))
            return new Context(start, end, prefix, null, false, List.of(), "CTE and nested-query alias resolution is not supported by this schema completion.");
        final int before = start;
        var preceding = tokens.stream().filter(t -> t.end() <= before).toList();
        Name qualifier = null; int index = preceding.size() - 1;
        if (index >= 1 && symbol(preceding.get(index), ".") && preceding.get(index - 1).name()) {
            qualifier = name(preceding.get(index - 1)); index -= 2;
        }
        boolean tablePosition = index >= 0 && Set.of("FROM", "JOIN", "UPDATE", "INTO").contains(preceding.get(index).upper());
        List<Reference> refs = new ArrayList<>(); depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i); if (symbol(token, "(")) depth++; if (symbol(token, ")")) depth--;
            if (depth != 0 || !Set.of("FROM", "JOIN", "UPDATE", "INTO").contains(token.upper())) continue;
            int j = i + 1; List<Name> path = new ArrayList<>();
            if (j >= tokens.size() || !tokens.get(j).name()) continue;
            path.add(name(tokens.get(j++)));
            while (j + 1 < tokens.size() && symbol(tokens.get(j), ".") && tokens.get(j + 1).name()) { path.add(name(tokens.get(j + 1))); j += 2; }
            if (j < tokens.size() && tokens.get(j).word("AS")) j++;
            Name alias = j < tokens.size() && tokens.get(j).name() && !RESERVED.contains(tokens.get(j).upper()) ? name(tokens.get(j)) : null;
            refs.add(new Reference(List.copyOf(path), alias));
        }
        if (refs.size() > 32) return new Context(start, end, prefix, qualifier, tablePosition, List.of(), "Completion supports at most 32 table references.");
        return new Context(start, end, prefix, qualifier, tablePosition, List.copyOf(refs), "");
    }
    static List<DatabaseSession.Table> referenced(Context context, DatabaseSession.Schema schema, DatabaseProfile.Kind kind) {
        LinkedHashSet<DatabaseSession.Table> result = new LinkedHashSet<>();
        for (var ref : context.references()) {
            var matches = schema.tables().stream().filter(table -> matches(ref.path(), table, kind)).toList();
            if (matches.size() > 1) throw new IllegalArgumentException("The table name is ambiguous across schemas; qualify it before completing columns.");
            result.addAll(matches);
        }
        if (result.size() > 32) throw new IllegalArgumentException("Table references are ambiguous across more than 32 schemas; qualify the table names.");
        return List.copyOf(result);
    }
    static Result complete(Context context, DatabaseSession.Schema schema, Map<DatabaseSession.Table, List<DatabaseSession.Column>> columns, DatabaseProfile.Kind kind) {
        if (!context.blocked().isEmpty()) return new Result(context.start(), context.end(), List.of(), context.blocked());
        LinkedHashMap<String, Item> items = new LinkedHashMap<>();
        if (context.tables() || context.references().isEmpty()) {
            for (var table : schema.tables()) {
                String namespace = namespace(table, kind);
                if (context.qualifier() != null && !matches(context.qualifier(), namespace, kind)) continue;
                if (!starts(table.name(), context.prefix())) continue;
                String insert = quote(table.name(), kind);
                if (context.qualifier() == null && !namespace.isEmpty()) insert = quote(namespace, kind) + "." + insert;
                items.putIfAbsent(insert, new Item(insert, table.type() + " · " + table.group()));
            }
        } else {
            for (var ref : context.references()) {
                Name alias = ref.alias() != null ? ref.alias() : ref.path().getLast();
                String aliasText = !alias.quoted() && kind == DatabaseProfile.Kind.POSTGRESQL ? alias.text().toLowerCase(Locale.ROOT) : alias.text();
                if (context.qualifier() != null && !matches(context.qualifier(), aliasText, kind)) continue;
                for (var table : schema.tables()) {
                    if (!matches(ref.path(), table, kind)) continue;
                    for (var column : columns.getOrDefault(table, List.of())) {
                        if (!starts(column.name(), context.prefix())) continue;
                        String insert = quote(column.name(), kind);
                        if (context.qualifier() == null && context.references().size() > 1) insert = quote(aliasText, kind) + "." + insert;
                        items.putIfAbsent(insert, new Item(insert, column.type() + " · " + table.group() + "." + table.name()));
                    }
                }
            }
        }
        var sorted = items.values().stream().sorted(Comparator.comparing(Item::text)).limit(100).toList();
        String note = "Current connection metadata; flat table/alias identifiers only."
                + (schema.truncated() ? " Schema limited to 2000 tables/views." : "")
                + (items.size() > 100 ? " First 100 suggestions; type a longer prefix." : "");
        return new Result(context.start(), context.end(), sorted, note);
    }
    private static Name name(SqlTokens.Token token) { return new Name(token.text(), token.kind() == SqlTokens.Kind.IDENTIFIER); }
    private static boolean symbol(SqlTokens.Token token, String value) { return token.kind() == SqlTokens.Kind.SYMBOL && token.text().equals(value); }
    private static boolean starts(String name, String prefix) { return name.regionMatches(true, 0, prefix, 0, prefix.length()); }
    private static boolean matches(Name name, String actual, DatabaseProfile.Kind kind) {
        if (actual == null) return false;
        return name.quoted() ? name.text().equals(actual) : kind == DatabaseProfile.Kind.POSTGRESQL
                ? name.text().toLowerCase(Locale.ROOT).equals(actual) : name.text().equalsIgnoreCase(actual);
    }
    private static boolean matches(List<Name> path, DatabaseSession.Table table, DatabaseProfile.Kind kind) {
        if (path.isEmpty() || path.size() > 2 || !matches(path.getLast(), table.name(), kind)) return false;
        if (path.size() == 1) return true;
        return matches(path.getFirst(), namespace(table, kind), kind)
                || (kind == DatabaseProfile.Kind.SQLITE && namespace(table, kind).isEmpty() && matches(path.getFirst(), "main", kind));
    }
    private static String namespace(DatabaseSession.Table table, DatabaseProfile.Kind kind) {
        String value = kind == DatabaseProfile.Kind.MYSQL ? table.catalog() : table.schema(); return value == null ? "" : value;
    }
    static String quote(String name, DatabaseProfile.Kind kind) { String q = kind == DatabaseProfile.Kind.MYSQL ? "`" : "\""; return q + name.replace(q, q + q) + q; }
}

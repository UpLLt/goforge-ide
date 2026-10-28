package dev.goforge.database;

import java.io.IOException;
import java.io.Writer;

/** RFC 4180-style quoting of the displayed grid; it never fetches undisplayed rows or runs SQL. */
public final class DatabaseCsv {
    private DatabaseCsv() { }
    public static void write(DatabaseSession.Grid grid, Writer writer) throws IOException {
        row(grid.columns(), writer); for (var values : grid.rows()) row(values, writer);
    }
    private static void row(java.util.List<String> values, Writer writer) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i != 0) writer.write(',');
            String value = values.get(i); writer.write('"'); writer.write(value.replace("\"", "\"\"")); writer.write('"');
        }
        writer.write("\r\n");
    }
}

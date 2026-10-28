package dev.goforge.database;

import org.junit.Test;
import java.io.StringWriter;
import java.util.List;
import static org.junit.Assert.*;

public class DatabaseCsvTest {
    @Test public void quotesCommasNewlinesAndQuotesAndKeepsUnicode() throws Exception {
        var grid = new DatabaseSession.Grid(List.of("id", "label"), List.of(List.of("1", "你好, \"name\"\nnext")), false);
        StringWriter writer = new StringWriter(); DatabaseCsv.write(grid, writer);
        assertEquals("\"id\",\"label\"\r\n\"1\",\"你好, \"\"name\"\"\nnext\"\r\n", writer.toString());
    }
    @Test public void exportsOnlySuppliedDisplayedRowsIncludingTruncationMarkers() throws Exception {
        var grid = new DatabaseSession.Grid(List.of("value"), List.of(List.of("long… [cell truncated]")), true);
        StringWriter writer = new StringWriter(); DatabaseCsv.write(grid, writer);
        assertEquals("\"value\"\r\n\"long… [cell truncated]\"\r\n", writer.toString());
    }
}

package dev.goforge.database;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One JDBC connection and one serialized worker. No SQL, results or credentials are written to disk or logs. */
public final class DatabaseSession implements AutoCloseable {
    public record Table(String catalog, String schema, String name, String type) {
        public String group() { return join(catalog, schema); }
        private static String join(String a, String b) {
            return a == null || a.isBlank() ? (b == null || b.isBlank() ? "Default schema" : b)
                    : (b == null || b.isBlank() || b.equals(a) ? a : a + "." + b);
        }
        @Override public String toString() { return name + ("VIEW".equals(type) ? " (view)" : ""); }
    }
    public record Column(String name, String type, int jdbcType, int size, boolean nullable, boolean primaryKey) {
        @Override public String toString() { return name + " : " + type + (primaryKey ? "  [PK]" : "") + (nullable ? "" : "  NOT NULL"); }
    }
    public record Schema(List<Table> tables, boolean truncated) { }
    public record Grid(List<String> columns, List<List<String>> rows, boolean truncated) { }
    public record Result(List<Grid> grids, List<Long> updateCounts, long elapsedMillis, boolean inTransaction, List<String> warnings) { }

    private interface Work<T> { T run(Operation operation) throws Exception; }
    private static final class Operation {
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile Statement statement;
        volatile boolean timeout;
        void check() throws SQLException {
            if (cancelled.get()) throw new SQLException(timeout ? "Query time limit reached." : "Operation cancelled.", "57014");
        }
    }
    private final ExecutorService worker = Executors.newSingleThreadExecutor(daemon("GoForge JDBC"));
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor(daemon("GoForge JDBC cancel"));
    private final AtomicReference<Operation> active = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> closedFuture = new CompletableFuture<>();
    private volatile Connection connection;
    private volatile DatabaseProfile profile;
    private volatile boolean inTransaction;

    private static ThreadFactory daemon(String name) {
        return task -> { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; };
    }
    public boolean isBusy() { return active.get() != null; }
    boolean hasActiveStatement() { Operation operation = active.get(); return operation != null && operation.statement != null; }
    public boolean isConnected() { return connection != null && !closed.get(); }
    public boolean inTransaction() { return inTransaction; }
    public boolean isClosed() { return closed.get() && closedFuture.isDone(); }

    public CompletableFuture<String> connect(DatabaseProfile configuration, char[] secret) {
        Objects.requireNonNull(configuration); Objects.requireNonNull(secret);
        char[] password = secret.clone(); Arrays.fill(secret, '\0');
        CompletableFuture<String> result = submit(operation -> {
            if (connection != null) throw new SQLException("Disconnect before selecting another database.");
            Properties properties = configuration.properties(password);
            String passwordText = properties.getProperty("password", "");
            try {
                Driver driver = (Driver) Class.forName(configuration.kind().driverClass, true, getClass().getClassLoader())
                        .getDeclaredConstructor().newInstance();
                Connection ready = driver.connect(configuration.jdbcUrl(), properties);
                if (ready == null) throw new SQLException("The selected driver did not accept this connection.");
                try {
                    operation.check();
                    if (closed.get()) throw new SQLException("Connection was closed.");
                    ready.setAutoCommit(true);
                    String product = ready.getMetaData().getDatabaseProductName() + " " + ready.getMetaData().getDatabaseProductVersion();
                    connection = ready; profile = configuration; inTransaction = false;
                    return product;
                } catch (Exception error) { try { ready.close(); } catch (SQLException ignored) { } throw error; }
            } catch (SQLException error) {
                // Some drivers echo authentication details. Do not retain the original exception/cause.
                String message = error.getMessage() == null ? "JDBC connection failed." : error.getMessage();
                Throwable cause = error.getCause();
                for (int depth = 0; cause != null && depth < 3; depth++, cause = cause.getCause())
                    if (cause.getMessage() != null && !cause.getMessage().isBlank()) message += " — " + cause.getMessage();
                if (!passwordText.isEmpty()) message = message.replace(passwordText, "<redacted>");
                throw new SQLException(message, error.getSQLState(), error.getErrorCode());
            } finally { properties.clear(); Arrays.fill(password, '\0'); }
        });
        result.whenComplete((ignored, error) -> Arrays.fill(password, '\0'));
        return result;
    }

    public CompletableFuture<Schema> schema() {
        return submit(operation -> readSchema(requireConnection(), operation));
    }
    private static Schema readSchema(Connection db, Operation operation) throws SQLException {
            List<Table> tables = new ArrayList<>();
            try (ResultSet result = db.getMetaData().getTables(db.getCatalog(), null, "%", new String[]{"TABLE", "VIEW"})) {
                operation.statement = result.getStatement();
                while (result.next()) {
                    operation.check();
                    if (tables.size() == 2000) return new Schema(List.copyOf(tables), true);
                    tables.add(new Table(result.getString("TABLE_CAT"), result.getString("TABLE_SCHEM"), result.getString("TABLE_NAME"), result.getString("TABLE_TYPE")));
                }
            } finally { operation.statement = null; }
            operation.check();
            tables.sort(Comparator.comparing(Table::group).thenComparing(Table::name));
            return new Schema(List.copyOf(tables), false);
    }
    /** Fetch fresh metadata for every request; never reuse a pre-DDL or previous-connection schema snapshot. */
    public CompletableFuture<SqlCompletion.Result> complete(String sql, int caret) {
        return submit(operation -> {
            Connection db = requireConnection();
            var context = SqlCompletion.context(sql, caret, profile.kind());
            if (!context.blocked().isEmpty()) return new SqlCompletion.Result(context.start(), context.end(), List.of(), context.blocked());
            Schema schema = readSchema(db, operation);
            Map<Table, List<Column>> columns = new LinkedHashMap<>();
            if (!context.tables()) for (Table table : SqlCompletion.referenced(context, schema, profile.kind())) {
                operation.check(); columns.put(table, readColumns(db, table, operation));
            }
            operation.check();
            return SqlCompletion.complete(context, schema, columns, profile.kind());
        });
    }
    public CompletableFuture<List<Column>> columns(Table table) {
        return submit(operation -> readColumns(requireConnection(), table, operation));
    }
    private static List<Column> readColumns(Connection db, Table table, Operation operation) throws SQLException {
            DatabaseMetaData metadata = db.getMetaData();
            Set<String> keys = new HashSet<>();
            try (ResultSet result = metadata.getPrimaryKeys(table.catalog(), table.schema(), table.name())) {
                operation.statement = result.getStatement();
                while (result.next()) { operation.check(); keys.add(result.getString("COLUMN_NAME")); }
            } finally { operation.statement = null; }
            operation.check();
            List<Column> columns = new ArrayList<>();
            String escape = metadata.getSearchStringEscape();
            String pattern = escapedPattern(table.name(), escape);
            try (ResultSet result = metadata.getColumns(table.catalog(), escapedPattern(table.schema(), escape), pattern, "%")) {
                operation.statement = result.getStatement();
                while (result.next()) {
                    operation.check();
                    if (!table.name().equals(result.getString("TABLE_NAME"))) continue;
                    if (columns.size() == 2000) throw new SQLException("Column browser limit reached (2000).");
                    String name = result.getString("COLUMN_NAME");
                    columns.add(new Column(name, result.getString("TYPE_NAME"), result.getInt("DATA_TYPE"), result.getInt("COLUMN_SIZE"),
                            result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, keys.contains(name)));
                }
            } finally { operation.statement = null; }
            operation.check();
            return List.copyOf(columns);
    }
    private static String escapedPattern(String name, String escape) {
        if (name == null || escape == null || escape.isEmpty()) return name;
        return name.replace(escape, escape + escape).replace("%", escape + "%").replace("_", escape + "_");
    }

    public CompletableFuture<Result> execute(String sql, int maxRows, int timeoutSeconds) {
        return query(sql, maxRows, timeoutSeconds, false);
    }
    public CompletableFuture<Result> explain(String sql, int maxRows, int timeoutSeconds) {
        return query(sql, maxRows, timeoutSeconds, true);
    }
    private CompletableFuture<Result> query(String sql, int maxRows, int timeoutSeconds, boolean explain) {
        if (maxRows < 1 || maxRows > 10000 || timeoutSeconds < 1 || timeoutSeconds > 300)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Rows must be 1–10000 and timeout 1–300 seconds."));
        return submit(operation -> {
            Connection db = requireConnection();
            String statementText = explain ? SqlExplain.statement(sql, profile.kind()) : SqlStatementText.single(sql, profile.kind());
            long started = System.nanoTime();
            List<Grid> grids = new ArrayList<>(); List<Long> counts = new ArrayList<>(); List<String> warnings = new ArrayList<>();
            if (explain) warnings.add("Query plan only: ANALYZE is not enabled, and the original query is not separately executed. This is an estimated plan, not measured execution performance.");
            try (Statement statement = db.createStatement()) {
                operation.statement = statement;
                statement.setMaxRows(maxRows + 1);
                try { statement.setQueryTimeout(timeoutSeconds); } catch (SQLFeatureNotSupportedException ignored) { }
                ScheduledFuture<?> timeout = control.schedule(() -> { operation.timeout = true; cancel(operation); }, timeoutSeconds, TimeUnit.SECONDS);
                try {
                    operation.check();
                    boolean rows = statement.execute(statementText);
                    for (int count = 0; count < 16; count++) {
                        operation.check();
                        if (rows) {
                            try (ResultSet result = statement.getResultSet()) { grids.add(readGrid(result, maxRows, operation)); }
                        } else {
                            long updated = statement.getLargeUpdateCount();
                            if (updated == -1) break;
                            counts.add(updated);
                        }
                        rows = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                        if (count == 15) warnings.add("Only the first 16 results are displayed.");
                    }
                    SQLWarning warning = statement.getWarnings();
                    for (int i = 0; warning != null && i < 8; i++, warning = warning.getNextWarning())
                        warnings.add(limit(warning.getMessage(), 2000));
                    operation.check();
                } catch (SQLException error) { operation.check(); throw error; }
                finally { timeout.cancel(false); operation.statement = null; }
            }
            inTransaction = !db.getAutoCommit();
            return new Result(List.copyOf(grids), List.copyOf(counts), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), inTransaction, List.copyOf(warnings));
        });
    }

    private static Grid readGrid(ResultSet result, int maxRows, Operation operation) throws SQLException, IOException {
        ResultSetMetaData metadata = result.getMetaData();
        int columnCount = metadata.getColumnCount();
        if (columnCount > 256) throw new SQLException("Result exceeds the 256-column display limit. Select fewer columns.");
        List<String> columns = new ArrayList<>();
        for (int column = 1; column <= columnCount; column++) columns.add(limit(metadata.getColumnLabel(column), 1000));
        List<List<String>> rows = new ArrayList<>(); int characters = 0; boolean truncated = false;
        while (result.next()) {
            operation.check();
            if (rows.size() >= maxRows || characters >= 5_000_000) { truncated = true; break; }
            List<String> row = new ArrayList<>();
            for (int column = 1; column <= columnCount; column++) {
                String value;
                int type = metadata.getColumnType(column);
                if (type == Types.BLOB || type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY) {
                    try (InputStream bytes = result.getBinaryStream(column)) {
                        if (bytes == null) value = "NULL";
                        else { byte[] data = bytes.readNBytes(257); value = "0x" + HexFormat.of().formatHex(data, 0, Math.min(256, data.length)) + (data.length > 256 ? "… [binary truncated]" : ""); }
                    }
                } else if (type == Types.CHAR || type == Types.VARCHAR || type == Types.LONGVARCHAR || type == Types.CLOB
                        || type == Types.NCHAR || type == Types.NVARCHAR || type == Types.LONGNVARCHAR || type == Types.NCLOB) {
                    try (Reader reader = result.getCharacterStream(column)) {
                        if (reader == null) value = "NULL";
                        else {
                            char[] data = new char[4097]; int read = 0, next;
                            while (read < data.length && (next = reader.read(data, read, data.length - read)) != -1) read += next;
                            value = new String(data, 0, Math.min(read, 4096)) + (read > 4096 ? "… [cell truncated]" : "");
                        }
                    }
                } else { String scalar = result.getString(column); value = scalar == null ? "NULL" : limit(scalar, 4096); }
                row.add(value); characters += value.length();
            }
            rows.add(List.copyOf(row));
        }
        return new Grid(List.copyOf(columns), List.copyOf(rows), truncated);
    }
    private static String limit(String text, int count) { return text == null ? "" : text.length() > count ? text.substring(0, count) + "…" : text; }

    public CompletableFuture<Boolean> begin() {
        return submit(operation -> {
            Connection db = requireConnection();
            if (!db.getAutoCommit()) throw new SQLException("A transaction is already open.");
            db.setAutoCommit(false); inTransaction = true; return true;
        });
    }
    /** Stage exactly one keyed row update in a new transaction; the user must Commit or Rollback afterwards. */
    public CompletableFuture<Result> stageUpdate(Table table, Map<String, String> keyValues, String column, String value) {
        Map<String, String> keys = Map.copyOf(keyValues);
        return submit(operation -> {
            Connection db = requireConnection();
            if (!db.getAutoCommit()) throw new SQLException("Commit or Rollback the current transaction before editing a row.");
            List<Column> columns = readColumns(db, table, operation);
            List<String> primaryKeys = columns.stream().filter(Column::primaryKey).map(Column::name).toList();
            if (primaryKeys.isEmpty() || !keys.keySet().equals(Set.copyOf(primaryKeys))) throw new SQLException("Supply every primary-key column exactly once.");
            if (primaryKeys.contains(column) || columns.stream().noneMatch(c -> c.name().equals(column))) throw new SQLException("Choose an existing non-primary-key column.");
            String quote = db.getMetaData().getIdentifierQuoteString().trim(); if (quote.isEmpty()) throw new SQLException("Driver does not support quoted identifiers.");
            String namespace = profile.kind() == DatabaseProfile.Kind.MYSQL ? table.catalog() : table.schema();
            String tableName = quoted(table.name(), quote);
            if (namespace != null && !namespace.isBlank()) tableName = quoted(namespace, quote) + "." + tableName;
            StringJoiner where = new StringJoiner(" AND "); for (String key : primaryKeys) where.add(quoted(key, quote) + " = ?");
            String query = "UPDATE " + tableName + " SET " + quoted(column, quote) + " = ? WHERE " + where;
            long start = System.nanoTime(); db.setAutoCommit(false);
            try (PreparedStatement statement = db.prepareStatement(query)) {
                operation.statement = statement; statement.setQueryTimeout(30);
                Map<String, Integer> types = new HashMap<>(); columns.forEach(c -> types.put(c.name(), c.jdbcType()));
                if (value == null) statement.setNull(1, types.get(column)); else statement.setObject(1, value, types.get(column));
                int index = 2; for (String key : primaryKeys) statement.setObject(index++, keys.get(key), types.get(key));
                operation.check(); int changed = statement.executeUpdate(); operation.check();
                if (changed != 1) throw new SQLException("Row edit matched " + changed + " rows; rolled back. Refresh the key and try again.");
                inTransaction = true;
                return new Result(List.of(), List.of(1L), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), true,
                        List.of("Row edit is staged. Commit applies it; Rollback or Disconnect discards it."));
            } catch (Exception error) {
                try { db.rollback(); db.setAutoCommit(true); inTransaction = false; } catch (SQLException rollback) { error.addSuppressed(rollback); }
                throw error;
            } finally { operation.statement = null; }
        });
    }
    private static String quoted(String identifier, String quote) { return quote + identifier.replace(quote, quote + quote) + quote; }
    public CompletableFuture<Boolean> finishTransaction(boolean commit) {
        return submit(operation -> {
            Connection db = requireConnection();
            if (db.getAutoCommit()) throw new SQLException("There is no open transaction.");
            if (commit) db.commit(); else db.rollback();
            db.setAutoCommit(true); inTransaction = false; return false;
        });
    }
    private Connection requireConnection() throws SQLException {
        Connection db = connection;
        if (db == null || db.isClosed() || closed.get()) throw new SQLException("Connect to a database first.");
        return db;
    }
    private <T> CompletableFuture<T> submit(Work<T> work) {
        Operation operation = new Operation();
        if (closed.get()) return CompletableFuture.failedFuture(new SQLException("Database session is closed."));
        if (!active.compareAndSet(null, operation)) return CompletableFuture.failedFuture(new SQLException("Another database operation is running."));
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                T result = null; Throwable failure = null;
                try { operation.check(); if (closed.get()) throw new SQLException("Database session is closed."); result = work.run(operation); }
                catch (Throwable error) { failure = error; }
                finally { operation.statement = null; active.compareAndSet(operation, null); }
                if (failure == null) future.complete(result); else future.completeExceptionally(failure);
            });
        } catch (RejectedExecutionException error) { active.compareAndSet(operation, null); future.completeExceptionally(new SQLException("Database session is closed.")); }
        return future;
    }
    public void cancel() { Operation operation = active.get(); if (operation != null) cancel(operation); }
    private void cancel(Operation operation) {
        operation.cancelled.set(true);
        try { control.execute(() -> {
            if (active.get() != operation) return;
            Statement statement = operation.statement;
            if (statement != null) try { statement.cancel(); } catch (SQLException ignored) { /* worker reports completion/failure */ }
        }); } catch (RejectedExecutionException ignored) { /* already closed */ }
    }
    public CompletableFuture<Void> disconnect() {
        if (!closed.compareAndSet(false, true)) return closedFuture;
        cancel();
        worker.execute(() -> {
            SQLException problem = null; Connection db = connection; connection = null;
            if (db != null) {
                try { if (!db.isClosed() && !db.getAutoCommit()) db.rollback(); } catch (SQLException error) { problem = error; }
                try { db.close(); } catch (SQLException error) { if (problem == null) problem = error; }
            }
            inTransaction = false; profile = null; control.shutdownNow(); worker.shutdown();
            if (problem == null) closedFuture.complete(null); else closedFuture.completeExceptionally(problem);
        });
        return closedFuture;
    }
    /** Nonblocking; project disposal must never wait on JDBC/network activity on the UI thread. */
    @Override public void close() { disconnect(); }
}

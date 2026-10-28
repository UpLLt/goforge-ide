package dev.goforge.database;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import java.util.Arrays;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

@Service(Service.Level.PROJECT)
public final class DatabaseService implements Disposable {
    public static final String WINDOW_ID = "Go Database";
    private final Project project;
    private DatabaseSession session;
    private DatabaseProfile connectedProfile;
    private DatabasePanel panel;
    private boolean busy;
    private long connectionGeneration;
    private long completionGeneration;
    private volatile boolean disposed;
    private String status = "Connect to Scratch SQLite for a temporary local database, or add a connection.";

    public DatabaseService(Project project) { this.project = project; }
    public static DatabaseService getInstance(Project project) { return project.getService(DatabaseService.class); }
    void attach(DatabasePanel value) { panel = value; refresh(); }
    void detach(DatabasePanel value) { if (panel == value) { panel = null; disposeConnection(); } }
    boolean isConnected() { return session != null && session.isConnected(); }
    DatabaseProfile connectedProfile() { return connectedProfile; }
    long connectionGeneration() { return connectionGeneration; }
    private void refresh() {
        if (panel != null) panel.render(busy, isConnected(), session != null && session.inTransaction(), status);
    }
    void connect(DatabaseProfile profile, char[] password) {
        if (disposed || busy || session != null) { Arrays.fill(password, '\0'); return; }
        DatabaseSession created = new DatabaseSession(); session = created; connectedProfile = profile; connectionGeneration++;
        watch(created, "Connecting…", created.connect(profile, password), product -> {
            status = "Connected to " + profile.name() + " — " + product + ". Autocommit is on."; refresh(); refreshSchema();
        }, () -> { created.close(); if (session == created) { session = null; connectedProfile = null; } refresh(); });
    }
    void refreshSchema() {
        DatabaseSession db = session; if (db == null || busy) return;
        watch(db, "Reading database structure…", db.schema(), schema -> {
            if (panel != null) panel.showSchema(schema);
            status = schema.tables().size() + " tables/views" + (schema.truncated() ? " (first 2000; refine with SQL)" : "") + transactionState(); refresh();
        }, null);
    }
    void columns(DatabaseSession.Table table) {
        DatabaseSession db = session; if (db == null || busy) return;
        watch(db, "Reading columns…", db.columns(table), columns -> {
            if (panel != null) panel.showColumns(table, columns);
            status = table.name() + " — " + columns.size() + " columns" + transactionState(); refresh();
        }, null);
    }
    void execute(String sql, int maxRows, int timeout) {
        DatabaseSession db = session; if (db == null || busy) return;
        watch(db, "Executing SQL…", db.execute(sql, maxRows, timeout), result -> {
            if (panel != null) panel.showResult(result);
            long rows = result.grids().stream().mapToLong(grid -> grid.rows().size()).sum();
            status = "Completed in " + result.elapsedMillis() + " ms — " + rows + " displayed rows"
                    + (result.updateCounts().isEmpty() ? "" : "; update counts " + result.updateCounts())
                    + transactionState(); refresh();
        }, null);
    }
    void explain(String sql, int maxRows, int timeout) {
        DatabaseSession db = session; if (db == null || busy) return;
        watch(db, "Requesting estimated query plan (no ANALYZE)…", db.explain(sql, maxRows, timeout), result -> {
            if (panel != null) panel.showPlan(result);
            status = "Estimated query plan in " + result.elapsedMillis() + " ms; ANALYZE was not enabled." + transactionState(); refresh();
        }, null);
    }
    void complete(String sql, int caret, Consumer<SqlCompletion.Result> show) {
        DatabaseSession db = session; if (db == null || busy) return;
        long request = ++completionGeneration;
        busy = true; status = "Reading current schema for completion…"; refresh();
        db.complete(sql, caret).whenComplete((result, failure) -> later(() -> {
            if (session != db) return;
            busy = false;
            if (request != completionGeneration) { status = "Schema completion cancelled."; refresh(); return; }
            if (failure != null) status = "Completion unavailable: " + errorText(failure);
            else {
                status = result.items().isEmpty() ? "No suggestions. " + result.note() : result.items().size() + " schema suggestions. " + result.note();
                show.accept(result);
            }
            refresh();
        }));
    }
    void transaction(String operation) {
        DatabaseSession db = session; if (db == null || busy) return;
        CompletableFuture<Boolean> future = switch (operation) { case "Begin" -> db.begin(); case "Commit" -> db.finishTransaction(true); default -> db.finishTransaction(false); };
        watch(db, operation + " transaction…", future, ignored -> { status = operation + " completed" + transactionState(); refresh(); }, null);
    }
    void editRow(DatabaseSession.Table table) {
        DatabaseSession db = session; if (db == null || busy) return;
        if (db.inTransaction()) { status = "Commit or Rollback the current transaction before editing a row."; refresh(); return; }
        watch(db, "Reading keys for row edit…", db.columns(table), columns -> {
            if (columns.stream().noneMatch(DatabaseSession.Column::primaryKey)) { status = "Row editing requires a declared primary key."; refresh(); return; }
            DatabaseRowEditDialog dialog = new DatabaseRowEditDialog(project, table, columns);
            if (!dialog.showAndGet() || session != db) return;
            watch(db, "Staging row update…", db.stageUpdate(table, dialog.keys(), dialog.column(), dialog.value()), result -> {
                if (panel != null) panel.showResult(result);
                status = "One row update staged. Commit applies it; Rollback or Disconnect discards it."; refresh();
            }, null);
        }, null);
    }
    void export(DatabaseSession.Grid grid, Path destination) {
        CompletableFuture.runAsync(() -> {
            try (var writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) { DatabaseCsv.write(grid, writer); }
            catch (java.io.IOException error) { throw new CompletionException(error); }
        }).whenComplete((ignored, failure) -> later(() -> {
            status = failure == null ? "Exported " + grid.rows().size() + " displayed rows to " + destination + (grid.truncated() ? " (truncated result)." : ".")
                    : "Export failed: " + errorText(failure); refresh();
        }));
    }
    private String transactionState() { return session != null && session.inTransaction() ? " — transaction open; Commit or Rollback when ready." : " — autocommit on."; }
    void cancel() {
        if (session == null || !busy) return;
        completionGeneration++;
        session.cancel(); status = "Cancellation requested. Already committed changes are not undone."; refresh();
    }
    void disconnect(boolean confirm) {
        DatabaseSession db = session; if (db == null) return;
        if (confirm && (db.inTransaction() || busy) && Messages.showYesNoDialog(project,
                "Disconnect this database? Running work will be cancelled and an open transaction rolled back. Completed autocommit changes remain.",
                "Disconnect Database", Messages.getQuestionIcon()) != Messages.YES) return;
        // Detach before asynchronous closure so late query callbacks cannot repopulate the view.
        session = null; connectedProfile = null; connectionGeneration++; busy = true; status = "Disconnecting…";
        if (panel != null) panel.clearResults(); refresh();
        db.disconnect().whenComplete((ignored, failure) -> later(() -> {
            busy = false; status = failure == null ? "Disconnected. SQL text remains only in this window's memory." : "Disconnected with a cleanup error: " + errorText(failure); refresh();
        }));
    }
    private <T> void watch(DatabaseSession db, String message, CompletableFuture<T> future, Consumer<T> success, Runnable failed) {
        busy = true; status = message; refresh();
        future.whenComplete((result, failure) -> later(() -> {
            if (session != db) return;
            busy = false;
            if (failure == null) success.accept(result);
            else {
                status = "Database error: " + errorText(failure) + " Completed autocommit changes are not undone.";
                if (panel != null) panel.showError(errorText(failure));
                if (failed != null) failed.run();
            }
            refresh();
        }));
    }
    private void later(Runnable task) {
        ApplicationManager.getApplication().invokeLater(() -> { if (!disposed && !project.isDisposed()) task.run(); }, ModalityState.any());
    }
    static String errorText(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof java.util.concurrent.ExecutionException) && failure.getCause() != null) failure = failure.getCause();
        String text = failure.getMessage();
        if (text == null || text.isBlank()) text = failure.getClass().getSimpleName();
        return text.length() > 4000 ? text.substring(0, 4000) + "…" : text;
    }
    private void disposeConnection() {
        DatabaseSession db = session; session = null; connectedProfile = null; connectionGeneration++; busy = false;
        if (db != null) db.close();
    }
    @Override public void dispose() { disposed = true; panel = null; disposeConnection(); }
}

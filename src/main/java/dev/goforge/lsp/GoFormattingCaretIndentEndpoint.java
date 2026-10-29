package dev.goforge.lsp;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.psi.TokenType;
import com.redhat.devtools.lsp4ij.client.features.FileUriSupport;
import dev.goforge.language.GoFileType;
import dev.goforge.language.GoSourceLexer;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.Endpoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/** Keeps only an active Go caret's code indentation out of full-format whitespace edits. */
public final class GoFormattingCaretIndentEndpoint implements Endpoint {
    private static final String METHOD = "textDocument/formatting";
    private final Endpoint delegate;
    private final Project project;
    private final FileUriSupport uris;
    private final Map<String, Integer> sentVersions;
    private final Map<String, Overlay> overlays = new ConcurrentHashMap<>();

    public GoFormattingCaretIndentEndpoint(Endpoint delegate, Project project, FileUriSupport uris,
                                           Map<String, Integer> sentVersions) {
        this.delegate = Objects.requireNonNull(delegate);
        this.project = Objects.requireNonNull(project);
        this.uris = Objects.requireNonNull(uris);
        this.sentVersions = Objects.requireNonNull(sentVersions);
    }

    @Override public CompletableFuture<?> request(String method, Object parameter) {
        if (!METHOD.equals(method) || !(parameter instanceof DocumentFormattingParams params))
            return delegate.request(method, parameter);
        Snapshot snapshot = snapshot(params);
        CompletableFuture<?> source = delegate.request(method, parameter);
        if (snapshot == null) return source;
        var result = new CompletableFuture<Object>();
        result.whenComplete((ignored, error) -> { if (result.isCancelled()) source.cancel(true); });
        source.whenComplete((value, error) -> {
            if (error == null) {
                // LSP responses arrive on the JSON-RPC executor. Caret queries require
                // read access even though they do not require EDT. Never wait for RPC here.
                try { result.complete(ReadAction.compute(() -> protect(value, snapshot))); }
                catch (ProcessCanceledException canceled) { result.cancel(false); }
                catch (RuntimeException unavailable) { result.complete(value); }
            }
            else if (unwrap(error) instanceof CancellationException) result.cancel(false);
            else result.completeExceptionally(error);
        });
        return result;
    }

    @Override public void notify(String method, Object parameter) {
        delegate.notify(method, parameter);
        if ("textDocument/didOpen".equals(method) && parameter instanceof DidOpenTextDocumentParams open) {
            var item = open.getTextDocument();
            if (item != null && item.getUri() != null && item.getText() != null)
                overlays.put(item.getUri(), new Overlay(item.getVersion(), item.getText()));
        } else if ("textDocument/didChange".equals(method) && parameter instanceof DidChangeTextDocumentParams change) {
            var item = change.getTextDocument();
            if (item != null && item.getUri() != null) overlays.compute(item.getUri(), (uri, previous) -> changed(previous, change));
        } else if ("textDocument/didClose".equals(method) && parameter instanceof DidCloseTextDocumentParams close
                && close.getTextDocument() != null && close.getTextDocument().getUri() != null) {
            overlays.remove(close.getTextDocument().getUri());
        }
    }

    private static Overlay changed(Overlay previous, DidChangeTextDocumentParams change) {
        Integer version = change.getTextDocument().getVersion();
        if (previous == null || version == null || version <= previous.version || change.getContentChanges() == null) return null;
        String text = previous.text;
        for (var edit : change.getContentChanges()) {
            if (edit == null || edit.getText() == null) return null;
            if (edit.getRange() == null) text = edit.getText();
            else {
                int start = offset(text, edit.getRange().getStart()), end = offset(text, edit.getRange().getEnd());
                if (start < 0 || end < start) return null;
                text = text.substring(0, start) + edit.getText() + text.substring(end);
            }
        }
        return new Overlay(version, text);
    }

    private static int offset(String text, Position position) {
        if (position == null || position.getLine() < 0 || position.getCharacter() < 0) return -1;
        int start = 0;
        for (int line = 0; line < position.getLine(); line++) {
            int newline = text.indexOf('\n', start);
            if (newline < 0) return -1;
            start = newline + 1;
        }
        int end = text.indexOf('\n', start); if (end < 0) end = text.length();
        if (end > start && text.charAt(end - 1) == '\r') end--;
        return position.getCharacter() <= end - start ? start + position.getCharacter() : -1;
    }

    private Snapshot snapshot(DocumentFormattingParams params) {
        if (params.getTextDocument() == null || params.getTextDocument().getUri() == null || project.isDisposed()) return null;
        String uri = params.getTextDocument().getUri();
        Integer version = sentVersions.get(uri);
        Overlay overlay = overlays.get(uri);
        if (version == null || overlay == null || version != overlay.version) return null;
        try {
            return ReadAction.compute(() -> {
                if (project.isDisposed() || !EditorSettingsExternalizable.getInstance().isKeepTrailingSpacesOnCaretLine()) return null;
                var file = uris.findFileByUri(uri);
                if (file == null || !file.isValid() || file.getFileType() != GoFileType.INSTANCE
                        || !ProjectFileIndex.getInstance(project).isInContent(file)) return null;
                Document document = FileDocumentManager.getInstance().getCachedDocument(file);
                Editor editor = GoFormattingCaretIndentContext.getInstance(project).currentEditor();
                if (document == null || editor == null || editor.isDisposed() || editor.isViewer()
                        || editor.getProject() != project || editor.getDocument() != document) return null;
                String text = document.getText();
                if (!text.equals(overlay.text)) return null; // didChange must have caught up before this request.
                var lines = new ArrayList<ProtectedLine>();
                var carets = new ArrayList<CaretPosition>();
                for (Caret caret : editor.getCaretModel().getAllCarets()) {
                    int offset = caret.getOffset();
                    carets.add(new CaretPosition(caret, offset, caret.getSelectionStart(), caret.getSelectionEnd()));
                    if (caret.hasSelection()) continue;
                    int line = document.getLineNumber(offset);
                    int start = document.getLineStartOffset(line), end = document.getLineEndOffset(line);
                    if (start < end && horizontal(text, start, end) && codeWhitespace(text, start, end))
                        lines.add(new ProtectedLine(line, end - start));
                }
                if (lines.isEmpty() || !Objects.equals(version, sentVersions.get(uri)) || overlays.get(uri) != overlay) return null;
                return new Snapshot(uri, version, document, document.getModificationStamp(), editor,
                        List.copyOf(carets), List.copyOf(lines), text.split("\n", -1), overlay);
            });
        } catch (ProcessCanceledException canceled) {
            throw canceled;
        } catch (RuntimeException unavailable) {
            // A closing editor/project must not turn formatting into a different operation.
            return null;
        }
    }

    private Object protect(Object value, Snapshot snapshot) {
        if (!(value instanceof List<?> edits) || !stillCurrent(snapshot)) return value;
        var spans = new ArrayList<Span>();
        var kept = new ArrayList<TextEdit>();
        boolean changed = false;
        for (Object item : edits) {
            if (!(item instanceof TextEdit edit) || edit.getRange() == null || edit.getNewText() == null
                    || edit.getRange().getStart() == null || edit.getRange().getEnd() == null) return value;
            var start = edit.getRange().getStart();
            var end = edit.getRange().getEnd();
            if (!valid(snapshot, start.getLine(), start.getCharacter()) || !valid(snapshot, end.getLine(), end.getCharacter())
                    || start.getLine() > end.getLine() || start.getLine() == end.getLine() && start.getCharacter() > end.getCharacter()) return value;
            spans.add(new Span(start.getLine(), start.getCharacter(), end.getLine(), end.getCharacter()));
            boolean protect = false;
            for (ProtectedLine line : snapshot.lines) {
                if (start.getLine() <= line.number && end.getLine() >= line.number) {
                    // Do not guess how a coalesced/cross-line rewrite maps the protected line.
                    if (start.getLine() != end.getLine()) return value;
                    if (start.getLine() == line.number && end.getCharacter() <= line.length
                            && horizontal(edit.getNewText(), 0, edit.getNewText().length())) protect = true;
                }
            }
            if (protect) changed = true;
            else kept.add(edit); // Retain object identity and server order for every other edit.
        }
        spans.sort(Comparator.comparingInt(Span::startLine).thenComparingInt(Span::startColumn));
        for (int i = 1; i < spans.size(); i++) {
            Span previous = spans.get(i - 1), next = spans.get(i);
            if (previous.endLine > next.startLine || previous.endLine == next.startLine && previous.endColumn > next.startColumn)
                return value;
        }
        return changed && stillCurrent(snapshot) ? List.copyOf(kept) : value;
    }

    private boolean stillCurrent(Snapshot snapshot) {
        if (project.isDisposed() || snapshot.editor.isDisposed() || snapshot.document.getModificationStamp() != snapshot.stamp
                || !EditorSettingsExternalizable.getInstance().isKeepTrailingSpacesOnCaretLine()
                || !Objects.equals(sentVersions.get(snapshot.uri), snapshot.version)
                || overlays.get(snapshot.uri) != snapshot.overlay
                || GoFormattingCaretIndentContext.getInstance(project).currentEditor() != snapshot.editor
                || snapshot.editor.getCaretModel().getCaretCount() != snapshot.carets.size()) return false;
        for (CaretPosition position : snapshot.carets) {
            Caret caret = position.caret;
            if (!caret.isValid() || caret.getOffset() != position.offset || caret.getSelectionStart() != position.selectionStart
                    || caret.getSelectionEnd() != position.selectionEnd) return false;
        }
        return true;
    }

    private static boolean valid(Snapshot snapshot, int line, int column) {
        return line >= 0 && line < snapshot.sourceLines.length && column >= 0 && column <= snapshot.sourceLines[line].length();
    }

    private static boolean horizontal(String text, int start, int end) {
        for (int i = start; i < end; i++) if (text.charAt(i) != ' ' && text.charAt(i) != '\t') return false;
        return true;
    }

    private static boolean codeWhitespace(String text, int start, int end) {
        var lexer = new GoSourceLexer();
        lexer.start(text);
        while (lexer.getTokenType() != null && lexer.getTokenEnd() <= start) lexer.advance();
        return lexer.getTokenType() == TokenType.WHITE_SPACE && lexer.getTokenStart() <= start && lexer.getTokenEnd() >= end;
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null)
            error = error.getCause();
        return error;
    }

    private record ProtectedLine(int number, int length) {}
    private record CaretPosition(Caret caret, int offset, int selectionStart, int selectionEnd) {}
    private record Span(int startLine, int startColumn, int endLine, int endColumn) {}
    private record Overlay(int version, String text) {}
    private record Snapshot(String uri, Integer version, Document document, long stamp, Editor editor,
                            List<CaretPosition> carets, List<ProtectedLine> lines, String[] sourceLines, Overlay overlay) {}
}

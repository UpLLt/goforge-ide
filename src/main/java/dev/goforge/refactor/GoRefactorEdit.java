package dev.goforge.refactor;

import org.eclipse.lsp4j.*;
import java.net.URI;
import java.util.*;

/** Validates the entire single-document edit before changing an IDE buffer. */
public record GoRefactorEdit(String original, String replacement, List<Change> changes) {
    public record Change(int start, int end, String text) { }
    public GoRefactorEdit { changes = List.copyOf(changes); }

    public static GoRefactorEdit prepare(String uri, String original, Integer version, WorkspaceEdit edit) {
        if (edit == null) throw new IllegalArgumentException("This refactoring did not provide previewable edits.");
        var raw = new ArrayList<TextEdit>();
        boolean hasChanges = edit.getChanges() != null && !edit.getChanges().isEmpty();
        boolean hasDocuments = edit.getDocumentChanges() != null && !edit.getDocumentChanges().isEmpty();
        if (hasChanges && hasDocuments) throw new IllegalArgumentException("Ambiguous workspace edit formats.");
        if (hasChanges) for (var entry : edit.getChanges().entrySet()) {
            requireUri(uri, entry.getKey()); raw.addAll(entry.getValue());
        }
        if (hasDocuments) for (var item : edit.getDocumentChanges()) {
            if (!item.isLeft()) throw new IllegalArgumentException("File creation, deletion and rename are not supported by this preview.");
            var document = item.getLeft();
            requireUri(uri, document.getTextDocument().getUri());
            Integer requested = document.getTextDocument().getVersion();
            if (requested != null && !requested.equals(version)) throw new IllegalArgumentException("The Go document version changed. Request the refactoring again.");
            // The platform compile API returns TextEdit, while the pinned LSP4J
            // 1.0 wire decoder returns Either<TextEdit, SnippetTextEdit>. Accept
            // ordinary edits from either API; never interpret snippet placeholders.
            for (Object value : document.getEdits()) {
                if (value instanceof org.eclipse.lsp4j.jsonrpc.messages.Either<?, ?> either) {
                    if (!either.isLeft()) throw new IllegalArgumentException("Snippet edits cannot be applied by this preview.");
                    value = either.getLeft();
                }
                if (!(value instanceof TextEdit text)) throw new IllegalArgumentException("Unsupported refactoring edit format.");
                raw.add(text);
            }
        }
        if (raw.isEmpty()) throw new IllegalArgumentException("The refactoring produced no edits.");
        if (raw.size() > 10000) throw new IllegalArgumentException("Refactoring exceeds the 10,000-edit limit.");
        var edits = new ArrayList<Change>();
        for (var item : raw) {
            if (item instanceof AnnotatedTextEdit annotated && annotated.getAnnotationId() != null)
                throw new IllegalArgumentException("This edit requires an annotation confirmation that is not supported here.");
            int start = offset(original, item.getRange().getStart()), end = offset(original, item.getRange().getEnd());
            if (end < start || item.getNewText() == null) throw new IllegalArgumentException("Invalid Go refactoring edit range.");
            edits.add(new Change(start, end, item.getNewText()));
        }
        edits.sort(Comparator.comparingInt(Change::start).thenComparingInt(Change::end));
        for (int i = 1; i < edits.size(); i++) {
            var before = edits.get(i - 1); var after = edits.get(i);
            if (after.start < before.end || after.start == before.start)
                throw new IllegalArgumentException("Overlapping or ambiguous refactoring edits.");
        }
        var result = new StringBuilder(original);
        for (int i = edits.size() - 1; i >= 0; i--) {
            var item = edits.get(i); result.replace(item.start, item.end, item.text);
            if (result.length() > 8 * 1024 * 1024) throw new IllegalArgumentException("Refactoring exceeds the 8 MiB preview limit.");
        }
        if (original.contentEquals(result)) throw new IllegalArgumentException("The refactoring does not change this file.");
        return new GoRefactorEdit(original, result.toString(), edits);
    }

    private static void requireUri(String expected, String actual) {
        if (actual == null || !URI.create(expected).normalize().equals(URI.create(actual).normalize()))
            throw new IllegalArgumentException("This preview supports the current file only. Cross-file and file-move edits were not applied.");
    }

    public static Position position(String text, int offset) {
        if (offset < 0 || offset > text.length() || splitSurrogate(text, offset))
            throw new IllegalArgumentException("Selection splits a character or lies outside the file.");
        int line = 0, lineStart = 0;
        for (int i = 0; i < offset; i++) if (text.charAt(i) == '\n') { line++; lineStart = i + 1; }
        return new Position(line, offset - lineStart);
    }

    public static int offset(String text, Position position) {
        if (position == null || position.getLine() < 0 || position.getCharacter() < 0)
            throw new IllegalArgumentException("Invalid refactoring position.");
        int start = 0;
        for (int line = 0; line < position.getLine(); line++) {
            int newline = text.indexOf('\n', start);
            if (newline < 0) throw new IllegalArgumentException("Refactoring line is outside the file.");
            start = newline + 1;
        }
        int end = text.indexOf('\n', start); if (end < 0) end = text.length();
        if (end > start && text.charAt(end - 1) == '\r') end--;
        long offset = (long) start + position.getCharacter();
        if (offset > end || splitSurrogate(text, (int) offset)) throw new IllegalArgumentException("Refactoring column is outside a character boundary.");
        return (int) offset;
    }

    private static boolean splitSurrogate(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1)) && Character.isLowSurrogate(text.charAt(offset));
    }
}

package dev.goforge.refactor;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class GoRefactorEditTest {
    private static final String URI = "file:///workspace/main.go";
    private static TextEdit replace(int from, int to, String value) { return new TextEdit(new Range(new Position(0, from), new Position(0, to)), value); }
    private static WorkspaceEdit edits(TextEdit... values) { return new WorkspaceEdit(Map.of(URI, List.of(values))); }
    @Test public void appliesAllOriginalOffsetsInReverseOrder() {
        var plan = GoRefactorEdit.prepare(URI, "one + two", 1, edits(replace(6, 9, "second"), replace(0, 3, "first")));
        assertEquals("first + second", plan.replacement()); assertEquals("one + two", plan.original());
    }
    @Test public void validatesUtf16BoundariesAndCrLf() {
        assertEquals(new Position(1, 4), GoRefactorEdit.position("a\n世🙂x", 6));
        assertEquals(6, GoRefactorEdit.offset("a\n世🙂x", new Position(1, 4)));
        assertEquals(1, GoRefactorEdit.offset("a\r\nb", new Position(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> GoRefactorEdit.offset("a\r\nb", new Position(0, 2)));
        assertThrows(IllegalArgumentException.class, () -> GoRefactorEdit.offset("🙂", new Position(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> GoRefactorEdit.position("🙂", 1));
    }
    @Test public void rejectsOverlappingAndAmbiguousInsertions() {
        assertThrows(IllegalArgumentException.class, () -> GoRefactorEdit.prepare(URI, "abcd", 1, edits(replace(0, 2, "x"), replace(1, 3, "y"))));
        assertThrows(IllegalArgumentException.class, () -> GoRefactorEdit.prepare(URI, "abcd", 1, edits(replace(1, 1, "x"), replace(1, 1, "y"))));
    }
    @Test public void rejectsOtherFileBeforeApplyingAnyPart() {
        var edit = new WorkspaceEdit(Map.of(URI, List.of(replace(0, 1, "b")), "file:///workspace/else.go", List.of(replace(0, 1, "c"))));
        assertThrows(IllegalArgumentException.class, () -> GoRefactorEdit.prepare(URI, "a", 1, edit));
    }
    @Test public void rejectsResourceOperations() {
        var edit = new WorkspaceEdit(List.of(Either.forRight(new CreateFile("file:///workspace/new.go"))));
        assertThrows(IllegalArgumentException.class, () -> GoRefactorEdit.prepare(URI, "a", 1, edit));
    }
    @Test public void validatesDocumentVersion() {
        var edit = new WorkspaceEdit(List.of(Either.forLeft(new TextDocumentEdit(new VersionedTextDocumentIdentifier(URI, 4), List.of(replace(0, 1, "b"))))));
        assertEquals("b", GoRefactorEdit.prepare(URI, "a", 4, edit).replacement());
        assertThrows(IllegalArgumentException.class, () -> GoRefactorEdit.prepare(URI, "a", 3, edit));
    }
    @Test public void rejectsMixedFormatsInvalidRangeAndNoChange() {
        var mixed = edits(replace(0, 1, "b"));
        mixed.setDocumentChanges(List.of(Either.forLeft(new TextDocumentEdit(new VersionedTextDocumentIdentifier(URI, 1), List.of(replace(0, 1, "b"))))));
        assertThrows(IllegalArgumentException.class, () -> GoRefactorEdit.prepare(URI, "a", 1, mixed));
        assertThrows(IllegalArgumentException.class, () -> GoRefactorEdit.prepare(URI, "a", 1, edits(replace(0, 5, "b"))));
        assertThrows(IllegalArgumentException.class, () -> GoRefactorEdit.prepare(URI, "a", 1, edits(replace(0, 1, "a"))));
    }
    @Test public void rejectsUnconfirmedAnnotations() {
        var edit = new AnnotatedTextEdit(new Range(new Position(0, 0), new Position(0, 1)), "b", "confirmation");
        assertThrows(IllegalArgumentException.class, () -> GoRefactorEdit.prepare(URI, "a", 1, edits(edit)));
    }
    @Test @SuppressWarnings({"rawtypes", "unchecked"}) public void acceptsLspOneWireEitherEdits() {
        var item = new TextDocumentEdit(new VersionedTextDocumentIdentifier(URI, 1),
                (List) List.of(Either.forLeft(replace(0, 1, "b"))));
        assertEquals("b", GoRefactorEdit.prepare(URI, "a", 1,
                new WorkspaceEdit(List.of(Either.forLeft(item)))).replacement());
    }
}

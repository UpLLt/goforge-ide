package dev.goforge.language;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GoSaveImportEditsTest {
    private static final Path FILE = Path.of(System.getProperty("java.io.tmpdir"), "goforge import 源代码", "main.go")
            .toAbsolutePath().normalize();
    private static final String URI = FILE.toUri().toString();
    private static final int VERSION = 17;
    private static final String SOURCE = "package main\n\nimport \"strings\"\n\nfunc main() { fmt.Println(\"你好 😀\") }\n";

    private static TextEdit edit(int line, int start, int end, String value) {
        return new TextEdit(new Range(new Position(line, start), new Position(line, end)), value);
    }

    private static CodeAction action(String uri, Integer version, List<TextEdit> edits) {
        var action = new CodeAction("本地化名称不参与匹配");
        action.setKind(CodeActionKind.SourceOrganizeImports);
        action.setEdit(new WorkspaceEdit(List.of(Either.forLeft(
                new TextDocumentEdit(new VersionedTextDocumentIdentifier(uri, version), edits)))));
        return action;
    }

    private static CodeAction action() { return action(URI, VERSION, List.of(edit(2, 8, 15, "fmt"))); }
    private static List<Either<Command, CodeAction>> response(CodeAction action) { return List.of(Either.forRight(action)); }
    private static List<GoSaveFormattingEdits.Change> prepare(CodeAction action) {
        return GoSaveImportEdits.prepare(URI, SOURCE, VERSION, response(action));
    }
    private static void refuses(Consumer<CodeAction> change) {
        var action = action(); change.accept(action);
        assertThrows(IllegalArgumentException.class, () -> prepare(action));
    }
    private static TextDocumentEdit document(CodeAction action) {
        return action.getEdit().getDocumentChanges().get(0).getLeft();
    }
    private static String apply(String text, List<GoSaveFormattingEdits.Change> changes) {
        var result = new StringBuilder(text);
        for (int i = changes.size() - 1; i >= 0; i--) {
            var change = changes.get(i); result.replace(change.start(), change.end(), change.text());
        }
        return result.toString();
    }

    @Test public void directVersionedImportReplacementUsesExactOffsetsAndIgnoresTitle() {
        var result = prepare(action());
        assertEquals(SOURCE.replace("\"strings\"", "\"fmt\""), apply(SOURCE, result));
        assertEquals(1, result.size());
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
    }

    @Test public void deserializesPinnedDirectActionWithDefaultEmptyChangesMap() {
        // Replay the protocol-1 action shape with a local URI and no changes field.
        // Pinned LSP4J initializes an absent changes field to an empty map during decoding.
        var gson = new org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler(Map.of()).getGson();
        var json = """
                [{"title":"Organize Imports","kind":"source.organizeImports","edit":{
                  "documentChanges":[{
                    "textDocument":{"version":7,"uri":URI_PLACEHOLDER},"edits":[
                      {"range":{"start":{"line":1,"character":0},"end":{"line":1,"character":0}},"newText":"\\n"},
                      {"range":{"start":{"line":1,"character":8},"end":{"line":1,"character":10}},"newText":"fmt"},
                      {"range":{"start":{"line":1,"character":11},"end":{"line":1,"character":11}},"newText":"\\n"}
                    ]}]
                }}]
                """.replace("URI_PLACEHOLDER", gson.toJson(URI));
        var decoded = gson.fromJson(json, CodeAction[].class);
        var actions = response(decoded[0]);
        assertEquals(Map.of(), actions.get(0).getRight().getEdit().getChanges());
        var source = "package imports\nimport \"os\"\n// 世界🙂 survives import edits.\n"
                + "func Message() string {return fmt.Sprintf(\"%s\", \"🙂\")}\n";
        var edits = GoSaveImportEdits.prepare(URI, source, 7, actions);
        assertEquals(source.replace("\nimport \"os\"\n", "\n\nimport \"fmt\"\n\n"), apply(source, edits));
        assertEquals(3, edits.size());
        actions.get(0).getRight().getEdit().setChanges(Map.of(URI, List.of()));
        assertThrows(IllegalArgumentException.class, () -> GoSaveImportEdits.prepare(URI, source, 7, actions));
    }

    @Test public void noActionsAndIdenticalEditsAreNoOp() {
        assertTrue(GoSaveImportEdits.prepare(URI, SOURCE, VERSION, null).isEmpty());
        assertTrue(GoSaveImportEdits.prepare(URI, SOURCE, VERSION, List.of()).isEmpty());
        assertTrue(prepare(action(URI, VERSION, List.of())).isEmpty());
        assertTrue(prepare(action(URI, VERSION, List.of(edit(2, 8, 15, "strings")))).isEmpty());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test public void runtimeEitherLeftOrdinaryEditsAreAcceptedButSnippetsAndAnnotationsAreRefused() {
        var action=action();
        document(action).setEdits((List)List.of(Either.forLeft(edit(2,8,15,"fmt"))));
        assertEquals(SOURCE.replace("\"strings\"","\"fmt\""),apply(SOURCE,prepare(action)));
        document(action).setEdits((List)List.of(Either.forRight(Map.of("snippet","${1:fmt}"))));
        assertThrows(IllegalArgumentException.class,()->prepare(action));
        document(action).setEdits((List)List.of(Either.forLeft(new AnnotatedTextEdit(edit(2,8,15,"fmt").getRange(),"fmt","confirm"))));
        assertThrows(IllegalArgumentException.class,()->prepare(action));
        document(action).setEdits((List)List.of(Either.forLeft(null)));
        assertThrows(IllegalArgumentException.class,()->prepare(action));
    }

    @Test public void normalizesLocalPathWithoutRequiringFilesToExist() {
        var same = FILE.getParent().resolve("missing-subdirectory").resolve("..").resolve("main.go").toUri().toString();
        assertEquals(prepare(action()), prepare(action(same, VERSION, List.of(edit(2, 8, 15, "fmt")))));
        var caseVariant = FILE.resolveSibling("MAIN.go");
        var candidate = action(caseVariant.toUri().toString(), VERSION, List.of(edit(2, 8, 15, "fmt")));
        if (FILE.equals(caseVariant)) assertEquals(prepare(action()), prepare(candidate));
        else assertThrows(IllegalArgumentException.class, () -> prepare(candidate));
    }

    @Test public void rejectsOtherFileAndNonlocalOrMalformedUris() {
        for (var uri : List.of(FILE.resolveSibling("other.go").toUri().toString(), "untitled:main.go", "https://host/main.go",
                "file://remote/main.go", URI + "?query", URI + "#fragment", "file:main.go", "not a uri", "")) {
            assertThrows(uri, IllegalArgumentException.class, () -> prepare(action(uri, VERSION, List.of())));
            assertThrows(uri, IllegalArgumentException.class, () -> GoSaveImportEdits.prepare(uri, SOURCE, VERSION, response(action())));
        }
        refuses(a -> {
            var identifier = new VersionedTextDocumentIdentifier(); identifier.setVersion(VERSION);
            document(a).setTextDocument(identifier);
        });
        assertThrows(IllegalArgumentException.class, () -> GoSaveImportEdits.prepare(null, SOURCE, VERSION, response(action())));
    }

    @Test public void rejectsUnversionedStaleOrAbsentDocumentIdentity() {
        refuses(a -> document(a).getTextDocument().setVersion(null));
        refuses(a -> document(a).getTextDocument().setVersion(VERSION - 1));
        refuses(a -> document(a).getTextDocument().setVersion(VERSION + 1));
        refuses(a -> a.getEdit().setDocumentChanges(List.of(Either.forLeft(new TextDocumentEdit()))));
    }

    @Test public void rejectsCommandsLazyDataDisabledWrongKindAndMultipleActions() {
        assertThrows(IllegalArgumentException.class, () -> GoSaveImportEdits.prepare(URI, SOURCE, VERSION,
                List.of(Either.forLeft(new Command("title", "gopls.anything")))));
        refuses(a -> a.setCommand(new Command("title", "gopls.anything")));
        refuses(a -> a.setData(Map.of("command", "gopls.anything")));
        refuses(a -> a.setData(Map.of()));
        refuses(a -> a.setDisabled(new CodeActionDisabled("disabled")));
        for (var kind : List.of("quickfix", "source", "source.organizeImports.extra", "SOURCE.ORGANIZEIMPORTS", "")) {
            refuses(a -> a.setKind(kind));
        }
        refuses(a -> a.setKind(null));
        assertThrows(IllegalArgumentException.class, () -> GoSaveImportEdits.prepare(URI, SOURCE, VERSION,
                List.of(Either.forRight(action()), Either.forRight(action()))));
    }

    @Test public void rejectsAbsentMixedOrUnversionedWorkspaceEdits() {
        refuses(a -> a.setEdit(null));
        refuses(a -> a.setEdit(new WorkspaceEdit(Map.of(URI, List.of(edit(2, 8, 15, "fmt"))))));
        refuses(a -> a.getEdit().setChanges(Map.of(URI, List.of())));
        refuses(a -> a.getEdit().setDocumentChanges(null));
        refuses(a -> a.getEdit().setDocumentChanges(List.of()));
        refuses(a -> a.getEdit().setDocumentChanges(List.of(Either.forLeft(document(a)), Either.forLeft(document(a)))));
    }

    @Test public void rejectsAllResourceOperationsAndAnnotations() {
        for (ResourceOperation operation : List.of(new CreateFile(URI), new RenameFile(URI, URI + "-other"), new DeleteFile(URI))) {
            refuses(a -> a.getEdit().setDocumentChanges(List.of(Either.forRight(operation))));
        }
        refuses(a -> a.getEdit().setChangeAnnotations(Map.of("confirm", new ChangeAnnotation("import edit"))));
        refuses(a -> a.getEdit().setChangeAnnotations(Map.of()));
        refuses(a -> document(a).setEdits(List.of(new AnnotatedTextEdit(edit(2, 8, 15, "fmt").getRange(), "fmt", "confirm"))));
        refuses(a -> {
            var annotated = new AnnotatedTextEdit(); annotated.setRange(edit(2, 8, 15, "fmt").getRange());
            annotated.setNewText("fmt"); document(a).setEdits(List.of(annotated));
        });
    }

    @Test public void rejectsMalformedContainersAndEditsBeforeReturningAnyChanges() {
        assertThrows(IllegalArgumentException.class, () -> GoSaveImportEdits.prepare(URI, SOURCE, VERSION, Collections.singletonList(null)));
        assertThrows(IllegalArgumentException.class, () -> GoSaveImportEdits.prepare(URI, SOURCE, VERSION,
                List.of(Either.<Command, CodeAction>forRight(null))));
        refuses(a -> a.getEdit().setDocumentChanges(Collections.singletonList(null)));
        refuses(a -> a.getEdit().setDocumentChanges(List.of(Either.forLeft(null))));
        refuses(a -> {
            var incomplete = new TextDocumentEdit(); incomplete.setTextDocument(document(a).getTextDocument());
            a.getEdit().setDocumentChanges(List.of(Either.forLeft(incomplete)));
        });
        refuses(a -> document(a).setEdits(Collections.singletonList(null)));
        refuses(a -> document(a).setEdits(List.of(new TextEdit())));
        refuses(a -> {
            var incomplete = new TextEdit(); incomplete.setRange(edit(2, 8, 15, "fmt").getRange());
            document(a).setEdits(List.of(incomplete));
        });
    }

    @Test public void preservesUtf16CrLfAndSortsEditsForReverseApplication() {
        var text = "// 你好 😀\r\npackage main\r\nimport \"strings\"\r\n";
        var edits = List.of(edit(2, 8, 15, "fmt"), edit(0, 6, 8, "🌍"));
        var result = GoSaveImportEdits.prepare(URI, text, VERSION, response(action(URI, VERSION, edits)));
        assertEquals("// 你好 🌍\r\npackage main\r\nimport \"fmt\"\r\n", apply(text, result));
        assertEquals(6, result.get(0).start());
        assertEquals(8, result.get(0).end());
    }

    @Test public void rejectsInvalidRangesOverlapsDuplicateInsertAndSplitSurrogate() {
        var badEdits = List.of(List.of(edit(2, 15, 8, "x")), List.of(edit(2, -1, 1, "x")),
                List.of(edit(99, 0, 0, "x")), List.of(edit(2, 0, 100, "x")),
                List.of(edit(2, 0, 10, "x"), edit(2, 8, 15, "x")),
                List.of(edit(2, 0, 0, "x"), edit(2, 0, 0, "y")));
        for (var edits : badEdits) assertThrows(IllegalArgumentException.class, () -> prepare(action(URI, VERSION, edits)));
        assertThrows(IllegalArgumentException.class, () -> GoSaveImportEdits.prepare(URI, "// 😀", VERSION,
                response(action(URI, VERSION, List.of(edit(0, 0, 0, "valid"), edit(0, 4, 5, "bad"))))));
    }

    @Test public void enforcesEditCountAndResultSizeBounds() {
        var edits = new ArrayList<TextEdit>(Collections.nCopies(10001, edit(2, 8, 15, "fmt")));
        assertThrows(IllegalArgumentException.class, () -> prepare(action(URI, VERSION, edits)));
        var huge = "x".repeat(8 * 1024 * 1024 + 1);
        assertThrows(IllegalArgumentException.class, () -> prepare(action(URI, VERSION, List.of(edit(2, 8, 15, huge)))));
        assertThrows(IllegalArgumentException.class, () -> GoSaveImportEdits.prepare(URI, null, VERSION, response(action())));
    }
}

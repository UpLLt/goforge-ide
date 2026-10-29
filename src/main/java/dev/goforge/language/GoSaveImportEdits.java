package dev.goforge.language;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.AnnotatedTextEdit;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/** Accept only the pinned gopls direct, versioned, single-document import edit. */
final class GoSaveImportEdits {
    private GoSaveImportEdits() { }

    static List<GoSaveFormattingEdits.Change> prepare(String uri, String text, int expectedVersion,
                                                     List<Either<Command, CodeAction>> actions) {
        if (actions == null || actions.isEmpty()) return List.of();
        if (actions.size() != 1) throw invalid("Expected one unambiguous Organize Imports action");
        Object response = actions.get(0);
        if (!(response instanceof Either<?, ?> either) || !either.isRight()
                || !(either.getRight() instanceof CodeAction action)
                || !CodeActionKind.SourceOrganizeImports.equals(action.getKind())
                || action.getDisabled() != null || action.getCommand() != null || action.getData() != null) {
            throw invalid("Organize Imports must be an enabled direct edit without commands or lazy data");
        }
        var workspace = action.getEdit();
        if (workspace == null || (workspace.getChanges() != null && !workspace.getChanges().isEmpty())
                || workspace.getChangeAnnotations() != null
                || workspace.getDocumentChanges() == null || workspace.getDocumentChanges().size() != 1) {
            throw invalid("Organize Imports requires one versioned document edit without annotations");
        }
        Object documentChange = workspace.getDocumentChanges().get(0);
        if (!(documentChange instanceof Either<?, ?> change) || !change.isLeft()
                || !(change.getLeft() instanceof TextDocumentEdit document)) {
            throw invalid("Organize Imports cannot perform resource operations");
        }
        var identifier = document.getTextDocument();
        if (identifier == null || identifier.getVersion() == null || identifier.getVersion() != expectedVersion
                || !localPath(uri).equals(localPath(identifier.getUri()))) {
            throw invalid("Organize Imports returned a different document or stale version");
        }
        if (text == null || document.getEdits() == null || document.getEdits().size() > 10000) {
            throw invalid("Organize Imports requires at most 10,000 text edits");
        }
        var edits = new ArrayList<TextEdit>(document.getEdits().size());
        for (Object item : document.getEdits()) {
            // Platform compile API uses TextEdit; pinned LSP4J 1.0 wire decoding
            // uses Either<TextEdit, SnippetTextEdit>. Never interpret snippets.
            if (item instanceof Either<?, ?> editChoice) {
                if (!editChoice.isLeft()) throw invalid("Organize Imports cannot use snippet edits");
                item = editChoice.getLeft();
            }
            if (!(item instanceof TextEdit edit) || edit instanceof AnnotatedTextEdit) {
                throw invalid("Organize Imports cannot use annotated or invalid text edits");
            }
            edits.add(edit);
        }
        return GoSaveFormattingEdits.prepare(text, edits);
    }

    private static Path localPath(String value) {
        try {
            var uri = URI.create(value);
            if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || (uri.getAuthority() != null && !uri.getAuthority().isEmpty())) {
                throw new IllegalArgumentException();
            }
            // Path equality preserves the host's Windows case semantics. Never follow aliases.
            return Path.of(uri).toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            throw invalid("Organize Imports requires a local file URI without authority, query or fragment");
        }
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}

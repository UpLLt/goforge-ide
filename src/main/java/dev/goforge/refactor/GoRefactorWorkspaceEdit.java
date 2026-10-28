package dev.goforge.refactor;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

/** Pure all-document validation. No file access or changes occur while a plan is prepared. */
public record GoRefactorWorkspaceEdit(List<FileEdit> files) {
    public static final int MAX_FILES = 2048;
    public static final int MAX_CHARACTERS = 32 * 1024 * 1024;
    public record Source(String uri, String text, Integer version) { }
    public record FileEdit(String uri, GoRefactorEdit edit, boolean created) { }
    public GoRefactorWorkspaceEdit { files = List.copyOf(files); }

    public static GoRefactorWorkspaceEdit prepare(Collection<Source> sources, WorkspaceEdit workspace) {
        if (workspace == null) throw new IllegalArgumentException("This refactoring did not provide previewable edits.");
        var available = new LinkedHashMap<Path, Source>();
        for (Source source : sources) if (available.putIfAbsent(uriPath(source.uri()), source) != null)
            throw new IllegalArgumentException("Two snapshot files refer to the same path.");
        boolean changes = workspace.getChanges() != null && !workspace.getChanges().isEmpty();
        boolean documents = workspace.getDocumentChanges() != null && !workspace.getDocumentChanges().isEmpty();
        if (changes && documents) throw new IllegalArgumentException("Ambiguous workspace edit formats.");
        var edits = new LinkedHashMap<Path, WorkspaceEdit>();
        var created = new LinkedHashMap<Path, String>();
        if (changes) for (var item : workspace.getChanges().entrySet()) {
            Path key = uriPath(item.getKey());
            if (edits.putIfAbsent(key, new WorkspaceEdit(Map.of(item.getKey(), item.getValue()))) != null)
                throw new IllegalArgumentException("Duplicate file URI aliases in the workspace edit.");
        }
        if (documents) for (var item : workspace.getDocumentChanges()) {
            if (item == null) throw new IllegalArgumentException("Missing workspace operation.");
            if (!item.isLeft()) {
                if (!(item.getRight() instanceof CreateFile file))
                    throw new IllegalArgumentException("File deletion and moves are not supported by this preview. No files were changed.");
                Path key=uriPath(file.getUri());
                if (key.getFileName()==null || !key.getFileName().toString().endsWith(".go") || available.containsKey(key) || edits.containsKey(key) || created.putIfAbsent(key,file.getUri())!=null)
                    throw new IllegalArgumentException("A new Go file must be unique and created before its text edits.");
                if(file.getAnnotationId()!=null || (file.getOptions()!=null && (Boolean.TRUE.equals(file.getOptions().getOverwrite()) || Boolean.TRUE.equals(file.getOptions().getIgnoreIfExists()))))
                    throw new IllegalArgumentException("CreateFile overwrite/ignore-existing/confirmation options are not supported.");
                continue;
            }
            var document = item.getLeft();
            if (document == null || document.getTextDocument() == null || document.getEdits() == null)
                throw new IllegalArgumentException("Incomplete text document edit.");
            Path key = uriPath(document.getTextDocument().getUri());
            if(!available.containsKey(key)&&!created.containsKey(key))throw new IllegalArgumentException("An uncaptured file must have an earlier CreateFile operation.");
            if (edits.putIfAbsent(key, new WorkspaceEdit(List.of(Either.forLeft(document)))) != null)
                throw new IllegalArgumentException("Repeated edits for one document are not supported; no files were changed.");
        }
        for(var file:created.entrySet())if(!edits.containsKey(file.getKey()))edits.put(file.getKey(),null);
        if (edits.isEmpty()) throw new IllegalArgumentException("The refactoring produced no edits.");
        if (edits.size() > MAX_FILES) throw new IllegalArgumentException("Refactoring exceeds the 2,048-file limit.");
        var result = new ArrayList<FileEdit>(); long size = 0; int count = 0;
        for (var item : edits.entrySet()) {
            boolean isCreated=created.containsKey(item.getKey());
            Source source = isCreated ? new Source(created.get(item.getKey()),"",0) : available.get(item.getKey());
            if (source == null) throw new IllegalArgumentException("A refactoring target is outside the captured project Go sources: " + item.getKey());
            // Normalize only URI spelling. The actual path/ownership is established by the snapshot.
            var normalized = item.getValue();
            if (normalized==null) { result.add(new FileEdit(source.uri(),new GoRefactorEdit("","",List.of()),true));continue; }
            if (normalized.getChanges() != null) normalized = new WorkspaceEdit(Map.of(source.uri(), normalized.getChanges().values().iterator().next()));
            else {
                var document = normalized.getDocumentChanges().getFirst().getLeft();
                var id = new VersionedTextDocumentIdentifier(source.uri(), document.getTextDocument().getVersion());
                normalized = new WorkspaceEdit(List.of(Either.forLeft(new TextDocumentEdit(id, document.getEdits()))));
            }
            GoRefactorEdit edit = GoRefactorEdit.prepare(source.uri(), source.text(), source.version(), normalized);
            size += edit.original().length() + edit.replacement().length(); count += edit.changes().size();
            if (size > MAX_CHARACTERS || count > 10000) throw new IllegalArgumentException("Refactoring exceeds the 32 MiB/10,000-edit preview limit.");
            result.add(new FileEdit(source.uri(), edit, isCreated));
        }
        return new GoRefactorWorkspaceEdit(result);
    }

    static Path uriPath(String value) { return Path.of(uriKey(value)); }

    public static String uriKey(String value) {
        try {
            URI uri = URI.create(value);
            if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getAuthority() != null && !uri.getAuthority().isEmpty())) throw new IllegalArgumentException();
            return Path.of(uri).toAbsolutePath().normalize().toString();
        } catch (RuntimeException failure) { throw new IllegalArgumentException("Refactoring requires a local file URI without query, fragment or remote authority."); }
    }
}

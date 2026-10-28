package dev.goforge.refactor;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;

public final class GoRefactorApply {
    private GoRefactorApply() { }
    static void apply(Project project, GoRefactorSnapshot snapshot, GoRefactorWorkspaceEdit plan, String title) {
        WriteCommandAction.writeCommandAction(project).withName("Go: " + title).withGlobalUndo()
                .withUndoConfirmationPolicy(com.intellij.openapi.command.UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION).run(() -> {
            snapshot.requireCurrent();
            snapshot.requireTargetDiskCurrent(plan);
            for (var file : plan.files()) if(!file.created()) {
                var target=snapshot.entry(file.uri());
                if (!target.file().isWritable() || !target.document().isWritable())
                    throw new IllegalStateException("A refactoring target is read-only: " + target.file().getName());
                if (!target.text().equals(file.edit().original())) throw new IllegalStateException("The preview baseline changed.");
            }
            var newContents=new java.util.LinkedHashMap<String,byte[]>();
            for(var file:plan.files())if(file.created())newContents.put(file.uri(),encodeNewFile(file.edit().replacement()));
            var touched=new java.util.LinkedHashMap<Document,String>();
            var created=new java.util.ArrayList<com.intellij.openapi.vfs.VirtualFile>();
            var targets=new java.util.LinkedHashMap<String,Document>();
            try {
                for(var file:plan.files()){
                    if(file.created()){
                        var target=snapshot.newFile(file.uri());
                        var virtualFile=target.parent().createChildData(GoRefactorApply.class,target.name());created.add(virtualFile);
                        // The file-creation undo action owns this entire new file.
                        // Initialize its final bytes through VFS, rather than adding
                        // a separate EditorChangeAction for a transient empty Document.
                        // Existing files continue to preserve their live Documents.
                        virtualFile.setCharset(java.nio.charset.StandardCharsets.UTF_8);
                        virtualFile.setBinaryContent(newContents.get(file.uri()));
                    }else targets.put(file.uri(),snapshot.entry(file.uri()).document());
                }
                for(var file:plan.files()){
                    if(file.created())continue;
                    var document=targets.get(file.uri());touched.put(document,document.getText());
                    var changes=file.edit().changes();
                    for(int i=changes.size()-1;i>=0;i--){var change=changes.get(i);document.replaceString(change.start(),change.end(),change.text());}
                }
            } catch (java.io.IOException | RuntimeException failure) {
                for(var original:touched.entrySet()){
                    try{if(!original.getKey().getText().equals(original.getValue()))original.getKey().setText(original.getValue());}
                    catch(RuntimeException rollback){failure.addSuppressed(rollback);}
                }
                for(int i=created.size()-1;i>=0;i--){
                    try{if(created.get(i).isValid())created.get(i).delete(GoRefactorApply.class);}
                    catch(java.io.IOException|RuntimeException rollback){failure.addSuppressed(rollback);}
                }
                throw new IllegalStateException(failure.getSuppressed().length==0 ? "The refactoring failed; its changes were rolled back. " + failure.getMessage() : "The refactoring failed and rollback was incomplete. Inspect affected files before continuing. " + failure.getMessage(),failure);
            }
        });
    }

    private static byte[] encodeNewFile(String text) {
        try {
            var buffer=java.nio.charset.StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(text));
            var bytes=new byte[buffer.remaining()];buffer.get(bytes);return bytes;
        } catch(java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException("The new Go file contains invalid Unicode; no files were changed.",invalid);
        }
    }
    public static void apply(Project project, Document document, long stamp, GoRefactorEdit edit, String title) {
        WriteCommandAction.writeCommandAction(project).withName("Go: " + title).run(() -> {
            if (document.getModificationStamp() != stamp || !document.getText().equals(edit.original()))
                throw new IllegalStateException("The document changed after the preview. Request the refactoring again.");
            if (!document.isWritable()) throw new IllegalStateException("The document is read-only.");
            try {
                for (int i = edit.changes().size() - 1; i >= 0; i--) {
                    var change = edit.changes().get(i);
                    document.replaceString(change.start(), change.end(), change.text());
                }
            } catch (RuntimeException failure) {
                document.setText(edit.original());
                throw failure;
            }
        });
    }
}

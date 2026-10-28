package dev.goforge.lsp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandEvent;
import com.intellij.openapi.command.CommandListener;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.UndoConfirmationPolicy;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Real disk, Documents, command events and Undo; no IDE window or language server. */
@SuppressWarnings("deprecation")
public final class GoClosedDocumentSynchronizerTest extends BasePlatformTestCase {
    private static final String ORIGINAL = "package greeting\n\nvar Alpha = 1\nvar Beta = 2\n";
    private static final String CHANGED = ORIGINAL.replace("Alpha", "Welcome").replace("Beta", "Hello");

    public void testClosedWorkspaceEditBatchSavesOnlyAfterCommandFinishes() throws Exception {
        runScenario(fixture -> {
            var first = fixture.file("first.go", true);
            var second = fixture.file("second.go", true);
            // Zero actual Editors is also the service's restored/lazy-tab eligibility
            // condition. This test does not pretend to restore a native IDE tab.
            assertEquals(0, EditorFactory.getInstance().getEditors(first.document).length);
            WriteCommandAction.writeCommandAction(getProject()).withName("Workspace rename").run(() -> {
                LSPIJUtils.applyWorkspaceEdit(workspaceEdit(first));
                assertEquals(CHANGED, first.document.getText());
                assertEquals("No partial batch may reach disk", ORIGINAL, first.diskText());
                LSPIJUtils.applyWorkspaceEdit(workspaceEdit(second));
                assertEquals(ORIGINAL, first.diskText());
                assertEquals(ORIGINAL, second.diskText());
                assertTrue(manager().isDocumentUnsaved(first.document));
                assertTrue(manager().isDocumentUnsaved(second.document));
            });
            assertSaved(first, CHANGED);
            assertSaved(second, CHANGED);
        });
    }

    public void testActualEditorKeepsBufferUnsaved() throws Exception {
        runScenario(fixture -> {
            var source = fixture.file("edited.go", true);
            var editor = EditorFactory.getInstance().createEditor(source.document, getProject(),
                    PlainTextFileType.INSTANCE, false);
            try {
                commandChange(source);
                assertUnsaved(source, CHANGED, ORIGINAL);
            } finally {
                EditorFactory.getInstance().releaseEditor(editor);
            }
        });
    }

    public void testEditorWithoutProjectAlsoPreventsSave() throws Exception {
        runScenario(fixture -> {
            var source = fixture.file("shared-editor.go", true);
            // This editor is absent from a query restricted to the service's project.
            var editor = EditorFactory.getInstance().createEditor(source.document);
            try {
                assertEquals(0, EditorFactory.getInstance().getEditors(source.document, getProject()).length);
                commandChange(source);
                assertUnsaved(source, CHANGED, ORIGINAL);
            } finally {
                EditorFactory.getInstance().releaseEditor(editor);
            }
        });
    }

    public void testOriginallyDirtyClosedDocumentRemainsUnsaved() throws Exception {
        runScenario(fixture -> {
            var source = fixture.file("already-dirty.go", true);
            CommandProcessor.getInstance().runUndoTransparentAction(() ->
                    WriteAction.run(() -> source.document.insertString(0, "// existing user edit\n")));
            assertTrue(manager().isDocumentUnsaved(source.document));
            WriteCommandAction.runWriteCommandAction(getProject(), () ->
                    source.document.setText("// existing user edit\n" + CHANGED));
            assertUnsaved(source, "// existing user edit\n" + CHANGED, ORIGINAL);
        });
    }

    public void testOutsideProjectAndNonGoDocumentsRemainUnsaved() throws Exception {
        runScenario(fixture -> {
            var external = fixture.file("external.go", false);
            var text = fixture.file("notes.txt", true);
            assertFalse(ProjectFileIndex.getInstance(getProject()).isInContent(external.file));
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                external.document.setText(CHANGED);
                text.document.setText(CHANGED);
            });
            assertUnsaved(external, CHANGED, ORIGINAL);
            assertUnsaved(text, CHANGED, ORIGINAL);
        });
    }

    public void testCommandWithoutOwningProjectDoesNotSave() throws Exception {
        runScenario(fixture -> {
            var source = fixture.file("no-project.go", true);
            CommandProcessor.getInstance().executeCommand(null,
                    () -> WriteAction.run(() -> source.document.setText(CHANGED)),
                    "Command without project", new Object());
            assertUnsaved(source, CHANGED, ORIGINAL);
        });
    }

    public void testEditOutsideCommandDoesNotSave() throws Exception {
        runScenario(fixture -> {
            var source = fixture.file("no-command.go", true);
            assertNull(CommandProcessor.getInstance().getCurrentCommand());
            CommandProcessor.getInstance().runUndoTransparentAction(() ->
                    WriteAction.run(() -> source.document.setText(CHANGED)));
            assertUnsaved(source, CHANGED, ORIGINAL);
        });
    }

    public void testEditorCreatedBeforeCommandCompletionPreventsSave() throws Exception {
        runScenario(fixture -> {
            var source = fixture.file("opened-during-command.go", true);
            Editor[] editor = new Editor[1];
            try {
                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    source.document.setText(CHANGED);
                    editor[0] = EditorFactory.getInstance().createEditor(source.document, getProject(),
                            PlainTextFileType.INSTANCE, false);
                });
                assertUnsaved(source, CHANGED, ORIGINAL);
            } finally {
                if (editor[0] != null) EditorFactory.getInstance().releaseEditor(editor[0]);
            }
        });
    }

    public void testUndoRestoresClosedDocumentAndDisk() throws Exception {
        runScenario(fixture -> {
            var source = fixture.file("undo.go", true);
            var undo = UndoManager.getInstance(getProject());
            WriteCommandAction.writeCommandAction(getProject()).withName("Rename closed Go symbols")
                    .withGlobalUndo().withUndoConfirmationPolicy(UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION)
                    .run(() -> LSPIJUtils.applyWorkspaceEdit(workspaceEdit(source)));
            assertSaved(source, CHANGED);
            assertEquals(0, EditorFactory.getInstance().getEditors(source.document).length);
            assertTrue("The real global command must be undoable without materializing an editor",
                    undo.isUndoAvailable(null));
            undo.undo(null);
            assertSaved(source, ORIGINAL);
        });
    }

    public void testStandardCommandFinishedHasReleasedWriteLock() throws Exception {
        // This is a platform precondition for the modal connection barrier, not
        // proof of a language-server connection or of native modal UI behavior.
        runScenario(fixture -> {
            var source = fixture.file("command-lock.go", true);
            var finished = new AtomicBoolean();
            ApplicationManager.getApplication().getMessageBus().connect(getTestRootDisposable())
                    .subscribe(CommandListener.TOPIC, new CommandListener() {
                        @Override public void commandFinished(CommandEvent event) {
                            if (!"Verify modal barrier entry".equals(event.getCommandName())) return;
                            var app = ApplicationManager.getApplication();
                            assertTrue(app.isDispatchThread());
                            assertTrue(app.isWriteIntentLockAcquired());
                            assertFalse("Waiting for asynchronous didOpen under a write lock would deadlock",
                                    app.isWriteAccessAllowed());
                            finished.set(true);
                        }
                    });
            WriteCommandAction.writeCommandAction(getProject()).withName("Verify modal barrier entry")
                    .run(() -> source.document.setText(CHANGED));
            assertTrue(finished.get());
        });
    }

    public void testRootExclusionDuringCommandPreservesUnsavedBuffer() throws Exception {
        runScenario(fixture -> {
            var source = fixture.file("excluded/changed.go", true);
            var roots = ProjectRootModificationTracker.getInstance(getProject());
            long initialRootsVersion = roots.getModificationCount();
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                source.document.setText(CHANGED);
                PsiTestUtil.addExcludedRoot(getModule(), source.file.getParent());
                assertTrue(ProjectFileIndex.getInstance(getProject()).isExcluded(source.file));
                assertTrue("Excluding the file must invalidate the ownership snapshot",
                        roots.getModificationCount() != initialRootsVersion);
            });
            assertUnsaved(source, CHANGED, ORIGINAL);
        });
    }

    public void testPathChangeDuringCommandPreservesUnsavedBuffer() throws Exception {
        runScenario(fixture -> {
            var source = fixture.file("before-rename.go", true);
            String oldPath = source.file.getPath();
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                source.document.setText(CHANGED);
                try {
                    source.file.rename(this, "after-rename.go");
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
                assertFalse(oldPath.equals(source.file.getPath()));
            });
            assertEquals(CHANGED, source.document.getText());
            assertTrue(manager().isDocumentUnsaved(source.document));
            try {
                assertEquals("A changed path must not inherit the pre-command save decision",
                        ORIGINAL, Files.readString(source.file.toNioPath()));
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        });
    }

    private WorkspaceEdit workspaceEdit(Source source) {
        var edit = new WorkspaceEdit();
        edit.setChanges(Map.of(source.path.toUri().toString(), List.of(
                new TextEdit(new Range(new Position(2, 4), new Position(2, 9)), "Welcome"),
                new TextEdit(new Range(new Position(3, 4), new Position(3, 8)), "Hello"))));
        return edit;
    }

    private void commandChange(Source source) {
        WriteCommandAction.runWriteCommandAction(getProject(), () -> source.document.setText(CHANGED));
    }

    private FileDocumentManager manager() { return FileDocumentManager.getInstance(); }

    private void assertSaved(Source source, String expected) {
        assertEquals(expected, source.document.getText());
        assertEquals(expected, source.diskText());
        assertFalse(manager().isDocumentUnsaved(source.document));
    }

    private void assertUnsaved(Source source, String buffer, String disk) {
        assertEquals(buffer, source.document.getText());
        assertEquals(disk, source.diskText());
        assertTrue(manager().isDocumentUnsaved(source.document));
    }

    private void runScenario(Consumer<PhysicalFixture> scenario) throws Exception {
        EdtTestUtil.runInEdtAndWait(() -> {
            var fixture = new PhysicalFixture();
            try {
                GoClosedDocumentSynchronizer.getInstance(getProject());
                assertTrue(LanguageServiceAccessor.getInstance(getProject()).getStartedServers().isEmpty());
                scenario.accept(fixture);
                assertTrue("These tests must not launch gopls",
                        LanguageServiceAccessor.getInstance(getProject()).getStartedServers().isEmpty());
            } finally {
                fixture.close();
            }
        });
    }

    private final class PhysicalFixture {
        private final Path directory;
        private final VirtualFile contentRoot;
        private final List<Source> sources = new ArrayList<>();

        PhysicalFixture() {
            try {
                directory = FileUtil.createTempDirectory("goforge-closed-sync-", null, true).toPath();
                Path content = Files.createDirectory(directory.resolve("content"));
                contentRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(content);
                assertNotNull(contentRoot);
                WriteAction.run(() -> PsiTestUtil.addContentRoot(getModule(), contentRoot));
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        Source file(String name, boolean insideContent) {
            try {
                var path = (insideContent ? directory.resolve("content") : directory).resolve(name);
                Files.createDirectories(path.getParent());
                Files.writeString(path, ORIGINAL);
                var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
                assertNotNull(file);
                var document = manager().getDocument(file);
                assertNotNull(document);
                assertFalse(manager().isDocumentUnsaved(document));
                assertEquals(0, EditorFactory.getInstance().getEditors(document).length);
                assertEquals(insideContent, ProjectFileIndex.getInstance(getProject()).isInContent(file));
                var source = new Source(path, file, document);
                sources.add(source);
                return source;
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        void close() {
            try {
                for (var source : sources) manager().reloadFromDisk(source.document);
            } finally {
                WriteAction.run(() -> PsiTestUtil.removeContentEntry(getModule(), contentRoot));
                FileUtil.delete(directory.toFile());
            }
        }
    }

    private record Source(Path path, VirtualFile file, Document document) {
        String diskText() {
            try { return Files.readString(path); }
            catch (IOException error) { throw new UncheckedIOException(error); }
        }
    }
}

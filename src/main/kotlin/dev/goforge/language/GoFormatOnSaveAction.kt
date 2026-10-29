package dev.goforge.language

import com.intellij.codeInsight.actions.VcsFacade
import com.intellij.codeInsight.actions.onSave.FormatOnSaveOptions
import com.intellij.codeInsight.actions.onSave.OptimizeImportsOnSaveOptions
import com.intellij.formatting.service.FormattingNotificationService
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener.DocumentUpdatingActionOnSave
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.execute
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.application.options.CodeStyle
import com.redhat.devtools.lsp4ij.DocumentContentSynchronizer
import com.redhat.devtools.lsp4ij.LanguageServerWrapper
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor
import com.redhat.devtools.lsp4ij.ServerStatus
import dev.goforge.lsp.GoplsClientFeatures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One awaited Go save coordinator. Imports precede formatting and its refreshed overlay.
 * The platform owns the final save, cancellation and document lifetime. */
class GoFormatOnSaveAction : DocumentUpdatingActionOnSave() {
    override val presentableName: String = "Format and optimize Go imports"
    private enum class Operation { IMPORTS, FORMAT }

    override fun isEnabledForProject(project: Project): Boolean = !project.isDisposed &&
        (FormatOnSaveOptions.getInstance(project).isRunOnSaveEnabled ||
            OptimizeImportsOnSaveOptions.getInstance(project).isRunOnSaveEnabled)

    override suspend fun updateDocument(project: Project, document: Document) {
        var phase = "Go import optimization"
        var importsApplied = false
        try {
            withTimeout(15_000) {
                // The two awaited phases belong to one Save. A unique group cannot merge
                // separate saves or unrelated typing while a response is pending.
                val undoGroup = "goforge-save-" + java.util.UUID.randomUUID()
                val formatWasSelected = readAction {
                    FileDocumentManager.getInstance().getFile(document)?.let { selected(project, it, Operation.FORMAT) } == true
                }
                val imports = readAction { prepare(project, document, Operation.IMPORTS) }
                var continuation: Snapshot? = null
                if (imports != null) {
                    val (updated, changed) = organizeImports(project, document, imports, undoGroup)
                    continuation = updated
                    importsApplied = changed
                }
                currentCoroutineContext().ensureActive()
                phase = "Go formatting"
                // A fresh stamp/text is needed after our import edits. Never accept intervening typing
                // as a new save request or silently switch to another server after imports.
                val formatting = readAction {
                    continuation?.requireDocumentCurrent(project, document)
                    if (formatWasSelected) prepare(project, document, Operation.FORMAT, continuation) else null
                }
                if (formatting != null) format(project, document, formatting, undoGroup)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (!project.isDisposed) {
                val cause = if (error is CompletionException) error.cause ?: error else error
                val applied = if (importsApplied) " Imports were already updated." else ""
                FormattingNotificationService.getInstance(project).reportError(
                    "GoForge", "$phase was not applied",
                    (cause.message?.take(1000) ?: cause.javaClass.simpleName) + applied)
            }
        }
    }

    private suspend fun organizeImports(project: Project, document: Document, snapshot: Snapshot, undoGroup: String): Pair<Snapshot, Boolean> {
        snapshot.synchronizer.flushPendingChanges().awaitResponse(cancelFuture = false)
        val version = readAction {
            snapshot.requireCurrent(project, document)
            snapshot.sentVersion() ?: throw IllegalStateException("The Go buffer version is not synchronized")
        }
        currentCoroutineContext().ensureActive()
        val context = CodeActionContext(emptyList())
        context.only = listOf(CodeActionKind.SourceOrganizeImports)
        val end = Position(snapshot.text.count { it == '\n' }, snapshot.text.substringAfterLast('\n').length)
        val params = CodeActionParams(TextDocumentIdentifier(snapshot.uri), Range(Position(0, 0), end), context)
        val actions = snapshot.server.textDocumentService.codeAction(params).awaitResponse(cancelFuture = true)
        // No codeAction/resolve or executeCommand: pinned gopls supplies direct versioned edits.
        val changes = GoSaveImportEdits.prepare(snapshot.uri, snapshot.text, version, actions)
        if (changes.isEmpty()) return readAction {
            snapshot.requireCurrent(project, document)
            if (snapshot.sentVersion() != version) throw CancellationException("The Go overlay version changed")
            snapshot to false
        }
        return WriteCommandAction.writeCommandAction(project).withName("Optimize Go Imports on Save").withGroupId(undoGroup).execute {
            snapshot.requireCurrent(project, document)
            if (snapshot.sentVersion() != version) throw CancellationException("The Go overlay version changed")
            applyChanges(document, changes)
            snapshot.copy(stamp = document.modificationStamp, text = document.text) to changes.isNotEmpty()
        }
    }

    private suspend fun format(project: Project, document: Document, snapshot: Snapshot, undoGroup: String) {
        snapshot.synchronizer.flushPendingChanges().awaitResponse(cancelFuture = false)
        readAction { snapshot.requireCurrent(project, document) }
        currentCoroutineContext().ensureActive()
        val params = DocumentFormattingParams(TextDocumentIdentifier(snapshot.uri), snapshot.formattingOptions)
        val edits = snapshot.server.textDocumentService.formatting(params).awaitResponse(cancelFuture = true)
        if (edits.isNullOrEmpty()) return
        val changes = GoSaveFormattingEdits.prepare(snapshot.text, edits)
        if (changes.isEmpty()) return
        WriteCommandAction.writeCommandAction(project).withName("Format Go on Save").withGroupId(undoGroup).execute {
            snapshot.requireCurrent(project, document)
            applyChanges(document, changes)
        }
    }

    private fun applyChanges(document: Document, changes: List<GoSaveFormattingEdits.Change>) {
        for (change in changes.asReversed()) document.replaceString(change.start(), change.end(), change.text())
    }

    private fun prepare(project: Project, document: Document, operation: Operation, connection: Snapshot? = null): Snapshot? {
        if (!isEnabledForProject(project)) return null
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        if (!file.isValid || !file.isWritable || !file.isInLocalFileSystem || file.fileType !== GoFileType.INSTANCE) return null
        if (!selected(project, file, operation)) return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val style = CodeStyle.getSettings(psi)
        if (style.excludedFiles.contains(psi) || document.textLength > 8 * 1024 * 1024) return null
        var selected: LanguageServerWrapper? = null
        LanguageServiceAccessor.getInstance(project).processLanguageServers(psi) { wrapper ->
            if (wrapper.isDisposed || wrapper.serverStatus != ServerStatus.started ||
                connection != null && wrapper !== connection.wrapper) return@processLanguageServers
            if (operation == Operation.IMPORTS) {
                val feature = wrapper.clientFeatures.codeActionFeature
                if (wrapper.clientFeatures is GoplsClientFeatures && feature.isEnabled(psi) && feature.isCodeActionSupported(psi))
                    selected = wrapper
            } else {
                val feature = wrapper.clientFeatures.formattingFeature
                if (feature.isEnabled(psi) && feature.isFormattingSupported(psi) &&
                    (selected == null || feature.isExistingFormatterOverrideable(psi))) selected = wrapper
            }
        }
        val wrapper = selected ?: return null
        val server = wrapper.languageServer ?: return null
        val synchronizer = wrapper.getOpenedDocument(wrapper.toUri(file))?.synchronizer ?: return null
        if (connection != null && (server !== connection.server || synchronizer !== connection.synchronizer ||
                wrapper.toUriString(file) != connection.uri))
            throw CancellationException("The Go language server connection changed between save operations")
        if (synchronizer.document !== document || !synchronizer.didOpenFuture.isDone ||
            synchronizer.didOpenFuture.isCompletedExceptionally) return null
        val indent = style.getIndentOptionsByFile(psi)
        return Snapshot(file, wrapper, server, synchronizer, document.modificationStamp, document.text,
            wrapper.toUriString(file), FormattingOptions(indent.TAB_SIZE, !indent.USE_TAB_CHARACTER), operation)
    }

    private data class Snapshot(
        val file: VirtualFile, val wrapper: LanguageServerWrapper, val server: LanguageServer,
        val synchronizer: DocumentContentSynchronizer, val stamp: Long, val text: String,
        val uri: String, val formattingOptions: FormattingOptions, val operation: Operation
    ) {
        fun sentVersion(): Int? = (wrapper.clientFeatures as? GoplsClientFeatures)?.getSentDocumentVersion(uri)
        fun requireDocumentCurrent(project: Project, document: Document) {
            if (project.isDisposed || !file.isValid || !file.isWritable || !file.isInLocalFileSystem ||
                file.fileType !== GoFileType.INSTANCE || wrapper.toUriString(file) != uri ||
                FileDocumentManager.getInstance().getFile(document) != file || document.modificationStamp != stamp ||
                wrapper.isDisposed || wrapper.serverStatus != ServerStatus.started || wrapper.languageServer !== server ||
                wrapper.getOpenedDocument(wrapper.toUri(file))?.synchronizer !== synchronizer)
                throw CancellationException("The Go document or language server changed")
        }
        fun requireCurrent(project: Project, document: Document) {
            requireDocumentCurrent(project, document)
            if (!selected(project, file, operation)) throw CancellationException("The Go save preferences changed")
            val psi = PsiManager.getInstance(project).findFile(file) ?: throw CancellationException("The Go file is no longer available")
            val enabled = if (operation == Operation.IMPORTS) {
                val feature = wrapper.clientFeatures.codeActionFeature
                wrapper.clientFeatures is GoplsClientFeatures && feature.isEnabled(psi) && feature.isCodeActionSupported(psi)
            } else {
                val feature = wrapper.clientFeatures.formattingFeature
                feature.isEnabled(psi) && feature.isFormattingSupported(psi)
            }
            if (!enabled || CodeStyle.getSettings(psi).excludedFiles.contains(psi))
                throw CancellationException("Go save transformations were disabled for this file")
        }
    }

    private companion object {
        fun selected(project: Project, file: VirtualFile, operation: Operation): Boolean = when (operation) {
            Operation.IMPORTS -> OptimizeImportsOnSaveOptions.getInstance(project).let {
                it.isRunOnSaveEnabled && it.isFileTypeSelected(file.fileType)
            }
            Operation.FORMAT -> FormatOnSaveOptions.getInstance(project).let {
                it.isRunOnSaveEnabled && it.isFileTypeSelected(file.fileType) &&
                    !(it.isFormatOnlyChangedLines && VcsFacade.getInstance().hasActiveVcss(project))
            }
        }
    }

    private suspend fun <T> CompletableFuture<T>.awaitResponse(cancelFuture: Boolean): T =
        suspendCancellableCoroutine { continuation ->
            if (cancelFuture) continuation.invokeOnCancellation { cancel(true) }
            whenComplete { value, error ->
                if (error == null) continuation.resume(value) else continuation.resumeWithException(error)
            }
        }
}

package dev.goforge.language

import com.intellij.codeInsight.actions.VcsFacade
import com.intellij.codeInsight.actions.onSave.FormatOnSaveOptions
import com.intellij.formatting.service.FormattingNotificationService
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener.DocumentUpdatingActionOnSave
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.writeCommandAction
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The platform awaits this coroutine and saves the document only after it completes.
 * No editor, blocking RPC, detached callback, or additional save is needed. */
class GoFormatOnSaveAction : DocumentUpdatingActionOnSave() {
    override val presentableName: String = "Format Go"

    override fun isEnabledForProject(project: Project): Boolean =
        !project.isDisposed && FormatOnSaveOptions.getInstance(project).isRunOnSaveEnabled

    override suspend fun updateDocument(project: Project, document: Document) {
        val snapshot = readAction { prepare(project, document) } ?: return
        try {
            val edits = withTimeout(15_000) {
                // Await a dispatch barrier, but never cancel a shared synchronization future.
                snapshot.synchronizer.flushPendingChanges().awaitResponse(cancelFuture = false)
                readAction { snapshot.requireCurrent(project, document) }
                currentCoroutineContext().ensureActive()
                snapshot.server.textDocumentService.formatting(snapshot.params).awaitResponse(cancelFuture = true)
            }
            if (edits.isNullOrEmpty()) return
            val changes = GoSaveFormattingEdits.prepare(snapshot.text, edits)
            if (changes.isEmpty()) return
            writeCommandAction(project, "Format Go on Save") {
                snapshot.requireCurrent(project, document)
                // Preserve caret/range markers by applying the validated LSP edits, not setText.
                for (change in changes.asReversed()) {
                    document.replaceString(change.start(), change.end(), change.text())
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Formatting failures leave the saved input intact. The platform owns saving,
            // cancellation, and project lifetime; no late callback can apply this response.
            currentCoroutineContext().ensureActive()
            if (!project.isDisposed) {
                val cause = if (error is CompletionException) error.cause ?: error else error
                FormattingNotificationService.getInstance(project).reportError(
                    "GoForge", "Go formatting was not applied", cause.message?.take(1000) ?: cause.javaClass.simpleName)
            }
        }
    }

    private fun prepare(project: Project, document: Document): Snapshot? {
        if (!isEnabledForProject(project)) return null
        val manager = FileDocumentManager.getInstance()
        val file = manager.getFile(document) ?: return null
        if (!file.isValid || !file.isWritable || !file.isInLocalFileSystem || file.fileType !== GoFileType.INSTANCE) return null
        val options = FormatOnSaveOptions.getInstance(project)
        if (!options.isFileTypeSelected(file.fileType)) return null
        // gopls document formatting cannot honor a VCS-changed-lines-only request.
        if (options.isFormatOnlyChangedLines && VcsFacade.getInstance().hasActiveVcss(project)) return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val style = CodeStyle.getSettings(psi)
        if (style.excludedFiles.contains(psi)) return null
        if (document.textLength > 8 * 1024 * 1024) return null
        var selected: LanguageServerWrapper? = null
        LanguageServiceAccessor.getInstance(project).processLanguageServers(psi) { wrapper ->
            if (wrapper.isDisposed || wrapper.serverStatus != ServerStatus.started) return@processLanguageServers
            val feature = wrapper.clientFeatures.formattingFeature
            if (feature.isEnabled(psi) && feature.isFormattingSupported(psi) &&
                (selected == null || feature.isExistingFormatterOverrideable(psi))) selected = wrapper
        }
        val wrapper = selected ?: return null
        val server = wrapper.languageServer ?: return null
        val synchronizer = wrapper.getOpenedDocument(wrapper.toUri(file))?.synchronizer ?: return null
        if (synchronizer.document !== document || !synchronizer.didOpenFuture.isDone ||
            synchronizer.didOpenFuture.isCompletedExceptionally) return null
        // A closed editor still has code style settings, even though there is no Editor.
        val indent = style.getIndentOptionsByFile(psi)
        val formattingOptions = FormattingOptions(indent.TAB_SIZE, !indent.USE_TAB_CHARACTER)
        val params = DocumentFormattingParams(TextDocumentIdentifier(wrapper.toUriString(file)), formattingOptions)
        return Snapshot(file, wrapper, server, synchronizer, document.modificationStamp, document.text, params)
    }

    private data class Snapshot(
        val file: VirtualFile, val wrapper: LanguageServerWrapper, val server: LanguageServer,
        val synchronizer: DocumentContentSynchronizer, val stamp: Long, val text: String,
        val params: DocumentFormattingParams
    ) {
        fun requireCurrent(project: Project, document: Document) {
            val options = FormatOnSaveOptions.getInstance(project)
            if (project.isDisposed || !file.isValid || !file.isWritable ||
                FileDocumentManager.getInstance().getFile(document) != file || document.modificationStamp != stamp ||
                wrapper.isDisposed || wrapper.serverStatus != ServerStatus.started || wrapper.languageServer !== server ||
                wrapper.getOpenedDocument(wrapper.toUri(file))?.synchronizer !== synchronizer ||
                !options.isRunOnSaveEnabled || !options.isFileTypeSelected(file.fileType) ||
                (options.isFormatOnlyChangedLines && VcsFacade.getInstance().hasActiveVcss(project))) {
                throw CancellationException("The Go document, server or save preferences changed")
            }
            val psi = PsiManager.getInstance(project).findFile(file)
                ?: throw CancellationException("The Go file is no longer available")
            val feature = wrapper.clientFeatures.formattingFeature
            if (!feature.isEnabled(psi) || !feature.isFormattingSupported(psi) || CodeStyle.getSettings(psi).excludedFiles.contains(psi)) {
                throw CancellationException("Go formatting was disabled for this file")
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

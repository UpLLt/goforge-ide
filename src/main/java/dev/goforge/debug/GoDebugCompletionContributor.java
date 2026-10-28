package dev.goforge.debug;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Delve fallback for adapters without DAP CompletionsRequest support. */
public final class GoDebugCompletionContributor extends CompletionContributor implements DumbAware {
    @Override public void fillCompletionVariants(@NotNull CompletionParameters parameters,
                                                  @NotNull CompletionResultSet result) {
        if (!(parameters.getOriginalFile() instanceof GoDebugExpressionFragment fragment)) return;
        result.stopHere();
        var context = fragment.completionContext();
        if (context == null || !context.current().getAsBoolean()) return;
        var document = parameters.getEditor().getDocument();
        int offset = Math.min(parameters.getOffset(), document.getTextLength());
        var query = GoDebugCompletionEngine.query(document.getCharsSequence().subSequence(0, offset).toString());
        if (query == null) return;
        java.util.concurrent.CompletableFuture<java.util.List<GoDebugCompletionEngine.Candidate>> future;
        try {
            future = GoDebugCompletionEngine.complete(context, query);
        } catch (CancellationException staleFrame) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            while (true) {
                ProgressManager.checkCanceled();
                if (!context.current().getAsBoolean()) return;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return;
                try {
                    var candidates = future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(40)), TimeUnit.NANOSECONDS);
                    if (!context.current().getAsBoolean()) return;
                    var matches = result.withPrefixMatcher(query.prefix());
                    for (var candidate : candidates) {
                        var item = LookupElementBuilder.create(candidate.name()).withCaseSensitivity(true);
                        if (candidate.type() != null) item = item.withTypeText(candidate.type(), true);
                        if (candidate.value() != null) {
                            String value = candidate.value().replace('\n', ' ');
                            item = item.withTailText(" = " + (value.length() > 100 ? value.substring(0, 100) + "…" : value), true);
                        }
                        matches.addElement(item);
                    }
                    return;
                } catch (TimeoutException pending) { /* Check cancellation and frame identity again. */ }
            }
        } catch (CancellationException ignored) {
            // A step/resume, frame change or editor cancellation invalidates this completion.
        } catch (ExecutionException ignored) {
            // Adapter errors do not become executable fallback expressions.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }
}

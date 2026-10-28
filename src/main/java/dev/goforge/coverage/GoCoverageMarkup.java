package dev.goforge.coverage;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.*;

/** Owns only this project's current-session coverage highlights and listeners. */
final class GoCoverageMarkup implements Disposable {
    private final Project project;
    private final Map<Document, List<RangeHighlighter>> markers = new IdentityHashMap<>();
    private final Set<Document> watched = Collections.newSetFromMap(new IdentityHashMap<>());
    private Disposable listeners = Disposer.newDisposable("GoForge coverage source listeners");

    GoCoverageMarkup(Project project) { this.project = project; }

    boolean show(Document document, GoCoverageRun.FileResult file) {
        remove(document);
        if (file.source() == null || !file.unchangedOnDisk() || !file.source().matches(document.getText())) return false;
        // Navigation and numeric results remain available for very large files.
        if (file.coverage().blocks().size() > 5_000) return false;
        var model = DocumentMarkupModel.forDocument(document, project, true);
        var added = new ArrayList<RangeHighlighter>();
        markers.put(document, added);
        try {
            String text = document.getText();
            var ranges = GoCoverageOffsets.ranges(text, file.coverage().blocks());
            for (int index = 0; index < file.coverage().blocks().size(); index++) {
                var block = file.coverage().blocks().get(index);
                if (block.statements() == 0) continue;
                var range = ranges.get(index);
                if (range.start() == range.end()) continue;
                Color color = block.covered() ? new JBColor(new Color(0xe4f2e3), new Color(0x273c2b))
                        : new JBColor(new Color(0xfce2df), new Color(0x512e2c));
                var attributes = new TextAttributes(); attributes.setBackgroundColor(color);
                var marker = model.addRangeHighlighter(range.start(), range.end(), HighlighterLayer.ADDITIONAL_SYNTAX - 10,
                        attributes, HighlighterTargetArea.EXACT_RANGE);
                marker.setErrorStripeMarkColor(block.covered() ? new JBColor(0x388e3c, 0x66bb6a) : new JBColor(0xc62828, 0xef5350));
                marker.setErrorStripeTooltip("Go coverage: " + (block.covered() ? "executed" : "not executed")
                        + " (" + block.statements() + " statements)");
                added.add(marker);
            }
            if (watched.add(document)) document.addDocumentListener(new DocumentListener() {
                @Override public void beforeDocumentChange(@NotNull DocumentEvent event) { remove(event.getDocument()); }
            }, listeners);
            return true;
        } catch (IllegalArgumentException invalidRange) {
            remove(document);
            return false;
        }
    }

    private void remove(Document document) {
        var list = markers.remove(document);
        if (list != null) for (RangeHighlighter marker : list) if (marker.isValid()) marker.dispose();
    }
    int markerCount() { return markers.values().stream().mapToInt(List::size).sum(); }
    void clear() {
        for (Document document : List.copyOf(markers.keySet())) remove(document);
        Disposer.dispose(listeners);
        watched.clear();
        listeners = Disposer.newDisposable("GoForge coverage source listeners");
    }
    @Override public void dispose() {
        clear();
        Disposer.dispose(listeners);
    }
}

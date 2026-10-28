package dev.goforge.coverage;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.nio.file.Path;
import java.util.List;

public final class GoCoverageIntegrationTest extends BasePlatformTestCase {
    public void testFirstLayoutReservesReadableTableSpaceAndRetainsResizedProportion() {
        var panel = new GoCoveragePanel(GoCoverageService.getInstance(getProject()));
        var split = (com.intellij.ui.OnePixelSplitter) ((java.awt.BorderLayout) panel.getLayout())
                .getLayoutComponent(java.awt.BorderLayout.CENTER);
        try {
            for (int height : new int[]{420, 260}) {
                panel.setSize(900, height); panel.doLayout(); split.doLayout();
                int available = split.getHeight() - split.getDividerWidth();
                assertTrue("The first table row must have more than a header-height slot", split.getFirstComponent().getHeight() > 70);
                assertEquals(.55, (double) split.getFirstComponent().getHeight() / available, .02);
                assertTrue(split.getSecondComponent().getHeight() > 50);
            }
            split.setProportion(.30f);
            panel.setSize(900, 480); panel.doLayout(); split.doLayout();
            assertEquals(.30, (double) split.getFirstComponent().getHeight()
                    / (split.getHeight() - split.getDividerWidth()), .02);
        } finally { split.dispose(); }
    }
    public void testCancellationBeforePublicationDiscardsCompletedMeasurementButPreservesOutput() throws Exception {
        var ready = new GoCoverageRun.Result(GoCoverageRun.Status.PASSED, Path.of("/fixture"), 0,
                GoCoverageProfile.parse("mode: set\nx.go:1.1,1.2 1 1\n"), List.of(), "actual test output", "Tests passed");
        assertSame(ready, GoCoverageService.completionResult(ready, false));
        var cancelled = GoCoverageService.completionResult(ready, true);
        assertEquals(GoCoverageRun.Status.CANCELLED, cancelled.status());
        assertNull(cancelled.profile()); assertEmpty(cancelled.files());
        assertEquals("actual test output", cancelled.output());
    }
    public void testCoverageActionsExistWithoutAnyAccountContext() {
        assertInstanceOf(ActionManager.getInstance().getAction("GoForge.RunCoverage"), RunGoCoverageAction.class);
        assertInstanceOf(ActionManager.getInstance().getAction("GoForge.ClearCoverage"), ClearGoCoverageAction.class);
    }
    public void testClearAndDocumentEditsRemoveOnlyThisSessionCoverage() {
        var document = EditorFactory.getInstance().createDocument("package p\nfunc x() { return }\n");
        var model = DocumentMarkupModel.forDocument(document, getProject(), true);
        var unrelated = model.addRangeHighlighter(0, 1, 100, new TextAttributes(), HighlighterTargetArea.EXACT_RANGE);
        var block = new GoCoverageProfile.Block(2, 10, 2, 20, 1, 0);
        var file = new GoCoverageRun.FileResult(new GoCoverageProfile.FileCoverage("m/x.go", List.of(block)),
                new GoCoverageSourceMap.Source(Path.of("/fixture/x.go"), document.getText()), true, "");
        var markup = new GoCoverageMarkup(getProject());
        try {
            assertTrue(markup.show(document, file)); assertEquals(1, markup.markerCount());
            markup.clear(); assertEquals(0, markup.markerCount()); assertTrue(unrelated.isValid());
            assertTrue(markup.show(document, file));
            WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(0, "// changed\n"));
            assertEquals(0, markup.markerCount()); assertTrue(unrelated.isValid());
            assertFalse(markup.show(document, file));
        } finally { markup.dispose(); unrelated.dispose(); }
    }
}

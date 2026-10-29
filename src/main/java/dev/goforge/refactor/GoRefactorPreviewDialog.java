package dev.goforge.refactor;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import javax.swing.*;
import java.awt.*;

/** Every affected source has a complete before/after diff; Apply always uses the entire validated batch. */
final class GoRefactorPreviewDialog extends DialogWrapper {
    private final Project project;
    private final String title;
    private final GoRefactorSnapshot snapshot;
    private final GoRefactorWorkspaceEdit plan;
    GoRefactorPreviewDialog(Project project, String title, GoRefactorSnapshot snapshot, GoRefactorWorkspaceEdit plan) {
        super(project); this.project=project;this.title=title;this.snapshot=snapshot;this.plan=plan;
        setTitle("Go Refactor — " + title);setOKButtonText("Apply all " + plan.files().size() + " file(s)");init();
    }
    @Override protected JComponent createCenterPanel() {
        var panel=new JPanel(new BorderLayout(0,8));
        var top=new JPanel(new BorderLayout(8,4));
        top.add(new JLabel(title.startsWith("Add parameter")
                ? "All verified direct calls in the captured project are included. Apply uses one Undo command; dirty buffers remain unsaved."
                : "Review every affected file. Apply uses one Undo command; existing unsaved buffers remain unsaved."),BorderLayout.NORTH);
        var files=new JComboBox<String>();
        for(var item:plan.files())files.addItem(snapshot.displayName(item.uri())+(item.created()?" [new]":"")+" ("+item.edit().changes().size()+" edits)");
        files.getAccessibleContext().setAccessibleName("Refactoring changed files");top.add(files,BorderLayout.CENTER);panel.add(top,BorderLayout.NORTH);
        var diff=DiffManager.getInstance().createRequestPanel(project,getDisposable(),getWindow());
        Runnable show=()->{
            var item=plan.files().get(files.getSelectedIndex());var type=item.created()?dev.goforge.language.GoFileType.INSTANCE:snapshot.entry(item.uri()).file().getFileType();var content=DiffContentFactory.getInstance();
            diff.setRequest(new SimpleDiffRequest(title+" — "+snapshot.displayName(item.uri()),
                    content.create(project,item.edit().original(),type),
                    content.create(project,item.edit().replacement(),type),"Before","After"));
        };
        files.addActionListener(event->show.run());show.run();panel.add(diff.getComponent(),BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(1000,600));return panel;
    }
}

package dev.goforge.containers;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;
import javax.swing.*;
import java.awt.*;

final class DockerFieldsDialog extends DialogWrapper {
    private final String[] labels;
    private final JTextField[] fields;
    private final String explanation;
    DockerFieldsDialog(Project project, String title, String explanation, String... labelAndDefault) {
        super(project, true); setTitle(title); setOKButtonText(title);
        this.explanation = explanation;
        labels = new String[labelAndDefault.length / 2]; fields = new JTextField[labels.length];
        for (int i = 0; i < labels.length; i++) { labels[i] = labelAndDefault[i*2]; fields[i] = new JTextField(labelAndDefault[i*2+1], 40); }
        init();
    }
    @Override protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        for (int i = 0; i < fields.length; i++) {
            var c = new GridBagConstraints(); c.gridx=0; c.gridy=i; c.anchor=GridBagConstraints.WEST; c.insets=new Insets(4,0,4,10);
            JLabel label = new JLabel(labels[i]); label.setLabelFor(fields[i]); panel.add(label,c);
            c.gridx=1; c.fill=GridBagConstraints.HORIZONTAL; c.weightx=1; panel.add(fields[i],c);
        }
        var c = new GridBagConstraints(); c.gridx=0; c.gridy=fields.length; c.gridwidth=2; c.fill=GridBagConstraints.HORIZONTAL; c.insets=new Insets(10,0,0,0);
        JTextArea hint = new JTextArea(explanation); hint.setEditable(false); hint.setLineWrap(true); hint.setWrapStyleWord(true); hint.setOpaque(false); hint.setColumns(50); hint.setRows(3); panel.add(hint,c);
        return panel;
    }
    String value(int index) { return fields[index].getText().trim(); }
    @Override public @Nullable JComponent getPreferredFocusedComponent() { return fields[0]; }
}

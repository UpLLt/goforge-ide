package dev.goforge.refactor;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.Nullable;
import javax.swing.*;
import java.awt.*;

/** Explicit type migration; compiler validity is not a claim of unchanged runtime behavior. */
final class GoChangeScalarTypeDialog extends DialogWrapper {
    private final JComboBox<String> target = new JComboBox<>(new String[]{"Existing parameter", "Single result"});
    private final JTextField index = new JTextField("0", 20);
    private final JTextField type = new JTextField("int64", 20);
    GoChangeScalarTypeDialog(Project project) {
        super(project); setTitle("Change Go Scalar Type"); setOKButtonText("Compile and preview");
        target.addActionListener(e -> { index.setEnabled(target.getSelectedIndex() == 0); if (target.getSelectedIndex() == 1) index.setText("0"); });
        init();
    }
    @Override protected JComponent createCenterPanel() {
        var panel = new JPanel(new BorderLayout(0, 10)); var fields = new JPanel(new GridLayout(3, 2, 8, 6));
        fields.add(new JLabel("Change")); fields.add(target); fields.add(new JLabel("Parameter position (0 = first)")); fields.add(index);
        fields.add(new JLabel("New predeclared scalar type")); fields.add(type); panel.add(fields, BorderLayout.NORTH);
        panel.add(new JLabel("<html>Changes one existing scalar type in a free function; callers are checked, not rewritten.<br>"
                + "The captured module and tests must compile before and after. Project code is not executed.<br>"
                + "A type change can change behavior even when compilation succeeds.<br>"
                + "Requires one standalone module, default host build settings and cached pure-Go dependencies.<br>"
                + "Methods, function values, grouped parameters, multiple results, conditional builds,<br>"
                + "cgo/assembly, vendor trees, embedded resources and local replacements are refused. Review the exact change before Apply.</html>"), BorderLayout.CENTER);
        return panel;
    }
    GoChangeScalarType.Spec specification() {
        try { return new GoChangeScalarType.Spec(target.getSelectedIndex() == 1, Integer.parseInt(index.getText().strip()), type.getText()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Parameter position must be a nonnegative integer."); }
    }
    @Override protected @Nullable ValidationInfo doValidate() {
        try { specification(); return null; } catch (IllegalArgumentException ex) { return new ValidationInfo(ex.getMessage()); }
    }
}

package dev.goforge.refactor;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/** Input for the bounded direct-call operation; the worker performs AST validation. */
final class GoAddParameterDialog extends DialogWrapper {
    private final JTextField name = new JTextField("added", 24);
    private final JTextField type = new JTextField("int", 24);
    private final JTextField index = new JTextField("0", 24);
    private final JTextField value = new JTextField("0", 24);
    GoAddParameterDialog(Project project) {
        super(project); setTitle("Add One Go Parameter to Direct Calls"); setOKButtonText("Preview verified callers"); init();
    }
    @Override protected JComponent createCenterPanel() {
        var panel = new JPanel(new BorderLayout(0, 10));
        var fields = new JPanel(new GridLayout(4, 2, 8, 6));
        fields.add(new JLabel("New parameter name")); fields.add(name);
        fields.add(new JLabel("Predeclared scalar type")); fields.add(type);
        fields.add(new JLabel("Insert position (0 = first)")); fields.add(index);
        fields.add(new JLabel("Literal default at every call")); fields.add(value);
        panel.add(fields, BorderLayout.NORTH);
        panel.add(new JLabel("<html>Free functions with separately named parameters; generic functions are supported.<br>"
                + "Existing type parameters, constraints and explicit type arguments are kept.<br>"
                + "Existing argument order is preserved. Methods, variadics and function values are refused.<br>"
                + "Ordinary imports need a standalone module and an offline before/after compile check.<br>"
                + "Cached dependencies and default host settings are required; project code is not run.<br>"
                + "The check confirms type compatibility, not identical behavior. Shadowed scalar types are refused.<br>"
                + "Defaults: numeric/string/rune literals, signed numbers, true or false.<br>"
                + "All verified direct calls in the captured project are included in one preview.</html>"), BorderLayout.CENTER);
        return panel;
    }
    GoAddParameter.Spec specification() {
        final int at;
        try { at = Integer.parseInt(index.getText().strip()); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Insert position must be a nonnegative integer."); }
        return new GoAddParameter.Spec(name.getText(), type.getText(), at, value.getText());
    }
    @Override protected @Nullable ValidationInfo doValidate() {
        try { specification(); return null; }
        catch (IllegalArgumentException invalid) { return new ValidationInfo(invalid.getMessage()); }
    }
}

package dev.goforge.coverage;

import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.table.JBTable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/** Numeric results, uncovered-block navigation and unmodified command output. */
final class GoCoveragePanel extends JPanel {
    private final GoCoverageService service;
    private final JLabel summary = new JLabel("Run coverage for the current Go module's saved files.");
    private final JButton run = new JButton("Run Again"), cancel = new JButton("Cancel"), clear = new JButton("Clear");
    private final FilesModel model = new FilesModel();
    private final JBTable table = new JBTable(model);
    private final DefaultListModel<GoCoverageProfile.Block> uncovered = new DefaultListModel<>();
    private final JList<GoCoverageProfile.Block> blocks = new JList<>(uncovered);
    private final JTextArea output = new JTextArea();
    private GoCoverageRun.Result rendered;

    GoCoveragePanel(GoCoverageService service) {
        super(new BorderLayout(0, 6));
        this.service = service;
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 3));
        toolbar.add(run); toolbar.add(cancel); toolbar.add(clear);
        run.addActionListener(event -> service.runAgain());
        cancel.addActionListener(event -> service.cancel());
        clear.addActionListener(event -> service.clear());
        JPanel north = new JPanel(new BorderLayout()); north.add(toolbar, BorderLayout.NORTH); north.add(summary, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) return;
            uncovered.clear();
            var file = selected();
            if (file != null) file.coverage().blocks().stream().filter(block -> block.statements() > 0 && !block.covered()).forEach(uncovered::addElement);
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() != 2) return;
                var file = selected();
                if (file != null) service.navigate(file, uncovered.isEmpty() ? null : uncovered.firstElement());
            }
        });
        blocks.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                var label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof GoCoverageProfile.Block block) label.setText("Line " + block.startLine() + ":" + block.startColumn()
                        + " – " + block.endLine() + ":" + block.endColumn() + "   " + block.statements() + " unexecuted statements");
                return label;
            }
        });
        blocks.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && selected() != null && blocks.getSelectedValue() != null)
                    service.navigate(selected(), blocks.getSelectedValue());
            }
        });
        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, output.getFont().getSize()));
        JTabbedPane details = new JTabbedPane();
        details.addTab("Uncovered Blocks", new JScrollPane(blocks));
        details.addTab("Command Output", new JScrollPane(output));
        // resizeWeight alone never establishes a JSplitPane's initial divider.
        // This splitter applies the proportion on first layout and preserves drags.
        OnePixelSplitter split = new OnePixelSplitter(true, .55f, .1f, .9f);
        split.setHonorComponentsMinimumSize(false);
        split.setResizeEnabled(true);
        split.setFirstComponent(new JScrollPane(table));
        split.setSecondComponent(details);
        add(split, BorderLayout.CENTER);
        add(new JLabel("Single module • saved files • statements weighted by block • double-click a file or uncovered block"), BorderLayout.SOUTH);
    }

    void render(boolean running, GoCoverageRun.Result result) {
        run.setEnabled(!running); cancel.setEnabled(running);
        if (result != rendered) {
            rendered = result;
            model.files = result == null ? List.of() : result.files();
            model.fireTableDataChanged(); uncovered.clear();
            output.setText(result == null ? "" : result.output()); output.setCaretPosition(0);
        }
        if (running) summary.setText("Running Go coverage in the background. Cancel stops this session.");
        else if (result == null) summary.setText("Run coverage for the current Go module's saved files.");
        else {
            String totals = result.profile() == null ? "" : " — " + result.profile().totals().display() + " ("
                    + result.profile().totals().covered() + "/" + result.profile().totals().statements() + " statements)";
            summary.setText(result.detail() + totals + (result.module() == null ? "" : " — " + result.module()));
        }
    }

    void notice(String text) { summary.setText(text); }
    private GoCoverageRun.FileResult selected() {
        int index = table.getSelectedRow();
        if (index < 0 || index >= table.getRowCount()) return null;
        int row = table.convertRowIndexToModel(index);
        return row >= model.files.size() ? null : model.files.get(row);
    }

    private static final class FilesModel extends AbstractTableModel {
        List<GoCoverageRun.FileResult> files = List.of();
        private static final String[] HEADINGS = {"File", "Covered", "Statements", "Coverage", "Source status"};
        @Override public int getRowCount() { return files.size(); }
        @Override public int getColumnCount() { return HEADINGS.length; }
        @Override public String getColumnName(int column) { return HEADINGS[column]; }
        @Override public Class<?> getColumnClass(int column) { return column == 1 || column == 2 ? Long.class : String.class; }
        @Override public Object getValueAt(int row, int column) {
            var file = files.get(row);
            return switch (column) {
                case 0 -> file.coverage().name();
                case 1 -> file.coverage().totals().covered();
                case 2 -> file.coverage().totals().statements();
                case 3 -> file.coverage().totals().display();
                default -> file.problem();
            };
        }
    }
}

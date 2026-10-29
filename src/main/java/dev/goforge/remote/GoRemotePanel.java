package dev.goforge.remote;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.OnePixelSplitter;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

final class GoRemotePanel extends JPanel implements Disposable {
    private final Project project;private final GoRemoteService service;
    private final JTextField host=new JTextField(),user=new JTextField(),port=new JTextField("22"),key=new JTextField(),root=new JTextField(),go=new JTextField("go"),known=new JTextField();
    private final JTextArea log=new JTextArea();private final JLabel path=new JLabel("Remote workspace");
    private final JTabbedPane tabs=new JTabbedPane();
    private final JTextField gopls=new JTextField("gopls");
    private final JLabel analysisStatus=new JLabel();
    private final DefaultTableModel model=new DefaultTableModel(new String[]{"Name","Kind","Bytes"},0) {public boolean isCellEditable(int r,int c){return false;}};
    private final JTable table=new JTable(model);private final ArrayList<JButton> operations=new ArrayList<>();private final JButton cancel=new JButton("Cancel");
    private List<GoRemoteBackend.Entry> entries=List.of();private String directory="";private boolean truncated;private OnePixelSplitter split;
    GoRemotePanel(Project project) {
        super(new BorderLayout());this.project=project;service=GoRemoteService.getInstance(project);
        var saved=GoRemoteSettings.getInstance(project);host.setText(saved.host);user.setText(saved.user);port.setText(Integer.toString(saved.port));key.setText(saved.keyFile);root.setText(saved.remoteDirectory);go.setText(saved.goExecutable);known.setText(saved.knownHostsFile);
        gopls.setText(saved.goplsExecutable);
        tabs.addTab("Workspace",workspace());tabs.addTab("Connection",connection());tabs.addTab("Go analysis",analysis());tabs.addTab("Go debug",debug());if(saved.host.isBlank())tabs.setSelectedIndex(1);add(tabs);
        service.attach(this);
    }
    private JComponent connection() {
        var form=new JPanel(new GridBagLayout());int row=0;
        for(var pair:List.of(new Object[]{"SSH host / config alias",host},new Object[]{"User (blank: SSH config)",user},new Object[]{"Port",port},new Object[]{"Identity file (optional)",key},new Object[]{"Known-hosts file (optional)",known},new Object[]{"Remote workspace (absolute)",root},new Object[]{"Remote Go executable",go})) {
            var c=new GridBagConstraints();c.gridx=0;c.gridy=row;c.anchor=GridBagConstraints.WEST;c.insets=new Insets(4,6,4,8);form.add(new JLabel((String)pair[0]),c);
            c.gridx=1;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;form.add((JComponent)pair[1],c);row++;
        }
        var actions=new JPanel(new FlowLayout(FlowLayout.LEFT));actions.add(button("Save profile",p -> {tabs.setSelectedIndex(0);service.notice("Connection coordinates saved. No connection was made.\n");}));actions.add(button("Test connection",p -> {tabs.setSelectedIndex(0);service.check(p);}));
        var content=new JPanel(new BorderLayout());content.add(form,BorderLayout.NORTH);
        var text=new JTextArea("Remote host needs a POSIX shell, Python 3 and Go.\nOpenSSH checks known_hosts strictly. Add and verify the host normally before connecting.\nBackground tasks use your existing key/agent; SSH Terminal supports normal interactive authentication.\nOnly connection coordinates are saved; passwords and key contents are never stored.\nUse Go analysis to explicitly create a new snapshot and connect remote gopls.");
        text.setEditable(false);text.setOpaque(false);text.setBorder(BorderFactory.createEmptyBorder(6,6,6,6));content.add(text,BorderLayout.CENTER);
        var scroll=new JScrollPane(content);scroll.setBorder(BorderFactory.createEmptyBorder());scroll.getVerticalScrollBar().setUnitIncrement(20);
        var box=new JPanel(new BorderLayout());box.add(scroll,BorderLayout.CENTER);box.add(actions,BorderLayout.SOUTH);return box;
    }
    private JComponent analysis() {
        var box=new JPanel(new BorderLayout());var field=new JPanel(new BorderLayout(8,0));field.add(new JLabel("Remote gopls executable"),BorderLayout.WEST);field.add(gopls);
        var text=new JTextArea("Analyze this local Go project on the configured SSH host.\n\nStart previews saved files and creates a new private remote snapshot.\nCompletion, diagnostics, definition, references and symbol rename use remote gopls.\nUnsaved Go editor changes are sent as overlays. Saves update only the snapshot.\nConflicts stop analysis; existing remote workspace files are never overwritten.\n\nSupports one module or a project-root go.work with up to 32 modules\nand 64 local replacements, all contained in the reviewed source root.\nSave go.work/go.mod/sum edits before starting. Metadata and file-set changes\nrequire a fresh preview; ordinary Go source overlays work across modules.\nSDK definitions open read-only. Cached module definitions additionally require\none root module, reviewed checksums, and no replacements/vendor directory.\nStop closes remote source tabs. Full remote IDE hosting remains unavailable.\nNo remote tool installation or module downloads. Close/Stop retains the snapshot.\n\nThe Connection and Workspace tabs keep using the configured remote directory.\nLocal Run/Test/Debug configurations continue to use the local Go toolchain.");
        text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);
        var content=new JPanel(new BorderLayout(0,10));content.add(field,BorderLayout.NORTH);content.add(text);analysisStatus.setText(GoRemoteAnalysisService.getInstance(project).status());content.add(analysisStatus,BorderLayout.SOUTH);box.add(new JScrollPane(content));
        var actions=new JPanel(new FlowLayout(FlowLayout.LEFT));actions.add(button("Start remote analysis…",p -> {
            String executable=GoRemoteAnalysisConfig.validateExecutable(gopls.getText());GoRemoteSettings.getInstance(project).goplsExecutable=executable;tabs.setSelectedIndex(0);service.analyze(p,executable);
        }));var stop=new JButton("Stop remote analysis");stop.addActionListener(e -> {tabs.setSelectedIndex(0);service.stopAnalysis();});actions.add(stop);box.add(actions,BorderLayout.SOUTH);return box;
    }
    private JComponent debug() {
        var settings=GoRemoteSettings.getInstance(project);var delve=new JTextField(settings.delveExecutable);
        var target=new JTextField(".");var pid=new JTextField();var args=new JTextField();var env=new JTextArea(3,28);
        var mode=new JComboBox<>(new String[]{"Package", "Tests", "Executable", "Attach to remote PID"});
        var snapshot=new JCheckBox("Use current remote analysis snapshot (if active)");
        var form=new JPanel(new GridLayout(0,2,8,6));
        form.add(new JLabel("Remote Delve executable"));form.add(delve);form.add(new JLabel("Mode"));form.add(mode);
        form.add(new JLabel("Package / executable relative to workspace"));form.add(target);form.add(new JLabel("Remote process ID (attach only)"));form.add(pid);form.add(new JLabel("Program arguments (supports quoting)"));form.add(args);form.add(new JLabel("Environment (one NAME=value per line)"));form.add(new JScrollPane(env));
        var text=new JTextArea("Debug a saved module or project-root go.work workspace.\nAll use modules and local replacements must stay inside this project.\nThe host needs Python 3, Go and Delve; no tools are installed.\n\nReview first: every local visible file is checked against the remote SHA-256.\nNo upload, overwrite, automatic save or module download occurs.\nSave/revert local edits before starting. Source changes require a new review.\nBreakpoints, frames, variables, expressions and stepping use the IDE debugger.\nVerified workspace sources open normally.\nPackage and Tests sessions can also open verified sources from the selected\nremote Go SDK, read-only, from a currently paused stack frame.\nSelected cached dependency sources are also read-only at paused frames,\nfor one reviewed root module with go.sum checksums and no replacements/vendor.\nDependency sources are unavailable for go.work, Executable and Attach sessions.\nMissing or unverified dependency sources do not stop workspace/SDK debugging.\nSDK and dependency source breakpoints are unavailable.\nStop closes this session's read-only source tabs.\nExecutable and Attach sessions do not load SDK sources.\n\nDelve listens only on remote loopback; DAP travels through normal SSH.\nStop terminates a launched target, but detaches and preserves an attached PID.\nAttach requires remote OS ptrace permissions and a matching debug build.\nGoForge does not grant permissions or change system settings.");
        text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);
        var content=new JPanel(new BorderLayout(0,8));content.add(form,BorderLayout.NORTH);content.add(text);content.add(snapshot,BorderLayout.SOUTH);
        var actions=new JPanel(new FlowLayout(FlowLayout.LEFT));actions.add(button("Review and debug…",p->{
            String executable=GoRemoteAnalysisConfig.validateExecutable(delve.getText());settings.delveExecutable=executable;
            String selected=mode.getSelectedIndex()==3?"attach":mode.getSelectedIndex()==2?"exec":mode.getSelectedIndex()==1?"test":"debug";
            int selectedPid=selected.equals("attach")?Integer.parseInt(pid.getText().trim()):0;
            tabs.setSelectedIndex(0);service.debug(p,executable,selected,target.getText(),selectedPid,snapshot.isSelected(),com.intellij.util.execution.ParametersListUtil.parse(args.getText()),GoRemoteDebugOptions.environmentLines(env.getText()));
        }));
        var box=new JPanel(new BorderLayout());box.add(new JScrollPane(content));box.add(actions,BorderLayout.SOUTH);return box;
    }
    private JComponent workspace() {
        var top=new JPanel(new BorderLayout());var buttons=new JPanel(new GridLayout(0,4,4,3));
        buttons.add(button("Browse",p -> service.browse(p,directory)));buttons.add(button("Up",p -> service.browse(p,directory.contains("/")?directory.substring(0,directory.lastIndexOf('/')):"")));
        buttons.add(button("SSH Terminal",service::terminal));buttons.add(button("Upload project snapshot…",service::upload));
        buttons.add(button("Download selected…",p -> {var entry=selected();if(entry==null||!entry.kind().equals("file")){service.notice("Select a regular remote file.\n");return;}service.download(p,child(entry.name()));}));
        for(var task:GoRemoteBackend.GoTask.values())buttons.add(button("Go "+task.name().toLowerCase(),p -> service.go(p,task)));
        cancel.addActionListener(e -> service.cancel());buttons.add(cancel);var clear=new JButton("Clear log");clear.addActionListener(e -> {log.setText("");truncated=false;});buttons.add(clear);top.add(buttons,BorderLayout.CENTER);top.add(path,BorderLayout.SOUTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2){var entry=selected();if(entry!=null&&entry.kind().equals("directory"))use(p -> service.browse(p,child(entry.name())));}}});
        log.setEditable(false);split=new OnePixelSplitter(false,0.5f);split.setFirstComponent(new JScrollPane(table));split.setSecondComponent(new JScrollPane(log));
        var box=new JPanel(new BorderLayout());box.add(top,BorderLayout.NORTH);box.add(split);return box;
    }
    private JButton button(String name,java.util.function.Consumer<GoRemoteProfile> action) {var b=new JButton(name);operations.add(b);b.addActionListener(e -> use(action));return b;}
    private void use(java.util.function.Consumer<GoRemoteProfile> action) {
        try {var p=new GoRemoteProfile(host.getText(),user.getText(),Integer.parseInt(port.getText().trim()),key.getText(),root.getText(),go.getText(),known.getText());GoRemoteSettings.getInstance(project).save(p);action.accept(p);}
        catch(Exception e){tabs.setSelectedIndex(0);append("Invalid connection/action: "+e.getMessage()+"\n");}
    }
    private GoRemoteBackend.Entry selected() {int n=table.getSelectedRow();return n<0?null:entries.get(table.convertRowIndexToModel(n));}
    private String child(String name) {return GoRemoteProfile.relative(directory.isEmpty()?name:directory+"/"+name);}
    void entries(String directory,List<GoRemoteBackend.Entry> rows) {this.directory=directory;this.entries=rows;model.setRowCount(0);for(var row:rows)model.addRow(new Object[]{row.name(),row.kind(),row.bytes()});path.setText("Remote /"+directory);}
    void running(boolean active) {operations.forEach(b -> b.setEnabled(!active));cancel.setEnabled(active);}
    void append(String text) {analysisStatus.setText(GoRemoteAnalysisService.getInstance(project).status());int left=1024*1024-log.getDocument().getLength();if(left>0)log.append(text.substring(0,Math.min(left,text.length())));if(text.length()>left&&!truncated){truncated=true;log.append("\n[Display limited to 1 MiB; subsequent output omitted]\n");}log.setCaretPosition(log.getDocument().getLength());}
    public void dispose() {service.detach(this);split.dispose();}
}

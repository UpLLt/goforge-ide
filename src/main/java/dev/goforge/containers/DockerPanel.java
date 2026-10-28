package dev.goforge.containers;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DockerPanel extends JPanel {
    private final Project project;
    private final DockerService service;
    private final JTextField executable = new JTextField("docker", 25);
    private final JComboBox<DockerData.Context> contexts = new JComboBox<>();
    private final JComboBox<String> containerAction = new JComboBox<>(new String[]{"Start","Stop","Restart","Remove…","Exec command…","Exec terminal…"});
    private final JLabel connection = new JLabel("Load contexts, select one, then Connect / Refresh.");
    private final JLabel status = new JLabel("Ready");
    private final JTextArea output = new JTextArea();
    private final DefaultTableModel containersModel = model("Name", "Image", "State", "Status", "Ports", "ID");
    private final DefaultTableModel imagesModel = model("Repository", "Tag", "Size", "Created", "ID");
    private final JTable containers = new JBTable(containersModel), images = new JBTable(imagesModel);
    private List<DockerData.Container> containerRows = List.of();
    private List<DockerData.Image> imageRows = List.of();
    private final List<JButton> buttons = new ArrayList<>();
    private final JButton cancel = new JButton("Cancel operation");
    private final JTextField composeFile = new JTextField(36), composeProject = new JTextField(20);
    private boolean loadingContexts;
    public DockerPanel(Project project, DockerService service) {
        super(new BorderLayout(0,6)); this.project=project; this.service=service;
        JPanel north = new JPanel(new FlowLayout(FlowLayout.LEADING,4,0));
        JLabel contextLabel=new JLabel("Context");contextLabel.setLabelFor(contexts);north.add(contextLabel);contexts.setPreferredSize(new Dimension(155,28));north.add(contexts);
        north.add(button("Contexts",()->service.loadContexts(executable.getText())));
        north.add(button("Refresh",()->withContext(c->service.refresh(executable.getText(),c))));
        north.add(button("Settings…",()->{String path=Messages.showInputDialog(project,"Docker executable path or command","Docker Settings",Messages.getQuestionIcon(),executable.getText(),null);if(path!=null)executable.setText(path);}));
        cancel.setText("Cancel");cancel.setEnabled(false);cancel.setToolTipText("Stops the local CLI; daemon work already accepted may continue. Refresh to verify.");cancel.addActionListener(e->service.cancel());north.add(cancel);add(north,BorderLayout.NORTH);
        contexts.addActionListener(e -> { if (!loadingContexts) { clearInventory(); connection.setText("Not connected to this selection. Click Connect / Refresh."); } });
        for (JTable table : List.of(containers,images)) { table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setAutoCreateRowSorter(true); table.setFillsViewportHeight(true); }
        JTabbedPane tabs = new JTabbedPane();
        JPanel containerTab = new JPanel(new BorderLayout()); containerTab.add(new JBScrollPane(containers),BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING,4,0));
        actions.add(button("Details", () -> selectedContainer(row -> request(() -> DockerCommands.inspectContainer(row.id()),false))));
        actions.add(button("Logs", () -> selectedContainer(row -> request(() -> DockerCommands.logs(row.id()),false))));
        Runnable[] containerOperations = new Runnable[]{
            ()->lifecycle(DockerCommands.Lifecycle.START),()->lifecycle(DockerCommands.Lifecycle.STOP),()->lifecycle(DockerCommands.Lifecycle.RESTART),
            ()->selectedContainer(row->{if(confirm("Remove container "+row.name()+"\nID: "+row.id()+"\nContext: "+context()+"\n\nThe writable layer is removed; running containers are not forced and volumes are retained.","Remove Container"))request(()->DockerCommands.lifecycle(DockerCommands.Lifecycle.REMOVE,row.id()),true);}),
            ()->exec(false),()->exec(true)};
        JLabel actionLabel=new JLabel("Action");actionLabel.setLabelFor(containerAction);actions.add(actionLabel);
        containerAction.setName("container-action");containerAction.setPreferredSize(new Dimension(155,28));containerAction.setToolTipText("Choose an action, then click Run action. Selecting an item does not execute it.");actions.add(containerAction);
        actions.add(button("Run action",()->{int selected=containerAction.getSelectedIndex();if(selected>=0)containerOperations[selected].run();}));
        containerTab.add(actions,BorderLayout.SOUTH); tabs.addTab("Containers",containerTab);
        JPanel imageTab = new JPanel(new BorderLayout()); imageTab.add(new JBScrollPane(images),BorderLayout.CENTER);
        JPanel imageActions = new JPanel(new FlowLayout(FlowLayout.LEADING,4,0));
        imageActions.add(button("Details", () -> selectedImage(row -> request(() -> DockerCommands.inspectImage(row.id()),false))));
        imageActions.add(button("Pull…", this::pull)); imageActions.add(button("Build…", this::build));
        imageActions.add(button("Run image…", this::runImage));
        imageActions.add(button("Remove…", () -> selectedImage(row -> {
            String ref = DockerCommands.imageReference(row);
            if (confirm("Remove selected image reference " + ref + "\nID: " + row.id() + "\nContext: " + context() + "\n\nNo force flag is used. Docker will refuse unsafe removals.","Remove Image")) request(() -> DockerCommands.removeImage(ref),true);
        })));
        imageTab.add(imageActions,BorderLayout.SOUTH); tabs.addTab("Images",imageTab);
        tabs.addTab("Compose",composePanel());
        output.setEditable(false); output.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));
        JPanel details = new JPanel(new BorderLayout()); details.add(new JLabel("Command result / details / logs"),BorderLayout.NORTH); details.add(new JBScrollPane(output),BorderLayout.CENTER);
        OnePixelSplitter splitter = new OnePixelSplitter(true,.65f,.15f,.9f); splitter.setHonorComponentsMinimumSize(false); splitter.setFirstComponent(tabs); splitter.setSecondComponent(details); add(splitter,BorderLayout.CENTER);
        status.setToolTipText("Context is local to this panel. Cancel stops the CLI, not work already accepted by the Docker daemon. Interactive sessions are managed in Terminal.");add(status,BorderLayout.SOUTH);
    }
    private JPanel composePanel() {
        JPanel panel = new JPanel(new BorderLayout(0,8)); JPanel fields = new JPanel(new GridLayout(0,1,0,6));
        JPanel fileRow = new JPanel(new FlowLayout(FlowLayout.LEADING)); JLabel fileLabel=new JLabel("Compose file"); fileLabel.setLabelFor(composeFile); fileRow.add(fileLabel); fileRow.add(composeFile);
        composeFile.setText(service.directory().resolve("compose.yaml").toString());
        fileRow.add(button("Browse…", () -> { var file=FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor(),project,null); if (file!=null) composeFile.setText(file.getPath()); })); fields.add(fileRow);
        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEADING)); JLabel nameLabel=new JLabel("Compose project"); nameLabel.setLabelFor(composeProject); nameRow.add(nameLabel); nameRow.add(composeProject);
        String name=service.directory().getFileName()==null ? "goforge" : service.directory().getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]","-");
        if (!name.matches("[a-z0-9].*")) name="goforge-"+name; composeProject.setText(name.substring(0,Math.min(63,name.length()))); fields.add(nameRow);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
        for (DockerCommands.Compose action : DockerCommands.Compose.values()) actions.add(button("Compose " + action.name().toLowerCase(Locale.ROOT), () -> {
            String file=composeFile.getText().trim(), projectName=composeProject.getText().trim();
            if (action==DockerCommands.Compose.DOWN && !confirm("Stop and remove containers and default networks for Compose project " + projectName + "\nFile: " + file + "\nContext: " + context() + "\n\nNamed volumes and external networks are retained.","Compose Down")) return;
            request(() -> DockerCommands.compose(service.directory(),Path.of(file),projectName,action),action!=DockerCommands.Compose.PS);
        })); fields.add(actions); panel.add(new JScrollPane(fields),BorderLayout.CENTER);
        JTextArea hint = new JTextArea("Choose a Compose file inside this project. The explicit Compose project name determines which resources are managed. Up may create/recreate containers and build or pull images according to your file. Commands run only when clicked.\n\nThis panel uses the selected Docker context for every command; it never changes the global default context.");
        hint.setEditable(false); hint.setLineWrap(true); hint.setWrapStyleWord(true); hint.setOpaque(false); fields.setToolTipText(hint.getText()); return panel;
    }
    private void lifecycle(DockerCommands.Lifecycle action) { selectedContainer(row -> request(() -> DockerCommands.lifecycle(action,row.id()),true)); }
    private void pull() {
        String ref=Messages.showInputDialog(project,"Image reference (registry/repository:tag or digest)","Pull Image",Messages.getQuestionIcon());
        if(ref!=null) request(() -> DockerCommands.pull(ref.trim()),true);
    }
    private void build() {
        var dialog=new DockerFieldsDialog(project,"Build Image","Uses a local build context and Dockerfile inside this project. Docker receives these exact paths; build output is shown below.","Build context",service.directory().toString(),"Dockerfile",service.directory().resolve("Dockerfile").toString(),"Image tag","goforge-local:dev");
        if(dialog.showAndGet()) { String dir=dialog.value(0),file=dialog.value(1),tag=dialog.value(2); request(() -> DockerCommands.build(service.directory(),Path.of(dir),Path.of(file),tag),true); }
    }
    private void runImage() { selectedImage(row -> {
        var dialog=new DockerFieldsDialog(project,"Run Image","Starts this exact image ID as a new detached container. Leave arguments empty to use the image default. Port publishing is optional.","Container name","goforge-container","Publish port","","Command arguments (JSON)","[]");
        if(dialog.showAndGet()) { String name=dialog.value(0),ports=dialog.value(1),args=dialog.value(2); request(() -> DockerCommands.runImage(row.id(),name,ports,DockerCommands.parseArguments(args)),true); }
    }); }
    private void exec(boolean terminal) { selectedContainer(row -> {
        var dialog=new DockerFieldsDialog(project,terminal ? "Open Exec Terminal" : "Execute Command","Enter a JSON argument array. No host shell interprets it. The program must exist inside the selected running container.","Arguments (JSON)",terminal ? "[\"/bin/sh\"]" : "[\"echo\",\"hello\"]");
        if(dialog.showAndGet()) {
            try { List<String> args=DockerCommands.parseArguments(dialog.value(0)); if(terminal) withContext(c -> service.terminal(executable.getText(),c,row.id(),args)); else request(() -> DockerCommands.exec(row.id(),args,false),false); }
            catch(RuntimeException e) { output(e.getMessage()); }
        }
    }); }
    private void request(DockerService.RequestFactory factory,boolean refresh) { withContext(c -> service.request(executable.getText(),c,factory,refresh)); }
    private void withContext(java.util.function.Consumer<String> action) { String c=context(); if(c==null) output("Load contexts and select one first."); else action.accept(c); }
    private String context() { var item=(DockerData.Context)contexts.getSelectedItem(); return item==null ? null : item.name(); }
    private JButton button(String label,Runnable action) { var button=new JButton(label); buttons.add(button); button.addActionListener(e -> action.run()); return button; }
    private boolean confirm(String message,String title) { return Messages.showYesNoDialog(project,message,title,"Proceed","Cancel",Messages.getWarningIcon())==Messages.YES; }
    private void selectedContainer(java.util.function.Consumer<DockerData.Container> action) { int row=containers.getSelectedRow(); if(row<0) { output("Select one container first."); return; } action.accept(containerRows.get(containers.convertRowIndexToModel(row))); }
    private void selectedImage(java.util.function.Consumer<DockerData.Image> action) { int row=images.getSelectedRow(); if(row<0) { output("Select one image first."); return; } action.accept(imageRows.get(images.convertRowIndexToModel(row))); }
    private static DefaultTableModel model(String... columns) { return new DefaultTableModel(columns,0) { @Override public boolean isCellEditable(int r,int c) { return false; } }; }
    private void clearInventory() { containerRows=List.of(); imageRows=List.of(); containersModel.setRowCount(0); imagesModel.setRowCount(0); }
    void setContexts(List<DockerData.Context> values) {
        String previous=context(); loadingContexts=true;
        try { contexts.removeAllItems(); DockerData.Context selected=null; for(var value:values) { contexts.addItem(value); if(value.name().equals(previous) || selected==null && value.current()) selected=value; } if(selected!=null) contexts.setSelectedItem(selected); }
        finally { loadingContexts=false; }
        clearInventory(); connection.setText("Context loaded; click Connect / Refresh.");
    }
    void inventory(String expectedContext,String label,List<DockerData.Container> containerValues,List<DockerData.Image> imageValues) {
        if(!java.util.Objects.equals(expectedContext,context())) return;
        clearInventory(); containerRows=List.copyOf(containerValues); imageRows=List.copyOf(imageValues);
        for(var c:containerRows) containersModel.addRow(new Object[]{c.name(),c.image(),c.state(),c.status(),c.ports(),c.id()});
        for(var i:imageRows) imagesModel.addRow(new Object[]{i.repository(),i.tag(),i.size(),i.created(),i.id()}); connection.setText(label);
    }
    void busy(boolean value,String text) { for(var b:buttons)b.setEnabled(!value); executable.setEnabled(!value); contexts.setEnabled(!value); containerAction.setEnabled(!value); composeFile.setEnabled(!value); composeProject.setEnabled(!value); cancel.setEnabled(value); status.setText(connection.getText()+" · "+text);status.setToolTipText(status.getText()); }
    void output(String text) {
        int limit=1024*1024;
        output.setText(text.length()>limit ? text.substring(0,limit)+"\n[UI display truncated at 1 MiB]" : text); output.setCaretPosition(0);
    }
}

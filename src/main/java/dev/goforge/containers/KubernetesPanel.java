package dev.goforge.containers;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.table.JBTable;
import dev.goforge.coverage.GoCoverageProcess;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class KubernetesPanel extends JPanel {
    private final Project project;private final Path directory;private final ContainerToolTasks tasks;
    private final JTextField cli=new JTextField("kubectl",16),namespace=new JTextField("default",15),manifest=new JTextField(30);
    private final JComboBox<String> contexts=new JComboBox<>();private final JTextArea output=new JTextArea();
    private final DefaultTableModel model=new DefaultTableModel(new String[]{"Kind","Namespace","Name","Status","UID"},0){@Override public boolean isCellEditable(int r,int c){return false;}};
    private final JTable table=new JBTable(model);private List<KubernetesCommands.Resource> rows=List.of();private final List<JButton> buttons=new ArrayList<>();private final JButton cancel=new JButton("Cancel / stop forward");
    public KubernetesPanel(Project project){super(new BorderLayout(0,6));this.project=project;directory=Path.of(project.getBasePath()==null?System.getProperty("user.home"):project.getBasePath());tasks=new ContainerToolTasks(project);
        JPanel top=new JPanel(new FlowLayout(FlowLayout.LEADING,4,0));top.add(new JLabel("Context"));contexts.setPreferredSize(new Dimension(150,28));top.add(contexts);namespace.setColumns(9);top.add(new JLabel("Namespace"));top.add(namespace);
        top.add(button("Contexts",this::contexts));top.add(button("Refresh",this::refresh));top.add(button("Settings…",()->{String path=Messages.showInputDialog(project,"kubectl executable path or command","Kubernetes Settings",Messages.getQuestionIcon(),cli.getText(),null);if(path!=null)cli.setText(path);}));cancel.setText("Cancel");cancel.setEnabled(false);cancel.addActionListener(e->tasks.cancel());top.add(cancel);add(top,BorderLayout.NORTH);
        contexts.addActionListener(e->clear());namespace.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){clear();}public void removeUpdate(javax.swing.event.DocumentEvent e){clear();}public void changedUpdate(javax.swing.event.DocumentEvent e){clear();}});
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setAutoCreateRowSorter(true);JPanel inventory=new JPanel(new BorderLayout());inventory.add(new JScrollPane(table),BorderLayout.CENTER);JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEADING,4,0));
        actions.add(button("Describe",()->selected(r->request((bin,c,n)->KubernetesCommands.describe(bin,c,n,r),false))));actions.add(button("Logs",()->selected(r->request((bin,c,n)->KubernetesCommands.logs(bin,c,n,r),false))));actions.add(button("Port-forward…",this::forward));
        actions.add(button("Delete…",()->selected(r->{if(Messages.showYesNoDialog(project,"Delete "+r.target()+" in "+r.namespace()+" on context "+context()+"?\nSelected UID: "+r.uid(),"Delete Kubernetes Resource",Messages.getWarningIcon())==Messages.YES)delete(r);})));inventory.add(actions,BorderLayout.SOUTH);
        output.setEditable(false);output.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));OnePixelSplitter split=new OnePixelSplitter(true,.60f,.15f,.9f);split.setHonorComponentsMinimumSize(false);split.setFirstComponent(inventory);split.setSecondComponent(new JScrollPane(output));add(split,BorderLayout.CENTER);
        JPanel bottom=new JPanel(new BorderLayout());JPanel apply=new JPanel(new FlowLayout(FlowLayout.LEADING,4,0));apply.add(new JLabel("Project manifest"));apply.add(manifest);apply.add(button("Preview Diff / Apply…",this::preview));bottom.add(apply,BorderLayout.CENTER);cancel.setToolTipText("Stop the owned port-forward process (loopback only, 10-minute maximum), or cancel the current command.");add(bottom,BorderLayout.SOUTH);
    }
    private String context(){return (String)contexts.getSelectedItem();}
    private String bin(String configured)throws Exception{return DockerCommands.resolveExecutable(configured,new GeneralCommandLine().getEffectiveEnvironment());}
    private GoCoverageProcess.Result run(List<String> argv,BooleanSupplier cancelled,Duration timeout)throws Exception{return GoCoverageProcess.run(argv,directory,new GeneralCommandLine().getEffectiveEnvironment(),cancelled,timeout);}
    private static String text(GoCoverageProcess.Result r){return "Exit "+r.exitCode()+(r.cancelled()?" · cancelled":"")+(r.timedOut()?" · timed out":"")+(r.outputTruncated()?" · output truncated":"")+"\n"+r.stdout()+"\n"+r.stderr();}
    private static String json(GoCoverageProcess.Result r)throws Exception{if(!Integer.valueOf(0).equals(r.exitCode())||r.cancelled()||r.timedOut()||r.outputTruncated())throw new IllegalStateException(text(r));return r.stdout();}
    private void contexts(){String configured=cli.getText();tasks.submit("Read local Kubernetes contexts",(cancel,p)->json(run(List.of(bin(configured),"config","get-contexts","--output=name"),cancel,Duration.ofSeconds(15))),value->{contexts.removeAllItems();value.lines().filter(s->!s.isBlank()).forEach(contexts::addItem);show("Contexts loaded from local configuration. Select one and explicitly refresh to contact it.");},this::show,this::busy);}
    private void refresh(){String configured=cli.getText(),c=context(),n=namespace.getText().trim();tasks.submit("Kubernetes resources",(cancel,p)->KubernetesCommands.resources(json(run(KubernetesCommands.inventory(bin(configured),c,n),cancel,Duration.ofSeconds(30)))),value->{clear();rows=value;for(var r:rows)model.addRow(new Object[]{r.kind(),r.namespace(),r.name(),r.status(),r.uid()});show("Loaded "+rows.size()+" resources from "+c+" / "+n);},this::show,this::busy);}
    @FunctionalInterface private interface Command{List<String> build(String bin,String c,String n)throws Exception;}
    private void request(Command command,boolean mutation){String configured=cli.getText(),c=context(),n=namespace.getText().trim();tasks.submit("Kubernetes command",(cancel,p)->text(run(command.build(bin(configured),c,n),cancel,Duration.ofSeconds(60))),this::show,this::show,this::busy);}
    private void delete(KubernetesCommands.Resource row){String configured=cli.getText(),c=context(),n=namespace.getText().trim();tasks.submit("Delete selected resource UID",(cancel,p)->{Path options=Files.createTempFile("goforge-k8s-delete-",".json");try{Files.writeString(options,KubernetesCommands.deleteOptions(row));return text(run(KubernetesCommands.delete(bin(configured),c,n,row,options),cancel,Duration.ofSeconds(60)));}finally{Files.deleteIfExists(options);}},this::show,this::show,this::busy);}
    private void forward(){selected(row->{var dialog=new DockerFieldsDialog(project,"Start Port Forward","Forwards only 127.0.0.1. Cancel stops this local process; no cluster resource is changed.","Local port","8080","Remote port","80");if(!dialog.showAndGet())return;String configured=cli.getText(),c=context(),n=namespace.getText().trim(),local=dialog.value(0),remote=dialog.value(1);var forward=new KubernetesPortForward();javax.swing.Timer timer=new javax.swing.Timer(250,e->{if(!forward.output().isEmpty())show(forward.output());});com.intellij.openapi.util.Disposer.register(project,timer::stop);
        tasks.submit("Kubernetes loopback port-forward",(cancel,p)->forward.run(KubernetesCommands.forward(bin(configured),c,n,row,Integer.parseInt(local),Integer.parseInt(remote)),directory,cancel,Duration.ofMinutes(10)),result->{timer.stop();show(result);},failure->{timer.stop();show(failure+"\n"+forward.output());},value->{busy(value);if(value){show("Starting port-forward; waiting for kubectl readiness output…");timer.start();}});});}
    private record Preview(KubernetesCommands.Manifest manifest,String diff,String context,String namespace,String executable){}
    private void preview(){String configured=cli.getText(),c=context(),n=namespace.getText().trim(),path=manifest.getText().trim();tasks.submit("Preview Kubernetes manifest diff",(cancel,p)->{var snapshot=KubernetesCommands.readManifest(directory,Path.of(path));String executable=bin(configured);Path temp=Files.createTempFile("goforge-k8s-diff-",".yaml");try{Files.write(temp,snapshot.bytes());var diff=run(KubernetesCommands.manifest(executable,c,n,temp,false),cancel,Duration.ofSeconds(60));if(!KubernetesCommands.diffSuccessful(diff.exitCode(),diff.cancelled(),diff.timedOut(),diff.outputTruncated()))throw new IllegalStateException(text(diff));return new Preview(snapshot,text(diff),c,n,executable);}finally{Files.deleteIfExists(temp);}},this::confirmApply,this::show,this::busy);}
    private void confirmApply(Preview preview){show(preview.diff());JTextArea review=new JTextArea("Context: "+preview.context()+"\nNamespace: "+preview.namespace()+"\nSHA256: "+preview.manifest().sha256()+"\n\n"+preview.diff()+"\n\nManifest (may include cluster-scoped objects):\n"+new String(preview.manifest().bytes(),java.nio.charset.StandardCharsets.UTF_8));review.setEditable(false);JScrollPane pane=new JScrollPane(review);pane.setPreferredSize(new Dimension(800,500));
        if(JOptionPane.showConfirmDialog(this,pane,"Apply This Reviewed Manifest?",JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.OK_OPTION)return;
        tasks.submit("Apply reviewed Kubernetes manifest",(cancel,p)->{preview.manifest().requireUnchanged();Path temp=Files.createTempFile("goforge-k8s-apply-",".yaml");try{Files.write(temp,preview.manifest().bytes());return text(run(KubernetesCommands.manifest(preview.executable(),preview.context(),preview.namespace(),temp,true),cancel,Duration.ofSeconds(90)));}finally{Files.deleteIfExists(temp);}},this::show,this::show,this::busy);}
    private JButton button(String text,Runnable run){JButton b=new JButton(text);buttons.add(b);b.addActionListener(e->run.run());return b;}
    private void selected(java.util.function.Consumer<KubernetesCommands.Resource> fn){int row=table.getSelectedRow();if(row<0)show("Select one resource first.");else fn.accept(rows.get(table.convertRowIndexToModel(row)));}
    private void clear(){rows=List.of();model.setRowCount(0);}
    private void busy(boolean busy){buttons.forEach(b->b.setEnabled(!busy));cancel.setEnabled(busy);cli.setEnabled(!busy);contexts.setEnabled(!busy);namespace.setEnabled(!busy);manifest.setEnabled(!busy);}
    private void show(String value){output.setText(value.length()>1024*1024?value.substring(0,1024*1024)+"\n[display truncated]":value);output.setCaretPosition(0);}
}

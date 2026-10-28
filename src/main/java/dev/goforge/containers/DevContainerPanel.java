package dev.goforge.containers;

import com.google.gson.*;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import dev.goforge.coverage.GoCoverageProcess;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class DevContainerPanel extends JPanel {
    private final Project project;private final Path directory;private final ContainerToolTasks tasks;
    private final JTextField config=new JTextField(48),node=new JTextField(38),cli=new JTextField(48),docker=new JTextField("docker",18),context=new JTextField("desktop-linux",18),suffix=new JTextField("workspace",18);
    private final JTextArea output=new JTextArea();private final List<JButton> buttons=new ArrayList<>();private final JButton cancel=new JButton("Cancel");
    private DevContainerPlan plan;private final DevContainerSession session=new DevContainerSession();
    public DevContainerPanel(Project project){super(new BorderLayout(0,6));this.project=project;directory=Path.of(project.getBasePath()==null?System.getProperty("user.home"):project.getBasePath());tasks=new ContainerToolTasks(project);
        config.setText(directory.resolve(".devcontainer/devcontainer.json").toString());node.setText(Path.of(PathManager.getHomePath(),"goforge-tools","bin",dev.goforge.tools.HostTools.executableName("node")).toString());cli.setText(Path.of(PathManager.getHomePath(),"goforge-tools","devcontainer-cli","dist","spec-node","devContainersSpecCLI.js").toString());
        JPanel fields=new JPanel(new FlowLayout(FlowLayout.LEADING,4,0));config.setColumns(35);fields.add(new JLabel("Configuration"));fields.add(config);fields.add(button("Review",this::load));fields.add(button("Settings…",this::settings));add(fields,BorderLayout.NORTH);
        output.setEditable(false);output.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));add(new JScrollPane(output),BorderLayout.CENTER);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEADING,4,0));actions.add(button("Start Workspace…",this::start));actions.add(button("Terminal",this::terminal));actions.add(button("postCreate…",this::postCreate));actions.add(button("Stop",this::stop));actions.add(button("Remove…",this::remove));actions.add(cancel);cancel.setEnabled(false);cancel.addActionListener(e->tasks.cancel());add(actions,BorderLayout.SOUTH);
        config.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){plan=null;}public void removeUpdate(javax.swing.event.DocumentEvent e){plan=null;}public void changedUpdate(javax.swing.event.DocumentEvent e){plan=null;}});
    }
    private void settings(){var dialog=new DockerFieldsDialog(project,"Dev Container Settings","The basic workflow mounts only this workspace, refuses inherited image metadata and never runs host initialization or lifecycle hooks automatically.","Node executable",node.getText(),"Official CLI .js",cli.getText(),"Docker executable",docker.getText(),"Docker context",context.getText(),"Container suffix",suffix.getText());if(dialog.showAndGet()){node.setText(dialog.value(0));cli.setText(dialog.value(1));docker.setText(dialog.value(2));context.setText(dialog.value(3));if(!suffix.getText().equals(dialog.value(4)))plan=null;suffix.setText(dialog.value(4));}}
    private String resolve(String configured)throws Exception{return DockerCommands.resolveExecutable(configured,new GeneralCommandLine().getEffectiveEnvironment());}
    private Map<String,String> environment(String ctx){if(ctx==null||ctx.isBlank()||ctx.startsWith("-")||ctx.chars().anyMatch(c->c<32))throw new IllegalArgumentException("Enter an explicit Docker context.");var env=new HashMap<>(new GeneralCommandLine().getEffectiveEnvironment());env.put("DOCKER_CONTEXT",ctx);return env;}
    private GoCoverageProcess.Result run(List<String> argv,String ctx,BooleanSupplier cancelled,Duration timeout)throws Exception{return GoCoverageProcess.run(argv,directory,environment(ctx),cancelled,timeout);}
    private static String text(GoCoverageProcess.Result r){return "Exit "+r.exitCode()+(r.cancelled()?" · cancelled":"")+(r.timedOut()?" · timed out":"")+(r.outputTruncated()?" · output truncated":"")+"\n"+r.stdout()+"\n"+r.stderr();}
    private static void success(GoCoverageProcess.Result r){if(!Integer.valueOf(0).equals(r.exitCode())||r.cancelled()||r.timedOut()||r.outputTruncated())throw new IllegalStateException(text(r));}
    private void load(){String path=config.getText().trim(),name=suffix.getText().trim();tasks.submit("Review Dev Container",(cancel,p)->DevContainerPlan.read(directory,Path.of(path),name),value->{plan=value;show(review(value));},this::show,this::busy);}
    private static String review(DevContainerPlan p){return "BASIC WORKSPACE PLAN\nContainer: "+p.name()+"\nWorkspace read/write mount: "+p.workspace()+" -> "+p.workspaceFolder()+"\n\nOriginal fields not imported into this basic workflow: "+p.omitted()+"\nNo host initializeCommand, features, arbitrary mounts or lifecycle commands are run. Use a full external Dev Container client for those capabilities.\n\nDeferred postCreateCommand (requires separate confirmation):\n"+p.lifecycle()+"\n\nGenerated configuration passed to official CLI:\n"+p.configuration()+"\n\nOriginal configuration:\n"+p.original();}
    private void start(){
        if(plan==null){show("Load and review a configuration first.");return;}
        DevContainerPlan selected=plan;String c=context.getText().trim(),d=docker.getText().trim(),n=node.getText().trim(),script=cli.getText().trim();
        JTextArea preview=new JTextArea("Docker context: "+c+"\n\n"+review(selected));preview.setEditable(false);JScrollPane scroll=new JScrollPane(preview);scroll.setPreferredSize(new Dimension(850,500));
        if(JOptionPane.showConfirmDialog(this,scroll,"Create / Start This Basic Workspace?",JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.OK_OPTION)return;
        var attempt=new java.util.concurrent.atomic.AtomicReference<DevContainerSession.Attempt>();
        tasks.submitManaged("Start Dev Container workspace",(cancel,p)->{
            selected.requireUnchanged();String nodePath=resolve(n),dockerPath=resolve(d);Path cliPath=Path.of(script).toRealPath();
            if(!Files.isRegularFile(cliPath))throw new IllegalArgumentException("Choose the official Dev Containers CLI JavaScript entry point.");
            Path generated=Path.of(PathManager.getSystemPath(),"goforge-devcontainers",selected.owner(),selected.name(),"devcontainer.json");
            var owned=session.begin(selected,dockerPath,c,environment(c));attempt.set(owned);
            return session.start(owned,nodePath,cliPath,generated,cancel);
        },value->{session.commit(attempt.get());show(value.output()+"\nWorkspace ready. Lifecycle commands were skipped.");},this::show,this::busy,
        ()->{var owned=attempt.get();return owned==null?"No workspace creation was started.":session.abandon(owned).output();});
    }
    private DevContainerSession.State ready(){var state=session.state();if(state==null)show("Start this workspace first. For an unfinished attempt, use Remove to retry recorded resource cleanup.");return state;}
    private void terminal(){
        var state=ready();if(state==null)return;
        tasks.submit("Open workspace terminal",(cancel,p)->{
            DevContainerImage.inspectContainer(state.plan(),state.imageId(),state.docker(),state.context(),state.id(),state.environment(),cancel);
            return DockerCommands.command(state.docker(),state.context(),new DockerCommands.Request("Workspace terminal",List.of("container","exec","-it","--workdir",state.plan().workspaceFolder(),"--",state.id(),"/bin/sh"),Duration.ofHours(1),true));
        },argv->TerminalToolWindowManager.getInstance(project).createNewSession(directory.toString(),"Dev Container",argv,true,true),this::show,this::busy);
    }
    private void postCreate(){
        var state=ready();if(state==null)return;DevContainerPlan selected=state.plan();var commands=DevContainerPlan.commands(selected.lifecycle());
        if(commands.isEmpty()){show("This configuration has no postCreateCommand.");return;}
        if(Messages.showYesNoDialog(project,"Execute these commands inside the selected workspace container?\nStrings intentionally run via /bin/sh -c in the container. Object entries run sequentially.\n\n"+selected.lifecycle(),"Run Deferred postCreateCommand",Messages.getWarningIcon())!=Messages.YES)return;
        tasks.submit("Run explicitly approved postCreate",(cancel,p)->{
            selected.requireUnchanged();DevContainerImage.inspectContainer(selected,state.imageId(),state.docker(),state.context(),state.id(),state.environment(),cancel);
            StringBuilder log=new StringBuilder();for(var command:commands){if(cancel.getAsBoolean())break;var args=new ArrayList<>(List.of("container","exec","--workdir",selected.workspaceFolder(),"--",state.id()));args.addAll(command);
                var result=DockerClient.execute(state.docker(),state.context(),new DockerCommands.Request("postCreate",args,Duration.ofMinutes(10),true),directory,state.environment(),cancel);log.append(result.output()).append('\n');if(!result.succeeded())break;}return log.toString();
        },this::show,this::show,this::busy);
    }
    private void stop(){if(ready()!=null)tasks.submit("Stop workspace container",(cancel,p)->session.stop(cancel),this::show,this::show,this::busy);}
    private void remove(){
        if(!session.hasResources()){show("There is no recorded workspace or unfinished attempt to remove.");return;}
        if(Messages.showYesNoDialog(project,"Remove only these recorded workspace resources?\n"+session.description()+"\nProject files in the workspace bind mount are retained.","Remove Dev Container",Messages.getWarningIcon())!=Messages.YES)return;
        tasks.submit("Remove workspace resources",(cancel,p)->session.remove(cancel),this::show,this::show,this::busy);
    }
    private JButton button(String text,Runnable action){var b=new JButton(text);buttons.add(b);b.addActionListener(e->action.run());return b;}
    private void busy(boolean value){buttons.forEach(b->b.setEnabled(!value));cancel.setEnabled(value);for(var f:List.of(config,node,cli,docker,context,suffix))f.setEnabled(!value);}
    private void show(String text){output.setText(text.length()>1024*1024?text.substring(0,1024*1024)+"\n[display truncated]":text);output.setCaretPosition(0);}
}

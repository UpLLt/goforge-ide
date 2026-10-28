package dev.goforge.http;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.List;

final class HttpClientPanel extends JPanel {
    private final Project project;private final HttpClientService service;
    private final JBTextArea source=new JBTextArea("### Local request\n# @expect status 200\nGET http://localhost:8080/\nAccept: application/json\n");
    private final JBTextArea body=new JBTextArea(),headers=new JBTextArea(),assertions=new JBTextArea();
    private final JComboBox<HttpRequestFile.Request> requests=new JComboBox<>();
    private final JComboBox<String> environmentNames=new JComboBox<>(new String[]{HttpEnvironment.NONE.name()});
    private final JLabel status=new JLabel("Responses stay in memory. Choose environments explicitly; no scripts execute.");
    private final JButton send=new JButton("Send"),cancel=new JButton("Cancel"),save=new JButton("Save Response…"),saveSummary=new JButton("Save Summary…");
    private final List<JComponent> idleControls=new ArrayList<>();
    private final DefaultTableModel resultsModel=new DefaultTableModel(new String[]{"Request","Outcome","HTTP","Checks","ms"},0){@Override public boolean isCellEditable(int row,int column){return false;}};
    private final JTable results=new JTable(resultsModel);
    private final List<HttpCollectionRunner.Row> rows=new ArrayList<>();
    private List<HttpRequestFile.Request> parsed=List.of();
    private HttpCollectionRunner.Run active;private HttpCollectionRunner.Summary summary;
    private HttpEnvironment environments=HttpEnvironment.empty();private HttpEnvironment.Selection environment=HttpEnvironment.NONE;
    private String publicFile="",privateFile="",runSource="";
    private HttpRequestEngine.Result result;
    HttpClientPanel(Project project,HttpClientService service){
        super(new BorderLayout(0,4));this.project=project;this.service=service;
        var first=new JPanel(new FlowLayout(FlowLayout.LEFT,4,1));var second=new JPanel(new FlowLayout(FlowLayout.LEFT,4,1));
        var open=button("Open .http…",this::openFile);var refresh=button("List",()->parseAt(0));
        requests.setPreferredSize(new Dimension(180,26));requests.setName("http-request-selection");
        first.add(open);first.add(refresh);first.add(requests);first.add(send);first.add(button("Run All",this::runAll));first.add(button("Run Selected…",this::selectRequests));first.add(cancel);
        environmentNames.setPreferredSize(new Dimension(145,26));environmentNames.setName("http-environment-selection");
        second.add(button("Environments…",this::chooseEnvironments));second.add(environmentNames);second.add(button("Next Failure",this::nextFailure));second.add(saveSummary);second.add(save);second.add(button("Help",this::help));
        var bars=new JPanel(new GridLayout(2,1,0,0));bars.add(first);bars.add(second);add(bars,BorderLayout.NORTH);
        source.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));source.getAccessibleContext().setAccessibleName("HTTP request source");
        for(var area:List.of(body,headers,assertions)){area.setEditable(false);area.setFont(source.getFont());}
        body.getAccessibleContext().setAccessibleName("HTTP response body");headers.getAccessibleContext().setAccessibleName("HTTP response headers");assertions.getAccessibleContext().setAccessibleName("HTTP response assertions");
        results.setName("http-collection-results");results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);results.setRowHeight(23);
        var tabs=new JTabbedPane();tabs.addTab("Results",new JBScrollPane(results));tabs.addTab("Response",new JBScrollPane(body));tabs.addTab("Headers",new JBScrollPane(headers));tabs.addTab("Assertions",new JBScrollPane(assertions));
        var split=new OnePixelSplitter(true,.46f,.1f,.9f);split.setHonorComponentsMinimumSize(false);split.setFirstComponent(new JBScrollPane(source));split.setSecondComponent(tabs);
        add(split,BorderLayout.CENTER);add(status,BorderLayout.SOUTH);
        idleControls.addAll(List.of(requests,send,environmentNames));cancel.setEnabled(false);save.setEnabled(false);saveSummary.setEnabled(false);
        send.addActionListener(e->start(List.of(Math.max(0,requests.getSelectedIndex()))));cancel.addActionListener(e->{if(active!=null)active.cancel();});save.addActionListener(e->saveResponse());saveSummary.addActionListener(e->saveSummary());
        environmentNames.addActionListener(e->{if(active==null){String selected=(String)environmentNames.getSelectedItem();environment=selected==null||selected.equals(HttpEnvironment.NONE.name())?HttpEnvironment.NONE:environments.select(selected);parseAt(0);}});
        results.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting())showResult();});
        results.addMouseListener(new MouseAdapter(){@Override public void mouseClicked(MouseEvent e){if(e.getClickCount()==2)navigateToResult();}});
        results.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"source");results.getActionMap().put("source",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){navigateToResult();}});
        parseAt(0);
    }
    private JButton button(String label,Runnable action){var button=new JButton(label);button.addActionListener(e->action.run());idleControls.add(button);return button;}
    void load(String text,int line,boolean execute){if(active!=null){status.setText("Cancel or finish the collection before loading another file.");return;}source.setText(text);source.setCaretPosition(0);if(parseAt(line)&&execute)start(List.of(Math.max(0,requests.getSelectedIndex())));}
    private boolean parseAt(int line){
        try{parsed=HttpRequestFile.parse(source.getText(),environment.variables());requests.removeAllItems();for(var request:parsed)requests.addItem(request);
            for(int i=0;i<parsed.size();i++)if(line>=parsed.get(i).startLine()&&line<=parsed.get(i).endLine())requests.setSelectedIndex(i);return true;
        }catch(IllegalArgumentException failure){parsed=List.of();requests.removeAllItems();status.setText(new HttpPrivacy(environment.secrets()).redact(failure.getMessage()));return false;}
    }
    private void runAll(){if(active==null&&parseAt(0))start(indices());}
    private List<Integer> indices(){var all=new ArrayList<Integer>();for(int i=0;i<parsed.size();i++)all.add(i);return all;}
    private void selectRequests(){
        if(active!=null||!parseAt(0))return;
        var selected=new JList<>(parsed.toArray(HttpRequestFile.Request[]::new));selected.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);selected.setVisibleRowCount(Math.min(12,parsed.size()));selected.setSelectionInterval(0,parsed.size()-1);
        var scroll=new JScrollPane(selected);scroll.setPreferredSize(new Dimension(480,280));
        if(JOptionPane.showConfirmDialog(this,scroll,"Run Selected Requests in File Order",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        start(Arrays.stream(selected.getSelectedIndices()).boxed().toList());
    }
    private void start(List<Integer> selection){
        if(active!=null)return;int previous=Math.max(0,requests.getSelectedIndex());if(!parseAt(0))return;
        if(selection.isEmpty()){status.setText("Select at least one request.");return;}
        if(selection.stream().anyMatch(index->index>=parsed.size())){status.setText("Request selection changed; list requests and select again.");return;}
        requests.setSelectedIndex(Math.min(previous,parsed.size()-1));rows.clear();resultsModel.setRowCount(0);result=null;summary=null;body.setText("");headers.setText("");assertions.setText("");save.setEnabled(false);saveSummary.setEnabled(false);runSource=source.getText();busy(true);
        status.setText("Running in file order · 60 s/request · 4 MiB/response, 16 MiB/collection · continues after failures");
        var privacy=HttpPrivacy.forRun(environment,parsed);
        active=HttpCollectionRunner.start(service.engine,parsed,selection,Duration.ofSeconds(60),4*1024*1024,16*1024*1024,privacy,row->ApplicationManager.getApplication().invokeLater(()->{if(!project.isDisposed()&&active!=null){rows.add(row);resultsModel.addRow(new Object[]{(row.requestIndex()+1)+". "+row.name(),row.status(),row.response()==null?"":row.response().status(),row.assertions().stream().filter(HttpAssertion.Outcome::passed).count()+"/"+row.assertions().size(),row.response()==null?"":row.response().elapsedMillis()});if(results.getSelectedRow()<0)results.setRowSelectionInterval(0,0);}}));
        service.collection=active;var expected=active;
        expected.completion().whenComplete((value,error)->ApplicationManager.getApplication().invokeLater(()->{
            if(project.isDisposed()||active!=expected)return;active=null;service.collection=null;busy(false);
            if(error!=null){status.setText("Collection could not complete; recorded rows are retained.");return;}
            summary=value;status.setText(value.description());saveSummary.setEnabled(true);showResult();
        }));
    }
    private void busy(boolean value){source.setEditable(!value);idleControls.forEach(control->control.setEnabled(!value));cancel.setEnabled(value);if(value){save.setEnabled(false);saveSummary.setEnabled(false);}}
    private void showResult(){
        int selected=results.getSelectedRow();if(selected<0||selected>=rows.size())return;var row=rows.get(selected);result=row.response();save.setEnabled(active==null&&result!=null);
        body.setText(row.displayBody());headers.setText(row.displayHeaders());var checks=new StringBuilder(row.detail()).append("\n\n");for(var outcome:row.assertions())checks.append(outcome.passed()?"PASS ":"FAIL ").append(outcome.label()).append(" · ").append(outcome.detail()).append('\n');
        assertions.setText(checks.toString());for(var text:List.of(body,headers,assertions))text.setCaretPosition(0);
    }
    private void nextFailure(){if(rows.isEmpty())return;int selected=results.getSelectedRow();for(int offset=1;offset<=rows.size();offset++){int index=Math.floorMod(selected+offset,rows.size());if(rows.get(index).failure()){results.setRowSelectionInterval(index,index);results.scrollRectToVisible(results.getCellRect(index,0,true));navigateToResult();return;}}status.setText("No assertion or transport failures in the recorded results.");}
    private void navigateToResult(){int selected=results.getSelectedRow();if(selected<0||selected>=rows.size())return;if(!source.getText().equals(runSource)){status.setText("Source has changed since this run; recorded line numbers are shown without moving to a potentially wrong location.");return;}var row=rows.get(selected);int line=row.assertions().stream().filter(a->!a.passed()).mapToInt(HttpAssertion.Outcome::sourceLine).findFirst().orElse(row.sourceLine());try{source.setCaretPosition(source.getLineStartOffset(line));source.requestFocusInWindow();}catch(javax.swing.text.BadLocationException ignored){status.setText("Recorded source line is no longer available.");}}
    static String displayBody(HttpRequestEngine.Result response){return HttpCollectionRunner.displayBody(response);}
    private void chooseEnvironments(){
        if(active!=null)return;var normal=new JTextField(publicFile,38);var secret=new JTextField(privateFile,38);var fields=new JPanel(new GridLayout(0,1,0,5));
        fields.add(new JLabel("Select local JSON files. Private values override ordinary values; inline @variables override both."));fields.add(fileRow("Ordinary JSON (optional)",normal));fields.add(fileRow("Private JSON (optional)",secret));
        fields.add(new JLabel("Files are read only, kept in memory and never added to Git by this client. Protect private files in your own VCS rules."));
        fields.add(new JLabel("Use {\"dev\":{\"host\":\"http://localhost:8080\",\"token\":\"…\"}}. Reopen this dialog to reload snapshots."));
        if(JOptionPane.showConfirmDialog(this,fields,"HTTP Environment Files",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION)return;
        String normalPath=normal.getText().trim(),secretPath=secret.getText().trim();busy(true);cancel.setEnabled(false);status.setText("Reading explicitly selected environment files…");
        ApplicationManager.getApplication().executeOnPooledThread(()->{
            try{var loaded=HttpEnvironment.load(normalPath.isEmpty()?null:Path.of(normalPath),secretPath.isEmpty()?null:Path.of(secretPath));
                // Validate every environment now so a combo action never throws an unchecked expansion error on the EDT.
                for(String name:loaded.names())loaded.select(name);
                ApplicationManager.getApplication().invokeLater(()->{if(project.isDisposed())return;environments=loaded;publicFile=normalPath;privateFile=secretPath;environmentNames.removeAllItems();environmentNames.addItem(HttpEnvironment.NONE.name());for(String name:loaded.names())environmentNames.addItem(name);environmentNames.setSelectedIndex(0);busy(false);status.setText("Environment snapshots loaded; select a name before sending. Values are not persisted.");});
            }catch(Exception failure){ApplicationManager.getApplication().invokeLater(()->{if(!project.isDisposed()){busy(false);status.setText("Environment files could not be loaded: invalid JSON, variables or local file. File contents were not logged.");}});}
        });
    }
    private JPanel fileRow(String label,JTextField field){var row=new JPanel(new BorderLayout(5,0));row.add(new JLabel(label),BorderLayout.WEST);row.add(field,BorderLayout.CENTER);var choose=new JButton("Choose…");choose.addActionListener(e->{var file=FileChooser.chooseFile(new FileChooserDescriptor(true,false,false,false,false,false).withTitle(label).withFileFilter(v->"json".equalsIgnoreCase(v.getExtension())),project,null);if(file!=null)field.setText(file.getPath());});row.add(choose,BorderLayout.EAST);return row;}
    private void openFile(){
        if(active!=null)return;var file=FileChooser.chooseFile(new FileChooserDescriptor(true,false,false,false,false,false).withTitle("Open HTTP Request File").withFileFilter(v->"http".equals(v.getExtension())||"rest".equals(v.getExtension())),project,null);
        if(file==null)return;if(file.getLength()>2_000_000){status.setText("Request file exceeds 2 MB.");return;}
        ApplicationManager.getApplication().executeOnPooledThread(()->{try{String text=Files.readString(file.toNioPath());ApplicationManager.getApplication().invokeLater(()->{if(!project.isDisposed())load(text,0,false);});}catch(Exception failure){ApplicationManager.getApplication().invokeLater(()->{if(!project.isDisposed())status.setText("Unable to read request file.");});}});
    }
    private void saveSummary(){if(summary!=null)export(summary.safeJson().getBytes(StandardCharsets.UTF_8),"Save HTTP Summary","Metadata only; no URLs, names, variables, headers or bodies","http-results.json");}
    private void saveResponse(){var current=result;if(current==null)return;if(Messages.showYesNoDialog(project,"Export the original response bytes? They can contain tokens, personal data or other secrets, including values masked in this panel. Only save to a private location. No environment file is copied.","Save Original HTTP Response",Messages.getWarningIcon())!=Messages.YES)return;export(current.body().bytes(),"Save HTTP Response","Original bytes; sensitive data may be present","response.bin");}
    private void export(byte[] bytes,String title,String description,String suggested){var target=FileChooserFactory.getInstance().createSaveFileDialog(new FileSaverDescriptor(title,description),project).save(suggested);if(target==null)return;var path=target.getFile().toPath();ApplicationManager.getApplication().executeOnPooledThread(()->{String message;try{Files.write(path,bytes,StandardOpenOption.CREATE_NEW);message="Saved to the explicitly chosen new file.";}catch(Exception failure){message="Unable to save; existing files are preserved.";}String displayed=message;ApplicationManager.getApplication().invokeLater(()->{if(!project.isDisposed())status.setText(displayed);});});}
    private void help(){Messages.showInfoMessage(project,"Requests run in file order; errors/assertion failures do not stop later requests. Cancel stops the current client request and marks the rest Not run.\n\nBefore METHOD, declare checks:\n# @expect status 200\n# @expect header Content-Type \"application/json\"\n# @expect json /items/0/name \"GoForge\"\nJSON pointers use ~1 for / and ~0 for ~; use \"\" for root. Values are JSON, not JavaScript.\n\nDouble-click a result or choose Next Failure to locate its source line. Known private values and sensitive headers are masked in presentation. Arbitrary server data may contain other secrets. Summary export excludes response data; original response export asks separately.\n\nTLS verification remains enabled; redirects are not followed automatically. No account, response scripts, automatic history or background collection runs.","HTTP Collections and Assertions");}
}

package dev.goforge.http;

import com.google.gson.GsonBuilder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Sequential, bounded collection execution. Errors are rows; cancellation never invents successful rows. */
public final class HttpCollectionRunner {
    @FunctionalInterface interface Sender { CompletableFuture<HttpRequestEngine.Result> send(HttpRequestFile.Request request,Duration timeout,int maximumBytes); }
    public enum Status { RECEIVED, PASSED, FAILED, ERROR, CANCELLED, NOT_RUN }
    public record Row(int requestIndex,String name,String method,int sourceLine,Status status,
                      HttpRequestEngine.Result response,List<HttpAssertion.Outcome> assertions,String detail,
                      String displayBody,String displayHeaders) {
        @Override public String toString(){return "Request "+(requestIndex+1)+": "+status;}
        public boolean failure(){return status==Status.FAILED||status==Status.ERROR;}
    }
    public record Summary(List<Row> rows,boolean cancelled) {
        public String description(){
            long passed=rows.stream().filter(r->r.status()==Status.PASSED).count(),received=rows.stream().filter(r->r.status()==Status.RECEIVED).count(),failed=rows.stream().filter(Row::failure).count(),notRun=rows.stream().filter(r->r.status()==Status.NOT_RUN).count();
            return rows.size()+" requests · "+passed+" requests passed · "+received+" received without assertions · "+failed+" failed/errors · "+notRun+" not run"+(cancelled?" · cancelled":"");
        }
        /** Deliberately excludes URLs, names, request/response bodies, headers and variable values. */
        public String safeJson(){
            var report=new LinkedHashMap<String,Object>();report.put("cancelled",cancelled);report.put("summary",description());
            report.put("privacy","Metadata only: no URLs, request names, variables, headers or bodies. Raw response export is a separate explicit operation.");
            report.put("requests",rows.stream().map(row->{var item=new LinkedHashMap<String,Object>();item.put("requestIndex",row.requestIndex()+1);item.put("sourceLine",row.sourceLine()+1);item.put("method",row.method());item.put("outcome",row.status());item.put("httpStatus",row.response()==null?null:row.response().status());item.put("elapsedMillis",row.response()==null?null:row.response().elapsedMillis());item.put("assertions",row.assertions().stream().map(a->Map.of("sourceLine",a.sourceLine()+1,"passed",a.passed())).toList());return item;}).toList());
            return new GsonBuilder().setPrettyPrinting().create().toJson(report);
        }
    }
    public static final class Run {
        private final Sender sender;private final List<HttpRequestFile.Request> requests;private final List<Integer> selected;
        private final Duration timeout;private final int perResponseBytes,totalBytes;private final HttpPrivacy privacy;private final Consumer<Row> progress;
        private final List<Row> rows=new ArrayList<>();private final CompletableFuture<Summary> completion=new CompletableFuture<>();
        private volatile CompletableFuture<HttpRequestEngine.Result> inFlight;private volatile boolean cancelled;private int next,retained;
        private Run(Sender sender,List<HttpRequestFile.Request> requests,List<Integer> selected,Duration timeout,int perResponseBytes,int totalBytes,HttpPrivacy privacy,Consumer<Row> progress){
            this.sender=sender;this.requests=requests;this.selected=selected;this.timeout=timeout;this.perResponseBytes=perResponseBytes;this.totalBytes=totalBytes;this.privacy=privacy;this.progress=progress;
        }
        public CompletableFuture<Summary> completion(){return completion;}
        public void cancel(){if(completion.isDone())return;cancelled=true;var current=inFlight;if(current!=null)CompletableFuture.runAsync(()->current.cancel(true));}
        private synchronized void next(){
            if(next>=selected.size()){completion.complete(new Summary(List.copyOf(rows),cancelled));return;}
            if(cancelled||retained>=totalBytes){
                while(next<selected.size()){int index=selected.get(next++);add(row(index,Status.NOT_RUN,null,List.of(),cancelled?"Cancelled before sending":"Collection response memory limit reached"));}
                completion.complete(new Summary(List.copyOf(rows),cancelled));return;
            }
            int index=selected.get(next++);var request=requests.get(index);
            try{
                var sent=sender.send(request,timeout,Math.min(perResponseBytes,totalBytes-retained));
                inFlight=sent;
                // cancel() can win while send() is returning, before its future becomes visible.
                if(cancelled)sent.cancel(true);
                sent.whenComplete((response,error)->complete(index,response,error));
            }catch(RuntimeException error){complete(index,null,error);}
        }
        private synchronized void complete(int index,HttpRequestEngine.Result response,Throwable error){
            inFlight=null;
            if(cancelled){add(row(index,Status.CANCELLED,null,List.of(),"Request cancelled; server work already accepted may continue"));next();return;}
            if(error!=null){
                Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
                String type=cause instanceof java.util.concurrent.TimeoutException||cause instanceof java.net.http.HttpTimeoutException?"Timed out":cause instanceof java.util.concurrent.CancellationException?"Transport cancelled":"Transport failed";
                add(row(index,Status.ERROR,null,List.of(),type+"; no URL or exception payload logged"));next();return;
            }
            retained+=response.body().bytes().length;
            var outcomes=requests.get(index).assertions().stream().map(a->a.evaluate(response)).map(a->new HttpAssertion.Outcome(privacy.redact(a.label()),a.passed(),privacy.redact(a.detail()),a.sourceLine())).toList();
            Status status=outcomes.isEmpty()?Status.RECEIVED:outcomes.stream().allMatch(HttpAssertion.Outcome::passed)?Status.PASSED:Status.FAILED;
            var completed=row(index,status,response,outcomes,response.body().truncated()?"Response truncated; JSON assertions cannot pass":"Completed");
            add(cancelled?row(index,Status.CANCELLED,null,List.of(),"Cancelled before result publication"):completed);next();
        }
        private Row row(int index,Status status,HttpRequestEngine.Result response,List<HttpAssertion.Outcome> assertions,String detail){
            var request=requests.get(index);String headerText="",bodyText="";
            if(response!=null){
                var headers=new StringBuilder(response.version()).append(' ').append(response.status()).append('\n');
                response.headers().forEach((key,values)->values.forEach(value->headers.append(privacy.redact(key)).append(": ").append(privacy.header(key,value)).append('\n')));
                headerText=headers.toString();bodyText=privacy.redact(displayBody(response));
            }
            return new Row(index,privacy.redact(request.name()),request.method(),request.startLine(),status,response,assertions,detail,bodyText,headerText);
        }
        private void add(Row row){
            rows.add(row);
            try{progress.accept(row);}catch(RuntimeException disposedObserver){
                // A presentation observer cannot discard recorded results or strand the active transport.
            }
        }
    }
    private HttpCollectionRunner(){}
    public static Run start(HttpRequestEngine engine,List<HttpRequestFile.Request> requests,List<Integer> selected,
                            Duration timeout,int perResponseBytes,int totalBytes,HttpPrivacy privacy,Consumer<Row> progress){
        return start(engine::send,requests,selected,timeout,perResponseBytes,totalBytes,privacy,progress);
    }
    static Run start(Sender sender,List<HttpRequestFile.Request> requests,List<Integer> selected,
                     Duration timeout,int perResponseBytes,int totalBytes,HttpPrivacy privacy,Consumer<Row> progress){
        if(selected.isEmpty()||requests.size()>128||perResponseBytes<1||totalBytes<1||totalBytes>32*1024*1024)throw new IllegalArgumentException("Invalid collection or response limits.");
        var indices=new TreeSet<Integer>();for(int index:selected)if(index<0||index>=requests.size()||!indices.add(index))throw new IllegalArgumentException("Invalid or duplicate request selection.");
        var run=new Run(sender,List.copyOf(requests),List.copyOf(indices),timeout,perResponseBytes,totalBytes,privacy,progress);
        // Even immediate validation errors and presentation work must not run on the caller's EDT.
        CompletableFuture.runAsync(run::next);return run;
    }
    public static String displayBody(HttpRequestEngine.Result response){
        String contentType=response.headers().entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("content-type")).flatMap(e->e.getValue().stream()).findFirst().orElse("application/octet-stream");
        String type=contentType.toLowerCase(Locale.ROOT);
        if(!(type.startsWith("text/")||type.contains("json")||type.contains("xml")||type.contains("javascript")||type.contains("x-www-form-urlencoded")))return "Binary response ("+response.body().bytes().length+" bytes). Use Save Response to export the received bytes.";
        Charset charset=StandardCharsets.UTF_8;var match=java.util.regex.Pattern.compile("(?i)charset=\"?([^;\"\\s]+)").matcher(contentType);
        if(match.find())try{charset=Charset.forName(match.group(1));}catch(IllegalArgumentException ignored){}
        return new String(response.body().bytes(),charset);
    }
}

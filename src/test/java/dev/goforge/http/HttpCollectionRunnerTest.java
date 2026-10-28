package dev.goforge.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Real loopback requests; no external services, credentials or mocked responses. */
public final class HttpCollectionRunnerTest {
    private HttpServer server;private ExecutorService workers;private HttpRequestEngine engine;private String base;
    private final List<String> observed=new CopyOnWriteArrayList<>();private final CountDownLatch release=new CountDownLatch(1);
    @Before public void setup()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);workers=Executors.newCachedThreadPool();server.setExecutor(workers);server.start();engine=new HttpRequestEngine();base="http://127.0.0.1:"+server.getAddress().getPort();}
    @After public void close()throws Exception{release.countDown();engine.close();server.stop(0);workers.shutdownNow();workers.awaitTermination(3,TimeUnit.SECONDS);}
    private void endpoint(String path,int status,String text){server.createContext(path,exchange->{observed.add(path);byte[] bytes=text.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");try{exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});}
    private HttpCollectionRunner.Run run(String source,List<Integer> selected,Duration timeout,int per,int total){var requests=HttpRequestFile.parse(source);return HttpCollectionRunner.start(engine,requests,selected,timeout,per,total,HttpPrivacy.forRun(HttpEnvironment.NONE,requests),row->{});}
    private HttpCollectionRunner.Summary completed(HttpCollectionRunner.Run run)throws Exception{return run.completion().get(6,TimeUnit.SECONDS);}
    @Test public void executesInFileOrderAndRetainsAssertionFailureWhileContinuing()throws Exception{
        endpoint("/one",200,"{\"ok\":true}");endpoint("/two",409,"{}");endpoint("/three",204,"");
        String source="# @expect status 200\n# @expect json /ok true\nGET "+base+"/one\n### conflict\n# @expect status 200\nGET "+base+"/two\n### final\n# @expect status 204\nGET "+base+"/three";
        var result=completed(run(source,List.of(2,0,1),Duration.ofSeconds(2),1024,4096));assertEquals(List.of("/one","/two","/three"),observed);assertEquals(List.of(HttpCollectionRunner.Status.PASSED,HttpCollectionRunner.Status.FAILED,HttpCollectionRunner.Status.PASSED),result.rows().stream().map(HttpCollectionRunner.Row::status).toList());assertTrue(result.rows().get(1).failure());assertEquals(409,result.rows().get(1).response().status());
    }
    @Test public void selectedRequestsOnlyRunOnceInFileOrder()throws Exception{
        endpoint("/first",200,"{}");endpoint("/skip",200,"{}");endpoint("/last",500,"{}");
        String source="GET "+base+"/first\n### omit\nGET "+base+"/skip\n### last\nGET "+base+"/last";
        var result=completed(run(source,List.of(2,0),Duration.ofSeconds(2),1024,4096));assertEquals(List.of("/first","/last"),observed);assertEquals(List.of(0,2),result.rows().stream().map(HttpCollectionRunner.Row::requestIndex).toList());assertEquals(HttpCollectionRunner.Status.RECEIVED,result.rows().get(1).status());assertEquals(500,result.rows().get(1).response().status());
    }
    @Test public void explicitCancelCancelsCurrentAndNeverSendsRemaining()throws Exception{
        var entered=new CountDownLatch(1);server.createContext("/wait",exchange->{observed.add("/wait");try{exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write('x');exchange.getResponseBody().flush();entered.countDown();release.await(4,TimeUnit.SECONDS);}catch(Exception ignored){}finally{exchange.close();}});endpoint("/never",200,"{}");
        var running=run("GET "+base+"/wait\n### later\nGET "+base+"/never",List.of(0,1),Duration.ofSeconds(5),1024,4096);assertTrue(entered.await(3,TimeUnit.SECONDS));running.cancel();var result=completed(running);assertTrue(result.cancelled());assertEquals(List.of(HttpCollectionRunner.Status.CANCELLED,HttpCollectionRunner.Status.NOT_RUN),result.rows().stream().map(HttpCollectionRunner.Row::status).toList());assertEquals(List.of("/wait"),observed);
    }
    @Test public void timeoutIsAnErrorAndLaterRequestStillExecutes()throws Exception{
        server.createContext("/slow",exchange->{try{exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write('x');exchange.getResponseBody().flush();release.await(3,TimeUnit.SECONDS);}catch(Exception ignored){}finally{exchange.close();}});endpoint("/ok",200,"{}");
        var result=completed(run("GET "+base+"/slow\n### later\n# @expect status 200\nGET "+base+"/ok",List.of(0,1),Duration.ofMillis(250),1024,4096));assertEquals(HttpCollectionRunner.Status.ERROR,result.rows().getFirst().status());assertEquals(HttpCollectionRunner.Status.PASSED,result.rows().get(1).status());assertTrue(result.rows().getFirst().detail().contains("Timed out"));
    }
    @Test public void connectionErrorDoesNotExposeUrlAndDoesNotStopCollection()throws Exception{
        int unused;try(var socket=new java.net.ServerSocket(0)){unused=socket.getLocalPort();}endpoint("/ok",200,"{}");
        var result=completed(run("GET http://127.0.0.1:"+unused+"/fixture-private?token=hidden\n### next\nGET "+base+"/ok",List.of(0,1),Duration.ofSeconds(2),1024,4096));assertEquals(HttpCollectionRunner.Status.ERROR,result.rows().getFirst().status());assertEquals(HttpCollectionRunner.Status.RECEIVED,result.rows().get(1).status());assertFalse(result.safeJson().contains("fixture-private"));assertFalse(result.safeJson().contains("127.0.0.1"));
    }
    @Test public void realEnvironmentFileSwitchExpandsHeaderBodyAndMasksEchoesAndExports()throws Exception{
        var received=new CopyOnWriteArrayList<String>();server.createContext("/echo",exchange->{String token=exchange.getRequestHeaders().getFirst("X-Token"),body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);received.add(token+":"+body);byte[] output=("{\"token\":\""+token+"\",\"encoded\":\""+Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8))+"\"}").getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Set-Cookie","unclassified-server-session");try{exchange.sendResponseHeaders(201,output.length);exchange.getResponseBody().write(output);}finally{exchange.close();}});
        Path root=Files.createTempDirectory("goforge-http-private-");try{
            Path normal=root.resolve("env.json"),secret=root.resolve("private.json");Files.writeString(normal,"{\"dev\":{\"base\":\""+base+"\",\"name\":\"one\"},\"test\":{\"base\":\""+base+"\",\"name\":\"two\"}}");Files.writeString(secret,"{\"dev\":{\"token\":\"fixture-private-dev\"},\"test\":{\"token\":\"fixture-private-test\"}}");var environments=HttpEnvironment.load(normal,secret);
            for(String name:List.of("dev","test")){var env=environments.select(name);var requests=HttpRequestFile.parse("### secret title fixture-private-"+name+"\n# @expect status 201\n# @expect header Content-Type \"application/json\"\n# @expect json /token \"{{token}}\"\nPOST {{base}}/echo\nX-Token: {{token}}\n\n{\"name\":\"{{name}}\"}",env.variables());var result=completed(HttpCollectionRunner.start(engine,requests,List.of(0),Duration.ofSeconds(2),1024,4096,HttpPrivacy.forRun(env,requests),row->{}));var row=result.rows().getFirst();assertEquals(HttpCollectionRunner.Status.PASSED,row.status());String presentation=row.name()+row.displayBody()+row.displayHeaders()+row.assertions()+result.safeJson();assertFalse(presentation.contains(env.variables().get("token")));assertFalse(presentation.contains(Base64.getEncoder().encodeToString(env.variables().get("token").getBytes(StandardCharsets.UTF_8))));assertFalse(presentation.contains("unclassified-server-session"));assertFalse(result.safeJson().contains("secret title"));}
            assertEquals(List.of("fixture-private-dev:{\"name\":\"one\"}","fixture-private-test:{\"name\":\"two\"}"),received);
        }finally{try(var files=Files.walk(root)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    @Test public void collectionMemoryLimitSkipsFurtherRequestsWithoutClaimingPass()throws Exception{
        endpoint("/full",200,"12345");endpoint("/never",200,"{}");var result=completed(run("GET "+base+"/full\n### next\nGET "+base+"/never",List.of(0,1),Duration.ofSeconds(2),5,5));assertEquals(HttpCollectionRunner.Status.RECEIVED,result.rows().getFirst().status());assertEquals(HttpCollectionRunner.Status.NOT_RUN,result.rows().get(1).status());assertFalse(result.cancelled());assertEquals(List.of("/full"),observed);
    }
    @Test public void missingVariableBlocksWholeParsedCollectionBeforeAnyNetworkOperation(){
        endpoint("/safe",200,"{}");assertThrows(IllegalArgumentException.class,()->HttpRequestFile.parse("GET "+base+"/safe\n### unresolved\nGET {{missing}}"));assertTrue(observed.isEmpty());
    }
    @Test public void cancellationBeforeFuturePublicationCancelsNewlyReturnedFutureDeterministically()throws Exception{
        var entered=new CountDownLatch(1);var returnFuture=new CountDownLatch(1);var pending=new CompletableFuture<HttpRequestEngine.Result>();var calls=new AtomicInteger();
        HttpCollectionRunner.Sender sender=(request,timeout,limit)->{calls.incrementAndGet();entered.countDown();try{if(!returnFuture.await(3,TimeUnit.SECONDS))throw new IllegalStateException("Controlled publication gate timed out");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}return pending;};
        var requests=HttpRequestFile.parse("GET http://localhost/first\n### later\nGET http://localhost/never");
        var running=HttpCollectionRunner.start(sender,requests,List.of(0,1),Duration.ofSeconds(10),1024,4096,new HttpPrivacy(List.of()),row->{});
        try{assertTrue(entered.await(3,TimeUnit.SECONDS));running.cancel();assertFalse("The future has not been returned/published yet",pending.isCancelled());returnFuture.countDown();var result=completed(running);assertTrue(pending.isCancelled());assertEquals(1,calls.get());assertTrue(result.cancelled());assertEquals(List.of(HttpCollectionRunner.Status.CANCELLED,HttpCollectionRunner.Status.NOT_RUN),result.rows().stream().map(HttpCollectionRunner.Row::status).toList());}
        finally{returnFuture.countDown();pending.cancel(true);}
    }
    @Test public void cancellingCompletedCollectionDoesNotRewriteItsResults()throws Exception{
        endpoint("/ok",200,"{}");var running=run("# @expect status 200\nGET "+base+"/ok",List.of(0),Duration.ofSeconds(2),1024,4096);var result=completed(running);running.cancel();assertSame(result,running.completion().get());assertFalse(result.cancelled());assertEquals(HttpCollectionRunner.Status.PASSED,result.rows().getFirst().status());
    }
}

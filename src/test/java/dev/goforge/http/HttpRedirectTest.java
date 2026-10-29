package dev.goforge.http;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import org.junit.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real owned loopback HTTP servers; no remote services, external credentials or global settings. */
public final class HttpRedirectTest {
    private HttpServer server, other;
    private ExecutorService workers;
    private HttpRequestEngine engine;
    private String base, foreign;
    private final BlockingQueue<Seen> received = new LinkedBlockingQueue<>();
    record Seen(String method, byte[] body, Map<String,List<String>> headers, String path) {
        String header(String name) { return headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(name)).flatMap(e->e.getValue().stream()).findFirst().orElse(null); }
    }
    @Before public void before() throws Exception {
        workers=Executors.newCachedThreadPool(); engine=new HttpRequestEngine();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);other=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(workers);other.setExecutor(workers);server.start();other.start();
        base="http://127.0.0.1:"+server.getAddress().getPort();foreign="http://127.0.0.1:"+other.getAddress().getPort();
        server.createContext("/final",this::record);other.createContext("/final",this::record);
    }
    @After public void after() throws Exception { engine.close();server.stop(0);other.stop(0);workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS)); }
    private void record(HttpExchange exchange) throws java.io.IOException {
        received.add(new Seen(exchange.getRequestMethod(),exchange.getRequestBody().readAllBytes(),Map.copyOf(exchange.getRequestHeaders()),exchange.getRequestURI().toString()));
        exchange.getResponseHeaders().set("Content-Type","text/plain");
        byte[] data="final".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200,exchange.getRequestMethod().equals("HEAD")?-1:data.length);
        if(!exchange.getRequestMethod().equals("HEAD"))exchange.getResponseBody().write(data);exchange.close();
    }
    private void redirect(String path,int status,String location) { server.createContext(path,e->{e.getRequestBody().readAllBytes();e.getResponseHeaders().set("Location",location);e.sendResponseHeaders(status,-1);e.close();}); }
    private CompletableFuture<HttpRequestEngine.Result> send(String text,Duration timeout) { return engine.send(HttpRequestFile.parse(text).getFirst(),timeout,1024,HttpRequestEngine.RedirectPolicy.FOLLOW); }
    private HttpRequestEngine.Result send(String text) throws Exception { return send(text,Duration.ofSeconds(5)).get(7,TimeUnit.SECONDS); }
    private String rejected(String text) throws Exception {
        var e=assertThrows(ExecutionException.class,()->send(text,Duration.ofSeconds(5)).get(7,TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof HttpRedirects.Refused);assertFalse(e.getCause().getMessage().contains(base));return e.getCause().getMessage();
    }
    @Test public void defaultSendFollowsRedirectAndPerRequestOptOutStillReturnsOriginalResponse() throws Exception {
        redirect("/default",302,"/final");
        var request=HttpRequestFile.parse("GET "+base+"/default").getFirst();
        var result=engine.send(request,Duration.ofSeconds(5),1024).get(7,TimeUnit.SECONDS);
        assertEquals(200,result.status());assertEquals(1,result.redirects().size());
        assertEquals("/final",received.poll(1,TimeUnit.SECONDS).path());
        for(String prefix:List.of("#","//")) {
            var disabled=HttpRequestFile.parse(prefix+" @no-redirect\nGET "+base+"/default").getFirst();
            var original=engine.send(disabled,Duration.ofSeconds(5),1024).get(7,TimeUnit.SECONDS);
            assertEquals(302,original.status());assertTrue(original.redirects().isEmpty());
        }
        assertNull(received.poll(150,TimeUnit.MILLISECONDS));
    }
    @Test public void followsRelativeChainAndReturnsOnlyFinalBodyWithSafeHopMetadata() throws Exception {
        redirect("/start",301,"/middle");redirect("/middle",308,"final#not-sent");
        var result=send("GET "+base+"/start");assertEquals(200,result.status());assertEquals("final",new String(result.body().bytes(),StandardCharsets.UTF_8));
        assertEquals(List.of(301,308),result.redirects().stream().map(HttpRequestEngine.Redirect::status).toList());assertEquals("/final",received.poll(1,TimeUnit.SECONDS).path());
        assertFalse(result.redirects().toString().contains(base));
    }
    @Test public void post301And302BecomeGetAndDropBodySpecificHeaders() throws Exception {
        for(int status:List.of(301,302)) {
            redirect("/s"+status,status,"/final");
            send("POST "+base+"/s"+status+"\nContent-Type: text/plain\nContent-Encoding: identity\nContent-Language: en\nContent-Range: bytes 0-11/12\nDigest: fixture\nAuthorization: Bearer local\n\nfixture-body");
            var request=received.poll(1,TimeUnit.SECONDS);assertEquals("GET",request.method());assertEquals(0,request.body().length);
            for(String h:List.of("Content-Type","Content-Encoding","Content-Language","Content-Range","Digest"))assertNull(h,request.header(h));
            assertEquals("Bearer local",request.header("Authorization"));
        }
    }
    @Test public void putAndPatch303BecomeGetButHeadStaysHead() throws Exception {
        redirect("/see",303,"/final");
        for(String method:List.of("PUT","PATCH","HEAD")) {
            send(method+" "+base+"/see\nContent-Type: text/plain\n\nbody");var r=received.poll(1,TimeUnit.SECONDS);
            assertEquals(method.equals("HEAD")?"HEAD":"GET",r.method());assertEquals(0,r.body().length);assertNull(r.header("Content-Type"));
        }
    }
    @Test public void sameOrigin307And308RetainExactMethodBodyAndHeaders() throws Exception {
        for(int status:List.of(307,308)) {
            redirect("/keep"+status,status,"/final");send("PATCH "+base+"/keep"+status+"\nContent-Type: application/json\nAuthorization: Bearer local\nCookie: explicit=local\n\n{\"unicode\":\"你好\"}");
            var r=received.poll(1,TimeUnit.SECONDS);assertEquals("PATCH",r.method());assertArrayEquals("{\"unicode\":\"你好\"}".getBytes(StandardCharsets.UTF_8),r.body());assertEquals("application/json",r.header("Content-Type"));assertEquals("Bearer local",r.header("Authorization"));assertEquals("explicit=local",r.header("Cookie"));
        }
    }
    @Test public void put301RetainsBodyUnlikePost() throws Exception {
        redirect("/put",301,"/final");send("PUT "+base+"/put\n\nbody");var r=received.poll(1,TimeUnit.SECONDS);assertEquals("PUT",r.method());assertArrayEquals("body".getBytes(StandardCharsets.UTF_8),r.body());
    }
    @Test public void crossOriginDropsAllCallerHeadersAndNeverRestoresThemOnReturn() throws Exception {
        redirect("/out",302,foreign+"/back");other.createContext("/back",e->{recordHeadersOnly(e);e.getResponseHeaders().set("Location",base+"/final");e.getResponseHeaders().set("Set-Cookie","ambient=never");e.sendResponseHeaders(302,-1);e.close();});
        var result=send("GET "+base+"/out\nAuthorization: Bearer local\nCookie: explicit=local\nProxy-Authorization: Basic local\nX-Api-Key: local\nX-Custom-Credential: local\nOrigin: http://private.invalid\nReferer: http://private.invalid\nAccept: application/json");
        assertEquals(2,result.redirects().size());assertTrue(result.redirects().stream().allMatch(HttpRequestEngine.Redirect::crossOrigin));
        for(int i=0;i<2;i++){var r=received.poll(1,TimeUnit.SECONDS);for(String h:List.of("Authorization","Cookie","Proxy-Authorization","X-Api-Key","X-Custom-Credential","Origin","Referer","Accept"))assertNull(h,r.header(h));}
    }
    private void recordHeadersOnly(HttpExchange e)throws java.io.IOException {received.add(new Seen(e.getRequestMethod(),e.getRequestBody().readAllBytes(),Map.copyOf(e.getRequestHeaders()),e.getRequestURI().toString()));}
    @Test public void crossOriginPost302MayBecomeBodylessGet() throws Exception {
        redirect("/out",302,foreign+"/final");send("POST "+base+"/out\nContent-Type: text/plain\nAuthorization: secret\n\nbody");var r=received.poll(1,TimeUnit.SECONDS);assertEquals("GET",r.method());assertEquals(0,r.body().length);assertNull(r.header("Content-Type"));assertNull(r.header("Authorization"));
    }
    @Test public void crossOriginPreservedBodyIsRefusedBeforeDestinationRequest() throws Exception {
        redirect("/out",307,foreign+"/final");assertTrue(rejected("POST "+base+"/out\n\nbody-secret").startsWith("Cross-origin"));assertNull(received.poll(150,TimeUnit.MILLISECONDS));
    }
    @Test public void requestLocalNoRedirectOverridesFollowForBothCommentForms() throws Exception {
        redirect("/start",302,"/final");for(String prefix:List.of("#","//")){var r=send(prefix+" @no-redirect\nGET "+base+"/start");assertEquals(302,r.status());assertTrue(r.redirects().isEmpty());}assertTrue(received.isEmpty());
    }
    @Test public void repeatedUrlWithChangedPostMethodIsNotAFalseLoop() throws Exception {
        server.createContext("/same",e->{if(e.getRequestMethod().equals("POST")){e.getRequestBody().readAllBytes();e.getResponseHeaders().set("Location","/same");e.sendResponseHeaders(303,-1);e.close();}else record(e);});
        assertEquals(200,send("POST "+base+"/same\n\nbody").status());assertEquals("GET",received.poll(1,TimeUnit.SECONDS).method());
    }
    @Test public void detectsLoopAndCapsUniqueHopsAtFive() throws Exception {
        var count=new AtomicInteger();server.createContext("/loop",e->{count.incrementAndGet();e.getResponseHeaders().set("Location","/loop#fragment");e.sendResponseHeaders(302,-1);e.close();});
        assertEquals("Redirect loop detected.",rejected("GET "+base+"/loop"));assertEquals(1,count.get());
        count.set(0);server.createContext("/chain",e->{int n=count.incrementAndGet();e.getResponseHeaders().set("Location","/chain?hop="+n);e.sendResponseHeaders(302,-1);e.close();});
        assertEquals("Redirect limit reached (maximum 5).",rejected("GET "+base+"/chain"));assertEquals(6,count.get());
    }
    @Test public void exactlyFiveRedirectsSucceed() throws Exception {
        for(int n=0;n<5;n++)redirect("/n"+n,302,n==4?"/final":"/n"+(n+1));assertEquals(5,send("GET "+base+"/n0").redirects().size());
    }
    @Test public void missingLocationAndOtherStatusesRemainOrdinaryResponses() throws Exception {
        for(int status:List.of(302,304,305,300)){server.createContext("/ordinary"+status,e->{if(status!=302)e.getResponseHeaders().set("Location","/final");e.sendResponseHeaders(status,-1);e.close();});assertEquals(status,send("GET "+base+"/ordinary"+status).status());}assertTrue(received.isEmpty());
    }
    @Test public void rejectsAmbiguousInvalidSchemeAndCredentialLocationsWithoutLeakingValues() throws Exception {
        for(var pair:Map.of("scheme","file:///private/fixture","creds","http://secret:password@localhost/","invalid","http://[").entrySet()){redirect("/bad"+pair.getKey(),302,pair.getValue());assertEquals("Redirect Location is not a valid HTTP(S) target.",rejected("GET "+base+"/bad"+pair.getKey()));}
        server.createContext("/ambiguous",e->{e.getResponseHeaders().add("Location","/one");e.getResponseHeaders().add("Location","/two");e.sendResponseHeaders(302,-1);e.close();});assertTrue(rejected("GET "+base+"/ambiguous").startsWith("Redirect has multiple"));assertTrue(received.isEmpty());
    }
    @Test public void queryOnlyReferenceKeepsCurrentPathAndEscapedOctets() throws Exception {
        server.createContext("/query/item",e->{if(e.getRequestURI().getRawQuery().equals("old")){e.getResponseHeaders().set("Location","?new=%2F#ignored");e.sendResponseHeaders(302,-1);e.close();}else record(e);});
        send("GET "+base+"/query/item?old");assertEquals("/query/item?new=%2F",received.poll(1,TimeUnit.SECONDS).path());
    }
    @Test public void policyRejectsDowngradeAndNormalizesDefaultOriginPorts() {
        var source=new HttpRedirects.Request(URI.create("https://Example.test/path"),"GET",List.of(new HttpRequestFile.Header("Authorization","local")),new byte[0],null);
        assertThrows(HttpRedirects.Refused.class,()->HttpRedirects.next(source,302,List.of("http://example.test/next")));
        var next=HttpRedirects.next(source,302,List.of("https://EXAMPLE.test:443/next"));assertFalse(next.crossOrigin());assertEquals(source.headers(),next.request().headers());
    }
    @Test public void cancelledCurrentHopDoesNotSendItsLaterRedirect() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        redirect("/first",302,"/wait");server.createContext("/wait",e->{entered.countDown();try{release.await(4,TimeUnit.SECONDS);e.getResponseHeaders().set("Location","/final");e.sendResponseHeaders(302,-1);}catch(Exception ignored){}finally{e.close();}});
        try{var pending=send("GET "+base+"/first",Duration.ofSeconds(5));assertTrue(entered.await(2,TimeUnit.SECONDS));assertTrue(pending.cancel(true));release.countDown();assertTrue(pending.isCancelled());assertNull(received.poll(250,TimeUnit.MILLISECONDS));}finally{release.countDown();}
    }
    @Test public void deadlineCoversWholeChainRatherThanRestartingForEachHop() throws Exception {
        var count=new AtomicInteger();server.createContext("/delay",e->{int n=count.incrementAndGet();try{Thread.sleep(150);e.getResponseHeaders().set("Location","/delay?hop="+n);e.sendResponseHeaders(302,-1);}catch(Exception ignored){}finally{e.close();}});
        var error=assertThrows(ExecutionException.class,()->send("GET "+base+"/delay",Duration.ofMillis(380)).get(3,TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof TimeoutException || error.getCause() instanceof java.net.http.HttpTimeoutException);assertTrue(count.get()<6);
    }
    @Test public void redirectBodyDoesNotBlockFollowingAndFinalBodyLimitStillApplies() throws Exception {
        var release=new CountDownLatch(1);server.createContext("/body",e->{try{e.getResponseHeaders().set("Location","/large");e.sendResponseHeaders(302,0);e.getResponseBody().flush();release.await(4,TimeUnit.SECONDS);}catch(Exception ignored){}finally{e.close();}});
        server.createContext("/large",e->{try{e.sendResponseHeaders(200,2048);e.getResponseBody().write(new byte[2048]);}finally{e.close();}});
        try{var r=send("GET "+base+"/body",Duration.ofSeconds(2)).get(3,TimeUnit.SECONDS);assertEquals(1024,r.body().bytes().length);assertTrue(r.body().truncated());assertEquals(1,r.redirects().size());}finally{release.countDown();}
    }
    @Test public void closingEngineDuringRedirectChainCancelsCurrentHop() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);redirect("/first",302,"/wait");
        server.createContext("/wait",e->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException ignored){}finally{e.close();}});
        try{var pending=send("GET "+base+"/first",Duration.ofSeconds(5));assertTrue(entered.await(2,TimeUnit.SECONDS));engine.close();assertTrue(pending.isCancelled());}finally{release.countDown();}
    }
    @Test public void cancellingWhileReadingRedirectedFinalBodySettlesWithoutPublishingPartialResult() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var ended=new CountDownLatch(1);
        redirect("/first",302,"/body-wait");
        server.createContext("/body-wait",e->{try{e.sendResponseHeaders(200,0);e.getResponseBody().write('x');e.getResponseBody().flush();entered.countDown();release.await(3,TimeUnit.SECONDS);}catch(Exception ignored){}finally{e.close();ended.countDown();}});
        try{var pending=send("GET "+base+"/first",Duration.ofSeconds(5));assertTrue(entered.await(2,TimeUnit.SECONDS));assertFalse(pending.isDone());assertTrue(pending.cancel(true));assertThrows(CancellationException.class,pending::join);release.countDown();assertTrue(ended.await(2,TimeUnit.SECONDS));assertTrue(pending.isCancelled());}finally{release.countDown();}
    }
    @Test public void simultaneousSendAndCloseNeverLeaveAnUnsettledChain() throws Exception {
        for(int i=0;i<12;i++)try(var concurrent=new HttpRequestEngine()){
            var ready=new CyclicBarrier(2);var request=HttpRequestFile.parse("GET "+base+"/final").getFirst();
            var submitted=workers.submit(()->{ready.await(2,TimeUnit.SECONDS);try{return concurrent.send(request,Duration.ofSeconds(3),1024,HttpRequestEngine.RedirectPolicy.FOLLOW);}catch(IllegalStateException alreadyClosed){return null;}});
            var closure=workers.submit(()->{ready.await(2,TimeUnit.SECONDS);concurrent.close();return null;});closure.get(3,TimeUnit.SECONDS);
            var pending=submitted.get(3,TimeUnit.SECONDS);if(pending!=null){try{pending.get(3,TimeUnit.SECONDS);}catch(CancellationException|ExecutionException stopped){}assertTrue(pending.isDone());}
            assertThrows(IllegalStateException.class,()->concurrent.send(request,Duration.ofSeconds(1),1024));
        }
    }

}

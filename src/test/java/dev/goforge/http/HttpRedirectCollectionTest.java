package dev.goforge.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class HttpRedirectCollectionTest {
    @Test public void finalAssertionsAndPerRequestOptOutUseSameCollectionSelection() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var reached=new AtomicInteger();
        server.createContext("/start",e->{e.getResponseHeaders().set("Location","/end");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/end",e->{reached.incrementAndGet();e.sendResponseHeaders(204,-1);e.close();});server.start();
        try(var engine=new HttpRequestEngine()) {
            String url="http://127.0.0.1:"+server.getAddress().getPort()+"/start";
            var requests=HttpRequestFile.parse("# @expect status 204\nGET "+url+"\n### keep redirect\n// @no-redirect\n# @expect status 302\nGET "+url);
            var run=HttpCollectionRunner.start(engine,requests,List.of(0,1),Duration.ofSeconds(5),1024,4096,new HttpPrivacy(List.of()),row->{},HttpRequestEngine.RedirectPolicy.FOLLOW);
            var summary=run.completion().get(7,TimeUnit.SECONDS);assertEquals(1,reached.get());
            assertTrue(summary.rows().stream().allMatch(r->r.status()==HttpCollectionRunner.Status.PASSED));
            assertTrue(summary.rows().getFirst().displayHeaders().contains("Redirect 302: GET → GET"));
            assertTrue(summary.rows().get(1).response().redirects().isEmpty());assertFalse(summary.safeJson().contains(url));
        }finally{server.stop(0);}
    }
    @Test public void redirectRefusalIsUsefulSafeErrorAndLaterRequestsStillRun() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/loop",e->{e.getResponseHeaders().set("Location","/loop?token=private");e.sendResponseHeaders(302,-1);e.close();});server.createContext("/ok",e->{e.sendResponseHeaders(200,-1);e.close();});server.start();
        try(var engine=new HttpRequestEngine()) {
            String base="http://127.0.0.1:"+server.getAddress().getPort();var requests=HttpRequestFile.parse("GET "+base+"/loop?token=private\n### later\nGET "+base+"/ok");
            var summary=HttpCollectionRunner.start(engine,requests,List.of(0,1),Duration.ofSeconds(5),1024,4096,new HttpPrivacy(List.of()),row->{},HttpRequestEngine.RedirectPolicy.FOLLOW).completion().get(7,TimeUnit.SECONDS);
            assertEquals(HttpCollectionRunner.Status.ERROR,summary.rows().getFirst().status());assertEquals("Redirect loop detected.",summary.rows().getFirst().detail());assertEquals(200,summary.rows().get(1).response().status());assertFalse(summary.safeJson().contains("private"));assertFalse(summary.rows().getFirst().detail().contains("token"));
        }finally{server.stop(0);}
    }
    @Test public void collectionCancelAtRedirectedHopLeavesFollowingSelectionNotRun() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var workers=Executors.newCachedThreadPool();server.setExecutor(workers);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var later=new AtomicInteger();
        server.createContext("/start",e->{e.getResponseHeaders().set("Location","/wait");e.sendResponseHeaders(302,-1);e.close();});server.createContext("/wait",e->{entered.countDown();try{release.await(4,TimeUnit.SECONDS);}catch(InterruptedException ignored){}finally{e.close();}});server.createContext("/later",e->{later.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});server.start();
        try(var engine=new HttpRequestEngine()){
            String base="http://127.0.0.1:"+server.getAddress().getPort();var requests=HttpRequestFile.parse("GET "+base+"/start\n### later\nGET "+base+"/later");var run=HttpCollectionRunner.start(engine,requests,List.of(0,1),Duration.ofSeconds(5),1024,4096,new HttpPrivacy(List.of()),row->{},HttpRequestEngine.RedirectPolicy.FOLLOW);
            assertTrue(entered.await(2,TimeUnit.SECONDS));run.cancel();var summary=run.completion().get(3,TimeUnit.SECONDS);assertTrue(summary.cancelled());assertEquals(HttpCollectionRunner.Status.CANCELLED,summary.rows().getFirst().status());assertEquals(HttpCollectionRunner.Status.NOT_RUN,summary.rows().get(1).status());assertEquals(0,later.get());
        }finally{release.countDown();server.stop(0);workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
}

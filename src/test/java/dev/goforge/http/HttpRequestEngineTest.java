package dev.goforge.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real loopback HTTP exchanges, with no external network or user services. */
public final class HttpRequestEngineTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService workers;
    private HttpRequestEngine engine;
    private String base;
    @Before public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool(); server.setExecutor(workers); server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort(); engine = new HttpRequestEngine();
    }
    @After public void close() throws Exception {
        engine.close(); server.stop(0); workers.shutdownNow(); workers.awaitTermination(3, TimeUnit.SECONDS);
    }
    @Test public void realPostPreservesBodyHeadersAndErrorStatus() throws Exception {
        server.createContext("/echo", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            assertEquals("POST", exchange.getRequestMethod()); assertEquals("value", exchange.getRequestHeaders().getFirst("X-Test"));
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(422, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        var result = send("POST " + base + "/echo\nX-Test: value\n\n{\"message\":\"你好\"}", 1024);
        assertEquals(422, result.status());
        assertEquals("{\"message\":\"你好\"}", new String(result.body().bytes(), StandardCharsets.UTF_8));
        assertFalse(result.body().truncated());
    }
    @Test public void redirectIsReturnedWithoutReplayingCredentials() throws Exception {
        var followed = new AtomicInteger();
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().set("Location", base + "/destination"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.createContext("/destination", exchange -> { followed.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        var result = engine.send(HttpRequestFile.parse("GET " + base + "/redirect\nAuthorization: Bearer fixture-only").getFirst(), Duration.ofSeconds(3), 1024, HttpRequestEngine.RedirectPolicy.NEVER).get(5, TimeUnit.SECONDS);
        assertEquals(302, result.status()); assertEquals(0, followed.get());
    }
    @Test public void bodyLimitCancelsRemainingBytesAndReportsTruncation() throws Exception {
        server.createContext("/large", exchange -> {
            byte[] bytes = new byte[16384]; java.util.Arrays.fill(bytes, (byte) 'x');
            try { exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
            finally { exchange.close(); }
        });
        var result = send("GET " + base + "/large", 128);
        assertEquals(128, result.body().bytes().length); assertTrue(result.body().truncated());
    }
    @Test public void exactLimitIsNotReportedTruncated() throws Exception {
        server.createContext("/exact", exchange -> { exchange.sendResponseHeaders(200, 128); exchange.getResponseBody().write(new byte[128]); exchange.close(); });
        var result = send("GET " + base + "/exact", 128);
        assertEquals(128, result.body().bytes().length); assertFalse(result.body().truncated());
    }
    @Test public void deadlineCoversAStalledBodyAfterHeaders() throws Exception {
        server.createContext("/slow", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write('x'); exchange.getResponseBody().flush();
                try { Thread.sleep(5000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } finally { exchange.close(); }
        });
        var future = engine.send(HttpRequestFile.parse("GET " + base + "/slow").getFirst(), Duration.ofMillis(200), 1024);
        var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof java.util.concurrent.TimeoutException || failure.getCause() instanceof java.net.http.HttpTimeoutException);
    }
    @Test public void explicitCancellationAndCloseCancelPendingRequests() throws Exception {
        server.createContext("/wait", exchange -> {
            try { Thread.sleep(5000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } finally { exchange.close(); }
        });
        var request = HttpRequestFile.parse("GET " + base + "/wait").getFirst();
        var cancelled = engine.send(request, Duration.ofSeconds(10), 1024);
        assertTrue(cancelled.cancel(true)); assertTrue(cancelled.isCancelled());
        var closed = engine.send(request, Duration.ofSeconds(10), 1024); engine.close();
        assertTrue(closed.isCancelled());
        assertThrows(IllegalStateException.class, () -> engine.send(request, Duration.ofSeconds(1), 1024));
    }
    private HttpRequestEngine.Result send(String source, int maximumBytes) throws Exception {
        return engine.send(HttpRequestFile.parse(source).getFirst(), Duration.ofSeconds(5), maximumBytes).get(7, TimeUnit.SECONDS);
    }
}

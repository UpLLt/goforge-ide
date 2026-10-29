package dev.goforge.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Default bounded redirects; no ambient credentials, cookie jar, scripts, or disk history. Normal TLS verification. */
public final class HttpRequestEngine implements AutoCloseable {
    public record Body(byte[] bytes, boolean truncated) { }
    public enum RedirectPolicy { NEVER, FOLLOW }
    /** No URLs or header values: redirect presentation must not introduce an unredacted history. */
    public record Redirect(int status, String method, String nextMethod, boolean crossOrigin) { }
    public record Result(int status, Map<String, List<String>> headers, Body body, long elapsedMillis, String version,
                         List<Redirect> redirects) {
        public Result { redirects = List.copyOf(redirects); }
        public Result(int status, Map<String, List<String>> headers, Body body, long elapsedMillis, String version) {
            this(status, headers, body, elapsedMillis, version, List.of());
        }
    }
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final java.util.concurrent.ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "GoForge HTTP deadline"); thread.setDaemon(true); return thread;
    });
    private final Set<CompletableFuture<?>> running = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public CompletableFuture<Result> send(HttpRequestFile.Request source, Duration timeout, int maximumBytes) {
        return send(source, timeout, maximumBytes, RedirectPolicy.FOLLOW);
    }

    public CompletableFuture<Result> send(HttpRequestFile.Request source, Duration timeout, int maximumBytes,
                                          RedirectPolicy redirects) {
        if (closed) throw new IllegalStateException("HTTP client is closed.");
        if (timeout.isNegative() || timeout.isZero() || maximumBytes < 1 || maximumBytes > 16 * 1024 * 1024)
            throw new IllegalArgumentException("Invalid HTTP limits.");
        java.util.Objects.requireNonNull(redirects);
        var request = new HttpRedirects.Request(source.uri(), source.method(), List.copyOf(source.headers()),
                source.body().clone(), source.version());
        var chain = new Chain(timeout, maximumBytes, redirects == RedirectPolicy.FOLLOW && !source.noRedirect());
        running.add(chain.output);
        chain.output.whenComplete((value, error) -> {
            running.remove(chain.output);
            if (error != null) chain.cancelCurrent();
        });
        try {
            var deadline = timer.schedule(() -> chain.output.completeExceptionally(
                    new TimeoutException("HTTP request timed out.")), timeout.toNanos(), TimeUnit.NANOSECONDS);
            chain.output.whenComplete((value, error) -> deadline.cancel(false));
            chain.send(request);
        } catch (java.util.concurrent.RejectedExecutionException disposed) {
            running.remove(chain.output); chain.output.cancel(true);
        } catch (RuntimeException failure) {
            chain.output.completeExceptionally(failure);
        }
        if (closed) chain.output.cancel(true);
        return chain.output;
    }

    private final class Chain {
        final CompletableFuture<Result> output = new CompletableFuture<>();
        private final Duration timeout;
        private final int maximumBytes;
        private final boolean follow;
        private final long start = System.nanoTime();
        private final List<Redirect> hops = new ArrayList<>();
        private final Set<String> visited = new HashSet<>();
        private CompletableFuture<HttpResponse<Body>> current;
        Chain(Duration timeout, int maximumBytes, boolean follow) {
            this.timeout = timeout; this.maximumBytes = maximumBytes; this.follow = follow;
        }
        synchronized void cancelCurrent() { if (current != null) current.cancel(true); }
        synchronized void send(HttpRedirects.Request request) {
            if (output.isDone()) return;
            if (closed) { output.cancel(true); return; }
            if (!visited.add(HttpRedirects.key(request))) { output.completeExceptionally(new HttpRedirects.Refused("Redirect loop detected.")); return; }
            try {
                var builder = HttpRequest.newBuilder(request.uri()).timeout(timeout).method(request.method(),
                        request.body().length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(request.body()));
                if (request.version() != null) builder.version(request.version().equals("2") ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1);
                for (var header : request.headers()) builder.header(header.name(), header.value());
                current = client.sendAsync(builder.build(), info -> follow && HttpRedirects.supported(info.statusCode())
                        && !info.headers().allValues("location").isEmpty() ? new RedirectBody() : new LimitedBody(maximumBytes));
                // A cancellation can win while sendAsync is creating the underlying future.
                if (output.isDone()) { current.cancel(true); return; }
                current.whenComplete((response, error) -> received(request, response, error));
            } catch (RuntimeException failure) { output.completeExceptionally(failure); }
        }
        private synchronized void received(HttpRedirects.Request request, HttpResponse<Body> response, Throwable error) {
            if (output.isDone()) return;
            if (error != null) { output.completeExceptionally(error); return; }
            try {
                var locations = response.headers().allValues("location");
                if (follow && HttpRedirects.supported(response.statusCode()) && !locations.isEmpty()) {
                    if (hops.size() >= HttpRedirects.MAXIMUM) throw new HttpRedirects.Refused("Redirect limit reached (maximum 5).");
                    var next = HttpRedirects.next(request, response.statusCode(), locations);
                    hops.add(new Redirect(response.statusCode(), request.method(), next.request().method(), next.crossOrigin()));
                    send(next.request());
                } else output.complete(new Result(response.statusCode(), response.headers().map(), response.body(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), response.version().toString(), hops));
            } catch (RuntimeException failure) { output.completeExceptionally(failure); }
        }
    }

    /** A redirect does not need its body. Cancel it immediately rather than retain or await it. */
    private static final class RedirectBody implements HttpResponse.BodySubscriber<Body> {
        @Override public CompletionStage<Body> getBody() { return CompletableFuture.completedFuture(new Body(new byte[0], false)); }
        @Override public void onSubscribe(Flow.Subscription value) { value.cancel(); }
        @Override public void onNext(List<ByteBuffer> values) { }
        @Override public void onError(Throwable error) { }
        @Override public void onComplete() { }
    }

    @Override public void close() {
        closed = true;
        for (var future : running) future.cancel(true);
        timer.shutdownNow();
        client.shutdownNow();
    }

    static final class LimitedBody implements HttpResponse.BodySubscriber<Body> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<Body> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<Body> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) { value.cancel(); return; }
            subscription = value;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) return;
            for (var buffer : items) {
                int count = Math.min(buffer.remaining(), limit - bytes.size());
                byte[] part = new byte[count]; buffer.get(part); bytes.writeBytes(part);
                if (buffer.hasRemaining()) {
                    result.complete(new Body(bytes.toByteArray(), true)); subscription.cancel(); return;
                }
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(new Body(bytes.toByteArray(), false)); }
    }
}

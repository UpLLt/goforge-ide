package dev.goforge.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** No redirects, ambient credentials, cookies, scripts, or disk history. TLS uses normal JDK verification. */
public final class HttpRequestEngine implements AutoCloseable {
    public record Body(byte[] bytes, boolean truncated) { }
    public record Result(int status, Map<String, List<String>> headers, Body body, long elapsedMillis, String version) { }
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final java.util.concurrent.ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "GoForge HTTP deadline"); thread.setDaemon(true); return thread;
    });
    private final Set<CompletableFuture<?>> running = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public CompletableFuture<Result> send(HttpRequestFile.Request source, Duration timeout, int maximumBytes) {
        if (closed) throw new IllegalStateException("HTTP client is closed.");
        if (timeout.isNegative() || timeout.isZero() || maximumBytes < 1 || maximumBytes > 16 * 1024 * 1024)
            throw new IllegalArgumentException("Invalid HTTP limits.");
        var builder = HttpRequest.newBuilder(source.uri()).timeout(timeout)
                .method(source.method(), source.body().length == 0 ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(source.body()));
        if (source.version() != null) builder.version(source.version().equals("2")
                ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1);
        for (var header : source.headers()) builder.header(header.name(), header.value());
        long start = System.nanoTime();
        var response = client.sendAsync(builder.build(), info -> new LimitedBody(maximumBytes));
        var output = new CompletableFuture<Result>();
        running.add(output);
        final java.util.concurrent.ScheduledFuture<?> deadline;
        try {
            deadline = timer.schedule(() -> {
                if (output.completeExceptionally(new TimeoutException("HTTP request timed out."))) response.cancel(true);
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException disposed) {
            response.cancel(true); running.remove(output); output.cancel(true); return output;
        }
        output.whenComplete((value, error) -> {
            deadline.cancel(false); running.remove(output);
            if (output.isCancelled()) response.cancel(true);
        });
        response.whenComplete((value, error) -> {
            if (error != null) output.completeExceptionally(error);
            else output.complete(new Result(value.statusCode(), value.headers().map(), value.body(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), value.version().toString()));
        });
        if (closed) output.cancel(true);
        return output;
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

package dev.goforge.http;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Explicit bounded redirect policy. Never delegates header forwarding to the JDK. */
final class HttpRedirects {
    static final int MAXIMUM = 5;
    static final Set<String> BODY_HEADERS = Set.of("content-length", "content-type", "content-encoding",
            "content-language", "content-location", "content-md5", "content-digest", "repr-digest",
            "digest", "transfer-encoding", "trailer", "expect");
    static final class Refused extends IllegalStateException {
        Refused(String safeReason) { super(safeReason); }
    }
    record Request(URI uri, String method, List<HttpRequestFile.Header> headers, byte[] body, String version) { }
    record Next(Request request, boolean crossOrigin) { }
    static boolean supported(int status) { return status == 301 || status == 302 || status == 303 || status == 307 || status == 308; }
    static Next next(Request previous, int status, List<String> locations) {
        if (locations.size() != 1) throw new Refused("Redirect has multiple Location headers.");
        final URI destination;
        try {
            var reference = URI.create(locations.getFirst());
            URI resolved;
            // URI.resolve follows RFC 2396 for query-only references; HTTP uses RFC 3986.
            if (reference.getScheme() == null && reference.getRawAuthority() == null && reference.getRawPath().isEmpty()) {
                String base = previous.uri().toASCIIString().split("#", 2)[0];
                resolved = URI.create(reference.getRawQuery() == null ? base : base.split("\\?", 2)[0] + "?" + reference.getRawQuery());
            } else resolved = previous.uri().resolve(reference);
            destination = URI.create(resolved.toASCIIString().split("#", 2)[0]).normalize();
        } catch (IllegalArgumentException invalid) { throw new Refused("Redirect Location is not a valid HTTP(S) target."); }
        if (!("http".equalsIgnoreCase(destination.getScheme()) || "https".equalsIgnoreCase(destination.getScheme()))
                || destination.getHost() == null || destination.getRawUserInfo() != null || destination.getPort() > 65535)
            throw new Refused("Redirect Location is not a valid HTTP(S) target.");
        if ("https".equalsIgnoreCase(previous.uri().getScheme()) && "http".equalsIgnoreCase(destination.getScheme()))
            throw new Refused("Redirect from HTTPS to HTTP is refused.");
        boolean rewrite = ((status == 301 || status == 302) && previous.method().equals("POST"))
                || (status == 303 && !previous.method().equals("HEAD"));
        String method = rewrite ? "GET" : previous.method();
        // HEAD is retained for 303 but an explicit request body has no role in the retrieval.
        boolean removeBody = rewrite || status == 303;
        byte[] body = removeBody ? new byte[0] : previous.body();
        boolean crossOrigin = !origin(previous.uri()).equals(origin(destination));
        if (crossOrigin && body.length != 0)
            throw new Refused("Cross-origin redirect with a preserved request body is refused. Send the destination explicitly.");
        var headers = crossOrigin ? List.<HttpRequestFile.Header>of() : previous.headers().stream()
                .filter(h -> !Set.of("host", "proxy-authorization", "referer", "origin").contains(h.name().toLowerCase(Locale.ROOT)))
                .filter(h -> !removeBody || !(h.name().toLowerCase(Locale.ROOT).startsWith("content-") || BODY_HEADERS.contains(h.name().toLowerCase(Locale.ROOT)))).toList();
        return new Next(new Request(destination, method, headers, body, previous.version()), crossOrigin);
    }
    static String key(Request request) {
        URI uri = request.uri().normalize();
        String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return request.method() + " " + origin(uri) + path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }
    private static String origin(URI uri) {
        int port = uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }
    private HttpRedirects() { }
}

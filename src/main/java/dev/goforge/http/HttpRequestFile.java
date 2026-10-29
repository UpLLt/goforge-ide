package dev.goforge.http;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** A deliberately execution-free .http subset: requests, headers, bodies and inline variables. */
public final class HttpRequestFile {
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.-]*)\\s*}}");
    private static final Pattern DEFINITION = Pattern.compile("@([A-Za-z_][A-Za-z0-9_.-]*)\\s*=\\s*(.*)");
    private static final Pattern REQUEST = Pattern.compile("([A-Z]+)\\s+(\\S+?)(?:\\s+HTTP/(1\\.[01]|2))?");
    public record Header(String name, String value) { }
    public record Request(String name, String method, URI uri, List<Header> headers, byte[] body,
                          int startLine, int endLine, String version, List<HttpAssertion> assertions, boolean noRedirect) {
        public Request(String name,String method,URI uri,List<Header> headers,byte[] body,int startLine,int endLine,String version){this(name,method,uri,headers,body,startLine,endLine,version,List.of(),false);}
        public Request(String name,String method,URI uri,List<Header> headers,byte[] body,int startLine,int endLine,String version,List<HttpAssertion> assertions){this(name,method,uri,headers,body,startLine,endLine,version,assertions,false);}
        @Override public String toString() { return name; }
    }
    private HttpRequestFile() { }

    public static List<Request> parse(String source) { return parse(source,Map.of()); }
    public static List<Request> parse(String source,Map<String,String> environment) {
        if (source.length() > 2_000_000) throw new IllegalArgumentException("Request file exceeds 2 MB.");
        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        var variables = new LinkedHashMap<String, String>(environment);
        var result = new ArrayList<Request>();
        int start = 0;
        String title = "";
        for (int i = 0; i <= lines.length; i++) {
            if (i < lines.length && !lines[i].startsWith("###")) continue;
            parseBlock(lines, start, i, title, variables, result);
            if (i < lines.length) title = lines[i].substring(3).trim();
            start = i + 1;
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Enter a request, for example GET http://localhost:8080/.");
        return List.copyOf(result);
    }

    private static void parseBlock(String[] lines, int start, int end, String title,
                                   Map<String, String> vars, List<Request> result) {
        int line = start;
        var declarations=new ArrayList<Map.Entry<Integer,String>>();
        boolean noRedirect=false;
        while (line < end) {
            String value = lines[line].trim();
            var definition = DEFINITION.matcher(value);
            String comment=value.startsWith("//")?value.substring(2).trim():value.startsWith("#")?value.substring(1).trim():null;
            if(comment!=null&&comment.startsWith("@no-redirect")){
                if(!comment.equals("@no-redirect"))throw failure(line,"Malformed @no-redirect directive; use it without parameters.");
                noRedirect=true;
            } else if (definition.matches()) {
                if (vars.size() >= 256) throw failure(line, "Too many variables.");
                vars.put(definition.group(1), definition.group(2));
            } else if(value.startsWith("# @expect")) {
                if(!value.startsWith("# @expect "))throw failure(line,"Malformed response assertion directive.");
                if(declarations.size()>=128)throw failure(line,"Too many response assertions.");
                declarations.add(Map.entry(line,value.substring(10)));
            } else if (!value.isEmpty() && !value.startsWith("#") && !value.startsWith("//")) break;
            line++;
        }
        if (line == end) return;
        int requestLine = line;
        var request = REQUEST.matcher(expand(lines[line].trim(), vars));
        if (!request.matches()) throw failure(line, "Expected METHOD and an absolute HTTP(S) URL.");
        if ("1.0".equals(request.group(3))) throw failure(line, "HTTP/1.0 is not supported; use HTTP/1.1 or HTTP/2.");
        String method = request.group(1);
        if (!method.matches("GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE"))
            throw failure(line, "Unsupported HTTP method.");
        URI uri;
        try { uri = URI.create(request.group(2)); } catch (IllegalArgumentException badUri) { throw failure(line, "Invalid URL."); }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() > 65535)
            throw failure(line, "Use an absolute HTTP(S) URL without embedded credentials or fragment.");
        var headers = new ArrayList<Header>();
        for (line++; line < end && !lines[line].isBlank(); line++) {
            String header = expand(lines[line], vars);
            int colon = header.indexOf(':');
            if (colon < 1) throw failure(line, "Expected a header or a blank line before the body.");
            String name = header.substring(0, colon).trim();
            String value = header.substring(colon + 1).trim();
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
                throw failure(line, "Invalid header.");
            headers.add(new Header(name, value));
            if (headers.size() > 256) throw failure(line, "Too many headers.");
        }
        var body = new StringBuilder();
        boolean firstBodyLine = true;
        for (line++; line < end; line++) {
            String value = lines[line];
            if (value.stripLeading().startsWith("< ") || value.stripLeading().startsWith("> ")
                    || value.stripLeading().startsWith("<%") || value.stripLeading().startsWith("{%"))
                throw failure(line, "File includes and request/response scripts are not supported; paste the body explicitly.");
            if (!firstBodyLine) body.append('\n');
            firstBodyLine = false;
            body.append(value);
        }
        if (result.size() >= 128) throw failure(start, "Too many requests.");
        var assertions=declarations.stream().map(d->HttpAssertion.parse(d.getValue(),d.getKey(),vars)).toList();
        result.add(new Request(title.isEmpty() ? method + " request " + (result.size() + 1) : title,
                method, uri, List.copyOf(headers), expand(body.toString(), vars).getBytes(StandardCharsets.UTF_8),
                requestLine, end, request.group(3),assertions,noRedirect));
    }

    static String expand(String value, Map<String, String> vars) { return expand(value, vars, new java.util.HashSet<>(), 0); }
    private static String expand(String value, Map<String, String> vars, java.util.Set<String> visiting, int depth) {
        if (depth > 16) throw new IllegalArgumentException("Variable expansion is too deeply nested.");
        var matcher = VARIABLE.matcher(value);
        var out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!vars.containsKey(key)) throw new IllegalArgumentException("Undefined variable: " + key);
            if (!visiting.add(key)) throw new IllegalArgumentException("Cyclic variable: " + key);
            String resolved = expand(vars.get(key), vars, visiting, depth + 1);
            visiting.remove(key);
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(resolved));
            if (out.length() > 2_000_000) throw new IllegalArgumentException("Expanded request exceeds 2 MB.");
        }
        matcher.appendTail(out);
        if (out.length() > 2_000_000) throw new IllegalArgumentException("Expanded request exceeds 2 MB.");
        return out.toString();
    }
    private static IllegalArgumentException failure(int line, String message) {
        return new IllegalArgumentException("Line " + (line + 1) + ": " + message);
    }
}

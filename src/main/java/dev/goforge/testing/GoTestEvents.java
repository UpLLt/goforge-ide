package dev.goforge.testing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stateful, single-threaded conversion of one {@code go test -json} process.
 * No IntelliJ or JSON-parser dependency. Feed parsed objects to {@link #accept(Map)};
 * non-JSON stdout/stderr belongs in {@link #acceptText(String)}. Call
 * {@link #finishProcess(int)} exactly when the process has terminated.
 *
 * <p>Go TestEvents and BuildEvents are different streams: ImportPath is NOT a
 * TestEvent.Package. See https://pkg.go.dev/cmd/test2json and {@code go help buildjson}.
 * Ordinary tests require explicit terminal events. Successful benchmarks may
 * have only run/output events (Go 1.26's testing.B.Run/processBench); an observed
 * package pass completes explicitly started, valid Benchmark functions and
 * their descendants. This is package-level evidence, not an individual terminal
 * event or an inference from process exit 0. Other missing results are INCOMPLETE.
 *
 * <p>The canonical tree is the structured nodeId/parentId stream. Service messages
 * include IntelliJ's id-based-tree attributes and a separate flow for every test;
 * configure the consuming SMTestRunner to use an id-based test tree. Generic
 * TeamCity consumers can ignore those attributes; package-qualified test names
 * still avoid ambiguous names. This class itself does not install a test UI.
 *
 * <p>Source output is emitted immediately as TEST_OUTPUT, with an empty service
 * message while buffered. A single testStdOut attachment is emitted on completion
 * (https://www.jetbrains.com/help/teamcity/service-messages.html). The total output
 * buffer is bounded; overflow is preserved in escaped process-log messages.
 * Never forward raw program output as service messages.
 */
public final class GoTestEvents {
    public enum Status { RUNNING, PAUSED, PASSED, FAILED, SKIPPED, BENCHMARK_COMPLETED, INCOMPLETE }
    public enum Kind {
        SUITE_STARTED, SUITE_FINISHED, TEST_STARTED, TEST_FINISHED, TEST_FAILED,
        TEST_SKIPPED, TEST_PAUSED, TEST_RESUMED, TEST_OUTPUT, OUTPUT_ATTACHMENT,
        PACKAGE_OUTPUT, BUILD_OUTPUT, PROCESS_OUTPUT, OUTPUT_REDIRECTED,
        INFRASTRUCTURE_FAILURE, PROTOCOL_WARNING
    }

    /** Empty teamCityMessage means the consumer should not write a service line. */
    public record Emission(Kind kind, String packageName, String testName,
                           String nodeId, String parentId, String flowId, int attempt,
                           Status status, String text, Long durationMillis,
                           boolean inferred, String teamCityMessage) { }

    public record Event(String action, String packageName, String test,
                        Double elapsed, String output, String failedBuild, String importPath) {
        public Event {
            if (action == null || action.isBlank()) throw new IllegalArgumentException("Action is required");
            packageName = nullToEmpty(packageName);
            test = nullToEmpty(test);
            output = nullToEmpty(output);
            failedBuild = nullToEmpty(failedBuild);
            importPath = nullToEmpty(importPath);
        }

        public static Event fromMap(Map<String, Object> object) {
            Objects.requireNonNull(object, "event");
            Object elapsed = object.get("Elapsed");
            if (elapsed != null && !(elapsed instanceof Number)) {
                throw new IllegalArgumentException("Elapsed must be a number");
            }
            return new Event(string(object, "Action"), string(object, "Package"),
                    string(object, "Test"), elapsed == null ? null : ((Number) elapsed).doubleValue(),
                    string(object, "Output"), string(object, "FailedBuild"), string(object, "ImportPath"));
        }

        private static String string(Map<String, Object> object, String key) {
            Object value = object.get(key);
            if (value == null) return "";
            if (!(value instanceof String)) throw new IllegalArgumentException(key + " must be a string");
            return (String) value;
        }
    }

    private static final Set<String> TEST_ACTIONS = Set.of("start", "run", "pause", "cont",
            "pass", "fail", "skip", "output", "bench");
    private static final class Node {
        final String packageName, name, id, parentId;
        final int attempt;
        boolean inferred, closed, explicitRun, redirected;
        Status status = Status.RUNNING;
        final StringBuilder output = new StringBuilder();
        Node(String packageName, String name, String id, String parentId, int attempt, boolean inferred) {
            this.packageName = packageName; this.name = name; this.id = id; this.parentId = parentId;
            this.attempt = attempt; this.inferred = inferred;
        }
    }
    private static final class PackageState {
        final Node node;
        final Map<String, Node> tests = new LinkedHashMap<>();
        final Map<String, Integer> attempts = new LinkedHashMap<>();
        boolean failed, incomplete;
        PackageState(Node node) { this.node = node; }
    }

    private final Map<String, PackageState> packages = new LinkedHashMap<>();
    private final Map<String, Integer> packageAttempts = new LinkedHashMap<>();
    private final Set<String> failedBuilds = new LinkedHashSet<>();
    private final int maxBufferedOutputChars;
    private int bufferedOutputChars, infrastructureSequence;
    private boolean finished, observedFailure;

    public GoTestEvents() { this(1_048_576); }
    public GoTestEvents(int maxBufferedOutputChars) {
        if (maxBufferedOutputChars < 0) throw new IllegalArgumentException("Output buffer must be nonnegative");
        this.maxBufferedOutputChars = maxBufferedOutputChars;
    }

    public List<Emission> accept(Map<String, Object> event) {
        try {
            return accept(Event.fromMap(event));
        } catch (IllegalArgumentException | NullPointerException ex) {
            List<Emission> out = new ArrayList<>();
            warn(out, null, "Invalid Go event: " + ex.getMessage());
            return List.copyOf(out);
        }
    }

    public List<Emission> accept(Event event) {
        Objects.requireNonNull(event, "event");
        List<Emission> out = new ArrayList<>();
        if (finished) {
            warn(out, null, "Event after process termination: " + event.action());
            if (!event.output().isEmpty()) log(out, Kind.PROCESS_OUTPUT, null, event.output());
            return List.copyOf(out);
        }
        if (event.action().equals("build-output") || event.action().equals("build-fail")) {
            acceptBuild(event, out);
            return List.copyOf(out);
        }
        if (!TEST_ACTIONS.contains(event.action())) {
            warn(out, null, "Unknown Go action: " + event.action());
            if (!event.output().isEmpty()) log(out, Kind.PROCESS_OUTPUT, null, event.output());
            return List.copyOf(out);
        }
        if (event.packageName().isEmpty()) {
            warn(out, null, "TestEvent has no Package; no package identity was invented");
            if (!event.output().isEmpty()) log(out, Kind.PROCESS_OUTPUT, null, event.output());
            return List.copyOf(out);
        }
        PackageState pkg = packages.get(event.packageName());
        if (event.action().equals("start")) {
            if (!event.test().isEmpty()) {
                warn(out, null, "Package start unexpectedly includes a Test");
            } else if (pkg != null && !pkg.node.closed) {
                warn(out, pkg.node, "Duplicate package start");
            } else {
                startPackage(event.packageName(), false, out);
            }
            return List.copyOf(out);
        }
        if (pkg == null) {
            pkg = startPackage(event.packageName(), true, out);
            warn(out, pkg.node, "Package start event missing; created an inferred container");
        }
        if (pkg.node.closed) {
            warn(out, pkg.node, "Late event after package result: " + event.action());
            if (!event.output().isEmpty()) log(out, Kind.PACKAGE_OUTPUT, pkg.node, event.output());
            return List.copyOf(out);
        }
        if (event.test().isEmpty()) {
            switch (event.action()) {
                case "output" -> log(out, Kind.PACKAGE_OUTPUT, pkg.node, event.output());
                case "pass", "fail", "skip" -> finishPackage(pkg, event, out);
                default -> warn(out, pkg.node, "Test action without Test: " + event.action());
            }
            return List.copyOf(out);
        }
        acceptTest(pkg, event, out);
        return List.copyOf(out);
    }

    /** Treat every byte of unstructured output as data, including ##teamcity text. */
    public List<Emission> acceptText(String text) {
        List<Emission> out = new ArrayList<>();
        if (text != null && !text.isEmpty()) log(out, Kind.PROCESS_OUTPUT, null, text);
        return List.copyOf(out);
    }

    /** Idempotent. exitCode 0 is not evidence that an unfinished test passed. */
    public List<Emission> finishProcess(int exitCode) {
        if (finished) return List.of();
        finished = true;
        List<Emission> out = new ArrayList<>();
        boolean openPackage = false;
        for (PackageState pkg : packages.values()) {
            if (pkg.node.closed) continue;
            openPackage = true;
            for (Node test : pkg.tests.values()) {
                if (!test.closed) finishTest(pkg, test, Status.INCOMPLETE, null,
                        "Process exited " + exitCode + " before this test's terminal event", out);
            }
            infrastructureFailure(pkg, "process", "Process exited " + exitCode
                    + " before the package terminal event", out);
            closeSuite(pkg.node, Status.INCOMPLETE, null, out);
        }
        if (!openPackage && exitCode != 0 && !observedFailure) {
            infrastructureFailure(null, "process", "go test exited with code " + exitCode, out);
        } else if (packages.isEmpty() && !observedFailure) {
            infrastructureFailure(null, "process", "Process exited " + exitCode
                    + " without any valid package result", out);
        }
        return List.copyOf(out);
    }

    private PackageState startPackage(String name, boolean inferred, List<Emission> out) {
        int attempt = packageAttempts.merge(name, 1, Integer::sum);
        Node n = new Node(name, name, "pkg:" + encode(name) + ":" + attempt, "0", attempt, inferred);
        PackageState pkg = new PackageState(n);
        packages.put(name, pkg);
        out.add(emit(Kind.SUITE_STARTED, n, n.status, "", null,
                tc("testSuiteStarted", n, "name", name)));
        return pkg;
    }

    private void acceptTest(PackageState pkg, Event e, List<Emission> out) {
        Node n = pkg.tests.get(e.test());
        if (e.action().equals("run")) {
            if (n != null && !n.closed) {
                if (n.explicitRun) warn(out, n, "Duplicate run for active test");
                else { n.explicitRun = true; warn(out, n, "Run arrived after output/result initialization"); }
            } else {
                n = startTest(pkg, e.test(), false, out);
                n.explicitRun = true;
            }
            return;
        }
        if (n == null) {
            n = startTest(pkg, e.test(), true, out);
            warn(out, n, "Test run event missing; start is inferred, result is not");
        } else if (n.closed) {
            warn(out, n, "Late or duplicate event after test result: " + e.action());
            if (!e.output().isEmpty()) log(out, Kind.TEST_OUTPUT, n, e.output());
            return;
        }
        switch (e.action()) {
            case "output" -> output(n, e.output(), out);
            case "pause" -> {
                if (n.status == Status.PAUSED) warn(out, n, "Duplicate pause");
                else { n.status = Status.PAUSED; out.add(emit(Kind.TEST_PAUSED, n, n.status, "", null, "")); }
            }
            case "cont" -> {
                if (n.status != Status.PAUSED) warn(out, n, "Continue without preceding pause");
                n.status = Status.RUNNING;
                out.add(emit(Kind.TEST_RESUMED, n, n.status, "", null, ""));
            }
            case "pass" -> finishTest(pkg, n, Status.PASSED, duration(e, n, out), "", out);
            case "fail" -> finishTest(pkg, n, Status.FAILED, duration(e, n, out), "Go test failed", out);
            case "skip" -> finishTest(pkg, n, Status.SKIPPED, duration(e, n, out), "Skipped by go test", out);
            case "bench" -> finishTest(pkg, n, Status.BENCHMARK_COMPLETED, duration(e, n, out), "", out);
            default -> warn(out, n, "Unsupported test action: " + e.action());
        }
    }

    private Node startTest(PackageState pkg, String name, boolean inferred, List<Emission> out) {
        int attempt = pkg.attempts.merge(name, 1, Integer::sum);
        String parentId = pkg.node.id;
        int slash = name.lastIndexOf('/');
        while (slash > 0) {
            Node parent = pkg.tests.get(name.substring(0, slash));
            if (parent != null && !parent.closed) { parentId = parent.id; break; }
            slash = name.lastIndexOf('/', slash - 1);
        }
        Node n = new Node(pkg.node.packageName, name,
                pkg.node.id + ":test:" + encode(name) + ":" + attempt, parentId, attempt, inferred);
        pkg.tests.put(name, n);
        out.add(emit(Kind.TEST_STARTED, n, n.status, "", null,
                tc("testStarted", n, "name", testName(n), "captureStandardOutput", "false")));
        return n;
    }

    private void output(Node n, String text, List<Emission> out) {
        int retain = Math.min(text.length(), Math.max(0, maxBufferedOutputChars - bufferedOutputChars));
        n.output.append(text, 0, retain);
        bufferedOutputChars += retain;
        out.add(emit(Kind.TEST_OUTPUT, n, n.status, text, null, ""));
        if (retain < text.length()) {
            if (!n.redirected) {
                n.redirected = true;
                String note = "Test output buffer limit reached; remaining output is preserved in the process log";
                out.add(emit(Kind.OUTPUT_REDIRECTED, n, n.status, note, null,
                        tc("message", n, "text", note, "status", "WARNING")));
            }
            log(out, Kind.PROCESS_OUTPUT, n, text.substring(retain));
        }
    }

    private void finishTest(PackageState pkg, Node n, Status status, Long duration, String reason, List<Emission> out) {
        if (n.closed) return;
        if (!n.output.isEmpty()) {
            String output = n.output.toString();
            out.add(emit(Kind.OUTPUT_ATTACHMENT, n, n.status, output, null,
                    tc("testStdOut", n, "name", testName(n), "out", output)));
            bufferedOutputChars -= n.output.length();
            n.output.setLength(0);
        }
        n.status = status;
        if (status == Status.FAILED || status == Status.INCOMPLETE) {
            observedFailure = true;
            if (status == Status.FAILED) pkg.failed = true; else pkg.incomplete = true;
            out.add(emit(Kind.TEST_FAILED, n, status, reason, duration,
                    tc("testFailed", n, "name", testName(n), "message", reason)));
        } else if (status == Status.SKIPPED) {
            out.add(emit(Kind.TEST_SKIPPED, n, status, reason, duration,
                    tc("testIgnored", n, "name", testName(n), "message", reason)));
        }
        n.closed = true;
        String message = duration == null
                ? tc("testFinished", n, "name", testName(n))
                : tc("testFinished", n, "name", testName(n), "duration", Long.toString(duration));
        out.add(emit(Kind.TEST_FINISHED, n, status, reason, duration, message));
    }

    private void finishPackage(PackageState pkg, Event e, List<Emission> out) {
        // Close descendants before their still-open benchmark parents. Go's
        // JSON benchmark protocol has no unconditional per-benchmark pass/bench.
        // Timing text is not a terminal signal (and parent benchmarks may have
        // no timing line at all). Never trust arbitrary output as proof of pass.
        var pending = new ArrayList<>(pkg.tests.values());
        for (int i = pending.size() - 1; i >= 0; i--) {
            Node n = pending.get(i);
            if (n.closed) continue;
            if (e.action().equals("pass") && n.explicitRun && isBenchmark(n.name)) {
                finishTest(pkg, n, Status.BENCHMARK_COMPLETED, null,
                        "Benchmark completed with package pass; no individual terminal event was emitted", out);
            } else {
                finishTest(pkg, n, Status.INCOMPLETE, null,
                        "Package result arrived before this test's terminal event", out);
            }
        }
        Status result = switch (e.action()) {
            case "fail" -> Status.FAILED;
            case "skip" -> Status.SKIPPED;
            default -> Status.PASSED;
        };
        if (result == Status.FAILED) {
            observedFailure = true;
            if (!pkg.failed && !pkg.incomplete) {
                String reason = e.failedBuild().isEmpty() ? "Go package failed outside a reported test"
                        : "Go package failed to build; FailedBuild=" + e.failedBuild();
                if (e.failedBuild().isEmpty() || !failedBuilds.contains(e.failedBuild())) {
                    infrastructureFailure(pkg, "package", reason, out);
                } else {
                    pkg.failed = true;
                    log(out, Kind.PACKAGE_OUTPUT, pkg.node, reason);
                }
            }
        } else if (pkg.failed || pkg.incomplete) {
            warn(out, pkg.node, "Package " + e.action() + " conflicts with failed/incomplete child results");
            result = pkg.failed ? Status.FAILED : Status.INCOMPLETE;
        }
        closeSuite(pkg.node, result, duration(e, pkg.node, out), out);
    }

    /** Go's isTest name rule applies to the top-level function, not a subtest segment. */
    private static boolean isBenchmark(String name) {
        String prefix = "Benchmark";
        if (!name.startsWith(prefix)) return false;
        // unicode.IsLower in Go means category Ll, not Java's broader
        // isLowerCase predicate (which also includes Other_Lowercase letters).
        return name.length() == prefix.length()
                || Character.getType(name.codePointAt(prefix.length())) != Character.LOWERCASE_LETTER;
    }

    private void acceptBuild(Event e, List<Emission> out) {
        if (e.importPath().isEmpty()) {
            warn(out, null, "BuildEvent has no ImportPath; it was not treated as a TestEvent.Package");
            if (!e.output().isEmpty()) log(out, Kind.BUILD_OUTPUT, null, e.output());
            return;
        }
        Node build = new Node("", e.importPath(), "build:" + encode(e.importPath()), "0", 1, false);
        if (e.action().equals("build-output")) log(out, Kind.BUILD_OUTPUT, build, e.output());
        else if (failedBuilds.add(e.importPath())) {
            infrastructureFailure(null, "build", "Build failed: " + e.importPath(), out);
        } else warn(out, build, "Duplicate build-fail");
    }

    private void infrastructureFailure(PackageState pkg, String type, String reason, List<Emission> out) {
        observedFailure = true;
        Node suite = pkg == null
                ? new Node("", "Go " + type, "infra-suite:" + (++infrastructureSequence), "0", 1, true)
                : pkg.node;
        if (pkg == null) out.add(emit(Kind.SUITE_STARTED, suite, Status.RUNNING, "", null,
                tc("testSuiteStarted", suite, "name", suite.name)));
        Node n = new Node(suite.packageName, "[" + type + " failure]",
                suite.id + ":failure:" + (++infrastructureSequence), suite.id, 1, true);
        out.add(emit(Kind.TEST_STARTED, n, Status.RUNNING, "", null,
                tc("testStarted", n, "name", testName(n), "captureStandardOutput", "false")));
        n.status = Status.FAILED;
        out.add(emit(Kind.INFRASTRUCTURE_FAILURE, n, n.status, reason, null,
                tc("testFailed", n, "name", testName(n), "message", reason)));
        n.closed = true;
        out.add(emit(Kind.TEST_FINISHED, n, n.status, reason, null,
                tc("testFinished", n, "name", testName(n))));
        if (pkg != null) pkg.failed = true;
        else closeSuite(suite, Status.FAILED, null, out);
    }

    private void closeSuite(Node n, Status status, Long duration, List<Emission> out) {
        n.closed = true; n.status = status;
        out.add(emit(Kind.SUITE_FINISHED, n, status, "", duration,
                tc("testSuiteFinished", n, "name", n.name)));
    }

    private Long duration(Event e, Node n, List<Emission> out) {
        if (e.elapsed() == null) return null;
        double ms = e.elapsed() * 1000.0;
        if (!Double.isFinite(ms) || ms < 0 || ms >= Long.MAX_VALUE) {
            warn(out, n, "Invalid Elapsed; duration omitted");
            return null;
        }
        return Math.round(ms);
    }

    private static void log(List<Emission> out, Kind kind, Node n, String text) {
        out.add(emit(kind, n, n == null ? Status.RUNNING : n.status, text, null,
                tc("message", n, "text", text, "status", "NORMAL")));
    }
    private static void warn(List<Emission> out, Node n, String text) {
        out.add(emit(Kind.PROTOCOL_WARNING, n, Status.INCOMPLETE, text, null,
                tc("message", n, "text", text, "status", "WARNING")));
    }
    private static Emission emit(Kind kind, Node n, Status status, String text, Long duration, String tc) {
        return new Emission(kind, n == null ? "" : n.packageName, n == null ? "" : n.name,
                n == null ? "" : n.id, n == null ? "" : n.parentId, n == null ? "" : n.id,
                n == null ? 0 : n.attempt, status, text, duration, n != null && n.inferred, tc);
    }
    private static String testName(Node n) { return n.packageName.isEmpty() ? n.name : n.packageName + "::" + n.name; }
    private static String nullToEmpty(String s) { return s == null ? "" : s; }
    private static String encode(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
    private static String tc(String name, Node n, String... attributes) {
        StringBuilder b = new StringBuilder("##teamcity[").append(name);
        for (int i = 0; i < attributes.length; i += 2) {
            b.append(' ').append(attributes[i]).append("='").append(escape(attributes[i + 1])).append('\'');
        }
        if (n != null) b.append(" nodeId='").append(escape(n.id)).append("' parentNodeId='")
                .append(escape(n.parentId)).append("' flowId='").append(escape(n.id)).append('\'');
        return b.append(']').toString();
    }

    public static String escape(String text) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '|' -> b.append("||");
                case '\'' -> b.append("|'");
                case '\n' -> b.append("|n");
                case '\r' -> b.append("|r");
                case '[' -> b.append("|[");
                case ']' -> b.append("|]");
                default -> {
                    if (Character.isISOControl(c) && c != '\t') b.append(String.format("|0x%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }
}

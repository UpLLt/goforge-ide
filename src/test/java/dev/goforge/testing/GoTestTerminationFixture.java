package dev.goforge.testing;

/** Controlled JSON-producing OS process for handler/SM lifecycle tests; it is not a Go runtime probe. */
public final class GoTestTerminationFixture {
    private static void event(String action, String test) {
        System.out.println("{\"Action\":\"" + action + "\",\"Package\":\"p\",\"Test\":\"" + test + "\"}");
        System.out.flush();
    }
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.equals("empty")) {
            System.err.println("FIXTURE_READY");
            Thread.sleep(60_000);
            return;
        }
        event("start", "");
        if (mode.equals("benchmark-open")) {
            event("run", "BenchmarkOne");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> event("fail", "")));
            System.err.println("FIXTURE_READY");
            Thread.sleep(60_000);
            return;
        }
        if (mode.equals("benchmark") || mode.equals("benchmark-missing")) {
            if (mode.equals("benchmark")) event("run", "BenchmarkOne");
            event("pass", "");
            return;
        }
        if (mode.equals("terminal-but-alive")) {
            event("run", "TestPass"); event("pass", "TestPass");
            event("run", "TestFail"); event("fail", "TestFail");
            event("run", "TestSkip"); event("skip", "TestSkip");
            event("fail", "");
            System.err.println("FIXTURE_READY");
            Thread.sleep(60_000);
            return;
        }
        if (mode.equals("natural") || mode.equals("bad-exit")) {
            event("run", "TestPass"); event("pass", "TestPass");
            event("pass", "TestPass"); // warning must not overwrite the recorded pass
            event("pass", "");
            if (mode.equals("bad-exit")) System.exit(1);
            return;
        }
        if (mode.equals("buffered")) {
            event("run", "TestPass");
            System.out.print("{\"Action\":\"pass\",\"Package\":\"p\",\"Test\":\"TestPass\"}");
            System.out.flush();
        } else {
            event("run", "TestPass"); event("pass", "TestPass");
            event("run", "TestFail"); event("fail", "TestFail");
            event("run", "TestSkip"); event("skip", "TestSkip");
            event("run", "TestSlow"); event("run", "TestSlow/child"); event("pause", "TestSlow/child");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Package fail after SIGINT is aggregate evidence, not an assertion.
            if (mode.equals("buffered")) System.out.println();
            event("fail", "");
            System.err.println("FIXTURE_STOPPING");
            if (mode.equals("force")) {
                try { Thread.sleep(60_000); } catch (InterruptedException ignored) { }
            }
        }));
        System.err.println("FIXTURE_READY");
        Thread.sleep(60_000);
    }
}

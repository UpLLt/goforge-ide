package dev.goforge.debug;

/** Dependency-free test process; deliberately never echoes its private stdin. */
public final class RemoteBridgeChild {
    public static void main(String[] arguments) throws Exception {
        System.out.print("READY\n");System.out.flush();
        new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8)).readLine();
        System.out.print("ACK\n");System.out.flush();Thread.sleep(60_000);
    }
}

package dev.goforge.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Original bounded Python module reader, embedded in the owned SSH supervisor. */
final class GoRemoteModuleScript {
    static final String SOURCE = read();
    private GoRemoteModuleScript() { }
    private static String read() {
        try (var input = GoRemoteModuleScript.class.getResourceAsStream("module-source.py")) {
            if (input == null) throw new IOException("Missing remote module source reader.");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
}

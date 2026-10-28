package dev.goforge.containers;

import dev.goforge.coverage.GoCoverageProcess;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Uses the shared bounded local-process runner. Cancellation stops the CLI, not daemon work already accepted. */
public final class DockerClient {
    public record Result(DockerCommands.Request request, List<String> command, GoCoverageProcess.Result process) {
        public boolean succeeded() { return !process.cancelled() && !process.timedOut() && Integer.valueOf(0).equals(process.exitCode()); }
        public String status() {
            if (process.cancelled()) return "Cancelled; refresh to check work already accepted by Docker";
            if (process.timedOut()) return "Timed out; refresh to check work already accepted by Docker";
            return "Exit " + process.exitCode() + (process.outputTruncated() ? " · output truncated at 4 MiB per stream" : "")
                    + (process.knownProcessesStopped() ? "" : " · a known local child has not stopped");
        }
        public String output() {
            return request.title() + "\nArguments: " + new com.google.gson.Gson().toJson(command) + "\n" + status() + "\n\n"
                    + process.stdout() + (process.stderr().isEmpty() ? "" : "\n[stderr]\n" + process.stderr());
        }
        public String requiredJson() throws IOException {
            if (!succeeded()) throw new IOException(status() + "\n" + process.stderr());
            if (process.outputTruncated()) throw new IOException("Docker inventory exceeded the output limit; it was not treated as a complete inventory.");
            return process.stdout();
        }
    }
    private DockerClient() { }
    public static Result execute(String executable, String context, DockerCommands.Request request, Path directory,
                                 Map<String, String> environment, BooleanSupplier cancelled) throws IOException, InterruptedException {
        var command = DockerCommands.command(executable, context, request);
        var result = GoCoverageProcess.run(command, directory, environment, cancelled, request.timeout());
        return new Result(request, command, result);
    }
}

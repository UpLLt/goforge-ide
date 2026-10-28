package dev.goforge.containers;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Docker requests are argument vectors, never shell command strings. */
public final class DockerCommands {
    public record Request(String title, List<String> arguments, Duration timeout, boolean mutation) {
        public Request { arguments = List.copyOf(arguments); }
    }
    public enum Lifecycle { START, STOP, RESTART, REMOVE }
    public enum Compose { PS, UP, DOWN }
    private DockerCommands() { }
    private static Request read(String title, String... args) { return new Request(title, List.of(args), Duration.ofSeconds(30), false); }
    public static Request contexts() { return read("Docker contexts", "context", "ls", "--format", "{{json .}}"); }
    public static Request version() { return read("Docker connection", "version", "--format", "{{json .}}"); }
    public static Request containers() { return read("Containers", "container", "ls", "--all", "--no-trunc", "--format", "{{json .}}"); }
    public static Request images() { return read("Images", "image", "ls", "--no-trunc", "--format", "{{json .}}"); }
    public static Request inspectContainer(String id) { return read("Container details", "container", "inspect", "--", containerId(id)); }
    public static Request inspectImage(String id) { return read("Image details", "image", "inspect", "--", imageId(id)); }
    public static Request logs(String id) { return read("Container logs (last 1000 lines)", "container", "logs", "--timestamps", "--tail", "1000", "--", containerId(id)); }
    public static Request lifecycle(Lifecycle action, String id) {
        return new Request(action + " container", List.of("container", action.name().toLowerCase(java.util.Locale.ROOT).replace("remove", "rm"), "--", containerId(id)), Duration.ofSeconds(90), true);
    }
    public static Request removeImage(String reference) { return new Request("Remove image", List.of("image", "rm", "--", reference(reference)), Duration.ofSeconds(60), true); }
    public static Request pull(String reference) { return new Request("Pull image", List.of("image", "pull", "--", reference(reference)), Duration.ofMinutes(15), true); }
    public static Request build(Path project, Path directory, Path dockerfile, String tag) throws IOException {
        Path context = within(project, directory, true), file = within(context, dockerfile, false);
        return new Request("Build image", List.of("build", "--progress", "plain", "--file", file.toString(), "--tag", reference(tag), "--", context.toString()), Duration.ofMinutes(20), true);
    }
    public static Request compose(Path project, Path composeFile, String projectName, Compose action) throws IOException {
        Path file = within(project, composeFile, false);
        if (!projectName.matches("[a-z0-9][a-z0-9_-]{0,62}")) throw new IllegalArgumentException("Compose project name must start with a lowercase letter or digit and use only lowercase letters, digits, _ or -.");
        var args = new ArrayList<>(List.of("compose", "--ansi", "never", "--project-directory", file.getParent().toString(), "--file", file.toString(), "--project-name", projectName));
        switch (action) {
            case PS -> args.addAll(List.of("ps", "--all", "--format", "json"));
            case UP -> args.addAll(List.of("up", "--detach"));
            case DOWN -> args.add("down"); // Deliberately no --volumes or --remove-orphans.
        }
        return new Request("Compose " + action.name().toLowerCase(java.util.Locale.ROOT), args, Duration.ofMinutes(action == Compose.PS ? 1 : 15), action != Compose.PS);
    }
    public static Request exec(String id, List<String> command, boolean interactive) {
        if (command.isEmpty()) throw new IllegalArgumentException("Enter at least an executable.");
        var args = new ArrayList<>(List.of("container", "exec"));
        if (interactive) args.add("-it");
        args.add("--"); args.add(containerId(id));
        for (String arg : command) { plain(arg, "Exec argument"); args.add(arg); }
        if (command.getFirst().isBlank()) throw new IllegalArgumentException("Executable cannot be blank.");
        return new Request("Exec in container", args, Duration.ofMinutes(5), true);
    }
    public static Request runImage(String id, String name, String publish, List<String> command) {
        if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}")) throw new IllegalArgumentException("Enter a valid container name.");
        var args = new ArrayList<>(List.of("container", "run", "--detach", "--name", name));
        if (!publish.isBlank()) {
            if (!publish.matches("(?:(?:127\\.0\\.0\\.1|0\\.0\\.0\\.0):)?[0-9]{1,5}:[0-9]{1,5}(?:/(?:tcp|udp))?")) throw new IllegalArgumentException("Port mapping must be [127.0.0.1:]host:container[/tcp|udp].");
            args.addAll(List.of("--publish", publish));
        }
        args.add("--"); args.add(imageId(id));
        for (String arg : command) { plain(arg, "Command argument"); args.add(arg); }
        return new Request("Run image", args, Duration.ofMinutes(2), true);
    }
    public static List<String> command(String executable, String context, Request request) {
        plain(executable, "Docker executable");
        if (executable.isBlank()) throw new IllegalArgumentException("Docker executable is required.");
        if ((context == null || context.isBlank()) && !request.arguments().equals(contexts().arguments())) throw new IllegalArgumentException("Select an explicit Docker context first.");
        var command = new ArrayList<>(List.of(executable));
        if (context != null && !context.isBlank()) {
            plain(context, "Docker context");
            if (context.startsWith("-")) throw new IllegalArgumentException("Invalid Docker context.");
            command.addAll(List.of("--context", context));
        }
        command.addAll(request.arguments()); return List.copyOf(command);
    }
    public static String resolveExecutable(String configured, Map<String, String> environment) throws IOException {
        String value = configured == null || configured.isBlank() ? "docker" : configured.trim();
        plain(value, "Docker executable");
        try { return dev.goforge.tools.HostTools.resolve(value, environment); }
        catch (IOException unavailable) {
            if (!dev.goforge.tools.HostTools.isWindows() && value.equals("docker"))
                for (String fallback : List.of("/opt/homebrew/bin/docker", "/usr/local/bin/docker", "/Applications/Docker.app/Contents/Resources/bin/docker"))
                    if (Files.isRegularFile(Path.of(fallback)) && Files.isExecutable(Path.of(fallback))) return fallback;
            throw unavailable;
        }
    }

    public static List<String> parseArguments(String json) {
        var array = JsonParser.parseString(json);
        if (!array.isJsonArray()) throw new IllegalArgumentException("Enter a JSON array of strings, for example [\"echo\",\"hello\"].");
        var result = new ArrayList<String>();
        for (var value : array.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Every command argument must be a string.");
            String arg = value.getAsString(); plain(arg, "Command argument"); result.add(arg);
        }
        return List.copyOf(result);
    }
    private static Path within(Path project, Path candidate, boolean directory) throws IOException {
        Path root = project.toRealPath();
        Path path = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).toRealPath();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Choose a path inside " + root);
        if (directory ? !Files.isDirectory(path) : !Files.isRegularFile(path)) throw new IllegalArgumentException("Choose an existing " + (directory ? "directory" : "file") + ".");
        return path;
    }
    public static String imageReference(DockerData.Image image) {
        return image.repository().equals("<none>") || image.tag().equals("<none>") ? image.id() : image.repository() + ":" + image.tag();
    }
    private static String reference(String value) {
        plain(value, "Image reference");
        if (value.isBlank() || value.startsWith("-") || value.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException("Enter an image reference without spaces or options.");
        return value;
    }
    private static String containerId(String id) {
        if (id == null || !id.matches("[a-fA-F0-9]{12,64}")) throw new IllegalArgumentException("Select a container with a full Docker ID."); return id;
    }
    private static String imageId(String id) {
        if (id == null || !id.matches("(?:sha256:)?[a-fA-F0-9]{12,64}")) throw new IllegalArgumentException("Select an image with a Docker ID."); return id;
    }
    private static void plain(String value, String label) {
        if (value == null || value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw new IllegalArgumentException(label + " cannot contain NUL or line breaks.");
    }
}

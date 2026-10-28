package dev.goforge.remote;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Non-secret connection coordinates. OpenSSH owns authentication and known-hosts. */
public record GoRemoteProfile(String host, String user, int port, String keyFile,
                              String remoteDirectory, String goExecutable, String knownHostsFile) {
    public GoRemoteProfile {
        host = value(host); user = value(user); keyFile = raw(keyFile);
        remoteDirectory = raw(remoteDirectory); goExecutable = value(goExecutable);
        knownHostsFile = raw(knownHostsFile);
        if (host.isEmpty() || host.startsWith("-") || !host.matches("[A-Za-z0-9:][A-Za-z0-9._:%-]*"))
            throw new IllegalArgumentException("Enter a host name, SSH config alias, or IP address without options.");
        if (!user.isEmpty() && !user.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*"))
            throw new IllegalArgumentException("Invalid SSH user name.");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("SSH port must be 1 to 65535.");
        absoluteRemote(remoteDirectory);
        if (goExecutable.isEmpty()) goExecutable = "go";
        if (goExecutable.startsWith("/")) absoluteRemote(goExecutable);
        else if (!goExecutable.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*"))
            throw new IllegalArgumentException("Go executable must be a command name or absolute path.");
        localFile(keyFile, "Identity file"); localFile(knownHostsFile, "Known-hosts file");
    }

    private static String value(String text) { return text == null ? "" : text.trim(); }
    private static String raw(String text) {return text == null ? "" : text;}
    static void noControls(String value) {
        if (value.codePoints().anyMatch(c -> Character.isISOControl(c)))
            throw new IllegalArgumentException("Control characters are not allowed in paths.");
    }
    public static String absoluteRemote(String path) {
        noControls(path);
        if (!path.startsWith("/") || path.equals("/"))
            throw new IllegalArgumentException("Use an absolute remote workspace path other than /.");
        for (String part : path.split("/", -1))
            if (part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Workspace paths cannot contain . or .. components.");
        return path;
    }
    public static String relative(String path) {
        noControls(path);
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\"))
            throw new IllegalArgumentException("Use a relative workspace path.");
        for (String part : path.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IllegalArgumentException("Relative paths cannot contain empty, . or .. components.");
        return path;
    }
    private static void localFile(String path, String label) {
        noControls(path);
        if (!path.isEmpty() && (!Path.of(path).isAbsolute() || !Files.isRegularFile(Path.of(path)) || !Files.isReadable(Path.of(path))))
            throw new IllegalArgumentException(label + " must be an existing readable absolute file.");
    }
    /** POSIX shell quoting, required because ssh concatenates remote command arguments. */
    public static String quote(String value) { noControls(value); return "'" + value.replace("'", "'\"'\"'") + "'"; }
    public List<String> ssh(String executable, String command, boolean terminal) {
        var args = new ArrayList<String>(); args.add(executable);
        args.addAll(List.of("-o", "StrictHostKeyChecking=yes", "-o", "BatchMode=" + (terminal ? "no" : "yes"),
                "-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=5", "-o", "ServerAliveCountMax=2",
                "-o", "ControlMaster=no", "-o", "ControlPath=none", "-o", "ForwardAgent=no", "-o", "ForwardX11=no",
                "-o", "ClearAllForwardings=yes", "-p", Integer.toString(port)));
        if (!user.isEmpty()) args.addAll(List.of("-l", user));
        if (!keyFile.isEmpty()) args.addAll(List.of("-i", keyFile, "-o", "IdentitiesOnly=yes"));
        if (!knownHostsFile.isEmpty()) args.addAll(List.of("-o", "UserKnownHostsFile=" + quoteConfig(knownHostsFile)));
        args.add(terminal ? "-tt" : "-T"); args.add("--"); args.add(host); args.add(command);
        return List.copyOf(args);
    }
    private static String quoteConfig(String path) { return "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    public String terminalCommand() { return "cd -P " + quote(remoteDirectory) + " && exec \"${SHELL:-/bin/sh}\" -l"; }
}

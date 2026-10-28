package dev.goforge.remote;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns the SSH client. A heartbeat EOF also cancels the remote Go helper's process group. */
public final class GoRemoteProcess {
    public record Result(Integer exitCode, boolean cancelled, boolean timedOut, String stdout, String stderr,
                         boolean truncated, boolean localProcessesStopped) {
        public boolean success() { return !cancelled && !timedOut && exitCode != null && exitCode == 0 && !truncated; }
        public String failure() {
            return cancelled ? "Cancelled. Transfer snapshots may be incomplete; existing paths were not overwritten."
                    : timedOut ? "Remote operation timed out." : truncated ? "Remote output exceeded the capture limit."
                    : "SSH/remote command exited " + exitCode + (stderr.isBlank() ? "" : ": " + stderr);
        }
    }
    private record Owned(ProcessHandle handle, Optional<Instant> started) {
        boolean alive() { return handle.isAlive() && handle.info().startInstant().equals(started); }
    }
    private GoRemoteProcess() { }
    public static Result run(List<String> command, Path directory, Map<String,String> environment, byte[] input,
                             boolean heartbeat, BooleanSupplier cancelled, Duration timeout,
                             Consumer<String> output) throws IOException, InterruptedException {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Positive timeout required.");
        if (cancelled.getAsBoolean()) return new Result(null,true,false,"","",false,true);
        var builder = new ProcessBuilder(command).directory(directory.toFile()); dev.goforge.tools.HostTools.putEnvironment(builder.environment(),environment);
        Process process = builder.start(); var owned = new LinkedHashMap<Long,Owned>();
        owned.put(process.pid(),new Owned(process.toHandle(),process.info().startInstant()));
        var closing = new AtomicBoolean();
        var stdout = new Capture(process.getInputStream(),output);
        var stderr = new Capture(process.getErrorStream(),output);
        Thread out = daemon(stdout,"GoForge remote stdout"), err = daemon(stderr,"GoForge remote stderr");
        Thread writer = daemon(() -> {
            try (var stream=process.getOutputStream()) {
                stream.write(input); stream.flush();
                while (heartbeat && !closing.get()) { stream.write('.'); stream.flush(); Thread.sleep(200); }
            } catch (IOException ignored) { /* Remote command may finish before the next heartbeat. */ }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        },"GoForge remote stdin");
        boolean wasCancelled=false,timedOut=false,interrupted=false;Integer exit=null;long start=System.nanoTime();
        try {
            while (true) {
                process.descendants().forEach(p -> owned.putIfAbsent(p.pid(),new Owned(p,p.info().startInstant())));
                if (cancelled.getAsBoolean()) { wasCancelled=true;break; }
                if (System.nanoTime()-start>=timeout.toNanos()) { timedOut=true;break; }
                if (process.waitFor(50,TimeUnit.MILLISECONDS)) { exit=process.exitValue();break; }
            }
        } catch (InterruptedException e) { interrupted=true;throw e; }
        finally {
            interrupted |= Thread.interrupted(); closing.set(true); writer.interrupt();
            // close() may wait for a blocked writer's stream lock. Never block the cancellation owner on it.
            Thread closeInput=daemon(() -> close(process.getOutputStream()),"GoForge remote close stdin");
            process.descendants().forEach(p -> owned.putIfAbsent(p.pid(),new Owned(p,p.info().startInstant())));
            // Close stdin first: the remote helper gets a chance to terminate its own Go group.
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(350);
            while (process.isAlive() && System.nanoTime()<deadline) interrupted |= pause();
            for (var p : new ArrayList<>(owned.values()).reversed()) if (p.alive()) p.handle.destroy();
            deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(350);
            while (owned.values().stream().anyMatch(Owned::alive) && System.nanoTime()<deadline) interrupted |= pause();
            for (var p : new ArrayList<>(owned.values()).reversed()) if (p.alive()) p.handle.destroyForcibly();
            for (Thread thread : List.of(out,err,writer,closeInput)) {
                try { thread.join(500); } catch (InterruptedException e) { interrupted=true; }
            }
            close(process.getInputStream());close(process.getErrorStream());
            if (interrupted) Thread.currentThread().interrupt();
        }
        return new Result(exit,wasCancelled,timedOut,stdout.text(),stderr.text(),stdout.truncated||stderr.truncated,
                owned.values().stream().noneMatch(Owned::alive));
    }
    private static boolean pause() { try { Thread.sleep(10);return false; } catch (InterruptedException e) { return true; } }
    private static Thread daemon(Runnable work,String name) { var t=new Thread(work,name);t.setDaemon(true);t.start();return t; }
    private static void close(Closeable c) { try { c.close(); } catch(IOException ignored) { } }
    private static final class Capture implements Runnable {
        private static final int LIMIT=16*1024*1024,LIVE_LIMIT=512*1024;
        private final InputStream input;private final Consumer<String> output;private final StringBuilder text=new StringBuilder();
        volatile boolean truncated;private int liveCharacters;private boolean liveTruncated;
        Capture(InputStream input,Consumer<String> output) { this.input=input;this.output=output; }
        public void run() {
            try (var reader=new InputStreamReader(input,StandardCharsets.UTF_8)) {
                char[] buffer=new char[4096];int n;
                while ((n=reader.read(buffer))!=-1) {
                    String chunk=new String(buffer,0,n);
                    synchronized(text) { int keep=Math.min(n,LIMIT-text.length());text.append(buffer,0,keep);if(keep<n)truncated=true; }
                    int visible=Math.min(chunk.length(),LIVE_LIMIT-liveCharacters);
                    if(visible>0) {output.accept(chunk.substring(0,visible));liveCharacters+=visible;}
                    if(visible<chunk.length()&&!liveTruncated) {liveTruncated=true;output.accept("\n[Live output capped at 512 KiB for this stream; remaining bytes are drained]\n");}
                }
            } catch(IOException ignored) { }
        }
        String text() { synchronized(text) { return text.toString(); } }
    }
}

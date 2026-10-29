package dev.goforge.refactor;

import dev.goforge.tools.HostTools;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One private scratch tree. Never accepts an existing project or searches for old scratch directories. */
final class GoRefactorScratch implements AutoCloseable {
    private static final long BUDGET = TimeUnit.SECONDS.toNanos(5);
    private static final long PAUSE = TimeUnit.MILLISECONDS.toNanos(50);
    private static final int MAX_ENTRIES = 20_000;
    interface Operations {
        void delete(Path path) throws IOException;
        long nanoTime();
        void pause(long nanos) throws InterruptedException;
    }
    private static final Operations SYSTEM = new Operations() {
        public void delete(Path path) throws IOException { Files.delete(path); }
        public long nanoTime() { return System.nanoTime(); }
        public void pause(long nanos) throws InterruptedException { TimeUnit.NANOSECONDS.sleep(nanos); }
    };
    private record Entry(Path path, boolean directory, Object key) { }
    private final Path root;
    private final Object rootKey;
    private final Operations operations;
    private final boolean retryWindows;
    private boolean closed;

    static GoRefactorScratch create(String prefix) throws IOException {
        return create(prefix, SYSTEM, HostTools.isWindows());
    }
    // The injected delete/clock is only a deterministic test seam; ownership and identity checks stay real.
    static GoRefactorScratch create(String prefix, Operations operations, boolean retryWindows) throws IOException {
        Path created = Files.createTempDirectory(prefix);
        try {
            Path root = created.toRealPath();
            return new GoRefactorScratch(root, identity(root, true), operations, retryWindows);
        } catch (IOException | RuntimeException | Error failure) {
            // No contents have been created. Remove only this empty directory, without recursive fallback.
            try { Files.delete(created); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private GoRefactorScratch(Path root, Object key, Operations operations, boolean retryWindows) {
        this.root=root; this.rootKey=key; this.operations=Objects.requireNonNull(operations); this.retryWindows=retryWindows;
    }
    Path path() { return root; }

    /** Expected refusal/cancellation is not a successful guard when resource cleanup also failed. */
    static <T extends Throwable> void requireCleanFailure(T failure) throws T {
        if (hasSuppressedFailure(failure)) throw failure;
    }
    static boolean hasSuppressedFailure(Throwable failure) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current=failure; current!=null && seen.size()<32 && seen.add(current); current=current.getCause())
            if (current.getSuppressed().length!=0) return true;
        return false;
    }
    /** Keep the original reason and a bounded cleanup diagnostic visible at the refactoring UI boundary. */
    static String failureDetail(Throwable failure) {
        var details=new ArrayList<String>(); Throwable deepest=failure;
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current=failure; current!=null && seen.size()<32 && seen.add(current); current=current.getCause()) {
            deepest=current;
            for (Throwable suppressed:current.getSuppressed()) if(details.size()<3) details.add(message(suppressed));
        }
        String reason=message(deepest);
        return details.isEmpty()?reason:reason+"\nAdditional cleanup failure: "+String.join("; ",details);
    }
    private static String message(Throwable failure) {
        String value=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
        return value.substring(0,Math.min(value.length(),500));
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        interrupted();
        try { requireRoot(); } catch (NoSuchFileException absent) { closed=true; return; }
        var entries = new LinkedHashMap<Path, Entry>();
        // Do not hold a walk/list directory stream while deleting on Windows.
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 100, new SimpleFileVisitor<>() {
            private void capture(Path path, boolean directory) throws IOException {
                interrupted(); requireRoot();
                if (!path.startsWith(root) || entries.size() >= MAX_ENTRIES)
                    throw new IOException("Refactoring scratch cleanup exceeds its owned-tree limits; retained " + root);
                entries.put(path, new Entry(path, directory, identity(path, directory)));
            }
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                capture(path, true); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || attrs.isSymbolicLink())
                    throw new IOException("Refactoring scratch contains an unexpected non-regular entry; retained " + root);
                capture(path, false); return FileVisitResult.CONTINUE;
            }
        });
        long started = operations.nanoTime();
        var reverse = new ArrayList<>(entries.values()); Collections.reverse(reverse);
        for (Entry entry : reverse) {
            while (true) {
                interrupted();
                // Recheck every captured parent before each attempt. These checks are not atomic with deletion.
                requireRoot();
                for (Path parent=entry.path().getParent(); parent!=null && parent.startsWith(root); parent=parent.getParent()) {
                    Entry expected=entries.get(parent);
                    if (expected==null || !expected.directory() || !expected.key().equals(identity(parent,true)))
                        throw new IOException("Refactoring scratch parent changed; retained " + root);
                }
                try {
                    if (!entry.key().equals(identity(entry.path(),entry.directory())))
                        throw new IOException("Refactoring scratch entry changed; retained " + root);
                    operations.delete(entry.path()); break;
                } catch (NoSuchFileException absent) { break; }
                catch (FileSystemException busy) {
                    // Windows may briefly retain an executable image/directory after process exit.
                    // Permanent permission/sharing failures remain failures after one total bounded budget.
                    long remaining=BUDGET-(operations.nanoTime()-started);
                    if (!retryWindows || remaining<=0) throw busy;
                    try { operations.pause(Math.min(PAUSE,remaining)); }
                    catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                        var failure=new InterruptedIOException("Refactoring scratch cleanup interrupted; retained " + root);
                        failure.initCause(cancelled); failure.addSuppressed(busy); throw failure;
                    }
                }
            }
        }
        closed=true;
    }
    private void requireRoot() throws IOException {
        if (!rootKey.equals(identity(root,true))) throw new IOException("Refactoring scratch directory was replaced; retained " + root);
    }
    private static Object identity(Path path, boolean directory) throws IOException {
        // The shared Windows reader uses a no-follow handle and full volume/128-bit file identity.
        // Non-Windows readers use NOFOLLOW fileKey. No path-only deletion fallback is permitted.
        var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || (directory ? !attributes.isDirectory() : !attributes.isRegularFile()))
            throw new IOException("Refactoring scratch is no longer the expected physical entry: " + path);
        Object key=GoRefactorFileIdentity.read(path,directory).key();
        if (key==null) throw new IOException("Refactoring scratch identity is unavailable; retained " + path);
        return key;
    }
    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Refactoring scratch cleanup interrupted; owned files retained.");
    }
}

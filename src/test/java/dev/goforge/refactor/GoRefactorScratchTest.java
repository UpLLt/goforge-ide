package dev.goforge.refactor;

import org.junit.Test;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class GoRefactorScratchTest {
    @FunctionalInterface interface Delete { void run(Path path)throws IOException; }
    static final class Clock implements GoRefactorScratch.Operations {
        long now; int pauses; Delete delete=Files::delete; boolean interrupt;
        public void delete(Path path)throws IOException { delete.run(path); }
        public long nanoTime(){return now;}
        public void pause(long nanos)throws InterruptedException { pauses++; if(interrupt)throw new InterruptedException("controlled interruption"); now+=nanos; }
    }
    @Test public void transientExecutableAndRootDirectoryBusyAreRetriedWithoutTouchingSibling()throws Exception {
        var clock=new Clock();var attempts=new HashMap<Path,Integer>();
        var scratch=GoRefactorScratch.create("goforge-scratch-test-",clock,true);Path root=scratch.path();
        Path binary=Files.writeString(root.resolve("planner.exe"),"owned"),nested=Files.createDirectory(root.resolve("module"));
        Files.writeString(nested.resolve("source.go"),"package api\n");
        Path sentinel=Files.createTempFile(root.getParent(),"goforge-sentinel-",".txt");Files.writeString(sentinel,"keep");
        clock.delete=p->{int count=attempts.merge(p,1,Integer::sum);if(p.equals(binary)&&count<3)throw new AccessDeniedException(p.toString());if(p.equals(root)&&count<2)throw new FileSystemException(p.toString(),null,"busy directory");Files.delete(p);};
        try { scratch.close();scratch.close();assertFalse(Files.exists(root));assertEquals("keep",Files.readString(sentinel));assertEquals(3,clock.pauses);assertEquals(Integer.valueOf(3),attempts.get(binary)); }
        finally {Files.deleteIfExists(sentinel);remove(root);}
    }
    @Test public void oneDeadlineCoversTheWholeTreeAndPermanentFailurePreservesPrimary()throws Exception {
        var clock=new Clock();var scratch=GoRefactorScratch.create("goforge-scratch-test-",clock,true);Path root=scratch.path();
        Files.writeString(root.resolve("planner.exe"),"owned");
        clock.delete=p->{if(!p.equals(root)&&clock.now<TimeUnit.SECONDS.toNanos(4))throw new AccessDeniedException(p.toString());if(p.equals(root))throw new FileSystemException(p.toString(),null,"permanent busy");Files.delete(p);};
        var refusal=new IllegalStateException("non-alias receiver required");
        try {
            var actual=assertThrows(IllegalStateException.class,()->{try(scratch){throw refusal;}});
            assertSame(refusal,actual);assertEquals(1,actual.getSuppressed().length);assertTrue(actual.getSuppressed()[0] instanceof FileSystemException);
            assertEquals(TimeUnit.SECONDS.toNanos(5),clock.now);assertEquals(100,clock.pauses);assertTrue(Files.isDirectory(root));assertFalse(Files.exists(root.resolve("planner.exe")));
        } finally {remove(root);}
    }
    @Test public void interruptionDuringRetryIsPreservedAndRetainsOwnedOutput()throws Exception {
        var clock=new Clock();clock.interrupt=true;var scratch=GoRefactorScratch.create("goforge-scratch-test-",clock,true);Path binary=Files.writeString(scratch.path().resolve("planner.exe"),"owned");
        clock.delete=p->{throw new AccessDeniedException(p.toString());};
        try {var failure=assertThrows(InterruptedIOException.class,scratch::close);assertTrue(Thread.currentThread().isInterrupted());assertEquals(1,failure.getSuppressed().length);assertEquals("owned",Files.readString(binary));}
        finally {Thread.interrupted();remove(scratch.path());}
    }
    @Test public void preexistingInterruptionDoesNotDeleteAndNonWindowsDoesNotRetry()throws Exception {
        var clock=new Clock();var scratch=GoRefactorScratch.create("goforge-scratch-test-",clock,false);Path binary=Files.writeString(scratch.path().resolve("planner"),"owned");
        var calls=new AtomicInteger();clock.delete=p->{calls.incrementAndGet();throw new AccessDeniedException(p.toString());};
        try {Thread.currentThread().interrupt();assertThrows(InterruptedIOException.class,scratch::close);assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,calls.get());Thread.interrupted();assertThrows(AccessDeniedException.class,scratch::close);assertEquals(1,calls.get());assertEquals(0,clock.pauses);assertTrue(Files.exists(binary));}
        finally {Thread.interrupted();remove(scratch.path());}
    }
    @Test public void replacedRootIsRefusedAndBothTreesAreRetained()throws Exception {
        var scratch=GoRefactorScratch.create("goforge-scratch-test-");Path root=scratch.path(),moved=root.resolveSibling(root.getFileName()+"-moved");
        Files.writeString(root.resolve("planner"),"original");Files.move(root,moved);Files.createDirectory(root);Files.writeString(root.resolve("sentinel"),"replacement");
        try {assertThrows(IOException.class,scratch::close);assertEquals("original",Files.readString(moved.resolve("planner")));assertEquals("replacement",Files.readString(root.resolve("sentinel")));}
        finally {remove(root);remove(moved);}
    }
    @Test public void replacementAfterBusyFailureIsNotDeletedOnRetry()throws Exception {
        var clock=new Clock();var scratch=GoRefactorScratch.create("goforge-scratch-test-",clock,true);Path root=scratch.path(),binary=Files.writeString(root.resolve("planner.exe"),"original");
        Path held=Files.createTempDirectory(root.getParent(),"goforge-held-original-").resolve("original.exe");
        clock.delete=p->{Files.move(binary,held);Files.writeString(binary,"replacement");throw new AccessDeniedException(p.toString());};
        try {assertThrows(IOException.class,scratch::close);assertEquals(1,clock.pauses);assertEquals("replacement",Files.readString(binary));assertEquals("original",Files.readString(held));}
        finally {remove(root);remove(held.getParent());}
    }
    @Test public void expectedRefusalCompilationFallbackAndCancellationNeverSwallowCleanupFailure()throws Exception {
        for (RuntimeException failure:List.of(new IllegalStateException("non-alias receiver required"),new GoAddParameter.CompilationRequired("compile proof required"),new java.util.concurrent.CancellationException("cancelled"))) {
            GoRefactorScratch.requireCleanFailure(failure);
            var cleanup=new AccessDeniedException("owned planner.exe");failure.addSuppressed(cleanup);
            assertSame(failure,assertThrows(RuntimeException.class,()->GoRefactorScratch.requireCleanFailure(failure)));
            String visible=GoRefactorScratch.failureDetail(new IllegalStateException("worker wrapper",failure));
            assertTrue(visible,visible.contains(failure.getMessage()));assertTrue(visible,visible.contains("owned planner.exe"));
            assertTrue(GoRefactorScratch.hasSuppressedFailure(new IllegalStateException(failure)));
        }
    }
    @Test public void cleanupDiagnosticIsBoundedAndHandlesCycles() {
        var outer=new IllegalStateException("outer");var inner=new IllegalStateException("x".repeat(1000),outer);outer.initCause(inner);
        for(int i=0;i<10;i++)inner.addSuppressed(new IOException("y".repeat(1000)));
        String visible=GoRefactorScratch.failureDetail(outer);assertTrue(visible.length()<2100);assertTrue(GoRefactorScratch.hasSuppressedFailure(outer));
    }
    private static void remove(Path root)throws IOException {
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;
        List<Path> paths;try(var walk=Files.walk(root)){paths=walk.sorted(Comparator.reverseOrder()).toList();}
        for(Path p:paths)Files.deleteIfExists(p);
    }
}

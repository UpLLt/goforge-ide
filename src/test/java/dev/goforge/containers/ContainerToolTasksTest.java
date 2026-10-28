package dev.goforge.containers;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Real platform task/publication ordering with controlled resources, not Docker integration. */
public final class ContainerToolTasksTest extends BasePlatformTestCase {
    private static final String ASYNC_PROPERTY = "intellij.progress.task.ignoreHeadless";
    private String previousAsyncProperty;

    @Override protected void setUp() throws Exception {
        super.setUp();
        // The pinned platform otherwise runs headless Task.queue synchronously.
        // This selects its real asynchronous implementation; it does not relax locks.
        previousAsyncProperty = System.getProperty(ASYNC_PROPERTY);
        System.setProperty(ASYNC_PROPERTY, "true");
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (previousAsyncProperty == null) System.clearProperty(ASYNC_PROPERTY);
            else System.setProperty(ASYNC_PROPERTY, previousAsyncProperty);
        } finally { super.tearDown(); }
    }

    public void testCancelAfterWorkerCompletionAbandonsOnceBeforeReleasingBusy() throws Exception {
        assertEdt();
        var tasks = new ContainerToolTasks(getProject());
        var published = new AtomicInteger();
        var cleanupCalls = new AtomicInteger();
        var cleanupOnEdt = new AtomicBoolean();
        var cleanupStarted = new CountDownLatch(1);
        var allowCleanup = new CountDownLatch(1);
        var failure = new AtomicReference<String>();
        try {
            tasks.submitManaged("Controlled completed resource", (cancelled, progress) -> {
                assertFalse(ApplicationManager.getApplication().isDispatchThread());
                return "owned resource";
            }, value -> published.incrementAndGet(), failure::set, ignored -> {}, () -> {
                cleanupOnEdt.set(ApplicationManager.getApplication().isDispatchThread());
                cleanupCalls.incrementAndGet(); cleanupStarted.countDown();
                awaitGate(allowCleanup);
                return "Controlled resource removed";
            });
            awaitWithoutDispatch(tasks::workerCompleted);
            assertEquals("EDT publication is blocked while this test owns the EDT", 0, published.get());
            tasks.cancel(); tasks.cancel();
            awaitWithDispatch(() -> cleanupStarted.getCount() == 0);
            assertTrue("A second operation must not start before rollback finishes", tasks.busy());
            assertEquals(0, published.get());
            allowCleanup.countDown();
            awaitWithDispatch(() -> !tasks.busy() && failure.get() != null);
            assertEquals(1, cleanupCalls.get()); assertFalse(cleanupOnEdt.get());
            assertTrue(failure.get().contains("Cancelled"));
            assertTrue(failure.get().contains("Controlled resource removed"));
            assertEquals(0, published.get());
        } finally { allowCleanup.countDown(); Disposer.dispose(tasks); }
    }

    public void testDisposedOwnerAbandonsCompletedUnpublishedResultOnce() throws Exception {
        assertEdt();
        var tasks = new ContainerToolTasks(getProject());
        var published = new AtomicInteger();
        var failureCallbacks = new AtomicInteger();
        var cleanupCalls = new AtomicInteger();
        var cleanupOnEdt = new AtomicBoolean();
        try {
            tasks.submitManaged("Controlled disposal window", (cancelled, progress) -> "owned resource",
                    value -> published.incrementAndGet(), value -> failureCallbacks.incrementAndGet(), ignored -> {}, () -> {
                        cleanupOnEdt.set(ApplicationManager.getApplication().isDispatchThread());
                        cleanupCalls.incrementAndGet(); return "Removed";
                    });
            awaitWithoutDispatch(tasks::workerCompleted);
            assertEquals(0, published.get());
            tasks.cancel();
            // This is the same real Disposer callback registered on project closure.
            // The shared light project itself remains alive for other tests.
            Disposer.dispose(tasks);
            awaitWithDispatch(() -> !tasks.busy() && cleanupCalls.get() == 1);
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            assertEquals(1, cleanupCalls.get()); assertFalse(cleanupOnEdt.get());
            assertEquals(0, published.get()); assertEquals(0, failureCallbacks.get());
        } finally { if (!Disposer.isDisposed(tasks)) Disposer.dispose(tasks); }
    }

    public void testNormalPublicationTransfersOwnershipWithoutAbandonment() throws Exception {
        assertEdt();
        var tasks = new ContainerToolTasks(getProject());
        var received = new AtomicReference<String>();
        var publicationOnEdt = new AtomicBoolean();
        var failure = new AtomicReference<String>();
        var cleanupCalls = new AtomicInteger();
        try {
            tasks.submitManaged("Controlled successful transfer", (cancelled, progress) -> {
                assertFalse(ApplicationManager.getApplication().isDispatchThread()); return "resource identity";
            }, value -> { publicationOnEdt.set(ApplicationManager.getApplication().isDispatchThread()); received.set(value); },
                    failure::set, ignored -> {}, () -> { cleanupCalls.incrementAndGet(); return "Removed"; });
            awaitWithoutDispatch(tasks::workerCompleted);
            assertNull(received.get());
            awaitWithDispatch(() -> received.get() != null && !tasks.busy());
            assertEquals("resource identity", received.get()); assertTrue(publicationOnEdt.get());
            assertNull(failure.get()); assertEquals(0, cleanupCalls.get());
            Disposer.dispose(tasks);
            assertEquals("Committed resources are not abandoned on owner disposal", 0, cleanupCalls.get());
        } finally { if (!Disposer.isDisposed(tasks)) Disposer.dispose(tasks); }
    }

    public void testFailedWorkerAbandonsAndPreservesFailureAndCleanupResult() throws Exception {
        assertEdt();
        var tasks = new ContainerToolTasks(getProject());
        var published = new AtomicInteger();
        var cleanupCalls = new AtomicInteger();
        var failure = new AtomicReference<String>();
        try {
            tasks.submitManaged("Controlled start failure", (cancelled, progress) -> {
                throw new IOException("Controlled failure after resource creation");
            }, value -> published.incrementAndGet(), failure::set, ignored -> {}, () -> {
                assertFalse(ApplicationManager.getApplication().isDispatchThread());
                cleanupCalls.incrementAndGet(); return "Owned resource cleaned";
            });
            awaitWithDispatch(() -> failure.get() != null && !tasks.busy());
            assertEquals(0, published.get()); assertEquals(1, cleanupCalls.get());
            assertTrue(failure.get().contains("Controlled failure after resource creation"));
            assertTrue(failure.get().contains("Owned resource cleaned"));
        } finally { Disposer.dispose(tasks); }
    }

    public void testOwnerDisposalDuringWorkerDefersCleanupUntilWorkerFinishes() throws Exception {
        assertEdt();
        var tasks = new ContainerToolTasks(getProject());
        var entered = new CountDownLatch(1);
        var allowReturn = new CountDownLatch(1);
        var workerFinishedResource = new AtomicBoolean();
        var cleanupBeforeWorker = new AtomicBoolean();
        var cleanupCalls = new AtomicInteger();
        var published = new AtomicInteger();
        try {
            tasks.submitManaged("Controlled creation in flight", (cancelled, progress) -> {
                entered.countDown(); awaitGate(allowReturn);
                workerFinishedResource.set(true); return "late created resource";
            }, value -> published.incrementAndGet(), ignored -> {}, ignored -> {}, () -> {
                cleanupBeforeWorker.set(!workerFinishedResource.get());
                cleanupCalls.incrementAndGet(); return "Late resource removed";
            });
            awaitWithoutDispatch(() -> entered.getCount() == 0);
            assertFalse(tasks.workerCompleted());
            Disposer.dispose(tasks);
            assertEquals("Cleanup cannot race unfinished resource creation", 0, cleanupCalls.get());
            allowReturn.countDown();
            awaitWithDispatch(() -> !tasks.busy() && cleanupCalls.get() == 1);
            assertFalse(cleanupBeforeWorker.get()); assertEquals(0, published.get());
        } finally {
            allowReturn.countDown();
            if (!Disposer.isDisposed(tasks)) Disposer.dispose(tasks);
        }
    }

    public void testCleanupFailureIsVisibleWithoutPublishingSuccess() throws Exception {
        assertEdt();
        var tasks = new ContainerToolTasks(getProject());
        var failure = new AtomicReference<String>();
        var published = new AtomicInteger();
        try {
            tasks.submitManaged("Controlled rollback failure", (cancelled, progress) -> "resource",
                    value -> published.incrementAndGet(), failure::set, ignored -> {}, () -> {
                        throw new IllegalStateException("Controlled daemon cleanup failure");
                    });
            awaitWithoutDispatch(tasks::workerCompleted); tasks.cancel();
            awaitWithDispatch(() -> !tasks.busy() && failure.get() != null);
            assertEquals(0, published.get());
            assertTrue(failure.get().contains("Resource cleanup failed"));
            assertTrue(failure.get().contains("Controlled daemon cleanup failure"));
        } finally { Disposer.dispose(tasks); }
    }

    private static void assertEdt() { assertTrue(ApplicationManager.getApplication().isDispatchThread()); }
    private static void awaitGate(CountDownLatch gate) {
        try {
            if (!gate.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Controlled test gate timed out");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    private static void awaitWithoutDispatch(BooleanSupplier condition) throws InterruptedException { await(condition, false); }
    private static void awaitWithDispatch(BooleanSupplier condition) throws InterruptedException { await(condition, true); }
    private static void await(BooleanSupplier condition, boolean dispatch) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            if (dispatch) PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            Thread.sleep(2);
        }
        assertTrue("Controlled platform task did not reach the required phase", condition.getAsBoolean());
    }
}

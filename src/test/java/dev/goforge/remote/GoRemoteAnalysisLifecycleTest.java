package dev.goforge.remote;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic publication boundary; no SSH connection or gopls wrapper is created. */
public final class GoRemoteAnalysisLifecycleTest extends BasePlatformTestCase {
    public void testStopInvalidatesACompletedSnapshotBeforeItsEdtPublication() {
        var service=new GoRemoteService(getProject());var calls=new AtomicInteger();
        try {
            long request=service.beginAnalysisRequest();
            service.invalidateAnalysisRequests();
            assertFalse(service.commitAnalysisRequest(request,calls::incrementAndGet));
            assertEquals(0,calls.get());
        }finally{service.dispose();}
    }
    public void testNewStartInvalidatesOldCallbackButAllowsCurrentPublication() {
        var service=new GoRemoteService(getProject());var calls=new AtomicInteger();
        try {
            long old=service.beginAnalysisRequest(),current=service.beginAnalysisRequest();
            assertFalse(service.commitAnalysisRequest(old,calls::incrementAndGet));
            assertTrue(service.commitAnalysisRequest(current,calls::incrementAndGet));
            assertEquals(1,calls.get());
        }finally{service.dispose();}
    }
    public void testOwnerDisposalInvalidatesLateSnapshotCallback() {
        var service=new GoRemoteService(getProject());var calls=new AtomicInteger();
        long request=service.beginAnalysisRequest();service.dispose();
        assertFalse(service.commitAnalysisRequest(request,calls::incrementAndGet));assertEquals(0,calls.get());
    }
}

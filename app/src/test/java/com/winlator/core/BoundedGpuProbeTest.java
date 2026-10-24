package com.winlator.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class BoundedGpuProbeTest {
    @Test public void returnsSuccessfulWorkerResult() throws Exception {
        assertEquals("Adreno", BoundedGpuProbe.run(() -> "Adreno", 1_000));
    }

    @Test public void retainsCompletionBeforeCallerWaits() throws Exception {
        FutureTask<String> completed = new FutureTask<>(() -> "Adreno");
        completed.run();
        assertEquals("Adreno", BoundedGpuProbe.await(completed, 1));
    }

    @Test public void reportsWorkerFailureWithoutWaitingForTimeout() throws Exception {
        IOException failure = new IOException("EGL probe failed");
        try {
            BoundedGpuProbe.run(() -> { throw failure; }, 1_000);
            fail("The worker failure must reach the caller.");
        }
        catch (ExecutionException error) {
            assertSame(failure, error.getCause());
        }
    }

    @Test public void timesOutAndCancelsUnfinishedProbe() throws Exception {
        FutureTask<String> unfinished = new FutureTask<>(() -> "not started");
        try {
            BoundedGpuProbe.await(unfinished, 10);
            fail("An unfinished probe must time out.");
        }
        catch (TimeoutException expected) {
            assertTrue(unfinished.isCancelled());
        }
    }

    @Test public void preservesCallerInterruptionAndCancelsWorker() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerFinished = new CountDownLatch(1);
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                BoundedGpuProbe.run(() -> {
                    workerStarted.countDown();
                    try {
                        new CountDownLatch(1).await();
                        return "unexpected";
                    }
                    finally {
                        workerFinished.countDown();
                    }
                }, 5_000);
                unexpected.set(new AssertionError("Interrupted caller returned a result."));
            }
            catch (InterruptedException expected) {
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
            catch (Throwable error) {
                unexpected.set(error);
            }
        });
        caller.start();
        assertTrue(workerStarted.await(1, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(1_000);
        assertFalse("The interrupted caller must finish.", caller.isAlive());
        assertNull(unexpected.get());
        assertTrue("The caller's interrupt flag must remain set.", interruptPreserved.get());
        assertTrue("The worker must receive cancellation.", workerFinished.await(1, TimeUnit.SECONDS));
    }
}

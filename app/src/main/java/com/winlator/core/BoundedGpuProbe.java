package com.winlator.core;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Completion that survives a probe finishing before its caller begins waiting. */
final class BoundedGpuProbe {
    private BoundedGpuProbe() {}

    static <T> T run(Callable<T> probe, long timeoutMillis)
            throws ExecutionException, InterruptedException, TimeoutException {
        FutureTask<T> task = new FutureTask<>(probe);
        Thread worker = new Thread(task, "Winlator-GPU-probe");
        worker.setDaemon(true);
        worker.start();
        return await(task, timeoutMillis);
    }

    static <T> T await(FutureTask<T> task, long timeoutMillis)
            throws ExecutionException, InterruptedException, TimeoutException {
        try {
            // FutureTask records both successful and exceptional completion;
            // unlike notify(), neither result can be lost before get().
            return task.get(timeoutMillis, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        }
        finally {
            // Native EGL calls may not react to interruption, but provisioning
            // must still return a bounded failure rather than wait forever.
            if (!task.isDone()) task.cancel(true);
        }
    }
}

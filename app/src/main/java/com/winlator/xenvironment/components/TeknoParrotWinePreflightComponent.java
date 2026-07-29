package com.winlator.xenvironment.components;

import android.os.SystemClock;
import android.util.Log;

import com.winlator.core.FileUtils;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a required Wine installer exactly once before the game and bridge are
 * started.  The marker is trusted only while the installed payload is still
 * present, so prefix replacement or partial package extraction automatically
 * triggers a repair on the next launch.
 */
public final class TeknoParrotWinePreflightComponent
        extends GuestProgramLauncherComponent {
    private static final String TAG = "TeknoParrotWinePreflight";
    private static final long INSTALL_TIMEOUT_MILLIS = 90_000;
    private static final long PAYLOAD_SETTLE_TIMEOUT_MILLIS = 10_000;
    private final String payloadName;
    private final File markerFile;
    private final boolean runEveryStart;
    private final File[] payloadDirectories;

    public TeknoParrotWinePreflightComponent(
            String payloadName, File markerFile, File... payloadDirectories) {
        this(payloadName, markerFile, false, payloadDirectories);
    }

    public TeknoParrotWinePreflightComponent(
            String payloadName, File markerFile, boolean runEveryStart,
            File... payloadDirectories) {
        if (payloadName == null || payloadName.isEmpty() || markerFile == null ||
            payloadDirectories == null || payloadDirectories.length == 0)
            throw new IllegalArgumentException("A Wine preflight payload and marker are required.");
        this.payloadName = payloadName;
        this.markerFile = markerFile;
        this.runEveryStart = runEveryStart;
        this.payloadDirectories = payloadDirectories;
    }

    @Override
    public void start() {
        if (!runEveryStart && markerFile.isFile() && isPayloadInstalled()) {
            Log.i(TAG, payloadName + " is already installed; skipping the Wine preflight.");
            return;
        }

        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicInteger exitStatus = new AtomicInteger(Integer.MIN_VALUE);
        setTerminationCallback((status) -> {
            exitStatus.set(status);
            completed.set(true);
        });
        Log.i(TAG, "Installing required Wine payload " + payloadName + '.');
        super.start();

        long deadline = SystemClock.uptimeMillis() + INSTALL_TIMEOUT_MILLIS;
        while (!completed.get() && SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(20);
        if (!completed.get()) {
            super.stop();
            throw new IllegalStateException(
                payloadName + " did not finish within " + INSTALL_TIMEOUT_MILLIS + " ms.");
        }

        long settleDeadline = SystemClock.uptimeMillis() + PAYLOAD_SETTLE_TIMEOUT_MILLIS;
        while (!isPayloadInstalled() && SystemClock.uptimeMillis() < settleDeadline)
            SystemClock.sleep(50);
        if (exitStatus.get() != 0 || !isPayloadInstalled())
            throw new IllegalStateException(
                payloadName + " installation failed with status " + exitStatus.get() + '.');
        if (!ensureMarker())
            throw new IllegalStateException(
                payloadName + " installed, but its durable marker could not be written.");
        Log.i(TAG, "Installed and validated required Wine payload " + payloadName + '.');
    }

    private boolean isPayloadInstalled() {
        for (File directory : payloadDirectories) {
            File[] matches = directory.listFiles((ignored, name) ->
                name.equalsIgnoreCase(payloadName));
            if (matches != null && matches.length != 0 && matches[0].isFile() &&
                matches[0].length() != 0)
                return true;
        }
        return false;
    }

    private boolean ensureMarker() {
        if (markerFile.isFile()) return true;
        return FileUtils.writeStringAtomic(
            markerFile, payloadName + "\n" + System.currentTimeMillis() + "\n");
    }
}

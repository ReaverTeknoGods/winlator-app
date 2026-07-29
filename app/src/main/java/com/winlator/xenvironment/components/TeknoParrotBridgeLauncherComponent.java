package com.winlator.xenvironment.components;

import android.os.SystemClock;

import java.io.File;

/**
 * Starts the Wine-side TeknoParrot bridge and blocks subsequent environment
 * components until its named pipe and shared mapping are ready.
 */
public final class TeknoParrotBridgeLauncherComponent
        extends GuestProgramLauncherComponent {
    private static final long READY_TIMEOUT_MILLIS = 15_000;
    private final File readyFile;

    public TeknoParrotBridgeLauncherComponent(File readyFile) {
        if (readyFile == null)
            throw new IllegalArgumentException("A bridge-ready file is required.");
        this.readyFile = readyFile;
    }

    @Override
    public void start() {
        super.start();
        long deadline = SystemClock.uptimeMillis() + READY_TIMEOUT_MILLIS;
        while (!readyFile.isFile() && SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(10);
        if (!readyFile.isFile()) {
            super.stop();
            throw new IllegalStateException(
                "The Wine-side TeknoParrot pipe was not ready within 15 seconds.");
        }
    }
}

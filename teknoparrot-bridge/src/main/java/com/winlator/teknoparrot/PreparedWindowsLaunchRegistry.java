package com.winlator.teknoparrot;

import java.util.HashMap;
import java.util.Map;

/**
 * In-process immutable handoff for validated Windows launch data.  Only the
 * opaque prepared-session id crosses the explicit Activity intent boundary.
 */
public final class PreparedWindowsLaunchRegistry {
    private static final Map<String, Launch> ENTRIES = new HashMap<>();

    private PreparedWindowsLaunchRegistry() {
    }

    static synchronized void register(
            String sessionId,
            ActivityLaunchContract.Request request,
            SessionContract.PreparedRequest prepared,
            String sharedPagePath) {
        unregister(sessionId);
        ENTRIES.put(sessionId, new Launch(
            request.executable,
            request.workingDirectory,
            request.arguments,
            request.libraryDirectory,
            request.controlsProfileId,
            request.frameRateLimit,
            request.resolutionWidth,
            request.resolutionHeight,
            request.debugLoggingEnabled,
            request.compatibilityPreset,
            request.displayMode,
            request.profileConfigIni,
            request.scopedGameDirectory,
            prepared.flags == SessionContract.SESSION_FLAG_PRODUCTION,
            prepared.pipePort,
            prepared.sessionId,
            prepared.tokenHex,
            prepared.pipeName32,
            prepared.pipeName64,
            sharedPagePath));
    }

    static synchronized void unregister(String sessionId) {
        if (sessionId != null)
            ENTRIES.remove(sessionId);
    }

    public static synchronized Launch find(String sessionId) {
        Launch launch = sessionId != null ? ENTRIES.get(sessionId) : null;
        return launch != null ? new Launch(
            launch.executable,
            launch.workingDirectory,
            launch.arguments,
            launch.libraryDirectory,
            launch.controlsProfileId,
            launch.frameRateLimit,
            launch.resolutionWidth,
            launch.resolutionHeight,
            launch.debugLoggingEnabled,
            launch.compatibilityPreset,
            launch.displayMode,
            launch.profileConfigIni,
            launch.scopedGameDirectory,
            launch.productionBridge,
            launch.pipePort,
            launch.sessionId,
            launch.tokenHex,
            launch.pipeName32,
            launch.pipeName64,
            launch.sharedPagePath) : null;
    }

    public static final class Launch {
        public final String executable;
        public final String workingDirectory;
        public final String[] arguments;
        public final String libraryDirectory;
        public final int controlsProfileId;
        public final int frameRateLimit;
        public final int resolutionWidth;
        public final int resolutionHeight;
        public final boolean debugLoggingEnabled;
        public final String compatibilityPreset;
        public final String displayMode;
        public final String profileConfigIni;
        public final String scopedGameDirectory;
        public final boolean productionBridge;
        public final int pipePort;
        public final String sessionId;
        public final String tokenHex;
        public final String pipeName32;
        public final String pipeName64;
        public final String sharedPagePath;

        private Launch(
                String executable,
                String workingDirectory,
                String[] arguments,
                String libraryDirectory,
                int controlsProfileId,
                int frameRateLimit,
                int resolutionWidth,
                int resolutionHeight,
                boolean debugLoggingEnabled,
                String compatibilityPreset,
                String displayMode,
                String profileConfigIni,
                String scopedGameDirectory,
                boolean productionBridge,
                int pipePort,
                String sessionId,
                String tokenHex,
                String pipeName32,
                String pipeName64,
                String sharedPagePath) {
            this.executable = executable;
            this.workingDirectory = workingDirectory;
            this.arguments = arguments != null ? arguments.clone() : new String[0];
            this.libraryDirectory = libraryDirectory;
            this.controlsProfileId = controlsProfileId;
            this.frameRateLimit = frameRateLimit;
            this.resolutionWidth = resolutionWidth;
            this.resolutionHeight = resolutionHeight;
            this.debugLoggingEnabled = debugLoggingEnabled;
            this.compatibilityPreset = compatibilityPreset;
            this.displayMode = displayMode;
            this.profileConfigIni = profileConfigIni;
            this.scopedGameDirectory = scopedGameDirectory;
            this.productionBridge = productionBridge;
            this.pipePort = pipePort;
            this.sessionId = sessionId;
            this.tokenHex = tokenHex;
            this.pipeName32 = pipeName32;
            this.pipeName64 = pipeName64;
            this.sharedPagePath = sharedPagePath;
        }
    }
}

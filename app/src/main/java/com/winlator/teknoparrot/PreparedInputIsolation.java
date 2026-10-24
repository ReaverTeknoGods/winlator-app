package com.winlator.teknoparrot;

/** Android-free routing policy shared by Activity dispatch and the JVM audit. */
public final class PreparedInputIsolation {
    public enum Route {
        LEGACY,
        ANDROID_UI,
        FORWARDED_ONLY
    }

    private PreparedInputIsolation() {}

    public static final class FocusState {
        public static final int UNCHANGED = 0;
        public static final int FOCUSED = 1;
        public static final int UNFOCUSED = -1;
        private boolean androidUiOwnsInput;

        public int update(
                boolean preparedLaunch,
                boolean authenticatedBridge,
                boolean diagnostic,
                boolean androidUiOwnsInput) {
            if (!isolatesPreparedInput(preparedLaunch, authenticatedBridge, diagnostic)) {
                this.androidUiOwnsInput = false;
                return UNCHANGED;
            }
            if (this.androidUiOwnsInput == androidUiOwnsInput)
                return UNCHANGED;
            this.androidUiOwnsInput = androidUiOwnsInput;
            // Focus=false makes the host release all cabinet buttons/axes.
            // Otherwise a release swallowed by a same-Activity drawer/editor
            // could leave a previously forwarded button held indefinitely.
            return androidUiOwnsInput ? UNFOCUSED : FOCUSED;
        }
    }

    public static Route keyRoute(
            boolean preparedLaunch,
            boolean authenticatedBridge,
            boolean diagnostic,
            boolean androidUiOwnsInput,
            boolean systemKey) {
        if (!isolatesPreparedInput(preparedLaunch, authenticatedBridge, diagnostic))
            return Route.LEGACY;
        return androidUiOwnsInput || systemKey
                ? Route.ANDROID_UI : Route.FORWARDED_ONLY;
    }

    public static Route motionRoute(
            boolean preparedLaunch,
            boolean authenticatedBridge,
            boolean diagnostic,
            boolean androidUiOwnsInput,
            boolean controllerMotion) {
        if (!isolatesPreparedInput(preparedLaunch, authenticatedBridge, diagnostic))
            return Route.LEGACY;
        if (androidUiOwnsInput)
            return Route.ANDROID_UI;
        return controllerMotion ? Route.FORWARDED_ONLY : Route.LEGACY;
    }

    public static boolean isolatesPreparedInput(
            boolean preparedLaunch,
            boolean authenticatedBridge,
            boolean diagnostic) {
        // Registry attachment proves that this Activity belongs to a managed
        // session. A temporary socket disconnect must not enable raw guest
        // keyboard/XInput as a second, uncontrolled input path.
        return preparedLaunch && authenticatedBridge && !diagnostic;
    }
}

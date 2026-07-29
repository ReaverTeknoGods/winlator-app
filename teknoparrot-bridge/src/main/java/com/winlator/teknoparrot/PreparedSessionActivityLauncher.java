package com.winlator.teknoparrot;

import android.content.Context;
import android.content.Intent;

/** The single explicit-Intent boundary from a validated prepared session to Winlator UI. */
public final class PreparedSessionActivityLauncher {
    public static final String EXTRA_FORWARDED_INPUT_DIAGNOSTIC =
        "com.teknoparrot.bridge.v1.FORWARDED_INPUT_ACTIVITY_DIAGNOSTIC";
    public static final String EXTRA_PREPARED_WINDOWS_LAUNCH =
        "com.teknoparrot.bridge.v1.PREPARED_WINDOWS_LAUNCH";

    private static final String XSERVER_ACTIVITY_CLASS =
        "com.winlator.XServerDisplayActivity";

    private PreparedSessionActivityLauncher() {
    }

    static String launch(
        Context context,
        ActivityLaunchContract.Request request,
        SessionContract.PreparedRequest prepared,
        String sharedPagePath) {
        if (context == null || request == null)
            throw new IllegalArgumentException("A context and Activity launch request are required.");
        request.validatePrepared(prepared);

        Intent intent = new Intent();
        intent.setClassName(context.getPackageName(), XSERVER_ACTIVITY_CLASS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra("container_id", request.containerId);
        intent.putExtra(ForwardedInputSessionRegistry.EXTRA_SESSION_ID, request.sessionId);
        if (ActivityLaunchContract.FORWARDED_INPUT_DIAGNOSTIC.equals(request.launchKind)) {
            PreparedWindowsLaunchRegistry.unregister(request.sessionId);
            intent.putExtra(EXTRA_FORWARDED_INPUT_DIAGNOSTIC, true);
        }
        else {
            if (prepared.flags == SessionContract.SESSION_FLAG_PRODUCTION &&
                (sharedPagePath == null || sharedPagePath.isEmpty()))
                throw new IllegalStateException("The production shared page was not exposed.");
            PreparedWindowsLaunchRegistry.register(
                request.sessionId, request, prepared, sharedPagePath);
            intent.putExtra(EXTRA_PREPARED_WINDOWS_LAUNCH, true);
        }
        context.startActivity(intent);
        return request.status();
    }
}

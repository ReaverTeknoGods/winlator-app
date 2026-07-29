package com.winlator.teknoparrot;

import android.app.Activity;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** In-process handoff from the protected bridge service to Winlator's Activity. */
public final class ForwardedInputSessionRegistry {
    public static final String EXTRA_SESSION_ID =
            "com.teknoparrot.bridge.v1.FORWARDED_INPUT_SESSION_ID";

    private static final Map<String, Entry> ENTRIES = new HashMap<>();

    private ForwardedInputSessionRegistry() {}

    static synchronized void register(String sessionId, int port, byte[] token) {
        unregister(sessionId);
        ENTRIES.put(sessionId, new Entry(port, token));
    }

    static synchronized void unregister(String sessionId) {
        PreparedWindowsLaunchRegistry.unregister(sessionId);
        Entry entry = ENTRIES.remove(sessionId);
        if (entry == null)
            return;
        if (entry.client != null)
            entry.client.close();
        Arrays.fill(entry.token, (byte)0);
    }

    public static synchronized ForwardedInputActivityBridge attach(
            Activity activity,
            String sessionId) {
        if (activity == null || sessionId == null || sessionId.length() != 32)
            return null;
        Entry entry = ENTRIES.get(sessionId);
        if (entry == null)
            return null;
        if (entry.client != null)
            entry.client.close();

        ForwardedInputClient client = new ForwardedInputClient(
                sessionId, entry.port, entry.token);
        entry.client = client;
        return new ForwardedInputActivityBridge(activity, sessionId, client);
    }

    static synchronized void detach(String sessionId, ForwardedInputClient client) {
        Entry entry = ENTRIES.get(sessionId);
        if (entry != null && entry.client == client)
            entry.client = null;
    }

    private static final class Entry {
        final int port;
        final byte[] token;
        ForwardedInputClient client;

        Entry(int port, byte[] token) {
            this.port = port;
            this.token = token.clone();
        }
    }
}

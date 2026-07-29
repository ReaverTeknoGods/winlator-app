package com.winlator.teknoparrot;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

final class BridgeProtocol {
    static final int VERSION = 1;
    static final int PAGE_SIZE = 4096;
    static final int MAX_FRAME_BYTES = 64 * 1024;
    static final int MAX_PIPE_NAME_BYTES = 128;

    static final String ACTION = "com.teknoparrot.bridge.v1.WINLATOR_BIND";
    static final String DESCRIPTOR = "com.teknoparrot.bridge.v1.ITeknoParrotWinlatorService";
    static final String PIPE_NAME = "TeknoParrot_WinlatorProbe";
    static final String FORWARDED_INPUT_CHANNEL_NAME = "TeknoParrot_ForwardedInput";
    static final int CHANNEL_KIND_NAMED_PIPE = 1;
    static final int CHANNEL_KIND_FORWARDED_INPUT = 2;
    // android.os.IBinder.FIRST_CALL_TRANSACTION is a stable protocol constant
    // equal to 1. Keeping the wire constant here also lets the socket protocol
    // run in the host JVM audit without android.jar.
    static final int OPEN_PAGE_TRANSACTION = 1 + 32;
    static final int INSTALL_RUNTIME_PACKAGE_TRANSACTION = 1 + 33;
    static final int QUERY_RUNTIME_PACKAGES_TRANSACTION = 1 + 34;

    static final int LEGACY_OFFSET = 0;
    static final int LEGACY_SIZE = 64;
    static final int MAGIC_OFFSET = 64;
    static final int LAYOUT_VERSION_OFFSET = 68;
    static final int HEADER_SIZE_OFFSET = 70;
    static final int TOTAL_SIZE_OFFSET = 72;
    static final int HOST_SEQUENCE_OFFSET = 76;
    static final int GUEST_SEQUENCE_OFFSET = 80;
    static final int HOST_TIMESTAMP_OFFSET = 84;
    static final int GUEST_TIMESTAMP_OFFSET = 92;
    static final int FLAGS_OFFSET = 100;

    static final int FLAG_HOST_READY = 1;
    static final int FLAG_PIPE_AUTHENTICATED = 1 << 1;
    static final int FLAG_GUEST_TOUCHED_PAGE = 1 << 2;
    static final int FLAG_STOPPING = 1 << 3;
    static final int FLAG_FAULT = 1 << 4;

    private BridgeProtocol() {
    }

    static String newSessionId() {
        return UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    static byte[] hexToBytes(String value, int expectedBytes) {
        if (value == null || value.length() != expectedBytes * 2)
            throw new IllegalArgumentException("Expected " + expectedBytes + " bytes of hexadecimal data.");

        byte[] result = new byte[expectedBytes];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0)
                throw new IllegalArgumentException("Invalid hexadecimal data.");
            result[i] = (byte)((high << 4) | low);
        }
        return result;
    }

    static byte[] createHandshake(String sessionId, byte[] token) {
        return createAuthenticatedHandshake(
                sessionId, token, CHANNEL_KIND_NAMED_PIPE, PIPE_NAME);
    }

    static byte[] createAuthenticatedHandshake(
            String sessionId, byte[] token, int channelKind, String channelName) {
        if (channelKind != CHANNEL_KIND_NAMED_PIPE &&
                channelKind != CHANNEL_KIND_FORWARDED_INPUT)
            throw new IllegalArgumentException("Unsupported bridge channel kind.");
        byte[] name = channelName.getBytes(StandardCharsets.UTF_8);
        if (name.length == 0 || name.length > MAX_PIPE_NAME_BYTES)
            throw new IllegalStateException("Invalid bridge channel name.");

        ByteBuffer header = ByteBuffer.allocate(58 + name.length).order(ByteOrder.BIG_ENDIAN);
        header.put("TPB1".getBytes(StandardCharsets.US_ASCII));
        header.putShort((short)VERSION);
        header.putShort((short)channelKind);
        header.put(hexToBytes(sessionId, 16));
        header.put(token);
        header.putShort((short)name.length);
        header.put(name);
        return header.array();
    }
}

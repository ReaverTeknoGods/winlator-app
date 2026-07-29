package com.winlator.teknoparrot;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.json.JSONException;
import org.json.JSONObject;

/** Strict versioned envelope schema used because .NET Android misreads AIDL parcelables. */
final class SessionContract {
    static final int SERVICE_PROTOCOL_VERSION = 13;
    static final int MINIMUM_PROTOCOL_VERSION = 1;

    static final int FEATURE_SHARED_PAGE_DESCRIPTOR = 1;
    static final int FEATURE_TPB1 = 1 << 1;
    static final int FEATURE_GUEST_X64 = 1 << 2;
    static final int FEATURE_GUEST_X86 = 1 << 3;
    static final int FEATURE_CONTROLLED_DIAGNOSTIC = 1 << 4;
    static final int FEATURE_VERSIONED_SESSION = 1 << 5;
    static final int FEATURE_FORWARDED_INPUT = 1 << 6;
    static final int FEATURE_PREPARED_ACTIVITY_LAUNCH = 1 << 7;
    static final int FEATURE_SCOPED_WINDOWS_PATH = 1 << 8;
    static final int FEATURE_MANAGED_ENVIRONMENT = 1 << 9;
    static final int FEATURE_PRODUCTION_PIPE_BRIDGE = 1 << 11;
    static final int FEATURE_PREPARED_DISPLAY_POLICY = 1 << 12;
    static final int FEATURE_PREPARED_PROFILE_CONFIG = 1 << 13;
    static final int FEATURE_FLAGS = FEATURE_SHARED_PAGE_DESCRIPTOR | FEATURE_TPB1 |
        FEATURE_GUEST_X64 | FEATURE_GUEST_X86 | FEATURE_CONTROLLED_DIAGNOSTIC |
        FEATURE_VERSIONED_SESSION | FEATURE_FORWARDED_INPUT |
        FEATURE_PREPARED_ACTIVITY_LAUNCH | FEATURE_SCOPED_WINDOWS_PATH |
        FEATURE_MANAGED_ENVIRONMENT |
        FEATURE_PRODUCTION_PIPE_BRIDGE | FEATURE_PREPARED_DISPLAY_POLICY |
        FEATURE_PREPARED_PROFILE_CONFIG;

    static final int SESSION_FLAG_DIAGNOSTIC = 1;
    static final int SESSION_FLAG_PRODUCTION = 1 << 1;
    static final String DIAGNOSTIC_PIPE_64 = "TPWinlatorServicePipe64";
    static final String DIAGNOSTIC_PIPE_32 = "TPWinlatorServicePipe32";
    static final String PRODUCTION_PIPE_64 = "TeknoParrotPipe64";
    static final String PRODUCTION_PIPE_32 = "TeknoParrotPipe";
    static final String PRODUCTION_JVS_PIPE_64 = "TeknoParrot_JVS64";
    static final String PRODUCTION_JVS_PIPE_32 = "TeknoParrot_JVS";
    private static final int MAXIMUM_ENVELOPE_BYTES = 4096;

    private static final String PROTOCOL_VERSION = "protocolVersion";
    private static final String MINIMUM_VERSION = "minimumProtocolVersion";
    private static final String FEATURE_FLAGS_KEY = "featureFlags";
    private static final String MAXIMUM_PAGE_BYTES = "maximumSharedPageBytes";
    private static final String MAXIMUM_PIPE_NAME_BYTES = "maximumPipeNameBytes";
    private static final String IMPLEMENTATION = "implementation";
    private static final String CLIENT_NAME = "clientName";
    private static final String REQUESTED_SESSION_ID = "requestedSessionId";
    private static final String TOKEN_HEX = "tokenHex";
    private static final String CONTAINER_ID = "containerId";
    private static final String PIPE_PORT = "pipePort";
    private static final String PIPE_NAME_64 = "pipeName64";
    private static final String PIPE_NAME_32 = "pipeName32";
    private static final String SHARED_PAGE_BYTES = "sharedPageBytes";
    private static final String SESSION_FLAGS = "sessionFlags";
    private static final String SESSION_ID = "sessionId";
    private static final String STATE = "state";

    private SessionContract() {
    }

    static byte[] capabilities(int clientProtocolVersion) {
        if (clientProtocolVersion < MINIMUM_PROTOCOL_VERSION)
            throw new IllegalArgumentException("The client service protocol is unsupported.");

        try {
            JSONObject result = new JSONObject();
            result.put(PROTOCOL_VERSION, SERVICE_PROTOCOL_VERSION);
            result.put(MINIMUM_VERSION, MINIMUM_PROTOCOL_VERSION);
            result.put(FEATURE_FLAGS_KEY, FEATURE_FLAGS);
            result.put(MAXIMUM_PAGE_BYTES, BridgeProtocol.PAGE_SIZE);
            result.put(MAXIMUM_PIPE_NAME_BYTES, BridgeProtocol.MAX_PIPE_NAME_BYTES);
            result.put(IMPLEMENTATION, "Winlator 11.1 TeknoParrot companion (protocol 12)");
            return encode(result);
        }
        catch (JSONException error) {
            throw new IllegalStateException("Could not encode bridge capabilities.", error);
        }
    }

    static PreparedRequest parse(byte[] sourceBytes) {
        JSONObject source = decode(sourceBytes);

        int protocolVersion = source.optInt(PROTOCOL_VERSION, 0);
        if (protocolVersion < MINIMUM_PROTOCOL_VERSION ||
            protocolVersion > SERVICE_PROTOCOL_VERSION)
            throw new IllegalArgumentException("The requested service protocol is unsupported.");

        String clientName = requireText(source, CLIENT_NAME, 80);
        String sessionId = requireHex(source, REQUESTED_SESSION_ID, 16)
            .toLowerCase(Locale.ROOT);
        String tokenHex = requireHex(source, TOKEN_HEX, 32).toUpperCase(Locale.ROOT);
        int containerId = source.optInt(CONTAINER_ID, 0);
        int pipePort = source.optInt(PIPE_PORT, 0);
        String pipeName64 = requirePipeName(source, PIPE_NAME_64);
        String pipeName32 = requirePipeName(source, PIPE_NAME_32);
        int sharedPageBytes = source.optInt(SHARED_PAGE_BYTES, 0);
        int flags = source.optInt(SESSION_FLAGS, 0);

        if (containerId <= 0)
            throw new IllegalArgumentException("A positive Winlator container id is required.");
        if (pipePort < 1 || pipePort > 65535)
            throw new IllegalArgumentException("A valid loopback port is required.");
        if (pipeName64.equals(pipeName32))
            throw new IllegalArgumentException("The x64 and x86 diagnostic pipes must be distinct.");
        if (sharedPageBytes != BridgeProtocol.PAGE_SIZE)
            throw new IllegalArgumentException("The shared page must be exactly 4096 bytes.");
        if (flags == SESSION_FLAG_DIAGNOSTIC) {
            if (!DIAGNOSTIC_PIPE_64.equals(pipeName64) ||
                !DIAGNOSTIC_PIPE_32.equals(pipeName32))
                throw new IllegalArgumentException("The diagnostic pipe names changed.");
        }
        else if (flags == SESSION_FLAG_PRODUCTION) {
            boolean standardPair = PRODUCTION_PIPE_64.equals(pipeName64) &&
                PRODUCTION_PIPE_32.equals(pipeName32);
            boolean jvsPair = PRODUCTION_JVS_PIPE_64.equals(pipeName64) &&
                PRODUCTION_JVS_PIPE_32.equals(pipeName32);
            if (!standardPair && !jvsPair)
                throw new IllegalArgumentException("The production pipe names changed.");
        }
        else {
            throw new IllegalArgumentException("The requested session kind is not implemented.");
        }

        return new PreparedRequest(
            protocolVersion, clientName, sessionId, tokenHex, containerId,
            pipePort, pipeName64, pipeName32, sharedPageBytes, flags);
    }

    static byte[] prepared(PreparedRequest request) {
        try {
            JSONObject result = new JSONObject();
            result.put(PROTOCOL_VERSION, SERVICE_PROTOCOL_VERSION);
            result.put(SESSION_ID, request.sessionId);
            result.put(CONTAINER_ID, request.containerId);
            result.put(PIPE_PORT, request.pipePort);
            result.put(PIPE_NAME_64, request.pipeName64);
            result.put(PIPE_NAME_32, request.pipeName32);
            result.put(SHARED_PAGE_BYTES, request.sharedPageBytes);
            result.put(FEATURE_FLAGS_KEY, FEATURE_FLAGS);
            result.put(STATE, "ready");
            return encode(result);
        }
        catch (JSONException error) {
            throw new IllegalStateException("Could not encode the prepared session.", error);
        }
    }

    private static JSONObject decode(byte[] source) {
        if (source == null || source.length == 0 || source.length > MAXIMUM_ENVELOPE_BYTES)
            throw new IllegalArgumentException("A bounded versioned session specification is required.");
        try {
            return new JSONObject(new String(source, StandardCharsets.UTF_8));
        }
        catch (JSONException error) {
            throw new IllegalArgumentException("The session specification is not valid JSON.", error);
        }
    }

    private static byte[] encode(JSONObject source) {
        byte[] result = source.toString().getBytes(StandardCharsets.UTF_8);
        if (result.length == 0 || result.length > MAXIMUM_ENVELOPE_BYTES)
            throw new IllegalStateException("The bridge envelope exceeded its size limit.");
        return result;
    }

    private static String requireText(JSONObject source, String key, int maximumLength) {
        String value = source.optString(key, null);
        if (value == null || value.trim().isEmpty() || value.length() > maximumLength)
            throw new IllegalArgumentException("Invalid session field: " + key);
        return value;
    }

    private static String requireHex(JSONObject source, String key, int expectedBytes) {
        String value = requireText(source, key, expectedBytes * 2);
        BridgeProtocol.hexToBytes(value, expectedBytes);
        return value;
    }

    private static String requirePipeName(JSONObject source, String key) {
        String value = requireText(source, key, BridgeProtocol.MAX_PIPE_NAME_BYTES);
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > BridgeProtocol.MAX_PIPE_NAME_BYTES ||
            !value.matches("[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("Invalid declared pipe name: " + key);
        return value;
    }

    static final class PreparedRequest {
        final int protocolVersion;
        final String clientName;
        final String sessionId;
        final String tokenHex;
        final int containerId;
        final int pipePort;
        final String pipeName64;
        final String pipeName32;
        final int sharedPageBytes;
        final int flags;

        PreparedRequest(
            int protocolVersion,
            String clientName,
            String sessionId,
            String tokenHex,
            int containerId,
            int pipePort,
            String pipeName64,
            String pipeName32,
            int sharedPageBytes,
            int flags) {
            this.protocolVersion = protocolVersion;
            this.clientName = clientName;
            this.sessionId = sessionId;
            this.tokenHex = tokenHex;
            this.containerId = containerId;
            this.pipePort = pipePort;
            this.pipeName64 = pipeName64;
            this.pipeName32 = pipeName32;
            this.sharedPageBytes = sharedPageBytes;
            this.flags = flags;
        }
    }
}

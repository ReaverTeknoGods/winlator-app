package com.winlator.teknoparrot;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded immutable request for handing a prepared session to a Winlator Activity. */
final class ActivityLaunchContract {
    static final String FORWARDED_INPUT_DIAGNOSTIC = "forwarded-input-diagnostic";
    static final String WINDOWS_EXECUTABLE = "windows-executable";

    private static final int MAXIMUM_ENVELOPE_BYTES = 32 * 1024;
    private static final int MAXIMUM_PROFILE_CONFIG_BYTES = 16 * 1024;
    private static final int MAXIMUM_ARGUMENTS = 32;
    private static final String PROTOCOL_VERSION = "protocolVersion";
    private static final String SESSION_ID = "sessionId";
    private static final String CONTAINER_ID = "containerId";
    private static final String LAUNCH_KIND = "launchKind";
    private static final String EXECUTABLE = "executable";
    private static final String WORKING_DIRECTORY = "workingDirectory";
    private static final String ARGUMENTS = "arguments";
    private static final String LIBRARY_DIRECTORY = "libraryDirectory";
    private static final String CONTROLS_PROFILE_ID = "controlsProfileId";
    private static final String FRAME_RATE_LIMIT = "frameRateLimit";
    private static final String RESOLUTION_WIDTH = "resolutionWidth";
    private static final String RESOLUTION_HEIGHT = "resolutionHeight";
    private static final String DEBUG_LOGGING_ENABLED = "debugLoggingEnabled";
    private static final String COMPATIBILITY_PRESET = "compatibilityPreset";
    private static final String DISPLAY_MODE = "displayMode";
    private static final String PROFILE_CONFIG_INI = "profileConfigIni";
    private static final String SCOPED_GAME_DIRECTORY = "scopedGameDirectory";
    static final String DISPLAY_MODE_CENTERED = "centered";
    static final String DISPLAY_MODE_ASPECT_FIT = "aspect-fit";
    static final String DISPLAY_MODE_FULLSCREEN = "fullscreen";
    static final String COMPATIBILITY_PRESET_MEDIA_WMV = "media-wmv";
    static final String COMPATIBILITY_PRESET_WINE_GSTREAMER = "wine-gstreamer";
    static final String COMPATIBILITY_PRESET_KOF_XII_WINE_GSTREAMER =
        "kof-xii-wine-gstreamer";
    static final String COMPATIBILITY_PRESET_KOF_MIRA_BUILTIN_WINED3D =
        "kof-mira-builtin-wined3d";
    static final String COMPATIBILITY_PRESET_TAITO_LEGACY_SCARD = "taito-legacy-scard";
    static final String COMPATIBILITY_PRESET_DIRTY_DRIVING_FULLSCREEN = "dirty-driving-fullscreen";
    static final String COMPATIBILITY_PRESET_EN_EINS_NATIVE_FULLSCREEN =
        "en-eins-native-fullscreen";
    static final String COMPATIBILITY_PRESET_MUSIC_GUNGUN_NATIVE_FULLSCREEN =
        "music-gungun-native-fullscreen";
    static final String COMPATIBILITY_PRESET_BATTLE_GEAR_4_ORIGINAL =
        "battle-gear-4-original";
    static final String COMPATIBILITY_PRESET_JUSTICE_LEAGUE_WOW64_TRANSITION =
        "justice-league-wow64-transition";
    static final String COMPATIBILITY_PRESET_FNF_DRIFT_WOW64_TRANSITION =
        "fnf-drift-wow64-transition";
    static final String COMPATIBILITY_PRESET_WMMT_TERMINAL = "wmmt-terminal";
    static final String COMPATIBILITY_PRESET_WMMT_NO_TERMINAL = "wmmt-no-terminal";
    static final String COMPATIBILITY_PRESET_WMMT3_YACARD = "wmmt3-yacard";
    static final String COMPATIBILITY_PRESET_CXBXR_WMMT_YACARD = "cxbxr-wmmt-yacard";
    static final String COMPATIBILITY_PRESET_CXBXR_PERFORMANCE = "cxbxr-performance";
    static final String COMPATIBILITY_PRESET_CXBXR_CHIHIRO_TYPE3 =
        "cxbxr-chihiro-type3";
    static final String COMPATIBILITY_PRESET_WACKY_RACES_NETWORK = "wacky-races-network";
    static final String COMPATIBILITY_PRESET_INITIAL_D8 = "initial-d8";
    static final String COMPATIBILITY_PRESET_INITIAL_D_THE_ARCADE =
        "initial-d-the-arcade";
    static final String COMPATIBILITY_PRESET_CHASE_HQ2 = "chase-hq2";
    static final String COMPATIBILITY_PRESET_STAR_WARS = "star-wars";
    static final String COMPATIBILITY_PRESET_TAIKO_CUSTOM_RESOLUTION = "taiko-custom-resolution";
    static final String COMPATIBILITY_PRESET_LARGE_ADDRESS_AWARE = "large-address-aware";
    static final String COMPATIBILITY_PRESET_LARGE_ADDRESS_AWARE_DDRAW = "large-address-aware-ddraw";
    static final String COMPATIBILITY_PRESET_GAME_WORKING_DIRECTORY = "game-working-directory";
    static final String COMPATIBILITY_PRESET_BUILTIN_DDRAW = "builtin-ddraw";
    static final String COMPATIBILITY_PRESET_XACT_LOCAL_REGISTER = "xact-local-register";
    static final String COMPATIBILITY_PRESET_EADP_DUAL_IO = "eadp-dual-io";
    static final String COMPATIBILITY_PRESET_SHARED_JVS_DUAL_IO = "shared-jvs-dual-io";
    static final String COMPATIBILITY_PRESET_GAIA_ATTACK4_MEDIA = "gaia-attack4-media";
    static final String COMPATIBILITY_PRESET_DIRECT_TOUCH_JVS = "direct-touch-jvs";
    static final String COMPATIBILITY_PRESET_BOX64_INTERPRETER = "box64-interpreter";
    static final String COMPATIBILITY_PRESET_PORTRAIT_WINDOW_COUNTER_CLOCKWISE =
        "portrait-window-counter-clockwise";
    static final String COMPATIBILITY_PRESET_PARKED_ENTRYPOINT = "parked-entrypoint";
    static final String COMPATIBILITY_PRESET_POST_START_REMOTE_THREAD =
        "post-start-remote-thread";
    static final String COMPATIBILITY_PRESET_GGS_APM3_LOADER_SAFE =
        "ggs-apm3-loader-safe";
    static final String COMPATIBILITY_PRESET_BBTAG_APM3_LOADER_SAFE =
        "bbtag-apm3-loader-safe";
    static final String COMPATIBILITY_PRESET_OTOSHU_APM3_LOADER_SAFE =
        "otoshu-apm3-loader-safe";
    static final String COMPATIBILITY_PRESET_WINED3D_REMOTE_THREAD =
        "wined3d-remote-thread";
    static final String COMPATIBILITY_PRESET_WINED3D_PARKED_ENTRYPOINT =
        "wined3d-parked-entrypoint";

    private ActivityLaunchContract() {
    }

    static Request parse(byte[] sourceBytes) {
        JSONObject source = decode(sourceBytes);
        if (!source.has(PROTOCOL_VERSION) ||
            !source.has(SESSION_ID) ||
            !source.has(CONTAINER_ID) ||
            !source.has(LAUNCH_KIND))
            throw new IllegalArgumentException("The Activity launch envelope has an unknown schema.");

        int protocolVersion = requireInt(source, PROTOCOL_VERSION);
        if (protocolVersion != SessionContract.SERVICE_PROTOCOL_VERSION)
            throw new IllegalArgumentException("The Activity launch protocol is unsupported.");

        String sessionId = requireString(source, SESSION_ID, 32);
        if (!sessionId.matches("[0-9a-fA-F]{32}"))
            throw new IllegalArgumentException("The Activity launch session id is invalid.");

        int containerId = requireInt(source, CONTAINER_ID);
        if (containerId <= 0)
            throw new IllegalArgumentException("A positive Activity launch container id is required.");

        String launchKind = requireString(source, LAUNCH_KIND, 64);
        String executable = null;
        String workingDirectory = null;
        String libraryDirectory = null;
        String[] arguments = new String[0];
        int controlsProfileId = 0;
        int frameRateLimit = 0;
        int resolutionWidth = 0;
        int resolutionHeight = 0;
        boolean debugLoggingEnabled = true;
        String compatibilityPreset = "";
        String displayMode = DISPLAY_MODE_CENTERED;
        String profileConfigIni = "";
        String scopedGameDirectory = "";
        if (FORWARDED_INPUT_DIAGNOSTIC.equals(launchKind)) {
            if (source.length() != 4)
                throw new IllegalArgumentException("The diagnostic Activity launch schema changed.");
        }
        else if (WINDOWS_EXECUTABLE.equals(launchKind)) {
            boolean hasScopedGameDirectory = source.has(SCOPED_GAME_DIRECTORY);
            if (source.length() != (hasScopedGameDirectory ? 17 : 16) ||
                !source.has(EXECUTABLE) ||
                !source.has(WORKING_DIRECTORY) ||
                !source.has(ARGUMENTS) ||
                !source.has(LIBRARY_DIRECTORY) ||
                !source.has(CONTROLS_PROFILE_ID) ||
                !source.has(FRAME_RATE_LIMIT) ||
                !source.has(RESOLUTION_WIDTH) ||
                !source.has(RESOLUTION_HEIGHT) ||
                !source.has(DEBUG_LOGGING_ENABLED) ||
                !source.has(COMPATIBILITY_PRESET) ||
                !source.has(DISPLAY_MODE) ||
                !source.has(PROFILE_CONFIG_INI))
                throw new IllegalArgumentException("The Windows Activity launch schema changed.");
            executable = requireDosPath(source, EXECUTABLE, false);
            workingDirectory = requireDosPath(source, WORKING_DIRECTORY, true);
            arguments = requireArguments(source);
            if (!source.isNull(LIBRARY_DIRECTORY))
                libraryDirectory = requireDosPath(source, LIBRARY_DIRECTORY, true);
            controlsProfileId = requireInt(source, CONTROLS_PROFILE_ID);
            if (controlsProfileId < 0 || controlsProfileId > 1_000_000)
                throw new IllegalArgumentException("The controls profile id is invalid.");
            frameRateLimit = requireInt(source, FRAME_RATE_LIMIT);
            if (frameRateLimit < 0 || frameRateLimit > 1_000)
                throw new IllegalArgumentException("The frame-rate limit is invalid.");
            resolutionWidth = requireInt(source, RESOLUTION_WIDTH);
            resolutionHeight = requireInt(source, RESOLUTION_HEIGHT);
            validateResolution(resolutionWidth, resolutionHeight);
            debugLoggingEnabled = requireBoolean(source, DEBUG_LOGGING_ENABLED);
            compatibilityPreset = requireCompatibilityPreset(source);
            displayMode = requireDisplayMode(source);
            profileConfigIni = requireProfileConfigIni(source);
            if (hasScopedGameDirectory)
                scopedGameDirectory = requireScopedGameDirectory(source);
            boolean usesScopedGameDrive = usesDosDrive(executable, 'I') ||
                usesDosDrive(workingDirectory, 'I') || usesDosDrive(libraryDirectory, 'I') ||
                usesDosDrive(arguments, 'I');
            if (usesScopedGameDrive != !scopedGameDirectory.isEmpty())
                throw new IllegalArgumentException(
                    "An I: launch path and a scoped Android game folder are required together.");
        }
        else {
            throw new IllegalArgumentException("The Activity launch kind is not implemented.");
        }

        return new Request(
            protocolVersion,
            sessionId.toLowerCase(Locale.ROOT),
            containerId,
            launchKind,
            executable,
            workingDirectory,
            arguments,
            libraryDirectory,
            controlsProfileId,
            frameRateLimit,
            resolutionWidth,
            resolutionHeight,
            debugLoggingEnabled,
            compatibilityPreset,
            displayMode,
            profileConfigIni,
            scopedGameDirectory);
    }

    static Request forwardedInputDiagnostic(SessionContract.PreparedRequest prepared) {
        if (prepared == null)
            throw new IllegalArgumentException("A prepared session is required.");
        return new Request(
            SessionContract.SERVICE_PROTOCOL_VERSION,
            prepared.sessionId,
            prepared.containerId,
            FORWARDED_INPUT_DIAGNOSTIC,
            null,
            null,
            new String[0],
            null,
            0,
            0,
            0,
            0,
            true,
            "",
            DISPLAY_MODE_CENTERED,
            "",
            "");
    }

    private static JSONObject decode(byte[] sourceBytes) {
        if (sourceBytes == null || sourceBytes.length == 0 ||
            sourceBytes.length > MAXIMUM_ENVELOPE_BYTES)
            throw new IllegalArgumentException("A bounded Activity launch envelope is required.");
        try {
            return new JSONObject(new String(sourceBytes, StandardCharsets.UTF_8));
        }
        catch (JSONException error) {
            throw new IllegalArgumentException("The Activity launch envelope is not valid JSON.", error);
        }
    }

    private static int requireInt(JSONObject source, String key) {
        Object value = source.opt(key);
        if (!(value instanceof Number))
            throw new IllegalArgumentException("Invalid Activity launch field: " + key);
        Number number = (Number)value;
        long longValue = number.longValue();
        if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE ||
            number.doubleValue() != (double)longValue)
            throw new IllegalArgumentException("Invalid Activity launch integer: " + key);
        return (int)longValue;
    }

    private static void validateResolution(int width, int height) {
        if ((width == 0) != (height == 0) || width < 0 || height < 0 ||
            width > 8_192 || height > 8_192 ||
            (width != 0 && (width < 320 || height < 240)))
            throw new IllegalArgumentException(
                "The resolution must be omitted or between 320x240 and 8192x8192.");
    }

    private static boolean requireBoolean(JSONObject source, String key) {
        Object value = source.opt(key);
        if (!(value instanceof Boolean))
            throw new IllegalArgumentException("Invalid Activity launch field: " + key);
        return (Boolean)value;
    }

    private static String requireCompatibilityPreset(JSONObject source) {
        Object raw = source.opt(COMPATIBILITY_PRESET);
        if (!(raw instanceof String))
            throw new IllegalArgumentException("Invalid Activity launch field: " + COMPATIBILITY_PRESET);
        String value = (String)raw;
        if (!value.isEmpty() &&
            !COMPATIBILITY_PRESET_MEDIA_WMV.equals(value) &&
            !COMPATIBILITY_PRESET_WINE_GSTREAMER.equals(value) &&
            !COMPATIBILITY_PRESET_KOF_XII_WINE_GSTREAMER.equals(value) &&
            !COMPATIBILITY_PRESET_KOF_MIRA_BUILTIN_WINED3D.equals(value) &&
            !COMPATIBILITY_PRESET_TAITO_LEGACY_SCARD.equals(value) &&
            !COMPATIBILITY_PRESET_DIRTY_DRIVING_FULLSCREEN.equals(value) &&
            !COMPATIBILITY_PRESET_EN_EINS_NATIVE_FULLSCREEN.equals(value) &&
            !COMPATIBILITY_PRESET_MUSIC_GUNGUN_NATIVE_FULLSCREEN.equals(value) &&
            !COMPATIBILITY_PRESET_BATTLE_GEAR_4_ORIGINAL.equals(value) &&
            !COMPATIBILITY_PRESET_JUSTICE_LEAGUE_WOW64_TRANSITION.equals(value) &&
            !COMPATIBILITY_PRESET_FNF_DRIFT_WOW64_TRANSITION.equals(value) &&
            !COMPATIBILITY_PRESET_WMMT_TERMINAL.equals(value) &&
            !COMPATIBILITY_PRESET_WMMT_NO_TERMINAL.equals(value) &&
            !COMPATIBILITY_PRESET_WMMT3_YACARD.equals(value) &&
            !COMPATIBILITY_PRESET_CXBXR_WMMT_YACARD.equals(value) &&
            !COMPATIBILITY_PRESET_CXBXR_PERFORMANCE.equals(value) &&
            !COMPATIBILITY_PRESET_CXBXR_CHIHIRO_TYPE3.equals(value) &&
            !COMPATIBILITY_PRESET_WACKY_RACES_NETWORK.equals(value) &&
            !COMPATIBILITY_PRESET_INITIAL_D8.equals(value) &&
            !COMPATIBILITY_PRESET_INITIAL_D_THE_ARCADE.equals(value) &&
            !COMPATIBILITY_PRESET_CHASE_HQ2.equals(value) &&
            !COMPATIBILITY_PRESET_STAR_WARS.equals(value) &&
            !COMPATIBILITY_PRESET_TAIKO_CUSTOM_RESOLUTION.equals(value) &&
            !COMPATIBILITY_PRESET_LARGE_ADDRESS_AWARE.equals(value) &&
            !COMPATIBILITY_PRESET_LARGE_ADDRESS_AWARE_DDRAW.equals(value) &&
            !COMPATIBILITY_PRESET_GAME_WORKING_DIRECTORY.equals(value) &&
            !COMPATIBILITY_PRESET_BUILTIN_DDRAW.equals(value) &&
            !COMPATIBILITY_PRESET_XACT_LOCAL_REGISTER.equals(value) &&
            !COMPATIBILITY_PRESET_EADP_DUAL_IO.equals(value) &&
            !COMPATIBILITY_PRESET_SHARED_JVS_DUAL_IO.equals(value) &&
            !COMPATIBILITY_PRESET_GAIA_ATTACK4_MEDIA.equals(value) &&
            !COMPATIBILITY_PRESET_DIRECT_TOUCH_JVS.equals(value) &&
            !COMPATIBILITY_PRESET_BOX64_INTERPRETER.equals(value) &&
            !COMPATIBILITY_PRESET_PORTRAIT_WINDOW_COUNTER_CLOCKWISE.equals(value) &&
            !COMPATIBILITY_PRESET_POST_START_REMOTE_THREAD.equals(value) &&
            !COMPATIBILITY_PRESET_GGS_APM3_LOADER_SAFE.equals(value) &&
            !COMPATIBILITY_PRESET_BBTAG_APM3_LOADER_SAFE.equals(value) &&
            !COMPATIBILITY_PRESET_OTOSHU_APM3_LOADER_SAFE.equals(value) &&
            !COMPATIBILITY_PRESET_PARKED_ENTRYPOINT.equals(value) &&
            !COMPATIBILITY_PRESET_WINED3D_REMOTE_THREAD.equals(value) &&
            !COMPATIBILITY_PRESET_WINED3D_PARKED_ENTRYPOINT.equals(value))
            throw new IllegalArgumentException("The Activity compatibility preset is unsupported.");
        return value;
    }

    private static String requireDisplayMode(JSONObject source) {
        Object raw = source.opt(DISPLAY_MODE);
        if (!(raw instanceof String))
            throw new IllegalArgumentException("Invalid Activity launch field: " + DISPLAY_MODE);
        String value = (String)raw;
        if (!DISPLAY_MODE_CENTERED.equals(value) &&
            !DISPLAY_MODE_ASPECT_FIT.equals(value) &&
            !DISPLAY_MODE_FULLSCREEN.equals(value))
            throw new IllegalArgumentException("The Activity display mode is unsupported.");
        return value;
    }

    private static String requireProfileConfigIni(JSONObject source) {
        Object raw = source.opt(PROFILE_CONFIG_INI);
        if (!(raw instanceof String))
            throw new IllegalArgumentException(
                "Invalid Activity launch field: " + PROFILE_CONFIG_INI);
        String value = (String)raw;
        if (value.trim().isEmpty() ||
            value.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_PROFILE_CONFIG_BYTES)
            throw new IllegalArgumentException(
                "The Activity profile configuration is empty or oversized.");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == 0 ||
                (character < 0x20 && character != '\r' && character != '\n' && character != '\t'))
                throw new IllegalArgumentException(
                    "The Activity profile configuration contains a control character.");
        }
        return value;
    }

    private static String requireString(JSONObject source, String key, int exactOrMaximumLength) {
        Object raw = source.opt(key);
        if (!(raw instanceof String))
            throw new IllegalArgumentException("Invalid Activity launch field: " + key);
        String value = (String)raw;
        if (value.isEmpty() || value.length() > exactOrMaximumLength)
            throw new IllegalArgumentException("Invalid Activity launch field: " + key);
        return value;
    }

    private static String requireDosPath(JSONObject source, String key, boolean directory) {
        String value = requireString(source, key, 512);
        if (!value.matches("(?i)^[CDEGHI]:\\\\[^/\"]+$") ||
            value.endsWith("\\") ||
            value.contains("\\.\\") ||
            value.contains("\\..\\") ||
            value.endsWith("\\.") ||
            value.endsWith("\\..") ||
            (!directory && !value.toLowerCase(Locale.ROOT).endsWith(".exe")))
            throw new IllegalArgumentException("Invalid Activity launch DOS path: " + key);
        for (int character = 0; character < value.length(); character++) {
            if (value.charAt(character) < 0x20)
                throw new IllegalArgumentException("An Activity launch DOS path contains a control character.");
        }
        return value;
    }

    private static String[] requireArguments(JSONObject source) {
        Object raw = source.opt(ARGUMENTS);
        if (!(raw instanceof JSONArray))
            throw new IllegalArgumentException("Invalid Activity launch field: " + ARGUMENTS);
        JSONArray values = (JSONArray)raw;
        if (values.length() > MAXIMUM_ARGUMENTS)
            throw new IllegalArgumentException("Too many Activity launch arguments.");
        String[] result = new String[values.length()];
        for (int index = 0; index < values.length(); index++) {
            Object argument = values.opt(index);
            if (!(argument instanceof String))
                throw new IllegalArgumentException("An Activity launch argument is not a string.");
            String value = (String)argument;
            if (value.length() > 512 || value.indexOf('\"') >= 0)
                throw new IllegalArgumentException("An Activity launch argument is invalid.");
            for (int character = 0; character < value.length(); character++) {
                if (value.charAt(character) < 0x20)
                    throw new IllegalArgumentException("An Activity launch argument contains a control character.");
            }
            result[index] = value;
        }
        return result;
    }

    private static boolean usesDosDrive(String value, char letter) {
        return value != null && value.length() >= 3 &&
            Character.toUpperCase(value.charAt(0)) == letter &&
            value.charAt(1) == ':' && value.charAt(2) == '\\';
    }

    private static boolean usesDosDrive(String[] values, char letter) {
        if (values == null) return false;
        for (String value : values) {
            if (usesDosDrive(value, letter)) return true;
        }
        return false;
    }

    private static String requireScopedGameDirectory(JSONObject source) {
        Object raw = source.opt(SCOPED_GAME_DIRECTORY);
        if (!(raw instanceof String))
            throw new IllegalArgumentException(
                "Invalid Activity launch field: " + SCOPED_GAME_DIRECTORY);
        String value = (String)raw;
        if (value.trim().isEmpty() || value.length() > 1024 ||
            value.indexOf('\\') >= 0 || value.indexOf('\"') >= 0 || value.endsWith("/"))
            throw new IllegalArgumentException("A canonical Android game folder is required.");

        String lower = value.toLowerCase(Locale.ROOT);
        boolean primary = value.startsWith("/storage/emulated/0/");
        boolean removable = value.matches("^/storage/[A-Za-z0-9_-]+/.+") &&
            !lower.startsWith("/storage/emulated/") &&
            !lower.startsWith("/storage/self/");
        if (!primary && !removable)
            throw new IllegalArgumentException("The game folder is outside shared storage.");
        if (lower.equals("/storage/emulated/0/android") ||
            lower.startsWith("/storage/emulated/0/android/data") ||
            lower.startsWith("/storage/emulated/0/android/obb") ||
            lower.matches("^/storage/[^/]+/android$") ||
            lower.matches("^/storage/[^/]+/android/(data|obb)(/.*)?$"))
            throw new IllegalArgumentException("Protected Android storage cannot be exposed.");
        for (String segment : value.split("/")) {
            if (segment.equals(".") || segment.equals(".."))
                throw new IllegalArgumentException("The game folder contains traversal.");
            for (int index = 0; index < segment.length(); index++) {
                if (segment.charAt(index) < 0x20)
                    throw new IllegalArgumentException("The game folder contains a control character.");
            }
        }
        return value;
    }

    static final class Request {
        final int protocolVersion;
        final String sessionId;
        final int containerId;
        final String launchKind;
        final String executable;
        final String workingDirectory;
        final String[] arguments;
        final String libraryDirectory;
        final int controlsProfileId;
        final int frameRateLimit;
        final int resolutionWidth;
        final int resolutionHeight;
        final boolean debugLoggingEnabled;
        final String compatibilityPreset;
        final String displayMode;
        final String profileConfigIni;
        final String scopedGameDirectory;

        Request(
            int protocolVersion,
            String sessionId,
            int containerId,
            String launchKind,
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
            String scopedGameDirectory) {
            this.protocolVersion = protocolVersion;
            this.sessionId = sessionId;
            this.containerId = containerId;
            this.launchKind = launchKind;
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
        }

        void validatePrepared(SessionContract.PreparedRequest prepared) {
            if (prepared == null ||
                protocolVersion != SessionContract.SERVICE_PROTOCOL_VERSION ||
                !sessionId.equals(prepared.sessionId) ||
                containerId != prepared.containerId ||
                ((FORWARDED_INPUT_DIAGNOSTIC.equals(launchKind) &&
                  prepared.flags != SessionContract.SESSION_FLAG_DIAGNOSTIC) ||
                 (WINDOWS_EXECUTABLE.equals(launchKind) &&
                  prepared.flags != SessionContract.SESSION_FLAG_PRODUCTION) ||
                 (!FORWARDED_INPUT_DIAGNOSTIC.equals(launchKind) &&
                  !WINDOWS_EXECUTABLE.equals(launchKind))))
                throw new IllegalArgumentException(
                    "The Activity launch request changed immutable prepared-session settings.");
        }

        String status() {
            return "state=launching;session=" + sessionId +
                ";container=" + containerId + ";kind=" + launchKind;
        }
    }
}

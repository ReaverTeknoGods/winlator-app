package com.winlator.teknoparrot;

/** Pure mapping/math used by Android event taps and the desktop JVM audit. */
public final class ForwardedInputMapping {
    public static final int UNMAPPED = -1;

    // Stable integer values from android.view.KeyEvent. Keeping this mapper
    // Android-free allows the same mapping table to run in the JVM audit.
    private static final int KEYCODE_1 = 8;
    private static final int KEYCODE_5 = 12;
    private static final int KEYCODE_DPAD_UP = 19;
    private static final int KEYCODE_DPAD_DOWN = 20;
    private static final int KEYCODE_DPAD_LEFT = 21;
    private static final int KEYCODE_DPAD_RIGHT = 22;
    private static final int KEYCODE_DPAD_CENTER = 23;
    private static final int KEYCODE_ENTER = 66;
    private static final int KEYCODE_BUTTON_A = 96;
    private static final int KEYCODE_BUTTON_B = 97;
    private static final int KEYCODE_BUTTON_X = 99;
    private static final int KEYCODE_BUTTON_Y = 100;
    private static final int KEYCODE_BUTTON_L1 = 102;
    private static final int KEYCODE_BUTTON_R1 = 103;
    private static final int KEYCODE_BUTTON_L2 = 104;
    private static final int KEYCODE_BUTTON_R2 = 105;
    private static final int KEYCODE_BUTTON_START = 108;
    private static final int KEYCODE_BUTTON_SELECT = 109;
    private static final int KEYCODE_F1 = 131;
    private static final int KEYCODE_F2 = 132;

    private ForwardedInputMapping() {}

    public static int mapKeyCode(int keyCode) {
        return mapKeyCode(keyCode, false);
    }

    public static int mapKeyCode(int keyCode, boolean mapTriggersToExtensionButtons) {
        switch (keyCode) {
            case KEYCODE_DPAD_UP:
                return ForwardedInputProtocol.BUTTON_UP;
            case KEYCODE_DPAD_DOWN:
                return ForwardedInputProtocol.BUTTON_DOWN;
            case KEYCODE_DPAD_LEFT:
                return ForwardedInputProtocol.BUTTON_LEFT;
            case KEYCODE_DPAD_RIGHT:
                return ForwardedInputProtocol.BUTTON_RIGHT;
            case KEYCODE_DPAD_CENTER:
            case KEYCODE_ENTER:
            case KEYCODE_BUTTON_START:
            case KEYCODE_1:
                return ForwardedInputProtocol.BUTTON_START;
            case KEYCODE_F2:
                return ForwardedInputProtocol.BUTTON_SERVICE;
            case KEYCODE_F1:
                return ForwardedInputProtocol.BUTTON_TEST;
            case KEYCODE_BUTTON_SELECT:
            case KEYCODE_5:
                return ForwardedInputProtocol.BUTTON_COIN;
            case KEYCODE_BUTTON_A:
                return ForwardedInputProtocol.BUTTON_1;
            case KEYCODE_BUTTON_B:
                return ForwardedInputProtocol.BUTTON_2;
            case KEYCODE_BUTTON_X:
                return ForwardedInputProtocol.BUTTON_3;
            case KEYCODE_BUTTON_Y:
                return ForwardedInputProtocol.BUTTON_4;
            case KEYCODE_BUTTON_L1:
                return ForwardedInputProtocol.BUTTON_5;
            case KEYCODE_BUTTON_R1:
                return ForwardedInputProtocol.BUTTON_6;
            case KEYCODE_BUTTON_L2:
                return mapTriggersToExtensionButtons
                        ? ForwardedInputProtocol.BUTTON_7 : UNMAPPED;
            case KEYCODE_BUTTON_R2:
                return mapTriggersToExtensionButtons
                        ? ForwardedInputProtocol.BUTTON_8 : UNMAPPED;
            default:
                return UNMAPPED;
        }
    }

    public static short toQ15(float value) {
        if (Float.isNaN(value))
            return 0;
        float clamped = Math.max(-1.0f, Math.min(1.0f, value));
        return (short)Math.round(clamped * Short.MAX_VALUE);
    }

    public static float directionalAxisValue(
            float offset,
            boolean isActionDown,
            boolean negativeDirection) {
        if (!isActionDown)
            return 0.0f;

        // Winlator's touchscreen stick dispatches the same signed offset to
        // both directional bindings. Preserve that sign so the second binding
        // cannot overwrite every movement as LEFT/UP. A zero offset still
        // represents a keyboard/button binding and therefore needs the bound
        // direction's full-scale fallback.
        if (offset != 0.0f)
            return Math.max(-1.0f, Math.min(1.0f, offset));
        return negativeDirection ? -1.0f : 1.0f;
    }

    public static int toUnsignedQ15(float value) {
        if (Float.isNaN(value))
            return 0;
        float clamped = Math.max(0.0f, Math.min(1.0f, value));
        return Math.round(clamped * Short.MAX_VALUE);
    }

    public static int toUnsignedQ16(float value, int extent) {
        if (Float.isNaN(value) || extent <= 1)
            return 0;
        float clamped = Math.max(0.0f, Math.min(extent - 1.0f, value));
        return Math.round(clamped * 65535.0f / (extent - 1.0f));
    }
}

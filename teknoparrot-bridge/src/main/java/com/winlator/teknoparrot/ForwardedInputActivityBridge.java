package com.winlator.teknoparrot;

import android.app.Activity;
import android.content.Context;
import android.hardware.input.InputManager;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/** Non-consuming observer attached to Winlator's real Activity dispatch path. */
public final class ForwardedInputActivityBridge
        implements InputManager.InputDeviceListener, AutoCloseable {
    private static final long VIRTUAL_CONTROLS_DEVICE_ID = 0x54505649L; // "TPVI"
    private static final int[][] ANDROID_AXIS_CANDIDATES = {
            {MotionEvent.AXIS_X},
            {MotionEvent.AXIS_Y},
            {MotionEvent.AXIS_Z, MotionEvent.AXIS_RX},
            {MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY},
            {MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE},
            {MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS},
            {MotionEvent.AXIS_HAT_X},
            {MotionEvent.AXIS_HAT_Y}
    };

    private final Activity activity;
    private final String sessionId;
    private final ForwardedInputClient client;
    private final InputManager inputManager;
    private final SparseIntArray stableIdsByAndroidId = new SparseIntArray();
    private final SparseIntArray playersByAndroidId = new SparseIntArray();
    private final SparseArray<short[]> axesByAndroidId = new SparseArray<>();
    private boolean mapTriggersToExtensionButtons;
    private boolean closed;

    ForwardedInputActivityBridge(
            Activity activity,
            String sessionId,
            ForwardedInputClient client) {
        this.activity = activity;
        this.sessionId = sessionId;
        this.client = client;
        inputManager = (InputManager)activity.getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null)
            inputManager.registerInputDeviceListener(this, null);
        client.start();
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        if (!closed)
            client.sendFocus(hasFocus, System.nanoTime());
    }

    public void onResume() {
        if (!closed)
            client.sendFocus(true, System.nanoTime());
    }

    public void onPause() {
        if (!closed)
            client.sendFocus(false, System.nanoTime());
    }

    public boolean isConnected() {
        return !closed && client.isConnected();
    }

    public void setMapTriggersToExtensionButtons(boolean enabled) {
        mapTriggersToExtensionButtons = enabled;
    }

    public void onKeyEvent(KeyEvent event) {
        if (closed || event == null || event.getRepeatCount() != 0)
            return;
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP)
            return;
        int button = ForwardedInputMapping.mapKeyCode(
                event.getKeyCode(), mapTriggersToExtensionButtons);
        if (button == ForwardedInputMapping.UNMAPPED)
            return;
        client.sendButton(
                stableDeviceId(event.getDeviceId(), event.getDevice()),
                playerForDevice(event.getDeviceId(), event.getDevice()),
                button,
                action == KeyEvent.ACTION_DOWN,
                event.getEventTime() * 1_000_000L);
    }

    public void onGenericMotionEvent(MotionEvent event) {
        if (closed || event == null ||
                (event.getSource() & InputDevice.SOURCE_JOYSTICK) !=
                        InputDevice.SOURCE_JOYSTICK)
            return;
        InputDevice device = event.getDevice();
        if (device == null)
            return;

        int androidDeviceId = event.getDeviceId();
        long stableDeviceId = stableDeviceId(androidDeviceId, device);
        short[] previous = axesByAndroidId.get(androidDeviceId);
        if (previous == null) {
            previous = new short[ANDROID_AXIS_CANDIDATES.length];
            axesByAndroidId.put(androidDeviceId, previous);
        }

        long eventTime = event.getEventTime() * 1_000_000L;
        for (int index = 0; index < ANDROID_AXIS_CANDIDATES.length; index++) {
            InputDevice.MotionRange range = null;
            float rawValue = 0.0f;
            float flatValue = 0.0f;
            boolean triggerAxis = index == 4 || index == 5;
            // Android controllers do not agree on right-stick and trigger axis
            // names. Preserve one canonical TPI1 slot and select the live alias
            // with the largest magnitude/activation (for example LTRIGGER vs BRAKE).
            for (int androidAxis : ANDROID_AXIS_CANDIDATES[index]) {
                InputDevice.MotionRange candidate =
                        device.getMotionRange(androidAxis, event.getSource());
                if (candidate == null)
                    continue;
                float candidateValue = event.getAxisValue(androidAxis);
                float candidateFlat = candidate.getFlat();
                if (triggerAxis) {
                    float span = candidate.getMax() - candidate.getMin();
                    if (span > 0.0f) {
                        // Some pads report triggers as -1..1 and others as 0..1.
                        // Canonical pedal/APM3 state is always an unsigned 0..1.
                        candidateValue = Math.max(0.0f, Math.min(1.0f,
                                (candidateValue - candidate.getMin()) / span));
                        candidateFlat /= span;
                    }
                }
                boolean candidateIsStronger = triggerAxis
                        ? candidateValue > rawValue
                        : Math.abs(candidateValue) > Math.abs(rawValue);
                if (range == null || candidateIsStronger) {
                    range = candidate;
                    rawValue = candidateValue;
                    flatValue = candidateFlat;
                }
            }
            if (range == null)
                continue;
            short value = ForwardedInputMapping.toQ15(rawValue);
            if (value == previous[index])
                continue;
            previous[index] = value;
            client.sendAxis(
                    stableDeviceId,
                    playerForDevice(androidDeviceId, device),
                    index,
                    value,
                    ForwardedInputMapping.toUnsignedQ15(flatValue),
                    eventTime);
        }
    }

    /** Forwards Winlator's editable touchscreen controls through TPI1. */
    public void onVirtualButton(int button, boolean pressed) {
        if (closed)
            return;
        client.sendButton(
                VIRTUAL_CONTROLS_DEVICE_ID,
                0,
                button,
                pressed,
                System.nanoTime());
    }

    /** Forwards a normalized Winlator touchscreen axis through TPI1. */
    public void onVirtualAxis(int axis, float value) {
        if (closed)
            return;
        client.sendAxis(
                VIRTUAL_CONTROLS_DEVICE_ID,
                0,
                axis,
                ForwardedInputMapping.toQ15(value),
                0,
                System.nanoTime());
    }

    public void onTouchEvent(MotionEvent event) {
        onTouchEvent(event, null);
    }

    /**
     * Forwards direct game-surface touches while excluding pointer ids claimed
     * by Winlator's virtual controls overlay.
     */
    public void onTouchEvent(
            MotionEvent event,
            SparseBooleanArray excludedPointerIds) {
        if (closed || event == null)
            return;
        int source = event.getSource();
        boolean pointerSource = source == 0 ||
                (source & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN ||
                (source & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS ||
                (source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;
        if (!pointerSource)
            return;

        View decor = activity.getWindow().getDecorView();
        int width = decor.getWidth();
        int height = decor.getHeight();
        if (width <= 1 || height <= 1)
            return;

        int action = event.getActionMasked();
        boolean releaseAll = action == MotionEvent.ACTION_CANCEL;
        if (action == MotionEvent.ACTION_MOVE || releaseAll) {
            for (int index = 0; index < event.getPointerCount(); index++) {
                if (!isExcluded(event, index, excludedPointerIds))
                    sendPointer(event, index, width, height, releaseAll);
            }
            return;
        }
        if (action == MotionEvent.ACTION_DOWN ||
                action == MotionEvent.ACTION_POINTER_DOWN ||
                action == MotionEvent.ACTION_UP ||
                action == MotionEvent.ACTION_POINTER_UP) {
            boolean released = action == MotionEvent.ACTION_UP ||
                    action == MotionEvent.ACTION_POINTER_UP;
            int actionIndex = event.getActionIndex();
            if (!isExcluded(event, actionIndex, excludedPointerIds))
                sendPointer(event, actionIndex, width, height, released);
        }
    }

    private static boolean isExcluded(
            MotionEvent event,
            int pointerIndex,
            SparseBooleanArray excludedPointerIds) {
        return excludedPointerIds != null &&
                excludedPointerIds.get(event.getPointerId(pointerIndex), false);
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        stableDeviceId(deviceId, InputDevice.getDevice(deviceId));
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        releaseDevice(deviceId);
        stableDeviceId(deviceId, InputDevice.getDevice(deviceId));
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        releaseDevice(deviceId);
    }

    private void releaseDevice(int deviceId) {
        int stableId = stableIdsByAndroidId.get(deviceId, 0);
        if (stableId != 0)
            client.sendDeviceRemoved(Integer.toUnsignedLong(stableId), System.nanoTime());
        stableIdsByAndroidId.delete(deviceId);
        axesByAndroidId.remove(deviceId);
        playersByAndroidId.delete(deviceId);
    }

    @Override
    public void close() {
        if (closed)
            return;
        closed = true;
        if (inputManager != null)
            inputManager.unregisterInputDeviceListener(this);
        client.sendFocus(false, System.nanoTime());
        client.close();
        ForwardedInputSessionRegistry.detach(sessionId, client);
    }

    private void sendPointer(
            MotionEvent event,
            int pointerIndex,
            int width,
            int height,
            boolean released) {
        long buttons = released ? 0 : Integer.toUnsignedLong(event.getButtonState());
        int source = event.getSource();
        if (!released &&
                ((source & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN ||
                 (source & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS))
            buttons |= 1;
        client.sendPointerAbsolute(
                stableDeviceId(event.getDeviceId(), event.getDevice()),
                0,
                event.getToolType(pointerIndex),
                ForwardedInputMapping.toUnsignedQ16(event.getX(pointerIndex), width),
                ForwardedInputMapping.toUnsignedQ16(event.getY(pointerIndex), height),
                released ? 0 : ForwardedInputMapping.toUnsignedQ16(event.getPressure(pointerIndex), 2),
                Integer.toUnsignedLong(event.getPointerId(pointerIndex)),
                buttons,
                event.getEventTime() * 1_000_000L);
    }

    private long stableDeviceId(int androidDeviceId, InputDevice device) {
        int existing = stableIdsByAndroidId.get(androidDeviceId, 0);
        if (existing != 0)
            return Integer.toUnsignedLong(existing);

        int hash = 0x811C9DC5;
        if (device != null) {
            String descriptor = device.getDescriptor();
            if (descriptor != null) {
                for (int index = 0; index < descriptor.length(); index++) {
                    hash ^= descriptor.charAt(index);
                    hash *= 0x01000193;
                }
            }
            hash = mix(hash, device.getVendorId());
            hash = mix(hash, device.getProductId());
            hash = mix(hash, device.getSources());
        }
        else {
            hash = mix(hash, androidDeviceId);
        }
        if (hash == 0)
            hash = 1;
        stableIdsByAndroidId.put(androidDeviceId, hash);
        return Integer.toUnsignedLong(hash);
    }

    /** Assigns connected Android game controllers to P1-P4 deterministically. */
    private int playerForDevice(int androidDeviceId, InputDevice device) {
        if (!isGameController(device))
            return 0;
        int existing = playersByAndroidId.get(androidDeviceId, -1);
        if (existing >= 0)
            return existing;

        boolean[] used = new boolean[4];
        for (int index = 0; index < playersByAndroidId.size(); index++) {
            int player = playersByAndroidId.valueAt(index);
            if (player >= 0 && player < used.length)
                used[player] = true;
        }
        int player = 0;
        while (player + 1 < used.length && used[player])
            player++;
        playersByAndroidId.put(androidDeviceId, player);
        return player;
    }

    private static boolean isGameController(InputDevice device) {
        if (device == null)
            return false;
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private static int mix(int hash, int value) {
        hash ^= value;
        hash *= 0x01000193;
        return hash;
    }
}

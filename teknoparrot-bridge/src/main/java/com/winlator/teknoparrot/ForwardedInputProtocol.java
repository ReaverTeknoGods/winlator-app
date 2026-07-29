package com.winlator.teknoparrot;

/** Allocation-free TPI1 frame encoder for Winlator input taps. */
public final class ForwardedInputProtocol {
    public static final int PROTOCOL_VERSION = 1;
    public static final int HEADER_BYTES = 28;
    public static final int MAXIMUM_PAYLOAD_BYTES = 1024;

    public static final int TYPE_DEVICE_ADDED = 1;
    public static final int TYPE_DEVICE_REMOVED = 2;
    public static final int TYPE_KEY = 3;
    public static final int TYPE_AXIS = 4;
    public static final int TYPE_BUTTON = 5;
    public static final int TYPE_POINTER_ABSOLUTE = 6;
    public static final int TYPE_POINTER_RELATIVE = 7;
    public static final int TYPE_GAMEPAD_SNAPSHOT = 8;
    public static final int TYPE_FOCUS = 9;
    public static final int TYPE_SUSPEND = 10;

    public static final int BUTTON_PAYLOAD_BYTES = 4;
    public static final int AXIS_PAYLOAD_BYTES = 8;
    public static final int POINTER_ABSOLUTE_PAYLOAD_BYTES = 16;
    public static final int FOCUS_PAYLOAD_BYTES = 4;

    public static final int BUTTON_UP = 0;
    public static final int BUTTON_DOWN = 1;
    public static final int BUTTON_LEFT = 2;
    public static final int BUTTON_RIGHT = 3;
    public static final int BUTTON_START = 4;
    public static final int BUTTON_SERVICE = 5;
    public static final int BUTTON_TEST = 6;
    public static final int BUTTON_COIN = 7;
    public static final int BUTTON_1 = 8;
    public static final int BUTTON_2 = 9;
    public static final int BUTTON_3 = 10;
    public static final int BUTTON_4 = 11;
    public static final int BUTTON_5 = 12;
    public static final int BUTTON_6 = 13;
    public static final int BUTTON_7 = 14;
    public static final int BUTTON_8 = 15;

    private ForwardedInputProtocol() {}

    public static final class Header {
        public final int type;
        public final int payloadLength;
        public final long sequence;
        public final long eventTimeNanoseconds;
        public final long deviceStableId;

        private Header(int type, int payloadLength, long sequence,
                       long eventTimeNanoseconds, long deviceStableId) {
            this.type = type;
            this.payloadLength = payloadLength;
            this.sequence = sequence;
            this.eventTimeNanoseconds = eventTimeNanoseconds;
            this.deviceStableId = deviceStableId;
        }
    }

    public static int writeButtonFrame(
            byte[] destination,
            long sequence,
            long eventTimeNanoseconds,
            long deviceStableId,
            int player,
            int button,
            boolean pressed) {
        checkRange("player", player, 0, 3);
        checkRange("button", button, 0, 15);
        int offset = beginFrame(
                destination, TYPE_BUTTON, BUTTON_PAYLOAD_BYTES,
                sequence, eventTimeNanoseconds, deviceStableId);
        destination[offset] = (byte)player;
        destination[offset + 1] = pressed ? (byte)1 : (byte)0;
        writeU16(destination, offset + 2, button);
        return offset + BUTTON_PAYLOAD_BYTES;
    }

    public static int writeAxisFrame(
            byte[] destination,
            long sequence,
            long eventTimeNanoseconds,
            long deviceStableId,
            int player,
            int axisId,
            short valueQ15,
            int flatQ15) {
        checkRange("player", player, 0, 3);
        checkRange("axisId", axisId, 0, 15);
        checkRange("flatQ15", flatQ15, 0, Short.MAX_VALUE);
        int offset = beginFrame(
                destination, TYPE_AXIS, AXIS_PAYLOAD_BYTES,
                sequence, eventTimeNanoseconds, deviceStableId);
        destination[offset] = (byte)player;
        destination[offset + 1] = 0;
        writeU16(destination, offset + 2, axisId);
        writeU16(destination, offset + 4, valueQ15 & 0xffff);
        writeU16(destination, offset + 6, flatQ15);
        return offset + AXIS_PAYLOAD_BYTES;
    }

    public static int writePointerAbsoluteFrame(
            byte[] destination,
            long sequence,
            long eventTimeNanoseconds,
            long deviceStableId,
            int player,
            int toolType,
            int x,
            int y,
            int pressure,
            long pointerId,
            long buttons) {
        checkRange("player", player, 0, 3);
        checkRange("toolType", toolType, 0, 255);
        checkRange("x", x, 0, 0xffff);
        checkRange("y", y, 0, 0xffff);
        checkRange("pressure", pressure, 0, 0xffff);
        checkUnsignedInt("pointerId", pointerId);
        checkUnsignedInt("buttons", buttons);
        int offset = beginFrame(
                destination, TYPE_POINTER_ABSOLUTE, POINTER_ABSOLUTE_PAYLOAD_BYTES,
                sequence, eventTimeNanoseconds, deviceStableId);
        destination[offset] = (byte)player;
        destination[offset + 1] = (byte)toolType;
        writeU16(destination, offset + 2, x);
        writeU16(destination, offset + 4, y);
        writeU16(destination, offset + 6, pressure);
        writeU32(destination, offset + 8, pointerId);
        writeU32(destination, offset + 12, buttons);
        return offset + POINTER_ABSOLUTE_PAYLOAD_BYTES;
    }

    public static int writeFocusFrame(
            byte[] destination,
            long sequence,
            long eventTimeNanoseconds,
            long deviceStableId,
            boolean focused) {
        int offset = beginFrame(
                destination, TYPE_FOCUS, FOCUS_PAYLOAD_BYTES,
                sequence, eventTimeNanoseconds, deviceStableId);
        destination[offset] = focused ? (byte)1 : (byte)0;
        destination[offset + 1] = 0;
        destination[offset + 2] = 0;
        destination[offset + 3] = 0;
        return offset + FOCUS_PAYLOAD_BYTES;
    }

    public static int writeEmptyFrame(
            byte[] destination,
            int type,
            long sequence,
            long eventTimeNanoseconds,
            long deviceStableId) {
        if (type != TYPE_DEVICE_REMOVED && type != TYPE_SUSPEND)
            throw new IllegalArgumentException("type is not an empty TPI1 frame");
        return beginFrame(
                destination, type, 0, sequence, eventTimeNanoseconds, deviceStableId);
    }

    public static Header readHeader(byte[] packet, int length) {
        if (packet == null || length < HEADER_BYTES || length > packet.length ||
                packet[0] != 'T' || packet[1] != 'P' ||
                packet[2] != 'I' || packet[3] != '1')
            return null;

        int version = readU16(packet, 4);
        int type = readU16(packet, 6);
        long payloadLengthValue = readU32(packet, 8);
        if (version != PROTOCOL_VERSION || type < TYPE_DEVICE_ADDED || type > TYPE_SUSPEND ||
                payloadLengthValue > MAXIMUM_PAYLOAD_BYTES ||
                length != HEADER_BYTES + (int)payloadLengthValue)
            return null;

        long sequence = readU32(packet, 12);
        long eventTimeNanoseconds = readI64(packet, 16);
        long deviceStableId = readU32(packet, 24);
        return new Header(type, (int)payloadLengthValue, sequence,
                eventTimeNanoseconds, deviceStableId);
    }

    private static int beginFrame(
            byte[] destination,
            int type,
            int payloadLength,
            long sequence,
            long eventTimeNanoseconds,
            long deviceStableId) {
        if (destination == null || destination.length < HEADER_BYTES + payloadLength)
            throw new IllegalArgumentException("TPI1 destination buffer is too small");
        checkRange("type", type, TYPE_DEVICE_ADDED, TYPE_SUSPEND);
        checkRange("payloadLength", payloadLength, 0, MAXIMUM_PAYLOAD_BYTES);
        checkUnsignedInt("sequence", sequence);
        checkUnsignedInt("deviceStableId", deviceStableId);

        destination[0] = 'T';
        destination[1] = 'P';
        destination[2] = 'I';
        destination[3] = '1';
        writeU16(destination, 4, PROTOCOL_VERSION);
        writeU16(destination, 6, type);
        writeU32(destination, 8, payloadLength);
        writeU32(destination, 12, sequence);
        writeI64(destination, 16, eventTimeNanoseconds);
        writeU32(destination, 24, deviceStableId);
        return HEADER_BYTES;
    }

    private static void writeU16(byte[] destination, int offset, int value) {
        destination[offset] = (byte)value;
        destination[offset + 1] = (byte)(value >>> 8);
    }

    private static void writeU32(byte[] destination, int offset, long value) {
        destination[offset] = (byte)value;
        destination[offset + 1] = (byte)(value >>> 8);
        destination[offset + 2] = (byte)(value >>> 16);
        destination[offset + 3] = (byte)(value >>> 24);
    }

    private static void writeI64(byte[] destination, int offset, long value) {
        for (int index = 0; index < 8; index++)
            destination[offset + index] = (byte)(value >>> (index * 8));
    }

    private static int readU16(byte[] source, int offset) {
        return (source[offset] & 0xff) | ((source[offset + 1] & 0xff) << 8);
    }

    private static long readU32(byte[] source, int offset) {
        return (source[offset] & 0xffL) |
                ((source[offset + 1] & 0xffL) << 8) |
                ((source[offset + 2] & 0xffL) << 16) |
                ((source[offset + 3] & 0xffL) << 24);
    }

    private static long readI64(byte[] source, int offset) {
        long value = 0;
        for (int index = 0; index < 8; index++)
            value |= (source[offset + index] & 0xffL) << (index * 8);
        return value;
    }

    private static void checkRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum)
            throw new IllegalArgumentException(name + " is outside the TPI1 range");
    }

    private static void checkUnsignedInt(String name, long value) {
        if (value < 0 || value > 0xffffffffL)
            throw new IllegalArgumentException(name + " is outside the uint32 range");
    }
}

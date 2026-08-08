package com.winlator.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Prefix-local, exact-build patch for KOF XII's Quartz output negotiation.
 *
 * Wine 10.10 advertises I420 before YV12 in its 32-bit decodebin parser.
 * KOF XII accepts the first compatible type but creates its renderer for
 * YV12, leaving the movie graph without a usable sample path.  Reordering
 * those two formats fixes that title.  The APK asset is never modified: the
 * change is made only in the active prefix and is restored at teardown.
 */
public final class WineGStreamerOutputFormatPatch {
    private static final String ORIGINAL_SHA256 =
        "f35d717eaf5340260107dc38211a192c9fbf87fde53abc82e1ea2c123b4e8cf3";
    private static final long FORMAT_ORDER_OFFSET = 336512L;
    private static final int[] ORIGINAL_FORMAT_ORDER = {7, 8, 13, 12, 11, 14, 9};
    private static final int[] KOF_XII_FORMAT_ORDER = {7, 13, 8, 12, 11, 14, 9};

    private WineGStreamerOutputFormatPatch() {
    }

    public static boolean apply(File prefixDll) {
        if (!isUsable(prefixDll)) return false;
        try {
            int[] current = readAt(prefixDll, FORMAT_ORDER_OFFSET, ORIGINAL_FORMAT_ORDER.length);
            if (Arrays.equals(current, KOF_XII_FORMAT_ORDER)) return true;
            if (!Arrays.equals(current, ORIGINAL_FORMAT_ORDER) ||
                !ORIGINAL_SHA256.equals(sha256(prefixDll)))
                return false;
            writeAt(prefixDll, FORMAT_ORDER_OFFSET, KOF_XII_FORMAT_ORDER);
            return Arrays.equals(
                readAt(prefixDll, FORMAT_ORDER_OFFSET, KOF_XII_FORMAT_ORDER.length),
                KOF_XII_FORMAT_ORDER);
        }
        catch (IOException error) {
            return false;
        }
    }

    public static boolean restore(File prefixDll) {
        if (!isUsable(prefixDll)) return false;
        try {
            int[] current = readAt(prefixDll, FORMAT_ORDER_OFFSET, ORIGINAL_FORMAT_ORDER.length);
            if (Arrays.equals(current, ORIGINAL_FORMAT_ORDER))
                return ORIGINAL_SHA256.equals(sha256(prefixDll));
            if (!Arrays.equals(current, KOF_XII_FORMAT_ORDER)) return false;
            writeAt(prefixDll, FORMAT_ORDER_OFFSET, ORIGINAL_FORMAT_ORDER);
            return ORIGINAL_SHA256.equals(sha256(prefixDll));
        }
        catch (IOException error) {
            return false;
        }
    }

    private static boolean isUsable(File file) {
        return file != null && file.isFile() && file.canRead() && file.canWrite() &&
            file.length() >= FORMAT_ORDER_OFFSET + ORIGINAL_FORMAT_ORDER.length * 4L;
    }

    private static int[] readAt(File file, long offset, int count) throws IOException {
        int[] values = new int[count];
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(offset);
            for (int index = 0; index < count; index++)
                values[index] = Integer.reverseBytes(input.readInt());
        }
        return values;
    }

    private static void writeAt(File file, long offset, int[] values) throws IOException {
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.seek(offset);
            for (int value : values) output.writeInt(Integer.reverseBytes(value));
            output.getFD().sync();
        }
    }

    private static String sha256(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable.", error);
        }
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1)
                digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest())
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}

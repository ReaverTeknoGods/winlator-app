package com.winlator.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Prefix-local, exact-build KOF XII YV12 preference patch. */
public final class WineGStreamerOutputFormatPatch {
    private static final long EXPECTED_LENGTH = 495616L;
    private static final int FORMAT_ORDER_OFFSET = 0x52280;
    private static final byte[] ORIGINAL_FORMAT_ORDER = integers(7, 8, 13, 12, 11, 14, 9, 1, 2, 3, 5, 4);
    private static final byte[] PATCHED_FORMAT_ORDER = integers(7, 13, 8, 12, 11, 14, 9, 1, 2, 3, 5, 4);
    private static final byte[] ORIGINAL_SHA256 = hex("f35d717eaf5340260107dc38211a192c9fbf87fde53abc82e1ea2c123b4e8cf3");

    public enum Result { APPLIED, ALREADY_APPLIED, RESTORED, ALREADY_ORIGINAL, NOT_PRESENT, UNSUPPORTED }
    private WineGStreamerOutputFormatPatch() {}

    public static String fingerprint(File file) throws IOException {
        if (file == null) return "null";
        if (!file.isFile()) return "missing:" + file.getAbsolutePath();
        byte[] image = Files.readAllBytes(file.toPath());
        return "length=" + image.length + ", sha256=" + toHex(sha256(image));
    }

    public static Result configure(File immutableWineDll, File prefixDll, boolean enabled) throws IOException {
        byte[] source = read(immutableWineDll);
        if (!isOriginal(source)) return Result.UNSUPPORTED;
        if (enabled) {
            if (!prefixDll.isFile()) {
                File parent = prefixDll.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create the Wine syswow64 directory.");
                Files.copy(immutableWineDll.toPath(), prefixDll.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            byte[] target = read(prefixDll);
            if (isPatched(target)) return Result.ALREADY_APPLIED;
            if (!isOriginal(target)) return Result.UNSUPPORTED;
            writeAt(prefixDll, FORMAT_ORDER_OFFSET, PATCHED_FORMAT_ORDER);
            if (!isPatched(read(prefixDll))) throw new IOException("The prefix-local Wine-GStreamer patch did not validate.");
            return Result.APPLIED;
        }
        if (!prefixDll.isFile()) return Result.NOT_PRESENT;
        byte[] target = read(prefixDll);
        if (isOriginal(target)) return Result.ALREADY_ORIGINAL;
        if (!isPatched(target)) return Result.UNSUPPORTED;
        writeAt(prefixDll, FORMAT_ORDER_OFFSET, ORIGINAL_FORMAT_ORDER);
        if (!isOriginal(read(prefixDll))) throw new IOException("The prefix-local Wine-GStreamer restore did not validate.");
        return Result.RESTORED;
    }

    static boolean isOriginal(byte[] image) { return image.length == EXPECTED_LENGTH && matches(image, FORMAT_ORDER_OFFSET, ORIGINAL_FORMAT_ORDER) && Arrays.equals(sha256(image), ORIGINAL_SHA256); }
    static boolean isPatched(byte[] image) {
        if (image.length != EXPECTED_LENGTH || !matches(image, FORMAT_ORDER_OFFSET, PATCHED_FORMAT_ORDER)) return false;
        byte[] restored = image.clone();
        System.arraycopy(ORIGINAL_FORMAT_ORDER, 0, restored, FORMAT_ORDER_OFFSET, ORIGINAL_FORMAT_ORDER.length);
        return Arrays.equals(sha256(restored), ORIGINAL_SHA256);
    }
    private static byte[] read(File file) throws IOException { return file != null && file.isFile() && file.length() == EXPECTED_LENGTH ? Files.readAllBytes(file.toPath()) : new byte[0]; }
    private static void writeAt(File file, int offset, byte[] value) throws IOException { try (RandomAccessFile output = new RandomAccessFile(file, "rw")) { output.seek(offset); output.write(value); output.getFD().sync(); } }
    private static boolean matches(byte[] image, int offset, byte[] expected) { if (offset < 0 || offset + expected.length > image.length) return false; for (int index = 0; index < expected.length; index++) if (image[offset + index] != expected[index]) return false; return true; }
    private static byte[] integers(int... values) { byte[] result = new byte[values.length * 4]; for (int index = 0; index < values.length; index++) { int value = values[index]; result[index * 4] = (byte)value; result[index * 4 + 1] = (byte)(value >>> 8); result[index * 4 + 2] = (byte)(value >>> 16); result[index * 4 + 3] = (byte)(value >>> 24); } return result; }
    private static byte[] sha256(byte[] value) { try { return MessageDigest.getInstance("SHA-256").digest(value); } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable.", error); } }
    private static byte[] hex(String value) { byte[] result = new byte[value.length() / 2]; for (int index = 0; index < result.length; index++) result[index] = (byte)Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16); return result; }
    private static String toHex(byte[] value) { StringBuilder result = new StringBuilder(value.length * 2); for (byte item : value) result.append(String.format("%02x", item & 0xff)); return result.toString(); }
}

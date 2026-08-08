package com.winlator.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Applies a guarded Wine 10.10 WOW64 transition workaround to a prefix-local
 * wow64cpu.dll. The source under /opt/wine remains immutable.
 *
 * Affected 32-bit titles can enter Wine's 32-to-64-bit Unix-call thunk with an
 * unmasked x87 invalid-operation exception pending. The exception is delivered
 * at the first 64-bit instruction, before Wine exchanges onto its native
 * transition stack, so normal SEH cannot unwind it. The patched 32-bit thunk
 * executes FNCLEX before the far jump. No other Wine build is accepted.
 */
public final class Wow64CpuUnixTransitionPatch {
    private static final long EXPECTED_LENGTH = 40960L;
    private static final int PE_TIMESTAMP_OFFSET = 0x88;
    private static final int PE_CHECKSUM_OFFSET = 0xd8;
    private static final int CAVE_OFFSET = 0x1501;
    private static final int OPCODE_GETTER_OFFSET = 0x1850;

    private static final byte[] ORIGINAL_CAVE = {
        (byte)0x90, (byte)0x90, (byte)0x90, (byte)0x90, (byte)0x90,
        (byte)0x90, (byte)0x90, (byte)0x90, (byte)0x90, (byte)0x90,
        (byte)0x90, (byte)0x90, (byte)0x90, (byte)0x90, (byte)0x90
    };
    private static final byte[] PATCHED_CAVE = {
        (byte)0xdb, (byte)0xe2,                         // fnclex
        (byte)0xe8, 0x00, 0x00, 0x00, 0x00,             // call $+5
        0x58,                                           // pop eax
        0x05, 0x0a, 0x6b, 0x00, 0x00,                   // add eax, 0x6b0a
        (byte)0xff, 0x28                                // jmp fword ptr [eax]
    };
    private static final byte[] ORIGINAL_GETTER = {
        0x48, (byte)0x8d, 0x05, (byte)0xb5, 0x67, 0x00, 0x00, (byte)0xc3
    };
    private static final byte[] PATCHED_GETTER = {
        0x48, (byte)0x8d, 0x05, (byte)0xaa, (byte)0xfc, (byte)0xff,
        (byte)0xff, (byte)0xc3
    };
    private static final byte[] ORIGINAL_SHA256 = hex(
        "c5d5d8ee84c537735ec0414d0585de3064c5fb63177df73c1c09411907553cef");
    private static final byte[] ORIGINAL_PE_TIMESTAMP = {
        0x5d, (byte)0xd1, 0x32, 0x6a
    };
    private static final byte[] ORIGINAL_PE_CHECKSUM = {
        (byte)0x88, 0x71, 0x01, 0x00
    };

    public enum Result {
        APPLIED,
        ALREADY_APPLIED,
        RESTORED,
        ALREADY_ORIGINAL,
        NOT_PRESENT,
        UNSUPPORTED
    }

    private Wow64CpuUnixTransitionPatch() {}

    /** Returns a compact diagnostic fingerprint without changing the file. */
    public static String fingerprint(File file) throws IOException {
        if (file == null)
            return "null";
        if (!file.isFile())
            return "missing:" + file.getAbsolutePath();
        byte[] image = Files.readAllBytes(file.toPath());
        return "length=" + image.length + ", sha256=" + toHex(sha256(image));
    }

    /** Returns a bounded byte-difference summary for an unsupported prefix DLL. */
    public static String differenceSummary(File expected, File actual) throws IOException {
        if (expected == null || !expected.isFile() || actual == null || !actual.isFile())
            return "comparison unavailable";
        byte[] left = Files.readAllBytes(expected.toPath());
        byte[] right = Files.readAllBytes(actual.toPath());
        int sharedLength = Math.min(left.length, right.length);
        int differences = Math.abs(left.length - right.length);
        StringBuilder first = new StringBuilder();
        int reported = 0;
        for (int offset = 0; offset < sharedLength; offset++) {
            if (left[offset] == right[offset])
                continue;
            differences++;
            if (reported++ < 32) {
                if (first.length() > 0)
                    first.append(' ');
                first.append(String.format("%04x:%02x>%02x", offset,
                    left[offset] & 0xff, right[offset] & 0xff));
            }
        }
        return "differentBytes=" + differences + ", first=" + first;
    }

    public static Result configure(File immutableWineDll, File prefixDll,
                                   boolean enabled) throws IOException {
        if (enabled) {
            byte[] source = read(immutableWineDll);
            if (!isOriginal(source))
                return Result.UNSUPPORTED;
            if (!prefixDll.isFile()) {
                File parent = prefixDll.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                    throw new IOException("Could not create the Wine system32 directory.");
                Files.copy(immutableWineDll.toPath(), prefixDll.toPath());
            }

            byte[] target = read(prefixDll);
            if (isPatched(target))
                return Result.ALREADY_APPLIED;
            if (!isOriginal(target))
                return Result.UNSUPPORTED;

            // The cave is inert until the getter redirects Wine's generated
            // thunk to it, so an interrupted write cannot execute partial code.
            writeAt(prefixDll, CAVE_OFFSET, PATCHED_CAVE);
            writeAt(prefixDll, OPCODE_GETTER_OFFSET, PATCHED_GETTER);
            if (!isPatched(read(prefixDll)))
                throw new IOException("The prefix-local wow64cpu patch did not validate.");
            return Result.APPLIED;
        }

        if (!prefixDll.isFile())
            return Result.NOT_PRESENT;
        byte[] target = read(prefixDll);
        if (isOriginal(target))
            return Result.ALREADY_ORIGINAL;
        if (!isPatched(target))
            return Result.UNSUPPORTED;

        // Disable the redirect before restoring the now-unreachable cave.
        writeAt(prefixDll, OPCODE_GETTER_OFFSET, ORIGINAL_GETTER);
        writeAt(prefixDll, CAVE_OFFSET, ORIGINAL_CAVE);
        if (!isOriginal(read(prefixDll)))
            throw new IOException("The prefix-local wow64cpu restore did not validate.");
        return Result.RESTORED;
    }

    static boolean isOriginal(byte[] image) {
        return image.length == EXPECTED_LENGTH &&
            matches(image, CAVE_OFFSET, ORIGINAL_CAVE) &&
            matches(image, OPCODE_GETTER_OFFSET, ORIGINAL_GETTER) &&
            Arrays.equals(canonicalSha256(image), ORIGINAL_SHA256);
    }

    static boolean isPatched(byte[] image) {
        if (image.length != EXPECTED_LENGTH ||
            !matches(image, CAVE_OFFSET, PATCHED_CAVE) ||
            !matches(image, OPCODE_GETTER_OFFSET, PATCHED_GETTER))
            return false;
        byte[] restored = image.clone();
        System.arraycopy(ORIGINAL_CAVE, 0, restored, CAVE_OFFSET, ORIGINAL_CAVE.length);
        System.arraycopy(ORIGINAL_GETTER, 0, restored, OPCODE_GETTER_OFFSET,
            ORIGINAL_GETTER.length);
        return Arrays.equals(canonicalSha256(restored), ORIGINAL_SHA256);
    }

    private static byte[] canonicalSha256(byte[] image) {
        byte[] canonical = image.clone();
        // Wine may rewrite these non-executable PE metadata fields while
        // materializing the prefix-local DLL. No code or section data is
        // excluded from identity validation.
        System.arraycopy(ORIGINAL_PE_TIMESTAMP, 0, canonical,
            PE_TIMESTAMP_OFFSET, ORIGINAL_PE_TIMESTAMP.length);
        System.arraycopy(ORIGINAL_PE_CHECKSUM, 0, canonical,
            PE_CHECKSUM_OFFSET, ORIGINAL_PE_CHECKSUM.length);
        return sha256(canonical);
    }

    private static byte[] read(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() != EXPECTED_LENGTH)
            return new byte[0];
        return Files.readAllBytes(file.toPath());
    }

    private static void writeAt(File file, int offset, byte[] value) throws IOException {
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.seek(offset);
            output.write(value);
            output.getFD().sync();
        }
    }

    private static boolean matches(byte[] image, int offset, byte[] expected) {
        if (offset < 0 || offset + expected.length > image.length)
            return false;
        for (int index = 0; index < expected.length; index++) {
            if (image[offset + index] != expected[index])
                return false;
        }
        return true;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++)
            result[index] = (byte)Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        return result;
    }

    private static String toHex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value)
            result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }
}

package com.winlator.teknoparrot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** The managed x86 no-card-service payload, independent of prior title launches. */
public final class PreparedWinSCardCompatibility {
    private static final long MAX_PAYLOAD_BYTES = 1024 * 1024;

    private PreparedWinSCardCompatibility() {}

    public static boolean requiresPayload(String preset, int controls,
            String loader, String game) {
        // Preserve the existing, explicitly selected legacy preset.
        if ("taito-legacy-scard".equals(preset))
            return true;
        return "music-gungun-native-fullscreen".equals(preset) && controls == 9074 &&
            PreparedGameExecutablePath.isCanonicalDosPath(loader, false) &&
            loader.toLowerCase(Locale.ROOT).endsWith(
                "\\openparrotwin32\\openparrotloader.exe") &&
            PreparedGameExecutablePath.isCanonicalDosPath(game, false) &&
            game.substring(game.lastIndexOf('\\') + 1).equalsIgnoreCase("game.exe");
    }

    public static String withNativeOverride(String existing) {
        StringBuilder result = new StringBuilder();
        if (existing != null) {
            for (String clause : existing.split(";")) {
                int equals = clause.indexOf('=');
                if (clause.isEmpty() || (equals >= 0 &&
                        clause.substring(0, equals).trim().equalsIgnoreCase("winscard")))
                    continue;
                result.append(clause).append(';');
            }
        }
        return result.append("winscard=n,b").toString();
    }

    /** A failed asset read/copy must never accept an old prefix DLL as success. */
    public static void install(InputStream asset, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs()))
            throw new IOException("Cannot create the WinSCard payload directory");
        File staged = File.createTempFile("winscard-", ".tmp", parent);
        try {
            MessageDigest sourceHash = sha256();
            long count = 0;
            byte[] buffer = new byte[8192];
            try (FileOutputStream output = new FileOutputStream(staged)) {
                int read;
                while ((read = asset.read(buffer)) != -1) {
                    count += read;
                    if (count > MAX_PAYLOAD_BYTES)
                        throw new IOException("WinSCard payload exceeds its size limit");
                    sourceHash.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (count == 0 || staged.length() != count ||
                    !MessageDigest.isEqual(sourceHash.digest(), digest(staged)))
                throw new IOException("WinSCard payload copy verification failed");
            byte[] expected = digest(staged);
            Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (destination.length() != count ||
                    !MessageDigest.isEqual(expected, digest(destination)))
                throw new IOException("Installed WinSCard payload verification failed");
        }
        finally {
            Files.deleteIfExists(staged.toPath());
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static byte[] digest(File file) throws IOException {
        MessageDigest hash = sha256();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1)
                hash.update(buffer, 0, read);
        }
        return hash.digest();
    }
}

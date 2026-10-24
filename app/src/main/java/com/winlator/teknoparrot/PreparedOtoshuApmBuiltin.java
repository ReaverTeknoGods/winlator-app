package com.winlator.teknoparrot;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Review-only proposal: exact source-built Otoshu Wine builtin, no game copy/patch. */
public final class PreparedOtoshuApmBuiltin {
    public static final String ASSET = "teknoparrot/apm-builtin/x86_64-windows/apm.dll";
    public static final String SHIM_SHA256 = "b5601e4e27fa314c3176c2503b8518e5e3736e383c36e1b35bf15d61b2077ec6";
    public static final int SHIM_BYTES = 12288;
    public static final String GAME_SHA256 = "efb4419bc3b1883d89e4da0209b098c238bc5b2935c9946ee021061a4a5837f7";
    public static final int GAME_BYTES = 650752;
    private static final String RUNTIME = "E:\\TeknoParrotRuntime\\OpenParrotWin64";

    private PreparedOtoshuApmBuiltin() {}

    public static boolean matches(String preset, int controls, String library, String loader, String game) {
        return "otoshu-apm3-loader-safe".equals(preset) && controls == 9033 &&
            RUNTIME.equalsIgnoreCase(library) && (RUNTIME + "\\OpenParrotLoader64.exe").equalsIgnoreCase(loader) &&
            game != null && game.replace('/', '\\').toLowerCase(java.util.Locale.ROOT).endsWith("\\otsdx.exe");
    }

    public static Stage prepare(File filesDirectory, File game, InputStream asset) throws IOException {
        Path files = filesDirectory.toPath().toAbsolutePath().normalize();
        requireRealDirectory(files);
        if (!game.getName().equalsIgnoreCase("otsdx.exe") || Files.isSymbolicLink(game.toPath()) || !game.isFile())
            throw new IOException("The exact Otoshu executable is unavailable.");
        try (InputStream input = new FileInputStream(game)) {
            requireDigest(readExact(input, GAME_BYTES), GAME_SHA256);
        }
        byte[] shim = readExact(asset, SHIM_BYTES);
        validateShim(shim); // Full SHA fixes the complete reviewed106-name/ordinal/unique-RVA surface.
        Path base = files.resolve("teknoparrot-apm-builtin");
        if (!Files.exists(base, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(base);
        requireRealDirectory(base);
        Path owned = base.resolve(UUID.randomUUID().toString());
        Files.createDirectory(owned); // Fresh ownership; never merge/reuse an old namespace.
        Path architecture = owned.resolve("x86_64-windows");
        Files.createDirectory(architecture);
        byte[] owner = ("{\"purpose\":\"otoshu-source-built-wine-apm\",\"shimSha256\":\"" + SHIM_SHA256 +
            "\",\"gameSha256\":\"" + GAME_SHA256 + "\",\"exports\":106,\"architecture\":\"AMD64\"}\n").getBytes(StandardCharsets.UTF_8);
        Files.write(owned.resolve("owner.json"), owner, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.write(architecture.resolve("apm.dll"), shim, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Stage result = new Stage(files, owned, owner);
        result.verify();
        return result;
    }

    public static final class Stage {
        private final Path files, owned;
        private final byte[] owner;
        private Stage(Path files, Path owned, byte[] owner) { this.files = files; this.owned = owned; this.owner = owner; }
        public String hostSearchDirectory() throws IOException { verify(); return owned.toString(); }
        public void verify() throws IOException {
            requireRealDirectory(files); requireRealDirectory(owned.getParent()); requireRealDirectory(owned);
            requireRealDirectory(owned.resolve("x86_64-windows"));
            if (!owned.getParent().equals(files.resolve("teknoparrot-apm-builtin")))
                throw new IOException("The owned APM payload left private storage.");
            Path marker = owned.resolve("owner.json"), dll = owned.resolve("x86_64-windows/apm.dll");
            if (Files.isSymbolicLink(marker) || Files.isSymbolicLink(dll) || !Files.isRegularFile(marker) || !Files.isRegularFile(dll))
                throw new IOException("Owned APM payload was redirected.");
            try (InputStream input = new FileInputStream(marker.toFile())) {
                if (!java.util.Arrays.equals(readExact(input, owner.length), owner)) throw new IOException("APM owner changed.");
            }
            try (InputStream input = new FileInputStream(dll.toFile())) { validateShim(readExact(input, SHIM_BYTES)); }
        }
    }

    public static String composeSearchPath(String ownedHostPath, String existing) throws IOException {
        if (ownedHostPath == null || !ownedHostPath.startsWith("/") || ownedHostPath.indexOf(':') >= 0 || ownedHostPath.indexOf('\n') >= 0 || ownedHostPath.indexOf('\r') >= 0)
            throw new IOException("APM requires its canonical private host-absolute path.");
        return ownedHostPath + (existing == null || existing.isEmpty() ? "" : ":" + existing);
    }

    public static String composeOverrides(String existing) {
        // Wine add_load_order replaces an earlier matching module option.
        // Preserve every earlier/mixed group verbatim, then select only apm.
        return existing == null || existing.isEmpty() ? "apm=b" : existing + ";apm=b";
    }

    private static void requireRealDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path) || !path.toRealPath().equals(path))
            throw new IOException("Expected a canonical real private APM directory.");
    }
    private static byte[] readExact(InputStream input, int expected) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(expected);
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer, 0, Math.min(buffer.length, expected + 1 - output.size()))) != -1) {
            if (count == 0) { int value = input.read(); if (value == -1) break; output.write(value); }
            else output.write(buffer, 0, count);
            if (output.size() > expected) throw new IOException("APM input exceeds the exact reviewed size.");
        }
        if (output.size() != expected) throw new IOException("APM input has the wrong exact size.");
        return output.toByteArray();
    }
    private static void requireDigest(byte[] bytes, String expected) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder text = new StringBuilder();
            for (byte value : digest) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            if (!text.toString().equals(expected)) throw new IOException("Exact Otoshu/APM SHA256 differs.");
        } catch (NoSuchAlgorithmException error) { throw new IOException(error); }
    }
    public static void validateShim(byte[] bytes) throws IOException {
        if (bytes.length != SHIM_BYTES) throw new IOException("Wrong APM builtin size.");
        requireDigest(bytes, SHIM_SHA256);
        int pe = (bytes[60] & 255) | ((bytes[61] & 255) << 8) | ((bytes[62] & 255) << 16) | ((bytes[63] & 255) << 24);
        if (bytes[0] != 'M' || bytes[1] != 'Z' || pe < 96 || pe > bytes.length - 26 ||
            bytes[pe] != 'P' || bytes[pe + 1] != 'E' || bytes[pe + 2] != 0 || bytes[pe + 3] != 0 ||
            (bytes[pe + 4] & 255) != 0x64 || (bytes[pe + 5] & 255) != 0x86 ||
            (bytes[pe + 24] & 255) != 0x0b || (bytes[pe + 25] & 255) != 0x02 ||
            !new String(bytes, 64, 16, StandardCharsets.US_ASCII).equals("Wine builtin DLL"))
            throw new IOException("The reviewed APM payload is not the expected AMD64 Wine builtin.");
    }
}

package com.winlator.teknoparrot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** A retained, launch-lifetime E:data alias; never edits storage or container.json. */
public final class PreparedBg4DataProjection {
    private PreparedBg4DataProjection() {}

    public static boolean isEligible(boolean production, String preset, int controls,
            String loader, String game) {
        return production && "battle-gear-4-original".equals(preset) && controls == 9008 &&
            loader != null && loader.equalsIgnoreCase(
                "E:\\TeknoParrotRuntime\\OpenParrotWin32\\OpenParrotLoader.exe") &&
            PreparedGameExecutablePath.isCanonicalDosPath(game, false) &&
            "DGHI".indexOf(Character.toUpperCase(game.charAt(0))) >= 0 &&
            game.substring(game.lastIndexOf('\\') + 1).equalsIgnoreCase("game.exe");
    }

    public static final class Mount {
        public final String letter;
        public final File directory;
        public Mount(String letter, File directory) {
            this.letter = letter;
            this.directory = directory;
        }
    }

    /** NIO adapter is injectable only for portable host tests of the real policy. */
    static class FileSystem {
        Path driveLink(Path prefix) { return prefix.resolve("dosdevices").resolve("e:"); }
        void replace(Path source, Path target) throws IOException {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Session prepare(File rootFs, File container, int containerId,
            boolean managed, File privateStorage, File game,
            Iterable<Mount> mounts, BooleanSupplier guestsStopped) throws IOException {
        return prepare(rootFs, container, containerId, managed, privateStorage, game,
            mounts, guestsStopped, new FileSystem(), UUID.randomUUID().toString());
    }

    static Session prepare(File rootFs, File container, int containerId,
            boolean managed, File privateStorage, File game,
            Iterable<Mount> mounts, BooleanSupplier guestsStopped,
            FileSystem fs, String operation) throws IOException {
        if (!managed || containerId <= 0 || guestsStopped == null ||
                !operation.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IOException("An exact managed BG4 prefix/session is required.");
        Path root = rootFs.toPath().toRealPath();
        Path home = container.toPath().toRealPath();
        if (!home.equals(root.resolve("home").resolve("xuser-" + containerId)))
            throw new IOException("The BG4 container is outside its managed rootfs.");
        Path prefix = home.resolve(".wine");
        Path devices = prefix.resolve("dosdevices");
        requireDirectory(prefix);
        requireDirectory(devices);
        Path storage = privateStorage.toPath().toRealPath();
        Path runtime = storage.resolve("TeknoParrotRuntime");
        requireDirectory(runtime);
        if (!runtime.toRealPath().equals(runtime))
            throw new IOException("The private runtime root was redirected.");
        Set<String> letters = new HashSet<>();
        int eCount = 0;
        for (Mount mount : mounts) {
            if (mount == null || mount.letter == null ||
                    !mount.letter.matches("[A-Za-z]") || mount.directory == null ||
                    !letters.add(mount.letter.toUpperCase(Locale.ROOT)))
                throw new IOException("Duplicate or invalid prepared drive mapping.");
            if (mount.letter.equalsIgnoreCase("E")) {
                eCount++;
                if (!mount.directory.toPath().toRealPath().equals(storage))
                    throw new IOException("E no longer identifies the private runtime storage.");
            }
        }
        if (eCount != 1)
            throw new IOException("Exactly one managed E mapping is required.");
        Path executable = game.toPath().toRealPath();
        if (!Files.isRegularFile(executable) || !Files.isReadable(executable) ||
                !executable.getFileName().toString().equalsIgnoreCase("game.exe"))
            throw new IOException("The prepared BG4 executable is unavailable.");
        Path gameRoot = executable.getParent();
        if (inside(gameRoot, root) || inside(gameRoot, storage))
            throw new IOException("BG4 data must belong to the prepared shared game folder.");
        Path data = gameRoot.resolve("data").toRealPath();
        if (!inside(data, gameRoot) || data.equals(gameRoot) || !Files.isDirectory(data) || !Files.isReadable(data))
            throw new IOException("BG4 data escaped its prepared game folder.");
        Path movie = data.resolve("Scene/demo/attract/attract_demo.wmv").toRealPath();
        if (!inside(movie, data) || !Files.isRegularFile(movie) || !Files.isReadable(movie) || Files.size(movie) == 0)
            throw new IOException("The exact BG4 attract movie is unavailable or escaped data.");
        Path eLink = fs.driveLink(prefix);
        if (!eLink.getParent().equals(devices) || !Files.isSymbolicLink(eLink))
            throw new IOException("The managed E drive is not the expected prefix symlink.");
        Path original = Files.readSymbolicLink(eLink);
        if (!eLink.toRealPath().equals(storage))
            throw new IOException("Existing E target is unknown; preserving it.");
        requireStopped(guestsStopped);
        Path parent = prefix.resolve(".teknoparrot-bg4-projections");
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(parent);
        requireDirectory(parent);
        Path owned = parent.resolve(operation);
        Files.createDirectory(owned); // Exclusive ownership; never reuse any old root.
        Path projectedE = owned.resolve("e");
        Files.createDirectory(projectedE);
        Files.createSymbolicLink(projectedE.resolve("TeknoParrotRuntime"), runtime);
        Files.createSymbolicLink(projectedE.resolve("data"), data);
        writeNew(owned.resolve("owner.json"), "{\"purpose\":\"prepared-original-bg4-e-data\",\"operation\":" +
            quote(operation) + ",\"prefix\":" + quote(prefix.toString()) +
            ",\"originalE\":" + quote(original.toString()) + ",\"runtime\":" + quote(runtime.toString()) +
            ",\"data\":" + quote(data.toString()) + ",\"game\":" + quote(executable.toString()) + "}\n");
        return new Session(fs, guestsStopped, owned, projectedE, eLink, original, storage,
            runtime, data, gameRoot, movie, operation);
    }

    public static final class Session {
        private final FileSystem fs;
        private final BooleanSupplier guestsStopped;
        private final Path owned, projectedE, eLink, original, storage, runtime, data, gameRoot, movie;
        private final String operation;
        private Session(FileSystem fs, BooleanSupplier stopped, Path owned, Path projectedE,
                Path eLink, Path original, Path storage, Path runtime, Path data,
                Path gameRoot, Path movie, String operation) {
            this.fs = fs; this.guestsStopped = stopped; this.owned = owned;
            this.projectedE = projectedE; this.eLink = eLink; this.original = original;
            this.storage = storage; this.runtime = runtime; this.data = data;
            this.gameRoot = gameRoot; this.movie = movie; this.operation = operation;
        }

        /** Caller retains this Session before activation so failed activation can restore. */
        public void activate() throws IOException {
            requireStopped(guestsStopped);
            requireOriginal();
            requireDirectory(owned);
            requireDirectory(projectedE);
            if (!projectedE.resolve("TeknoParrotRuntime").toRealPath().equals(runtime) ||
                    !projectedE.resolve("data").toRealPath().equals(data) ||
                    !inside(data.toRealPath(), gameRoot) || !inside(movie.toRealPath(), data))
                throw new IOException("The staged BG4 projection changed before activation.");
            Path temporary = eLink.resolveSibling(".tp-bg4-e-" + operation);
            Files.createSymbolicLink(temporary, projectedE);
            requireStopped(guestsStopped);
            requireOriginal();
            fs.replace(temporary, eLink);
            if (!Files.isSymbolicLink(eLink) || !Files.readSymbolicLink(eLink).equals(projectedE) ||
                    !eLink.resolve("TeknoParrotRuntime").toRealPath().equals(runtime))
                throw new IOException("The BG4 E projection could not be verified.");
            writeNew(owned.resolve("published.json"), "{\"operation\":" + quote(operation) +
                ",\"status\":\"published-gameplay-untested\"}\n");
        }

        /** Restore only our still-matching link, after guests ended; retain all roots/writes. */
        public void restore() throws IOException {
            requireStopped(guestsStopped);
            if (!Files.isSymbolicLink(eLink))
                throw new IOException("E was replaced by a non-link; preserving it.");
            Path current = Files.readSymbolicLink(eLink);
            if (current.equals(original) && eLink.toRealPath().equals(storage)) return;
            if (!current.equals(projectedE))
                throw new IOException("E now belongs to another target; preserving it.");
            Path temporary = eLink.resolveSibling(".tp-bg4-e-restore-" + operation);
            Files.createSymbolicLink(temporary, original);
            requireStopped(guestsStopped);
            if (!Files.isSymbolicLink(eLink) || !Files.readSymbolicLink(eLink).equals(projectedE))
                throw new IOException("E changed during restore; preserving it.");
            fs.replace(temporary, eLink);
            requireOriginal();
            writeNew(owned.resolve("restored.json"), "{\"operation\":" + quote(operation) +
                ",\"status\":\"restored-projection-and-game-writes-retained\"}\n");
        }

        private void requireOriginal() throws IOException {
            if (!Files.isSymbolicLink(eLink) || !Files.readSymbolicLink(eLink).equals(original) ||
                    !eLink.toRealPath().equals(storage))
                throw new IOException("The original managed E target changed.");
        }
    }

    private static boolean inside(Path value, Path root) { return value.startsWith(root); }
    private static void requireStopped(BooleanSupplier stopped) throws IOException {
        if (!stopped.getAsBoolean())
            throw new IOException("Guest processes still own the prepared prefix.");
    }
    private static void requireDirectory(Path value) throws IOException {
        if (Files.isSymbolicLink(value) || !Files.isDirectory(value))
            throw new IOException("Expected an owned real directory: " + value);
    }
    private static void writeNew(Path path, String text) throws IOException {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
    private static String quote(String text) {
        StringBuilder value = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '"') value.append('\\').append(c);
            else if (c < 0x20) value.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
            else value.append(c);
        }
        return value.append('"').toString();
    }
}

import com.winlator.teknoparrot.PreparedGameExecutablePath;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class PreparedGameExecutablePathTest {
    private static int assertions;

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition)
            throw new AssertionError(message);
    }

    private static void write(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[] {0x4d, 0x5a});
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        PreparedGamePathActivity activity = new PreparedGamePathActivity();
        for (char drive : "CDEGHI".toCharArray()) {
            Path mount = root.resolve("drive-" + drive);
            write(mount.resolve("Game Folder/game.exe"));
            write(mount.resolve("Game Folder/arcade"));
            write(mount.resolve("Game Folder/game.elf"));
            activity.container.mounts.put("" + drive, mount.toString());
        }
        PreparedGameExecutablePath.MappedPathResolver resolver =
            path -> WineUtils.dosToUnixPath(path, activity.container);

        for (char drive : "CDEGHI".toCharArray()) {
            String game = drive + ":\\Game Folder\\game.exe";
            activity.preparedWindowsLaunch.arguments = new String[] {
                ".\\OpenParrotWin32\\OpenParrot", game, "-language=ja"};
            check(game.equals(activity.find()), "Prepared " + drive + " game path must be selected");
            check(activity.argument() == 1, "The exact recipe argument index must be retained");
            check(activity.contains(game, false), "Mapped " + drive + " file must remain contained");
            check(activity.contains(drive + ":\\Game Folder", true), "Mapped directory must be contained");
            check(activity.contains(drive + ":\\", true), "Existing mapped root remains a directory");
            check(!activity.contains(drive + ":\\", false), "A drive root is not an executable");
        }
        activity.preparedWindowsLaunch.arguments = new String[] {"g:\\Game Folder\\game.exe"};
        check(activity.find() != null, "Drive matching remains case insensitive");
        for (String drive : new String[] {"H", "I"}) {
            String mount = activity.container.mounts.remove(drive);
            activity.preparedWindowsLaunch.arguments = new String[] {drive + ":\\Game Folder\\game.exe"};
            check(activity.find() == null, "Optional " + drive + " must actually be mapped");
            check(!activity.contains(drive + ":\\Game Folder", true), "Unmapped directory is rejected");
            activity.container.mounts.put(drive, mount);
            check(activity.find() != null, "Validated optional " + drive + " mapping is accepted");
        }

        String[] invalid = {
            "A:\\Game Folder\\game.exe", "F:\\Game Folder\\game.exe", "J:\\Game Folder\\game.exe",
            "Z:\\Game Folder\\game.exe", "G:game.exe", "game.exe", ".\\game.exe",
            "/storage/emulated/0/TeknoParrotGames/game.exe", "\\\\server\\game.exe",
            "G:/Game Folder/game.exe", "G:\\\\Game Folder\\game.exe",
            "G:\\Game Folder\\..\\game.exe", "G:\\..\\game.exe", "G:\\.\\game.exe",
            "G:\\Game Folder\\.\\game.exe", "G:\\Game Folder\\game.exe\\..",
            "G:\\Game Folder\\game.exe\\", "G:\\Game Folder\\C:game.exe",
            "G:\\Game Folder\\\"game.exe", "G:\\Game Folder\\ga\nme.exe",
            "--game=G:\\Game Folder\\game.exe", "\"G:\\Game Folder\\game.exe\"", "", null
        };
        for (String value : invalid) {
            check(!PreparedGameExecutablePath.isCanonicalDosPath(value, false),
                "Noncanonical or unapproved argument must be rejected: " + value);
            check(!activity.contains(value, false), "Invalid path cannot pass containment: " + value);
            activity.preparedWindowsLaunch.arguments = new String[] {value};
            check(activity.find() == null, "Invalid path cannot become a game: " + value);
        }
        check(!PreparedGameExecutablePath.isCanonicalDosPath("G:\\" + "a".repeat(510) + ".exe", false),
            "Oversized paths remain outside the contract");
        activity.preparedWindowsLaunch.arguments = new String[] {"G:\\Game Folder\\missing.exe"};
        check(activity.find() == null, "The exact file must exist; do not scan for another game.exe");
        activity.preparedWindowsLaunch.arguments = new String[] {"-language=ja", ".\\OpenParrotWin32\\OpenParrot"};
        check(activity.find() == null, "Relative runtime modules and switches are not games");
        activity.preparedWindowsLaunch.arguments = new String[] {"G:\\Game Folder\\game.exe", "H:\\Game Folder\\game.exe"};
        check(activity.find() == null, "Two game executable arguments are ambiguous");
        activity.preparedWindowsLaunch.arguments = new String[] {"G:\\Game Folder\\game.exe", "H:\\missing.exe"};
        check(activity.find() == null, "A missing second executable must not hide ambiguity");
        activity.preparedWindowsLaunch.arguments = null;
        check(activity.find() == null, "A missing argument list is safe");

        for (String name : new String[] {"arcade", "game.elf"}) {
            String game = "I:\\Game Folder\\" + name;
            activity.preparedWindowsLaunch.arguments = new String[] {game};
            activity.preparedWindowsLaunch.executable = "E:\\TeknoParrotRuntime\\OpenParrotWin32\\OpenParrotLoader.exe";
            check(activity.find() == null, "OpenParrot requires an exe game argument");
            activity.preparedWindowsLaunch.executable = "E:\\TeknoParrotRuntime\\ElfLdr2\\ElfLoader.exe";
            check(game.equals(activity.find()), "ElfLdr2 keeps extensionless and ELF game support");
        }

        Path outside = root.resolve("outside/game.exe");
        write(outside);
        String game = "G:\\Game Folder\\game.exe";
        activity.preparedWindowsLaunch.arguments = new String[] {game};
        activity.container.overrides.put(game, outside.toString());
        check(activity.find() == null, "Resolved absolute escape is rejected");
        check(!activity.contains(game, false), "Absolute escapes cannot pass canonical containment");
        Path prefixSibling = root.resolve("drive-G-unrelated/game.exe");
        write(prefixSibling);
        activity.container.overrides.put(game, prefixSibling.toString());
        check(activity.find() == null, "A sibling sharing the mapped root prefix is rejected");
        activity.container.overrides.put(game, root.resolve("drive-G/../outside/game.exe").toString());
        check(activity.find() == null, "Canonical resolution cannot leave the declared mount");
        activity.container.overrides.put(game, "relative/game.exe");
        check(activity.find() == null, "A resolver cannot introduce a relative host path");
        activity.container.overrides.clear();
        check(activity.find() != null, "The exact mapped game remains selectable after rejected escapes");
        activity.preparedWindowsLaunch = null;
        check(activity.find() == null, "Unprepared Winlator launch is safe");
        activity.preparedWindowsLaunch = new PreparedPathLaunch();
        activity.container = null;
        check(activity.find() == null, "A container must be selected first");
        check(PreparedGameExecutablePath.findArgumentIndex(new String[] {game}, false, null) == -1,
            "No path is selected without a mapping resolver");
        check(PreparedGameExecutablePath.findArgumentIndex(new String[] {game}, false,
            path -> null) == -1, "Unknown mapping resolution fails closed");
        System.out.println("Prepared game path tests passed: " + assertions + " assertions");
    }
}

class PreparedPathLaunch {
    String executable = "E:\\TeknoParrotRuntime\\OpenParrotWin32\\OpenParrotLoader.exe";
    String[] arguments = new String[0];
}

class PathContainer {
    final Map<String, String> mounts = new HashMap<>();
    final Map<String, String> overrides = new HashMap<>();
}

class WineUtils {
    static String dosToUnixPath(String dosPath, PathContainer container) {
        if (container.overrides.containsKey(dosPath))
            return container.overrides.get(dosPath);
        String root = container.mounts.get(dosPath.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
        return root == null ? "" : root + File.separator + dosPath.substring(3).replace('\\', File.separatorChar);
    }
}

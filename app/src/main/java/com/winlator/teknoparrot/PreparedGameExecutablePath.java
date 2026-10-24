package com.winlator.teknoparrot;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Argument-only lookup inside the prepared session's existing Wine mounts. */
public final class PreparedGameExecutablePath {
    private PreparedGameExecutablePath() {}

    public interface MappedPathResolver {
        String toUnixPath(String dosPath);
    }

    /**
     * C/D/E retain their existing meaning; G is the managed game library, H
     * its optional removable counterpart, and I the validated launch-lifetime
     * folder. A letter alone never grants access: it must resolve to a mount.
     */
    public static boolean isCanonicalDosPath(String value, boolean directory) {
        if (value == null || value.length() < 3 || value.length() > 512 ||
            value.charAt(1) != ':' || value.charAt(2) != '\\' ||
            "CDEGHI".indexOf(Character.toUpperCase(value.charAt(0))) < 0)
            return false;
        if (value.length() == 3)
            return directory;
        String[] segments = value.substring(3).split("\\\\", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                return false;
            for (int index = 0; index < segment.length(); index++) {
                char character = segment.charAt(index);
                if (character < 0x20 || character == '/' || character == ':' ||
                    character == '"')
                    return false;
            }
        }
        return true;
    }

    /** Preserve canonical containment and existence checks, including symlinks. */
    public static boolean isWithinMappedDrive(String dosPath, boolean directory,
            MappedPathResolver resolver) throws IOException {
        if (!isCanonicalDosPath(dosPath, directory) || resolver == null)
            return false;
        String unixPath = resolver.toUnixPath(dosPath);
        String driveRootPath = resolver.toUnixPath(dosPath.substring(0, 3));
        if (unixPath == null || unixPath.isEmpty() ||
            driveRootPath == null || driveRootPath.isEmpty() ||
            !new File(unixPath).isAbsolute() || !new File(driveRootPath).isAbsolute())
            return false;
        File candidate = new File(unixPath).getCanonicalFile();
        File driveRoot = new File(driveRootPath).getCanonicalFile();
        String root = driveRoot.getPath();
        String path = candidate.getPath();
        boolean contained = path.equals(root) || path.startsWith(root + File.separator);
        return contained && (directory ? candidate.isDirectory() : candidate.isFile());
    }

    /**
     * Recipes supply one game path as a complete argument. Never search the
     * filesystem or interpret switches, relative module names, or embedded
     * command lines as games. Multiple executable arguments are ambiguous.
     * ElfLdr2 alone may supply an extensionless executable.
     */
    public static int findArgumentIndex(String[] arguments, boolean allowExtensionlessElf,
            MappedPathResolver resolver) {
        if (arguments == null)
            return -1;
        int found = -1;
        for (int index = 0; index < arguments.length; index++) {
            String candidate = arguments[index];
            if (!isCanonicalDosPath(candidate, false) ||
                (!allowExtensionlessElf &&
                 !candidate.toLowerCase(Locale.ROOT).endsWith(".exe")))
                continue;
            // Reject ambiguity even if one of the supplied paths is missing.
            if (found >= 0)
                return -1;
            found = index;
        }
        if (found < 0)
            return -1;
        try {
            return isWithinMappedDrive(arguments[found], false, resolver) ? found : -1;
        }
        catch (IOException | SecurityException error) {
            return -1;
        }
    }
}

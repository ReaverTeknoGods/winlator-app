package com.winlator.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import com.winlator.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * Migrates runtime data inherited from the upstream com.winlator application
 * ID without changing Winlator's Java namespace.  Pinned native components
 * embed the old rootfs path in binaries as well as configuration files, so the
 * replacement must preserve the original byte length.
 */
public final class PackagePathCompat {
    private static final String TAG = "PackagePathCompat";
    private static final String PREFERENCES_NAME = "package_path_compat";
    private static final String ROOTFS_MIGRATION_KEY = "rootfs_migration_version";
    private static final int ROOTFS_MIGRATION_VERSION = 1;
    private static final int PATCH_BUFFER_SIZE = 1024 * 1024;

    private static final String UPSTREAM_DATA_PATH = "/data/data/com.winlator";
    private static final String UPSTREAM_USER_DATA_PATH = "/data/user/0/com.winlator";
    private static final String UPSTREAM_ROOTFS_PATH = UPSTREAM_DATA_PATH+"/files/rootfs";
    private static final String UPSTREAM_USER_ROOTFS_PATH = UPSTREAM_USER_DATA_PATH+"/files/rootfs";
    private static final String PREVIOUS_CWD_ROOTFS_PATH = "/proc/self/cwd";
    private static final String[] ROOTFS_DIRECTORIES = {"bin", "etc", "home", "lib", "opt", "tmp", "usr", "var"};

    private PackagePathCompat() {}

    public static boolean ensureRootfs(Context context, File rootDir) {
        try {
            ensureRootfsAliases(context, rootDir);

            SharedPreferences preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
            if (preferences.getInt(ROOTFS_MIGRATION_KEY, 0) < ROOTFS_MIGRATION_VERSION) {
                MigrationStats stats = new MigrationStats();
                patchTree(context, rootDir, stats);
                if (!preferences.edit().putInt(ROOTFS_MIGRATION_KEY, ROOTFS_MIGRATION_VERSION).commit()) {
                    throw new IOException("Could not persist the rootfs package-path migration marker.");
                }
                Log.i(TAG, "Scanned "+stats.filesScanned+" rootfs files and migrated "+
                    stats.replacements+" embedded package path(s) in "+stats.filesPatched+" file(s).");
            }
            return true;
        }
        catch (Exception e) {
            Log.e(TAG, "Could not migrate package-specific rootfs paths.", e);
            return false;
        }
    }

    /** Patch a newly extracted component before it can be launched. */
    public static void patchExtractedFile(Context context, File file) throws IOException {
        if (file == null || !file.isFile() || FileUtils.isSymlink(file)) return;
        patchFile(context, file);
    }

    /**
     * Migrate application-private paths stored in container JSON.  These are
     * normal strings, so unlike native binaries they can use the canonical
     * path for the current application ID without padding.
     */
    public static boolean migrateStoredPaths(JSONObject object) throws JSONException {
        boolean changed = false;
        for (Iterator<String> iterator = object.keys(); iterator.hasNext();) {
            String key = iterator.next();
            Object value = object.get(key);
            if (value instanceof JSONObject) {
                changed |= migrateStoredPaths((JSONObject)value);
            }
            else if (value instanceof JSONArray) {
                changed |= migrateStoredPaths((JSONArray)value);
            }
            else if (value instanceof String) {
                String original = (String)value;
                String migrated = migrateStoredPath(original);
                if (!original.equals(migrated)) {
                    object.put(key, migrated);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static boolean migrateStoredPaths(JSONArray array) throws JSONException {
        boolean changed = false;
        for (int index = 0; index < array.length(); index++) {
            Object value = array.get(index);
            if (value instanceof JSONObject) {
                changed |= migrateStoredPaths((JSONObject)value);
            }
            else if (value instanceof JSONArray) {
                changed |= migrateStoredPaths((JSONArray)value);
            }
            else if (value instanceof String) {
                String original = (String)value;
                String migrated = migrateStoredPath(original);
                if (!original.equals(migrated)) {
                    array.put(index, migrated);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static String migrateStoredPath(String value) {
        return value
            .replace(UPSTREAM_USER_DATA_PATH, "/data/user/0/"+BuildConfig.APPLICATION_ID)
            .replace(UPSTREAM_DATA_PATH, "/data/data/"+BuildConfig.APPLICATION_ID);
    }

    private static void ensureRootfsAliases(Context context, File rootDir) throws IOException, ErrnoException {
        for (String directory : ROOTFS_DIRECTORIES) {
            ensureSymlink(new File(context.getDataDir(), directory), new File(rootDir, directory));
        }
    }

    private static void ensureSymlink(File link, File target) throws IOException, ErrnoException {
        try {
            String currentTarget = Os.readlink(link.getPath());
            if (!currentTarget.equals(target.getPath())) {
                throw new IOException("Unexpected rootfs alias target for "+link+": "+currentTarget);
            }
        }
        catch (ErrnoException e) {
            if (e.errno != OsConstants.ENOENT) throw e;
            Os.symlink(target.getPath(), link.getPath());
        }
    }

    private static void patchTree(Context context, File directory, MigrationStats stats) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Could not enumerate runtime directory: "+directory);

        for (File child : children) {
            if (FileUtils.isSymlink(child)) continue;
            if (child.isDirectory()) {
                patchTree(context, child, stats);
            }
            else if (child.isFile()) {
                stats.filesScanned++;
                int replacements = patchFile(context, child);
                if (replacements > 0) {
                    stats.filesPatched++;
                    stats.replacements += replacements;
                }
            }
        }
    }

    private static int patchFile(Context context, File file) throws IOException {
        String replacementBase = "/data/data/"+context.getPackageName()+"/";
        PathReplacement[] replacements = {
            new PathReplacement(UPSTREAM_ROOTFS_PATH, padPathToLength(replacementBase, UPSTREAM_ROOTFS_PATH.length())),
            new PathReplacement(UPSTREAM_USER_ROOTFS_PATH, padPathToLength(replacementBase, UPSTREAM_USER_ROOTFS_PATH.length())),
            new PathReplacement(padPathToLength(PREVIOUS_CWD_ROOTFS_PATH, UPSTREAM_ROOTFS_PATH.length()),
                padPathToLength(replacementBase, UPSTREAM_ROOTFS_PATH.length()))
        };

        int replacementCount = 0;
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw")) {
            long length = randomAccessFile.length();
            int overlap = longestPattern(replacements)-1;
            for (long offset = 0; offset < length; offset += PATCH_BUFFER_SIZE) {
                int ownedLength = (int)Math.min(PATCH_BUFFER_SIZE, length-offset);
                int readLength = (int)Math.min((long)ownedLength+overlap, length-offset);
                byte[] buffer = new byte[readLength];
                randomAccessFile.seek(offset);
                randomAccessFile.readFully(buffer);

                boolean changed = false;
                for (PathReplacement replacement : replacements) {
                    for (int index = 0; index < ownedLength && index <= buffer.length-replacement.oldBytes.length; index++) {
                        if (!matches(buffer, index, replacement.oldBytes)) continue;
                        System.arraycopy(replacement.newBytes, 0, buffer, index, replacement.newBytes.length);
                        replacementCount++;
                        changed = true;
                        index += replacement.oldBytes.length-1;
                    }
                }

                if (changed) {
                    randomAccessFile.seek(offset);
                    randomAccessFile.write(buffer);
                }
            }
        }
        return replacementCount;
    }

    private static boolean matches(byte[] contents, int offset, byte[] value) {
        for (int index = 0; index < value.length; index++) {
            if (contents[offset+index] != value[index]) return false;
        }
        return true;
    }

    private static int longestPattern(PathReplacement[] replacements) {
        int result = 0;
        for (PathReplacement replacement : replacements) result = Math.max(result, replacement.oldBytes.length);
        return result;
    }

    private static String padPathToLength(String path, int length) throws IOException {
        if (path.length() > length) throw new IOException("Application ID is too long for the pinned runtime path: "+path);
        StringBuilder result = new StringBuilder(path);
        while (result.length() < length) result.append('/');
        return result.toString();
    }

    private static final class PathReplacement {
        final byte[] oldBytes;
        final byte[] newBytes;

        PathReplacement(String oldValue, String newValue) throws IOException {
            oldBytes = oldValue.getBytes(StandardCharsets.UTF_8);
            newBytes = newValue.getBytes(StandardCharsets.UTF_8);
            if (oldBytes.length != newBytes.length) {
                throw new IOException("Runtime path replacements must have equal byte lengths.");
            }
        }
    }

    private static final class MigrationStats {
        int filesScanned;
        int filesPatched;
        int replacements;
    }
}

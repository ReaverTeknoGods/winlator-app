package com.winlator.teknoparrot;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs a content-addressed runtime package received from the signed TPUI
 * client. The archive and every manifest-listed file are SHA256 verified,
 * archive paths are constrained to package-specific runtime roots, and all
 * extraction happens in Winlator's private storage.
 */
final class TeknoParrotRuntimePackageInstaller {
    private static final String MANIFEST_NAME = "teknoparrot-package.json";
    private static final String PAYLOAD_PREFIX = "payload/";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final int MAX_FILES = 20000;
    private static final long MAX_DECLARED_BYTES = 20L * 1024 * 1024 * 1024;
    private static final Map<String, Set<String>> ALLOWED_ROOTS = new HashMap<>();

    static {
        ALLOWED_ROOTS.put(
            "OpenParrotWin32",
            new HashSet<>(Arrays.asList("OpenParrotWin32")));
        ALLOWED_ROOTS.put(
            "OpenParrotx64",
            new HashSet<>(Arrays.asList("OpenParrotWin64")));
        ALLOWED_ROOTS.put(
            "TeknoParrot",
            new HashSet<>(Arrays.asList("TeknoParrot")));
        ALLOWED_ROOTS.put(
            "TeknoParrotElfLdr2",
            new HashSet<>(Arrays.asList("ElfLdr2")));
        ALLOWED_ROOTS.put(
            "cxbxr",
            new HashSet<>(Arrays.asList("cxbxr-export", "cxbxr-japan")));
    }

    private TeknoParrotRuntimePackageInstaller() {
    }

    static String queryInstalledPackages(Context context) throws IOException {
        File runtimeRoot = new File(
            context.getApplicationInfo().dataDir,
            "storage/TeknoParrotRuntime");
        File markerDirectory = new File(runtimeRoot, ".packages");
        try {
            JSONObject result = new JSONObject();
            result.put("schemaVersion", SCHEMA_VERSION);
            JSONObject packages = new JSONObject();
            for (String packageId : ALLOWED_ROOTS.keySet()) {
                File marker = new File(markerDirectory, packageId + ".json");
                if (!marker.isFile())
                    continue;
                JSONObject installed;
                try (InputStream input = new FileInputStream(marker)) {
                    installed = new JSONObject(new String(
                        readBounded(input, MAX_MANIFEST_BYTES),
                        StandardCharsets.UTF_8));
                }
                if (installed.optInt("schemaVersion") == SCHEMA_VERSION &&
                    packageId.equals(installed.optString("packageId")) &&
                    isSafeVersion(installed.optString("version")))
                    packages.put(packageId, installed.getString("version"));
            }
            result.put("packages", packages);
            return result.toString();
        }
        catch (JSONException error) {
            throw new IOException("Could not encode installed runtime packages.", error);
        }
    }

    static String install(
        Context context,
        ParcelFileDescriptor descriptor,
        String expectedPackageId,
        String expectedVersion,
        String expectedArchiveDigest) throws IOException {
        Set<String> allowedRoots = ALLOWED_ROOTS.get(expectedPackageId);
        if (allowedRoots == null)
            throw new IOException("Unsupported runtime package id: " + expectedPackageId);
        if (!isSafeVersion(expectedVersion))
            throw new IOException("Runtime package version is invalid.");
        byte[] expectedDigest = parseDigest(expectedArchiveDigest);

        File runtimeRoot = new File(
            context.getApplicationInfo().dataDir,
            "storage/TeknoParrotRuntime");
        File workRoot = new File(runtimeRoot, ".package-work");
        if (!workRoot.isDirectory() && !workRoot.mkdirs())
            throw new IOException("Could not create the runtime package work directory.");
        String operationId = expectedPackageId + "-" + UUID.randomUUID();
        File archive = new File(workRoot, operationId + ".zip");
        File staging = new File(workRoot, operationId + "-staging");
        File backup = new File(workRoot, operationId + "-backup");

        try {
            copyAndVerifyArchive(descriptor, archive, expectedDigest);
            PackageManifest manifest = readManifest(
                archive,
                expectedPackageId,
                expectedVersion,
                allowedRoots);
            extractAndVerify(archive, staging, manifest);
            replaceRoots(runtimeRoot, staging, backup, allowedRoots);
            try {
                writeInstalledMarker(runtimeRoot, manifest, expectedArchiveDigest);
            }
            catch (IOException error) {
                rollbackRoots(runtimeRoot, backup, allowedRoots, error);
                throw error;
            }
            return expectedPackageId + " " + expectedVersion + " installed.";
        }
        finally {
            deleteRecursively(staging);
            deleteRecursively(backup);
            if (archive.exists() && !archive.delete())
                archive.deleteOnExit();
        }
    }

    private static void copyAndVerifyArchive(
        ParcelFileDescriptor descriptor,
        File destination,
        byte[] expectedDigest) throws IOException {
        MessageDigest hash = sha256();
        try (InputStream input = new BufferedInputStream(
                 new FileInputStream(descriptor.getFileDescriptor()));
             FileOutputStream rawOutput = new FileOutputStream(destination);
             BufferedOutputStream output = new BufferedOutputStream(rawOutput)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                hash.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }
        if (!MessageDigest.isEqual(expectedDigest, hash.digest()))
            throw new IOException("Runtime archive SHA256 mismatch.");
    }

    private static PackageManifest readManifest(
        File archive,
        String expectedPackageId,
        String expectedVersion,
        Set<String> allowedRoots) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            rejectDuplicateOrUnexpectedEntries(zip);
            ZipEntry manifestEntry = zip.getEntry(MANIFEST_NAME);
            if (manifestEntry == null || manifestEntry.isDirectory() ||
                manifestEntry.getSize() <= 0 ||
                manifestEntry.getSize() > MAX_MANIFEST_BYTES)
                throw new IOException("Runtime package manifest is missing or invalid.");

            JSONObject json;
            try (InputStream input = zip.getInputStream(manifestEntry)) {
                json = new JSONObject(new String(
                    readBounded(input, MAX_MANIFEST_BYTES),
                    StandardCharsets.UTF_8));
            }
            if (json.getInt("schemaVersion") != SCHEMA_VERSION ||
                !"android".equals(json.getString("platform")) ||
                !expectedPackageId.equals(json.getString("packageId")) ||
                !expectedVersion.equals(json.getString("version")))
                throw new IOException("Runtime package identity does not match the update.");

            JSONArray files = json.getJSONArray("files");
            if (files.length() == 0 || files.length() > MAX_FILES)
                throw new IOException("Runtime package file count is invalid.");
            List<ManifestFile> parsed = new ArrayList<>(files.length());
            Set<String> paths = new HashSet<>();
            Set<String> roots = new HashSet<>();
            long totalSize = 0;
            for (int index = 0; index < files.length(); index++) {
                JSONObject file = files.getJSONObject(index);
                String path = file.getString("path");
                validatePayloadPath(path, allowedRoots);
                if (!paths.add(path))
                    throw new IOException("Duplicate runtime manifest path: " + path);
                long size = file.getLong("size");
                if (size < 0 || size > MAX_DECLARED_BYTES - totalSize)
                    throw new IOException("Runtime package declared size is invalid.");
                totalSize += size;
                byte[] digest = parseHexDigest(file.getString("sha256"));
                String root = path.substring(0, path.indexOf('/'));
                roots.add(root);
                parsed.add(new ManifestFile(path, size, digest));
            }
            return new PackageManifest(
                expectedPackageId,
                expectedVersion,
                parsed,
                roots);
        }
        catch (JSONException error) {
            throw new IOException("Runtime package manifest is invalid JSON.", error);
        }
    }

    private static void rejectDuplicateOrUnexpectedEntries(ZipFile zip)
        throws IOException {
        Set<String> names = new HashSet<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!names.add(name))
                throw new IOException("Duplicate runtime archive entry: " + name);
            if (MANIFEST_NAME.equals(name))
                continue;
            if (!name.startsWith(PAYLOAD_PREFIX))
                throw new IOException("Unexpected runtime archive entry: " + name);
            String relative = name.substring(PAYLOAD_PREFIX.length());
            if (!entry.isDirectory() && relative.endsWith("/"))
                throw new IOException("Invalid runtime archive entry: " + name);
        }
    }

    private static void extractAndVerify(
        File archive,
        File staging,
        PackageManifest manifest) throws IOException {
        if (!staging.mkdirs())
            throw new IOException("Could not create the runtime staging directory.");
        try (ZipFile zip = new ZipFile(archive)) {
            Set<String> manifestPaths = new HashSet<>();
            for (ManifestFile file : manifest.files) {
                manifestPaths.add(file.path);
                ZipEntry entry = zip.getEntry(PAYLOAD_PREFIX + file.path);
                if (entry == null || entry.isDirectory() || entry.getSize() != file.size)
                    throw new IOException("Runtime payload entry is missing: " + file.path);
                File destination = new File(staging, file.path);
                requireContained(staging, destination);
                File parent = destination.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs())
                    throw new IOException("Could not create runtime payload directory.");
                MessageDigest hash = sha256();
                long written = 0;
                try (InputStream input = new BufferedInputStream(zip.getInputStream(entry));
                     FileOutputStream rawOutput = new FileOutputStream(destination);
                     BufferedOutputStream output = new BufferedOutputStream(rawOutput)) {
                    byte[] buffer = new byte[1024 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        written += count;
                        if (written > file.size)
                            throw new IOException("Runtime payload exceeded declared size.");
                        hash.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                    }
                }
                if (written != file.size ||
                    !MessageDigest.isEqual(file.digest, hash.digest()))
                    throw new IOException(
                        "Runtime payload verification failed: " + file.path);
            }

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || MANIFEST_NAME.equals(entry.getName()))
                    continue;
                String relative = entry.getName().substring(PAYLOAD_PREFIX.length());
                if (!manifestPaths.contains(relative))
                    throw new IOException(
                        "Runtime archive contains an unlisted payload: " + relative);
            }
        }
    }

    private static void replaceRoots(
        File runtimeRoot,
        File staging,
        File backup,
        Set<String> roots) throws IOException {
        if (!runtimeRoot.isDirectory() && !runtimeRoot.mkdirs())
            throw new IOException("Could not create Winlator's runtime directory.");
        if (!backup.mkdirs())
            throw new IOException("Could not create the runtime backup directory.");

        List<String> installed = new ArrayList<>();
        List<String> backedUp = new ArrayList<>();
        try {
            for (String root : roots) {
                File stagedRoot = new File(staging, root);
                if (stagedRoot.exists() && !stagedRoot.isDirectory())
                    throw new IOException("Runtime package root is invalid: " + root);
                File destination = new File(runtimeRoot, root);
                File old = new File(backup, root);
                if (destination.exists()) {
                    if (!destination.renameTo(old))
                        throw new IOException("Could not back up runtime root: " + root);
                    backedUp.add(root);
                }
                // Every allowed root belongs to this package. If an optional
                // root is omitted from the new archive, backing up (and later
                // discarding) the old root prevents stale binaries surviving
                // an otherwise successful update.
                if (stagedRoot.isDirectory() &&
                    !stagedRoot.renameTo(destination))
                    throw new IOException("Could not install runtime root: " + root);
                if (stagedRoot.exists() || destination.isDirectory())
                    installed.add(root);
            }
        }
        catch (IOException error) {
            for (int index = installed.size() - 1; index >= 0; index--)
                deleteRecursively(new File(runtimeRoot, installed.get(index)));
            for (int index = backedUp.size() - 1; index >= 0; index--) {
                String root = backedUp.get(index);
                File old = new File(backup, root);
                File destination = new File(runtimeRoot, root);
                if (old.exists() && !old.renameTo(destination))
                    throw new IOException(
                        "Runtime rollback failed for " + root + ".", error);
            }
            throw error;
        }
    }

    private static void rollbackRoots(
        File runtimeRoot,
        File backup,
        Set<String> roots,
        IOException cause) throws IOException {
        IOException rollbackError = null;
        for (String root : roots) {
            File destination = new File(runtimeRoot, root);
            if (destination.exists() && !deleteRecursivelyChecked(destination) &&
                rollbackError == null)
                rollbackError = new IOException(
                    "Could not remove failed runtime root: " + root);
            File old = new File(backup, root);
            if (old.exists() && !old.renameTo(destination) &&
                rollbackError == null)
                rollbackError = new IOException(
                    "Could not restore runtime root: " + root);
        }
        if (rollbackError != null) {
            rollbackError.addSuppressed(cause);
            throw new IOException(
                "Runtime rollback failed after the package marker could not be written.",
                rollbackError);
        }
    }

    private static void writeInstalledMarker(
        File runtimeRoot,
        PackageManifest manifest,
        String archiveDigest) throws IOException {
        File markerDirectory = new File(runtimeRoot, ".packages");
        if (!markerDirectory.isDirectory() && !markerDirectory.mkdirs())
            throw new IOException("Could not create the runtime package marker directory.");
        File marker = new File(markerDirectory, manifest.packageId + ".json");
        File temporary = new File(markerDirectory, manifest.packageId + ".json.tmp");
        try {
            JSONObject json = new JSONObject();
            json.put("schemaVersion", SCHEMA_VERSION);
            json.put("packageId", manifest.packageId);
            json.put("version", manifest.version);
            json.put("digest", archiveDigest.toLowerCase(Locale.ROOT));
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(json.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
        }
        catch (JSONException error) {
            throw new IOException("Could not create the runtime package marker.", error);
        }
        if (marker.exists() && !marker.delete())
            throw new IOException("Could not replace the runtime package marker.");
        if (!temporary.renameTo(marker))
            throw new IOException("Could not publish the runtime package marker.");
    }

    private static void validatePayloadPath(String path, Set<String> allowedRoots)
        throws IOException {
        if (path == null || path.isEmpty() || path.length() > 1024 ||
            path.startsWith("/") || path.endsWith("/") || path.contains("\\") ||
            path.contains("\u0000") || path.contains("//") ||
            path.equals(".") || path.equals("..") ||
            path.startsWith("./") || path.contains("/../") ||
            path.endsWith("/.."))
            throw new IOException("Unsafe runtime package path: " + path);
        int separator = path.indexOf('/');
        if (separator <= 0 || !allowedRoots.contains(path.substring(0, separator)))
            throw new IOException("Runtime package path is outside its allowed roots: " + path);
    }

    private static void requireContained(File root, File child) throws IOException {
        String rootPath = root.getCanonicalPath() + File.separator;
        if (!child.getCanonicalPath().startsWith(rootPath))
            throw new IOException("Runtime package path escaped the staging directory.");
    }

    private static byte[] readBounded(InputStream input, int limit)
        throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() > limit - count)
                throw new IOException("Runtime package manifest exceeds its size limit.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static byte[] parseDigest(String value) throws IOException {
        if (value == null || !value.regionMatches(true, 0, "sha256:", 0, 7))
            throw new IOException("Runtime archive digest must use sha256.");
        return parseHexDigest(value.substring(7));
    }

    private static byte[] parseHexDigest(String value) throws IOException {
        if (value == null || !value.matches("[0-9a-fA-F]{64}"))
            throw new IOException("Runtime package SHA256 is invalid.");
        byte[] result = new byte[32];
        for (int index = 0; index < result.length; index++)
            result[index] = (byte) Integer.parseInt(
                value.substring(index * 2, index * 2 + 2),
                16);
        return result;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("Android has no SHA-256 provider.", error);
        }
    }

    private static boolean isSafeVersion(String version) {
        return version != null && version.length() <= 128 &&
            version.matches("[A-Za-z0-9._+-]+");
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists())
            return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children)
                    deleteRecursively(child);
            }
        }
        if (!file.delete())
            file.deleteOnExit();
    }

    private static boolean deleteRecursivelyChecked(File file) {
        if (file == null || !file.exists())
            return true;
        boolean deleted = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null)
                deleted = false;
            else {
                for (File child : children)
                    deleted &= deleteRecursivelyChecked(child);
            }
        }
        return file.delete() && deleted;
    }

    private static final class ManifestFile {
        final String path;
        final long size;
        final byte[] digest;

        ManifestFile(String path, long size, byte[] digest) {
            this.path = path;
            this.size = size;
            this.digest = digest;
        }
    }

    private static final class PackageManifest {
        final String packageId;
        final String version;
        final List<ManifestFile> files;
        final Set<String> roots;

        PackageManifest(
            String packageId,
            String version,
            List<ManifestFile> files,
            Set<String> roots) {
            this.packageId = packageId;
            this.version = version;
            this.files = files;
            this.roots = roots;
        }
    }
}

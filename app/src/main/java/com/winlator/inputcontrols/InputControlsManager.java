package com.winlator.inputcontrols;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.JsonReader;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;

public class InputControlsManager {
    private static final String TAG = "InputControlsManager";
    private static final int LEGACY_GUNDAM_PROFILE_ID = 9053;
    private static final String LEGACY_GUNDAM_PROFILE_SHA256 =
        "e6359997772c7d79fa2dfe84e7bbc9d48042d6aec6b5d5e53e90ebafc4f156f1";
    private static final int LEGACY_BATTLE_GEAR_PROFILE_ID = 9008;
    private static final String LEGACY_BATTLE_GEAR_PROFILE_SHA256 =
        "52e6bdb110102c9f4c7d168835c58bc9033381da3b4793c484795b4494df57a6";
    private final Context context;
    private ArrayList<ControlsProfile> profiles;
    private int maxProfileId;
    private boolean profilesLoaded = false;

    public InputControlsManager(Context context) {
        this.context = context;
    }

    public static File getProfilesDir(Context context) {
        File profilesDir = new File(context.getFilesDir(), "profiles");
        if (!profilesDir.isDirectory()) profilesDir.mkdir();
        return profilesDir;
    }

    public ArrayList<ControlsProfile> getProfiles() {
        return getProfiles(false);
    }

    public ArrayList<ControlsProfile> getProfiles(boolean ignoreTemplates) {
        if (!profilesLoaded) loadProfiles(false);
        if (!ignoreTemplates) return profiles;

        ArrayList<ControlsProfile> visibleProfiles = new ArrayList<>();
        for (ControlsProfile profile : profiles) {
            if (!profile.isTemplate()) visibleProfiles.add(profile);
        }
        return visibleProfiles;
    }

    private void copyAssetProfilesIfNeeded() {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        int newVersion = AppUtils.getVersionCode(context);
        int oldVersion = preferences.getInt("inputcontrols_app_version", 0);
        boolean appWasUpdated = oldVersion != newVersion;

        File[] files = profilesDir.listFiles();
        if (files == null) return;

        try {
            AssetManager assetManager = context.getAssets();
            String[] assetFiles = assetManager.list("inputcontrols/profiles");
            if (assetFiles == null || assetFiles.length == 0)
                throw new IOException("Bundled control-profile list is unavailable.");
            for (String assetFile : assetFiles) {
                String assetPath = "inputcontrols/profiles/"+assetFile;
                ControlsProfile originProfile = loadProfile(context, assetManager.open(assetPath));
                if (!isValidProfile(originProfile))
                    throw new IOException("Bundled control profile is invalid: "+assetFile);

                File targetFile = null;
                boolean profileIdInUse = false;
                for (File file : files) {
                    if (!file.isFile() || !file.getName().endsWith(".icp"))
                        continue;
                    ControlsProfile targetProfile = loadProfile(context, file);
                    if (!isValidProfile(targetProfile) || originProfile.id != targetProfile.id)
                        continue;
                    profileIdInUse = true;
                    if (originProfile.getName().equals(targetProfile.getName())) {
                        targetFile = file;
                        break;
                    }
                }

                if (targetFile != null) {
                    // IDs 9000+ are TeknoParrot's editable built-ins. Preserve
                    // arbitrary player edits, but migrate byte-identical known
                    // old bundled layouts whose bindings were incomplete. This
                    // also works between private APKs that intentionally retain
                    // the same versionCode.
                    if (shouldRefreshBundledProfile(
                            targetFile, originProfile.id, appWasUpdated)) {
                        if (!FileUtils.copyAssetAtomic(context, assetPath, targetFile))
                            throw new IOException("Could not update bundled control profile: "+assetFile);
                        verifyCopiedProfile(targetFile, originProfile.id);
                    }
                }
                else if (!profileIdInUse) {
                    // App upgrades can add new built-in profiles without
                    // replacing or renumbering a user's custom profile.
                    File destination = ControlsProfile.getProfileFile(context, originProfile.id);
                    preserveInvalidProfile(destination);
                    if (!FileUtils.copyAssetAtomic(context, assetPath, destination))
                        throw new IOException("Could not install bundled control profile: "+assetFile);
                    verifyCopiedProfile(destination, originProfile.id);
                }
            }
            if (appWasUpdated && !preferences.edit()
                    .putInt("inputcontrols_app_version", newVersion).commit())
                Log.w(TAG, "Could not persist the bundled control-profile version; synchronization will retry.");
        }
        catch (IOException e) {
            // Leave the old app-version marker intact so a transient storage or
            // asset failure is retried on the next profile load.
            Log.w(TAG, "Could not synchronize bundled control profiles.", e);
        }
    }

    private boolean shouldRefreshBundledProfile(
            File targetFile, int profileId, boolean appWasUpdated) throws IOException {
        if (appWasUpdated && profileId < 9000) return true;
        String targetSha256 = sha256(targetFile);
        return (profileId == LEGACY_GUNDAM_PROFILE_ID &&
                LEGACY_GUNDAM_PROFILE_SHA256.equals(targetSha256)) ||
            (profileId == LEGACY_BATTLE_GEAR_PROFILE_ID &&
                LEGACY_BATTLE_GEAR_PROFILE_SHA256.equals(targetSha256));
    }

    private String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1)
                    digest.update(buffer, 0, count);
            }
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest.digest())
                value.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return value.toString();
        }
        catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable.", error);
        }
    }

    private void verifyCopiedProfile(File destination, int expectedId) throws IOException {
        ControlsProfile copied = loadProfile(context, destination);
        if (!isValidProfile(copied) || copied.id != expectedId)
            throw new IOException("Control-profile copy verification failed: "+destination);
    }

    private void preserveInvalidProfile(File destination) throws IOException {
        if (!destination.isFile() || isValidProfile(loadProfile(context, destination)))
            return;
        File preserved = new File(destination.getAbsolutePath()+".invalid");
        if (preserved.exists()) return;
        byte[] data = FileUtils.read(destination);
        if (data == null || !FileUtils.writeAtomic(preserved, data))
            throw new IOException("Could not preserve invalid control profile: "+destination);
    }

    private static int getProfileIdFromFileName(File file) {
        String name = file.getName();
        if (!name.startsWith("controls-") || !name.endsWith(".icp")) return 0;
        try {
            return Integer.parseInt(name.substring(9, name.length()-4));
        }
        catch (NumberFormatException error) {
            return 0;
        }
    }

    private static boolean isValidProfile(ControlsProfile profile) {
        return profile != null && profile.id > 0 && profile.getName() != null &&
            !profile.getName().trim().isEmpty();
    }

    public void loadProfiles(boolean ignoreTemplates) {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        copyAssetProfilesIfNeeded();

        ArrayList<ControlsProfile> profiles = new ArrayList<>();
        File[] files = profilesDir.listFiles();
        if (files != null) {
            for (File file : files) {
                // Atomic writers deliberately leave a failed temporary file
                // for diagnosis and the next retry. It must never appear as a
                // duplicate selectable profile.
                if (!file.isFile() || !file.getName().endsWith(".icp"))
                    continue;
                // Reserve IDs found in filenames even if their contents are
                // corrupt, so creating a profile cannot overwrite the support
                // evidence that the loader is deliberately ignoring.
                maxProfileId = Math.max(maxProfileId, getProfileIdFromFileName(file));
                ControlsProfile profile = loadProfile(context, file);
                if (!isValidProfile(profile)) {
                    // A partially written or user-supplied profile must not
                    // prevent every game and the controls editor from opening.
                    // Keep the file untouched so it can be repaired or
                    // inspected; valid bundled profiles are restored by the
                    // synchronization pass above.
                    Log.w(TAG, "Ignoring invalid control profile: "+file);
                    continue;
                }
                profiles.add(profile);
                maxProfileId = Math.max(maxProfileId, profile.id);
            }
        }

        Collections.sort(profiles);
        this.profiles = profiles;
        profilesLoaded = true;
    }

    public ControlsProfile createProfile(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        if (!profilesLoaded) loadProfiles(false);
        int newId = maxProfileId + 1;
        ControlsProfile profile = new ControlsProfile(context, newId);
        profile.setName(name.trim());
        if (!profile.save()) return null;
        maxProfileId = newId;
        profiles.add(profile);
        Collections.sort(profiles);
        return profile;
    }

    public ControlsProfile duplicateProfile(ControlsProfile source) {
        if (source == null) return null;
        if (!profilesLoaded) loadProfiles(false);
        String newName;
        for (int i = 1;;i++) {
            newName = source.getName() + " ("+i+")";
            boolean found = false;
            for (ControlsProfile profile : profiles) {
                if (profile.getName().equals(newName)) {
                    found = true;
                    break;
                }
            }
            if (!found) break;
        }

        int newId = maxProfileId + 1;
        File newFile = ControlsProfile.getProfileFile(context, newId);

        try {
            String sourceData = FileUtils.readString(
                ControlsProfile.getProfileFile(context, source.id));
            if (sourceData == null) return null;
            JSONObject data = new JSONObject(sourceData);
            data.put("id", newId);
            data.put("name", newName);
            if (data.has("template")) data.remove("template");
            if (!FileUtils.writeStringAtomic(newFile, data.toString())) return null;
        }
        catch (JSONException | RuntimeException error) {
            Log.w(TAG, "Could not duplicate control profile "+source.id+".", error);
            return null;
        }

        ControlsProfile profile = loadProfile(context, newFile);
        if (!isValidProfile(profile)) return null;
        maxProfileId = newId;
        profiles.add(profile);
        Collections.sort(profiles);
        return profile;
    }

    public void removeProfile(ControlsProfile profile) {
        File file = ControlsProfile.getProfileFile(context, profile.id);
        if (file.isFile() && file.delete()) profiles.remove(profile);
    }

    public ControlsProfile importProfile(JSONObject data) {
        try {
            if (data == null || !data.has("id") || !data.has("name")) return null;
            if (!profilesLoaded) loadProfiles(false);
            String importedName = data.optString("name", "").trim();
            if (importedName.isEmpty()) return null;

            int foundIndex = -1;
            for (int i = 0; i < profiles.size(); i++) {
                if (importedName.equals(profiles.get(i).getName())) {
                    foundIndex = i;
                    break;
                }
            }

            int newId = foundIndex >= 0
                ? profiles.get(foundIndex).id
                : maxProfileId + 1;
            File newFile = ControlsProfile.getProfileFile(context, newId);
            JSONObject persistedData = new JSONObject(data.toString());
            persistedData.put("id", newId);
            persistedData.put("name", importedName);
            if (!FileUtils.writeStringAtomic(newFile, persistedData.toString())) return null;
            ControlsProfile newProfile = loadProfile(context, newFile);
            if (!isValidProfile(newProfile)) return null;

            if (foundIndex != -1) {
                profiles.set(foundIndex, newProfile);
            }
            else {
                maxProfileId = newId;
                profiles.add(newProfile);
            }
            Collections.sort(profiles);
            return newProfile;
        }
        catch (JSONException | RuntimeException error) {
            Log.w(TAG, "Could not import control profile.", error);
            return null;
        }
    }

    public File exportProfile(ControlsProfile profile) {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File destination = new File(downloadsDir, "Winlator/profiles/"+profile.getName()+".icp");
        FileUtils.copy(ControlsProfile.getProfileFile(context, profile.id), destination);
        MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()}, null, null);
        return destination.isFile() ? destination : null;
    }

    public static ControlsProfile loadProfile(Context context, File file) {
        try {
            return loadProfile(context, new FileInputStream(file));
        }
        catch (FileNotFoundException e) {
            return null;
        }
    }

    public static ControlsProfile loadProfile(Context context, InputStream inStream) {
        try (JsonReader reader = new JsonReader(new InputStreamReader(inStream, StandardCharsets.UTF_8))) {
            int profileId = 0;
            String profileName = null;
            float cursorSpeed = Float.NaN;
            boolean disableMouseInput = false;
            int fieldsRead = 0;
            final byte numFieldsToBreak = 4;

            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();

                if (name.equals("id")) {
                    profileId = reader.nextInt();
                    fieldsRead++;
                }
                else if (name.equals("name")) {
                    profileName = reader.nextString();
                    fieldsRead++;
                }
                else if (name.equals("cursorSpeed")) {
                    cursorSpeed = (float)reader.nextDouble();
                    fieldsRead++;
                }
                else if (name.equals("disableMouseInput")) {
                    disableMouseInput = reader.nextBoolean();
                    fieldsRead++;
                }
                else {
                    if (fieldsRead == numFieldsToBreak) break;
                    reader.skipValue();
                }
            }

            ControlsProfile profile = new ControlsProfile(context, profileId);
            profile.setName(profileName);
            profile.setCursorSpeed(cursorSpeed);
            profile.setDisableMouseInput(disableMouseInput);
            return profile;
        }
        catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public ControlsProfile getProfile(int id) {
        for (ControlsProfile profile : getProfiles()) if (profile.id == id) return profile;
        return null;
    }
}

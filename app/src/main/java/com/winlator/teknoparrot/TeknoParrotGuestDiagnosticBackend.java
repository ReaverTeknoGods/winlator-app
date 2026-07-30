package com.winlator.teknoparrot;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.system.ErrnoException;
import android.system.Os;

import com.winlator.XServerDisplayActivity;
import com.winlator.box64.Box64Preset;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.GraphicsDrivers;
import com.winlator.core.AppUtils;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GPUHelper;
import com.winlator.core.WineInfo;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/**
 * Full-Winlator implementation behind the bridge library's reflective debug
 * hook.  Keeping this class in the app module lets the Binder contract remain
 * usable by the x86_64 service stub without coupling the library to Winlator's
 * container and Box64 implementation.
 */
public final class TeknoParrotGuestDiagnosticBackend {
    private static final Object LOCK = new Object();
    private static final Object PROVISION_LOCK = new Object();
    private static final String ASSET_DIRECTORY = "teknoparrot/";
    private static final String MANAGED_CONTAINER_TEMPLATE = "teknoparrot-x86-v1";
    private static final String MANAGED_CONTAINER_MARKER = "teknoparrotTemplate";
    private static final String MANAGED_CONTAINER_NAME = "TeknoParrot Managed";
    private static final String MANAGED_RUNTIME_ROOT = "E:\\TeknoParrotRuntime";
    private static final String[] MANAGED_RUNTIME_FILES = {
        "bngrw.dll",
        "iDmacDrv32.dll",
        "OpenParrot.dll",
        "OpenParrotKonamiLoader.exe",
        "OpenParrotLoader.exe"
    };
    private static final String[] MANAGED_RUNTIME64_FILES = {
        "iDmacDrv64.dll",
        "OpenParrot64.dll",
        "OpenParrotLoader64.exe"
    };
    private static final String[] MANAGED_ELFLDR2_FILES = {
        "elfloader.exe",
        "TeknoParrot.dll",
        "msys-2.0.dll",
        "msys-gcc_s-1.dll",
        "msys-stdc++-6.dll",
        "hints.dat",
        "env/dev/fd",
        "libs/libGLU.so.1",
        "YACardEmu/YACardEmu.exe",
        "YACardEmu/config.ini",
        "YACardEmu/license.txt",
        "YACardEmu/public/index.html"
    };
    private static final String[] MANAGED_CXBXR_VARIANTS = {
        "cxbxr-export",
        "cxbxr-japan"
    };
    private static final String[] MANAGED_CXBXR_FILES = {
        "cxbxr-ldr.exe",
        "cxbxr-emu.dll",
        "SDL2.dll",
        "glew32.dll",
        "subhook.dll",
        "hlsl/FixedFunctionPixelShader.hlsl",
        "YACardEmu/YACardEmu.exe",
        "YACardEmu/config.ini",
        "YACardEmu/license.txt",
        "YACardEmu/public/index.html",
        "YACardEmu/public/blah.js",
        "TeknoParrot/settings.ini",
        "TeknoParrot/EmuMediaBoard/fpr21042_m29w160et.bin",
        "TeknoParrot/EmuMediaBoard/Chihiro/ic10_g24lc64.bin",
        "TeknoParrot/EmuMediaBoard/Chihiro/pc20_g24lc64.bin",
        "TeknoParrot/EmuMediaBoard/Chihiro/ic11_24lc024.bin"
    };
    private static final String WINDOWS_TOOLS_DIRECTORY = "C:\\teknoparrot-service";
    private static final String[] ASSET_NAMES = {
        "android-winlator-service-bridge-diagnostics.bat",
        "pipehelper.exe",
        "pipehelper32.exe",
        "bridgeguest64.exe",
        "bridgeguest32.exe"
    };
    private static final String[] WINDOWS_NAMES = {
        "android-winlator-service-bridge-diagnostics.bat",
        "pipehelper64.exe",
        "pipehelper32.exe",
        "bridgeguest64.exe",
        "bridgeguest32.exe"
    };

    private static XEnvironment environment;
    private static String sessionId = "";
    private static String state = "idle";
    private static int exitStatus = Integer.MIN_VALUE;
    private static long startedNanos;

    private TeknoParrotGuestDiagnosticBackend() {
    }

    /** Ensures all Winlator-owned prerequisites for a TPUI launch. */
    public static String ensureEnvironment(Context context, int preferredContainerId)
        throws IOException {
        if (preferredContainerId <= 0)
            throw new IllegalArgumentException("A positive preferred container id is required.");

        // Android blocks background services from surfacing permission UI.
        // TPUI's user-initiated launch trampoline opens the exported,
        // signature-protected provisioning Activity before calling us.
        if (!hasSharedStoragePermission(context))
            return environmentStatus("permission-required", null);

        synchronized (PROVISION_LOCK) {
            RootFSInstaller.installIfNeededBlocking(context);
            RootFS rootFS = RootFS.find(context);
            ContainerManager manager = new ContainerManager(context);
            Container container = findManagedContainer(manager, preferredContainerId);
            if (container == null)
                container = createManagedContainer(manager);
            if (container == null)
                throw new IOException("Winlator could not create the managed TeknoParrot container.");

            configureManagedContainer(context, container);
            if (!isContainerActive(rootFS, container.id))
                manager.activateContainer(container);
            if (!isManagedBaseRuntimePresent()) {
                // TPUI installs content-addressed runtime archives through the
                // signature-protected bridge. The companion never embeds them.
                return environmentStatus("runtime-required", container);
            }
            return environmentStatus("ready", container);
        }
    }

    private static boolean hasSharedStoragePermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                   PackageManager.PERMISSION_GRANTED &&
               context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                   PackageManager.PERMISSION_GRANTED;
    }

    private static Container findManagedContainer(
        ContainerManager manager,
        int preferredContainerId) {
        for (Container candidate : manager.getContainers()) {
            if (MANAGED_CONTAINER_TEMPLATE.equals(
                    candidate.getExtra(MANAGED_CONTAINER_MARKER)))
                return candidate;
        }

        Container preferred = manager.getContainerById(preferredContainerId);
        if (preferred != null &&
            (MANAGED_CONTAINER_NAME.equals(preferred.getName()) ||
             "TeknoParrot-Diagnostics".equals(preferred.getName())))
            return preferred;
        return null;
    }

    private static Container createManagedContainer(ContainerManager manager) throws IOException {
        try {
            EnvVars envVars = new EnvVars(Container.DEFAULT_ENV_VARS);
            envVars.put("WINEESYNC", "1");
            JSONObject data = new JSONObject();
            data.put("name", MANAGED_CONTAINER_NAME);
            data.put("screenSize", Container.DEFAULT_SCREEN_SIZE);
            data.put("envVars", envVars.toString());
            data.put("cpuList", Container.getFallbackCPUList());
            data.put("cpuListWoW64", Container.getFallbackCPUList());
            data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
            data.put("drives", Container.DEFAULT_DRIVES);
            data.put("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL);
            // Start the managed container with a compatibility-oriented preset.
            // Prepared OpenParrot launches select their title-specific runtime
            // policy when the game Activity starts.
            data.put("box64Preset", Box64Preset.CONSERVATIVE);
            return manager.createContainer(data);
        }
        catch (JSONException error) {
            throw new IOException("Could not describe the managed TeknoParrot container.", error);
        }
    }

    private static void configureManagedContainer(Context context, Container container) {
        EnvVars envVars = new EnvVars(container.getEnvVars());
        envVars.put("WINEESYNC", "1");
        container.setName(MANAGED_CONTAINER_NAME);
        container.setScreenSize(Container.DEFAULT_SCREEN_SIZE);
        String vulkanDriver = GPUHelper.getAdrenoModelId(context) > 0
            ? GraphicsDrivers.TURNIP
            : GraphicsDrivers.VORTEK;
        // Gladio renders many D3D games well but produced a black window for
        // TGM3's native OpenGL renderer. Zink is compatible with both paths.
        container.setGraphicsDriver(vulkanDriver + "," + GraphicsDrivers.ZINK);
        container.setEnvVars(envVars.toString());
        container.setCPUList(Container.getFallbackCPUList());
        container.setCPUListWoW64(Container.getFallbackCPUList());
        container.setDrives(Container.DEFAULT_DRIVES);
        container.setStartupSelection(Container.STARTUP_SELECTION_ESSENTIAL);
        container.setBox64Preset(Box64Preset.CONSERVATIVE);
        container.putExtra(MANAGED_CONTAINER_MARKER, MANAGED_CONTAINER_TEMPLATE);
        container.putExtra("teknoparrotEnvironmentVersion", 4);
        container.saveData();
    }

    private static boolean isContainerActive(RootFS rootFS, int containerId) {
        File activeHome = new File(rootFS.getRootDir(), RootFS.HOME_PATH);
        try {
            File resolved = new File(activeHome.getParentFile(), Os.readlink(activeHome.getPath()))
                .getCanonicalFile();
            File expected = new File(
                activeHome.getParentFile(), RootFS.USER + "-" + containerId).getCanonicalFile();
            return resolved.equals(expected);
        }
        catch (ErrnoException | IOException error) {
            return false;
        }
    }

    private static boolean isManagedElfLdr2RuntimePresent() {
        File runtimeDirectory = new File(
            AppUtils.INTERNAL_STORAGE, "TeknoParrotRuntime/ElfLdr2");
        for (String name : MANAGED_ELFLDR2_FILES) {
            File file = new File(runtimeDirectory, name);
            if (!file.isFile() || file.length() == 0)
                return false;
        }
        return true;
    }

    private static boolean isManagedCxbxrRuntimePresent() {
        File runtimeDirectory = new File(
            AppUtils.INTERNAL_STORAGE, "TeknoParrotRuntime");
        for (String variant : MANAGED_CXBXR_VARIANTS) {
            File variantDirectory = new File(runtimeDirectory, variant);
            for (String name : MANAGED_CXBXR_FILES) {
                File file = new File(variantDirectory, name);
                if (!file.isFile() || file.length() == 0)
                    return false;
            }
        }
        return true;
    }

    private static boolean isManagedBaseRuntimePresent() {
        File openParrotDirectory = new File(
            AppUtils.INTERNAL_STORAGE, "TeknoParrotRuntime/OpenParrotWin32");
        for (String name : MANAGED_RUNTIME_FILES) {
            File file = new File(openParrotDirectory, name);
            if (!file.isFile() || file.length() == 0)
                return false;
        }
        return true;
    }

    private static String environmentStatus(String state, Container container) throws IOException {
        try {
            JSONObject result = new JSONObject();
            result.put("schemaVersion", 1);
            result.put("state", state);
            if (container != null) {
                result.put("containerId", container.id);
                result.put("containerTemplate", MANAGED_CONTAINER_TEMPLATE);
                result.put("runtimeRoot", MANAGED_RUNTIME_ROOT);
                result.put("cxbxrAvailable", isManagedCxbxrRuntimePresent());
            }
            return result.toString();
        }
        catch (JSONException error) {
            throw new IOException("Could not encode Winlator's managed environment status.", error);
        }
    }

    public static String launch(
        Context context,
        String requestedSessionId,
        int containerId,
        int port,
        String pagePath) throws IOException {
        return launchInternal(
            context, requestedSessionId, containerId, port, pagePath,
            null, null, null);
    }

    public static String launchAuthenticated(
        Context context,
        String requestedSessionId,
        int containerId,
        int port,
        String pagePath,
        String tokenHex,
        String pipeName64,
        String pipeName32) throws IOException {
        BridgeProtocol.hexToBytes(tokenHex, 32);
        validatePipeName(pipeName64);
        validatePipeName(pipeName32);
        if (pipeName64.equals(pipeName32))
            throw new IllegalArgumentException("The diagnostic pipe names must be distinct.");
        return launchInternal(
            context, requestedSessionId, containerId, port, pagePath,
            tokenHex, pipeName64, pipeName32);
    }

    private static String launchInternal(
        Context context,
        String requestedSessionId,
        int containerId,
        int port,
        String pagePath,
        String tokenHex,
        String pipeName64,
        String pipeName32) throws IOException {
        if (!BuildConfig.DEBUG)
            throw new SecurityException("Guest diagnostics are available only in debug builds.");
        if (requestedSessionId == null || !requestedSessionId.matches("[0-9a-fA-F]{32}"))
            throw new IllegalArgumentException("A valid bridge session is required.");
        if (containerId <= 0)
            throw new IllegalArgumentException("A positive Winlator container id is required.");
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("A valid loopback port is required.");

        synchronized (LOCK) {
            stopLocked(null, false);

            File pageFile = validatePageFile(context, pagePath);
            RootFS rootFS = RootFS.find(context);
            ContainerManager manager = new ContainerManager(context);
            Container container = manager.getContainerById(containerId);
            if (container == null)
                throw new IllegalArgumentException("Winlator container " + containerId + " was not found.");
            validateActiveContainer(rootFS, containerId);
            File toolsDirectory = stageFixtures(context, container);
            File exposedPage = exposePage(toolsDirectory, pageFile, requestedSessionId);

            WineInfo wineInfo = WineInfo.fromIdentifier(context, container.getWineVersion());
            if (wineInfo != WineInfo.MAIN_WINE_INFO)
                rootFS.setWinePath(wineInfo.path);

            EnvVars envVars = new EnvVars(container.getEnvVars());
            envVars.put("WINEPREFIX", rootFS.getRootDir().getPath() + RootFS.WINEPREFIX);
            envVars.put("WINEDEBUG", "-all");
            envVars.put("WINEESYNC", "0");

            String windowsPagePath = WINDOWS_TOOLS_DIRECTORY + "\\" + exposedPage.getName();
            String batchPath = WINDOWS_TOOLS_DIRECTORY +
                "\\android-winlator-service-bridge-diagnostics.bat";
            // These app-private paths are deliberately space-free. Winlator's
            // ProcessHelper preserves quote characters in argv for Wine, so
            // adding shell-style quotes makes cmd.exe treat them as literal.
            String command = "wine cmd /c " + batchPath + " " + port + " " + windowsPagePath;
            if (tokenHex != null) {
                command += " " + requestedSessionId + " " + tokenHex +
                    " " + pipeName64 + " " + pipeName32;
            }

            GuestProgramLauncherComponent launcher = new GuestProgramLauncherComponent();
            launcher.setGuestExecutable(command);
            launcher.setEnvVars(envVars);
            launcher.setBox64Preset(container.getBox64Preset());

            XEnvironment nextEnvironment = new XEnvironment(context, rootFS);
            nextEnvironment.addComponent(launcher);
            environment = nextEnvironment;
            sessionId = requestedSessionId;
            state = "starting";
            exitStatus = Integer.MIN_VALUE;
            startedNanos = System.nanoTime();

            launcher.setTerminationCallback(status -> {
                synchronized (LOCK) {
                    if (requestedSessionId.equals(sessionId)) {
                        exitStatus = status;
                        state = "exited";
                        environment = null;
                    }
                }
            });

            // XEnvironment.startEnvironmentComponents() clears Winlator's
            // shared tmp directory.  A diagnostic must not mutate unrelated
            // runtime state, so start only the single registered component.
            for (EnvironmentComponent component : nextEnvironment)
                component.start();
            if (state.equals("starting"))
                state = "running";
            return getStatusLocked(requestedSessionId);
        }
    }

    public static String getStatus(String requestedSessionId) {
        synchronized (LOCK) {
            return getStatusLocked(requestedSessionId);
        }
    }

    /**
     * Stages the signed bridge helper and exposes the Binder-backed TPJ1 page
     * inside the selected Wine prefix.  No guest process is started here;
     * XServerDisplayActivity starts the helper before the prepared game.
     */
    public static String prepareProduction(
            Context context,
            int containerId,
            String pagePath,
            String requestedSessionId) throws IOException {
        if (context == null || requestedSessionId == null ||
            !requestedSessionId.matches("[0-9a-fA-F]{32}"))
            throw new IllegalArgumentException("A valid production bridge session is required.");
        if (containerId <= 0)
            throw new IllegalArgumentException("A positive Winlator container id is required.");

        synchronized (LOCK) {
            File pageFile = validatePageFile(context, pagePath);
            Container container = new ContainerManager(context).getContainerById(containerId);
            if (container == null)
                throw new IllegalArgumentException(
                    "Winlator container " + containerId + " was not found.");
            File toolsDirectory = stageFixtures(context, container);
            File exposedPage = exposePage(toolsDirectory, pageFile, requestedSessionId);
            return WINDOWS_TOOLS_DIRECTORY + "\\" + exposedPage.getName();
        }
    }

    public static String stop(String requestedSessionId) {
        boolean activityStopping =
            XServerDisplayActivity.stopTeknoParrotSession(requestedSessionId);
        synchronized (LOCK) {
            stopLocked(requestedSessionId, true);
            String status = getStatusLocked(requestedSessionId);
            return activityStopping ? "state=stopping;activity=prepared" : status;
        }
    }

    private static String getStatusLocked(String requestedSessionId) {
        if (requestedSessionId == null || !requestedSessionId.equals(sessionId))
            return "state=missing";
        long elapsedMillis = startedNanos == 0 ? 0 :
            (System.nanoTime() - startedNanos) / 1_000_000L;
        String exit = exitStatus == Integer.MIN_VALUE ? "pending" : String.valueOf(exitStatus);
        return "state=" + state + ";session=" + sessionId + ";exit=" + exit +
            ";elapsedMs=" + elapsedMillis;
    }

    private static void stopLocked(String requestedSessionId, boolean retainIdentity) {
        if (!sessionId.isEmpty() && requestedSessionId != null && !requestedSessionId.equals(sessionId))
            return;
        if (environment != null) {
            environment.stopEnvironmentComponents();
            environment = null;
        }
        if (!sessionId.isEmpty())
            state = "stopped";
        if (!retainIdentity) {
            sessionId = "";
            state = "idle";
            exitStatus = Integer.MIN_VALUE;
            startedNanos = 0;
        }
    }

    private static File validatePageFile(Context context, String pagePath) throws IOException {
        if (pagePath == null || pagePath.isEmpty())
            throw new IllegalArgumentException("A shared-page path is required.");
        File pageFile = new File(pagePath).getCanonicalFile();
        String dataRoot = context.getDataDir().getCanonicalPath() + File.separator;
        if (!pageFile.getPath().startsWith(dataRoot) || !pageFile.isFile() || pageFile.length() < 4096)
            throw new SecurityException("The shared page is not a valid app-private TPJ1 file.");
        return pageFile;
    }

    private static void validateActiveContainer(RootFS rootFS, int containerId) throws IOException {
        File activeHome = new File(rootFS.getRootDir(), RootFS.HOME_PATH);
        String expectedRelative = RootFS.USER + "-" + containerId;
        try {
            String target = Os.readlink(activeHome.getPath());
            File resolved = new File(activeHome.getParentFile(), target).getCanonicalFile();
            File expected = new File(activeHome.getParentFile(), expectedRelative).getCanonicalFile();
            if (!resolved.equals(expected)) {
                throw new IllegalStateException(
                    "Container " + containerId + " is not active; open it in Winlator once before the diagnostic.");
            }
        }
        catch (ErrnoException error) {
            throw new IOException("Could not validate the active Winlator container.", error);
        }
    }

    private static File stageFixtures(Context context, Container container) throws IOException {
        File toolsDirectory = new File(container.getRootDir(),
            ".wine/drive_c/teknoparrot-service");
        if (!toolsDirectory.isDirectory() && !toolsDirectory.mkdirs())
            throw new IOException("Could not create the private guest tools directory.");

        for (int index = 0; index < ASSET_NAMES.length; index++) {
            File destination = new File(toolsDirectory, WINDOWS_NAMES[index]);
            if (!FileUtils.copyAssetAtomic(
                    context, ASSET_DIRECTORY + ASSET_NAMES[index], destination))
                throw new IOException("Guest fixture staging failed: " + WINDOWS_NAMES[index]);
            if (!destination.isFile() || destination.length() == 0)
                throw new IOException("Guest fixture staging failed: " + WINDOWS_NAMES[index]);
        }
        return toolsDirectory;
    }

    private static File exposePage(File toolsDirectory, File pageFile, String requestedSessionId)
        throws IOException {
        File exposedPage = new File(toolsDirectory,
            "shared-page-" + requestedSessionId.toLowerCase() + ".page");
        try {
            if (exposedPage.exists()) {
                File existingTarget = new File(Os.readlink(exposedPage.getPath())).getCanonicalFile();
                if (!existingTarget.equals(pageFile))
                    throw new IOException("The guest shared-page link targets another session.");
            }
            else {
                // Wine cannot traverse Android's /data/user path through Z: on
                // this device.  A per-session symlink inside drive_c exposes
                // the very same inode without copying or later cleanup.
                Os.symlink(pageFile.getAbsolutePath(), exposedPage.getAbsolutePath());
            }
        }
        catch (ErrnoException error) {
            throw new IOException("Could not expose the Binder shared page to Wine.", error);
        }
        return exposedPage;
    }

    private static void validatePipeName(String value) {
        if (value == null || value.isEmpty() || value.length() > 128 ||
            !value.matches("[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("Invalid diagnostic pipe name.");
    }

    private static String processQuote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}

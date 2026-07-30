package com.winlator;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.alsaserver.ALSAClient;
import com.winlator.box64.Box64Preset;
import com.winlator.container.AudioDrivers;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ActiveWindowsDialog;
import com.winlator.contentdialog.AudioDriverConfigDialog;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.DXVKConfigDialog;
import com.winlator.contentdialog.DebugDialog;
import com.winlator.contentdialog.ScreenEffectDialog;
import com.winlator.contentdialog.TurnipConfigDialog;
import com.winlator.contentdialog.VKD3DConfigDialog;
import com.winlator.contentdialog.VirGLConfigDialog;
import com.winlator.contentdialog.WineD3DConfigDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.KeyValueSet;
import com.winlator.core.LocaleHelper;
import com.winlator.core.NetworkHelper;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.ProcessHelper;
import com.winlator.core.StringUtils;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.Win32AppWorkarounds;
import com.winlator.core.WineInfo;
import com.winlator.core.WineInstaller;
import com.winlator.core.WineRegistryEditor;
import com.winlator.core.WineStartMenuCreator;
import com.winlator.core.WineThemeManager;
import com.winlator.core.WineUtils;
import com.winlator.inputcontrols.Binding;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.math.Mathf;
import com.winlator.renderer.GLRenderer;
import com.winlator.teknoparrot.ForwardedInputActivityBridge;
import com.winlator.teknoparrot.ForwardedInputProtocol;
import com.winlator.teknoparrot.ForwardedInputSessionRegistry;
import com.winlator.teknoparrot.PreparedSessionActivityLauncher;
import com.winlator.teknoparrot.PreparedWindowsLaunchRegistry;
import com.winlator.teknoparrot.TeknoParrotBridgeService;
import com.winlator.widget.FrameRating;
import com.winlator.widget.InputControlsView;
import com.winlator.widget.MagnifierView;
import com.winlator.widget.TouchpadView;
import com.winlator.widget.XServerView;
import com.winlator.winhandler.GamepadHandler;
import com.winlator.winhandler.TaskManagerDialog;
import com.winlator.winhandler.WinHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.ALSAServerComponent;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.xenvironment.components.NetworkInfoUpdateComponent;
import com.winlator.xenvironment.components.PulseAudioComponent;
import com.winlator.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.xenvironment.components.TeknoParrotBridgeLauncherComponent;
import com.winlator.xenvironment.components.TeknoParrotWinePreflightComponent;
import com.winlator.xenvironment.components.VirGLRendererComponent;
import com.winlator.xenvironment.components.VortekRendererComponent;
import com.winlator.xenvironment.components.XServerComponent;
import com.winlator.xserver.Atom;
import com.winlator.xserver.Property;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;

public class XServerDisplayActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    private static final Object TEKNOPARROT_SESSION_LOCK = new Object();
    private static final String TEKNOPARROT_CONTROLS_PROFILE_PREFERENCE =
            "teknoparrot_controls_profile_id";
    private static final int TEKNOPARROT_ARCADE_PROFILE_ID = 9001;
    private static final int TEKNOPARROT_LUIGI_PROFILE_ID = 9035;
    private static final int VIRTUAL_GAMEPAD_PROFILE_ID = 3;
    private static WeakReference<XServerDisplayActivity> teknoParrotSessionActivity =
            new WeakReference<>(null);
    private static final String TAG = "TeknoParrotLaunch";
    private static final String PREPARED_WINDOWS_BOOTSTRAP_ASSET =
        "teknoparrot/windows-path-bootstrap.exe";
    private static final String PREPARED_WINDOWS_BOOTSTRAP_DOS_PATH =
        "C:\\windows\\teknoparrot-path-bootstrap.exe";
    private XServerView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private RootFS rootFS;
    private FrameRating frameRating;
    private Runnable editInputControlsCallback;
    private Shortcut shortcut;
    private String[] graphicsDriver = {GraphicsDrivers.DEFAULT_VULKAN_DRIVER, GraphicsDrivers.DEFAULT_OPENGL_DRIVER};
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private ScreenInfo screenInfo = new ScreenInfo(Container.DEFAULT_SCREEN_SIZE);
    private KeyValueSet[] dxwrapperConfig;
    private KeyValueSet[] graphicsDriverConfig = {new KeyValueSet(), new KeyValueSet()};
    private KeyValueSet audioDriverConfig;
    private String wincomponents;
    private WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private EnvVars overrideEnvVars;
    private ClipboardManager clipboardManager;
    private SharedPreferences preferences;
    private final WinHandler winHandler = new WinHandler(this);
    private float globalCursorSpeed = 1.0f;
    private boolean capturePointerOnExternalMouse = true;
    private MagnifierView magnifierView;
    private DebugDialog debugDialog;
    private int frameRatingWindowId = -1;
    private Win32AppWorkarounds win32AppWorkarounds;
    private String screenEffectProfile;
    private ForwardedInputActivityBridge forwardedInputBridge;
    private final SparseBooleanArray overlayTouchPointers = new SparseBooleanArray();
    private boolean forwardedInputActivityDiagnostic;
    private boolean hasPreparedWindowsLaunch;
    private PreparedWindowsLaunchRegistry.Launch preparedWindowsLaunch;
    private String forwardedSessionId;
    private WifiManager.MulticastLock wmmtTerminalMulticastLock;
    private volatile boolean cxbxrProcessMonitorStarted;
    private volatile boolean preparedGameProcessMonitorStarted;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        forwardedInputActivityDiagnostic = getIntent().getBooleanExtra(
                PreparedSessionActivityLauncher.EXTRA_FORWARDED_INPUT_DIAGNOSTIC, false);
        hasPreparedWindowsLaunch = getIntent().getBooleanExtra(
                PreparedSessionActivityLauncher.EXTRA_PREPARED_WINDOWS_LAUNCH, false);
        forwardedSessionId = getIntent().getStringExtra(
                ForwardedInputSessionRegistry.EXTRA_SESSION_ID);
        if (forwardedSessionId != null) {
            synchronized (TEKNOPARROT_SESSION_LOCK) {
                teknoParrotSessionActivity = new WeakReference<>(this);
            }
        }
        preparedWindowsLaunch = hasPreparedWindowsLaunch
                ? PreparedWindowsLaunchRegistry.find(forwardedSessionId)
                : null;
        forwardedInputBridge = ForwardedInputSessionRegistry.attach(
                this,
                forwardedSessionId);
        if (forwardedInputBridge != null && preparedWindowsLaunch != null)
            forwardedInputBridge.setMapTriggersToExtensionButtons(
                    preparedWindowsLaunch.controlsProfileId == 9033);
        if (hasPreparedWindowsLaunch &&
            (preparedWindowsLaunch == null || forwardedInputBridge == null)) {
            finish();
            return;
        }
        acquirePreparedMulticastLock();
        ProcessHelper.setOutputSuppressed(
            preparedWindowsLaunch != null && !preparedWindowsLaunch.debugLoggingEnabled);
        applyPreparedFrameRateLimit();
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
        setContentView(R.layout.xserver_display_activity);
        if (forwardedInputActivityDiagnostic) {
            scheduleForwardedInputActivityDiagnostic(0);
            return;
        }

        final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useAndroidClipboardOnWine = preferences.getBoolean("use_android_clipboard_on_wine", false);
        clipboardManager = useAndroidClipboardOnWine ? (ClipboardManager)getSystemService(CLIPBOARD_SERVICE) : null;

        drawerLayout = findViewById(R.id.DrawerLayout);
        drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        NavigationView navigationView = findViewById(R.id.NavigationView);
        ProcessHelper.removeAllDebugCallbacks();
        boolean enableLogs = preparedWindowsLaunch != null
            ? preparedWindowsLaunch.debugLoggingEnabled
            : preferences.getBoolean("enable_wine_debug", false) ||
                preferences.getInt("box64_logs", 0) >= 1;
        if (enableLogs) ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
        Menu menu = navigationView.getMenu();
        menu.findItem(R.id.menu_item_logs).setVisible(enableLogs);
        navigationView.setNavigationItemSelectedListener(this);

        rootFS = RootFS.find(this);

        if (!isGenerateWineprefix()) {
            ContainerManager containerManager = new ContainerManager(this);
            container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));
            if (container == null || !validatePreparedWindowsLaunch()) {
                finish();
                return;
            }
            containerManager.activateContainer(container);

            boolean wineprefixNeedsUpdate = container.getExtra("wineprefixNeedsUpdate").equals("t");
            if (wineprefixNeedsUpdate) {
                preloaderDialog.show(R.string.updating_system_files);
                WineUtils.updateWineprefix(this, (status) -> {
                    if (status == 0) {
                        container.putExtra("wineprefixNeedsUpdate", null);
                        container.putExtra("wincomponents", null);
                        container.saveData();
                        AppUtils.restartActivity(this);
                    }
                    else finish();
                });
                return;
            }

            win32AppWorkarounds = new Win32AppWorkarounds(this);

            String wineVersion = container.getWineVersion();
            wineInfo = WineInfo.fromIdentifier(this, wineVersion);

            if (wineInfo != WineInfo.MAIN_WINE_INFO) rootFS.setWinePath(wineInfo.path);

            String shortcutPath = getIntent().getStringExtra("shortcut_path");
            if (shortcutPath != null && !shortcutPath.isEmpty()) shortcut = new Shortcut(container, new File(shortcutPath));

            String graphicsDriver = container.getGraphicsDriver();
            audioDriver = container.getAudioDriver();
            String dxwrapper = container.getDXWrapper();
            wincomponents = container.getWinComponents();
            String dxwrapperConfig = container.getDXWrapperConfig();
            String graphicsDriverConfig = container.getGraphicsDriverConfig();
            audioDriverConfig = new KeyValueSet(container.getAudioDriverConfig());
            screenInfo = new ScreenInfo(container.getScreenSize());

            // A managed TeknoParrot launch carries a per-game render size. Use
            // it for the Wine/X11 desktop as well as teknoparrot.ini; otherwise
            // the container's 1280x720 default silently wins and the game still
            // renders the old pixel count even though the recipe requested a
            // lower resolution for a thermally constrained Android device.
            if (preparedWindowsLaunch != null &&
                preparedWindowsLaunch.resolutionWidth > 0 &&
                preparedWindowsLaunch.resolutionHeight > 0) {
                screenInfo = new ScreenInfo(
                    preparedWindowsLaunch.resolutionWidth + "x" +
                    preparedWindowsLaunch.resolutionHeight);
            }
            else if (isKofXiiiPreparedLaunch()) {
                // KOF XIII's original settings.arc renders at 1360x768. Keep
                // that file and teknoparrot.ini untouched so the working
                // DirectShow/movie path is preserved, but give the X desktop
                // enough room for the complete native window. GLRenderer then
                // scales the whole 1360x768 desktop to the Android surface.
                screenInfo = new ScreenInfo("1360x768");
            }

            if (shortcut != null) {
                graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
                audioDriver = shortcut.getExtra("audioDriver", container.getAudioDriver());
                dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
                wincomponents = shortcut.getExtra("wincomponents", container.getWinComponents());
                dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
                graphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
                audioDriverConfig = new KeyValueSet(shortcut.getExtra("audioDriverConfig", container.getAudioDriverConfig()));
                screenInfo = new ScreenInfo(shortcut.getExtra("screenSize", container.getScreenSize()));

                String dinputMapperType = shortcut.getExtra("dinputMapperType");
                if (!dinputMapperType.isEmpty()) winHandler.gamepadHandler.setDInputMapperType(Byte.parseByte(dinputMapperType));

                win32AppWorkarounds.applyStartupWorkarounds(!shortcut.wmClass.isEmpty() ? shortcut.wmClass : shortcut.path);
            }
            else {
                Intent intent = getIntent();
                if (intent.hasExtra("exec_path")) win32AppWorkarounds.applyStartupWorkarounds(FileUtils.getName(intent.getStringExtra("exec_path")));
                else if (preparedWindowsLaunch != null)
                {
                    win32AppWorkarounds.applyPreparedStartupWorkarounds(
                        preparedWindowsLaunch.executable,
                        preparedWindowsLaunch.arguments);
                    win32AppWorkarounds.applyTeknoParrotCompatibilityPreset(
                        preparedWindowsLaunch.compatibilityPreset);
                    if (!prepareTeknoParrotCompatibilityPayload()) {
                        finish();
                        return;
                    }
                }
            }

            // Luigi's Mansion relies on D3D11 dynamic class linkage. DXVK 2.4
            // rejects those shaders and leaves its otherwise healthy swapchain
            // black. Keep this a per-game override so every other managed
            // launch retains the faster Vulkan path.
            boolean isMarioKartDxLaunch = isMarioKartDxPreparedLaunch();
            boolean isCrazySpeedLaunch = isCrazySpeedPreparedLaunch();
            boolean isKofXiiiLaunch = isKofXiiiPreparedLaunch();
            if (preparedWindowsLaunch != null &&
                ("wined3d-remote-thread".equals(
                    preparedWindowsLaunch.compatibilityPreset) ||
                 "wined3d-parked-entrypoint".equals(
                    preparedWindowsLaunch.compatibilityPreset)))
                dxwrapper = DXWrappers.WINED3D;

            // KOF XIII's failed DirectShow presenter leaves live D3D9
            // resources behind when the game resets its device to continue
            // past the first movie. DXVK reports VK_ERROR_DEVICE_LOST at that
            // exact reset. Keep this comparison title-scoped while the paired
            // movie-interface guard lets the game reach the transition.
            if (isKofXiiiLaunch)
                dxwrapper = DXWrappers.WINED3D;

            // MKDX 1.06 hard-codes its main DXUT renderer to D3D10, but
            // xelib::clDSMovie creates a separate D3D9Ex device for movies.
            // Two DXVK renderer instances in this 32-bit process fail at the
            // first movie transition. Keep D3D10 on stable DXVK 2.3.1 and
            // route D3D9 through WineD3D. A title-scoped attempt to reject the
            // secondary D3D9Ex device did not prevent the late WOW64 floating
            // point failure and also removed the movie picture, so preserve
            // the game's normal movie path here.
            if (isMarioKartDxLaunch) {
                dxwrapper = DXWrappers.DXVK;
                KeyValueSet[] selectedDxwrapperConfig =
                    DXWrappers.parseConfigs(dxwrapper, dxwrapperConfig);
                selectedDxwrapperConfig[0].put(
                    "version", DefaultVersion.INTERMEDIATE_DXVK);
                dxwrapperConfig = selectedDxwrapperConfig[0].toString() + "|" +
                    selectedDxwrapperConfig[1].toString();
                getOverrideEnvVars().put(
                    "WINEDLLOVERRIDES",
                    "d3d9=b;mscoree,mshtml=d");
            }

            // Crazy Speed's 2010 Ogre renderer survives device creation on
            // DXVK 2.4.1 but later dereferences a null D3D9 surface while
            // entering attract. DXVK 2.3.1 is already bundled for legacy
            // renderers, so keep this fallback title-scoped.
            if (isCrazySpeedLaunch) {
                dxwrapper = DXWrappers.DXVK;
                KeyValueSet[] selectedDxwrapperConfig =
                    DXWrappers.parseConfigs(dxwrapper, dxwrapperConfig);
                selectedDxwrapperConfig[0].put(
                    "version", DefaultVersion.INTERMEDIATE_DXVK);
                dxwrapperConfig = selectedDxwrapperConfig[0].toString() + "|" +
                    selectedDxwrapperConfig[1].toString();
            }

            // Homura opens and closes DirectSound/ALSA streams at extreme
            // frequency. HOD3 also transitions from the Chihiro boot sound into
            // Xbox DirectSound streaming and advances farther through its
            // Sofdec/ADX startup when routed through PulseAudio. Other CXBXR
            // titles regress on Pulse, so retain ALSA for them.
            if (preparedWindowsLaunch != null &&
                ("box64-interpreter".equals(
                    preparedWindowsLaunch.compatibilityPreset) ||
                 isPreparedCxbxrPulseAudioTitle()))
                audioDriver = AudioDrivers.PULSEAUDIO;

            this.graphicsDriver = GraphicsDrivers.parseIdentifiers(graphicsDriver);
            this.graphicsDriverConfig = GraphicsDrivers.parseConfigs(graphicsDriver, graphicsDriverConfig);
            this.dxwrapper = DXWrappers.parseIdentifier(dxwrapper);
            this.dxwrapperConfig = DXWrappers.parseConfigs(dxwrapper, dxwrapperConfig);

        }

        preloaderDialog.show(R.string.starting_up);

        inputControlsManager = new InputControlsManager(this);
        xServer = new XServer(this, screenInfo);
        xServer.setWinHandler(winHandler);
        if (preparedWindowsLaunch != null)
            xServer.windowManager.setWindowPositionPolicy(
                this::resolvePreparedWindowPosition);
        final boolean[] flags = {
            false,
            shortcut != null || getIntent().hasExtra("exec_path") || preparedWindowsLaunch != null};
        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {
                if (window.id == frameRatingWindowId) frameRating.update();
            }

            @Override
            public void onMapWindow(Window window) {
                if (BuildConfig.DEBUG && preparedWindowsLaunch != null &&
                    preparedWindowsLaunch.debugLoggingEnabled) {
                    Log.i(TAG, "map id="+window.id+
                        " pid="+window.getProcessId()+
                        " class="+window.getClassName()+
                        " name="+window.getName()+
                        " wow64="+window.isWoW64()+
                        " renderable="+window.isRenderable()+
                        " viewable="+window.attributes.isViewable()+
                        " desktop="+window.isDesktopWindow());
                }
                if (!flags[0] && window.isRenderable() && !window.getClassName().isEmpty()) {
                    // Managed arcade games have dedicated touch/controller
                    // overlays and should never reveal Wine's desktop cursor.
                    xServerView.getRenderer().setCursorVisible(preparedWindowsLaunch == null);
                    preloaderDialog.closeOnUiThread();
                    flags[0] = true;
                }

                if (flags[1] && window.attributes.isViewable() && window.isDesktopWindow()) {
                    window.attributes.setViewable(false);
                    if (window.attributes.isEnabled()) window.disableAllDescendants();
                }

                centerPreparedWindow(window);

                if (win32AppWorkarounds != null) win32AppWorkarounds.applyWindowWorkarounds(window);
                changeFrameRatingVisibility(window, true);
            }

            @Override
            public void onUnmapWindow(Window window) {
                if (BuildConfig.DEBUG && preparedWindowsLaunch != null &&
                    preparedWindowsLaunch.debugLoggingEnabled) {
                    Log.i(TAG, "unmap id="+window.id+
                        " pid="+window.getProcessId()+
                        " class="+window.getClassName()+
                        " name="+window.getName());
                }
                changeFrameRatingVisibility(window, false);
            }
        });

        setupUI();

        Executors.newSingleThreadExecutor().execute(() -> {
            if (!isGenerateWineprefix()) {
                setupWineSystemFiles();
                applyPreparedWineRegistryWorkarounds();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
            }
            setupXEnvironment();
        });
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (editInputControlsCallback != null) {
                editInputControlsCallback.run();
                editInputControlsCallback = null;
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (forwardedInputBridge != null)
            forwardedInputBridge.onWindowFocusChanged(hasFocus);

        if (hasFocus && !forwardedInputActivityDiagnostic) {
            // Permission sheets, controller editors, and the Android task
            // switcher can make gesture/navigation bars visible again. Restore
            // immersive mode whenever the managed game regains focus.
            AppUtils.hideSystemUI(this);
            if (capturePointerOnExternalMouse) touchpadView.requestPointerCapture();

            if (winHandler != null && clipboardManager != null && clipboardManager.hasPrimaryClip()) {
                ClipData primaryClip = clipboardManager.getPrimaryClip();
                if (primaryClip != null && primaryClip.getItemCount() > 0) {
                    winHandler.setClipboardData(primaryClip.getItemAt(0).getText().toString());
                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (forwardedInputBridge != null) forwardedInputBridge.onResume();
        if (environment != null) {
            xServerView.onResume();
            environment.onResume();
        }
    }

    @Override
    public void onPause() {
        if (forwardedInputBridge != null) forwardedInputBridge.onPause();
        super.onPause();
        if (environment != null && !isInPictureInPictureMode()) {
            environment.onPause();
            xServerView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        prepareManagedSessionGraphicsTeardown();
        synchronized (TEKNOPARROT_SESSION_LOCK) {
            if (teknoParrotSessionActivity.get() == this)
                teknoParrotSessionActivity.clear();
        }
        if (forwardedInputBridge != null) {
            forwardedInputBridge.close();
            forwardedInputBridge = null;
        }
        winHandler.stop();
        if (environment != null) environment.stopEnvironmentComponents();
        if (hasPreparedWindowsLaunch)
            ProcessHelper.killGuestProcesses();
        if (hasPreparedWindowsLaunch && !isChangingConfigurations())
            TeknoParrotBridgeService.releasePreparedSession(this, forwardedSessionId);
        releasePreparedMulticastLock();
        ProcessHelper.setOutputSuppressed(false);
        super.onDestroy();
    }

    /**
     * WMMT's in-process terminal emulator announces itself to the game over
     * 225.0.0.1:50765. Android filters Wi-Fi multicast unless the owning app
     * holds a MulticastLock, so keep one only for the terminal-enabled recipe
     * and release it with the prepared game Activity.
     */
    private void acquirePreparedMulticastLock() {
        if (preparedWindowsLaunch == null ||
            !"wmmt-terminal".equals(preparedWindowsLaunch.compatibilityPreset))
            return;

        WifiManager wifiManager =
            (WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null)
            return;

        try {
            wmmtTerminalMulticastLock = wifiManager.createMulticastLock(
                "TeknoParrotWmmtTerminal");
            wmmtTerminalMulticastLock.setReferenceCounted(false);
            wmmtTerminalMulticastLock.acquire();
            if (preparedWindowsLaunch.debugLoggingEnabled)
                Log.i(TAG, "Acquired WMMT terminal multicast lock.");
        }
        catch (RuntimeException error) {
            wmmtTerminalMulticastLock = null;
            Log.e(TAG, "Could not acquire the WMMT terminal multicast lock.", error);
        }
    }

    private void releasePreparedMulticastLock() {
        if (wmmtTerminalMulticastLock == null)
            return;
        try {
            if (wmmtTerminalMulticastLock.isHeld())
                wmmtTerminalMulticastLock.release();
        }
        catch (RuntimeException error) {
            Log.w(TAG, "Could not release the WMMT terminal multicast lock.", error);
        }
        finally {
            wmmtTerminalMulticastLock = null;
        }
    }

    /**
     * Ends only the prepared TeknoParrot Activity that owns the supplied
     * authenticated session. The bridge backend calls this in-process after
     * TPUI requests Force Quit; ordinary Winlator containers are unaffected.
     */
    public static boolean stopTeknoParrotSession(String requestedSessionId) {
        final XServerDisplayActivity activity;
        synchronized (TEKNOPARROT_SESSION_LOCK) {
            activity = teknoParrotSessionActivity.get();
            if (activity == null || requestedSessionId == null ||
                    !requestedSessionId.equals(activity.forwardedSessionId))
                return false;
        }
        activity.runOnUiThread(() -> {
            activity.prepareManagedSessionGraphicsTeardown();
            if (!activity.isFinishing())
                activity.finishAndRemoveTask();
        });
        return true;
    }

    private void prepareManagedSessionGraphicsTeardown() {
        if (hasPreparedWindowsLaunch && xServerView != null)
            xServerView.setPreserveEGLContextOnPause(false);
    }

    private short[] resolvePreparedWindowPosition(
            Window window, short width, short height) {
        Window parent = window.getParent();
        if (preparedWindowsLaunch == null ||
                !"centered".equals(preparedWindowsLaunch.displayMode) ||
                window == xServer.windowManager.rootWindow ||
                (parent != xServer.windowManager.rootWindow &&
                    (parent == null || !parent.isDesktopWindow())) ||
                !window.attributes.isViewable() || window.isDesktopWindow() ||
                width <= 1 || height <= 1)
            return null;

        short x = (short)Math.max(0, (screenInfo.width - width) / 2);
        short y = (short)Math.max(0, (screenInfo.height - height) / 2);
        return new short[]{x, y};
    }

    private void centerPreparedWindow(Window window) {
        // Wine commonly represents a decorated Win32 window as an undecorated
        // client nested inside a title/border wrapper.  The client is the last
        // window mapped, so centering only direct root children leaves its
        // wrapper at Wine's requested (0,0) position.  Always move the actual
        // top-level X11 window instead.  Winlator's hidden explorer.exe
        // desktop may sit between that wrapper and the X root, so it is a
        // valid positioning container but must never be the window we move.
        Window topLevelWindow = window;
        while (topLevelWindow != null &&
                topLevelWindow.getParent() != null &&
                topLevelWindow.getParent() != xServer.windowManager.rootWindow &&
                !topLevelWindow.getParent().isDesktopWindow()) {
            topLevelWindow = topLevelWindow.getParent();
        }

        if (topLevelWindow == null)
            return;

        short[] position = resolvePreparedWindowPosition(
            topLevelWindow, topLevelWindow.getWidth(), topLevelWindow.getHeight());
        if (position != null)
            xServer.windowManager.moveWindow(
                topLevelWindow, position[0], position[1]);
    }

    @Override
    public void onBackPressed() {
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            else drawerLayout.closeDrawers();
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        final GLRenderer renderer = xServerView.getRenderer();
        switch (item.getItemId()) {
            case R.id.menu_item_keyboard:
                AppUtils.showKeyboard(this);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_input_controls:
                showInputControlsDialog();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_toggle_fullscreen:
                renderer.toggleFullscreen();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_task_manager:
                (new TaskManagerDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_active_windows:
                (new ActiveWindowsDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_magnifier:
                if (magnifierView == null) {
                    final FrameLayout container = findViewById(R.id.FLXServerDisplay);
                    magnifierView = new MagnifierView(this);
                    magnifierView.setZoomButtonCallback((value) -> {
                        renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                        magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    });
                    magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    magnifierView.setHideButtonCallback(() -> {
                        container.removeView(magnifierView);
                        magnifierView = null;
                    });
                    container.addView(magnifierView);
                }
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_screen_effect:
                (new ScreenEffectDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_pip_mode:
                PictureInPictureParams pipParams = (new PictureInPictureParams.Builder())
                    .setAspectRatio(screenInfo.aspectRatio())
                    .build();
                enterPictureInPictureMode(pipParams);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_logs:
                debugDialog.show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_touchpad_help:
                showTouchpadHelpDialog();
                break;
            case R.id.menu_item_exit:
                exit();
                break;
        }
        return true;
    }

    public SharedPreferences getPreferences() {
        return preferences;
    }

    private void exit() {
        prepareManagedSessionGraphicsTeardown();
        winHandler.stop();
        if (environment != null) environment.stopEnvironmentComponents();

        // A managed TeknoParrot launch owns its own disposable task.  The
        // guest termination callback can race the bridge's Force Quit path;
        // restarting Winlator here would expose its container UI on top of
        // TeknoParrotUI.  Ordinary Winlator launches retain their original
        // restart behaviour below.
        if (hasPreparedWindowsLaunch) {
            if (!isFinishing()) finishAndRemoveTask();
            return;
        }

        Intent intent = getIntent();
        if (intent.hasExtra("exec_path")) {
            AppUtils.RestartApplicationOptions options = new AppUtils.RestartApplicationOptions();
            options.containerId = container.id;
            options.startPath = FileUtils.getDirname(intent.getStringExtra("exec_path"));
            AppUtils.restartApplication(this, options);
        }
        else AppUtils.restartApplication(this);
    }

    /**
     * CXBXR's Chihiro quick reboot starts a detached replacement process and
     * then exits the original loader. The desktop launcher explicitly follows
     * that child, while Winlator normally treats the first Box64/Wine process
     * exit as the end of the whole session. Keep only CXBXR sessions alive
     * through the bounded handoff and close them once the replacement emulator
     * is genuinely gone.
     */
    private void handleGuestProgramTermination(int status) {
        if (!hasPreparedWindowsLaunch) {
            exit();
            return;
        }
        if (!isPreparedCxbxrLaunch()) {
            monitorPreparedGameProcess();
            return;
        }
        if (cxbxrProcessMonitorStarted) return;
        cxbxrProcessMonitorStarted = true;
        Thread processMonitor = new Thread(() -> {
            final long handoffDeadline =
                android.os.SystemClock.elapsedRealtime() + 12_000L;
            boolean observedReplacement = false;
            long absentSince = -1L;

            while (!isFinishing() && !isDestroyed()) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (ProcessHelper.hasLiveGuestProcessName("cxbxr")) {
                    observedReplacement = true;
                    absentSince = -1L;
                }
                else if (!observedReplacement) {
                    if (now >= handoffDeadline) break;
                }
                else {
                    if (absentSince < 0L) absentSince = now;
                    // CxbxrExec allows up to five seconds for a quick-reboot
                    // successor. A seven-second quiet period covers that
                    // transition without retaining a completed game forever.
                    if (now - absentSince >= 7_000L) break;
                }
                android.os.SystemClock.sleep(250L);
            }

            if (!isFinishing() && !isDestroyed())
                runOnUiThread(this::exit);
        }, "CxbxrProcessMonitor");
        processMonitor.start();
    }

    /**
     * OpenParrotLoader and similar injection launchers normally exit after the
     * real game has been created. Their process status therefore cannot define
     * the lifetime of a managed TeknoParrot session. Follow the immutable game
     * executable from the prepared launch record and keep the X server/activity
     * alive until that process has genuinely ended.
     */
    private void monitorPreparedGameProcess() {
        if (preparedGameProcessMonitorStarted) return;
        preparedGameProcessMonitorStarted = true;

        String gameExecutable = findPreparedGameExecutable();
        if (gameExecutable == null) {
            exit();
            return;
        }
        String processMarker = FileUtils.getName(gameExecutable)
            .toLowerCase(java.util.Locale.ROOT);
        // Linux's comm field is limited to 15 characters. ProcessHelper matches
        // that UID-scoped field deliberately instead of stale parent cmdlines.
        if (processMarker.length() > 15)
            processMarker = processMarker.substring(0, 15);
        final String immutableProcessMarker = processMarker;

        Thread processMonitor = new Thread(() -> {
            final long handoffDeadline =
                android.os.SystemClock.elapsedRealtime() + 15_000L;
            boolean observedGame = false;
            long absentSince = -1L;

            while (!isFinishing() && !isDestroyed()) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (ProcessHelper.hasLiveGuestProcessName(
                        immutableProcessMarker)) {
                    observedGame = true;
                    absentSince = -1L;
                }
                else if (!observedGame) {
                    if (now >= handoffDeadline) break;
                }
                else {
                    if (absentSince < 0L) absentSince = now;
                    if (now - absentSince >= 2_000L) break;
                }
                android.os.SystemClock.sleep(250L);
            }

            if (!isFinishing() && !isDestroyed())
                runOnUiThread(this::exit);
        }, "PreparedGameProcessMonitor");
        processMonitor.start();
    }

    private boolean isPreparedCxbxrLaunch() {
        if (preparedWindowsLaunch == null ||
            preparedWindowsLaunch.executable == null)
            return false;
        String executable = preparedWindowsLaunch.executable
            .replace('/', '\\')
            .toLowerCase(java.util.Locale.ROOT);
        return executable.endsWith("\\cxbxr-ldr.exe");
    }

    private boolean isPreparedCxbxrPerformanceTitle() {
        if (!isPreparedCxbxrLaunch())
            return false;
        if ("cxbxr-performance".equals(
                preparedWindowsLaunch.compatibilityPreset))
            return true;

        // Managed games imported before a recipe gained a compatibility
        // preset retain their original stored launch record. Recognize the
        // two proven fast-path titles from the immutable XBE argument as a
        // migration-safe fallback so users do not have to delete/reimport.
        for (String argument : preparedWindowsLaunch.arguments) {
            if (argument == null)
                continue;
            String normalized = argument.replace('/', '\\')
                .toLowerCase(java.util.Locale.ROOT);
            if (normalized.endsWith("\\vc3.xbe") ||
                normalized.endsWith("\\vsg.xbe"))
                return true;
        }
        return false;
    }

    private boolean isPreparedCxbxrVirtuaCop3Title() {
        if (!isPreparedCxbxrLaunch())
            return false;
        for (String argument : preparedWindowsLaunch.arguments) {
            if (argument == null)
                continue;
            String normalized = argument.replace('/', '\\')
                .toLowerCase(java.util.Locale.ROOT);
            if (normalized.endsWith("\\vc3.xbe"))
                return true;
        }
        return false;
    }

    private boolean isPreparedCxbxrCrazyTaxiTitle() {
        if (!isPreparedCxbxrLaunch())
            return false;
        for (String argument : preparedWindowsLaunch.arguments) {
            if (argument == null)
                continue;
            String normalized = argument.replace('/', '\\')
                .toLowerCase(java.util.Locale.ROOT);
            if (normalized.endsWith("\\ctx_ac[r].xbe"))
                return true;
        }
        return false;
    }

    private boolean isPreparedCxbxrCooperativeSelfSuspendTitle() {
        if (!isPreparedCxbxrLaunch())
            return false;
        for (String argument : preparedWindowsLaunch.arguments) {
            if (argument == null)
                continue;
            String normalized = argument.replace('/', '\\')
                .toLowerCase(java.util.Locale.ROOT);
            // VC3 and OutRun need emulator-owned self-suspend events to
            // survive CRI/Sofdec worker transitions under Wine. OutRun is
            // included here while its post-cycle teardown is isolated: the
            // native suspend path leaves its game thread frozen at the legal
            // screen even though the JVS thread continues to poll normally.
            if (normalized.endsWith("\\vc3.xbe") ||
                normalized.endsWith("\\outrun2.xbe"))
                return true;
        }
        return false;
    }

    private boolean isPreparedCxbxrPulseAudioTitle() {
        if (!isPreparedCxbxrLaunch())
            return false;
        for (String argument : preparedWindowsLaunch.arguments) {
            if (argument == null)
                continue;
            String normalized = argument.replace('/', '\\')
                .toLowerCase(java.util.Locale.ROOT);
            if (normalized.endsWith("\\hod3xb.xbe"))
                return true;
        }
        return false;
    }

    private void setupWineSystemFiles() {
        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String rfsVersion = String.valueOf(rootFS.getVersion());
        boolean containerDataChanged = false;

        boolean wineprefixWasUpdated = WineUtils.isWineprefixWasUpdated(container);
        if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("rfsVersion").equals(rfsVersion) || wineprefixWasUpdated) {
            applyGeneralPatches(container);
            container.putExtra("appVersion", appVersion);
            container.putExtra("rfsVersion", rfsVersion);
            containerDataChanged = true;
        }

        if (verifyUserRegistry()) containerDataChanged = true;
        if (extractDXWrapperFiles()) containerDataChanged = true;

        if (!wincomponents.equals(container.getExtra("wincomponents"))) {
            extractWinComponentFiles();
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        String desktopTheme = container.getDesktopTheme();
        if (!(desktopTheme+","+xServer.screenInfo).equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), xServer.screenInfo);
            container.putExtra("desktopTheme", desktopTheme+","+xServer.screenInfo);
            containerDataChanged = true;
        }

        WineStartMenuCreator.create(this, container);
        WineUtils.createDosdevicesSymlinks(container, true);

        String startupSelection = String.valueOf(container.getStartupSelection());
        if (!startupSelection.equals(container.getExtra("startupSelection")) || wineprefixWasUpdated) {
            WineUtils.changeServicesStatus(container, container.getStartupSelection());
            container.putExtra("startupSelection", startupSelection);
            containerDataChanged = true;
        }

        boolean openAndroidBrowserFromWine = preferences.getBoolean("open_android_browser_from_wine", true);
        String openAndroidBrowserFromWineStr = openAndroidBrowserFromWine ? "t" : "f";
        if (!openAndroidBrowserFromWineStr.equals(container.getExtra("openAndroidBrowserFromWine")) || wineprefixWasUpdated) {
            WineUtils.changeBrowsersRegistryKey(container, openAndroidBrowserFromWine);
            container.putExtra("openAndroidBrowserFromWine", openAndroidBrowserFromWineStr);
            containerDataChanged = true;
        }

        if (containerDataChanged) container.saveData();
    }

    private void setupXEnvironment() {
        String rootPath = rootFS.getRootDir().getPath();
        envVars.put("MESA_DEBUG", "silent");
        envVars.put("MESA_NO_ERROR", "1");
        envVars.put("WINEPREFIX", rootPath+RootFS.WINEPREFIX);
        envVars.put("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", "1");

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels", SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS);
        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty() ? "+"+wineDebugChannels.replace(",", ",+") : "-all");

        // A killed or crashed managed Activity can leave re-parented Wine
        // processes behind. End only same-UID Wine guests before touching the
        // prefix or starting a new prepared session, otherwise two copies of a
        // large game can silently contend for CPU, RAM, and swap.
        if (hasPreparedWindowsLaunch)
            ProcessHelper.killGuestProcesses();

        FileUtils.clear(rootFS.getTmpDir());

        GuestProgramLauncherComponent guestProgramLauncherComponent = new GuestProgramLauncherComponent();
        TeknoParrotBridgeLauncherComponent teknoParrotBridgeLauncherComponent = null;
        TeknoParrotBridgeLauncherComponent teknoParrotJvsBridgeLauncherComponent = null;
        TeknoParrotWinePreflightComponent teknoParrotWinePreflightComponent = null;

        if (container != null) {
            if (!ensurePreparedWindowsPathBootstrap()) {
                runOnUiThread(this::finish);
                return;
            }
            // Apply display policy while the recipe still points at the original
            // dump. Dirty Drivin's exact-basename LAA copy lives in a private
            // subdirectory, but OpenParrot reads teknoparrot.ini from the declared
            // working directory. Staging first would therefore write Windowed=1
            // beside the private copy where the game never reads it.
            if (preparedWindowsLaunch != null && preparedWindowsLaunch.productionBridge &&
                !ensurePreparedDisplayIni()) {
                runOnUiThread(this::finish);
                return;
            }
            if (preparedWindowsLaunch != null && preparedWindowsLaunch.productionBridge &&
                !ensurePreparedLargeAddressAwareExecutable()) {
                runOnUiThread(this::finish);
                return;
            }
            if (preparedWindowsLaunch != null && preparedWindowsLaunch.productionBridge &&
                !ensurePreparedApm3StartupExecutable()) {
                runOnUiThread(this::finish);
                return;
            }
            if (preparedWindowsLaunch != null && preparedWindowsLaunch.productionBridge &&
                !ensurePreparedMarioKartStackExecutable()) {
                runOnUiThread(this::finish);
                return;
            }
            if (preparedWindowsLaunch != null && preparedWindowsLaunch.productionBridge &&
                !ensurePreparedGameWorkingDirectory()) {
                runOnUiThread(this::finish);
                return;
            }
            if (preparedWindowsLaunch != null && preparedWindowsLaunch.productionBridge &&
                !ensurePreparedCxbxrSettings()) {
                runOnUiThread(this::finish);
                return;
            }
            if (container.getHUDMode() == FrameRating.Mode.FULL.ordinal()) envVars.put("X11_WND_GPU_INFO", "1");
            String desktopName = shortcut != null || getIntent().hasExtra("exec_path") ||
                preparedWindowsLaunch != null ? "nogui" : "shell";
            String guestExecutable = "wine explorer /desktop="+desktopName+","+xServer.screenInfo+" "+getWineStartCommand();
            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);

            envVars.putAll(container.getEnvVars());
            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));
            // Prepared TeknoParrot launches should not inherit diagnostic logging
            // from the user's general Winlator settings or managed container.
            // Wine and DXVK can produce enough output during game startup to add
            // noticeable latency on Android.  Ordinary Winlator launches retain
            // their configured values so its diagnostic workflow is unaffected.
            if (preparedWindowsLaunch != null && !preparedWindowsLaunch.debugLoggingEnabled) {
                envVars.put("TP_ANDROID_DEBUG_LOGGING", "0");
                envVars.put("WINEDEBUG", "-all");
                envVars.put("DXVK_LOG_LEVEL", "none");
                envVars.put("DXVK_LOG_PATH", "none");
                envVars.put("VKD3D_DEBUG", "none");
                envVars.put("VKD3D_SHADER_DEBUG", "none");
                envVars.put("MESA_DEBUG", "silent");
                envVars.put("BOX64_NOBANNER", "1");
                envVars.put("BOX64_LOG", "0");
                envVars.put("BOX64_DYNAREC_MISSING", "0");
                envVars.put("BOX64_SHOWSEGV", "0");
                envVars.put("BOX64_DLSYM_ERROR", "0");
            }
            else if (preparedWindowsLaunch != null) {
                envVars.put("TP_ANDROID_DEBUG_LOGGING", "1");
                String preparedWineDebug = wineDebugChannels.isEmpty()
                    ? "+warn,+err"
                    : "+" + wineDebugChannels.replace(",", ",+");
                // A prepared launch's per-game diagnostic switch should
                // include Wine's exception channel. Without it, guest faults
                // report only the final address and lose the register/stack
                // context needed to distinguish a game bug from a renderer,
                // input, or loader failure. Production launches still take
                // the logging-disabled branch above and remain WINEDEBUG=-all.
                if (!preparedWineDebug.contains("+seh"))
                    preparedWineDebug += ",+seh";
                if ("eadp-dual-io".equals(preparedWindowsLaunch.compatibilityPreset)) {
                    if (!preparedWineDebug.contains("+loaddll"))
                        preparedWineDebug += ",+loaddll";
                    if (!preparedWineDebug.contains("+reg"))
                        preparedWineDebug += ",+reg";
                }
                envVars.put("WINEDEBUG", preparedWineDebug);
                envVars.put("DXVK_LOG_LEVEL", "info");
                envVars.put("VKD3D_DEBUG", "warn");
                envVars.put("BOX64_NOBANNER", "0");
                envVars.put("BOX64_LOG", "1");
                envVars.put("BOX64_DYNAREC_MISSING", "1");
                envVars.put("BOX64_SHOWSEGV", "1");
                envVars.put("BOX64_SHOWBT", "1");
                envVars.put("BOX64_ROLLING_LOG", "32");
                if ("eadp-dual-io".equals(preparedWindowsLaunch.compatibilityPreset)) {
                    // Wine's DLL/registry channels are sufficient for this
                    // title. Keep Box64 and DXVK quiet so the timing-sensitive
                    // copyright/PhysX transition remains representative.
                    envVars.put("BOX64_NOBANNER", "1");
                    envVars.put("BOX64_LOG", "0");
                    envVars.put("BOX64_DYNAREC_MISSING", "0");
                    envVars.put("DXVK_LOG_LEVEL", "none");
                }
            }
            // OpenParrot's default Get/SetThreadContext injection is unsafe for
            // Wine WoW64.  The Linux launcher already forces the loader's
            // remote-thread path for this reason; prepared Android launches
            // must make the same platform choice.
            if (preparedWindowsLaunch != null) {
                // True fullscreen is unreliable for a number of arcade dumps
                // under Wine/Winlator. Keep those games in their proven
                // windowed mode, but ask the native launch bootstrap to remove
                // Win32 non-client chrome and continuously center the client.
                // The bootstrap preserves the game's original client size.
                if ("centered".equals(preparedWindowsLaunch.displayMode)) {
                    envVars.put("TP_BORDERLESS_WINDOW", "1");
                    envVars.put("TP_CENTER_WINDOW", "1");
                }
                else {
                    envVars.remove("TP_BORDERLESS_WINDOW");
                    envVars.remove("TP_CENTER_WINDOW");
                }
                envVars.remove("TP_CRUISN_USE_REGAL");
                if (preparedWindowsLaunch.controlsProfileId == 9008)
                    envVars.put("TP_HIDE_WINDOW_MENU", "1");
                else
                    envVars.remove("TP_HIDE_WINDOW_MENU");
                // Crazy Taxi can exceed Wine WoW64's original host-thread
                // stack during the Start-to-game transition. CXBXR consumes
                // this flag by moving only its emulation call onto a larger
                // worker stack; the loader image and every other title retain
                // their existing address-space layout.
                if (isPreparedCxbxrCrazyTaxiTitle())
                    envVars.put("TP_ANDROID_CXBXR_LARGE_STACK", "1");
                else
                    envVars.remove("TP_ANDROID_CXBXR_LARGE_STACK");
                // The bootstrap and console-based loaders share a Wine console.
                // Keep it for explicit troubleshooting, but hide it during
                // normal play so only the centered game window is presented.
                if (preparedWindowsLaunch.productionBridge &&
                    !preparedWindowsLaunch.debugLoggingEnabled)
                    envVars.put("TP_HIDE_LAUNCH_CONSOLE", "1");
                else
                    envVars.remove("TP_HIDE_LAUNCH_CONSOLE");

                if ("star-wars".equals(preparedWindowsLaunch.compatibilityPreset)) {
                    envVars.put("TP_STARWARS_JVS_POLL_MS", "4");
                    // Battle Pod needs the fast preset to render UE3 at a
                    // useful rate, but its post-Start game-state transition is
                    // not safe with aggressive call/return prediction or fully
                    // relaxed memory ordering. Keep those targeted guards and
                    // allow the remaining performance-preset optimizations.
                    envVars.put("BOX64_DYNAREC_CALLRET", "0");
                    envVars.put("BOX64_DYNAREC_STRONGMEM", "1");
                    envVars.put("BOX64_DYNAREC_BIGBLOCK", "2");
                }
                else if (isPreparedCxbxrPerformanceTitle()) {
                    // VC3/GS need the performance preset to render at a useful
                    // rate, but VC3's FMV-to-attract transition stops its main
                    // emulation thread when call/return prediction and relaxed
                    // memory ordering are both enabled. Preserve the remaining
                    // fast-path settings while guarding that handoff.
                    envVars.put("BOX64_DYNAREC_CALLRET", "0");
                    envVars.put("BOX64_DYNAREC_STRONGMEM", "1");
                    envVars.put("BOX64_DYNAREC_BIGBLOCK", "2");
                }
                else {
                    envVars.remove("TP_STARWARS_JVS_POLL_MS");
                }

                boolean useWmmt3CardService = "wmmt3-yacard".equals(
                    preparedWindowsLaunch.compatibilityPreset);
                boolean useCxbxrWmmtCardService =
                    "cxbxr-wmmt-yacard".equals(
                        preparedWindowsLaunch.compatibilityPreset) &&
                    isPreparedCxbxrLaunch();
                boolean useCardService =
                    useWmmt3CardService || useCxbxrWmmtCardService;
                if (useCardService) {
                    // WMMT3 uses the S31R/38400-even helper bundled with
                    // ElfLoader2. Chihiro WMMT1/2 use a dedicated
                    // C1231LR/9600-none configuration beside their regional
                    // CXBXR loader so neither title family rewrites the other.
                    String cardDirectory = useCxbxrWmmtCardService
                        ? FileUtils.getDirname(preparedWindowsLaunch.executable) +
                            "\\YACardEmu"
                        : "E:\\TeknoParrotRuntime\\ElfLdr2\\YACardEmu";
                    envVars.put(
                        "TP_PRELAUNCH_EXECUTABLE",
                        cardDirectory + "\\YACardEmu.exe");
                    envVars.put("TP_PRELAUNCH_WORKING_DIRECTORY", cardDirectory);
                    envVars.put("TP_PRELAUNCH_DIRECT", "1");
                    envVars.put("TP_PRELAUNCH_HIDE_WINDOW", "1");
                    envVars.put("TP_PRELAUNCH_READY_PIPE", "\\\\.\\pipe\\YACardEmu");
                    // CXBXR exits its original loader after Partition3 and
                    // relaunches for the title XBE. Binding YACardEmu to that
                    // first process kills the reader during the handoff and
                    // leaves WMMT1/2 at E55. The managed session teardown
                    // already stops the complete Wine tree, so keep the
                    // companion alive for CXBXR while retaining the bounded
                    // lifetime for ElfLoader2's single-process WMMT3 path.
                    if (useCxbxrWmmtCardService)
                        envVars.remove("TP_PRELAUNCH_TERMINATE_WITH_GAME");
                    else
                        envVars.put("TP_PRELAUNCH_TERMINATE_WITH_GAME", "1");
                    if (preparedWindowsLaunch.debugLoggingEnabled)
                        envVars.put("TP_PRELAUNCH_ARGUMENTS", "-t -f");
                    else
                        envVars.remove("TP_PRELAUNCH_ARGUMENTS");
                    envVars.remove("TP_PRELAUNCH_WAIT_FOR_LOADER");
                }
                else {
                    envVars.remove("TP_PRELAUNCH_WORKING_DIRECTORY");
                    envVars.remove("TP_PRELAUNCH_DIRECT");
                    envVars.remove("TP_PRELAUNCH_HIDE_WINDOW");
                    envVars.remove("TP_PRELAUNCH_READY_PIPE");
                    envVars.remove("TP_PRELAUNCH_TERMINATE_WITH_GAME");
                    envVars.remove("TP_PRELAUNCH_ARGUMENTS");
                }

                if ("initial-d-the-arcade".equals(
                        preparedWindowsLaunch.compatibilityPreset)) {
                    String gameExecutable = findPreparedGameExecutable();
                    String marker = "\\App\\DAC\\WindowsNoEditor\\";
                    int markerIndex = gameExecutable != null
                        ? gameExecutable.toLowerCase(java.util.Locale.ROOT).indexOf(
                            marker.toLowerCase(java.util.Locale.ROOT))
                        : -1;
                    if (markerIndex >= 0) {
                        envVars.put(
                            "TP_PRELAUNCH_EXECUTABLE",
                            gameExecutable.substring(0, markerIndex) +
                                "\\App\\DAC\\AMDaemon\\amdaemon.exe");
                        // amdaemon is a persistent companion process. Waiting for
                        // its loader to exit prevents the main Unreal executable
                        // from ever launching; the desktop flow starts it, waits
                        // one second, and then continues with the game loader.
                        envVars.remove("TP_PRELAUNCH_WAIT_FOR_LOADER");
                    }
                    envVars.put("OPENSSL_ia32cap", ":~0x20000000");
                }
                else if (!"initial-d8".equals(
                        preparedWindowsLaunch.compatibilityPreset) &&
                        !useCardService) {
                    envVars.remove("TP_PRELAUNCH_EXECUTABLE");
                    envVars.remove("TP_PRELAUNCH_WAIT_FOR_LOADER");
                    envVars.remove("OPENSSL_ia32cap");
                }

                if ("initial-d8".equals(preparedWindowsLaunch.compatibilityPreset)) {
                    String gameExecutable = null;
                    for (int index = preparedWindowsLaunch.arguments.length - 1;
                         index >= 0; index--) {
                        String candidate = preparedWindowsLaunch.arguments[index];
                        if (candidate != null &&
                            candidate.matches("(?i)^[CDE]:\\\\[^/\"]+\\.exe$")) {
                            gameExecutable = candidate;
                            break;
                        }
                    }
                    if (gameExecutable != null) {
                        envVars.put(
                            "TP_PRELAUNCH_EXECUTABLE",
                            FileUtils.getDirname(gameExecutable) + "\\picodaemon.exe");
                    }
                    else {
                        envVars.remove("TP_PRELAUNCH_EXECUTABLE");
                    }
                    envVars.remove("TP_PRELAUNCH_WAIT_FOR_LOADER");
                }
                else if (!"initial-d-the-arcade".equals(
                        preparedWindowsLaunch.compatibilityPreset) &&
                        !useCardService) {
                    envVars.remove("TP_PRELAUNCH_EXECUTABLE");
                }

                if (isPreparedElfLoaderLaunch()) {
                    // ElfLoader loads its matching TeknoParrot DLL itself after
                    // mapping the ELF. OpenParrot's injection switches are both
                    // unnecessary and potentially misleading for this path.
                    envVars.remove("TP_REMOTETHREAD");
                    envVars.remove("TP_POSTSTART_REMOTETHREAD_MS");
                    envVars.remove("TP_ENTRYPOINT_REMOTETHREAD_MS");
                    envVars.remove("TP_LOADER_MANAGED_INIT");
                    envVars.remove("TP_CHILD_PRIMARY_THREAD_INIT");
                    envVars.remove("BOX64_DYNAREC_VOLATILE_METADATA");
                    envVars.remove("BOX64_SHOWBT");
                    envVars.remove("BOX64_ROLLING_LOG");
                    envVars.put("tp_windowed",
                        "fullscreen".equals(preparedWindowsLaunch.displayMode) ? "0" : "1");
                    envVars.put("TP_LOGTOFILE",
                        preparedWindowsLaunch.debugLoggingEnabled ? "1" : "0");
                }
                // Guilty Gear's Wine x86 startup owns ntdll's loader lock until
                // the executable entry point. A remote thread created before
                // that boundary deadlocks behind the primary thread. Use
                // OpenParrotLoader's original primary-thread entry-point path:
                // run Wine initialization to completion, suspend at the real
                // entry point, and execute LoadLibrary on that same thread.
                else if ("post-start-remote-thread".equals(
                        preparedWindowsLaunch.compatibilityPreset)) {
                    envVars.remove("TP_REMOTETHREAD");
                    envVars.remove("TP_POSTSTART_REMOTETHREAD_MS");
                    envVars.remove("TP_ENTRYPOINT_REMOTETHREAD_MS");
                    envVars.remove("TP_LOADER_MANAGED_INIT");
                    envVars.remove("TP_CHILD_PRIMARY_THREAD_INIT");
                    envVars.remove("BOX64_DYNAREC_VOLATILE_METADATA");
                    envVars.remove("BOX64_SHOWBT");
                    envVars.remove("BOX64_ROLLING_LOG");
                }
                // The WMMT family needs Wine to publish its target modules before the
                // remote thread is created, but OpenParrot must still initialize
                // before the game executes. Park its entry point while Wine
                // initializes, then inject against the resolved target address.
                else if (isWmmtCompatibilityPreset(
                        preparedWindowsLaunch.compatibilityPreset)) {
                    envVars.remove("TP_REMOTETHREAD");
                    envVars.remove("TP_POSTSTART_REMOTETHREAD_MS");
                    envVars.put("TP_ENTRYPOINT_REMOTETHREAD_MS", "3000");
                    envVars.put("TP_LOADER_MANAGED_INIT", "1");
                    envVars.remove("TP_CHILD_PRIMARY_THREAD_INIT");
                    // The fault target is the WMMT executable RVA rebased on
                    // OpenParrot64's volatile-metadata image base. Box64 enables
                    // this Windows-game optimization by default; WMMT does not
                    // need it, so disable it only for this compatibility recipe.
                    envVars.put("BOX64_DYNAREC_VOLATILE_METADATA", "0");
                    // WMMT's large image also produced invalid mid-instruction
                    // branch targets when Box64 joined code into big blocks.
                    // Keep that single translation guard while allowing the
                    // remaining performance-preset optimizations; the full
                    // stability preset makes both WMV playback and game logic
                    // run noticeably below real time on Snapdragon devices.
                    envVars.put("BOX64_DYNAREC_BIGBLOCK", "0");
                    // Wine cannot start winedbg under Box64 on Android. Avoid
                    // Box64's rolling/backtrace diagnostic path here: it adds a
                    // large startup cost and can itself fault during Wine process
                    // teardown. The regular warning/error channels still honor
                    // the per-game troubleshooting toggle.
                    envVars.put("BOX64_SHOWSEGV", "0");
                    envVars.remove("BOX64_SHOWBT");
                    envVars.remove("BOX64_ROLLING_LOG");
                    if (preparedWindowsLaunch.debugLoggingEnabled) {
                        envVars.put(
                            "WINEDEBUG",
                            "+warn,+err,+seh,+module,+mfplat,+winegstreamer,+winedmo,+winsock,+debugstr");
                        envVars.put("GST_DEBUG", "3");
                        envVars.put("GST_DEBUG_NO_COLOR", "1");
                        // Level 2 enables per-call tracing and can flood logcat
                        // fast enough for Android to kill the companion app.
                        envVars.put("BOX64_LOG", "1");
                    }
                    else {
                        envVars.put("WINEDEBUG", "-all");
                        envVars.remove("GST_DEBUG");
                        envVars.remove("GST_DEBUG_NO_COLOR");
                    }
                }
                else if (isPreparedBridge64Bit() ||
                    "wacky-races-network".equals(
                        preparedWindowsLaunch.compatibilityPreset) ||
                    "parked-entrypoint".equals(
                        preparedWindowsLaunch.compatibilityPreset) ||
                    "game-working-directory".equals(
                        preparedWindowsLaunch.compatibilityPreset) ||
                    "wined3d-parked-entrypoint".equals(
                        preparedWindowsLaunch.compatibilityPreset)) {
                    // A suspended target has not necessarily published
                    // kernel32.dll yet. Standard RemoteThread injection then
                    // fails while resolving LoadLibraryW (Error 2b), as seen
                    // with x64 STAR WARS, the x86 Wacky Races launcher,
                    // Super Bikes, and MKDX's unusual x86 image. Use
                    // the loader's parked-entry-point initializer: Wine may
                    // finish loading the image, while OpenParrot still
                    // initializes on the game's primary thread before its
                    // original entry point executes.
                    envVars.remove("TP_REMOTETHREAD");
                    envVars.remove("TP_POSTSTART_REMOTETHREAD_MS");
                    envVars.put("TP_ENTRYPOINT_REMOTETHREAD_MS", "3000");
                    envVars.put("TP_LOADER_MANAGED_INIT", "1");
                    // Some arcade launchers create the real game as a second
                    // process. Let OpenParrot's CreateProcessW hook use the
                    // same primary-thread bootstrap instead of rewriting the
                    // Wine/Box64 child thread context.
                    if (isPreparedBridge64Bit() ||
                        "wacky-races-network".equals(
                            preparedWindowsLaunch.compatibilityPreset))
                        envVars.put("TP_CHILD_PRIMARY_THREAD_INIT", "1");
                    else
                        envVars.remove("TP_CHILD_PRIMARY_THREAD_INIT");
                    // MK_AGP3_FINAL consistently raised a Wine SEH exception
                    // after its long shader-loading phase. Keep the
                    // parked-entry-point injection behavior, but prevent Box64
                    // from joining this game's translated blocks. Do not force
                    // the Crazy Speed x87 compatibility knobs here: an A/B run
                    // showed that they only postponed the MK failure.
                    if (isMarioKartDxPreparedLaunch()) {
                        envVars.put("BOX64_DYNAREC_BIGBLOCK", "0");
						if (preparedWindowsLaunch.debugLoggingEnabled)
							envVars.put("WINEDEBUG",
								"+err,+seh,+debugstr");
					}
                    envVars.remove("BOX64_DYNAREC_VOLATILE_METADATA");
                    envVars.remove("BOX64_SHOWBT");
                    envVars.remove("BOX64_ROLLING_LOG");
                }
                else {
                    envVars.remove("TP_POSTSTART_REMOTETHREAD_MS");
                    envVars.remove("TP_ENTRYPOINT_REMOTETHREAD_MS");
                    envVars.remove("TP_LOADER_MANAGED_INIT");
                    envVars.remove("TP_CHILD_PRIMARY_THREAD_INIT");
                    envVars.remove("BOX64_DYNAREC_VOLATILE_METADATA");
                    envVars.remove("BOX64_SHOWBT");
                    envVars.remove("BOX64_ROLLING_LOG");
                    envVars.put("TP_REMOTETHREAD", "1");
                }
                if (isCrazySpeedPreparedLaunch()) {
                    envVars.put("TP_CRAZY_SPEED_FAKE_MEMORY_WMI", "1");
                }
                else {
                    envVars.remove("TP_CRAZY_SPEED_FAKE_MEMORY_WMI");
                }
                envVars.remove("TP_JUSTICE_LEAGUE_ASPECT_GUARD");
                if (isCrazySpeedPreparedLaunch()) {
                    // The old Ogre D3D9 renderer uses x87 control/status state.
                    // Fast rounding translated its first attract scene into
                    // STATUS_FLOAT_MULTIPLE_TRAPS at
                    // RenderSystem_Direct3D9.dll+0x11DA3 on ARM64.
                    envVars.put("BOX64_DYNAREC_FASTNAN", "0");
                    envVars.put("BOX64_DYNAREC_FASTROUND", "0");
                    envVars.put("BOX64_DYNAREC_X87DOUBLE", "1");
                    envVars.put("BOX64_SYNC_ROUNDING", "1");
                }
                else {
                    envVars.remove("BOX64_DYNAREC_FASTNAN");
                    envVars.remove("BOX64_DYNAREC_FASTROUND");
                    envVars.remove("BOX64_DYNAREC_X87DOUBLE");
                    envVars.remove("BOX64_SYNC_ROUNDING");
                }

                if (isKofXiiiPreparedLaunch())
                    envVars.put("TP_KOFXIII_QUARTZ_NULL_GUARD", "1");
                else
                    envVars.remove("TP_KOFXIII_QUARTZ_NULL_GUARD");

                // OpenParrot applies this only to the verified Arcana Heart 2
                // eX-Board buffer descriptor. Wine has no hardware mixer, so
                // request software DirectSound buffers on Android.
                envVars.put("TP_EXBOARD_SOFTWARE_DSOUND", "1");

                // Homura's interpreter A/B removed the immediate invalid SEH
                // frame. Its apparent map-table failure came from the ALSA
                // AudioTrack churn handled by the PulseAudio override above,
                // so keep this genuinely interpreter-only as the preset name
                // promises.
                if ("box64-interpreter".equals(
                        preparedWindowsLaunch.compatibilityPreset)) {
                    envVars.put("BOX64_DYNAREC", "0");
                }

                // Dirty Drivin' can leave its bootstrap renderer normally and
                // still terminate after the cabinet-network probe.  Include
                // the Winsock and adapter channels only when this game's
                // troubleshooting toggle is enabled; the normal performance
                // path remains completely quiet.
                if (preparedWindowsLaunch.debugLoggingEnabled &&
                    "dirty-driving-fullscreen".equals(
                        preparedWindowsLaunch.compatibilityPreset))
                    envVars.put(
                        "WINEDEBUG",
                        "+warn,+err,+seh,+debugstr");
            }
            // The companion package currently has no working POSIX shm
            // backend for Wine ESYNC.  Force wineserver synchronization so
            // existing containers created with the upstream default can boot.
            // Preserve upstream behavior if this source is built as com.winlator.
            if (!BuildConfig.APPLICATION_ID.equals("com.winlator")) envVars.put("WINEESYNC", "0");
            else if (!envVars.has("WINEESYNC")) envVars.put("WINEESYNC", "1");

            String box64Preset = selectBox64Preset();
            guestProgramLauncherComponent.setBox64Preset(box64Preset);

            if (preparedWindowsLaunch != null && preparedWindowsLaunch.productionBridge &&
                "chase-hq2".equals(preparedWindowsLaunch.compatibilityPreset)) {
                teknoParrotWinePreflightComponent = createChaseHq2WinePreflight(
                    envVars, box64Preset);
                if (teknoParrotWinePreflightComponent == null) {
                    runOnUiThread(this::finish);
                    return;
                }
            }
            else if (preparedWindowsLaunch != null && preparedWindowsLaunch.productionBridge &&
                     "xact-local-register".equals(
                         preparedWindowsLaunch.compatibilityPreset)) {
                teknoParrotWinePreflightComponent = createLocalXactWinePreflight(
                    envVars, box64Preset);
                if (teknoParrotWinePreflightComponent == null) {
                    runOnUiThread(this::finish);
                    return;
                }
            }

            if (preparedWindowsLaunch != null && preparedWindowsLaunch.productionBridge) {
                String readyName = "bridge-ready-" + preparedWindowsLaunch.sessionId + ".flag";
                File readyFile = new File(container.getRootDir(),
                    ".wine/drive_c/teknoparrot-service/" + readyName);
                String helperName = isPreparedBridge64Bit() ?
                    "pipehelper64.exe" : "pipehelper32.exe";
                // TeknoParrotUI's desktop JVS server always exposes
                // TeknoParrot_JVS, including for 64-bit games. OpenParrot's
                // amJvs hook follows that contract and redirects COM3 to the
                // same unsuffixed pipe. Keep the bridge protocol's distinct
                // architecture names for validation, but expose the desktop-
                // compatible alias to 64-bit JVS guests.
                boolean isJvsPipePair =
                    "TeknoParrot_JVS64".equals(preparedWindowsLaunch.pipeName64) &&
                    "TeknoParrot_JVS".equals(preparedWindowsLaunch.pipeName32);
                // Initial D The Arcade is a 64-bit title, but its injected
                // amUsbio reader retains the desktop contract and opens the
                // unsuffixed \\.\pipe\TeknoParrotPipe. Keep the 64-bit helper
                // executable while publishing the legacy pipe name.
                boolean isInitialDTheArcade = "initial-d-the-arcade".equals(
                    preparedWindowsLaunch.compatibilityPreset);
                boolean isEadpDualIo = "eadp-dual-io".equals(
                    preparedWindowsLaunch.compatibilityPreset);
                boolean isSharedJvsDualIo = "shared-jvs-dual-io".equals(
                    preparedWindowsLaunch.compatibilityPreset);
                boolean usesUnsuffixedControlPipe = isJvsPipePair ||
                    isInitialDTheArcade;
                String pipeName = usesUnsuffixedControlPipe ?
                    preparedWindowsLaunch.pipeName32 :
                    (isPreparedBridge64Bit() ? preparedWindowsLaunch.pipeName64 :
                        preparedWindowsLaunch.pipeName32);
                String helperCommand = "wine C:\\teknoparrot-service\\" + helperName +
                    " pipe --name " + pipeName +
                    " --host 127.0.0.1 --port " + preparedWindowsLaunch.pipePort +
                    " --session " + preparedWindowsLaunch.sessionId +
                    " --token-env TP_BRIDGE_TOKEN" +
                    " --shared-page TeknoParrot_JvsState 64 " +
                    preparedWindowsLaunch.sharedPagePath +
                    " --ready-file C:\\teknoparrot-service\\" + readyName +
                    (preparedWindowsLaunch.debugLoggingEnabled ? "" : " --quiet");

                EnvVars bridgeEnvVars = new EnvVars();
                bridgeEnvVars.putAll(envVars);
                bridgeEnvVars.put("TP_BRIDGE_TOKEN", preparedWindowsLaunch.tokenHex);
                teknoParrotBridgeLauncherComponent =
                    new TeknoParrotBridgeLauncherComponent(readyFile);
                teknoParrotBridgeLauncherComponent.setGuestExecutable(helperCommand);
                teknoParrotBridgeLauncherComponent.setEnvVars(bridgeEnvVars);
                teknoParrotBridgeLauncherComponent.setBox64Preset(box64Preset);

                // ALLSIDTA and EADP use two independent desktop pipes. The
                // primary helper carries USB-I/O or the shared gun-state page;
                // AMDaemon/EADP's second cabinet device opens TeknoParrot_JVS.
                // Start both helpers before either game executable so neither
                // one-shot CreateFile call can miss its server.
                if (isInitialDTheArcade || isEadpDualIo || isSharedJvsDualIo) {
                    String jvsReadyName = "bridge-jvs-ready-" +
                        preparedWindowsLaunch.sessionId + ".flag";
                    File jvsReadyFile = new File(container.getRootDir(),
                        ".wine/drive_c/teknoparrot-service/" + jvsReadyName);
                    String jvsHelperCommand = "wine C:\\teknoparrot-service\\" +
                        helperName +
                        " pipe --name TeknoParrot_JVS" +
                        " --host 127.0.0.1 --port " + preparedWindowsLaunch.pipePort +
                        " --session " + preparedWindowsLaunch.sessionId +
                        " --token-env TP_BRIDGE_TOKEN" +
                        " --ready-file C:\\teknoparrot-service\\" + jvsReadyName +
                        (preparedWindowsLaunch.debugLoggingEnabled ? "" : " --quiet");
                    teknoParrotJvsBridgeLauncherComponent =
                        new TeknoParrotBridgeLauncherComponent(jvsReadyFile);
                    teknoParrotJvsBridgeLauncherComponent.setGuestExecutable(jvsHelperCommand);
                    teknoParrotJvsBridgeLauncherComponent.setEnvVars(bridgeEnvVars);
                    teknoParrotJvsBridgeLauncherComponent.setBox64Preset(box64Preset);
                }
            }
        }

        environment = new XEnvironment(this, rootFS);
        environment.addComponent(new SysVSharedMemoryComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        environment.addComponent(new XServerComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.XSERVER_PATH)));
        environment.addComponent(new NetworkInfoUpdateComponent());

        if (audioDriver.equals(AudioDrivers.ALSA)) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath+UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", ALSAClient.USE_SHARED_MEMORY ? "true" : "false");

            ALSAClient.Options options = ALSAClient.Options.fromKeyValueSet(audioDriverConfig);
            // Chase H.Q. 2 repeatedly closes and reopens its ALSA client while
            // initializing the WMV/DirectShow path. Android 16 retains very
            // large ART address-space reservations across the corresponding
            // native Attach/DetachCurrentThread cycle until Bionic can no
            // longer protect its atexit page and aborts the entire Activity.
            // The connector already supports serial client dispatch; isolate
            // that mode to Chase and keep the normal parallel audio path for
            // every other title.
            boolean multithreadedAlsaClients = preparedWindowsLaunch == null ||
                !"chase-hq2".equals(preparedWindowsLaunch.compatibilityPreset);
            environment.addComponent(new ALSAServerComponent(
                UnixSocketConfig.create(rootPath, UnixSocketConfig.ALSA_SERVER_PATH),
                options,
                multithreadedAlsaClients));
        }
        else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
            PulseAudioComponent pulseAudioComponent = new PulseAudioComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.PULSE_SERVER_PATH));
            envVars.put("PULSE_SERVER", rootPath+UnixSocketConfig.PULSE_SERVER_PATH);

            if (!audioDriverConfig.isEmpty()) {
                envVars.put("PULSE_LATENCY_MSEC", audioDriverConfig.getInt("latencyMillis", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS));
                pulseAudioComponent.setVolume(audioDriverConfig.getFloat("volume", AudioDriverConfigDialog.DEFAULT_VOLUME));
                pulseAudioComponent.setPerformanceMode(audioDriverConfig.getInt("performanceMode", AudioDriverConfigDialog.DEFAULT_PERFORMANCE_MODE));
            }
            else envVars.put("PULSE_LATENCY_MSEC", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS);
            environment.addComponent(pulseAudioComponent);
        }

        if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) {
            VortekRendererComponent.Options options = VortekRendererComponent.Options.fromKeyValueSet(this, graphicsDriverConfig[0]);
            VortekRendererComponent vortekRendererComponent = new VortekRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VORTEK_SERVER_PATH), options);
            environment.addComponent(vortekRendererComponent);
        }
        if (graphicsDriver[1].equals(GraphicsDrivers.VIRGL)) {
            environment.addComponent(new VirGLRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VIRGL_SERVER_PATH)));
        }

        if (teknoParrotWinePreflightComponent != null)
            environment.addComponent(teknoParrotWinePreflightComponent);
        if (teknoParrotBridgeLauncherComponent != null)
            environment.addComponent(teknoParrotBridgeLauncherComponent);
        if (teknoParrotJvsBridgeLauncherComponent != null)
            environment.addComponent(teknoParrotJvsBridgeLauncherComponent);
        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback(
            this::handleGuestProgramTermination);
        environment.addComponent(guestProgramLauncherComponent);

        if (isGenerateWineprefix()) {
            wineInfo = getIntent().getParcelableExtra("wine_info");
            if (wineInfo != null) WineInstaller.generateWineprefix(wineInfo, environment);
        }
        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars = null;
        }
        try {
            environment.startEnvironmentComponents();
        }
        catch (RuntimeException error) {
            Log.e(TAG, "Could not start the prepared Wine environment.", error);
            environment.stopEnvironmentComponents();
            runOnUiThread(this::finish);
            return;
        }

        winHandler.start();
        envVars.clear();
        graphicsDriver = null;
        dxwrapperConfig = null;
        graphicsDriverConfig = null;
        audioDriver = null;
        audioDriverConfig = null;
        wincomponents = null;
    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        xServerView = new XServerView(this, xServer);
        final GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(false);
        renderer.setCursorColor(preferences.getInt("cursor_color", 0xffffff));
        renderer.setCursorScale(preferences.getFloat("cursor_scale", 1.0f));
        boolean preparedAspectFit = preparedWindowsLaunch != null &&
            ("aspect-fit".equals(preparedWindowsLaunch.displayMode) ||
             "fullscreen".equals(preparedWindowsLaunch.displayMode));
        renderer.setForceWindowsFullscreen(
            preparedAspectFit ||
            (shortcut != null && shortcut.getExtra("forceFullscreen", "0").equals("1")));
        renderer.setRotatePreparedWindowsCounterClockwise(
            preparedWindowsLaunch != null &&
            "portrait-window-counter-clockwise".equals(
                preparedWindowsLaunch.compatibilityPreset));

        xServer.setRenderer(renderer);
        rootView.addView(xServerView);
        applyPreparedSurfaceFrameRate();

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        capturePointerOnExternalMouse = preferences.getBoolean("capture_pointer_on_external_mouse", true);
        touchpadView = new TouchpadView(this, xServer, capturePointerOnExternalMouse);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setMoveCursorToTouchpoint(preferences.getBoolean("move_cursor_to_touchpoint", false));
        touchpadView.setFourFingersTapCallback(() -> {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);
        });
        rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        if (forwardedInputBridge != null)
            inputControlsView.setInputEventListener(this::forwardVirtualTeknoParrotInput);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);

        if (container != null && container.getHUDMode() != FrameRating.Mode.DISABLED.ordinal()) {
            frameRating = new FrameRating(this);
            frameRating.setMode(FrameRating.Mode.values()[container.getHUDMode()]);
            frameRating.setVisibility(View.GONE);
            rootView.addView(frameRating);
        }

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null) showInputControls(profile);
            }
        }
        else if (hasPreparedWindowsLaunch) {
            int requestedProfileId = preparedWindowsLaunch != null &&
                    preparedWindowsLaunch.controlsProfileId > 0
                ? preparedWindowsLaunch.controlsProfileId
                : TEKNOPARROT_ARCADE_PROFILE_ID;
            int profileId = preferences.getInt(
                    getTeknoParrotControlsPreferenceKey(requestedProfileId),
                    requestedProfileId);
            if (profileId != 0) {
                ControlsProfile profile = inputControlsManager.getProfile(profileId);
                if (profile == null)
                    profile = inputControlsManager.getProfile(VIRTUAL_GAMEPAD_PROFILE_ID);
                if (profile != null) showInputControls(profile);
            }
        }

        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));
        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);
    }

    private void showInputControlsDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.input_controls_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_input_controls);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);
        final ArrayList<ControlsProfile> visibleProfiles = new ArrayList<>();
        final int[] selectedProfileId = {
            inputControlsView.getProfile() != null
                ? inputControlsView.getProfile().id
                : 0
        };
        Runnable loadProfileSpinner = () -> {
            visibleProfiles.clear();
            visibleProfiles.addAll(inputControlsManager.getProfiles(true));
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- "+getString(R.string.disabled)+" --");
            for (int i = 0; i < visibleProfiles.size(); i++) {
                ControlsProfile profile = visibleProfiles.get(i);
                if (profile.id == selectedProfileId[0]) selectedPosition = i + 1;
                profileItems.add(profile.getName());
            }

            sProfile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, profileItems));
            sProfile.setSelection(selectedPosition);
        };
        loadProfileSpinner.run();

        final CheckBox cbRelativeMouseMovement = dialog.findViewById(R.id.CBRelativeMouseMovement);
        cbRelativeMouseMovement.setChecked(xServer.isRelativeMouseMovement());

        final CheckBox cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

        dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
            int position = sProfile.getSelectedItemPosition();
            selectedProfileId[0] = position > 0 && position <= visibleProfiles.size()
                ? visibleProfiles.get(position - 1).id
                : 0;
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("edit_input_controls", true);
            intent.putExtra("selected_profile_id", selectedProfileId[0]);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(false);
                loadProfileSpinner.run();
            };
            startActivityForResult(intent, MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE);
        });

        dialog.setOnConfirmCallback(() -> {
            xServer.setRelativeMouseMovement(cbRelativeMouseMovement.isChecked());
            inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
            int position = sProfile.getSelectedItemPosition();
            if (position > 0 && position <= visibleProfiles.size()) {
                ControlsProfile profile = visibleProfiles.get(position - 1);
                showInputControls(profile);
                if (hasPreparedWindowsLaunch)
                    preferences.edit().putInt(
                            getTeknoParrotControlsPreferenceKey(
                                    preparedWindowsLaunch.controlsProfileId),
                            profile.id).apply();
            }
            else {
                hideInputControls();
                if (hasPreparedWindowsLaunch)
                    preferences.edit().putInt(
                            getTeknoParrotControlsPreferenceKey(
                                    preparedWindowsLaunch.controlsProfileId),
                            0).apply();
            }
        });

        dialog.show();
    }

    private String getTeknoParrotControlsPreferenceKey(int requestedProfileId) {
        int stableId = requestedProfileId > 0
            ? requestedProfileId
            : TEKNOPARROT_ARCADE_PROFILE_ID;
        return TEKNOPARROT_CONTROLS_PROFILE_PREFERENCE + "_" + stableId;
    }

    private void forwardVirtualTeknoParrotInput(
            Binding binding,
            boolean isActionDown,
            float offset) {
        if (forwardedInputBridge == null || binding == null)
            return;

        switch (binding) {
            case KEY_UP:
            case GAMEPAD_DPAD_UP:
                forwardedInputBridge.onVirtualButton(
                        ForwardedInputProtocol.BUTTON_UP, isActionDown);
                break;
            case KEY_DOWN:
            case GAMEPAD_DPAD_DOWN:
                forwardedInputBridge.onVirtualButton(
                        ForwardedInputProtocol.BUTTON_DOWN, isActionDown);
                break;
            case KEY_LEFT:
            case GAMEPAD_DPAD_LEFT:
                forwardedInputBridge.onVirtualButton(
                        ForwardedInputProtocol.BUTTON_LEFT, isActionDown);
                break;
            case KEY_RIGHT:
            case GAMEPAD_DPAD_RIGHT:
                forwardedInputBridge.onVirtualButton(
                        ForwardedInputProtocol.BUTTON_RIGHT, isActionDown);
                break;
            case KEY_ENTER:
            case KEY_1:
            case GAMEPAD_BUTTON_START:
                forwardedInputBridge.onVirtualButton(
                        ForwardedInputProtocol.BUTTON_START, isActionDown);
                break;
            case KEY_F2:
                forwardedInputBridge.onVirtualButton(
                        ForwardedInputProtocol.BUTTON_SERVICE, isActionDown);
                break;
            case KEY_F1:
                forwardedInputBridge.onVirtualButton(
                        ForwardedInputProtocol.BUTTON_TEST, isActionDown);
                break;
            case KEY_5:
            case GAMEPAD_BUTTON_SELECT:
                forwardedInputBridge.onVirtualButton(
                        ForwardedInputProtocol.BUTTON_COIN, isActionDown);
                break;
            case GAMEPAD_BUTTON_A:
                forwardedInputBridge.onVirtualButton(
                        ForwardedInputProtocol.BUTTON_1, isActionDown);
                break;
            case GAMEPAD_BUTTON_B:
                forwardedInputBridge.onVirtualButton(
                        ForwardedInputProtocol.BUTTON_2, isActionDown);
                break;
            case GAMEPAD_BUTTON_X:
                forwardedInputBridge.onVirtualButton(
                        ForwardedInputProtocol.BUTTON_3, isActionDown);
                break;
            case GAMEPAD_BUTTON_Y:
                forwardedInputBridge.onVirtualButton(
                        ForwardedInputProtocol.BUTTON_4, isActionDown);
                break;
            case GAMEPAD_BUTTON_L1:
                forwardedInputBridge.onVirtualButton(
                        ForwardedInputProtocol.BUTTON_5, isActionDown);
                break;
            case GAMEPAD_BUTTON_R1:
                forwardedInputBridge.onVirtualButton(
                        ForwardedInputProtocol.BUTTON_6, isActionDown);
                break;
            case GAMEPAD_BUTTON_L2:
                if (preparedWindowsLaunch != null &&
                    preparedWindowsLaunch.controlsProfileId == 9033)
                    forwardedInputBridge.onVirtualButton(
                            ForwardedInputProtocol.BUTTON_7, isActionDown);
                forwardedInputBridge.onVirtualAxis(4, isActionDown ? 1.0f : 0.0f);
                break;
            case GAMEPAD_BUTTON_R2:
                if (preparedWindowsLaunch != null &&
                    preparedWindowsLaunch.controlsProfileId == 9033)
                    forwardedInputBridge.onVirtualButton(
                            ForwardedInputProtocol.BUTTON_8, isActionDown);
                forwardedInputBridge.onVirtualAxis(5, isActionDown ? 1.0f : 0.0f);
                break;
            case GAMEPAD_LEFT_THUMB_LEFT:
                forwardedInputBridge.onVirtualAxis(
                        0, isActionDown ? -bindingMagnitude(offset) : 0.0f);
                break;
            case GAMEPAD_LEFT_THUMB_RIGHT:
                forwardedInputBridge.onVirtualAxis(
                        0, isActionDown ? bindingMagnitude(offset) : 0.0f);
                break;
            case GAMEPAD_LEFT_THUMB_UP:
                forwardedInputBridge.onVirtualAxis(
                        1, isActionDown ? -bindingMagnitude(offset) : 0.0f);
                break;
            case GAMEPAD_LEFT_THUMB_DOWN:
                forwardedInputBridge.onVirtualAxis(
                        1, isActionDown ? bindingMagnitude(offset) : 0.0f);
                break;
            case GAMEPAD_RIGHT_THUMB_LEFT:
                forwardedInputBridge.onVirtualAxis(
                        2, isActionDown ? -bindingMagnitude(offset) : 0.0f);
                break;
            case GAMEPAD_RIGHT_THUMB_RIGHT:
                forwardedInputBridge.onVirtualAxis(
                        2, isActionDown ? bindingMagnitude(offset) : 0.0f);
                break;
            case GAMEPAD_RIGHT_THUMB_UP:
                forwardedInputBridge.onVirtualAxis(
                        3, isActionDown ? -bindingMagnitude(offset) : 0.0f);
                break;
            case GAMEPAD_RIGHT_THUMB_DOWN:
                forwardedInputBridge.onVirtualAxis(
                        3, isActionDown ? bindingMagnitude(offset) : 0.0f);
                break;
            default:
                break;
        }
    }

    private static float bindingMagnitude(float offset) {
        float magnitude = Math.abs(offset);
        return magnitude > 0.0f ? magnitude : 1.0f;
    }

    private void showInputControls(ControlsProfile profile) {
        inputControlsView.setHideStickControls(
            preparedWindowsLaunch != null &&
            preparedWindowsLaunch.controlsProfileId == TEKNOPARROT_LUIGI_PROFILE_ID);
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(profile);

        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonRightEnabled(false);

        GLRenderer renderer = xServerView.getRenderer();
        boolean usesDirectCabinetTouch = preparedWindowsLaunch != null &&
            ("shared-jvs-dual-io".equals(preparedWindowsLaunch.compatibilityPreset) ||
             "direct-touch-jvs".equals(preparedWindowsLaunch.compatibilityPreset));
        if (usesDirectCabinetTouch) {
            // Wonderland Wars and Shining Force Cross read title-specific
            // touch emulators synthesized from the Wine cursor and left-button
            // state. Preserve overlays while making uncovered screen touches
            // absolute press-and-drag input.
            renderer.setCursorVisible(false);
            touchpadView.setDirectTouchMode(true);
            touchpadView.setEnabled(true);
        }
        else if (preparedWindowsLaunch != null || profile.isDisableMouseInput()) {
            renderer.setCursorVisible(false);
            touchpadView.setDirectTouchMode(false);
            touchpadView.setEnabled(false);
        }
        else {
            renderer.setCursorVisible(true);
            touchpadView.setDirectTouchMode(false);
            touchpadView.setEnabled(true);
        }

        inputControlsView.invalidate();
    }

    private void hideInputControls() {
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        if (!touchpadView.isEnabled()) {
            touchpadView.setEnabled(true);
            xServerView.getRenderer().setCursorVisible(preparedWindowsLaunch == null);
        }

        inputControlsView.invalidate();
    }

    private void applyPreparedFrameRateLimit() {
        if (preparedWindowsLaunch == null || preparedWindowsLaunch.frameRateLimit <= 0)
            return;

        // The managed game window owns the display while it runs. Requesting
        // the recipe rate here makes Mesa's synchronized presentation use a
        // 60 Hz Android mode even on 120 Hz phones such as the Fold6.
        android.view.WindowManager.LayoutParams attributes = getWindow().getAttributes();
        float requestedRate = preparedWindowsLaunch.frameRateLimit;
        attributes.preferredRefreshRate = requestedRate;

        android.view.Display display = getWindowManager().getDefaultDisplay();
        android.view.Display.Mode currentMode = display.getMode();
        android.view.Display.Mode bestMode = null;
        float bestDifference = Float.MAX_VALUE;
        for (android.view.Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() != currentMode.getPhysicalWidth() ||
                    mode.getPhysicalHeight() != currentMode.getPhysicalHeight())
                continue;
            float difference = Math.abs(mode.getRefreshRate() - requestedRate);
            if (difference < bestDifference) {
                bestMode = mode;
                bestDifference = difference;
            }
        }
        if (bestMode != null) {
            attributes.preferredDisplayModeId = bestMode.getModeId();
            attributes.preferredRefreshRate = bestMode.getRefreshRate();
        }
        getWindow().setAttributes(attributes);
    }

    private void applyPreparedSurfaceFrameRate() {
        if (preparedWindowsLaunch == null || preparedWindowsLaunch.frameRateLimit <= 0 ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            return;

        final float requestedRate = preparedWindowsLaunch.frameRateLimit;
        xServerView.getHolder().addCallback(new SurfaceHolder.Callback() {
            private void apply(Surface surface) {
                if (surface != null && surface.isValid())
                    surface.setFrameRate(
                        requestedRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            }

            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                apply(holder.getSurface());
            }

            @Override
            public void surfaceChanged(
                    @NonNull SurfaceHolder holder, int format, int width, int height) {
                apply(holder.getSurface());
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            }
        });
    }

    private void extractGraphicsDriverFiles() {
        boolean frameRateLimited = preparedWindowsLaunch != null &&
                preparedWindowsLaunch.frameRateLimit > 0;
        envVars.put(
            "vblank_mode",
            frameRateLimited ? "1" : "0");

        String cacheId = "";
        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            cacheId += graphicsDriver[0]+"-"+graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
        }
        else cacheId += graphicsDriver[0]+"-"+DefaultVersion.valueOf(graphicsDriver[0]);
        cacheId += "-"+graphicsDriver[1]+"-"+DefaultVersion.valueOf(graphicsDriver[1]);

        boolean changed = !cacheId.equals(container.getExtra("graphicsDriver"));
        File rootDir = rootFS.getRootDir();
        File libDir = rootFS.getLibDir();

        if (changed) {
            FileUtils.delete(new File(libDir, "libvulkan_freedreno.so"));
            FileUtils.delete(new File(libDir, "libvulkan_vortek.so"));
            FileUtils.delete(new File(libDir, "libGL.so.1.7.0"));

            File vulkanICDDir = new File(rootDir, "/usr/share/vulkan/icd.d");
            FileUtils.delete(vulkanICDDir);
            vulkanICDDir.mkdirs();

            container.putExtra("graphicsDriver", cacheId);
            container.saveData();
        }

        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            // Zink is OpenGL-over-Vulkan. DXVK's maxFrameRate setting cannot
            // throttle it, so prepared arcade launches use FIFO + vblank.
            // Ordinary Winlator sessions retain the original uncapped mode.
            envVars.put(
                "MESA_VK_WSI_PRESENT_MODE",
                frameRateLimited ? "fifo" : "mailbox");
            TurnipConfigDialog.setEnvVars(this, graphicsDriverConfig[0], envVars);

            if (changed) {
                String version = graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
                GeneralComponents.extractFile(GeneralComponents.Type.TURNIP, this, version, DefaultVersion.TURNIP);
            }
        }
        else if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK) && (changed || MainActivity.DEBUG_MODE)) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/vortek-" + DefaultVersion.VORTEK + ".tzst", rootDir);
        }

        switch (graphicsDriver[1]) {
            case GraphicsDrivers.ZINK:
                envVars.put("GALLIUM_DRIVER", "zink");
                envVars.put("ZINK_CONTEXT_THREADED", "1");
                if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3");

                if (changed) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/zink-"+DefaultVersion.ZINK+".tzst", rootDir);
                break;
            case GraphicsDrivers.VIRGL:
                envVars.put("GALLIUM_DRIVER", "virpipe");
                envVars.put("VIRGL_NO_READBACK", "true");
                envVars.put("VIRGL_SERVER_PATH", rootDir+UnixSocketConfig.VIRGL_SERVER_PATH);
                VirGLConfigDialog.setEnvVars(graphicsDriverConfig[1], envVars);

                if (changed) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/virgl-"+DefaultVersion.VIRGL+".tzst", rootDir);
                break;
            case GraphicsDrivers.GLADIO:
                envVars.put("GLADIO_NO_ERROR", "1");

                if (changed || MainActivity.DEBUG_MODE) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/gladio-"+DefaultVersion.GLADIO+".tzst", rootDir);
                break;
        }
    }

    private void showTouchpadHelpDialog() {
        ContentDialog dialog = new ContentDialog(this, R.layout.touchpad_help_dialog);
        dialog.setTitle(R.string.touchpad_help);
        dialog.setIcon(R.drawable.icon_help);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        // A per-profile controller assignment owns the event. Do not also send
        // the bridge's hardcoded Xbox mapping or one physical control would
        // activate both its old and newly selected cabinet actions.
        boolean mappedByControlsProfile =
                !forwardedInputActivityDiagnostic &&
                inputControlsView != null &&
                inputControlsView.onGenericMotionEvent(event);
        if (forwardedInputBridge != null && !mappedByControlsProfile)
            forwardedInputBridge.onGenericMotionEvent(event);
        if (forwardedInputActivityDiagnostic)
            return super.dispatchGenericMotionEvent(event);
        if (mappedByControlsProfile)
            return true;
        return !winHandler.onGenericMotionEvent(event) && !touchpadView.onExternalMouseEvent(event) && super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean mappedByControlsProfile =
                !forwardedInputActivityDiagnostic &&
                inputControlsView != null &&
                inputControlsView.onKeyEvent(event);
        if (forwardedInputBridge != null && !mappedByControlsProfile)
            forwardedInputBridge.onKeyEvent(event);
        if (forwardedInputActivityDiagnostic)
            return super.dispatchKeyEvent(event);
        if (mappedByControlsProfile)
            return true;
        return (!winHandler.onKeyEvent(event) && xServer.keyboard.onKeyEvent(event)) ||
               (!ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();
        int pointerId = event.getPointerId(actionIndex);
        if ((action == MotionEvent.ACTION_DOWN ||
                action == MotionEvent.ACTION_POINTER_DOWN) &&
                inputControlsView != null &&
                inputControlsView.isPointOverControl(
                    event.getX(actionIndex), event.getY(actionIndex))) {
            overlayTouchPointers.put(pointerId, true);
        }

        if (forwardedInputBridge != null)
            forwardedInputBridge.onTouchEvent(event, overlayTouchPointers);
        boolean handled = super.dispatchTouchEvent(event);

        if (action == MotionEvent.ACTION_UP ||
                action == MotionEvent.ACTION_POINTER_UP) {
            overlayTouchPointers.delete(pointerId);
        }
        else if (action == MotionEvent.ACTION_CANCEL) {
            overlayTouchPointers.clear();
        }
        return handled;
    }

    private void scheduleForwardedInputActivityDiagnostic(int attempt) {
        View decor = getWindow().getDecorView();
        if (forwardedInputBridge == null || isFinishing()) {
            finish();
            return;
        }
        if (!forwardedInputBridge.isConnected() || decor.getWidth() <= 1 || decor.getHeight() <= 1) {
            if (attempt >= 100) {
                finish();
                return;
            }
            decor.postDelayed(() -> scheduleForwardedInputActivityDiagnostic(attempt + 1), 50);
            return;
        }

        long downTime = SystemClock.uptimeMillis();
        dispatchKeyEvent(new KeyEvent(
                downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_5, 0));
        dispatchKeyEvent(new KeyEvent(
                downTime, downTime + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_5, 0));

        float x = decor.getWidth() * 0.25f;
        float y = decor.getHeight() * 0.75f;
        MotionEvent down = MotionEvent.obtain(
                downTime, downTime + 2, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        dispatchTouchEvent(down);
        down.recycle();

        MotionEvent up = MotionEvent.obtain(
                downTime, downTime + 3, MotionEvent.ACTION_UP, x, y, 0);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        dispatchTouchEvent(up);
        up.recycle();

        decor.postDelayed(this::finish, 250);
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private boolean extractDXWrapperFiles() {
        String cacheId = "";
        if (dxwrapper.equals(DXWrappers.DXVK)) {
            if (preparedWindowsLaunch != null && preparedWindowsLaunch.frameRateLimit > 0)
                dxwrapperConfig[0].put(
                    "framerate", Integer.toString(preparedWindowsLaunch.frameRateLimit));
            DXVKConfigDialog.setEnvVars(this, dxwrapperConfig[0], envVars);
            cacheId += dxwrapper+"-"+dxwrapperConfig[0].get("version", DefaultVersion.DXVK(graphicsDriver[0]));
        }
        else if (dxwrapper.equals(DXWrappers.WINED3D)) {
            WineD3DConfigDialog.setEnvVars(dxwrapperConfig[0], envVars);
            cacheId += dxwrapper+"-"+dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
        }

        String ddrawWrapper = dxwrapperConfig[0].get("ddrawWrapper", DXWrappers.WINED3D);
        if (preparedWindowsLaunch != null &&
            "large-address-aware-ddraw".equals(preparedWindowsLaunch.compatibilityPreset))
            ddrawWrapper = DXWrappers.CNC_DDRAW;
        cacheId += "-"+DXWrappers.VKD3D+"-"+dxwrapperConfig[1].get("version", DefaultVersion.VKD3D)+"-"+ddrawWrapper;
        boolean changed = !cacheId.equals(container.getExtra("dxwrapper"));
        VKD3DConfigDialog.setEnvVars(dxwrapperConfig[1], envVars);

        if (ddrawWrapper.equals(DXWrappers.CNC_DDRAW)) envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\ProgramData\\cnc-ddraw\\ddraw.ini");

        if (!changed) return false;
        container.putExtra("dxwrapper", cacheId);

        File rootDir = rootFS.getRootDir();
        File windowsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");

        if (dxwrapper.equals(DXWrappers.WINED3D)) {
            String version = dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
            if (version.equals(WineInfo.MAIN_WINE_VERSION)) {
                final String[] dlls = {"d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "dxgi.dll", "ddraw.dll", "wined3d.dll"};
                restoreBuiltinDllFiles(dlls);
            }
            else GeneralComponents.extractFile(GeneralComponents.Type.WINED3D, this, version, DefaultVersion.WINED3D);
        }
        else if (dxwrapper.equals(DXWrappers.DXVK)) {
            final boolean[] hasD3D8DllFile = {false};
            final boolean[] hasD3D10DllFile = {false};

            GeneralComponents.extractFile(GeneralComponents.Type.DXVK, this, dxwrapperConfig[0].get("version"), DefaultVersion.DXVK(graphicsDriver[0]), (destination, size) -> {
                String name = destination.getName();
                if (name.equals("d3d10.dll")) {
                    hasD3D10DllFile[0] = true;
                }
                else if (name.equals("d3d8.dll")) {
                    hasD3D8DllFile[0] = true;
                }
                return destination;
            });

            if (!hasD3D8DllFile[0]) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d8vk-"+DefaultVersion.D8VK+".tzst", windowsDir);
            }
            if (!hasD3D10DllFile[0]) restoreBuiltinDllFiles("d3d10.dll", "d3d10_1.dll");
        }

        GeneralComponents.extractFile(GeneralComponents.Type.VKD3D, this, dxwrapperConfig[1].get("version"), DefaultVersion.VKD3D);

        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");
        FileUtils.delete(new File(containerSysWoW64Dir, "ddraw_.dll"));

        switch (ddrawWrapper) {
            case DXWrappers.CNC_DDRAW:
                final String assetDir = "dxwrapper/cnc-ddraw-"+DefaultVersion.CNC_DDRAW;
                File configFile = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/ddraw.ini");
                if (!configFile.isFile()) FileUtils.copy(this, assetDir+"/ddraw.ini", configFile);
                File shadersDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/Shaders");
                FileUtils.delete(shadersDir);
                FileUtils.copy(this, assetDir+"/Shaders", shadersDir);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, assetDir+"/ddraw.tzst", windowsDir);
                break;
            case DXWrappers.D7VK:
                restoreBuiltinDllFiles("ddraw.dll");
                (new File(containerSysWoW64Dir, "ddraw.dll")).renameTo(new File(containerSysWoW64Dir, "ddraw_.dll"));
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d7vk-"+DefaultVersion.D7VK+".tzst", windowsDir);
                break;
            default:
                restoreBuiltinDllFiles("ddraw.dll");
                break;
        }
        return true;
    }

    private boolean prepareTeknoParrotCompatibilityPayload() {
        if (preparedWindowsLaunch == null ||
            !"taito-legacy-scard".equals(preparedWindowsLaunch.compatibilityPreset))
            return true;

        File destination = new File(
            rootFS.getRootDir(),
            RootFS.WINEPREFIX + "/drive_c/windows/syswow64/winscard.dll");
        FileUtils.copy(this, "teknoparrot/winscard-x86.dll", destination);
        if (!destination.isFile() || destination.length() == 0) {
            Log.e(TAG, "Could not install the TeknoParrot WinSCard compatibility payload.");
            return false;
        }
        return true;
    }

    private void applyPreparedWineRegistryWorkarounds() {
        if (preparedWindowsLaunch == null)
            return;

        File systemRegFile = new File(
            rootFS.getRootDir(), RootFS.WINEPREFIX + "/system.reg");
        if (isWmmtCompatibilityPreset(preparedWindowsLaunch.compatibilityPreset)) {
            // WMMT6R requests MFVideoFormat_RGB32 from a source reader without
            // enabling Media Foundation's optional video-processing pipeline.
            // Wine-GStreamer successfully decodes the WMV9 stream to raw YUV, but
            // mfreadwrite then searches only the video-decoder category for the
            // final YUV-to-RGB transform. Wine already provides both compatible
            // transforms; expose them to that search without replacing any codec.
            String category =
                "Software\\Classes\\MediaFoundation\\Transforms\\Categories\\" +
                "d6c02d4b-6833-45b4-971a-05a4b04bab91\\";
            try (WineRegistryEditor registryEditor =
                     new WineRegistryEditor(systemRegFile)) {
                boolean videoProcessor = registryEditor.ensureKey(
                    category + "88753b26-5b24-49bd-b2e7-0c445c78c982");
                boolean colorConverter = registryEditor.ensureKey(
                    category + "98230571-0087-4204-b020-3282538e57d3");
                if (!videoProcessor || !colorConverter)
                    Log.e(TAG,
                        "Could not register WMMT Media Foundation color conversion transforms.");
                else
                    Log.i(TAG,
                        "Registered WMMT Media Foundation color conversion transforms.");
            }
        }

        if ("eadp-dual-io".equals(preparedWindowsLaunch.compatibilityPreset)) {
            installPreparedEadpPhysxRuntime(systemRegFile);
        }
    }

    /**
     * Elevator Action Death Parade requests the registry-discovered PhysX 2.8.0
     * runtime. Its small dump carries the loader and cooking stubs, but not the
     * registered engine files that the desktop NVIDIA redistributable installs.
     * Stage only exact non-empty files supplied beside this title and point both
     * Wine registry views at the private prefix copy. CPU mode avoids probing an
     * unavailable NVIDIA CUDA device on Android.
     */
    private void installPreparedEadpPhysxRuntime(File systemRegFile) {
        String gameExecutable = findPreparedGameExecutable();
        if (gameExecutable == null) {
            Log.e(TAG, "The EADP PhysX stage has no validated game executable.");
            return;
        }

        try {
            if (!isPreparedDosPathWithinDrive(gameExecutable, false))
                throw new IOException("The EADP executable left its declared Wine drive.");
            File gameFile = new File(
                WineUtils.dosToUnixPath(gameExecutable, container)).getCanonicalFile();
            File gameDirectory = gameFile.getParentFile();
            String[] requiredNames = {
                "PhysXCore.dll",
                "PhysXCooking.dll",
                "cudart32_65.dll"
            };
            File[] sourceFiles = new File[requiredNames.length];
            File[] siblings = gameDirectory.listFiles();
            for (int index = 0; index < requiredNames.length; index++) {
                if (siblings != null) {
                    for (File sibling : siblings) {
                        if (sibling.isFile() && sibling.length() != 0 &&
                            sibling.getName().equalsIgnoreCase(requiredNames[index])) {
                            sourceFiles[index] = sibling;
                            break;
                        }
                    }
                }
                if (sourceFiles[index] == null)
                    throw new IOException(
                        requiredNames[index] + " is missing beside the EADP executable.");
            }

            File winePrefix = new File(rootFS.getRootDir(), RootFS.WINEPREFIX);
            File engineRoot = new File(
                winePrefix,
                "drive_c/Program Files (x86)/NVIDIA Corporation/PhysX/Engine");
            File engineVersion = new File(engineRoot, "v2.8.0");
            if (!engineVersion.isDirectory() && !engineVersion.mkdirs())
                throw new IOException("Could not create the private PhysX 2.8.0 directory.");
            for (File sourceFile : sourceFiles) {
                File destination = new File(engineVersion, sourceFile.getName());
                if (!FileUtils.copy(sourceFile, destination) ||
                    !destination.isFile() ||
                    destination.length() != sourceFile.length())
                    throw new IOException(
                        "Could not stage " + sourceFile.getName() + " for EADP.");
            }

            String engineDosPath =
                "C:\\Program Files (x86)\\NVIDIA Corporation\\PhysX\\Engine";
            String[] registryRoots = {
                "Software\\AGEIA Technologies",
                "Software\\Wow6432Node\\AGEIA Technologies"
            };
            try (WineRegistryEditor registryEditor =
                     new WineRegistryEditor(systemRegFile)) {
                for (String registryRoot : registryRoots) {
                    registryEditor.setStringValue(
                        registryRoot, "PhysXCore Path", engineDosPath);
                    registryEditor.setStringValue(registryRoot, "HwSelection", "CPU");
                    registryEditor.setDwordValue(
                        registryRoot + "\\PhysX_A32_Engines", "2.8.0", 0x36);
                }
            }
            Log.i(TAG, "Staged and registered the title-local EADP PhysX 2.8.0 runtime.");
        }
        catch (Exception error) {
            Log.e(TAG, "Could not prepare the title-local EADP PhysX runtime.", error);
        }
    }

    private void extractWinComponentFiles() {
        File rootDir = rootFS.getRootDir();
        File windowsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");
        File systemRegFile = new File(rootDir, RootFS.WINEPREFIX+"/system.reg");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(this, "wincomponents/wincomponents.json"));
            Iterator<String[]> oldWinComponentsIter = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();
            ArrayList<String> builtinDlls = new ArrayList<>();

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1])) continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                if (useNative) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir);
                }
                else {
                    JSONObject wincomponentJSONObject = wincomponentsJSONObject.getJSONObject(identifier);
                    if (wincomponentJSONObject.getBoolean("restoreBuiltinDlls")) {
                        JSONArray dlnames = wincomponentJSONObject.getJSONArray("dlnames");
                        for (int i = 0; i < dlnames.length(); i++) {
                            String dlname = dlnames.getString(i);
                            builtinDlls.add(!dlname.endsWith(".exe") ? dlname+".dll" : dlname);
                        }
                    }
                    else {
                        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir, (destination, size) -> {
                            String name = destination.getName();
                            if (name.endsWith(".dll") || name.endsWith(".manifest") || name.endsWith("_deadbeef")) FileUtils.delete(destination);
                            return null;
                        });
                    }
                }

                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative);
            }

            if (!builtinDlls.isEmpty()) restoreBuiltinDllFiles(builtinDlls.toArray(new String[0]));
            WineUtils.overrideWinComponentDlls(this, container, wincomponents);
        }
        catch (JSONException e) {}
    }

    private void restoreBuiltinDllFiles(final String... dlls) {
        File rootDir = rootFS.getRootDir();
        File wineDir = new File(rootDir, rootFS.getWinePath());
        File wineSystem32Dir = new File(wineDir, "/lib/wine/x86_64-windows");
        File wineSysWoW64Dir = new File(wineDir, "/lib/wine/i386-windows");
        File containerSystem32Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/system32");
        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");;

        for (String dll : dlls) {
            FileUtils.copy(new File(wineSysWoW64Dir, dll), new File(containerSysWoW64Dir, dll));
            FileUtils.copy(new File(wineSystem32Dir, dll), new File(containerSystem32Dir, dll));
        }
    }

    private boolean isGenerateWineprefix() {
        return getIntent().getBooleanExtra("generate_wineprefix", false);
    }

    private String getWineStartCommand() {
        if (preparedWindowsLaunch != null) {
            String executable = preparedWindowsLaunch.executable;
            if (preparedWindowsLaunch.libraryDirectory != null) {
                StringBuilder bootstrapCommand = new StringBuilder("C:\\windows\\winhandler.exe /dir ")
                    .append(StringUtils.escapeDOSPath(preparedWindowsLaunch.workingDirectory))
                    .append(' ')
                    .append(quotePreparedWindowsArgument(PREPARED_WINDOWS_BOOTSTRAP_DOS_PATH))
                    .append(' ')
                    .append(quotePreparedWindowsArgument(preparedWindowsLaunch.libraryDirectory))
                    .append(' ')
                    .append(quotePreparedWindowsArgument(executable));
                for (String argument : preparedWindowsLaunch.arguments)
                    bootstrapCommand.append(' ').append(quotePreparedWindowsArgument(argument));
                appendPreparedCxbxrDebugMode(bootstrapCommand);
                return bootstrapCommand.toString();
            }

            String launchTarget = executable;
            if (FileUtils.getDirname(executable).equalsIgnoreCase(
                    preparedWindowsLaunch.workingDirectory))
                launchTarget = FileUtils.getName(executable);

            StringBuilder preparedCommand = new StringBuilder("C:\\windows\\winhandler.exe /dir ")
                .append(StringUtils.escapeDOSPath(preparedWindowsLaunch.workingDirectory))
                .append(' ')
                .append(quotePreparedWindowsArgument(launchTarget));
            for (String argument : preparedWindowsLaunch.arguments)
                preparedCommand.append(' ').append(quotePreparedWindowsArgument(argument));
            appendPreparedCxbxrDebugMode(preparedCommand);
            return preparedCommand.toString();
        }

        String cmdArgs = "";
        String execPath = null;
        String execArgs = "";

        if (shortcut != null) {
            execArgs = shortcut.getExtra("execArgs");
            execArgs = !execArgs.isEmpty() ? " "+execArgs : "";

            if (shortcut.isLinkPath()) {
                cmdArgs = "\""+shortcut.path+"\""+execArgs;
            }
            else execPath = shortcut.path;
        }
        else {
            Intent intent = getIntent();
            if (intent.hasExtra("exec_path")) {
                execPath = WineUtils.unixToDOSPath(intent.getStringExtra("exec_path"), container);

                if (execPath.endsWith(".lnk")) {
                    cmdArgs = "\""+execPath+"\"";
                    execPath = null;
                }
            }
        }

        if (execPath != null) {
            String execDir = FileUtils.getDirname(execPath);
            String filename = FileUtils.getName(execPath);
            int dotIndex, spaceIndex;
            if ((dotIndex = filename.lastIndexOf(".")) != -1 && (spaceIndex = filename.indexOf(" ", dotIndex)) != -1) {
                execArgs = filename.substring(spaceIndex+1)+execArgs;
                filename = filename.substring(0, spaceIndex);
            }
            cmdArgs = "/dir "+StringUtils.escapeDOSPath(execDir)+" \""+filename+"\""+execArgs;
        }

        if (cmdArgs.isEmpty()) cmdArgs = "/dir C:\\windows \"wfm.exe\"";

        if (overrideEnvVars != null && overrideEnvVars.has("EXTRA_EXEC_ARGS")) {
            cmdArgs += " "+overrideEnvVars.get("EXTRA_EXEC_ARGS");
            overrideEnvVars.remove("EXTRA_EXEC_ARGS");
        }
        return "C:\\windows\\winhandler.exe "+cmdArgs;
    }

    /**
     * TPUI starts cxbxr-ldr directly instead of opening its desktop GUI. The
     * GUI normally copies KrnlDebugMode from settings.ini to the /dm argument
     * used by the quick-reboot emulator child, so a prepared launch must do
     * that explicitly. Renderer troubleshooting is deliberately requested
     * with the low-volume render trace instead of /full-trace: the latter can
     * flood Winlator's live debug dialog and perturb or terminate an otherwise
     * healthy Android session. Supplying zero is intentional: it also
     * overrides a stale command-line value without relying on the managed
     * settings copy.
     */
    private void appendPreparedCxbxrDebugMode(StringBuilder command) {
        if (!isPreparedCxbxrLaunch())
            return;
        if (preparedWindowsLaunch.debugLoggingEnabled) {
            command.append(
                " /dm 2 /df C:\\teknoparrot-cxbxr-kernel-debug.txt" +
                " /render-trace");
        }
        else {
            command.append(" /dm 0");
        }
    }

    private boolean isPreparedBridge64Bit() {
        if (preparedWindowsLaunch == null)
            return false;
        String libraryDirectory = preparedWindowsLaunch.libraryDirectory;
        String executable = preparedWindowsLaunch.executable;
        String normalizedExecutable = executable != null
            ? executable.replace('/', '\\').toLowerCase(java.util.Locale.ROOT)
            : "";
        return (libraryDirectory != null &&
                (libraryDirectory.toLowerCase(java.util.Locale.ROOT).contains("win64") ||
                 libraryDirectory.toLowerCase(java.util.Locale.ROOT).endsWith("\\x64"))) ||
            normalizedExecutable.contains("\\openparrotwin64\\") ||
            normalizedExecutable.endsWith("\\openparrotloader64.exe") ||
            normalizedExecutable.endsWith("\\elfloader_x64.exe");
    }

    private boolean isPreparedElfLoaderLaunch() {
        if (preparedWindowsLaunch == null)
            return false;
        String executable = preparedWindowsLaunch.executable;
        if (executable == null)
            return false;
        String normalized = executable.replace('/', '\\')
            .toLowerCase(java.util.Locale.ROOT);
        return normalized.endsWith("\\elfldr2\\elfloader.exe") ||
            normalized.endsWith("\\elfldr2\\x64\\elfloader_x64.exe");
    }

    private boolean isMarioKartDxPreparedLaunch() {
        if (preparedWindowsLaunch == null)
            return false;
        String gameExecutable = findPreparedGameExecutable();
        return gameExecutable != null && gameExecutable.replace('/', '\\')
            .toLowerCase(java.util.Locale.ROOT)
            .endsWith("\\mk_agp3_final.exe");
    }

    private boolean isCrazySpeedPreparedLaunch() {
        if (preparedWindowsLaunch == null)
            return false;
        String gameExecutable = findPreparedGameExecutable();
        return gameExecutable != null && gameExecutable.replace('/', '\\')
            .toLowerCase(java.util.Locale.ROOT)
            .endsWith("\\client_r.exe");
    }

    private boolean isKofXiiiPreparedLaunch() {
        if (preparedWindowsLaunch == null)
            return false;
        String gameExecutable = findPreparedGameExecutable();
        if (gameExecutable == null)
            return false;
        String normalized = gameExecutable.replace('/', '\\')
            .toLowerCase(java.util.Locale.ROOT);
        return normalized.endsWith("\\game.exe") &&
            normalized.contains("\\the king of fighters xiii (2010)");
    }

    private boolean isAkaiKatanaPreparedLaunch() {
        if (preparedWindowsLaunch == null)
            return false;
        String gameExecutable = findPreparedGameExecutable();
        if (gameExecutable == null)
            return false;
        String normalized = gameExecutable.replace('/', '\\')
            .toLowerCase(java.util.Locale.ROOT);
        return normalized.endsWith("\\game.exe") &&
            normalized.contains("\\akai katana shin ");
    }

    private String findPreparedGameExecutable() {
        boolean allowExtensionlessElf = isPreparedElfLoaderLaunch();
        for (int index = preparedWindowsLaunch.arguments.length - 1; index >= 0; index--) {
            String candidate = preparedWindowsLaunch.arguments[index];
            if (candidate == null || !candidate.matches("(?i)^[CDE]:\\\\[^/\"]+$"))
                continue;
            if (allowExtensionlessElf || candidate.toLowerCase(java.util.Locale.ROOT).endsWith(".exe"))
                return candidate;
        }
        return null;
    }

    /**
     * Use Box64's fast preset only for normal prepared OpenParrot play;
     * troubleshooting and all other launches retain the container's
     * compatibility-oriented setting.
     */
    private String selectBox64Preset() {
        String configuredPreset = shortcut != null
            ? shortcut.getExtra("box64Preset", container.getBox64Preset())
            : container.getBox64Preset();

        // WMMT uses the fast preset, with its two unsafe translation features
        // overridden in the recipe-specific environment above. Returning the
        // full stability preset here made the game and its movies run in slow
        // motion even with diagnostics disabled.
        if (preparedWindowsLaunch != null &&
            isWmmtCompatibilityPreset(preparedWindowsLaunch.compatibilityPreset))
            return Box64Preset.PERFORMANCE;

        // CXBXR translates the emulated Xbox CPU and D3D8 workload inside a
        // 32-bit Wine process. Virtua Cop 3 and Ghost Squad need Box64's fast
        // path to get beyond their startup/attract transition, but applying it
        // globally makes the original OutRun 2 fail in Wine at 0x7B198C44.
        // Keep the fast path explicit in the proven title recipes and retain
        // the configured compatibility preset for every other CXBXR title.
        if (preparedWindowsLaunch != null &&
            isPreparedCxbxrLaunch() &&
            isPreparedCxbxrPerformanceTitle())
            return Box64Preset.PERFORMANCE;

        // Battle Pod needs the performance preset for UE3 rendering. The
        // recipe-specific environment above retains conservative call/return,
        // memory-order, and block-size guards for its post-Start transition.
        if (preparedWindowsLaunch != null &&
            "star-wars".equals(preparedWindowsLaunch.compatibilityPreset))
            return Box64Preset.PERFORMANCE;

        // Big Buck Hunter World reaches its OpenGL attract loop with the
        // container's compatibility preset, but remains alive behind a black
        // X11 surface when ElfLoader is switched to the aggressive preset.
        // Keep this exception tied to its dedicated controls/recipe id so the
        // other Raw Thrills ELF titles retain their verified fast path.
        if (isBigBuckWorldPreparedLaunch())
            return configuredPreset;

        // ElfLoader maps and executes the arcade ELF inside its Windows host
        // process. Production sessions need the fast dynarec preset just as
        // OpenParrot sessions do; diagnostics retain the conservative preset.
        if (preparedWindowsLaunch != null &&
            isPreparedElfLoaderLaunch() &&
            !preparedWindowsLaunch.debugLoggingEnabled)
            return Box64Preset.PERFORMANCE;

        if (preparedWindowsLaunch == null ||
            !preparedWindowsLaunch.productionBridge ||
            preparedWindowsLaunch.debugLoggingEnabled ||
            preparedWindowsLaunch.arguments.length == 0)
            return configuredPreset;

        // Arcana Heart 2's eX-Board executable depends on the configured
        // compatibility preset (notably x87 precision and safer dynarec
        // ordering). The aggressive OpenParrot preset exits during startup.
        for (String argument : preparedWindowsLaunch.arguments) {
            if (argument != null &&
                argument.replace('/', '\\')
                    .toLowerCase(java.util.Locale.ROOT)
                    .endsWith("\\ah2.exe"))
                return configuredPreset;
        }

        // KOF XIII's DirectShow presenter and D3D9 game renderer complete the
        // movie transition with the configured compatibility preset. The
        // aggressive production preset leaves the game rendering at 60 FPS
        // behind a stale gray presenter surface and later exits. Keep this
        // exception tied to the exact imported game executable.
        if (isKofXiiiPreparedLaunch())
            return configuredPreset;

        // Tekken 7 reaches a clean 60 FPS D3D11 render with the configured
        // Box64 preset, while the aggressive production preset leaves the
        // exact shipping executable alive behind a permanently black surface.
        // Keep this exception executable-scoped so other OpenParrot x64 games
        // continue to receive their established performance preset.
        for (String argument : preparedWindowsLaunch.arguments) {
            if (argument != null &&
                argument.replace('/', '\\')
                    .toLowerCase(java.util.Locale.ROOT)
                    .endsWith("\\tekkengame-win64-shipping.exe"))
                return configuredPreset;
        }

        String runtimeArgument = preparedWindowsLaunch.arguments[0];
        return runtimeArgument != null &&
            runtimeArgument.toLowerCase(java.util.Locale.ROOT).contains("openparrot")
                ? Box64Preset.PERFORMANCE
                : configuredPreset;
    }

    private boolean isBigBuckWorldPreparedLaunch() {
        if (preparedWindowsLaunch == null || !isPreparedElfLoaderLaunch())
            return false;
        String gameExecutable = findPreparedGameExecutable();
        if (gameExecutable == null)
            return false;
        String normalized = gameExecutable.replace('/', '\\')
            .toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("\\big buck world ") &&
            normalized.endsWith("\\game");
    }

    private boolean validatePreparedWindowsLaunch() {
        if (preparedWindowsLaunch == null)
            return true;
        try {
            return isPreparedDosPathWithinDrive(preparedWindowsLaunch.executable, false) &&
                isPreparedDosPathWithinDrive(preparedWindowsLaunch.workingDirectory, true) &&
                (preparedWindowsLaunch.libraryDirectory == null ||
                 isPreparedDosPathWithinDrive(preparedWindowsLaunch.libraryDirectory, true));
        }
        catch (IOException ignored) {
            return false;
        }
    }

    /**
     * GTI Club ships the exact 32-bit XACT COM server required by its sound
     * runtime beside the game executable. Register that immutable local DLL in
     * the active Wine prefix once, instead of replacing it with a global copy.
     */
    private TeknoParrotWinePreflightComponent createLocalXactWinePreflight(
            EnvVars sourceEnvVars, String box64Preset) {
        String gameExecutable = findPreparedGameExecutable();
        if (gameExecutable == null) {
            Log.e(TAG, "The XACT preflight has no validated game executable.");
            return null;
        }

        try {
            if (!isPreparedDosPathWithinDrive(gameExecutable, false))
                throw new IOException("The XACT game executable left its declared Wine drive.");
            File gameFile = new File(WineUtils.dosToUnixPath(gameExecutable, container))
                .getCanonicalFile();
            File directory = gameFile.getParentFile();
            File xactDll = null;
            File[] siblings = directory.listFiles();
            if (siblings != null) {
                for (File sibling : siblings) {
                    if (sibling.isFile() && sibling.length() != 0 &&
                        sibling.getName().equalsIgnoreCase("xactengine2_10.dll")) {
                        xactDll = sibling;
                        break;
                    }
                }
            }
            if (xactDll == null)
                throw new IOException(
                    "xactengine2_10.dll is missing beside the game executable.");

            File winePrefix = new File(rootFS.getRootDir(), RootFS.WINEPREFIX);
            File serviceDirectory = new File(winePrefix, "drive_c/teknoparrot-service");
            if (!serviceDirectory.isDirectory() && !serviceDirectory.mkdirs())
                throw new IOException("Could not create the private XACT staging directory.");
            File stagedXactDll = new File(serviceDirectory, xactDll.getName());
            if (!FileUtils.copy(xactDll, stagedXactDll) ||
                !stagedXactDll.isFile() || stagedXactDll.length() != xactDll.length())
                throw new IOException("Could not stage the exact local XACT COM server.");
            File marker = new File(
                serviceDirectory, "xactengine2-10-v3.flag");
            TeknoParrotWinePreflightComponent component =
                new TeknoParrotWinePreflightComponent(
                    stagedXactDll.getName(), marker, true, serviceDirectory);
            component.setGuestExecutable(
                "wine C:\\windows\\syswow64\\regsvr32.exe /s " +
                "C:\\teknoparrot-service\\" + stagedXactDll.getName());
            EnvVars preflightEnvVars = new EnvVars();
            preflightEnvVars.putAll(sourceEnvVars);
            component.setEnvVars(preflightEnvVars);
            component.setBox64Preset(box64Preset);
            return component;
        }
        catch (Exception error) {
            Log.e(TAG, "Could not prepare the local XACT COM registration.", error);
            return null;
        }
    }

    /**
     * Chase H.Q. 2 requires Microsoft's 32-bit WMV9 VCM filter.  Running the
     * dump's installer inside the active container preserves Wine's registry
     * registration and avoids changing or deleting the user's local dinput8.
     */
    private TeknoParrotWinePreflightComponent createChaseHq2WinePreflight(
            EnvVars sourceEnvVars, String box64Preset) {
        String gameExecutable = null;
        for (int index = preparedWindowsLaunch.arguments.length - 1; index >= 0; index--) {
            String candidate = preparedWindowsLaunch.arguments[index];
            if (candidate != null && candidate.matches("(?i)^[CDE]:\\\\[^/\"]+\\.exe$")) {
                gameExecutable = candidate;
                break;
            }
        }
        if (gameExecutable == null) {
            Log.e(TAG, "Chase H.Q. 2 has no validated game executable for codec setup.");
            return null;
        }

        try {
            if (!isPreparedDosPathWithinDrive(gameExecutable, false))
                throw new IOException("The Chase H.Q. 2 executable left its declared Wine drive.");
            File gameFile = new File(WineUtils.dosToUnixPath(gameExecutable, container))
                .getCanonicalFile();
            File directory = gameFile.getParentFile();
            File installer = null;
            File[] siblings = directory.listFiles();
            if (siblings != null) {
                for (File sibling : siblings) {
                    if (sibling.isFile() &&
                        sibling.getName().equalsIgnoreCase("wmv9VCMsetup.exe")) {
                        installer = sibling;
                        break;
                    }
                }
            }
            if (installer == null || installer.length() == 0)
                throw new IOException("wmv9VCMsetup.exe is missing beside the game executable.");

            String installerDosPath = FileUtils.getDirname(gameExecutable) +
                "\\" + installer.getName();
            File winePrefix = new File(rootFS.getRootDir(), RootFS.WINEPREFIX);
            File marker = new File(
                winePrefix, "drive_c/teknoparrot-service/wmv9vcm-v1.flag");
            TeknoParrotWinePreflightComponent component =
                new TeknoParrotWinePreflightComponent(
                    "WMV9VCM.dll",
                    marker,
                    new File(winePrefix, "drive_c/windows/syswow64"),
                    new File(winePrefix, "drive_c/windows/system32"));
            // ProcessHelper launches Box64 directly rather than through a
            // shell. Its escaped-space form produces one argv element without
            // retaining literal quote characters in Wine's executable path.
            component.setGuestExecutable(
                "wine " + installerDosPath.replace(" ", "\\ ") + " /Q:A /R:N");
            EnvVars preflightEnvVars = new EnvVars();
            preflightEnvVars.putAll(sourceEnvVars);
            component.setEnvVars(preflightEnvVars);
            component.setBox64Preset(box64Preset);
            return component;
        }
        catch (Exception error) {
            Log.e(TAG, "Could not prepare the Chase H.Q. 2 WMV9 VCM installer.", error);
            return null;
        }
    }

    private boolean ensurePreparedWindowsPathBootstrap() {
        if (preparedWindowsLaunch == null || preparedWindowsLaunch.libraryDirectory == null)
            return true;

        File destination = new File(
            rootFS.getRootDir(),
            RootFS.WINEPREFIX+"/drive_c/windows/teknoparrot-path-bootstrap.exe");
        FileUtils.copy(this, PREPARED_WINDOWS_BOOTSTRAP_ASSET, destination);
        return destination.isFile() &&
            destination.length() == FileUtils.getSize(this, PREPARED_WINDOWS_BOOTSTRAP_ASSET);
    }

    /**
     * CXBXR keeps its GUI logging policy in TeknoParrot/settings.ini rather
     * than inheriting Wine's environment. Mirror TPUI's per-game logging
     * toggle there; appendPreparedCxbxrDebugMode separately passes the kernel
     * policy to the quick-reboot emulator child. Chihiro board identity and
     * Android kernel scheduling workarounds are stored beside the loader in
     * the shared beta.ini. Set every title-scoped value for each prepared
     * launch so VC3 and Type-3 settings cannot contaminate the next session.
     */
    private boolean ensurePreparedCxbxrSettings() {
        if (!isPreparedCxbxrLaunch())
            return true;
        try {
            File loader = new File(WineUtils.dosToUnixPath(
                preparedWindowsLaunch.executable, container)).getCanonicalFile();
            if (!loader.isFile() ||
                !loader.getName().equalsIgnoreCase("cxbxr-ldr.exe"))
                throw new IOException("The prepared CXBXR loader is missing.");
            File settings = new File(
                new File(loader.getParentFile(), "TeknoParrot"),
                "settings.ini").getCanonicalFile();
            if (!settings.getParentFile().getParentFile().equals(
                    loader.getParentFile()))
                throw new IOException("The CXBXR settings path left its runtime directory.");

            String source = FileUtils.readString(settings);
            if (source == null ||
                !source.matches("(?sm).*^CxbxDebugMode\\s*=.*$.*") ||
                !source.matches("(?sm).*^KrnlDebugMode\\s*=.*$.*") ||
                !source.matches("(?sm).*^LogLevel\\s*=.*$.*") ||
                !source.matches("(?sm).*^LoggedModules\\s*=.*$.*") ||
                !source.matches("(?sm).*^LogPopupTestCase\\s*=.*$.*"))
                throw new IOException("The CXBXR settings file is incomplete.");
            source = source.replace("\r\n", "\n").replace('\r', '\n');
            String debugMode = preparedWindowsLaunch.debugLoggingEnabled
                ? "0x1"
                : "0x0";
            String updated = source
                .replaceAll(
                    "(?m)^CxbxDebugMode\\s*=.*$",
                    "CxbxDebugMode = " + debugMode)
                .replaceAll(
                    "(?m)^KrnlDebugMode\\s*=.*$",
                    "KrnlDebugMode = " + debugMode)
                .replaceAll(
                    "(?m)^LogLevel\\s*=.*$",
                    "LogLevel = " +
                        (preparedWindowsLaunch.debugLoggingEnabled ? "0" : "1"))
                .replaceAll("(?m)^LoggedModules\\s*=.*\\n?", "");
            String loggedModules = preparedWindowsLaunch.debugLoggingEnabled
                ? "LoggedModules = 0x00007000\n" +
                    "LoggedModules = 0x00000780\n" +
                    "LoggedModules = 0x00000000"
                : "LoggedModules = 0x0";
            updated = updated.replaceFirst(
                "(?m)^(LogPopupTestCase\\s*=.*)$",
                loggedModules + "\n$1");
            if (!updated.equals(source) &&
                !FileUtils.writeStringAtomic(settings, updated))
                throw new IOException("Could not save the CXBXR logging policy.");

            File betaSettings = new File(
                loader.getParentFile(), "beta.ini").getCanonicalFile();
            if (!betaSettings.getParentFile().equals(loader.getParentFile()))
                throw new IOException("The CXBXR beta settings path left its runtime directory.");
            String betaSource = FileUtils.readString(betaSettings);
            if (betaSource == null) {
                if (betaSettings.exists())
                    throw new IOException("Could not read the CXBXR beta settings.");
                betaSource = "";
            }
            boolean chihiroType3 = "cxbxr-chihiro-type3".equals(
                preparedWindowsLaunch.compatibilityPreset);
            boolean cxbxrWmmt = "cxbxr-wmmt-yacard".equals(
                preparedWindowsLaunch.compatibilityPreset);
            String boardIdentity = chihiroType3 ? "1" : "0";
            boolean virtuaCop3 = isPreparedCxbxrVirtuaCop3Title();
            boolean crazyTaxi = isPreparedCxbxrCrazyTaxiTitle();
            boolean cooperativeSelfSuspend =
                isPreparedCxbxrCooperativeSelfSuspendTitle();
            String betaUpdated = betaSource;
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "apc_try_lock", virtuaCop3 ? "0" : "1");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "satisfy_wait_apc_flags", "1");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "wait_list_toctou_recheck",
                virtuaCop3 ? "0" : "1");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "notification_event_wait_recheck",
                virtuaCop3 ? "1" : "0");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "cooperative_self_suspend",
                cooperativeSelfSuspend ? "1" : "0");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "timer_try_lock", virtuaCop3 ? "0" : "1");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "precise_sleep_timer",
                virtuaCop3 ? "0" : "1");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "periodic_irq10", virtuaCop3 ? "0" : "1");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "llong_min_timeout_fix", "1");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "llong_min_timeout_sleep_ms", "0");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "get_now_lock", virtuaCop3 ? "0" : "1");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "atomic_interrupts", virtuaCop3 ? "0" : "1");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "system_events_other_affinity", "0");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "system_events_normal_priority", "0");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "timer_exp_pointer_guard",
                virtuaCop3 ? "0" : "1");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "timer_exp_max_expired", "0");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "timer_exp_max_ticks", "0");
            // VC3's fixed-function skinned meshes use the legacy HLE
            // SetTransform mirror. Reading their blend palette from the NV2A
            // constants under Wine/Box32 drops body sections (notably arms).
            // Reset the shared runtime explicitly for every other title so
            // games that update the NV2A palette directly keep that support.
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "ff_nv2a_blend_matrices",
                virtuaCop3 ? "0" : "1");
            // Reset this shared runtime switch for every title. It remains a
            // useful troubleshooting A/B, but Wine's native D3D9 fixed-function
            // path drops OutRun's HUD and does not fix its white 3D surface.
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "ff_hlsl_vertex_shader",
                "1");
            // Crazy Taxi's Android scheduler needs the title-local CRI assist
            // only for its initial file-20 logo stream. The core records the
            // AFS file id and leaves all multiplexed attract streams on their
            // native worker path. Reset the shared runtime for every other
            // title so the workaround cannot leak between sessions.
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "ct_cri_drive_movie_server",
                crazyTaxi ? "1" : "0");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "wmmt_device_poll_yield_ms",
                cxbxrWmmt ? "1" : "0");
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "wmmt_gamepad_init_bypass",
                cxbxrWmmt ? "1" : "0");
            if (!preparedWindowsLaunch.debugLoggingEnabled) {
                betaUpdated = upsertIniValue(
                    betaUpdated, "beta", "full_trace", "0");
                betaUpdated = upsertIniValue(
                    betaUpdated, "beta", "scheduler_io_trace", "0");
                betaUpdated = upsertIniValue(
                    betaUpdated, "beta", "scheduler_io_wait_trace", "0");
                betaUpdated = upsertIniValue(
                    betaUpdated, "beta", "scheduler_io_thread_trace", "0");
                betaUpdated = upsertIniValue(
                    betaUpdated, "beta", "scheduler_io_event_trace", "0");
                betaUpdated = upsertIniValue(
                    betaUpdated, "beta", "scheduler_io_file_trace", "0");
                betaUpdated = upsertIniValue(
                    betaUpdated, "beta", "scheduler_io_probe_address", "0");
                betaUpdated = upsertIniValue(
                    betaUpdated, "beta", "scheduler_io_cri_watch_base", "0");
                betaUpdated = upsertIniValue(
                    betaUpdated, "beta", "scheduler_io_cri_watch_count", "0");
            }
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "mb_board_type", boardIdentity);
            betaUpdated = upsertIniValue(
                betaUpdated, "beta", "mb_dimm_size", boardIdentity);
            if (!betaUpdated.equals(betaSource) &&
                !FileUtils.writeStringAtomic(betaSettings, betaUpdated))
                throw new IOException("Could not save the CXBXR runtime profile.");
            if (preparedWindowsLaunch.debugLoggingEnabled)
                Log.i(TAG, "CXBXR console and kernel logging enabled for troubleshooting.");
            return true;
        }
        catch (Exception error) {
            Log.e(TAG, "Could not prepare CXBXR settings.", error);
            return false;
        }
    }

    private static String upsertIniValue(
        String source,
        String sectionName,
        String key,
        String value) {
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        ArrayList<String> lines = new ArrayList<>();
        for (String line : normalized.split("\n", -1))
            lines.add(line);
        if (lines.size() == 1 && lines.get(0).isEmpty())
            lines.clear();

        String sectionHeader = "[" + sectionName + "]";
        int sectionStart = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (sectionHeader.equalsIgnoreCase(lines.get(index).trim())) {
                sectionStart = index;
                break;
            }
        }
        if (sectionStart < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty())
                lines.add("");
            lines.add(sectionHeader);
            lines.add(key + "=" + value);
        }
        else {
            int sectionEnd = lines.size();
            for (int index = sectionStart + 1; index < lines.size(); index++) {
                String candidate = lines.get(index).trim();
                if (candidate.startsWith("[") && candidate.endsWith("]")) {
                    sectionEnd = index;
                    break;
                }
            }

            boolean replaced = false;
            for (int index = sectionStart + 1; index < sectionEnd; index++) {
                String candidate = lines.get(index).trim();
                int equals = candidate.indexOf('=');
                if (equals >= 0 &&
                    key.equalsIgnoreCase(candidate.substring(0, equals).trim())) {
                    lines.set(index, key + "=" + value);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                while (sectionEnd > sectionStart + 1 &&
                    lines.get(sectionEnd - 1).isEmpty())
                    sectionEnd--;
                lines.add(sectionEnd, key + "=" + value);
            }
        }

        String result = String.join("\r\n", lines);
        return result.endsWith("\r\n") ? result : result + "\r\n";
    }

    /**
     * Some 32-bit arcade executables reserve roughly 800 MiB in one block before
     * their renderer starts. Wine's normal non-LAA address space can already be
     * fragmented by WoW64 modules at that point. Keep the dump immutable: make a
     * sibling launch copy, set only IMAGE_FILE_LARGE_ADDRESS_AWARE, and tell
     * OpenParrot to normalize that flag when it computes its first-page CRC.
     */
    private boolean ensurePreparedLargeAddressAwareExecutable() {
        String preset = preparedWindowsLaunch.compatibilityPreset;
        if (!"large-address-aware".equals(preset) &&
            !"large-address-aware-ddraw".equals(preset) &&
            !"dirty-driving-fullscreen".equals(preset))
            return true;

        int executableArgument = -1;
        for (int index = preparedWindowsLaunch.arguments.length - 1; index >= 0; index--) {
            String candidate = preparedWindowsLaunch.arguments[index];
            if (candidate != null && candidate.matches("(?i)^[CDE]:\\\\[^/\"]+\\.exe$")) {
                executableArgument = index;
                break;
            }
        }
        if (executableArgument < 0)
            return false;

        String gameExecutable = preparedWindowsLaunch.arguments[executableArgument];
        try {
            if (!isPreparedDosPathWithinDrive(gameExecutable, false))
                throw new IOException("The LAA executable left its declared Wine drive.");
            File source = new File(WineUtils.dosToUnixPath(gameExecutable, container))
                .getCanonicalFile();
            if (!source.isFile())
                throw new IOException("The LAA source executable is missing.");

            int sourceCharacteristics = readPeCharacteristics(source);
            if ((sourceCharacteristics & 0x20) != 0) {
                envVars.remove("TP_CRC_NORMALIZE_LAA");
                return true;
            }

            String sourceName = source.getName();
            int extension = sourceName.toLowerCase(java.util.Locale.ROOT).lastIndexOf(".exe");
            // Dirty Drivin identifies and locates companion data from the
            // original sdaemon.exe basename. Keep its private LAA copy in a
            // subdirectory just like the generic exact-name preset while the
            // title-specific preset also reserves the high address range.
            boolean preserveExecutableName = "large-address-aware".equals(preset) ||
                "dirty-driving-fullscreen".equals(preset);
            String stagedName = preserveExecutableName
                ? sourceName
                : sourceName.substring(0, extension) +
                    ".teknoparrot-laa" + sourceName.substring(extension);
            File stagedDirectory = preserveExecutableName
                ? new File(source.getParentFile(), ".teknoparrot-laa")
                : source.getParentFile();
            if (!stagedDirectory.isDirectory() && !stagedDirectory.mkdirs())
                throw new IOException("Could not create the private LAA launch directory.");
            File staged = new File(stagedDirectory, stagedName);
            if (!FileUtils.copy(source, staged))
                throw new IOException("Could not create the private LAA launch copy.");
            writePeCharacteristics(staged, sourceCharacteristics | 0x20);
            if (readPeCharacteristics(staged) != (sourceCharacteristics | 0x20) ||
                staged.length() != source.length())
                throw new IOException("The private LAA launch copy did not validate.");
            if (!staged.setLastModified(source.lastModified()))
                Log.w("TeknoParrotLaunch", "Could not preserve the LAA copy timestamp.");

            String directory = FileUtils.getDirname(gameExecutable) +
                (preserveExecutableName ? "\\.teknoparrot-laa" : "");
            preparedWindowsLaunch.arguments[executableArgument] =
                directory + "\\" + stagedName;
            envVars.put("TP_CRC_NORMALIZE_LAA", "1");
            if ("dirty-driving-fullscreen".equals(preset))
                envVars.put(
                    "TP_GAME_WORKING_DIRECTORY",
                    FileUtils.getDirname(gameExecutable));
            if (preparedWindowsLaunch.debugLoggingEnabled)
                Log.i("TeknoParrotLaunch", "Prepared LAA launch copy for " + sourceName + '.');
            return true;
        }
        catch (Exception error) {
            Log.e("TeknoParrotLaunch", "Could not prepare the LAA game executable.", error);
            return false;
        }
    }

    /**
     * Guilty Gear Xrd's apm_x86.dll starts and joins its input worker from
     * DllMain. Wine's experimental WoW64 worker immediately needs
     * ntdll.loader_section, which is still owned by the process-attach thread,
     * so both threads wait forever before the executable entry point.
     *
     * Keep the game dump immutable. A private executable directory contains an
     * equally private apm_x86 copy whose user DllMain returns TRUE without
     * starting the cabinet worker. The DLL's real PE entry point remains intact
     * so its security cookie, TLS, CRT, and static constructors still initialize.
     * Keeping the canonical module name also makes OpenParrot's later
     * LoadLibrary("apm_x86.dll") reuse this module instead of loading the unsafe
     * original a second time. OpenParrot replaces the APM3 exports before game
     * code uses them, so the original cabinet worker is neither required nor
     * allowed to race the emulated implementation.
     */
    private boolean ensurePreparedApm3StartupExecutable() {
        if (!"post-start-remote-thread".equals(
                preparedWindowsLaunch.compatibilityPreset))
            return true;

        int executableArgument = -1;
        for (int index = preparedWindowsLaunch.arguments.length - 1; index >= 0; index--) {
            String candidate = preparedWindowsLaunch.arguments[index];
            if (candidate != null && candidate.matches("(?i)^[CDE]:\\\\[^/\"]+\\.exe$")) {
                executableArgument = index;
                break;
            }
        }
        if (executableArgument < 0)
            return false;

        String gameExecutable = preparedWindowsLaunch.arguments[executableArgument];
        try {
            if (!isPreparedDosPathWithinDrive(gameExecutable, false))
                throw new IOException("The APM3 executable left its declared Wine drive.");
            File sourceExecutable =
                new File(WineUtils.dosToUnixPath(gameExecutable, container))
                    .getCanonicalFile();
            if (!sourceExecutable.isFile())
                throw new IOException("The APM3 source executable is missing.");

            File sourceApm = new File(sourceExecutable.getParentFile(), "apm_x86.dll")
                .getCanonicalFile();
            if (!sourceApm.isFile())
                throw new IOException("The title's apm_x86.dll is missing.");

            File sourceDirectory = sourceExecutable.getParentFile();
            File sourceDirectoryParent = sourceDirectory.getParentFile();
            if (sourceDirectoryParent == null)
                throw new IOException("The APM3 executable has no parent directory.");
            File stagedDirectory = new File(
                sourceDirectoryParent,
                sourceDirectory.getName() + ".teknoparrot-apm3");
            if (!stagedDirectory.isDirectory() && !stagedDirectory.mkdirs())
                throw new IOException("Could not create the private APM3 launch directory.");

            File stagedExecutable =
                new File(stagedDirectory, sourceExecutable.getName());
            File stagedApm = new File(stagedDirectory, "apm_x86.dll");
            if (!FileUtils.copy(sourceExecutable, stagedExecutable) ||
                !FileUtils.copy(sourceApm, stagedApm))
                throw new IOException("Could not create the private APM3 launch files.");

            patchGuiltyGearApm3UserDllMainReturnTrue(stagedApm);
            if (stagedExecutable.length() != sourceExecutable.length() ||
                stagedApm.length() != sourceApm.length())
                throw new IOException("The private APM3 launch files changed size.");

            if (!stagedExecutable.setLastModified(sourceExecutable.lastModified()))
                Log.w("TeknoParrotLaunch",
                    "Could not preserve the APM3 executable timestamp.");
            if (!stagedApm.setLastModified(sourceApm.lastModified()))
                Log.w("TeknoParrotLaunch", "Could not preserve the APM3 DLL timestamp.");

            String originalDirectory = FileUtils.getDirname(gameExecutable);
            String originalDirectoryParent = FileUtils.getDirname(originalDirectory);
            preparedWindowsLaunch.arguments[executableArgument] =
                originalDirectoryParent + "\\" + stagedDirectory.getName() + "\\" +
                    sourceExecutable.getName();
            envVars.put("TP_GAME_WORKING_DIRECTORY", originalDirectory);
            if (preparedWindowsLaunch.debugLoggingEnabled)
                Log.i("TeknoParrotLaunch",
                    "Prepared loader-lock-safe APM3 launch copies for " +
                        sourceExecutable.getName() + '.');
            return true;
        }
        catch (Exception error) {
            Log.e("TeknoParrotLaunch",
                "Could not prepare the loader-lock-safe APM3 executable.", error);
            return false;
        }
    }

    private static void patchGuiltyGearApm3UserDllMainReturnTrue(File dll)
            throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(dll, "rw")) {
            if (file.length() < 0x100)
                throw new IOException("The APM3 DLL is not a valid PE image.");
            file.seek(0);
            if (file.readUnsignedByte() != 'M' || file.readUnsignedByte() != 'Z')
                throw new IOException("The APM3 DLL has no DOS header.");
            file.seek(0x3c);
            long peOffset = readLittleEndianDword(file);
            if (peOffset <= 0 || peOffset + 24 > file.length())
                throw new IOException("The APM3 DLL has an invalid PE offset.");
            file.seek(peOffset);
            if (file.readUnsignedByte() != 'P' || file.readUnsignedByte() != 'E' ||
                file.readUnsignedByte() != 0 || file.readUnsignedByte() != 0)
                throw new IOException("The APM3 DLL has no PE signature.");

            file.seek(peOffset + 6);
            int sectionCount =
                file.readUnsignedByte() | (file.readUnsignedByte() << 8);
            file.seek(peOffset + 20);
            int optionalHeaderSize =
                file.readUnsignedByte() | (file.readUnsignedByte() << 8);
            long sectionTable = peOffset + 24 + optionalHeaderSize;
            final long userDllMainRva = 0x11640L;
            long userDllMainOffset = -1;
            for (int section = 0; section < sectionCount; section++) {
                long sectionOffset = sectionTable + (section * 40L);
                if (sectionOffset + 40 > file.length())
                    throw new IOException("The APM3 DLL section table is truncated.");
                file.seek(sectionOffset + 8);
                long virtualSize = readLittleEndianDword(file);
                long virtualAddress = readLittleEndianDword(file);
                long rawSize = readLittleEndianDword(file);
                long rawOffset = readLittleEndianDword(file);
                long mappedSize = Math.max(virtualSize, rawSize);
                if (userDllMainRva >= virtualAddress &&
                    userDllMainRva < virtualAddress + mappedSize) {
                    userDllMainOffset =
                        rawOffset + (userDllMainRva - virtualAddress);
                    break;
                }
            }
            byte[] expectedUserDllMain = new byte[] {
                0x55, (byte)0x8b, (byte)0xec, (byte)0x8b,
                0x45, 0x0c, (byte)0x83, (byte)0xe8,
                0x00, 0x74, 0x19
            };
            byte[] returnTrue = new byte[] {
                (byte)0xb8, 0x01, 0x00, 0x00, 0x00,
                (byte)0xc2, 0x0c, 0x00
            };
            if (userDllMainOffset < 0 ||
                userDllMainOffset + expectedUserDllMain.length > file.length())
                throw new IOException("The APM3 user DllMain is outside raw data.");
            file.seek(userDllMainOffset);
            for (byte expected : expectedUserDllMain) {
                if (file.readUnsignedByte() != (expected & 0xff))
                    throw new IOException(
                        "The APM3 user DllMain does not match the supported Guilty Gear build.");
            }
            file.seek(userDllMainOffset);
            file.write(returnTrue);
        }
    }

    private static int readPeCharacteristics(File executable) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(executable, "r")) {
            long offset = peCharacteristicsOffset(file);
            file.seek(offset);
            return file.readUnsignedByte() | (file.readUnsignedByte() << 8);
        }
    }

    private static void writePeCharacteristics(File executable, int value) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(executable, "rw")) {
            long offset = peCharacteristicsOffset(file);
            file.seek(offset);
            file.write(value & 0xff);
            file.write((value >>> 8) & 0xff);
        }
    }

    private static long peCharacteristicsOffset(RandomAccessFile file) throws IOException {
        if (file.length() < 0x40)
            throw new IOException("The LAA source is not a PE image.");
        file.seek(0);
        if (file.readUnsignedByte() != 'M' || file.readUnsignedByte() != 'Z')
            throw new IOException("The LAA source has no DOS signature.");
        file.seek(0x3c);
        long peOffset = (long) file.readUnsignedByte() |
            ((long) file.readUnsignedByte() << 8) |
            ((long) file.readUnsignedByte() << 16) |
            ((long) file.readUnsignedByte() << 24);
        if (peOffset <= 0 || peOffset + 24 > file.length())
            throw new IOException("The LAA source has an invalid PE offset.");
        file.seek(peOffset);
        if (file.readUnsignedByte() != 'P' || file.readUnsignedByte() != 'E' ||
            file.readUnsignedByte() != 0 || file.readUnsignedByte() != 0)
            throw new IOException("The LAA source has no PE signature.");
        return peOffset + 4 + 18;
    }

    /**
     * Wine 10's x86 D3D10 shader reflection exhausts both the one MiB stack
     * reserved by MK_AGP3_FINAL.exe and a four MiB trial reservation. The final
     * shader burst placed its SEH frame 0x2cc8 bytes below Wine's four MiB
     * stack limit. Preserve the dump: launch a private exact-name copy with a
     * sixteen MiB PE stack reservation so later shader batches retain useful
     * headroom instead of moving the guard-page failure.
     * TeknoParrot recognizes the patched executable's own CRC.  The bootstrap
     * and loader explicitly restore the original game working directory so Wine
     * can resolve the eOkao DLLs and the game's relative data without losing the
     * basename required by this title.
     */
    private boolean ensurePreparedMarioKartStackExecutable() {
        if (!isMarioKartDxPreparedLaunch())
            return true;

        int executableArgument = -1;
        for (int index = preparedWindowsLaunch.arguments.length - 1; index >= 0; index--) {
            String candidate = preparedWindowsLaunch.arguments[index];
            if (candidate != null && candidate.matches("(?i)^[CDE]:\\\\[^/\"]+\\.exe$")) {
                executableArgument = index;
                break;
            }
        }
        if (executableArgument < 0)
            return false;

        String gameExecutable = preparedWindowsLaunch.arguments[executableArgument];
        try {
            if (!isPreparedDosPathWithinDrive(gameExecutable, false))
                throw new IOException("The Mario Kart executable left its declared Wine drive.");
            File source = new File(WineUtils.dosToUnixPath(gameExecutable, container))
                .getCanonicalFile();
            if (!source.isFile())
                throw new IOException("The Mario Kart source executable is missing.");

            final long requiredStackReserve = 16L * 1024L * 1024L;
            long sourceStackReserve = readPe32StackReserve(source);
            if (sourceStackReserve >= requiredStackReserve) {
                envVars.remove("TP_GAME_WORKING_DIRECTORY");
                return true;
            }

            String sourceName = source.getName();
            File stagedDirectory = new File(source.getParentFile(), ".teknoparrot-stack");
            if (!stagedDirectory.isDirectory() && !stagedDirectory.mkdirs())
                throw new IOException("Could not create the private Mario Kart stack directory.");
            File staged = new File(stagedDirectory, sourceName);
            if (!FileUtils.copy(source, staged))
                throw new IOException("Could not create the private Mario Kart stack copy.");
            writePe32StackReserve(staged, requiredStackReserve);
            if (readPe32StackReserve(staged) != requiredStackReserve ||
                staged.length() != source.length())
                throw new IOException("The private Mario Kart stack copy did not validate.");
            if (!staged.setLastModified(source.lastModified()))
                Log.w("TeknoParrotLaunch", "Could not preserve the stack copy timestamp.");

            preparedWindowsLaunch.arguments[executableArgument] =
                FileUtils.getDirname(gameExecutable) + "\\.teknoparrot-stack\\" + sourceName;
            envVars.put("TP_GAME_WORKING_DIRECTORY", FileUtils.getDirname(gameExecutable));
            if (preparedWindowsLaunch.debugLoggingEnabled)
                Log.i("TeknoParrotLaunch", "Prepared 16 MiB stack launch copy for " +
                    source.getName() + '.');
            return true;
        }
        catch (Exception error) {
            Log.e("TeknoParrotLaunch", "Could not prepare the Mario Kart stack copy.", error);
            return false;
        }
    }

    /**
     * Some older arcade executables load assets relative to the process current
     * directory instead of the executable path. Keep that behavior opt-in so
     * existing recipes continue to start from the shared runtime directory.
     */
    private boolean ensurePreparedGameWorkingDirectory() {
        if (!"game-working-directory".equals(preparedWindowsLaunch.compatibilityPreset))
            return true;

        String gameExecutable = findPreparedGameExecutable();
        if (gameExecutable == null)
            return false;
        try {
            if (!isPreparedDosPathWithinDrive(gameExecutable, false))
                throw new IOException("The game executable left its declared Wine drive.");
            String workingDirectory = FileUtils.getDirname(gameExecutable);
            envVars.put("TP_GAME_WORKING_DIRECTORY", workingDirectory);
            if (preparedWindowsLaunch.debugLoggingEnabled)
                Log.i("TeknoParrotLaunch", "Using game working directory " +
                    workingDirectory + '.');
            return true;
        }
        catch (Exception error) {
            Log.e("TeknoParrotLaunch", "Could not prepare the game working directory.", error);
            return false;
        }
    }

    private static long readPe32StackReserve(File executable) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(executable, "r")) {
            file.seek(pe32StackReserveOffset(file));
            return readLittleEndianDword(file);
        }
    }

    private static void writePe32StackReserve(File executable, long value) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(executable, "rw")) {
            file.seek(pe32StackReserveOffset(file));
            for (int shift = 0; shift < 32; shift += 8)
                file.write((int)(value >>> shift) & 0xff);
        }
    }

    private static long pe32StackReserveOffset(RandomAccessFile file) throws IOException {
        long characteristicsOffset = peCharacteristicsOffset(file);
        long optionalHeaderOffset = characteristicsOffset + 2;
        if (optionalHeaderOffset + 76 > file.length())
            throw new IOException("The Mario Kart PE optional header is truncated.");
        file.seek(optionalHeaderOffset);
        int magic = file.readUnsignedByte() | (file.readUnsignedByte() << 8);
        if (magic != 0x10b)
            throw new IOException("The Mario Kart executable is not a 32-bit PE image.");
        return optionalHeaderOffset + 72;
    }

    private static long readLittleEndianDword(RandomAccessFile file) throws IOException {
        return (long)file.readUnsignedByte() |
            ((long)file.readUnsignedByte() << 8) |
            ((long)file.readUnsignedByte() << 16) |
            ((long)file.readUnsignedByte() << 24);
    }

    /** Applies the recipe's guest display policy without changing Windows/Linux profiles. */
    private boolean ensurePreparedDisplayIni() {
        String gameExecutable = findPreparedGameExecutable();
        if (gameExecutable == null)
            return true;

        try {
            if (!isPreparedDosPathWithinDrive(gameExecutable, false))
                throw new IOException("The game executable left its declared Wine drive.");
            File gameFile = new File(WineUtils.dosToUnixPath(gameExecutable, container))
                .getCanonicalFile();
            File directory = gameFile.getParentFile();
            File iniFile = new File(directory, "teknoparrot.ini");
            File[] siblings = directory.listFiles();
            if (siblings != null) {
                for (File sibling : siblings) {
                    if (sibling.isFile() &&
                        sibling.getName().equalsIgnoreCase("teknoparrot.ini")) {
                        iniFile = sibling;
                        break;
                    }
                }
            }

            // Desktop and Linux rewrite this file from the selected GameProfile
            // on every launch. Do the same here: an old or display-only INI in
            // the dump must never hide current settings such as Star Wars'
            // Remove Camera Error patch or a user's input/free-play choices.
            String normalizedProfileConfig = preparedWindowsLaunch.profileConfigIni
                .replace("\r\n", "\n")
                .replace('\r', '\n');
            List<String> source = new ArrayList<>();
            for (String line : normalizedProfileConfig.split("\n", -1))
                source.add(line);
            if (!source.isEmpty() && source.get(source.size() - 1).isEmpty())
                source.remove(source.size() - 1);
            int gameResolutionWidth = preparedWindowsLaunch.resolutionWidth;
            int gameResolutionHeight = preparedWindowsLaunch.resolutionHeight;
            boolean applyResolution = gameResolutionWidth > 0;
            boolean applyWmmtNetwork = isWmmtCompatibilityPreset(
                preparedWindowsLaunch.compatibilityPreset);
            boolean applyWackyNetwork = "wacky-races-network".equals(
                preparedWindowsLaunch.compatibilityPreset);
            boolean applyStarWarsDisplay = "star-wars".equals(
                preparedWindowsLaunch.compatibilityPreset);
            boolean applyTaikoCustomResolution = "taiko-custom-resolution".equals(
                preparedWindowsLaunch.compatibilityPreset);
            boolean enableWmmtTerminalEmulator = "wmmt-terminal".equals(
                preparedWindowsLaunch.compatibilityPreset);
            String terminalEmulatorValue = enableWmmtTerminalEmulator ? "1" : "0";
            String networkAdapterIp = null;
            if (applyWmmtNetwork || applyWackyNetwork) {
                networkAdapterIp = new NetworkHelper(this).getIPv4Address();
                if (networkAdapterIp == null || networkAdapterIp.trim().isEmpty())
                    networkAdapterIp = "127.0.0.1";
            }
            if (enableWmmtTerminalEmulator) {
                // WMMT's terminal and cabinet protocol uses UDP 50765. Keep the
                // traffic inside the Wine network namespace so Android Wi-Fi
                // multicast filtering cannot discard the terminal heartbeat.
                envVars.put("TP_ANDROID_WMMT_TERMINAL_UNICAST", "127.0.0.1");
                // Wine currently accepts the emulator's UDP sends but does
                // not loop them back to WMMT's second Winsock socket. Let the
                // private Android core deliver the already-serialized packet
                // directly at WSARecv while preserving native socket behavior
                // everywhere else.
                envVars.put("TP_ANDROID_WMMT_TERMINAL_DIRECT_RECV", "1");
            }
            String windowedValue = "fullscreen".equals(
                preparedWindowsLaunch.displayMode) ? "0" : "1";
            List<String> result = new ArrayList<>(
                source.size() + (applyResolution ? 5 : 3) +
                    (applyWmmtNetwork ? 5 : 0) + (applyStarWarsDisplay ? 1 : 0) +
                    (applyTaikoCustomResolution ? 1 : 0) +
                    (applyWackyNetwork ? 2 : 0));
            boolean inGeneral = false;
            boolean inNetwork = false;
            boolean foundGeneral = false;
            boolean foundNetwork = false;
            boolean wroteWindowed = false;
            boolean wroteBorderlessFullscreen = false;
            boolean wroteResolutionWidth = false;
            boolean wroteResolutionHeight = false;
            boolean wroteCustomResolution = false;
            boolean usesSpacedResolutionKeys = applyTaikoCustomResolution;
            boolean wroteTerminalEmulator = false;
            boolean wroteTerminalMode = false;
            boolean wroteFreePlay = false;
            boolean wroteNetworkAdapterIp = false;
            boolean wroteWackyCab1Ip = false;
            boolean wroteSkipMovies = false;
            for (String line : source) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    if (inGeneral && !wroteWindowed) {
                        result.add("Windowed=" + windowedValue);
                        wroteWindowed = true;
                    }
                    if (inGeneral && applyStarWarsDisplay && !wroteBorderlessFullscreen) {
                        // Battle Pod's shipped UE3 settings request 2560x1440. OpenParrot's
                        // Star Wars hook replaces that with the Wine desktop metrics only
                        // when this option is enabled. Keep the game at the managed Wine
                        // desktop size so it does not render the shipped four-times-larger
                        // image and force the device into swap/thermal pressure.
                        result.add("Borderless Fullscreen=1");
                        wroteBorderlessFullscreen = true;
                    }
                    if (inGeneral && applyResolution && !wroteResolutionWidth) {
                        result.add((usesSpacedResolutionKeys ? "Resolution Width=" : "ResolutionWidth=") +
                            gameResolutionWidth);
                        wroteResolutionWidth = true;
                    }
                    if (inGeneral && applyResolution && !wroteResolutionHeight) {
                        result.add((usesSpacedResolutionKeys ? "Resolution Height=" : "ResolutionHeight=") +
                            gameResolutionHeight);
                        wroteResolutionHeight = true;
                    }
                    if (inGeneral && applyTaikoCustomResolution && !wroteCustomResolution) {
                        result.add("Custom Resolution (Stretches)=1");
                        wroteCustomResolution = true;
                    }
                    if (inGeneral && applyWmmtNetwork && !wroteTerminalEmulator) {
                        result.add("Terminal Emu=" + terminalEmulatorValue);
                        wroteTerminalEmulator = true;
                    }
                    if (inGeneral && applyWmmtNetwork && !wroteTerminalMode) {
                        result.add("Terminal Mode=0");
                        wroteTerminalMode = true;
                    }
                    if (inGeneral && applyWmmtNetwork && !wroteFreePlay) {
                        result.add("FreePlay=1");
                        wroteFreePlay = true;
                    }
                    if (inGeneral && applyWmmtNetwork && !wroteNetworkAdapterIp) {
                        result.add("NetworkAdapterIP=" + networkAdapterIp);
                        wroteNetworkAdapterIp = true;
                    }
                    if (inGeneral && applyWmmtNetwork && !wroteSkipMovies) {
                        result.add("SkipMovies=0");
                        wroteSkipMovies = true;
                    }
                    if (inNetwork && applyWackyNetwork && !wroteWackyCab1Ip) {
                        // AddIPAddress is unsupported by Wine on Android, so the
                        // configured cabinet alias is never added to an adapter.
                        // DirectPlay must instead bind the phone's real address.
                        result.add("Cab1IP=" + networkAdapterIp);
                        wroteWackyCab1Ip = true;
                    }
                    inGeneral = trimmed.equalsIgnoreCase("[General]");
                    foundGeneral |= inGeneral;
                    inNetwork = trimmed.equalsIgnoreCase("[Network]");
                    foundNetwork |= inNetwork;
                }
                if (inGeneral) {
                    int equals = trimmed.indexOf('=');
                    if (equals > 0) {
                        String key = trimmed.substring(0, equals).trim();
                        if (key.equalsIgnoreCase("Windowed")) {
                            result.add("Windowed=" + windowedValue);
                            wroteWindowed = true;
                            continue;
                        }
                        if (applyStarWarsDisplay &&
                            key.equalsIgnoreCase("Borderless Fullscreen")) {
                            result.add("Borderless Fullscreen=1");
                            wroteBorderlessFullscreen = true;
                            continue;
                        }
                        if (applyResolution && key.equalsIgnoreCase("ResolutionWidth")) {
                            result.add("ResolutionWidth=" + gameResolutionWidth);
                            wroteResolutionWidth = true;
                            continue;
                        }
                        if (applyResolution && key.equalsIgnoreCase("Resolution Width")) {
                            usesSpacedResolutionKeys = true;
                            result.add("Resolution Width=" + gameResolutionWidth);
                            wroteResolutionWidth = true;
                            continue;
                        }
                        if (applyResolution && key.equalsIgnoreCase("ResolutionHeight")) {
                            result.add("ResolutionHeight=" + gameResolutionHeight);
                            wroteResolutionHeight = true;
                            continue;
                        }
                        if (applyResolution && key.equalsIgnoreCase("Resolution Height")) {
                            usesSpacedResolutionKeys = true;
                            result.add("Resolution Height=" + gameResolutionHeight);
                            wroteResolutionHeight = true;
                            continue;
                        }
                        if (applyResolution &&
                            key.equalsIgnoreCase("Custom Resolution (Stretches)")) {
                            usesSpacedResolutionKeys = true;
                            result.add("Custom Resolution (Stretches)=1");
                            wroteCustomResolution = true;
                            continue;
                        }
                        if (applyWmmtNetwork &&
                            (key.equalsIgnoreCase("TerminalEmulator") ||
                             key.equalsIgnoreCase("Terminal Emu"))) {
                            if (!wroteTerminalEmulator)
                                result.add("Terminal Emu=" + terminalEmulatorValue);
                            wroteTerminalEmulator = true;
                            continue;
                        }
                        if (applyWmmtNetwork &&
                            (key.equalsIgnoreCase("TerminalMode") ||
                             key.equalsIgnoreCase("Terminal Mode"))) {
                            if (!wroteTerminalMode)
                                result.add("Terminal Mode=0");
                            wroteTerminalMode = true;
                            continue;
                        }
                        if (applyWmmtNetwork && key.equalsIgnoreCase("FreePlay")) {
                            result.add("FreePlay=1");
                            wroteFreePlay = true;
                            continue;
                        }
                        if (applyWmmtNetwork && key.equalsIgnoreCase("NetworkAdapterIP")) {
                            result.add("NetworkAdapterIP=" + networkAdapterIp);
                            wroteNetworkAdapterIp = true;
                            continue;
                        }
                        if (applyWmmtNetwork && key.equalsIgnoreCase("SkipMovies")) {
                            result.add("SkipMovies=0");
                            wroteSkipMovies = true;
                            continue;
                        }
                    }
                }
                if (inNetwork && applyWackyNetwork) {
                    int equals = trimmed.indexOf('=');
                    if (equals > 0) {
                        String key = trimmed.substring(0, equals).trim();
                        if (key.equalsIgnoreCase("Cab1IP")) {
                            result.add("Cab1IP=" + networkAdapterIp);
                            wroteWackyCab1Ip = true;
                            continue;
                        }
                    }
                }
                result.add(line);
            }
            if (!foundGeneral) {
                if (!result.isEmpty() && !result.get(result.size() - 1).isEmpty())
                    result.add("");
                result.add("[General]");
            }
            if (!wroteWindowed)
                result.add("Windowed=" + windowedValue);
            if (applyStarWarsDisplay && !wroteBorderlessFullscreen)
                result.add("Borderless Fullscreen=1");
            if (applyResolution && !wroteResolutionWidth)
                result.add((usesSpacedResolutionKeys ? "Resolution Width=" : "ResolutionWidth=") +
                    gameResolutionWidth);
            if (applyResolution && !wroteResolutionHeight)
                result.add((usesSpacedResolutionKeys ? "Resolution Height=" : "ResolutionHeight=") +
                    gameResolutionHeight);
            if (applyTaikoCustomResolution && !wroteCustomResolution)
                result.add("Custom Resolution (Stretches)=1");
            if (applyWmmtNetwork && !wroteTerminalEmulator)
                result.add("Terminal Emu=" + terminalEmulatorValue);
            if (applyWmmtNetwork && !wroteTerminalMode)
                result.add("Terminal Mode=0");
            if (applyWmmtNetwork && !wroteFreePlay)
                result.add("FreePlay=1");
            if (applyWmmtNetwork && !wroteNetworkAdapterIp)
                result.add("NetworkAdapterIP=" + networkAdapterIp);
            if (applyWmmtNetwork && !wroteSkipMovies)
                result.add("SkipMovies=0");
            if (applyWackyNetwork && !foundNetwork) {
                if (!result.isEmpty() && !result.get(result.size() - 1).isEmpty())
                    result.add("");
                result.add("[Network]");
            }
            if (applyWackyNetwork && !wroteWackyCab1Ip)
                result.add("Cab1IP=" + networkAdapterIp);
            Files.write(iniFile.toPath(), result, StandardCharsets.UTF_8);
            if (preparedWindowsLaunch.debugLoggingEnabled)
                Log.i(TAG, "Prepared Android " +
                    ("0".equals(windowedValue) ? "fullscreen" : "windowed") + " mode" +
                    (applyResolution
                        ? " at " + gameResolutionWidth + "x" +
                            gameResolutionHeight
                        : "") +
                    (applyWmmtNetwork
                        ? " with WMMT adapter " + networkAdapterIp +
                            ", terminal emulator " +
                            (enableWmmtTerminalEmulator ? "enabled" : "disabled") +
                            ", terminal mode disabled, and movies enabled"
                        : "") +
                    (applyWackyNetwork
                        ? " with Wacky Races cabinet adapter " + networkAdapterIp
                        : "") +
                    (applyStarWarsDisplay
                        ? " with the OpenParrot managed-resolution Star Wars display hook"
                        : "") +
                    (applyTaikoCustomResolution
                        ? " with Taiko custom resolution enabled"
                        : "") +
                    " in " + iniFile.getPath());
            return true;
        }
        catch (IOException error) {
            Log.e(TAG, "Could not prepare the complete TeknoParrot.ini profile.", error);
            return false;
        }
    }

    private static boolean isWmmtCompatibilityPreset(String compatibilityPreset) {
        return "wmmt-terminal".equals(compatibilityPreset) ||
            "wmmt-no-terminal".equals(compatibilityPreset);
    }

    private boolean isPreparedDosPathWithinDrive(String dosPath, boolean directory)
            throws IOException {
        String unixPath = WineUtils.dosToUnixPath(dosPath, container);
        String driveRootPath = WineUtils.dosToUnixPath(dosPath.substring(0, 3), container);
        if (unixPath.isEmpty() || driveRootPath.isEmpty())
            return false;

        File candidate = new File(unixPath).getCanonicalFile();
        File driveRoot = new File(driveRootPath).getCanonicalFile();
        String root = driveRoot.getPath();
        String path = candidate.getPath();
        boolean contained = path.equals(root) || path.startsWith(root + File.separator);
        return contained && (directory ? candidate.isDirectory() : candidate.isFile());
    }

    private static String quotePreparedWindowsArgument(String value) {
        StringBuilder result = new StringBuilder(value.length() + 4).append('"');
        int trailingBackslashes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            result.append(character);
            trailingBackslashes = character == '\\' ? trailingBackslashes + 1 : 0;
        }
        for (int index = 0; index < trailingBackslashes; index++)
            result.append('\\');
        return result.append('"').toString();
    }

    public XServer getXServer() {
        return xServer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public XServerView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public RootFS getRootFs() {
        return rootFS;
    }

    public EnvVars getOverrideEnvVars() {
        if (overrideEnvVars == null) overrideEnvVars = new EnvVars();
        return overrideEnvVars;
    }

    public String getDXWrapper() {
        return dxwrapper;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public ScreenInfo getScreenInfo() {
        return screenInfo;
    }

    public void setScreenInfo(ScreenInfo screenInfo) {
        this.screenInfo = screenInfo;
    }

    public String getWinComponents() {
        return wincomponents;
    }

    public void setWinComponents(String wincomponents) {
        this.wincomponents = wincomponents;
    }

    public DebugDialog getDebugDialog() {
        return debugDialog;
    }

    public String getScreenEffectProfile() {
        return screenEffectProfile;
    }

    public void setScreenEffectProfile(String screenEffectProfile) {
        this.screenEffectProfile = screenEffectProfile;
    }

    private void changeWineAudioDriver() {
        if (!audioDriver.equals(container.getExtra("audioDriver"))) {
            File rootDir = rootFS.getRootDir();
            File userRegFile = new File(rootDir, RootFS.WINEPREFIX+"/user.reg");
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                if (audioDriver.equals(AudioDrivers.ALSA)) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
                }
                else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
                }
            }
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = rootFS.getRootDir();
        FileUtils.delete(new File(rootDir, "/opt/apps"));
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "rootfs_patches.tzst", rootDir);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("graphicsDriver", null);
        container.putExtra("dxwrapper", null);
        container.putExtra("desktopTheme", null);
        SettingsFragment.resetBox64Version(this);
    }

    private void changeFrameRatingVisibility(Window window, boolean visible) {
        if (frameRating == null) return;
        if (visible) {
            Window child = window.getChildCount() > 0 ? window.getChildren().get(0) : null;
            boolean viewable = window.attributes.isMapped() && window.getWidth() >= ScreenInfo.MIN_WIDTH && window.getHeight() >= ScreenInfo.MIN_HEIGHT;
            if (viewable && (window.isSurface() || (child != null && child.isSurface()))) {
                Window frameRatingWindow = window.isSurface() ? window : child;
                if (frameRating.getMode() == FrameRating.Mode.FULL) {
                    Property gpuInfo = frameRatingWindow.getProperty(Atom._NET_WM_GPU_INFO);
                    frameRating.setGPUInfo(gpuInfo != null ? new String(gpuInfo.data.array()) : "N/A");
                }
                frameRatingWindowId = frameRatingWindow.id;
                frameRating.reset();
            }
        }
        else if (window.id == frameRatingWindowId) {
            frameRatingWindowId = -1;
            runOnUiThread(() -> frameRating.setVisibility(View.GONE));
        }
    }

    public boolean verifyUserRegistry() {
        File userRegFile = new File(rootFS.getRootDir(), RootFS.WINEPREFIX+"/user.reg");
        String lastModified = String.valueOf(userRegFile.lastModified());

        if (!lastModified.equals(container.getExtra("userRegLastModified"))) {
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                registryEditor.removeKey("Software\\Wow6432Node\\Wine", true);
            }

            container.putExtra("userRegLastModified", lastModified);
            return true;
        }
        else return false;
    }
}

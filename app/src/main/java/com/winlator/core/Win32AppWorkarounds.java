package com.winlator.core;

import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.DXWrappers;
import com.winlator.winhandler.WinEnums;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;

import java.util.Locale;

public class Win32AppWorkarounds {
    private final short taskAffinityMask;
    private final short taskAffinityMaskWoW64;
    private final XServerDisplayActivity activity;

    private interface Workaround {}

    private static class MultiWorkaround implements Workaround {
        private final Workaround[] list;

        public MultiWorkaround(Workaround... list) {
            this.list = list;
        }
    }

    private interface WindowWorkaround extends Workaround {
        void apply(Window window);
    }

    private interface EnvVarsWorkaround extends Workaround {
        void apply(EnvVars envVars);
    }

    private interface ScreenSizeWorkaround extends Workaround {
        String getValue();
    }

    private interface DXWrapperWorkaround extends Workaround {
        String getValue();
    }

    private interface WinComponentsWorkaround extends Workaround {
        void setValue(KeyValueSet wincomponents);
    }

    public Win32AppWorkarounds(XServerDisplayActivity activity) {
        this.activity = activity;
        Container container = activity.getContainer();
        taskAffinityMask = (short)ProcessHelper.getAffinityMask(container.getCPUList(true));
        taskAffinityMaskWoW64 = (short)ProcessHelper.getAffinityMask(container.getCPUListWoW64(true));
    }

    private void applyWorkaround(Workaround workaround) {
        if (workaround instanceof EnvVarsWorkaround) {
            ((EnvVarsWorkaround)workaround).apply(activity.getOverrideEnvVars());
        }
        else if (workaround instanceof ScreenSizeWorkaround) {
            activity.setScreenInfo(new ScreenInfo(((ScreenSizeWorkaround)workaround).getValue()));
        }
        else if (workaround instanceof DXWrapperWorkaround) {
            activity.setDXWrapper(((DXWrapperWorkaround)workaround).getValue());
        }
        else if (workaround instanceof WinComponentsWorkaround) {
            KeyValueSet wincomponents = new KeyValueSet(Container.DEFAULT_WINCOMPONENTS);
            ((WinComponentsWorkaround)workaround).setValue(wincomponents);
            activity.setWinComponents(wincomponents.toString());
        }
    }

    public void applyStartupWorkarounds(String className) {
        Workaround workaround = getWorkaroundFor(className);
        if (workaround == null) return;

        if (workaround instanceof MultiWorkaround) {
            for (Workaround workaround2 : ((MultiWorkaround)workaround).list) applyWorkaround(workaround2);
        }
        else applyWorkaround(workaround);
    }

    /**
     * Prepared TeknoParrot launches commonly start a loader executable and pass
     * the actual game executable as an argument.  Startup workarounds must be
     * selected from the game as well as the loader because Wine components are
     * installed before either process creates its first window.
     */
    public void applyPreparedStartupWorkarounds(String executable, String[] arguments) {
        applyStartupWorkarounds(FileUtils.getName(executable));
        if (arguments == null) return;

        for (String argument : arguments) {
            String name = FileUtils.getName(argument);
            if (name.toLowerCase(Locale.ENGLISH).endsWith(".exe"))
                applyStartupWorkarounds(name);
        }
    }

    private void applyWineGStreamerWorkaround() {
        // 64-bit titles cannot use Winlator's optional 32-bit Microsoft WM
        // decoder archive. Restore Wine's media stack and expose the bundled
        // native GStreamer plugins through the media-only Box64 executable.
        applyWorkaround((WinComponentsWorkaround) (wincomponents) -> {
            wincomponents.put("directshow", "0");
            wincomponents.put("wmdecoder", "0");
        });
        applyWorkaround((EnvVarsWorkaround) (envVars) -> {
            envVars.put(
                "WINEDLLOVERRIDES",
                "amstream=b;qasf=b;quartz=b;winegstreamer=b;" +
                "wmadmod=b;wmasf=b;wmvcore=b;wmvdecod=b");
            envVars.put(
                "GST_PLUGIN_SYSTEM_PATH_1_0",
                activity.getRootFs().getRootDir().getPath()+"/usr/lib/gstreamer-1.0");
            // Box64 0.4.0 makes GTK a prerequisite of its GStreamer wrapper
            // even though GStreamer itself does not use it. The launcher
            // applies a private, media-only copy fix and exposes the bundled
            // native plugins to that wrapper. BOX64_NOGTK cannot be used:
            // it also disables the GLib/GObject/GStreamer wrappers.
            envVars.put("TP_BOX64_WINEGSTREAMER_FIX", "1");
        });
    }

    public void applyTeknoParrotCompatibilityPreset(String compatibilityPreset) {
        if (compatibilityPreset == null || compatibilityPreset.isEmpty()) return;

        if (compatibilityPreset.equals("media-wmv")) {
            // Legacy 32-bit Type X titles use the Microsoft DirectShow and WMV
            // filters. Keep Wine's graph manager so the native filters do not
            // take ownership of the game window.
            applyWorkaround((WinComponentsWorkaround) (wincomponents) -> {
                wincomponents.put("directshow", "1");
                wincomponents.put("wmdecoder", "1");
            });
            applyWorkaround((EnvVarsWorkaround) (envVars) ->
                envVars.put("WINEDLLOVERRIDES", "quartz=b"));
        }
        else if (compatibilityPreset.equals("wine-gstreamer") ||
                 compatibilityPreset.equals("kof-xii-wine-gstreamer")) {
            applyWineGStreamerWorkaround();
        }
        else if (compatibilityPreset.equals("gaia-attack4-media")) {
            // Gaia Attack 4 mixes WMV3 and Indeo 5 AVI files. Keep both
            // codecs on the prefix-local Wine-GStreamer path.
            applyWineGStreamerWorkaround();
        }
        else if (compatibilityPreset.equals("kof-mira-builtin-wined3d")) {
            // Regulation A ships local WineD3D/DirectDraw wrappers that do not
            // match this prefix. Prefer the Wine builtins without mutating the dump.
            applyWorkaround((EnvVarsWorkaround) (envVars) ->
                envVars.put("WINEDLLOVERRIDES", "d3d8,d3d9,ddraw=b"));
        }
        else if (compatibilityPreset.equals("post-start-remote-thread")) {
            // Guilty Gear Xrd REV2 APM3 reaches its startup movie immediately
            // after D3D9 initialization. The ordinary Box64 executable treats
            // GTK as a native GStreamer dependency, partially initializes the
            // plugin registry, and then aborts when gst-libav is registered a
            // second time. Route this title through the existing media-only
            // Box64 executable, which removes that erroneous GTK dependency
            // while retaining Wine-GStreamer and the bundled codecs.
            applyWineGStreamerWorkaround();
        }
        else if (compatibilityPreset.equals("wmmt-terminal") ||
                 compatibilityPreset.equals("wmmt-no-terminal")) {
            // The WMMT family is 64-bit, while Winlator's optional Microsoft
            // WM decoder archive contains only 32-bit filters.  Restore the
            // Wine builtins and route DirectShow through Wine-GStreamer,
            // whose bundled ASF/libav plugins decode the game's WMV9/WMA2
            // attract movies on Android.
            applyWineGStreamerWorkaround();
        }
        else if (compatibilityPreset.equals("taito-legacy-scard")) {
            applyWorkaround((EnvVarsWorkaround) (envVars) ->
                envVars.put("WINEDLLOVERRIDES", "dinput8=b;winscard=n,b"));
        }
        else if (compatibilityPreset.equals("builtin-ddraw")) {
            // Some preserved arcade dumps ship DDrawCompat beside the game.
            // That Windows-native wrapper initializes under Wine, then exits
            // before publishing a surface on Android. Prefer Wine's managed
            // DirectDraw path without renaming or deleting the user's DLL.
            applyWorkaround((EnvVarsWorkaround) (envVars) ->
                envVars.put("WINEDLLOVERRIDES", "ddraw=b"));
        }
        else if (compatibilityPreset.equals("xact-local-register")) {
            // The game ships its exact XACT engine beside the executable. Keep
            // the matching Winlator component archive installed, then let the
            // launch preflight register that local COM server in this prefix.
            applyWorkaround((WinComponentsWorkaround) (wincomponents) ->
                wincomponents.put("xaudio", "1"));
        }
        else if (compatibilityPreset.equals("dirty-driving-fullscreen")) {
            // Dirty Drivin requests a single 0x32010000-byte allocation very
            // early in sdaemon.exe.  On memory-pressured WoW64 processes the
            // high address range can otherwise become fragmented before that
            // request is made.  Keep this reservation title-scoped because it
            // changes the address-space layout seen by 32-bit applications.
            applyWorkaround((EnvVarsWorkaround) (envVars) ->
                envVars.put("BOX64_RESERVE_HIGH", "1"));
        }
        else if (compatibilityPreset.equals("wacky-races-network")) {
            // The cabinet software creates its DirectPlay session through COM.
            // Wine's legacy builtin reaches that setup with an invalid vtable
            // after the title's unsupported AddIPAddress call is bypassed by
            // OpenParrot.  Winlator already bundles the native DirectPlay 8
            // runtime; keep it isolated to this title instead of changing the
            // managed container used by every TeknoParrot game.
            applyWorkaround((WinComponentsWorkaround) (wincomponents) ->
                wincomponents.put("directplay", "1"));
        }
        else if (compatibilityPreset.equals("chase-hq2")) {
            // The dump's local dinput8.dll prevents Chase H.Q. 2 from reaching
            // OpenParrot under Wine. Force Wine's builtin without renaming or
            // deleting the user's game file. The cabinet also uses the legacy
            // 32-bit Windows Media filters for its WMV9 attract movies.
            applyWorkaround((WinComponentsWorkaround) (wincomponents) -> {
                wincomponents.put("directshow", "1");
                wincomponents.put("wmdecoder", "1");
            });
            applyWorkaround((EnvVarsWorkaround) (envVars) -> {
                envVars.put("WINEDLLOVERRIDES", "dinput8=b;quartz=b");
                // Chase's attract loop exhausts Android's vm.max_map_count in
                // about a minute while Box64 is joining dynarec big blocks.
                // Disable that optimization for this title while retaining the
                // shared-container default for every other OpenParrot game.
                envVars.put("BOX64_DYNAREC_BIGBLOCK", "0");
                // On current Box64, Chase's DirectPlay transform repeatedly
                // raises STATUS_FLOAT_MULTIPLE_TRAPS in its x87 product sequence.
                // The shared performance preset forces extended x87-double
                // handling; this title runs with the normal x87 path on Windows
                // and desktop Wine, so keep the workaround isolated.
                envVars.put("BOX64_DYNAREC_X87DOUBLE", "0");
            });
        }
    }

    private void setProcessAffinity(Window window, int processAffinity) {
        int processId = window.getProcessId();
        String className = window.getClassName();
        WinHandler winHandler = activity.getWinHandler();

        if (className.equals("steam.exe")) return;

        if (processId > 0) {
            winHandler.setProcessAffinity(processId, processAffinity);
        }
        else if (!className.isEmpty()) {
            winHandler.setProcessAffinity(window.getClassName(), processAffinity);
        }
    }

    public void applyWindowWorkarounds(Window window) {
        Workaround workaround = getWorkaroundFor(window.getClassName());
        if (workaround instanceof WindowWorkaround) {
            ((WindowWorkaround)workaround).apply(window);
        }
        else if (workaround instanceof MultiWorkaround) {
            for (Workaround workaround2 : ((MultiWorkaround) workaround).list) {
                if (workaround2 instanceof WindowWorkaround) {
                    ((WindowWorkaround)workaround2).apply(window);
                    break;
                }
            }
        }

        int windowGroup = window.getWMHintsValue(Window.WMHints.WINDOW_GROUP);
        boolean canApplyProcessAffinity = window.isRenderable() && !window.getClassName().isEmpty() && windowGroup == window.id;
        if (canApplyProcessAffinity) {
            int processAffinity = window.isWoW64() ? taskAffinityMaskWoW64 : taskAffinityMask;
            if (processAffinity != 0) setProcessAffinity(window, processAffinity);
        }
    }

    private Workaround getWorkaroundFor(String className) {
        String appIdentifier;
        if (className.startsWith("steam://")) {
            appIdentifier = className.substring(className.lastIndexOf("/") + 1);
        }
        else appIdentifier = className.toLowerCase(Locale.ENGLISH);

        switch (appIdentifier) {
            case "sonicgenerations.exe":
            case "71340":
            case "valkyria.exe":
            case "294860":
                return (EnvVarsWorkaround) (envVars) -> envVars.put("WINEESYNC", "0");
            case "blacklist_game.exe":
            case "blacklist_dx11_game.exe":
                return (EnvVarsWorkaround) (envVars) -> envVars.put("WINEOVERRIDEAFFINITYMASK", taskAffinityMaskWoW64);
            case "fate.exe":
            case "psychotoxic.exe":
                return (ScreenSizeWorkaround) () -> "1024x768";
            case "ffxii_tza.exe":
                ScreenInfo screenInfo = activity.getScreenInfo();
                return (ScreenSizeWorkaround) () -> (screenInfo.width+4)+"x"+(screenInfo.height+4);
            case "chronocross_launcher.exe":
                return (WindowWorkaround) (window) -> {
                    window.attributes.setTransparent(true);
                    final WinHandler winHandler = activity.getWinHandler();
                    AppUtils.runDelayed(() -> {
                        winHandler.showWindow(window.getHandle(), WinEnums.SW_MINIMIZE);
                        winHandler.showWindow(window.getHandle(), WinEnums.SW_RESTORE);
                    }, 500);
                };
            case "dino.exe":
            case "dino2.exe":
            case "bof4.exe":
                return (WinComponentsWorkaround) (wincomponents) -> wincomponents.put("directshow", "1");
            case "rally.exe":
                // SR3 needs Microsoft's ASF reader and WMV decoders, but the
                // native Quartz renderer prevents the game from mapping its
                // main window under Wine/Winlator.  Keep Wine's Quartz graph
                // manager while enabling the native DirectShow/WMV filters.
                return new MultiWorkaround(
                    (WinComponentsWorkaround) (wincomponents) -> wincomponents.put("directshow", "1"),
                    (EnvVarsWorkaround) (envVars) -> envVars.put("WINEDLLOVERRIDES", "quartz=b"));
            case "discipl2.exe":
                return (DXWrapperWorkaround) () -> DXWrappers.WINED3D;
            default:
                return null;
        }
    }
}

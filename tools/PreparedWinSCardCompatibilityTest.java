import com.winlator.core.EnvVars;
import com.winlator.teknoparrot.PreparedWinSCardCompatibility;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;

public final class PreparedWinSCardCompatibilityTest {
    private static int assertions;
    private static final String LOADER = "E:\\TeknoParrotRuntime\\OpenParrotWin32\\OpenParrotLoader.exe";
    private static final String GAME = "G:\\Music GunGun 2\\game.exe";

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    private static WinSCardPolicyActivity activity(String preset, int controls,
            String loader, String game, File prefix, byte[] asset) {
        WinSCardPolicyActivity result = new WinSCardPolicyActivity(prefix, asset);
        result.preparedWindowsLaunch = new WinSCardPolicyLaunch();
        result.preparedWindowsLaunch.compatibilityPreset = preset;
        result.preparedWindowsLaunch.controlsProfileId = controls;
        result.preparedWindowsLaunch.executable = loader;
        result.game = game;
        return result;
    }

    public static void main(String[] args) throws Exception {
        String preset = "music-gungun-native-fullscreen";
        File root = Files.createTempDirectory("tpui-scard-checks-").toFile();
        byte[] asset = new byte[32771];
        for (int i = 0; i < asset.length; i++) asset[i] = (byte)(i * 37);
        File prefix = new File(root, "fresh-prefix");
        WinSCardPolicyActivity music = activity(preset, 9074, LOADER, GAME, prefix, asset);
        File target = new File(prefix, RootFS.WINEPREFIX + "/drive_c/windows/syswow64/winscard.dll");
        check(!target.exists(), "prefix starts without any previous game's payload");
        check(music.requiresPreparedWinSCardPayload(), "actual Activity selects Music route");
        check(music.prepare(), "actual Activity installs into a fresh prefix");
        check(Arrays.equals(asset, Files.readAllBytes(target.toPath())), "installed asset is byte exact");
        check(music.assets.opened == 1, "eligible launch reads its own asset");
        Files.write(target.toPath(), new byte[] {1, 2, 3});
        check(music.prepare(), "repeat launch replaces a stale prefix DLL");
        check(Arrays.equals(asset, Files.readAllBytes(target.toPath())), "stale DLL replaced exactly");

        music.assets.fail = true;
        check(!music.prepare(), "asset open failure rejects launch despite an existing DLL");
        music.assets.fail = false;
        music.assets.bytes = new byte[0];
        check(!music.prepare(), "empty asset rejects launch despite an existing DLL");
        check(Arrays.equals(asset, Files.readAllBytes(target.toPath())), "failed staged copy preserves old DLL");
        music.assets.bytes = asset;

        for (String[] bad : new String[][] {
            {preset, "9002", LOADER, GAME},
            {preset, "9074", LOADER.replace("Win32", "Win32Legacy"), GAME},
            {preset, "9074", LOADER.replace("Win32", "Win64"), GAME},
            {preset, "9074", null, GAME},
            {preset, "9074", LOADER, null},
            {preset, "9074", LOADER, GAME.replace("game.exe", "other.exe")},
            {preset, "9074", LOADER, "G:\\Music\\..\\game.exe"},
            {preset + " ", "9074", LOADER, GAME},
            {"", "9074", LOADER, GAME}
        }) {
            WinSCardPolicyActivity rejected = activity(bad[0], Integer.parseInt(bad[1]),
                bad[2], bad[3], new File(root, "unrelated-" + assertions), asset);
            check(!rejected.requiresPreparedWinSCardPayload(), "wrong route rejected");
            check(rejected.prepare() && rejected.assets.opened == 0, "unrelated launch never stages DLL");
        }
        check(PreparedWinSCardCompatibility.requiresPayload(preset, 9074,
            LOADER.toLowerCase(), GAME.toUpperCase()), "canonical mixed-case Music path");
        WinSCardPolicyActivity legacy = activity("taito-legacy-scard", 9002, LOADER,
            "G:\\Samurai\\game.exe", new File(root, "fresh-legacy-prefix"), asset);
        check(legacy.prepare() && legacy.assets.opened == 1, "existing preset independently stages payload");
        WinSCardPolicyActivity absent = new WinSCardPolicyActivity(new File(root, "absent"), asset);
        check(absent.prepare() && absent.assets.opened == 0, "no prepared launch remains unchanged");

        WinSCardPolicyWorkarounds workaround = new WinSCardPolicyWorkarounds(music);
        workaround.applyPreset(preset);
        EnvVars env = workaround.environment;
        String media = "amstream=b;qasf=b;quartz=b;winegstreamer=b;wmadmod=b;wmasf=b;wmvcore=b;wmvdecod=b";
        check((media + ";winscard=n,b").equals(env.get("WINEDLLOVERRIDES")),
            "actual media branch retains every Wine media override");
        check("".equals(env.get("GST_PLUGIN_SYSTEM_PATH_1_0")), "actual Music plugin-path isolation retained");
        check("/tmp/gstreamer-music-gungun2-registry.bin".equals(env.get("GST_REGISTRY")),
            "actual Music registry isolation retained");
        check("1".equals(env.get("TP_BOX64_WINEGSTREAMER_FIX")), "actual media bridge retained");
        check("0".equals(workaround.components.get("directshow")) &&
            "0".equals(workaround.components.get("wmdecoder")), "actual component selection retained");
        check((media + ";winscard=n,b").equals(
            PreparedWinSCardCompatibility.withNativeOverride(media + ";winscard=d")), "only stale card override replaced");
        check("winscard=n,b".equals(PreparedWinSCardCompatibility.withNativeOverride(null)), "empty override handled");
        check("foo=n;winscard=n,b".equals(
            PreparedWinSCardCompatibility.withNativeOverride("WinSCard=b;foo=n;winscard=d")), "case-insensitive duplicates removed");
        check("winscard=n,b".equals(PreparedWinSCardCompatibility.withNativeOverride("winscard=n,b")), "idempotent override");
        WinSCardPolicyWorkarounds unrelated = new WinSCardPolicyWorkarounds(
            activity(preset, 9002, LOADER, GAME, root, asset));
        unrelated.applyPreset(preset);
        check(media.equals(unrelated.environment.get("WINEDLLOVERRIDES")), "wrong route retains media without card override");

        File faultTarget = new File(root, "copy-fault/winscard.dll");
        faultTarget.getParentFile().mkdirs();
        Files.write(faultTarget.toPath(), new byte[] {42});
        InputStream failing = new InputStream() {
            int count;
            public int read() throws IOException {
                if (++count >= 17) throw new IOException("injected source read failure");
                return 7;
            }
        };
        boolean threw = false;
        try { PreparedWinSCardCompatibility.install(failing, faultTarget); }
        catch (IOException expected) { threw = true; }
        check(threw && Arrays.equals(new byte[] {42}, Files.readAllBytes(faultTarget.toPath())),
            "partial asset read cannot publish or accept stale destination");
        check(faultTarget.getParentFile().listFiles().length == 1, "failed staging file removed");
        threw = false;
        try { PreparedWinSCardCompatibility.install(new ByteArrayInputStream(new byte[1024 * 1024 + 1]), faultTarget); }
        catch (IOException expected) { threw = true; }
        check(threw && faultTarget.length() == 1, "oversized asset rejected without replacement");
        System.out.println("Prepared WinSCard actual-helper/Activity/media checks passed: " + assertions + " assertions");
    }
}

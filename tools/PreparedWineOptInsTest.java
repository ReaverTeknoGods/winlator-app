import com.winlator.core.EnvVars;
import java.util.HashSet;
import java.util.Set;

public final class PreparedWineOptInsTest {
    private static final String LUPIN = "TP_WINE_LUPIN_JVS";
    private static final String PUZZLE = "TP_WINE_PUZZLE_BOBBLE_SD";
    private static final String CAPCOM = "TP_WINE_CAPCOM_NESICA_SD";
    private static final String KOF = "TP_WINE_KOFMIRA_JVS_RECONNECT";
    private static final String AUTH = "TP_WINE_AMAUTH_PRESERVE_CONFIG";
    private static final String WANGAN = "TP_WINE_WANGAN_BIND_REDIRECT";
    private static final String LOADER32 =
        "E:\\TeknoParrotRuntime\\OpenParrotWin32\\OpenParrotLoader.exe";
    private static final String LOADER64 =
        "E:\\TeknoParrotRuntime\\OpenParrotWin64\\OpenParrotLoader64.exe";
    private static int assertions;

    private static void check(boolean value, String reason) {
        assertions++;
        if (!value) throw new AssertionError(reason);
    }

    private static void exercise(String reason, String preset, int controls,
            String loader, String game, String prelaunch, String... expected) {
        PreparedWinePolicyActivity activity = new PreparedWinePolicyActivity();
        if (preset != null) {
            activity.preparedWindowsLaunch = new PreparedWinePolicyLaunch();
            activity.preparedWindowsLaunch.compatibilityPreset = preset;
            activity.preparedWindowsLaunch.controlsProfileId = controls;
            activity.preparedWindowsLaunch.executable = loader;
        }
        activity.game = game;
        EnvVars environment = new EnvVars();
        // Every fixture begins with stale container values; an unrelated flag
        // and the already selected companion launch must remain untouched.
        for (String name : new String[] {LUPIN, PUZZLE, CAPCOM, KOF, AUTH, WANGAN}) environment.put(name, "1");
        environment.put("UNRELATED_SETTING", "unchanged");
        if (prelaunch != null) environment.put("TP_PRELAUNCH_EXECUTABLE", prelaunch);
        activity.apply(environment);
        Set<String> actual = new HashSet<>();
        for (String name : new String[] {LUPIN, PUZZLE, CAPCOM, KOF, AUTH, WANGAN}) {
            if (environment.has(name)) {
                check("1".equals(environment.get(name)), reason + ": exact opt-in value");
                actual.add(name);
            }
        }
        check(actual.equals(Set.of(expected)), reason + ": wrong selected opt-ins " + actual);
        check("unchanged".equals(environment.get("UNRELATED_SETTING")), reason + ": unrelated environment changed");
        check((prelaunch != null) == environment.has("TP_PRELAUNCH_EXECUTABLE"), reason + ": prelaunch added/removed");
        if (prelaunch != null)
            check(prelaunch.equals(environment.get("TP_PRELAUNCH_EXECUTABLE")), reason + ": prelaunch rewritten");
    }

    public static void main(String[] args) {
        exercise("no prepared launch", null, 9002, LOADER32, "G:\\KOF\\game.exe", null);
        exercise("ordinary title", "", 9002, LOADER32, "G:\\Other\\game.exe", null);
        exercise("Puzzle native SD preset", "puzzle-bobble-sd", 9005, LOADER32, "G:\\Puzzle Bobble\\game.exe", null, PUZZLE);
        exercise("Puzzle old classic preset", "classic-640-client", 9005, LOADER32, "G:\\Puzzle Bobble\\game.exe", null);
        exercise("Puzzle wrong controls", "puzzle-bobble-sd", 9002, LOADER32, "G:\\Puzzle Bobble\\game.exe", null);
        exercise("Puzzle wrong loader", "puzzle-bobble-sd", 9005, LOADER64, "G:\\Puzzle Bobble\\game.exe", null);
        exercise("Puzzle wrong game", "puzzle-bobble-sd", 9005, LOADER32, "G:\\Puzzle Bobble\\other.exe", null);
        exercise("Puzzle absent path", "puzzle-bobble-sd", 9005, LOADER32, null, null);
        exercise("Capcom native SD preset", "capcom-nesica-sd", 9002, LOADER32, "G:\\Vampire\\game.exe", null, CAPCOM);
        exercise("Capcom case-insensitive paths", "capcom-nesica-sd", 9002, LOADER32.toLowerCase(), "g:\\Street Fighter Zero 3\\GAME.EXE", null, CAPCOM);
        exercise("Capcom no explicit preset", "", 9002, LOADER32, "G:\\Vampire\\game.exe", null);
        exercise("Capcom wrong controls", "capcom-nesica-sd", 9005, LOADER32, "G:\\Vampire\\game.exe", null);
        exercise("Capcom wrong x64 loader", "capcom-nesica-sd", 9002, LOADER64, "G:\\Vampire\\game.exe", null);
        exercise("Capcom old legacy loader", "capcom-nesica-sd", 9002, LOADER32.replace("Win32", "Win32Legacy"), "G:\\Vampire\\game.exe", null);
        exercise("Capcom wrong game", "capcom-nesica-sd", 9002, LOADER32, "G:\\Vampire\\other.exe", null);
        exercise("Capcom absent path", "capcom-nesica-sd", 9002, LOADER32, null, null);
        exercise("Capcom traversal", "capcom-nesica-sd", 9002, LOADER32, "G:\\Vampire\\..\\game.exe", null);
        exercise("Capcom preset suffix", "capcom-nesica-sd ", 9002, LOADER32, "G:\\Vampire\\game.exe", null);
        exercise("Lupin explicit preset", "lupin3-wine-jvs", 9078, LOADER32, "G:\\Lupin3\\game.exe", null, LUPIN);
        exercise("Lupin mixed-case path", "lupin3-wine-jvs", 9078, LOADER32.toLowerCase(), "g:\\Lupin3\\GAME.EXE", null, LUPIN);
        exercise("Lupin no preset", "", 9078, LOADER32, "G:\\Lupin3\\game.exe", null);
        exercise("Lupin wrong preset", "kof-mira-builtin-wined3d", 9078, LOADER32, "G:\\Lupin3\\game.exe", null);
        exercise("Lupin wrong controls", "lupin3-wine-jvs", 9002, LOADER32, "G:\\Lupin3\\game.exe", null);
        exercise("Lupin wrong loader", "lupin3-wine-jvs", 9078, LOADER64, "G:\\Lupin3\\game.exe", null);
        exercise("Lupin no loader", "lupin3-wine-jvs", 9078, null, "G:\\Lupin3\\game.exe", null);
        exercise("Lupin wrong game", "lupin3-wine-jvs", 9078, LOADER32, "G:\\Lupin3\\other.exe", null);
        exercise("Lupin no game", "lupin3-wine-jvs", 9078, LOADER32, null, null);
        exercise("Lupin traversal", "lupin3-wine-jvs", 9078, LOADER32, "G:\\Lupin3\\..\\game.exe", null);
        exercise("KOF exact", "kof-mira-builtin-wined3d", 9002, LOADER32, "G:\\KOF\\game.exe", null, KOF);
        exercise("KOF mixed-case DOS path", "kof-mira-builtin-wined3d", 9002,
            LOADER32.toLowerCase(), "g:\\KOF\\GAME.EXE", null, KOF);
        exercise("KOF wrong preset", "kof-xii-wine-gstreamer", 9002, LOADER32, "G:\\KOF\\game.exe", null);
        exercise("KOF wrong controls", "kof-mira-builtin-wined3d", 9022, LOADER32, "G:\\KOF\\game.exe", null);
        exercise("KOF wrong loader", "kof-mira-builtin-wined3d", 9002, LOADER64, "G:\\KOF\\game.exe", null);
        exercise("KOF wrong game", "kof-mira-builtin-wined3d", 9002, LOADER32, "G:\\KOF\\other.exe", null);
        exercise("KOF no game", "kof-mira-builtin-wined3d", 9002, LOADER32, null, null);
        exercise("KOF traversal", "kof-mira-builtin-wined3d", 9002, LOADER32, "G:\\KOF\\..\\game.exe", null);
        exercise("KOF preset suffix", "kof-mira-builtin-wined3d ", 9002, LOADER32, "G:\\KOF\\game.exe", null);

        String taiko = "G:\\Taiko\\Executable\\Release\\Taiko.exe";
        String auth = "G:\\Taiko\\Executable\\Release\\AMCUS\\AMAuthd.exe";
        exercise("Taiko exact local companion", "taiko-custom-resolution", 9022, LOADER64, taiko, auth, AUTH);
        exercise("Taiko case-insensitive local companion", "taiko-custom-resolution", 9022, LOADER64,
            taiko.toLowerCase(), auth.toUpperCase(), AUTH);
        exercise("Taiko no companion", "taiko-custom-resolution", 9022, LOADER64, taiko, null);
        exercise("Taiko companion another dump", "taiko-custom-resolution", 9022, LOADER64, taiko,
            "G:\\Tekken7\\AMCUS\\AMAuthd.exe");
        exercise("Taiko companion wrong filename", "taiko-custom-resolution", 9022, LOADER64, taiko,
            "G:\\Taiko\\Executable\\Release\\AMCUS\\Other.exe");
        exercise("Taiko wrong title", "taiko-custom-resolution", 9022, LOADER64,
            "G:\\Tekken7\\game.exe", "G:\\Tekken7\\AMCUS\\AMAuthd.exe");
        exercise("Taiko wrong preset", "wmmt-no-terminal", 9022, LOADER64, taiko, auth);
        exercise("Taiko wrong controls", "taiko-custom-resolution", 9012, LOADER64, taiko, auth);
        exercise("Taiko wrong loader", "taiko-custom-resolution", 9022, LOADER32, taiko, auth);

        for (String preset : new String[] {"wmmt-terminal", "wmmt-no-terminal"}) {
            exercise("Wangan public 5 family " + preset, preset, 9012, LOADER64,
                "G:\\Wangan\\wmn5r.exe", null, WANGAN);
            exercise("Wangan public 5 mixed-case " + preset, preset, 9012, LOADER64,
                "g:\\Wangan\\WMN5R.EXE", null, WANGAN);
            for (String basename : new String[] {"wmn6r.exe", "wmn6rr.exe", "wmn6.exe", "game.exe", "wmn5r.exe.bak"})
                exercise("Wangan exclude ambiguous/other " + basename, preset, 9012, LOADER64,
                    "G:\\Wangan\\" + basename, null);
            exercise("Wangan wrong controls", preset, 9002, LOADER64, "G:\\Wangan\\wmn5r.exe", null);
            exercise("Wangan wrong loader", preset, 9012, LOADER32, "G:\\Wangan\\wmn5r.exe", null);
        }
        for (String preset : new String[] {"", "wmmt3-yacard", "cxbxr-wmmt-yacard", "WMMT-TERMINAL"})
            exercise("Wangan wrong preset " + preset, preset, 9012, LOADER64, "G:\\Wangan\\wmn5r.exe", null);
        System.out.println("Prepared Wine opt-ins: PASS (" + assertions + " assertions; actual Activity method and EnvVars)");
    }
}

final class PreparedWinePolicyLaunch {
    String executable;
    String compatibilityPreset;
    int controlsProfileId;
}

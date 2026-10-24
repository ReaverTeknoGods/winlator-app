"""Exercise actual prepared payload/override code with fresh offline prefixes."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile


def block(source, signature):
    start = source.index(signature)
    cursor = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[cursor] == "{") - (source[cursor] == "}")
        cursor += 1
    return source[start:cursor]


def main():
    repo = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = args.output or Path(tempfile.mkdtemp(prefix="tpui-scard-"))
    output.mkdir(parents=True, exist_ok=True)
    activity = repo / "app/src/main/java/com/winlator/XServerDisplayActivity.java"
    workarounds = repo / "app/src/main/java/com/winlator/core/Win32AppWorkarounds.java"
    helper = repo / "app/src/main/java/com/winlator/teknoparrot/PreparedWinSCardCompatibility.java"
    paths = repo / "app/src/main/java/com/winlator/teknoparrot/PreparedGameExecutablePath.java"
    otoshu = repo / "app/src/main/java/com/winlator/teknoparrot/PreparedOtoshuApmBuiltin.java"
    source = activity.read_text(encoding="utf-8")
    actual = "\n".join(block(source, signature) for signature in (
        "private boolean prepareTeknoParrotCompatibilityPayload()",
        "public boolean requiresPreparedWinSCardPayload()"))
    fixture = output / "WinSCardPolicyActivity.java"
    fixture.write_text("""import java.io.*;
import com.winlator.teknoparrot.PreparedWinSCardCompatibility;
import com.winlator.teknoparrot.PreparedOtoshuApmBuiltin;
final class RootFS {
    static final String WINEPREFIX = "home/xuser/.wine";
    final File root; RootFS(File root) { this.root = root; }
    File getRootDir() { return root; }
}
final class WinSCardPolicyLaunch { String compatibilityPreset, executable, libraryDirectory; int controlsProfileId; }
final class Log { static void e(String tag, String message, Throwable error) {} static void e(String tag,String message) {} static void i(String tag,String message) {} }
final class WineUtils { static String dosToUnixPath(String path,Object container) { throw new AssertionError("unexpected Otoshu path translation"); } }
final class TestAssets {
    byte[] bytes; boolean fail; int opened;
    InputStream open(String name) throws IOException {
        opened++;
        if (!name.equals("teknoparrot/winscard-x86.dll") || fail) throw new IOException("asset missing");
        return new ByteArrayInputStream(bytes);
    }
}
final class WinSCardPolicyActivity {
    final RootFS rootFS; final TestAssets assets = new TestAssets();
    WinSCardPolicyLaunch preparedWindowsLaunch; String game;
    PreparedOtoshuApmBuiltin.Stage preparedOtoshuApmBuiltin; Object container;
    static final String TAG = "test";
    WinSCardPolicyActivity(File root, byte[] bytes) { rootFS = new RootFS(root); assets.bytes = bytes; }
    RootFS getRootFs() { return rootFS; }
    TestAssets getAssets() { return assets; }
    String findPreparedGameExecutable() { return game; }
    File getFilesDir() { throw new AssertionError("unexpected Otoshu files lookup"); }
    boolean isPreparedDosPathWithinDrive(String path,boolean allowRuntime) { throw new AssertionError("unexpected Otoshu path gate"); }
    boolean prepareApmLoaderSafePayload(boolean is64Bit) { throw new AssertionError("unexpected APM route"); }
    void clearPreparedApmPayload() {}
    boolean prepare() { return prepareTeknoParrotCompatibilityPayload(); }
""" + actual + "\n}\n", encoding="utf-8")
    ws = workarounds.read_text(encoding="utf-8")
    media = block(ws, 'else if (compatibilityPreset.equals("music-gungun-native-fullscreen"))')
    workaround_fixture = output / "WinSCardPolicyWorkarounds.java"
    workaround_fixture.write_text("""import com.winlator.core.EnvVars;
import com.winlator.teknoparrot.PreparedWinSCardCompatibility;
import java.util.HashMap;
final class WinSCardPolicyWorkarounds {
    final WinSCardPolicyActivity activity;
    final EnvVars environment = new EnvVars(); final HashMap<String, String> components = new HashMap<>();
    interface EnvVarsWorkaround { void apply(EnvVars vars); }
    interface WinComponentsWorkaround { void apply(HashMap<String, String> vars); }
    WinSCardPolicyWorkarounds(WinSCardPolicyActivity value) { activity = value; }
    void applyWorkaround(EnvVarsWorkaround callback) { callback.apply(environment); }
    void applyWorkaround(WinComponentsWorkaround callback) { callback.apply(components); }
    void applyPreset(String compatibilityPreset) { if (false) {} """ + media + "}\n" +
        block(ws, "private void applyWineGStreamerWorkaround()") + "\n}\n", encoding="utf-8")
    annotation = output / "NonNull.java"
    annotation.write_text("package androidx.annotation; public @interface NonNull {}\n", encoding="utf-8")
    env = repo / "app/src/main/java/com/winlator/core/EnvVars.java"
    test = repo / "tools/PreparedWinSCardCompatibilityTest.java"
    classes = output / "classes"
    classes.mkdir(exist_ok=True)
    built = subprocess.run([shutil.which("javac"), "-encoding", "UTF-8", "-d", str(classes),
        *map(str, [annotation, env, paths, helper, otoshu, test, fixture, workaround_fixture])], capture_output=True, text=True)
    result = {"scope": "Actual helper, extracted Activity payload selection/copy and Music media branch; offline only",
        "sources": {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in [activity, workarounds, helper, otoshu, test]},
        "compile_exit_code": built.returncode, "compile_stdout": built.stdout, "compile_stderr": built.stderr}
    if built.returncode == 0:
        tested = subprocess.run([shutil.which("java"), "-cp", str(classes), "PreparedWinSCardCompatibilityTest"], capture_output=True, text=True)
        result.update(test_exit_code=tested.returncode, stdout=tested.stdout, stderr=tested.stderr)
    (output / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    raise SystemExit(result.get("test_exit_code", built.returncode))


if __name__ == "__main__":
    main()

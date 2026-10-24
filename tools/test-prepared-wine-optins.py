"""Exercise the actual Activity Wine opt-in policy without Android or a device."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth, cursor = 1, opening + 1
    while depth:
        if source[cursor] == "{":
            depth += 1
        elif source[cursor] == "}":
            depth -= 1
        cursor += 1
    return source[start:cursor]


def main():
    repo = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--activity", type=Path, default=repo / "app/src/main/java/com/winlator/XServerDisplayActivity.java")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = args.output or Path(tempfile.mkdtemp(prefix="tpui-prepared-wine-"))
    output.mkdir(parents=True, exist_ok=True)
    source = args.activity.read_text(encoding="utf-8")
    call = "                applyPreparedOpenParrotWineOptIns(envVars);"
    if source.count(call) != 1 or source.index(call) < source.index("boolean useTaikoAuthService"):
        raise RuntimeError("The opt-in policy must run once after existing companion selection")
    actual = "\n".join(method(source, signature) for signature in (
        "private void applyPreparedOpenParrotWineOptIns(EnvVars envVars)",
        "private static boolean isWmmtCompatibilityPreset(String compatibilityPreset)"))
    fixture = output / "PreparedWinePolicyActivity.java"
    fixture.write_text("""import com.winlator.core.EnvVars;
import com.winlator.teknoparrot.PreparedGameExecutablePath;
final class PreparedWinePolicyActivity {
    PreparedWinePolicyLaunch preparedWindowsLaunch;
    String game;
    String findPreparedGameExecutable() { return game; }
    void apply(EnvVars environment) { applyPreparedOpenParrotWineOptIns(environment); }
""" + actual + "\n}\n", encoding="utf-8")
    annotation = output / "NonNull.java"
    annotation.write_text("package androidx.annotation; public @interface NonNull {}\n", encoding="utf-8")
    env = repo / "app/src/main/java/com/winlator/core/EnvVars.java"
    paths = repo / "app/src/main/java/com/winlator/teknoparrot/PreparedGameExecutablePath.java"
    test = repo / "tools/PreparedWineOptInsTest.java"
    classes = output / "classes"
    classes.mkdir(exist_ok=True)
    javac, java = shutil.which("javac"), shutil.which("java")
    if not javac or not java:
        raise RuntimeError("A Java JDK with javac/java is required")
    built = subprocess.run([javac, "-encoding", "UTF-8", "-d", str(classes),
        str(annotation), str(env), str(paths), str(test), str(fixture)], capture_output=True, text=True)
    result = {"scope": "Actual extracted Activity policy + real EnvVars/portable path validation; offline only, no gameplay qualification",
        "activity": str(args.activity), "activity_sha256": hashlib.sha256(args.activity.read_bytes()).hexdigest(),
        "test_sha256": hashlib.sha256(test.read_bytes()).hexdigest(),
        "compile_exit_code": built.returncode, "compile_stdout": built.stdout, "compile_stderr": built.stderr}
    if built.returncode == 0:
        tested = subprocess.run([java, "-cp", str(classes), "PreparedWineOptInsTest"], capture_output=True, text=True)
        result.update(test_exit_code=tested.returncode, stdout=tested.stdout, stderr=tested.stderr)
    (output / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    raise SystemExit(result.get("test_exit_code", built.returncode))


if __name__ == "__main__":
    main()

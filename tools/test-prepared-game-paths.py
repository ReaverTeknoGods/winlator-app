"""Exercise the real Activity path-selection methods with existing mount/file fixtures.

Requires javac/java only; no Gradle, APK, emulator, or Android device is used.
"""
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
    output = args.output or Path(tempfile.mkdtemp(prefix="tpui-prepared-paths-"))
    output.mkdir(parents=True, exist_ok=True)
    source = args.activity.read_text(encoding="utf-8")
    signatures = ["private String findPreparedGameExecutable()",
                  "private boolean isPreparedElfLoaderLaunch()",
                  "private boolean isPreparedDosPathWithinDrive(String dosPath, boolean directory)"]
    methods = [method(source, signature) for signature in signatures]
    if "private int findPreparedGameExecutableArgument()" in source:
        methods.append(method(source, "private int findPreparedGameExecutableArgument()"))
        index_wrapper = "int argument() { return findPreparedGameExecutableArgument(); }"
    else:
        index_wrapper = "int argument() { return java.util.Arrays.asList(preparedWindowsLaunch.arguments).indexOf(find()); }"
    fixture = """import com.winlator.teknoparrot.PreparedGameExecutablePath;
import java.io.File;
import java.io.IOException;
class PreparedGamePathActivity {
    PreparedPathLaunch preparedWindowsLaunch = new PreparedPathLaunch();
    PathContainer container = new PathContainer();
    String find() { return findPreparedGameExecutable(); }
    boolean contains(String path, boolean directory) throws IOException {
        return isPreparedDosPathWithinDrive(path, directory);
    }
""" + index_wrapper + "\n" + "\n".join(methods) + "\n}\n"
    fixture_path = output / "PreparedGamePathActivity.java"
    fixture_path.write_text(fixture, encoding="utf-8")
    helper = repo / "app/src/main/java/com/winlator/teknoparrot/PreparedGameExecutablePath.java"
    test = repo / "tools/PreparedGameExecutablePathTest.java"
    classes = output / "classes"
    classes.mkdir(exist_ok=True)
    javac, java = shutil.which("javac"), shutil.which("java")
    if not javac or not java:
        raise SystemExit("A Java JDK with javac/java is required.")
    compiled = subprocess.run([javac, "-encoding", "UTF-8", "-d", str(classes),
                               str(helper), str(test), str(fixture_path)], capture_output=True, text=True)
    result = {"scope": "offline JVM source/mount containment tests; no device qualification",
              "activity": str(args.activity), "activity_sha256": hashlib.sha256(args.activity.read_bytes()).hexdigest(),
              "helper_sha256": hashlib.sha256(helper.read_bytes()).hexdigest(),
              "compile_exit_code": compiled.returncode,
              "compile_stdout": compiled.stdout, "compile_stderr": compiled.stderr}
    if compiled.returncode == 0:
        tested = subprocess.run([java, "-cp", str(classes), "PreparedGameExecutablePathTest",
                                 str(output / "mounts")], capture_output=True, text=True)
        result.update(test_exit_code=tested.returncode, stdout=tested.stdout, stderr=tested.stderr)
    (output / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    raise SystemExit(result.get("test_exit_code", compiled.returncode))


if __name__ == "__main__":
    main()

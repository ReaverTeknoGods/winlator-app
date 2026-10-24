"""Compile the Activity's actual dispatch methods into a small JVM test fixture.

This requires javac/java only. Android collaborators are counters, allowing the
test to detect a second native input delivery after the real dispatch logic.
It does not build an APK or claim Android device input qualification.
"""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile


def extract_method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    cursor = opening + 1
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
    parser.add_argument("--touch-activity", type=Path,
                        help="Optionally substitute the touch method from a previous Activity for a regression check.")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = args.output or Path(tempfile.mkdtemp(prefix="tpui-input-isolation-"))
    output.mkdir(parents=True, exist_ok=True)
    source = args.activity.read_text(encoding="utf-8")
    signatures = ["public boolean dispatchGenericMotionEvent(MotionEvent event)",
                  "public boolean dispatchKeyEvent(KeyEvent event)",
                  "private boolean androidUiOwnsPreparedInput()",
                  "private boolean updatePreparedInputFocus(boolean forceAndroidUiOwnership)",
                  "private void installPreparedInputDrawerPolicy()",
                  "private void forwardPreparedWindowFocus(boolean hasFocus)",
                  "private void resumePreparedForwardedInput()",
                  "private void pausePreparedForwardedInput()",
                  "private void forwardVirtualTeknoParrotInput(",
                  "private boolean shouldForwardTaisenMahjongInputs()"]
    methods = [extract_method(source, signature) for signature in signatures]
    touch_source = args.touch_activity.read_text(encoding="utf-8") if args.touch_activity else source
    methods.append(extract_method(touch_source, "public boolean dispatchTouchEvent(MotionEvent event)"))
    fixture = """import com.winlator.teknoparrot.PreparedInputIsolation;
import com.winlator.teknoparrot.ForwardedInputMapping;
import com.winlator.teknoparrot.ForwardedInputProtocol;
class PreparedInputDispatchActivity extends StubActivity {
    PreparedLaunch preparedWindowsLaunch = new PreparedLaunch();
    ForwardedBridge forwardedInputBridge = new ForwardedBridge();
    final PreparedInputIsolation.FocusState preparedInputFocusState = new PreparedInputIsolation.FocusState();
    boolean forwardedInputActivityDiagnostic;
    boolean preparedDrawerInputBlocked, preparedUiTouchSequence;
    final SparseBooleanArray overlayTouchPointers = new SparseBooleanArray();
    InputControlsView inputControlsView = new InputControlsView();
    DrawerLayout drawerLayout = new DrawerLayout();
    WinHandler winHandler = new WinHandler();
    XServer xServer = new XServer();
    TouchpadView touchpadView = new TouchpadView();
    PreparedInputDispatchActivity() { installPreparedInputDrawerPolicy(); }
    void virtualInput(Binding binding, boolean down) { forwardVirtualTeknoParrotInput(binding, down, 0); }
    void resumeInput() { resumePreparedForwardedInput(); }
    void pauseInput() { pausePreparedForwardedInput(); }
    void windowFocusInput(boolean focused) { windowFocus=focused; forwardPreparedWindowFocus(focused); }
""" + "\n".join(methods) + "\n}\n"
    (output / "PreparedInputDispatchActivity.java").write_text(fixture, encoding="utf-8")
    android = output / "android/view"
    android.mkdir(parents=True, exist_ok=True)
    (android / "InputDevice.java").write_text(
        "package android.view; public final class InputDevice { public static final int SOURCE_JOYSTICK=0x01000010; }\n",
        encoding="utf-8")
    package = repo / "teknoparrot-bridge/src/main/java/com/winlator/teknoparrot"
    sources = [repo / "app/src/main/java/com/winlator/teknoparrot/PreparedInputIsolation.java",
               package / "ForwardedInputMapping.java", package / "ForwardedInputProtocol.java",
               repo / "tools/PreparedInputDispatchTest.java", output / "PreparedInputDispatchActivity.java",
               android / "InputDevice.java"]
    classes = output / "classes"
    classes.mkdir(exist_ok=True)
    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        raise SystemExit("A Java JDK with javac/java is required.")
    subprocess.run([javac, "-encoding", "UTF-8", "-d", str(classes), *map(str, sources)], check=True)
    result = subprocess.run([java, "-cp", str(classes), "PreparedInputDispatchTest"], capture_output=True, text=True)
    (output / "test-output.txt").write_text(result.stdout + result.stderr, encoding="utf-8")
    receipt = {"activity_source": str(args.activity.resolve()),
               "activity_sha256": hashlib.sha256(args.activity.read_bytes()).hexdigest(),
               "dispatch_methods": "Extracted from the Activity under test; not copied into the test source.",
               "apk_built": False, "device_tested": False,
               "exit_code": result.returncode, "output": result.stdout + result.stderr}
    if args.touch_activity:
        receipt["substituted_touch_source"] = str(args.touch_activity.resolve())
        receipt["substituted_touch_source_sha256"] = hashlib.sha256(args.touch_activity.read_bytes()).hexdigest()
    (output / "result.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(result.stdout + result.stderr, end="")
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()

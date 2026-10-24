"""Run actual Activity setup/show/hide and native mouse methods in a JVM fixture.

No APK build or device access. Android collaborators are observable seams;
production method bodies are extracted unchanged from the selected source.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT/'app/src/main/java/com/winlator'


def method(text, signature):
    start = text.index(signature)
    opening = text.index('{', start)
    depth, end = 1, opening + 1
    while depth:
        if text[end] == '{':
            depth += 1
        elif text[end] == '}':
            depth -= 1
        end += 1
    return text[start:end]


FIXTURE = r'''
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class PreparedTouchpadChecks {
    static int checks;
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    static ActivityHarness prepared(String preset, int savedProfile) {
        ActivityHarness a = new ActivityHarness();
        a.preparedWindowsLaunch = new Launch(preset);
        a.hasPreparedWindowsLaunch = true;
        a.preferences.savedProfile = savedProfile;
        a.setup();
        return a;
    }
    static void rawMouse(ActivityHarness a, boolean expected, String label) {
        int before = a.xServer.rawEvents;
        boolean down = a.touchpadView.onExternalMouseEvent(new MotionEvent(MotionEvent.ACTION_BUTTON_PRESS));
        boolean up = a.touchpadView.onExternalMouseEvent(new MotionEvent(MotionEvent.ACTION_BUTTON_RELEASE));
        boolean hover = a.touchpadView.onExternalMouseEvent(new MotionEvent(MotionEvent.ACTION_HOVER_MOVE));
        boolean scroll = a.touchpadView.onExternalMouseEvent(new MotionEvent(MotionEvent.ACTION_SCROLL));
        check(down == expected && up == expected && hover == expected && scroll == expected, label+": mouse event consumption");
        check((a.xServer.rawEvents > before) == expected, label+": actual XServer mouse delivery");
        before = a.xServer.rawEvents;
        boolean captured = a.inputControlsView.captured.onCapturedPointer(new View(), new MotionEvent(MotionEvent.ACTION_MOVE));
        check(captured == expected, label+": actual setup callback gates captured mouse");
        check((a.xServer.rawEvents > before) == expected, label+": captured pointer delivery");
    }
    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("hide-none")) {
            ActivityHarness a = prepared("puzzle-bobble-sd", 9005);
            check(!a.touchpadView.isEnabled(), "ordinary shown overlay starts isolated");
            a.hide();
            check(!a.touchpadView.isEnabled(), "selecting None must not expose raw mouse");
            rawMouse(a, false, "isolated None regression");
            System.out.println("Prepared touchpad hide-None checks: "+checks+" PASS");
            return;
        }
        ActivityHarness zero = prepared("puzzle-bobble-sd", 0);
        check(zero.inputControlsView.profile == null, "saved None skips showing a profile");
        check(!zero.touchpadView.isEnabled(), "startup saved None must not expose raw mouse");
        check(!zero.touchpadView.direct && !zero.xServerView.renderer.cursor, "ordinary prepared startup mode/cursor");
        rawMouse(zero, false, "startup None");
        zero.hide();
        check(!zero.touchpadView.isEnabled(), "repeated None stays disabled");
        rawMouse(zero, false, "repeat None");

        for (String preset : new String[]{"puzzle-bobble-sd", "portrait-window-counter-clockwise", "shared-raw-thrills-gun", "unknown", "direct-touch-jvs-extra"}) {
            ActivityHarness a = prepared(preset, 9005);
            check(a.inputControlsView.profile != null, "normal prepared profile shown");
            check(!a.touchpadView.isEnabled(), "ordinary prepared show is isolated "+preset);
            rawMouse(a, false, "shown "+preset);
            a.hide();
            check(a.inputControlsView.profile == null && a.inputControlsView.visibility == View.GONE, "None still hides overlay");
            check(!a.touchpadView.isEnabled() && !a.touchpadView.direct && !a.xServerView.renderer.cursor, "ordinary prepared None isolated "+preset);
            rawMouse(a, false, "hidden "+preset);
        }
        for (String preset : new String[]{"shared-jvs-dual-io", "gaia-attack4-media", "haunted-museum2-media", "music-gungun-native-fullscreen", "direct-touch-jvs"}) {
            for (int saved : new int[]{0, 9005}) {
                ActivityHarness a = prepared(preset, saved);
                check(a.touchpadView.isEnabled() && a.touchpadView.direct && !a.xServerView.renderer.cursor, "explicit direct touch at startup/show "+preset);
                rawMouse(a, true, "direct before None "+preset);
                a.hide();
                check(a.touchpadView.isEnabled() && a.touchpadView.direct && !a.xServerView.renderer.cursor, "explicit direct touch survives None "+preset);
                rawMouse(a, true, "direct after None "+preset);
            }
        }
        ActivityHarness legacy = new ActivityHarness();
        legacy.setup();
        check(legacy.touchpadView.isEnabled() && !legacy.touchpadView.direct && !legacy.xServerView.renderer.cursor, "legacy initial state unchanged");
        rawMouse(legacy, true, "legacy initial");
        legacy.show(new ControlsProfile(true));
        check(!legacy.touchpadView.isEnabled() && !legacy.touchpadView.direct && !legacy.xServerView.renderer.cursor, "legacy disable-mouse profile retained");
        legacy.hide();
        check(legacy.touchpadView.isEnabled() && legacy.xServerView.renderer.cursor, "legacy None enables pointer/cursor as before");
        rawMouse(legacy, true, "legacy None");
        legacy.show(new ControlsProfile(false));
        check(legacy.touchpadView.isEnabled() && !legacy.touchpadView.direct && legacy.xServerView.renderer.cursor, "legacy normal show retained");
        legacy.xServerView.renderer.cursor = false;
        legacy.hide();
        check(!legacy.xServerView.renderer.cursor, "legacy enabled-pointer hide leaves cursor state unchanged");
        System.out.println("Prepared touchpad actual-method checks: "+checks+" PASS");
    }
}

class ActivityHarness {
    static final int TEKNOPARROT_ARCADE_PROFILE_ID=9000, VIRTUAL_GAMEPAD_PROFILE_ID=1, TEKNOPARROT_LUIGI_PROFILE_ID=9090;
    Launch preparedWindowsLaunch;
    boolean hasPreparedWindowsLaunch, capturePointerOnExternalMouse;
    float globalCursorSpeed;
    final Preferences preferences=new Preferences();
    final XServer xServer=new XServer();
    XServerView xServerView;
    TouchpadView touchpadView;
    InputControlsView inputControlsView;
    final InputControlsManager inputControlsManager=new InputControlsManager();
    final DrawerLayout drawerLayout=new DrawerLayout();
    ForwardedBridge forwardedInputBridge=new ForwardedBridge();
    Container container;
    Shortcut shortcut;
    FrameRating frameRating;
    FrameLayout findViewById(int ignored) { return new FrameLayout(); }
    boolean shouldAspectFitPreparedOpenParrotWindow(boolean value) { return value; }
    boolean shouldLimitPreparedEnEinsToNativeSize() { return false; }
    float[] getPreparedPointerViewport() { return null; }
    String getTeknoParrotControlsPreferenceKey(int value) { return "controls_"+value; }
    void applyPreparedSurfaceFrameRate() {}
    void forwardVirtualTeknoParrotInput(Binding binding, boolean down, float offset) {}
    void setup() { setupUI(); }
    void show(ControlsProfile profile) { showInputControls(profile); }
    void hide() { hideInputControls(); }
    /* ACTIVITY_METHODS */
}
class TouchpadView extends View {
    static final float CURSOR_ACCELERATION_THRESHOLD=100, CURSOR_ACCELERATION=2;
    final XServer xServer;
    float sensitivity=1;
    float[] xform=new float[0];
    boolean direct;
    TouchpadView(ActivityHarness activity, XServer server, boolean capture) { xServer=server; }
    void setSensitivity(float value) { sensitivity=value; }
    void setDirectTouchMode(boolean value) { direct=value; }
    void setMoveCursorToTouchpoint(boolean value) {}
    void setFourFingersTapCallback(Runnable callback) {}
    void setPointerButtonLeftEnabled(boolean value) {}
    void setPointerButtonRightEnabled(boolean value) {}
    /* TOUCHPAD_METHODS */
}
class View {
    static final int GONE=8, VISIBLE=0;
    private boolean enabled=true;
    int visibility;
    boolean isEnabled() { return enabled; }
    void setEnabled(boolean value) { enabled=value; }
    void setVisibility(int value) { visibility=value; }
    void invalidate() {}
    void requestFocus() {}
    interface OnCapturedPointerListener { boolean onCapturedPointer(View view, MotionEvent event); }
}
class FrameLayout extends View { void addView(Object view) {} }
class XServerView extends View {
    final GLRenderer renderer=new GLRenderer();
    XServerView(ActivityHarness activity, XServer server) {}
    GLRenderer getRenderer() { return renderer; }
}
class GLRenderer {
    boolean cursor;
    void setCursorVisible(boolean value) { cursor=value; }
    void setCursorColor(int value) {}
    void setCursorScale(float value) {}
    void setPreparedWindowAspectFit(boolean value) {}
    void setPreparedEnEinsCenteredNativeSize(boolean value) {}
    void setForceWindowsFullscreen(boolean value) {}
    void setRotatePreparedWindowsCounterClockwise(boolean value) {}
    void setScreenOffsetYRelativeToCursor(float value) {}
}
class InputControlsView extends View {
    static final float DEFAULT_OVERLAY_OPACITY=1;
    ControlsProfile profile;
    OnCapturedPointerListener captured;
    InputControlsView(ActivityHarness activity) {}
    void setOverlayOpacity(float value) {}
    void setTouchpadView(TouchpadView value) {}
    void setOnCapturedPointerListener(OnCapturedPointerListener value) { captured=value; }
    void setXServer(XServer value) {}
    void setInputEventListener(InputListener value) {}
    void setHideStickControls(boolean value) {}
    void setShowTouchscreenControls(boolean value) {}
    void setProfile(ControlsProfile value) { profile=value; }
    interface InputListener { void onInputEvent(Binding binding, boolean down, float offset); }
}
class ControlsProfile {
    final boolean disabled;
    ControlsProfile(boolean value) { disabled=value; }
    float getCursorSpeed() { return 1; }
    boolean isDisableMouseInput() { return disabled; }
}
class InputControlsManager { ControlsProfile getProfile(int id) { return new ControlsProfile(false); } }
class Preferences {
    int savedProfile;
    int getInt(String key,int fallback) { return key.startsWith("controls_")?savedProfile:fallback; }
    float getFloat(String key,float fallback) { return fallback; }
    boolean getBoolean(String key,boolean fallback) { return fallback; }
}
class Launch {
    String compatibilityPreset, displayMode="centered";
    int controlsProfileId=9005;
    Launch(String value) { compatibilityPreset=value; }
}
class XServer {
    int rawEvents;
    void setRenderer(GLRenderer value) {}
    void injectPointerButtonPress(Pointer.Button value) { rawEvents++; }
    void injectPointerButtonRelease(Pointer.Button value) { rawEvents++; }
    void injectPointerMove(int x,int y) { rawEvents++; }
    void injectPointerMoveDelta(int x,int y) { rawEvents++; }
}
class Pointer { enum Button { BUTTON_LEFT,BUTTON_RIGHT,BUTTON_SCROLL_DOWN,BUTTON_SCROLL_UP } }
class InputDevice { static final int SOURCE_MOUSE=0x2002; }
class MotionEvent {
    static final int ACTION_BUTTON_PRESS=11,ACTION_BUTTON_RELEASE=12,ACTION_HOVER_MOVE=7,ACTION_SCROLL=8,ACTION_MOVE=2;
    static final int BUTTON_PRIMARY=1,BUTTON_SECONDARY=2,AXIS_VSCROLL=9;
    int source=InputDevice.SOURCE_MOUSE;
    final int action;
    MotionEvent(int value) { action=value; }
    boolean isFromSource(int value) { return (source&value)==value; }
    int getActionButton() { return BUTTON_PRIMARY; }
    int getAction() { return action; }
    float getX() { return 10; }
    float getY() { return 20; }
    float getAxisValue(int axis) { return -1; }
    int getSource() { return source; }
    void setSource(int value) { source=value; }
}
class XForm { static float[] transformPoint(float[] matrix,float x,float y) { return new float[]{x,y}; } }
class Mathf { static int roundPoint(float value) { return Math.round(value); } }
class DrawerLayout extends View { boolean isDrawerOpen(int gravity) { return false; } void openDrawer(int gravity) {} }
class GravityCompat { static final int START=0; }
class ForwardedBridge { void setPointerViewportProvider(Supplier<float[]> supplier) {} }
class Container { int getHUDMode() { return 0; } }
class Shortcut { String getExtra(String key) { return ""; } String getExtra(String key,String fallback) { return fallback; } }
class FrameRating extends View { enum Mode { DISABLED } FrameRating(ActivityHarness activity) {} void setMode(Mode value) {} }
class MainActivity { static final boolean DEBUG_MODE=false; }
class AppUtils { static View createDebugMsgTextView(ActivityHarness activity) { return new View(); } static void observeSoftKeyboardVisibility(DrawerLayout drawer,Consumer<Float> callback) {} }
class R { static final class id { static final int FLXServerDisplay=0; } }
enum Binding { NONE }
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--activity', type=Path, default=JAVA/'XServerDisplayActivity.java')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--scenario', choices=('all', 'hide-none'), default='all')
    args = parser.parse_args()
    source = args.activity.read_text(encoding='utf-8')
    touchpad = JAVA/'widget/TouchpadView.java'
    signatures = ['private void setupUI()', 'private void showInputControls(ControlsProfile profile)',
                  'private void hideInputControls()', 'private boolean usesPreparedSharedJvsDualIo()']
    if 'private void applyPreparedTouchpadPolicy()' in source:
        signatures.append('private void applyPreparedTouchpadPolicy()')
    activity_methods = '\n'.join(method(source, signature) for signature in signatures)
    touch_methods = '\n'.join(method(touchpad.read_text(encoding='utf-8'), signature) for signature in (
        'public boolean onExternalMouseEvent(MotionEvent event)',
        'public boolean onCapturedPointer(View view, MotionEvent event)'))
    fixture = FIXTURE.replace('/* ACTIVITY_METHODS */', activity_methods).replace('/* TOUCHPAD_METHODS */', touch_methods)
    javac, java = shutil.which('javac'), shutil.which('java')
    if not javac or not java:
        raise SystemExit('A JDK with javac/java is required')
    temporary = tempfile.TemporaryDirectory(prefix='tpui-prepared-touchpad-') if args.output is None else None
    output = Path(temporary.name) if temporary else args.output
    output.mkdir(parents=True, exist_ok=True)
    try:
        path = output/'PreparedTouchpadChecks.java'
        path.write_text(fixture, encoding='utf-8')
        subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(output), str(path)], check=True)
        command = [java, '-cp', str(output), 'PreparedTouchpadChecks']
        if args.scenario != 'all':
            command.append(args.scenario)
        result = subprocess.run(command, capture_output=True, text=True)
        receipt = {'activity': str(args.activity.resolve()),
                   'activity_sha256': hashlib.sha256(args.activity.read_bytes()).hexdigest(),
                   'touchpad': str(touchpad), 'touchpad_sha256': hashlib.sha256(touchpad.read_bytes()).hexdigest(),
                   'methods': signatures + ['TouchpadView.onExternalMouseEvent', 'TouchpadView.onCapturedPointer'],
                   'exit_code': result.returncode, 'output': result.stdout + result.stderr,
                   'scenario': args.scenario,
                   'device_commands': 0, 'apk_builds': 0}
        (output/'test-receipt.json').write_text(json.dumps(receipt, indent=2)+'\n', encoding='utf-8')
        print(result.stdout + result.stderr, end='')
        raise SystemExit(result.returncode)
    finally:
        if temporary:
            temporary.cleanup()


if __name__ == '__main__':
    main()

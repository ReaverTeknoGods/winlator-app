import com.winlator.teknoparrot.ForwardedInputMapping;
import com.winlator.teknoparrot.ForwardedInputProtocol;

/** Runs the real Activity dispatch methods against observable Android seams. */
public final class PreparedInputDispatchTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void noNative(PreparedInputDispatchActivity activity, String name) {
        check(activity.winHandler.keyCalls == 0, name + ": no native WinHandler key");
        check(activity.xServer.keyboard.calls == 0, name + ": no native X11 keyboard");
        check(activity.winHandler.motionCalls == 0, name + ": no native XInput motion");
    }

    public static void main(String[] arguments) {
        // Down, repeat and release must all be consumed, including unmapped keys.
        for (int key : new int[]{8, 12, 19, 22, 29, 62, 66, 96, 108, 131, 132, 200}) {
            for (int action : new int[]{0, 1, 2}) {
                PreparedInputDispatchActivity activity = new PreparedInputDispatchActivity();
                KeyEvent event = new KeyEvent(key, action, action == 2 ? 3 : 0, false, false);
                check(activity.dispatchKeyEvent(event), "prepared key consumed " + key + "/" + action);
                check(activity.forwardedInputBridge.keyCalls == 1, "bridge receives key once");
                check(activity.baseKeyCalls == 0, "gameplay does not reach Android fallback");
                noNative(activity, "prepared key " + key);
            }
        }
        for (int key : new int[]{131, 132}) {
            PreparedInputDispatchActivity activity = new PreparedInputDispatchActivity();
            activity.dispatchKeyEvent(new KeyEvent(key, 0, 0, false, false));
            check(activity.forwardedInputBridge.lastButton ==
                    (key == 131 ? ForwardedInputProtocol.BUTTON_TEST : ForwardedInputProtocol.BUTTON_SERVICE),
                    "F1/F2 retain the real TPI1 Test/Service mapping");
            noNative(activity, "F1/F2");
        }
        PreparedInputDispatchActivity remapped = new PreparedInputDispatchActivity();
        remapped.inputControlsView.mapsKey = true;
        remapped.dispatchKeyEvent(new KeyEvent(96, 0, 0, false, true));
        check(remapped.inputControlsView.profileKeyDeliveries == 1, "profile key assignment retained");
        check(remapped.forwardedInputBridge.keyCalls == 0, "profile key avoids hardcoded bridge duplicate");
        noNative(remapped, "profile key");

        for (int source : new int[]{0x01000010, 0x401}) {
            PreparedInputDispatchActivity activity = new PreparedInputDispatchActivity();
            check(activity.dispatchGenericMotionEvent(new MotionEvent(source, true)), "controller motion consumed");
            check(activity.forwardedInputBridge.motionCalls == 1, "fallback bridge axes delivered once");
            check(activity.baseMotionCalls == 0, "controller axes skip Android fallback");
            noNative(activity, "controller motion");
        }
        PreparedInputDispatchActivity remappedAxes = new PreparedInputDispatchActivity();
        remappedAxes.inputControlsView.mapsMotion = true;
        remappedAxes.dispatchGenericMotionEvent(new MotionEvent(0x01000010, true));
        check(remappedAxes.inputControlsView.profileMotionDeliveries == 1, "profile axis assignment retained");
        check(remappedAxes.forwardedInputBridge.motionCalls == 0, "profile axes avoid hardcoded duplicate");
        noNative(remappedAxes, "profile axes");

        // Back, volume, power, home, menu and notification belong to Android.
        for (int systemKey : new int[]{4, 24, 25, 164, 26, 3, 82, 83}) {
            PreparedInputDispatchActivity activity = new PreparedInputDispatchActivity();
            activity.dispatchKeyEvent(new KeyEvent(systemKey, 0, 0, true, false));
            check(activity.baseKeyCalls == 1, "Android receives system key " + systemKey);
            check(activity.forwardedInputBridge.keyCalls == 0, "system key is not a cabinet control");
            check(activity.inputControlsView.keyCalls == 0, "system key bypasses profile remap");
            noNative(activity, "system key");
        }
        for (int uiState = 0; uiState < 4; uiState++) {
            PreparedInputDispatchActivity activity = new PreparedInputDispatchActivity();
            if (uiState == 0) activity.focusedView = new View(true);
            if (uiState == 1) activity.drawerLayout.open = true;
            if (uiState == 2) activity.windowFocus = false;
            if (uiState == 3) activity.inputControlsView.editMode = true;
            activity.dispatchKeyEvent(new KeyEvent(29, 0, 0, false, false));
            activity.dispatchGenericMotionEvent(new MotionEvent(0x01000010, true));
            check(activity.baseKeyCalls == 1 && activity.baseMotionCalls == 1,
                    "editor/drawer/notification owns input " + uiState);
            check(activity.forwardedInputBridge.keyCalls == 0 && activity.forwardedInputBridge.motionCalls == 0,
                    "Android UI does not activate cabinet controls");
            noNative(activity, "Android UI");
        }
        // Ordinary Winlator launches retain their keyboard, XInput and mouse paths.
        for (boolean hasBridge : new boolean[]{false, true}) {
            PreparedInputDispatchActivity activity = new PreparedInputDispatchActivity();
            activity.preparedWindowsLaunch = null;
            if (!hasBridge) activity.forwardedInputBridge = null;
            activity.dispatchKeyEvent(new KeyEvent(29, 0, 0, false, false));
            activity.dispatchGenericMotionEvent(new MotionEvent(0x01000010, true));
            check(activity.winHandler.keyCalls == 1 && activity.xServer.keyboard.calls == 1,
                    "unprepared legacy keyboard remains");
            check(activity.winHandler.motionCalls == 1 && activity.touchpadView.calls == 1,
                    "unprepared legacy motion remains");
        }
        PreparedInputDispatchActivity noBridge = new PreparedInputDispatchActivity();
        noBridge.forwardedInputBridge = null;
        noBridge.dispatchKeyEvent(new KeyEvent(29, 0, 0, false, false));
        check(noBridge.xServer.keyboard.calls == 1, "unauthenticated launch does not enable TPUI routing");

        PreparedInputDispatchActivity diagnostic = new PreparedInputDispatchActivity();
        diagnostic.forwardedInputActivityDiagnostic = true;
        diagnostic.dispatchKeyEvent(new KeyEvent(12, 0, 0, false, false));
        diagnostic.dispatchGenericMotionEvent(new MotionEvent(0x01000010, true));
        check(diagnostic.forwardedInputBridge.keyCalls == 1 && diagnostic.forwardedInputBridge.motionCalls == 1,
                "diagnostic input observer retained");
        check(diagnostic.baseKeyCalls == 1 && diagnostic.baseMotionCalls == 1,
                "diagnostic Android dispatch retained");
        noNative(diagnostic, "diagnostic");

        PreparedInputDispatchActivity mouse = new PreparedInputDispatchActivity();
        mouse.dispatchGenericMotionEvent(new MotionEvent(0x2002, false));
        check(mouse.touchpadView.calls == 1, "mouse motion retains existing pointer behavior");

        PreparedInputDispatchActivity held = new PreparedInputDispatchActivity();
        held.dispatchKeyEvent(new KeyEvent(96, 0, 0, false, true));
        check(held.forwardedInputBridge.buttonHeld, "button held before opening drawer");
        held.drawerLayout.open = true;
        held.dispatchKeyEvent(new KeyEvent(96, 1, 0, false, true));
        check(!held.forwardedInputBridge.buttonHeld, "drawer transition releases previously held cabinet input");
        check(held.forwardedInputBridge.focusLost == 1 && held.forwardedInputBridge.keyCalls == 1,
                "drawer sends one focus release and no gameplay release event");
        held.dispatchGenericMotionEvent(new MotionEvent(0x01000010, true));
        check(held.forwardedInputBridge.focusLost == 1, "repeated UI events do not flood focus frames");
        held.drawerLayout.open = false;
        held.dispatchKeyEvent(new KeyEvent(97, 0, 0, false, true));
        check(held.forwardedInputBridge.focusGained == 1 && held.forwardedInputBridge.buttonHeld,
                "gameplay transition restores focus before the next cabinet input");
        held.dispatchKeyEvent(new KeyEvent(24, 0, 0, true, false));
        check(held.forwardedInputBridge.buttonHeld && held.forwardedInputBridge.focusLost == 1,
                "system Volume alone does not clear held cabinet input");
        held.inputControlsView.editMode = true;
        held.dispatchGenericMotionEvent(new MotionEvent(0x01000010, true));
        check(!held.forwardedInputBridge.buttonHeld && held.forwardedInputBridge.focusLost == 2,
                "controller motion entering editor releases held cabinet input");
        held.inputControlsView.editMode = false;
        held.dispatchGenericMotionEvent(new MotionEvent(0x01000010, true));
        check(held.forwardedInputBridge.focusGained == 2, "keyboard and axis dispatch share focus transition state");
        noNative(held, "held-input transitions");

        PreparedInputDispatchActivity touch = new PreparedInputDispatchActivity();
        touch.dispatchTouchEvent(MotionEvent.touch(MotionEvent.ACTION_DOWN, 1));
        check(touch.forwardedInputBridge.pointerCalls == 1 && touch.forwardedInputBridge.pointerHeld,
                "gameplay surface touch reaches TPI1 pointer path");
        touch.drawerLayout.openDrawer();
        check(!touch.forwardedInputBridge.pointerHeld && touch.forwardedInputBridge.focusLost == 1,
                "touch-only drawer opening immediately releases held gun pointer");
        touch.touchCallback = () -> touch.virtualInput(Binding.GAMEPAD_BUTTON_A, true);
        touch.dispatchTouchEvent(MotionEvent.touch(MotionEvent.ACTION_MOVE, 1));
        check(touch.forwardedInputBridge.pointerCalls == 1, "drawer touch is not forwarded as gun aim/fire");
        check(touch.forwardedInputBridge.virtualButtonCalls == 0, "drawer touch cannot invoke virtual cabinet buttons");
        touch.touchCallback = () -> touch.drawerLayout.closeDrawer();
        touch.dispatchTouchEvent(MotionEvent.touch(MotionEvent.ACTION_MOVE, 1));
        check(touch.forwardedInputBridge.focusGained == 1, "touch-only drawer closing restores effective focus");
        touch.touchCallback = () -> touch.virtualInput(Binding.GAMEPAD_BUTTON_A, true);
        touch.dispatchTouchEvent(MotionEvent.touch(MotionEvent.ACTION_UP, 1));
        check(touch.forwardedInputBridge.pointerCalls == 1 && touch.forwardedInputBridge.virtualButtonCalls == 0,
                "UI-owned gesture tail stays excluded after drawer closes");
        touch.touchCallback = null;
        touch.dispatchTouchEvent(MotionEvent.touch(MotionEvent.ACTION_DOWN, 2));
        check(touch.forwardedInputBridge.pointerCalls == 2 && touch.forwardedInputBridge.pointerHeld,
                "next independent gameplay touch resumes without keyboard/controller input");
        touch.dispatchTouchEvent(MotionEvent.touch(MotionEvent.ACTION_CANCEL, 2));
        check(!touch.forwardedInputBridge.pointerHeld, "cancel releases game pointer");

        PreparedInputDispatchActivity overlay = new PreparedInputDispatchActivity();
        overlay.inputControlsView.overControl = true;
        overlay.touchCallback = () -> overlay.virtualInput(Binding.KEY_F1, true);
        overlay.dispatchTouchEvent(MotionEvent.touch(MotionEvent.ACTION_DOWN, 3));
        check(overlay.forwardedInputBridge.pointerCalls == 0, "overlay press excludes direct gun pointer");
        check(overlay.forwardedInputBridge.virtualButtonCalls == 1 &&
                        overlay.forwardedInputBridge.lastButton == ForwardedInputProtocol.BUTTON_TEST,
                "gameplay overlay F1 still forwards cabinet Test");

        for (int uiState=0; uiState<3; uiState++) {
            PreparedInputDispatchActivity owner = new PreparedInputDispatchActivity();
            owner.virtualInput(Binding.GAMEPAD_BUTTON_A, true);
            if(uiState==0)owner.focusedView=new View(true);
            if(uiState==1)owner.inputControlsView.editMode=true;
            if(uiState==2)owner.windowFocus=false;
            owner.touchCallback=()->owner.virtualInput(Binding.GAMEPAD_BUTTON_A,true);
            owner.dispatchTouchEvent(MotionEvent.touch(MotionEvent.ACTION_DOWN, 4));
            check(owner.forwardedInputBridge.focusLost==1 && !owner.forwardedInputBridge.buttonHeld,
                    "touch-only editor/notification transition releases prior input " + uiState);
            check(owner.forwardedInputBridge.pointerCalls==0 && owner.forwardedInputBridge.virtualButtonCalls==1,
                    "UI-owned editor/notification touch is excluded " + uiState);
            owner.dispatchTouchEvent(MotionEvent.touch(MotionEvent.ACTION_UP,4));
            owner.focusedView=null;owner.inputControlsView.editMode=false;owner.windowFocus=true;
            owner.dispatchTouchEvent(MotionEvent.touch(MotionEvent.ACTION_DOWN,5));
            check(owner.forwardedInputBridge.focusGained==1 && owner.forwardedInputBridge.pointerCalls==1,
                    "touch-only return to game restores input " + uiState);
        }
        PreparedInputDispatchActivity lifecycle = new PreparedInputDispatchActivity();
        lifecycle.drawerLayout.openDrawer();
        int beforeResume = lifecycle.forwardedInputBridge.focusGained;
        lifecycle.pauseInput();lifecycle.resumeInput();lifecycle.windowFocusInput(true);
        check(lifecycle.forwardedInputBridge.focusGained==beforeResume,
                "resume/window focus cannot restore cabinet focus while drawer owns input");
        lifecycle.drawerLayout.closeDrawer();
        check(lifecycle.forwardedInputBridge.focusGained==beforeResume+1,
                "drawer close restores focus after lifecycle transitions");
        lifecycle.pauseInput();lifecycle.resumeInput();
        check(lifecycle.forwardedInputBridge.focusGained==beforeResume+2,
                "ordinary prepared pause/resume restores effective gameplay focus");

        PreparedInputDispatchActivity legacyTouch=new PreparedInputDispatchActivity();
        legacyTouch.preparedWindowsLaunch=null;legacyTouch.drawerLayout.openDrawer();
        legacyTouch.touchCallback=()->legacyTouch.virtualInput(Binding.GAMEPAD_BUTTON_A,true);
        legacyTouch.dispatchTouchEvent(MotionEvent.touch(MotionEvent.ACTION_DOWN,6));
        check(legacyTouch.forwardedInputBridge.pointerCalls==1 && legacyTouch.forwardedInputBridge.virtualButtonCalls==1,
                "unprepared pointer and virtual observer behavior remains unchanged");
        legacyTouch.pauseInput();legacyTouch.resumeInput();
        check(legacyTouch.forwardedInputBridge.pauseCalls==1 && legacyTouch.forwardedInputBridge.resumeCalls==1,
                "unprepared lifecycle bridge callbacks remain unchanged");
        // The tile-key surface is exclusive to the exact prepared Mahjong route.
        for (int variant=0;variant<8;variant++) {
            PreparedInputDispatchActivity mahjong=new PreparedInputDispatchActivity();
            mahjong.preparedWindowsLaunch.controlsProfileId=9079;
            mahjong.preparedWindowsLaunch.productionBridge=true;
            mahjong.preparedWindowsLaunch.compatibilityPreset="taito-legacy-scard";
            mahjong.preparedWindowsLaunch.executable="E:\\TeknoParrotRuntime\\OpenParrotWin32\\OpenParrotLoader.exe";
            if(variant==1)mahjong.preparedWindowsLaunch.controlsProfileId=9002;
            if(variant==2)mahjong.preparedWindowsLaunch.productionBridge=false;
            if(variant==3)mahjong.preparedWindowsLaunch.compatibilityPreset="none";
            if(variant==4)mahjong.preparedWindowsLaunch.executable="E:\\Other\\OpenParrotLoader.exe";
            if(variant==5)mahjong.preparedWindowsLaunch.executable=null;
            if(variant==6)mahjong.preparedWindowsLaunch=null;
            if(variant==7)mahjong.preparedWindowsLaunch.executable="e:/TeknoParrotRuntime/OpenParrotWin32/OpenParrotLoader.exe";
            for(int tile=0;tile<14;tile++)for(boolean down:new boolean[]{true,false}){
                int calls=mahjong.forwardedInputBridge.virtualButtonCalls;
                mahjong.virtualInput(Binding.valueOf("KEY_"+(char)('A'+tile)),down);
                boolean enabled=variant==0||variant==7;
                check(mahjong.forwardedInputBridge.virtualButtonCalls==calls+(enabled?1:0),"exact tile route "+variant);
                if(enabled)check(mahjong.forwardedInputBridge.lastButton==16+tile&&mahjong.forwardedInputBridge.buttonHeld==down,"actual tile make/break");
                noNative(mahjong,"tile overlay "+variant);
            }
            if(variant==0){
                mahjong.drawerLayout.openDrawer();int calls=mahjong.forwardedInputBridge.virtualButtonCalls;
                mahjong.virtualInput(Binding.KEY_A,true);
                check(mahjong.forwardedInputBridge.virtualButtonCalls==calls,"drawer retains ownership before tile forwarding");
            }
        }
        System.out.println("PASS: " + checks + " assertions; actual Activity dispatch methods, TPUI-only keyboard/controller isolation, system/editor paths and legacy behavior.");
    }
}

class KeyEvent {
    final int key, action, repeat;
    final boolean system, controller;
    KeyEvent(int key, int action, int repeat, boolean system, boolean controller) {
        this.key=key; this.action=action; this.repeat=repeat; this.system=system; this.controller=controller;
    }
    int getKeyCode(){return key;} int getAction(){return action;} int getRepeatCount(){return repeat;}
    boolean isSystem(){return system;} Boolean getDevice(){return controller;}
}
class MotionEvent {
    static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5,ACTION_POINTER_UP=6;
    final int source; final boolean controller; int action=ACTION_MOVE,pointerId;
    MotionEvent(int source, boolean controller){this.source=source;this.controller=controller;}
    static MotionEvent touch(int action,int pointerId){MotionEvent event=new MotionEvent(0x1002,false);event.action=action;event.pointerId=pointerId;return event;}
    int getSource(){return source;} Boolean getDevice(){return controller;}
    int getActionMasked(){return action;} int getActionIndex(){return 0;} int getPointerId(int index){return pointerId;}
    float getX(int index){return 100;} float getY(int index){return 100;}
}
class View {
    final boolean editor;
    View(boolean editor){this.editor=editor;} boolean onCheckIsTextEditor(){return editor;}
}
class StubActivity {
    int baseKeyCalls,baseMotionCalls,baseTouchCalls; boolean windowFocus=true; View focusedView; Runnable touchCallback;
    boolean dispatchKeyEvent(KeyEvent event){baseKeyCalls++;return false;}
    boolean dispatchGenericMotionEvent(MotionEvent event){baseMotionCalls++;return false;}
    boolean dispatchTouchEvent(MotionEvent event){baseTouchCalls++;if(touchCallback!=null)touchCallback.run();return true;}
    View getCurrentFocus(){return focusedView;} boolean hasWindowFocus(){return windowFocus;}
}
class ForwardedBridge {
    int keyCalls,motionCalls,lastButton=-1,focusLost,focusGained,pointerCalls,virtualButtonCalls,virtualAxisCalls,pauseCalls,resumeCalls; boolean buttonHeld,pointerHeld;
    void onKeyEvent(KeyEvent event){keyCalls++;if(event.getRepeatCount()==0 && event.getAction()<=1){lastButton=ForwardedInputMapping.mapKeyCode(event.getKeyCode());if(lastButton!=-1)buttonHeld=event.getAction()==0;}}
    void onGenericMotionEvent(MotionEvent event){motionCalls++;}
    void onWindowFocusChanged(boolean focused){if(focused)focusGained++;else{focusLost++;buttonHeld=false;pointerHeld=false;}}
    void onTouchEvent(MotionEvent event,SparseBooleanArray excluded){if(!excluded.get(event.pointerId)){pointerCalls++;pointerHeld=event.action!=MotionEvent.ACTION_UP&&event.action!=MotionEvent.ACTION_CANCEL;}}
    void onVirtualButton(int button,boolean pressed){virtualButtonCalls++;lastButton=button;buttonHeld=pressed;}
    void onVirtualAxis(int axis,float value){virtualAxisCalls++;}
    void onResume(){resumeCalls++;onWindowFocusChanged(true);}void onPause(){pauseCalls++;onWindowFocusChanged(false);}
}
class InputControlsView {
    int keyCalls,profileKeyDeliveries,profileMotionDeliveries; boolean mapsKey,mapsMotion,editMode,overControl;
    boolean isEditMode(){return editMode;}
    boolean onKeyEvent(KeyEvent event){keyCalls++;if(mapsKey)profileKeyDeliveries++;return mapsKey;}
    boolean onGenericMotionEvent(MotionEvent event){if(mapsMotion)profileMotionDeliveries++;return mapsMotion;}
    boolean isPointOverControl(float x,float y){return overControl;}
}
class DrawerLayout {
    static final int STATE_IDLE=0,STATE_SETTLING=2; boolean open;SimpleDrawerListener listener;
    static class SimpleDrawerListener {public void onDrawerStateChanged(int state){}public void onDrawerOpened(View view){}public void onDrawerClosed(View view){}}
    void addDrawerListener(SimpleDrawerListener listener){this.listener=listener;}
    boolean isDrawerOpen(int gravity){return open;}boolean isDrawerVisible(int gravity){return open;}
    void openDrawer(){listener.onDrawerStateChanged(STATE_SETTLING);open=true;listener.onDrawerOpened(new View(false));listener.onDrawerStateChanged(STATE_IDLE);}
    void closeDrawer(){listener.onDrawerStateChanged(STATE_SETTLING);open=false;listener.onDrawerClosed(new View(false));listener.onDrawerStateChanged(STATE_IDLE);}
}
class GravityCompat { static final int START=0x800003; }
class ExternalController { static boolean isGameController(Boolean controller){return controller!=null&&controller;} }
class WinHandler {
    int keyCalls,motionCalls;
    boolean onKeyEvent(KeyEvent event){keyCalls++;return false;}
    boolean onGenericMotionEvent(MotionEvent event){motionCalls++;return false;}
}
class XServer { final Keyboard keyboard=new Keyboard(); }
class Keyboard { int calls; boolean onKeyEvent(KeyEvent event){calls++;return true;} }
class TouchpadView { int calls; boolean onExternalMouseEvent(MotionEvent event){calls++;return false;} }
class SparseBooleanArray {final java.util.HashSet<Integer> values=new java.util.HashSet<>();void put(int key,boolean value){if(value)values.add(key);else values.remove(key);}boolean get(int key){return values.contains(key);}void delete(int key){values.remove(key);}void clear(){values.clear();}}
class PreparedLaunch {int controlsProfileId;boolean productionBridge;String compatibilityPreset="",executable="";}
enum Binding { KEY_A,KEY_B,KEY_C,KEY_D,KEY_E,KEY_F,KEY_G,KEY_H,KEY_I,KEY_J,KEY_K,KEY_L,KEY_M,KEY_N,KEY_UP,GAMEPAD_DPAD_UP,KEY_DOWN,GAMEPAD_DPAD_DOWN,KEY_LEFT,GAMEPAD_DPAD_LEFT,KEY_RIGHT,GAMEPAD_DPAD_RIGHT,KEY_ENTER,KEY_1,GAMEPAD_BUTTON_START,KEY_F2,KEY_F1,KEY_5,GAMEPAD_BUTTON_SELECT,GAMEPAD_BUTTON_A,GAMEPAD_BUTTON_B,GAMEPAD_BUTTON_X,GAMEPAD_BUTTON_Y,GAMEPAD_BUTTON_L1,GAMEPAD_BUTTON_R1,GAMEPAD_BUTTON_L2,GAMEPAD_BUTTON_R2,GAMEPAD_LEFT_THUMB_LEFT,GAMEPAD_LEFT_THUMB_RIGHT,GAMEPAD_LEFT_THUMB_UP,GAMEPAD_LEFT_THUMB_DOWN,GAMEPAD_RIGHT_THUMB_LEFT,GAMEPAD_RIGHT_THUMB_RIGHT,GAMEPAD_RIGHT_THUMB_UP,GAMEPAD_RIGHT_THUMB_DOWN }

"""Compile current helper/launcher and extracted lifecycle methods with Android fakes.

Requires javac/java on PATH. No APK, device, external snapshots or repository writes.
"""
from pathlib import Path
import argparse, hashlib, json, shutil, subprocess, tempfile

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1])
args=parser.parse_args()
P=args.root.resolve()
B=P/'teknoparrot-bridge/src/main/java/com/winlator/teknoparrot'
A=P/'app/src/main/java/com/winlator/XServerDisplayActivity.java'
temporary=tempfile.TemporaryDirectory(prefix='tp-cleanup-contract-')
BUILD=Path(temporary.name)
SRC=BUILD/'src'

def method(source, signature):
    start=source.index(signature)
    opening=source.index('{',start)
    depth=1; cursor=opening+1
    while depth:
        if source[cursor]=='{': depth+=1
        elif source[cursor]=='}': depth-=1
        cursor+=1
    return source[start:cursor]

def write(path,text):
    p=SRC/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');return p

base=Path('com/winlator/teknoparrot')
for name in ['PreparedSessionCleanup.java','PreparedSessionActivityLauncher.java']:
    write(base/name,(B/name).read_text(encoding='utf-8'))
activity=A.read_text(encoding='utf-8')
service=(B/'TeknoParrotBridgeService.java').read_text(encoding='utf-8')
destroy=method(activity,'protected void onDestroy()')
restore=method(activity,'private boolean restorePreparedBg4DataProjection()')
mahjong=method(activity,'private boolean shouldForwardTaisenMahjongInputs()')
timer_methods='\n'.join(method(service,x) for x in [
    'private static void requirePreparedCleanupComplete()',
    'public static void completePreparedActivityCleanup(',
    'private static void requestProductionProcessExitLocked()',
    'private static void cancelProductionProcessExitLocked()',
    'private static void scheduleProductionProcessExitLocked(long delayMs)'])
create=method(activity,'public void onCreate(Bundle savedInstanceState)')
startup=create[:create.index('        acquirePreparedMulticastLock();')]+'    }'
assert create.index('PreparedSessionCleanup.claim(')<create.index('ForwardedInputSessionRegistry.attach(')<create.index('teknoParrotSessionActivity = new WeakReference')
assert 'hasPreparedWindowsLaunch = false;' in create
assert 'productionBridge && preparedCleanupLease == null' in create
assert create.index('productionBridge && preparedCleanupLease == null')<create.index('ForwardedInputSessionRegistry.attach(')
assert destroy.index('if (preparedRejectedBeforeResources)')<destroy.index('prepareManagedSessionGraphicsTeardown()')
assert service.count('requirePreparedCleanupComplete();')==3
assert destroy.index('super.onDestroy();')<destroy.index('completePreparedActivityCleanup(')
write(base/'TeknoParrotBridgeService.java','''package com.winlator.teknoparrot;
import java.util.*;
final class TeknoParrotBridgeService {
 static final Object lock = new Object();
 static final String TAG="test";
 static final long PROCESS_EXIT_FALLBACK_DELAY_MS=10000, PROCESS_EXIT_AFTER_UNBIND_DELAY_MS=1500;
 static Object session;
 static boolean bridgeClientBound,productionProcessExitPending;
 static Runnable scheduledProcessExit;
 static final FakeHandler processExitHandler=new FakeHandler();
 static void releasePreparedSession(Object context,String id) { Events.add("release"); }
 static void request() { requestProductionProcessExitLocked(); }
 static void require() { requirePreparedCleanupComplete(); }
 static void reset() { cancelProductionProcessExitLocked();session=null;bridgeClientBound=false;processExitHandler.tasks.clear();android.os.Process.kills=0; }
'''+timer_methods+'''\n}
final class FakeHandler {
 final List<Runnable> tasks=new ArrayList<>();
 void removeCallbacks(Runnable action) { tasks.remove(action); }
 void postDelayed(Runnable action,long delay) { tasks.add(action); }
 void runAll() { List<Runnable> todo=new ArrayList<>(tasks);tasks.clear();for(Runnable action:todo) action.run(); }
}
''')
write(base/'XServerDisplayActivity.java','''package com.winlator.teknoparrot;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.*;
import android.os.Bundle;
import android.content.Intent;
class Parent {
 static boolean superThrows;
 public void onCreate(Bundle bundle){Events.add("super-create");}
 protected void onDestroy() { Events.add("super");if(superThrows)throw new IllegalStateException("super"); }
}
final class XServerDisplayActivity extends Parent {
 static final String TAG="test";
 static final Object TEKNOPARROT_SESSION_LOCK=new Object();
 static WeakReference<XServerDisplayActivity> teknoParrotSessionActivity=new WeakReference<>(null);
 boolean hasPreparedWindowsLaunch=true, stopped=true, recreating=false;
 boolean forwardedInputActivityDiagnostic,preparedRejectedBeforeResources;
 PreparedWindowsLaunchRegistry.Launch preparedWindowsLaunch;
 Intent intent=new Intent();Intent getIntent(){return intent;}
 void finish(){Events.add("finish");}
 String forwardedSessionId="0123456789abcdef0123456789abcdef";
 PreparedSessionCleanup.Lease preparedCleanupLease;
 Resource container=new Resource("drives"),forwardedInputBridge=new Resource("input"),winHandler=new Resource("handler"),environment=new Resource("environment");
 Projection preparedBg4DataProjection=new Projection();
 void prepareManagedSessionGraphicsTeardown(){Events.add("graphics");}
 void clearPreparedApmPayload(){Events.add("apm");}
 boolean awaitPreparedBg4GuestsStopped(){Events.add("guests");return stopped;}
 void restorePreparedWineGStreamerOutputFormat(){Events.add("gstreamer");}
 void releasePreparedMulticastLock(){Events.add("multicast");}
 boolean isChangingConfigurations(){return recreating;}
'''+startup+'\n'+destroy+'\n'+restore+'\n'+mahjong+'''\n}
final class Events {
 static List<String> events=new ArrayList<>();static String failure="";
 static void add(String event){events.add(event);if(failure.equals(event))throw new IllegalStateException(event);}
 static void reset(){events.clear();failure="";Parent.superThrows=false;}
}
final class Resource {
 final String name;Resource(String name){this.name=name;}
 void clearTransientDrives(){Events.add(name);}
 void close(){Events.add(name);}
 void stop(){Events.add(name);}
 void stopEnvironmentComponents(){Events.add(name);}
 void setMapTriggersToExtensionButtons(boolean value){Events.add("map-triggers");}
 boolean mahjongKeys;int mahjongMapCalls;
 void setMapMahjongKeys(boolean value){mahjongKeys=value;mahjongMapCalls++;}
}
final class AppUtils {static void setActivityTheme(Object activity){Events.add("theme");}}
final class Projection {
 boolean fail;void restore() throws IOException { Events.add("restore");if(fail)throw new IOException("unknown E"); }
}
final class ProcessHelper {
 static void killGuestProcesses(){Events.add("kill-guests");}
 static void setOutputSuppressed(boolean b){Events.add("output");}
}
final class ForegroundService { static void stopSession(Object context){Events.add("foreground");} }
final class Log {
 static void e(String tag,String message){Events.add("error");}
 static void e(String tag,String message,Throwable t){Events.add("error");}
 static void i(String tag,String message){Events.add("log");}
}
''')
write(Path('android/os/Process.java'),'''package android.os;
public final class Process { public static int kills;public static int myPid(){return 1;}public static void killProcess(int pid){kills++;} }
''')
write(Path('android/os/Bundle.java'),'package android.os; public final class Bundle {}\n')
write(Path('android/content/Context.java'),'''package android.content;
public abstract class Context {public String getPackageName(){return "com.teknoparrot.winlator";}public abstract void startActivity(Intent intent);}
''')
write(Path('android/content/Intent.java'),'''package android.content;
import java.util.*;
public final class Intent {
 public static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_NO_ANIMATION=2;
 private final Map<String,Object> values=new HashMap<>();
 public void setClassName(String p,String c){}public void addFlags(int f){}
 public void putExtra(String key,String v){values.put(key,v);}public void putExtra(String key,int v){values.put(key,v);}
 public void putExtra(String key,long v){values.put(key,v);}public void putExtra(String key,boolean v){values.put(key,v);}
 public long getLongExtra(String key,long d){Object v=values.get(key);return v instanceof Long?(Long)v:d;}
 public boolean getBooleanExtra(String key,boolean d){Object v=values.get(key);return v instanceof Boolean?(Boolean)v:d;}
 public String getStringExtra(String key){Object v=values.get(key);return v instanceof String?(String)v:null;}
}
''')
write(base/'Contracts.java','''package com.winlator.teknoparrot;
final class SessionContract { static final int SESSION_FLAG_PRODUCTION=1;static final class PreparedRequest {int flags;PreparedRequest(int f){flags=f;} } }
final class ActivityLaunchContract {
 static final String FORWARDED_INPUT_DIAGNOSTIC="input";
 static final class Request {String sessionId="0123456789abcdef0123456789abcdef",launchKind="game";int containerId=1;void validatePrepared(SessionContract.PreparedRequest p){}String status(){return "ok";} }
}
final class ForwardedInputSessionRegistry {
 static final String EXTRA_SESSION_ID="session";
 static int attachCount;static boolean available=true;
 static Resource attach(Object activity,String id){attachCount++;return available?new Resource("attached-input"):null;}
}
final class PreparedWindowsLaunchRegistry {
 static boolean available=true;static boolean production=true;
 static final class Launch {boolean productionBridge=production;int controlsProfileId=9033;String compatibilityPreset,executable;}
 static Launch find(String id){return available?new Launch():null;}
 static void unregister(String id){}static void register(String id,ActivityLaunchContract.Request r,SessionContract.PreparedRequest p,String page){}
}
''')
write(base/'CleanupContractTest.java','''package com.winlator.teknoparrot;
import android.content.*;
public final class CleanupContractTest {
 static final String ID="0123456789abcdef0123456789abcdef", OTHER="1123456789abcdef0123456789abcdef";
 static int checks,cases;
 static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 static void fail(Runnable r,String message){try{r.run();throw new AssertionError(message);}catch(IllegalStateException expected){checks++;}}
 static void reset(){check(!PreparedSessionCleanup.isPending(),"previous fixture leaked");Events.reset();TeknoParrotBridgeService.reset();PreparedWindowsLaunchRegistry.available=true;PreparedWindowsLaunchRegistry.production=true;ForwardedInputSessionRegistry.available=true;ForwardedInputSessionRegistry.attachCount=0;cases++;}
 static XServerDisplayActivity claimed(){long g=PreparedSessionCleanup.reserve(ID);XServerDisplayActivity a=new XServerDisplayActivity();a.preparedCleanupLease=PreparedSessionCleanup.claim(ID,g);XServerDisplayActivity.teknoParrotSessionActivity=new java.lang.ref.WeakReference<>(a);return a;}
 static void discard(XServerDisplayActivity a){PreparedSessionCleanup.complete(a.preparedCleanupLease,false);}
 static void noKill(){TeknoParrotBridgeService.processExitHandler.runAll();check(android.os.Process.kills==0,"unexpected process recycle");}
 static XServerDisplayActivity starting(long generation){XServerDisplayActivity a=new XServerDisplayActivity();a.container=null;a.forwardedInputBridge=null;a.environment=null;a.preparedBg4DataProjection=null;a.intent.putExtra(PreparedSessionActivityLauncher.EXTRA_PREPARED_WINDOWS_LAUNCH,true);a.intent.putExtra(ForwardedInputSessionRegistry.EXTRA_SESSION_ID,ID);if(generation!=0)a.intent.putExtra(PreparedSessionActivityLauncher.EXTRA_CLEANUP_GENERATION,generation);return a;}
 static void onlyFramework(String reason){check(Events.events.equals(java.util.Arrays.asList("theme","super-create","error","finish","super"))||Events.events.equals(java.util.Arrays.asList("theme","super-create","finish","super")),reason+": "+Events.events);check(ForwardedInputSessionRegistry.attachCount==0,"rejected startup must not attach input");}
 public static void main(String[]args){
  reset();TeknoParrotBridgeService.request();TeknoParrotBridgeService.processExitHandler.runAll();check(android.os.Process.kills==1,"normal no-Activity recycle preserved");
  reset();long reserve=PreparedSessionCleanup.reserve(ID);TeknoParrotBridgeService.request();noKill();check(TeknoParrotBridgeService.processExitHandler.tasks.isEmpty(),"reserved launch must not poll or time out");PreparedSessionCleanup.cancelUnclaimed(ID,reserve);check(!PreparedSessionCleanup.isPending(),"unclaimed failed start can cancel");
  reset();XServerDisplayActivity a=claimed();TeknoParrotBridgeService.request();XServerDisplayActivity.teknoParrotSessionActivity.clear();noKill();a.onDestroy();check(!PreparedSessionCleanup.isPending(),"successful exact restore acknowledges");check(Events.events.indexOf("restore")<Events.events.indexOf("super"),"restore before Android destroy");check(TeknoParrotBridgeService.processExitHandler.tasks.size()==1,"ack reschedules pending exit");TeknoParrotBridgeService.processExitHandler.runAll();check(android.os.Process.kills==1,"exit after successful cleanup");
  reset();a=claimed();a.preparedBg4DataProjection.fail=true;TeknoParrotBridgeService.request();a.onDestroy();check(PreparedSessionCleanup.isPending(),"failed restore remains pending");check(a.preparedBg4DataProjection!=null,"failed projection retained");noKill();fail(TeknoParrotBridgeService::require,"new operation must be blocked after failed restore");discard(a);
  reset();a=claimed();a.stopped=false;TeknoParrotBridgeService.request();a.onDestroy();check(PreparedSessionCleanup.isPending(),"live guests never acknowledge");noKill();discard(a);
  reset();a=claimed();Events.failure="environment";TeknoParrotBridgeService.request();XServerDisplayActivity e=a;fail(e::onDestroy,"environment exception expected");check(Events.events.contains("super"),"Android super still runs on teardown failure");check(PreparedSessionCleanup.isPending(),"exception leaves cleanup pending");noKill();discard(a);
  reset();a=claimed();Parent.superThrows=true;TeknoParrotBridgeService.request();e=a;fail(e::onDestroy,"super exception expected");check(PreparedSessionCleanup.isPending(),"super failure must not acknowledge");noKill();discard(a);
  reset();a=claimed();Events.failure="foreground";TeknoParrotBridgeService.request();e=a;fail(e::onDestroy,"late cleanup exception expected");check(PreparedSessionCleanup.isPending(),"late cleanup failure retained");noKill();discard(a);
  reset();long g=PreparedSessionCleanup.reserve(ID);PreparedSessionCleanup.Lease old=PreparedSessionCleanup.claim(ID,g);check(PreparedSessionCleanup.complete(old,false),"first ack");long next=PreparedSessionCleanup.reserve(OTHER);PreparedSessionCleanup.Lease newer=PreparedSessionCleanup.claim(OTHER,next);check(!PreparedSessionCleanup.complete(old,false),"old ack rejected");PreparedSessionCleanup.cancelUnclaimed(ID,g);check(PreparedSessionCleanup.isPending(),"old cancellation cannot release new generation");final long staleGeneration=g;fail(()->PreparedSessionCleanup.claim(ID,staleGeneration),"old intent rejected");PreparedSessionCleanup.complete(newer,false);
  reset();g=PreparedSessionCleanup.reserve(ID);old=PreparedSessionCleanup.claim(ID,g);PreparedSessionCleanup.complete(old,true);check(PreparedSessionCleanup.isPending(),"configuration gap retains reservation");newer=PreparedSessionCleanup.claim(ID,g);check(!PreparedSessionCleanup.complete(old,false),"old instance cannot finish replacement");PreparedSessionCleanup.complete(newer,false);check(!PreparedSessionCleanup.isPending(),"replacement final cleanup releases");
  reset();g=PreparedSessionCleanup.reserve(ID);old=PreparedSessionCleanup.claim(ID,g);newer=PreparedSessionCleanup.claim(ID,g);PreparedSessionCleanup.complete(old,true);check(PreparedSessionCleanup.isPending(),"replacement-before-destroy tracked");PreparedSessionCleanup.complete(newer,false);check(!PreparedSessionCleanup.isPending(),"no orphan reservation after reordered recreation");
  reset();a=claimed();a.preparedBg4DataProjection=null;TeknoParrotBridgeService.bridgeClientBound=true;TeknoParrotBridgeService.request();a.onDestroy();noKill();check(!PreparedSessionCleanup.isPending(),"positive no-projection cleanup acknowledges");TeknoParrotBridgeService.bridgeClientBound=false;TeknoParrotBridgeService.request();TeknoParrotBridgeService.processExitHandler.runAll();check(android.os.Process.kills==1,"unbind condition still required");
  reset();TeknoParrotBridgeService.request();a=claimed();noKill();discard(a);
  reset();ActivityLaunchContract.Request request=new ActivityLaunchContract.Request();SessionContract.PreparedRequest prod=new SessionContract.PreparedRequest(1);final long[] started={0};Context context=new Context(){public void startActivity(Intent intent){check(PreparedSessionCleanup.isPending(),"reservation exists before startActivity");started[0]=intent.getLongExtra(PreparedSessionActivityLauncher.EXTRA_CLEANUP_GENERATION,0);check(started[0]>0,"generation crosses Intent");}};PreparedSessionActivityLauncher.launch(context,request,prod,"page");newer=PreparedSessionCleanup.claim(ID,started[0]);PreparedSessionCleanup.complete(newer,false);
  reset();Context rejecting=new Context(){public void startActivity(Intent intent){throw new IllegalStateException("start rejected");}};fail(()->PreparedSessionActivityLauncher.launch(rejecting,request,prod,"page"),"start failure surfaced");check(!PreparedSessionCleanup.isPending(),"unclaimed rejected launch cancels");
  reset();final PreparedSessionCleanup.Lease[] concurrent={null};Context partial=new Context(){public void startActivity(Intent intent){concurrent[0]=PreparedSessionCleanup.claim(ID,intent.getLongExtra(PreparedSessionActivityLauncher.EXTRA_CLEANUP_GENERATION,0));throw new IllegalStateException("late start error");}};fail(()->PreparedSessionActivityLauncher.launch(partial,request,prod,"page"),"late start failure surfaced");check(PreparedSessionCleanup.isPending(),"already claimed start never cancels");PreparedSessionCleanup.complete(concurrent[0],false);
  reset();SessionContract.PreparedRequest debug=new SessionContract.PreparedRequest(0);Context ordinary=new Context(){public void startActivity(Intent intent){check(!PreparedSessionCleanup.isPending(),"diagnostic launch unchanged");check(intent.getLongExtra(PreparedSessionActivityLauncher.EXTRA_CLEANUP_GENERATION,0)==0,"no diagnostic reservation");}};PreparedSessionActivityLauncher.launch(ordinary,request,debug,"page");request.launchKind=ActivityLaunchContract.FORWARDED_INPUT_DIAGNOSTIC;PreparedSessionActivityLauncher.launch(ordinary,request,prod,"page");
  reset();XServerDisplayActivity normal=new XServerDisplayActivity();normal.hasPreparedWindowsLaunch=false;normal.preparedBg4DataProjection=null;normal.stopped=false;normal.onDestroy();check(!PreparedSessionCleanup.isPending(),"ordinary teardown unchanged");check(!Events.events.contains("kill-guests"),"ordinary Activity does not kill guest");
  reset();g=PreparedSessionCleanup.reserve(ID);old=PreparedSessionCleanup.claim(ID,g);PreparedSessionCleanup.complete(old,false);next=PreparedSessionCleanup.reserve(ID);newer=PreparedSessionCleanup.claim(ID,next);XServerDisplayActivity current=new XServerDisplayActivity();XServerDisplayActivity.teknoParrotSessionActivity=new java.lang.ref.WeakReference<>(current);a=starting(g);a.onCreate(null);a.onDestroy();onlyFramework("stale Activity must leave all globals/resources untouched");check(XServerDisplayActivity.teknoParrotSessionActivity.get()==current,"stale startup must not replace/clear current Activity");check(PreparedSessionCleanup.isPending(),"stale Activity must not acknowledge current cleanup");PreparedSessionCleanup.complete(newer,false);
  reset();g=PreparedSessionCleanup.reserve(ID);old=PreparedSessionCleanup.claim(ID,g);current=new XServerDisplayActivity();XServerDisplayActivity.teknoParrotSessionActivity=new java.lang.ref.WeakReference<>(current);a=starting(0);a.onCreate(null);a.onDestroy();onlyFramework("missing generation must leave current resources untouched");check(XServerDisplayActivity.teknoParrotSessionActivity.get()==current,"missing generation leaves current Activity");check(PreparedSessionCleanup.isPending(),"missing generation cannot release another owner");PreparedSessionCleanup.complete(old,false);
  reset();g=PreparedSessionCleanup.reserve(ID);TeknoParrotBridgeService.request();PreparedWindowsLaunchRegistry.available=false;a=starting(g);a.onCreate(null);check(PreparedSessionCleanup.isPending(),"stop before create remains reserved until destroyed");a.onDestroy();onlyFramework("stopped before startup needs no global cleanup");check(!PreparedSessionCleanup.isPending(),"valid pre-create cancellation acknowledges its reservation");TeknoParrotBridgeService.processExitHandler.runAll();check(android.os.Process.kills==1,"pre-create terminal completion can recycle");
  reset();g=PreparedSessionCleanup.reserve(ID);ForwardedInputSessionRegistry.available=false;a=starting(g);a.onCreate(null);a.onDestroy();check(ForwardedInputSessionRegistry.attachCount==1,"input attachment was attempted once");check(Events.events.equals(java.util.Arrays.asList("theme","super-create","finish","super")),"null input attachment must not run global teardown");check(!PreparedSessionCleanup.isPending(),"null attachment cancels valid owned prestart lease");
  reset();g=PreparedSessionCleanup.reserve(ID);PreparedWindowsLaunchRegistry.available=false;a=starting(g);a.onCreate(null);Parent.superThrows=true;e=a;fail(e::onDestroy,"rejected startup super failure surfaced");check(PreparedSessionCleanup.isPending(),"failed framework destroy must not acknowledge precreate cancellation");discard(a);
  check(!PreparedSessionCleanup.isPending(),"final clean");System.out.println("PASS cases="+cases+" checks="+checks);
 }
}
''')

classes=BUILD/'classes';classes.mkdir(exist_ok=True)
files=sorted(SRC.rglob('*.java'))
compiled=subprocess.run([shutil.which('javac'),'-encoding','UTF-8','-d',str(classes),*[str(f) for f in files]],capture_output=True,text=True)
result={'scope':'Offline actual helper and launcher, exact extracted Activity onCreate initialization prefix/onDestroy/BG4 restore and Service timer/ack bodies; fake Android lifecycle/process scheduler and filesystem faults. No device/graphics/gameplay qualification.','compile_exit_code':compiled.returncode,'compile_stdout':compiled.stdout,'compile_stderr':compiled.stderr,'structural_wiring_checks':7,'files':[{ 'path':str(f.relative_to(SRC)),'sha256':hashlib.sha256(f.read_bytes()).hexdigest()} for f in files]}
if compiled.returncode==0:
    tested=subprocess.run([shutil.which('java'),'-cp',str(classes),'com.winlator.teknoparrot.CleanupContractTest'],capture_output=True,text=True)
    result.update(test_exit_code=tested.returncode,test_stdout=tested.stdout,test_stderr=tested.stderr)
print(json.dumps(result,indent=2))
raise SystemExit(result.get('test_exit_code',compiled.returncode))

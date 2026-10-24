"""Test actual bridge touch-dispatch/mapping and Activity viewport callback offline."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
BRIDGE=ROOT/'teknoparrot-bridge/src/main/java/com/winlator/teknoparrot'
ACTIVITY=ROOT/'app/src/main/java/com/winlator/XServerDisplayActivity.java'

def method(source,signature):
    start=source.index(signature);opening=source.index('{',start);depth=1;cursor=opening+1
    while depth:
        if source[cursor]=='{':depth+=1
        elif source[cursor]=='}':depth-=1
        cursor+=1
    return source[start:cursor]

STUBS=r'''
package com.winlator.teknoparrot;
import java.util.*;
class View {int width=3200,height=2136,x,y;int getWidth(){return width;}int getHeight(){return height;}void getLocationInWindow(int[]p){p[0]=x;p[1]=y;}}
class Window {View decor=new View();View getDecorView(){return decor;}}
class Activity {Window window=new Window();Window getWindow(){return window;}}
class Renderer {float[] bounds;float[] getPreparedGameViewportBounds(int w,int h){return bounds;}}
class Surface extends View {Renderer renderer=new Renderer();Renderer getRenderer(){return renderer;}}
class InputDevice {static final int SOURCE_TOUCHSCREEN=0x1002,SOURCE_STYLUS=0x4002,SOURCE_MOUSE=0x2002;}
class SparseBooleanArray {Set<Integer>ids=new HashSet<>();boolean get(int i,boolean fallback){return ids.contains(i)||fallback;}void put(int i,boolean b){if(b)ids.add(i);else ids.remove(i);}}
class MotionEvent {
 static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5,ACTION_POINTER_UP=6;
 int source=InputDevice.SOURCE_TOUCHSCREEN,action=ACTION_DOWN,index;float[] x,y,pressure;int[] ids;
 MotionEvent(float...coordinates){int n=coordinates.length/2;x=new float[n];y=new float[n];ids=new int[n];pressure=new float[n];for(int i=0;i<n;i++){x[i]=coordinates[i*2];y[i]=coordinates[i*2+1];ids[i]=i+3;pressure[i]=1;}}
 int getSource(){return source;}int getActionMasked(){return action;}int getActionIndex(){return index;}int getPointerCount(){return x.length;}int getPointerId(int i){return ids[i];}int getToolType(int i){return 2;}float getX(int i){return x[i];}float getY(int i){return y[i];}float getPressure(int i){return pressure[i];}int getButtonState(){return 4;}long getEventTime(){return 123;}int getDeviceId(){return 8;}InputDevice getDevice(){return null;}
}
class Client {List<long[]>rows=new ArrayList<>();void sendPointerAbsolute(long device,int player,int tool,int x,int y,int pressure,long id,long buttons,long timestamp){rows.add(new long[]{device,player,tool,x,y,pressure,id,buttons,timestamp});}}
'''
CHECKS=r'''
package com.winlator.teknoparrot;
public final class ForwardedPointerViewportChecks {
 static int checks;
 static void eq(long value,long expected,String name){checks++;if(value!=expected)throw new AssertionError(name+": "+value+" != "+expected);}
 static void near(float value,float expected,String name){checks++;if(Math.abs(value-expected)>.001f)throw new AssertionError(name);}
 static void yes(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
 static long[] last(BridgeHarness bridge){return bridge.client.rows.get(bridge.client.rows.size()-1);}
 public static void main(String[]args){
  BridgeHarness b=new BridgeHarness();float[] bounds={176,70,2848,1994};b.setPointerViewportProvider(()->bounds);
  MotionEvent e=new MotionEvent(176,70);b.onTouchEvent(e);eq(last(b)[3],0,"complete client left");eq(last(b)[4],0,"complete client top");
  e=new MotionEvent(3023,2063);b.onTouchEvent(e);eq(last(b)[3],65535,"complete client right");eq(last(b)[4],65535,"complete client bottom");
  e=new MotionEvent(1599.5f,1066.5f);b.onTouchEvent(e);eq(last(b)[3],32768,"complete client centre x");eq(last(b)[4],32768,"complete client centre y");
  e=new MotionEvent(0,0);b.onTouchEvent(e);eq(last(b)[3],0,"side bar clamps x");eq(last(b)[4],0,"letter bar clamps y");
  e=new MotionEvent(3199,2135);b.onTouchEvent(e);eq(last(b)[3],65535,"far bar clamps x");eq(last(b)[4],65535,"far bar clamps y");
  eq(last(b)[0],77,"stable device unchanged");eq(last(b)[1],0,"pointer player unchanged");eq(last(b)[2],2,"tool unchanged");eq(last(b)[5],65535,"pressure unchanged");eq(last(b)[6],3,"pointer ID unchanged");eq(last(b)[7],5,"touch pressed buttons unchanged");eq(last(b)[8],123000000,"timestamp unchanged");
  int before=b.client.rows.size();SparseBooleanArray excluded=new SparseBooleanArray();excluded.put(3,true);
  e=new MotionEvent(176,70,3023,2063);e.action=MotionEvent.ACTION_MOVE;b.onTouchEvent(e,excluded);eq(b.client.rows.size(),before+1,"excluded overlay pointer skipped");eq(last(b)[6],4,"unexcluded second pointer kept");eq(last(b)[3],65535,"second pointer right");
  e.action=MotionEvent.ACTION_POINTER_UP;e.index=1;b.onTouchEvent(e,excluded);eq(last(b)[6],4,"UP action pointer kept");eq(last(b)[7],0,"UP clears buttons");eq(last(b)[5],0,"UP clears pressure");
  e.action=MotionEvent.ACTION_CANCEL;b.onTouchEvent(e,excluded);eq(last(b)[7],0,"CANCEL clears buttons");eq(last(b)[5],0,"CANCEL clears pressure");
  before=b.client.rows.size();excluded.put(4,true);b.onTouchEvent(e,excluded);eq(b.client.rows.size(),before,"all excluded CANCEL remains excluded");
  e=new MotionEvent(176,70);e.source=InputDevice.SOURCE_MOUSE;b.onTouchEvent(e);eq(last(b)[7],4,"mouse buttons unchanged");
  e.source=InputDevice.SOURCE_STYLUS;b.onTouchEvent(e);eq(last(b)[7],5,"stylus pressed semantics unchanged");
  e.source=0; b.onTouchEvent(e);eq(last(b)[3],0,"zero-source supported");
  before=b.client.rows.size();e.source=0x1000010;b.onTouchEvent(e);eq(b.client.rows.size(),before,"non-pointer source ignored");
  b.setPointerViewportProvider(null);e=new MotionEvent(176,70);b.onTouchEvent(e);eq(last(b)[3],ForwardedInputMapping.toUnsignedQ16(176,3200),"ordinary x exact mapper unchanged");eq(last(b)[4],ForwardedInputMapping.toUnsignedQ16(70,2136),"ordinary y exact mapper unchanged");
  b.setPointerViewportProvider(()->null);b.onTouchEvent(e);eq(last(b)[3],ForwardedInputMapping.toUnsignedQ16(176,3200),"unresolved prepared bounds old behavior");
  for(float[] invalid:new float[][]{{0,0,0,480},{0,0,640,1},{Float.NaN,0,640,480},{0,Float.POSITIVE_INFINITY,640,480},{0,0,Float.POSITIVE_INFINITY,480},{0,0,640,Float.NaN},{0,0,640},{0,0,640,480,1}}){b.setPointerViewportProvider(()->invalid);b.onTouchEvent(e);eq(last(b)[3],ForwardedInputMapping.toUnsignedQ16(176,3200),"invalid viewport preserves old behavior");}
  b.setPointerViewportProvider(()->new float[]{.5f,1.5f,100.5f,200.5f});e=new MotionEvent(100,201);b.onTouchEvent(e);eq(last(b)[3],65535,"fractional right pixel mapping");eq(last(b)[4],65535,"fractional bottom pixel mapping");
  before=b.client.rows.size();b.closed=true;b.onTouchEvent(e);b.onTouchEvent(null);eq(b.client.rows.size(),before,"closed/null no delivery");
  b.closed=false;b.activity.getWindow().decor.width=1;b.onTouchEvent(e);eq(b.client.rows.size(),before,"unlaid decor suppresses pointer");
  ActivityHarness a=new ActivityHarness();yes(a.getPreparedPointerViewport()==null,"no surface callback null");
  a.xServerView=new Surface();yes(a.getPreparedPointerViewport()==null,"renderer policy unresolved/null preserves old behavior");
  a.xServerView.renderer.bounds=new float[]{176,0,2848,2136};a.xServerView.x=12;a.xServerView.y=35;a.window.decor.x=2;a.window.decor.y=5;
  float[] mapped=a.getPreparedPointerViewport();near(mapped[0],186,"surface relative to decor x");near(mapped[1],30,"surface relative to decor y");near(mapped[2],2848,"width unchanged");near(mapped[3],2136,"height unchanged");near(a.xServerView.renderer.bounds[0],176,"does not mutate renderer array");
  b=new BridgeHarness();b.setPointerViewportProvider(a::getPreparedPointerViewport);e=new MotionEvent(186,30);b.onTouchEvent(e);eq(last(b)[3],0,"Activity offset reaches bridge x");eq(last(b)[4],0,"Activity offset reaches bridge y");
  System.out.println("Forwarded pointer viewport actual-method checks PASS: "+checks);
 }
}
'''

def run(before=None,output=None):
    source=(Path(before) if before else BRIDGE/'ForwardedInputActivityBridge.java').read_text(encoding='utf-8')
    activity=ACTIVITY.read_text(encoding='utf-8')
    javac=shutil.which('javac');java=shutil.which('java')
    if not javac or not java:raise RuntimeError('Java JDK required')
    output=output or Path(tempfile.mkdtemp(prefix='tp-pointer-viewport-'));output.mkdir(parents=True,exist_ok=True)
    def write(name,text):
        p=output/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');return p
    methods='\n'.join(method(source,signature) for signature in ['public void onTouchEvent(MotionEvent event)', 'public void onTouchEvent(\n', 'private static boolean isExcluded(', 'private void sendPointer('])
    for signature in ['private static boolean isValidPointerViewport(', 'private static int normalizePointerCoordinate(']:
        if signature in source:methods+='\n'+method(source,signature)
    setter=method(source,'public void setPointerViewportProvider(') if 'public void setPointerViewportProvider(' in source else 'public void setPointerViewportProvider(PointerViewportProvider p){pointerViewportProvider=p;}'
    write('com/winlator/teknoparrot/Stubs.java',STUBS)
    write('com/winlator/teknoparrot/BridgeHarness.java','package com.winlator.teknoparrot;public class BridgeHarness {public interface PointerViewportProvider{float[] getPointerViewport();}PointerViewportProvider pointerViewportProvider;Activity activity=new Activity();Client client=new Client();boolean closed;private long stableDeviceId(int id,InputDevice d){return 77;}'+setter+'\n'+methods+'\n}')
    callback=method(activity,'private float[] getPreparedPointerViewport()').replace('private float[]','public float[]',1)
    write('com/winlator/teknoparrot/ActivityHarness.java','package com.winlator.teknoparrot;class ActivityHarness extends Activity{Surface xServerView;'+callback+'}')
    for name in ['ForwardedInputMapping.java','ForwardedInputProtocol.java']:write('com/winlator/teknoparrot/'+name,(BRIDGE/name).read_text(encoding='utf-8'))
    write('com/winlator/teknoparrot/ForwardedPointerViewportChecks.java',CHECKS)
    built=subprocess.run([javac,'-encoding','UTF-8','-d',str(output),*[str(p) for p in output.rglob('*.java')]],capture_output=True,text=True)
    result={'scope':'Extracted actual bridge touch/exclusion/send/mapping methods + actual Activity callback, JVM Android fakes only; no device','source_sha256':hashlib.sha256(source.encode()).hexdigest(),'compile_exit_code':built.returncode,'compile_stderr':built.stderr,'before_reproduction':bool(before)}
    if built.returncode==0:
        tested=subprocess.run([java,'-cp',str(output),'com.winlator.teknoparrot.ForwardedPointerViewportChecks'],capture_output=True,text=True)
        result.update(test_exit_code=tested.returncode,stdout=tested.stdout,stderr=tested.stderr)
    if before:
        if result.get('test_exit_code')==0 or 'complete client left' not in result.get('stderr',''):raise AssertionError(result)
        result['expected_failure_verified']=True
    else:
        assert 'forwardedInputBridge.setPointerViewportProvider(this::getPreparedPointerViewport);' in activity
        result['activity_wiring_hook']=True
    (output/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(result,indent=2))
    if not before and result.get('test_exit_code',built.returncode):raise SystemExit(1)
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--before',type=Path);parser.add_argument('--output',type=Path);args=parser.parse_args();run(args.before,args.output)

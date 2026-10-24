"""Offline Taisen Mahjong TPI1, actual bridge key delivery and layout regression.

Compiles production Java and extracted production methods with observable Android
seams. It does not launch Wine, build an APK or qualify actual game controls.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / 'teknoparrot-bridge/src/main/java/com/winlator/teknoparrot'
JAVA = ROOT / 'app/src/main/java/com/winlator'
BASELINE_COMMIT = '923eb84c3f455764ae1ece701ef368e16f88dcbe'
BASELINE_BLOBS = {
    'ForwardedInputMapping': 'ab0190a70bec093aa038a2f7b54ccfc56376ea50',
    'ForwardedInputProtocol': '53c1e2bc0f0b382e9397de61caf1aa591a13c4fb',
}

def method(text, signature):
    start = text.index(signature)
    opening = text.index('{', start)
    depth, end = 1, opening + 1
    while depth:
        if text[end] == '{': depth += 1
        elif text[end] == '}': depth -= 1
        end += 1
    return text[start:end]

CHECKS = r"""
package com.winlator.teknoparrot;
import java.util.*;
public final class TaisenMahjongChecks {
 static int checks;
 static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
 public static void main(String[] args){
  // Every pre-existing key mapping and old wire ID retains its exact behavior.
  for(int key=-1;key<=400;key++)for(boolean triggers:new boolean[]{false,true}){
   check(ForwardedInputMapping.mapKeyCode(key,triggers)==ForwardedInputMappingBefore.mapKeyCode(key,triggers),"old key "+key);
   if(key<29||key>42)check(ForwardedInputMapping.mapKeyCode(key,triggers,true)==ForwardedInputMappingBefore.mapKeyCode(key,triggers),"non-tile old key "+key);
  }
  for(int player=0;player<4;player++)for(int id=0;id<16;id++)for(boolean down:new boolean[]{false,true}){
   byte[] now=new byte[32],before=new byte[32];
   ForwardedInputProtocol.writeButtonFrame(now,0xffffffffL,0x123456789L,0x87654321L,player,id,down);
   ForwardedInputProtocolBefore.writeButtonFrame(before,0xffffffffL,0x123456789L,0x87654321L,player,id,down);
   check(Arrays.equals(now,before),"old wire exact "+player+"/"+id);
  }
  BridgeHarness b=new BridgeHarness();
  for(int tile=0;tile<14;tile++){
   String binding="KEY_"+(char)('A'+tile);
   check(ForwardedInputMapping.mapMahjongBinding(binding,true)==16+tile,"tile overlay mapper");
   check(ForwardedInputMapping.mapMahjongBinding(binding,false)==-1,"other profile tile disabled");
   b.onKeyEvent(new KeyEvent(29+tile,0,0));check(b.client.rows.isEmpty(),"ordinary key not forwarded");
  }
  for(String bad:new String[]{null,"KEY_O","KEY_Z","KEY_a","A","KEY_AA","KEY_","GAMEPAD_BUTTON_A"})
   check(ForwardedInputMapping.mapMahjongBinding(bad,true)==-1,"invalid tile binding");
  b.setMapMahjongKeys(true);
  for(int tile=0;tile<14;tile++)for(int action=0;action<2;action++){
   int before=b.client.rows.size();b.onKeyEvent(new KeyEvent(29+tile,action,0));
   check(b.client.rows.size()==before+1,"actual key delivers one frame");
   byte[] wire=b.client.rows.get(before);check(wire.length==32&&wire[28]==0&&wire[29]==(action==0?1:0)&&wire[30]==16+tile&&wire[31]==0,"actual key tile wire");
  }
  int before=b.client.rows.size();b.onKeyEvent(new KeyEvent(29,0,1));b.onKeyEvent(new KeyEvent(29,2,0));b.onKeyEvent(null);
  check(b.client.rows.size()==before,"repeat/multiple/null rejected");b.closed=true;b.onKeyEvent(new KeyEvent(29,0,0));check(b.client.rows.size()==before,"closed suppressed");
  for(int id:new int[]{-1,30,31,65535}){boolean rejected=false;try{ForwardedInputProtocol.writeButtonFrame(new byte[32],1,2,3,0,id,true);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"out-of-range wire rejected");}
  for(int id=16;id<30;id++){byte[] wire=new byte[32];check(ForwardedInputProtocol.writeButtonFrame(wire,1,2,3,0,id,true)==32&&wire[30]==id,"additive bounded ID");}
  GeometryChecks.run();System.out.println("Taisen actual bridge/mapping/wire checks PASS: "+checks);
 }
}
class KeyEvent {
 static final int ACTION_DOWN=0,ACTION_UP=1;final int key,action,repeat;
 KeyEvent(int key,int action,int repeat){this.key=key;this.action=action;this.repeat=repeat;}
 int getKeyCode(){return key;}int getAction(){return action;}int getRepeatCount(){return repeat;}int getDeviceId(){return 7;}InputDevice getDevice(){return null;}long getEventTime(){return 123;}
}
class InputDevice {}
class Client {
 final List<byte[]> rows=new ArrayList<>();
 void sendButton(long device,int player,int button,boolean down,long time){byte[] wire=new byte[32];ForwardedInputProtocol.writeButtonFrame(wire,1,time,device,player,button,down);rows.add(wire);}
}
"""

GEOMETRY = r"""
package com.winlator.teknoparrot;
import java.util.*;
class GeometryChecks {
 static void run(){
  double[][] rows=ELEMENTS;
  for(int[] screen:new int[][]{{3200,2136},{3200,1984},{1920,1080},{1280,720},{1080,1920},{2136,3200}}){
   ControlsView inputControlsView=new ControlsView(screen[0],screen[1]);List<Rect> boxes=new ArrayList<>();
   for(double[] values:rows){
    ElementObject elementJSONObject=new ElementObject(values);Element element=new Element(inputControlsView);
    LOAD
    Rect box=element.computeBoundingBox();
    TaisenMahjongChecks.check(box.l>=10&&box.t>=10&&box.r<=screen[0]-10&&box.b<=screen[1]-10,"layout margin "+Arrays.toString(screen));
    TaisenMahjongChecks.check(box.r>box.l&&box.b>box.t,"positive hit rect");
    for(Rect other:boxes)TaisenMahjongChecks.check(box.r<=other.l||box.l>=other.r||box.b<=other.t||box.t>=other.b,"distinct tile/action hit rectangles");
    boxes.add(box);
   }
  }
 }
}
class ElementObject {final double[] v;ElementObject(double[]v){this.v=v;}double getDouble(String name){return v[name.equals("x")?0:name.equals("y")?1:2];}}
class Rect {int l,t,r,b;void set(int l,int t,int r,int b){this.l=l;this.t=t;this.r=r;this.b=b;}}
class Flags {void unset(int flag){}}
class Element {
 enum Type { BUTTON,MIDI_KEY,D_PAD,TRACKPAD,STICK,RANGE_BUTTON,RADIAL_MENU }
 enum Shape { RECT,ROUND_RECT,SQUARE,CIRCLE }
 static final int FLAG_BOUNDING_BOX_NEEDS_UPDATE=16;
 final ControlsView inputControlsView;Type type=Type.BUTTON;Shape shape=Shape.ROUND_RECT;int x,y,orientation;float scale;Object[]bindings=new Object[4];Object paths;Rect boundingBox=new Rect();Flags propertyFlags=new Flags();
 Element(ControlsView view){inputControlsView=view;}void setX(int value){x=value;}void setY(int value){y=value;}void setScale(float value){scale=value;}
 BOUNDS
}
class ControlsView {
 int width,height;final int snappingSize;
 ControlsView(int w,int h){width=w;height=h;snappingSize=w/100;}
 int getWidth(){return width;}int getHeight(){return height;}int getSnappingSize(){return snappingSize;}
 WIDTH
 HEIGHT
}
class Mathf {
 ROUND1
 ROUND2
}
"""

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    output=args.output or Path(tempfile.mkdtemp(prefix='tp-taisen-input-'))
    output.mkdir(parents=True,exist_ok=True)
    package=output/'com/winlator/teknoparrot';package.mkdir(parents=True,exist_ok=True)
    javac,java=shutil.which('javac'),shutil.which('java')
    if not javac or not java:raise SystemExit('Java JDK required')
    inputs={}
    def read(path):
        inputs[str(path.relative_to(ROOT))]=hashlib.sha256(path.read_bytes()).hexdigest()
        return path.read_text(encoding='utf-8')
    for name in ['ForwardedInputMapping','ForwardedInputProtocol']:
        source=read(BRIDGE/(name+'.java'));(package/(name+'.java')).write_text(source,encoding='utf-8')
        relative=str((BRIDGE/(name+'.java')).relative_to(ROOT)).replace('\\','/')
        object_name=BASELINE_COMMIT+':'+relative
        blob=subprocess.run(['git','rev-parse',object_name],cwd=ROOT,capture_output=True,check=True,text=True).stdout.strip()
        assert blob==BASELINE_BLOBS[name], 'Pre-Mahjong baseline blob changed: '+name
        old=subprocess.run(['git','show',object_name],cwd=ROOT,capture_output=True,check=True,text=True).stdout
        (package/(name+'Before.java')).write_text(old.replace(name,name+'Before'),encoding='utf-8')
    source=read(BRIDGE/'ForwardedInputActivityBridge.java')
    methods='\n'.join(method(source,x) for x in ['public void onKeyEvent(KeyEvent event)','public void setMapMahjongKeys(boolean enabled)'])
    (package/'BridgeHarness.java').write_text('package com.winlator.teknoparrot; class BridgeHarness {boolean closed,mapTriggersToExtensionButtons,mapMahjongKeys;Client client=new Client();long stableDeviceId(int id,InputDevice d){return 77;}int playerForDevice(int id,InputDevice d){return 0;}'+methods+'}',encoding='utf-8')
    (package/'TaisenMahjongChecks.java').write_text(CHECKS,encoding='utf-8')
    element=read(JAVA/'inputcontrols/ControlElement.java');view=read(JAVA/'widget/InputControlsView.java');math=read(JAVA/'math/Mathf.java');profile=read(JAVA/'inputcontrols/ControlsProfile.java')
    layout=json.loads(read(ROOT/'app/src/main/assets/inputcontrols/profiles/controls-9079.icp'))
    expected={**{'KEY_'+c:c for c in 'ABCDEFGHIJKLMN'},'GAMEPAD_BUTTON_A':'CHI','GAMEPAD_BUTTON_B':'PON','GAMEPAD_BUTTON_X':'KAN','GAMEPAD_BUTTON_Y':'REACH','GAMEPAD_BUTTON_L1':'RON','GAMEPAD_BUTTON_START':'START','GAMEPAD_BUTTON_SELECT':'COIN','KEY_F1':'TEST','KEY_F2':'SERVICE'}
    assert layout['id']==9079 and layout['disableMouseInput'] is True and len(layout['elements'])==23
    assert {row['bindings'][0]:row['text'] for row in layout['elements']}==expected
    assert all(row['type']=='BUTTON' and row['shape']=='ROUND_RECT' and row['bindings'][1:]==['NONE']*3 and not row['toggleSwitch'] for row in layout['elements'])
    rows='new double[][]{'+','.join('{'+','.join(str(e[k]) for k in ['x','y','scale'])+'}' for e in layout['elements'])+'}'
    load='\n'.join(line.strip() for line in profile.splitlines() if 'element.setX((int)' in line or 'element.setY((int)' in line or 'element.setScale((float)' in line)
    assert len(load.splitlines())==3
    geometry=GEOMETRY.replace('ELEMENTS',rows).replace('LOAD',load).replace('BOUNDS',method(element,'private Rect computeBoundingBox()').replace('private Rect','public Rect',1)).replace('WIDTH',method(view,'public int getMaxWidth()')).replace('HEIGHT',method(view,'public int getMaxHeight()')).replace('ROUND1',method(math,'public static float roundTo(float x, float step)')).replace('ROUND2',method(math,'public static float roundTo(float x, float step, boolean roundHalfDown)'))
    (package/'GeometryChecks.java').write_text(geometry,encoding='utf-8')
    classes=output/'classes';classes.mkdir(exist_ok=True)
    built=subprocess.run([javac,'-encoding','UTF-8','-d',str(classes),*[str(p) for p in package.glob('*.java')]],capture_output=True,text=True)
    result={'scope':'Host actual-method/source regression only; no APK/device/game qualification','independent_prechange_commit':BASELINE_COMMIT,'independent_prechange_blobs':BASELINE_BLOBS,'inputs_sha256':inputs,'compile_exit_code':built.returncode,'compile_stderr':built.stderr}
    if built.returncode==0:
        tested=subprocess.run([java,'-cp',str(classes),'com.winlator.teknoparrot.TaisenMahjongChecks'],capture_output=True,text=True)
        result.update(test_exit_code=tested.returncode,stdout=tested.stdout,stderr=tested.stderr)
    (output/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(result,indent=2))
    if result.get('test_exit_code',built.returncode):raise SystemExit(1)

if __name__=='__main__':main()

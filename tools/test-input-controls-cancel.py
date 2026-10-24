"""Run actual extracted InputControlsView gesture methods with fake Android edges."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,tempfile

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1])
parser.add_argument('--source',type=Path,help='Optional source override for a retained baseline fixture')
args=parser.parse_args()
args.source=(args.source or args.root/'app/src/main/java/com/winlator/widget/InputControlsView.java').resolve()
source=args.source.read_text(encoding='utf-8')

def method(signature):
 start=source.index(signature);opening=source.index('{',start);end=opening+1;depth=1
 while depth:
  if source[end]=='{':depth+=1
  elif source[end]=='}':depth-=1
  end+=1
 return source[start:end]

touch=method('public boolean onTouchEvent(MotionEvent event)')
cancel=method('private void cancelTouchControls(MotionEvent event)')if 'private void cancelTouchControls(MotionEvent event)'in source else ''
JAVA=r'''
import java.util.*;
class PointF {float x,y;PointF(){this(0,0);}PointF(float x,float y){set(x,y);}void set(float x,float y){this.x=x;this.y=y;}}
class SparseArray<T>{TreeMap<Integer,T> m=new TreeMap<>();void put(int k,T v){m.put(k,v);}T get(int k){return m.get(k);}void remove(int k){m.remove(k);}int size(){return m.size();}int keyAt(int i){return new ArrayList<>(m.keySet()).get(i);}T valueAt(int i){return new ArrayList<>(m.values()).get(i);}void clear(){m.clear();}}
class MotionEvent {
 static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5,ACTION_POINTER_UP=6;
 int action,index;int[] ids;float[] xs,ys;
 MotionEvent(int a,int i,int... ids){action=a;index=i;this.ids=ids;xs=new float[ids.length];ys=new float[ids.length];for(int p=0;p<ids.length;p++){xs[p]=ids[p]==0||ids[p]==7?20:150;ys[p]=20;}}
 int getAction(){return action|(index<<8);}int getActionIndex(){return index;}int getActionMasked(){return action;}int getPointerId(int i){return ids[i];}int getPointerCount(){return ids.length;}
 float getX(){return getX(0);}float getY(){return getY(0);}float getX(int i){return xs[i];}float getY(int i){return ys[i];}
}
enum Binding {MOUSE_LEFT_BUTTON,NONE}
class Mathf {static float roundTo(float x,int n){return x;}}
class TouchpadView {static final int MAX_TAP_TRAVEL_DISTANCE=10;int cancelled,other;void setPointerButtonLeftEnabled(boolean v){}boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()==3)cancelled++;else other++;return true;}}
class ControlElement {
 final String name;final int side;int pointer=-1,down,up;boolean pressed,hidden;final List<String> history;
 ControlElement(String n,int s,List<String> h){name=n;side=s;history=h;}
 boolean handleTouchDown(int id,float x,float y){if(pointer==-1&&((side==0&&x<100)||(side==1&&x>=100))){pointer=id;pressed=true;down++;history.add(name+"+");return true;}return false;}
 boolean handleTouchMove(int id,float x,float y){return id==pointer;}
 boolean handleTouchUp(int id,float x,float y){if(id==pointer){pointer=-1;pressed=false;up++;history.add(name+"-");return true;}return false;}
 Binding getBindingAt(int i){return Binding.NONE;}int getLastBindingIndex(){return -1;}int getX(){return 0;}int getY(){return 0;}void setX(int x){}void setY(int y){}
}
class Profile {List<ControlElement> elements=new ArrayList<>();List<ControlElement> getElements(){return elements;}void save(){}}
class InputControlsView {
 boolean editMode,readyToDraw,moveCursor,moveElement;float startX,startY,offsetX,offsetY;int snappingSize=1;PointF cursor=new PointF();ControlElement selectedElement;Profile profile=new Profile();TouchpadView touchpadView=new TouchpadView();
 final SparseArray<PointF> activeTouchPointers=new SparseArray<>();
 ControlElement intersectElement(float x,float y){return null;}void selectElement(ControlElement x){}void invalidate(){}boolean isHiddenControl(ControlElement e){return e.hidden;}
 $TOUCH
 $CANCEL
}
public class CancelMethodTest {
 static int checks,cases;static void check(boolean x,String m){checks++;if(!x)throw new AssertionError(m);}
 static class Fixture {InputControlsView view=new InputControlsView();List<String> history=new ArrayList<>();ControlElement brake=new ControlElement("brake",0,history),start=new ControlElement("start",1,history);Fixture(){view.profile.elements.add(brake);view.profile.elements.add(start);}void two(){view.onTouchEvent(new MotionEvent(0,0,0));view.onTouchEvent(new MotionEvent(5,1,0,1));check(brake.pressed&&start.pressed,"both Brake and Start are held");}void released(){check(!brake.pressed&&!start.pressed,"CANCEL must release BOTH pointers");check(brake.up==1&&start.up==1,"each control releases exactly once");}}
 public static void main(String[] args){
  Fixture f=new Fixture();f.two();f.view.onTouchEvent(new MotionEvent(3,0,0,1));f.released();check(f.view.touchpadView.cancelled==1,"touchpad receives full cancellation");cases++;
  f=new Fixture();f.two();f.view.onTouchEvent(new MotionEvent(3,0,0));f.released();cases++;
  f=new Fixture();f.two();f.view.onTouchEvent(new MotionEvent(6,1,0,1));check(f.brake.pressed&&!f.start.pressed,"POINTER_UP only releases Start");f.view.onTouchEvent(new MotionEvent(1,0,0));f.released();check(f.history.equals(Arrays.asList("brake+","start+","start-","brake-")),"normal release order unchanged");cases++;
  f=new Fixture();f.two();f.view.onTouchEvent(new MotionEvent(6,1,0,1));f.view.onTouchEvent(new MotionEvent(3,0,0));f.released();cases++;
  f=new Fixture();f.two();f.view.onTouchEvent(new MotionEvent(3,0,0,1));f.view.onTouchEvent(new MotionEvent(3,0,0,1));f.released();check(f.view.touchpadView.cancelled==2,"repeat CANCEL forwards but does not repeat control releases");cases++;
  f=new Fixture();f.two();f.start.hidden=true;f.view.onTouchEvent(new MotionEvent(3,0,0));f.released();cases++;
  f=new Fixture();f.view.onTouchEvent(new MotionEvent(3,0,0));check(f.brake.up==0&&f.start.up==0,"idle cancel fabricates no releases");check(f.view.touchpadView.cancelled==1,"idle cancel safely reaches touchpad");cases++;
  f=new Fixture();f.view.onTouchEvent(new MotionEvent(0,0,7));f.view.onTouchEvent(new MotionEvent(5,1,7,11));check(f.brake.pressed&&f.start.pressed,"non-contiguous IDs both held");f.view.onTouchEvent(new MotionEvent(3,0,7));f.released();cases++;
  f=new Fixture();f.two();f.view.onTouchEvent(new MotionEvent(2,0,0,1));f.view.onTouchEvent(new MotionEvent(3,0,0));f.released();cases++;
  f=new Fixture();f.two();f.view.onTouchEvent(new MotionEvent(3,0,0,1));f.view.onTouchEvent(new MotionEvent(0,0,0));f.view.onTouchEvent(new MotionEvent(5,1,0,1));check(f.brake.down==2&&f.start.down==2,"new chord can own pointers after cancel");f.view.onTouchEvent(new MotionEvent(6,1,0,1));f.view.onTouchEvent(new MotionEvent(1,0,0));check(!f.brake.pressed&&!f.start.pressed&&f.brake.up==2&&f.start.up==2,"new chord releases normally");cases++;
  System.out.println("Actual InputControlsView touch/cancel methods PASS: cases="+cases+" checks="+checks);
 }
}
'''.replace('$TOUCH',touch).replace('$CANCEL',cancel)
with tempfile.TemporaryDirectory(prefix='bg4-overlay-cancel-')as tmp:
 root=Path(tmp);path=root/'CancelMethodTest.java';path.write_text(JAVA,encoding='utf-8');classes=root/'classes';classes.mkdir()
 javac,java=shutil.which('javac'),shutil.which('java')
 if not javac or not java:raise RuntimeError('Host javac/java must be available on PATH')
 compiled=subprocess.run([javac,'--release','8','-encoding','UTF-8','-d',str(classes),str(path)],capture_output=True,text=True,timeout=30)
 result={'scope':'Actual complete extracted onTouchEvent plus actual cancel helper; fake MotionEvent/SparseArray/profile/control ownership and edit-mode edges. No device or production compile.','source':str(args.source),'source_sha256':hashlib.sha256(args.source.read_bytes()).hexdigest(),'compile_exit_code':compiled.returncode,'compile_stderr':compiled.stderr}
 if compiled.returncode==0:
  tested=subprocess.run([java,'-cp',str(classes),'CancelMethodTest'],capture_output=True,text=True,timeout=10)
  result.update(test_exit_code=tested.returncode,stdout=tested.stdout,stderr=tested.stderr)
 print(json.dumps(result,indent=2));raise SystemExit(result.get('test_exit_code',compiled.returncode))

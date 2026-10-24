"""Compile production prepared-centered viewport/pointer math against analytic checks.

This historical test entry also retains the En-Eins native guest fullscreen case.

Run from a checkout with: python tools/test-prepared-en-eins-native-viewport.py
Requires a JDK on PATH or JAVA_HOME. Uses only a temporary Java source tree;
there is no Android, device, game-data, or network dependency.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def method(text, signature):
    start = text.index(signature)
    brace = text.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        if text[end] == '{':
            depth += 1
        elif text[end] == '}':
            depth -= 1
        end += 1
    return text[start:end]


CHECKS = r'''
package com.winlator.renderer;
import com.winlator.math.XForm;

public final class EnEinsNativeViewportChecks {
 static int checks, policyCases, nativeCases, ordinaryCases;
 static void yes(boolean b,String label) {
  checks++; if(!b)throw new AssertionError(label);
 }
 static void near(float actual,float expected,float tolerance,String label) {
  yes(Float.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,
      label+": "+actual+" != "+expected);
 }
 static RendererHarness renderer(int w,int h,boolean fit,boolean force,boolean stretch,boolean cap,long content) {
  RendererHarness r=new RendererHarness();r.xServer.screenInfo.width=w;r.xServer.screenInfo.height=h;
  r.preparedWindowAspectFit=fit;r.forceWindowsFullscreen=force;r.fullscreen=stretch;
  r.preparedContentDimensions=content;r.setPreparedCenteredNativeSize(cap);
  yes(r.viewportNeedsUpdate,"native-size setter invalidates viewport");return r;
 }
 static ViewTransformation transform(RendererHarness r,int w,int h) {
  ViewTransformation t=new ViewTransformation();r.updateViewTransformation(t,w,h);return t;
 }
 static void transformMatches(ViewTransformation t,int pw,int ph,int dw,int dh,float scale,String label) {
  near(t.aspect,scale,0.000002f,label+" uniform scale");
  yes(t.viewWidth==(int)Math.ceil(dw*scale)&&t.viewHeight==(int)Math.ceil(dh*scale),label+" raster extent");
  yes(t.viewOffsetX==(int)((pw-dw*scale)/2)&&t.viewOffsetY==(int)((ph-dh*scale)/2),label+" centered offset");
  near(t.sceneScaleX,dw*scale/pw,.000002f,label+" normalized X scale");
  near(t.sceneScaleY,dh*scale/ph,.000002f,label+" normalized Y scale");
  near(t.sceneOffsetX,dw*(1-t.sceneScaleX)/2,.002f,label+" scene X offset");
  near(t.sceneOffsetY,dh*(1-t.sceneScaleY)/2,.002f,label+" scene Y offset");
 }
 static void pointerChecks(RendererHarness r,ViewTransformation t,int pw,int ph,int dw,int dh) {
  TouchpadHarness touch=new TouchpadHarness(r);touch.updateXform(pw,ph,dw,dh);
  float[][] samples={{0,0},{dw,dh},{dw*.25f,dh*.75f},{dw*.5f,dh*.5f}};
  for(float[] sample:samples){
   float[] actual=XForm.transformPoint(touch.xform,t.viewOffsetX+sample[0]*t.aspect,t.viewOffsetY+sample[1]*t.aspect);
   near(actual[0],sample[0],.01f,"actual touch inverse X");
   near(actual[1],sample[1],.01f,"actual touch inverse Y");
  }
  float[] bounds=r.getPreparedGameViewportBounds(pw,ph);
  if(pw<=1||ph<=1){yes(bounds==null,"unlaid surface suppresses forwarded viewport");return;}
  yes(bounds!=null,"native viewport available to forwarded input");
  near(bounds[0],t.viewOffsetX,0,"shared pointer origin X");
  near(bounds[1],t.viewOffsetY,0,"shared pointer origin Y");
  near(bounds[2],dw*t.aspect,.0001f,"shared pointer width");
  near(bounds[3],dh*t.aspect,.0001f,"shared pointer height");
  for(int axis=0;axis<2;axis++){
   float origin=bounds[axis],extent=bounds[axis+2];
   if(extent<=1){yes(PointerHarness.normalizePointerCoordinate(origin,origin,extent)==0,"degenerate Q16");continue;}
   yes(PointerHarness.normalizePointerCoordinate(origin,origin,extent)==0,"Q16 first visible pixel");
   yes(PointerHarness.normalizePointerCoordinate(origin+extent-1,origin,extent)==65535,"Q16 last visible pixel");
   yes(Math.abs(PointerHarness.normalizePointerCoordinate(origin+(extent-1)/2,origin,extent)-32768)<=1,"Q16 midpoint");
   yes(PointerHarness.normalizePointerCoordinate(origin-20,origin,extent)==0,"Q16 near bar clamps");
   yes(PointerHarness.normalizePointerCoordinate(origin+extent+20,origin,extent)==65535,"Q16 far bar clamps");
   yes(PointerHarness.normalizePointerCoordinate(Float.NaN,origin,extent)==0,"Q16 NaN ignored");
  }
 }
 static void policyChecks() {
  ActivityHarness h=new ActivityHarness();yes(!h.shouldLimitPreparedOpenParrotToNativeSize(),"absent launch unchanged");
  h.preparedWindowsLaunch=new PreparedLaunch();PreparedLaunch l=h.preparedWindowsLaunch;
  String[] routes={"E:\\TeknoParrotRuntime\\OpenParrotWin32\\OpenParrotLoader.exe",
   "E:\\TeknoParrotRuntime\\OpenParrotWin64\\OpenParrotLoader64.exe",
   "e:\\teknoparrotruntime\\openparrotwin32legacy\\openparrotloader.exe",
   null,"F:\\TeknoParrotRuntime\\OpenParrotWin32\\OpenParrotLoader.exe",
   "E:\\TeknoParrotRuntime\\TeknoNaomi\\TeknoNaomi.exe",
   "E:\\TeknoParrotRuntime\\OpenParrotWin32\\other.exe",
   "E:\\TeknoParrotRuntime\\OpenParrotWin32\\OpenParrotLoader.exe.backup",
   "E:/TeknoParrotRuntime/OpenParrotWin32/OpenParrotLoader.exe"};
  String[] presets={"en-eins-native-fullscreen","music-gungun-native-fullscreen","portrait-window-counter-clockwise",null};
  String[] modes={"centered","fullscreen","aspect-fit","windowed",null};
  for(boolean production:new boolean[]{false,true})for(int profile:new int[]{0,9015,9014,9020})
   for(int ri=0;ri<routes.length;ri++)for(String preset:presets)for(String mode:modes){
    l.productionBridge=production;l.controlsProfileId=profile;l.executable=routes[ri];l.compatibilityPreset=preset;l.displayMode=mode;
    boolean expected=production&&ri<3&&"centered".equals(mode);
    yes(h.shouldLimitPreparedOpenParrotToNativeSize()==expected,"exact production/route/centered guard; profiles and presets preserve centered pixels");policyCases++;
   }
 }
 static void nativeChecks() {
  int[][] desktops={{1024,768},{640,480},{1280,720},{720,1280},{640,448},{1024,1024},{3840,2160},{2160,3840}};
  int[][] panels={{3200,2136},{1920,1080},{1024,768},{640,480},{800,400},{481,719},{1080,1920},{1,1},{2000,2000}};
  for(int[] d:desktops)for(int[] p:panels){
   RendererHarness r=renderer(d[0],d[1],false,false,false,true,0);ViewTransformation t=transform(r,p[0],p[1]);
   float scale=Math.min(1f,Math.min((float)p[0]/d[0],(float)p[1]/d[1]));
   transformMatches(t,p[0],p[1],d[0],d[1],scale,"native");
   yes(t.aspect<=1,"centered native content never upscales");
   yes(d[0]*scale<=p[0]+.001f&&d[1]*scale<=p[1]+.001f,"small panel fits complete desktop");
   pointerChecks(r,t,p[0],p[1],d[0],d[1]);nativeCases++;
  }
  RendererHarness desktop=renderer(1280,720,false,false,false,true,0);
  ViewTransformation nativeDesktop=transform(desktop,3200,2136);
  yes(nativeDesktop.viewWidth==1280&&nativeDesktop.viewHeight==720&&nativeDesktop.viewOffsetX==960&&nativeDesktop.viewOffsetY==708,"centered1280x720 pixels remain native onPad7");
  near(640*nativeDesktop.aspect,640,0,"inner640px client remains640px");
  near(nativeDesktop.viewOffsetX+320*nativeDesktop.aspect,1280,0,"centered inner640px client origin");
  desktop.setPreparedCenteredNativeSize(false);ViewTransformation oldFit=transform(desktop,3200,2136);
  yes(oldFit.viewWidth==3200&&oldFit.viewHeight==1800&&oldFit.viewOffsetX==0&&oldFit.viewOffsetY==168,"ordinary fallback reproduction: source desktop fits at2.5x");
  RendererHarness r=renderer(1024,768,false,false,false,true,0);ViewTransformation t=transform(r,3200,2136);
  yes(t.viewWidth==1024&&t.viewHeight==768&&t.viewOffsetX==1088&&t.viewOffsetY==684,"Pad7 1024x768 centered native anchor");
  r.setPreparedCenteredNativeSize(false);t=transform(r,3200,2136);
  yes(t.viewWidth==2848&&t.viewHeight==2136&&t.viewOffsetX==176&&t.viewOffsetY==0,"flag off restores ordinary full-height fit");
  r.setPreparedCenteredNativeSize(true);t=transform(r,800,600);
  yes(t.viewWidth==800&&t.viewHeight==600&&t.viewOffsetX==0&&t.viewOffsetY==0,"smaller matching panel exact fit");
 }
 static void ordinaryChecks() {
  int[][] desktops={{1024,768},{640,480},{1280,720},{720,1280},{640,448},{1024,1024},{3840,2160},{2160,3840}};
  int[][] panels={{3200,2136},{1920,1080},{1024,768},{640,480},{800,400},{481,719},{1080,1920}};
  for(int[] d:desktops)for(int[] p:panels)for(boolean fit:new boolean[]{false,true})
   for(boolean force:new boolean[]{false,true})for(boolean stretch:new boolean[]{false,true})
   for(boolean content:new boolean[]{false,true})for(boolean cap:new boolean[]{false,true}){
    if(cap&&!force&&!stretch)continue;
    int cw=content?d[0]/2:d[0],ch=content?d[1]/2:d[1];
    long dimensions=content?((long)cw<<32)|ch:0;
    RendererHarness r=renderer(d[0],d[1],fit,force,stretch,cap,dimensions);ViewTransformation t=transform(r,p[0],p[1]);
    boolean contentFit=fit&&force&&!stretch&&content;
    float scale=contentFit?Math.min((float)p[0]/cw,(float)p[1]/ch):Math.min((float)p[0]/d[0],(float)p[1]/d[1]);
    transformMatches(t,p[0],p[1],d[0],d[1],scale,"unrelated/fullscreen");
    float[] v=r.getPreparedGameViewportBounds(p[0],p[1]);
    if(contentFit){
     yes(v!=null,"content-fit forwarded bounds retained");
     near(v[0],t.viewOffsetX+(d[0]-cw)*scale/2,.001f,"ordinary content origin X");
     near(v[1],t.viewOffsetY+(d[1]-ch)*scale/2,.001f,"ordinary content origin Y");
     near(v[2],cw*scale,.001f,"ordinary content width");near(v[3],ch*scale,.001f,"ordinary content height");
    }else yes(v==null,"ordinary/forced/stretch forwarded fallback retained");
    ordinaryCases++;
   }
 }
 public static void main(String[] args) {
  policyChecks();nativeChecks();ordinaryChecks();
  System.out.println("Prepared centered native viewport PASS: checks="+checks+" policy_cases="+policyCases+" native_cases="+nativeCases+" ordinary_cases="+ordinaryCases);
 }
}
'''


def run(root=ROOT):
    root = Path(root).resolve()
    java_root = root/'app/src/main/java/com/winlator'
    bridge_root = root/'teknoparrot-bridge/src/main/java/com/winlator/teknoparrot'
    javac = shutil.which('javac')
    if not javac and os.environ.get('JAVA_HOME'):
        candidate = Path(os.environ['JAVA_HOME'])/'bin'/('javac.exe' if os.name == 'nt' else 'javac')
        if candidate.is_file():
            javac = str(candidate)
    if not javac:
        raise SystemExit('JDK required: add javac to PATH or set JAVA_HOME.')
    java = Path(javac).with_name('java.exe' if Path(javac).suffix == '.exe' else 'java')
    renderer = (java_root/'renderer/GLRenderer.java').read_text(encoding='utf-8')
    activity = (java_root/'XServerDisplayActivity.java').read_text(encoding='utf-8')
    touch_source = (java_root/'widget/TouchpadView.java').read_text(encoding='utf-8')
    forwarded_source = (bridge_root/'ForwardedInputActivityBridge.java').read_text(encoding='utf-8')
    with tempfile.TemporaryDirectory(prefix='tp-en-eins-viewport-') as temporary:
        folder = Path(temporary)

        def write(name, text):
            path = folder/name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding='utf-8')

        for name in ('renderer/ViewTransformation.java', 'math/XForm.java'):
            write('com/winlator/'+name, (java_root/name).read_text(encoding='utf-8'))
        methods = '\n'.join(method(renderer, signature) for signature in (
            'public void updateViewTransformation(',
            'public float[] getPreparedGameViewportBounds(',
            'public void setPreparedCenteredNativeSize('))
        write('com/winlator/renderer/RendererHarness.java', '''package com.winlator.renderer;
class Screen {int width,height;}
class Server {Screen screenInfo=new Screen();RendererHarness renderer;RendererHarness getRenderer(){return renderer;}}
class RendererHarness {
 Server xServer=new Server();
 boolean preparedWindowAspectFit,forceWindowsFullscreen,fullscreen,viewportNeedsUpdate,preparedCenteredNativeSize;
 long preparedContentDimensions;boolean isFullscreen(){return fullscreen;}
''' + methods + '\n}')
        policies = '\n'.join(method(activity, signature).replace('private boolean', 'boolean', 1)
                             for signature in ('private boolean shouldAspectFitPreparedOpenParrotWindow(',
                                               'private boolean shouldLimitPreparedOpenParrotToNativeSize('))
        write('com/winlator/renderer/ActivityHarness.java', '''package com.winlator.renderer;
class PreparedLaunch {boolean productionBridge;int controlsProfileId;String executable,compatibilityPreset,displayMode;}
class ActivityHarness {PreparedLaunch preparedWindowsLaunch;
''' + policies + '\n}')
        touch = method(touch_source, 'private void updateXform(').replace('private void', 'void', 1)
        write('com/winlator/renderer/TouchpadHarness.java', '''package com.winlator.renderer;
import com.winlator.math.XForm;
class TouchpadHarness {Server xServer;float[] xform=XForm.getInstance();
 TouchpadHarness(RendererHarness r){xServer=r.xServer;xServer.renderer=r;}
''' + touch + '\n}')
        normalize = method(forwarded_source, 'private static int normalizePointerCoordinate(').replace('private static', 'static', 1)
        write('com/winlator/renderer/PointerHarness.java', '''package com.winlator.renderer;
import com.winlator.teknoparrot.ForwardedInputMapping;
class PointerHarness {
''' + normalize + '\n}')
        for name in ('ForwardedInputMapping.java', 'ForwardedInputProtocol.java'):
            write('com/winlator/teknoparrot/'+name, (bridge_root/name).read_text(encoding='utf-8'))
        write('com/winlator/renderer/EnEinsNativeViewportChecks.java', CHECKS)
        subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(folder),
                        *map(str, sorted(folder.rglob('*.java')))], check=True)
        subprocess.run([str(java), '-cp', str(folder), 'com.winlator.renderer.EnEinsNativeViewportChecks'], check=True)

    assert 'renderer.setPreparedCenteredNativeSize(shouldLimitPreparedOpenParrotToNativeSize());' in method(activity, 'private void setupUI(')
    assert 'updateViewTransformation(viewTransformation, surfaceWidth, surfaceHeight);' in method(renderer, 'protected void drawFrame(')
    assert 'updateXform(getWidth(), getHeight(),' in method(touch_source, 'public boolean onTouchEvent(')
    assert 'getPreparedGameViewportBounds(' in method(activity, 'private float[] getPreparedPointerViewport(')
    assert 'forwardedInputBridge.setPointerViewportProvider(this::getPreparedPointerViewport);' in activity
    assert 'normalizePointerCoordinate(event.getX(pointerIndex), left, width)' in forwarded_source
    assert 'normalizePointerCoordinate(event.getY(pointerIndex), top, height)' in forwarded_source
    print('7 production viewport/input integration hooks PASS; no device commands')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT,
                        help='Checkout root; defaults to the parent of tools/.')
    run(parser.parse_args().root)

"""Compile real viewport/pointer math and extracted renderer methods; no device."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT/'app/src/main/java/com/winlator'

def method(text, signature):
    start = text.index(signature)
    brace = text.index('{', start)
    depth = 1
    end = brace+1
    while depth:
        if text[end] == '{': depth += 1
        elif text[end] == '}': depth -= 1
        end += 1
    return text[start:end]

CHECKS = r'''
package com.winlator.renderer;
import com.winlator.math.XForm;
public final class PreparedViewportChecks {
 static int checks;
 static void close(float actual,float expected,float tolerance,String name) {
  checks++; if(Math.abs(actual-expected)>tolerance) throw new AssertionError(name+": "+actual+" != "+expected);
 }
 static void yes(boolean value,String name) { checks++;if(!value)throw new AssertionError(name); }
 static RenderableWindow window(int width,int height,int fittedWidth,int fittedHeight,boolean candidate,boolean offscreen,boolean rotated) {
  FullscreenTransformation fit = fittedWidth==0?null:new FullscreenTransformation();
  if(fit!=null){fit.width=(short)fittedWidth;fit.height=(short)fittedHeight;}
  return new RenderableWindow(new Drawable(width,height,offscreen),fit,rotated,candidate);
 }
 static RendererHarness renderer(int desktopWidth,int desktopHeight) {
  RendererHarness r=new RendererHarness();r.xServer.screenInfo.width=desktopWidth;r.xServer.screenInfo.height=desktopHeight;
  r.forceWindowsFullscreen=true;r.setPreparedWindowAspectFit(true);return r;
 }
 static ViewTransformation transform(RendererHarness r,int width,int height) {
  r.updatePreparedContentDimensions();ViewTransformation t=new ViewTransformation();r.updateViewTransformation(t,width,height);return t;
 }
 static void centeredContent(ViewTransformation t,int outerWidth,int outerHeight,int desktopWidth,int desktopHeight,int contentWidth,int contentHeight,float expectedWidth,float expectedHeight) {
  close(contentWidth*t.aspect,expectedWidth,1.1f,"complete client width");
  close(contentHeight*t.aspect,expectedHeight,1.1f,"complete client height");
  float left=t.viewOffsetX+(desktopWidth-contentWidth)*.5f*t.aspect;
  float top=t.viewOffsetY+(desktopHeight-contentHeight)*.5f*t.aspect;
  close(left,(outerWidth-expectedWidth)*.5f,1.1f,"centered left");
  close(top,(outerHeight-expectedHeight)*.5f,1.1f,"centered top");
  yes(left>=-1.1f&&top>=-1.1f,"no client crop");
 }
 public static void main(String[] args) {
  RendererHarness r=renderer(1280,720);
  r.renderableWindows.add(window(640,480,960,720,true,false,false));
  ViewTransformation t=transform(r,3200,2136);
  centeredContent(t,3200,2136,1280,720,960,720,2848,2136);
  float[] bounds=r.getPreparedGameViewportBounds(3200,2136);
  close(bounds[0],176,1,"forwarded pointer viewport left");
  close(bounds[1],0,1,"forwarded pointer viewport top");
  close(bounds[2],2848,1,"forwarded pointer viewport width");
  close(bounds[3],2136,1,"forwarded pointer viewport height");
  yes(t.viewWidth>3200,"only empty desktop extends beyond panel");
  TouchpadHarness touch=new TouchpadHarness(r);touch.updateXform(3200,2136,1280,720);
  float left=t.viewOffsetX+160*t.aspect;
  float[] p=XForm.transformPoint(touch.xform,left,0);
  close(p[0],160,.6f,"left pointer inverse");close(p[1],0,.6f,"top pointer inverse");
  p=XForm.transformPoint(touch.xform,left+2848,2136);
  close(p[0],1120,.6f,"right pointer inverse");close(p[1],720,.6f,"bottom pointer inverse");
  r.preparedWindowAspectFit=false;
  yes(r.getPreparedGameViewportBounds(3200,2136)==null,"ordinary forwarded pointer untouched");
  t=transform(r,3200,2136);
  close(t.viewWidth,3200,1,"ordinary viewport unchanged");close(t.viewHeight,1800,1,"ordinary desktop fit unchanged");
  r.preparedWindowAspectFit=true;r.forceWindowsFullscreen=false;
  t=transform(r,3200,2136);close(t.viewHeight,1800,1,"centered mode unchanged");
  r.forceWindowsFullscreen=true;r.fullscreen=true;
  yes(r.getPreparedGameViewportBounds(3200,2136)==null,"manual stretch forwarded pointer untouched");
  t=transform(r,3200,2136);close(t.viewHeight,1800,1,"manual stretch pointer math unchanged");
  r.fullscreen=false;
  RenderableWindow modal=window(250,100,0,0,false,false,false);
  modal.preparedViewportBlocker=true;r.renderableWindows.add(modal);
  t=transform(r,3200,2136);close(t.viewHeight,1800,1,"edge native modal keeps full desktop visible");
  yes(r.getPreparedGameViewportBounds(3200,2136)==null,"modal fallback does not remap game gun coordinates");
  modal.rootX=200;modal.rootY=100;
  t=transform(r,3200,2136);close(720*t.aspect,2136,1,"contained modal remains wholly visible");
  Window moved=new Window(modal.content,0,0);
  r.viewportNeedsUpdate=false;r.updateWindowPosition(moved);
  yes(r.getPreparedGameViewportBounds(3200,2136)==null,"moved edge modal refreshes viewport without resize");
  yes(r.viewportNeedsUpdate,"moved modal requests GL viewport update");
  moved.x=200;moved.y=100;r.updateWindowPosition(moved);
  yes(r.getPreparedGameViewportBounds(3200,2136)!=null,"moving modal back restores fitted viewport");
  modal.rootX=0;modal.rootY=0;modal.content.offscreen=true;
  t=transform(r,3200,2136);close(720*t.aspect,2136,1,"offscreen modal does not block fit");
  r.renderableWindows.remove(modal);
  r.renderableWindows.add(window(1280,720,0,0,false,false,false));
  t=transform(r,3200,2136);close(720*t.aspect,2136,1,"desktop backdrop excluded");
  r.renderableWindows.add(window(1280,720,0,0,true,true,false));
  t=transform(r,3200,2136);close(720*t.aspect,2136,1,"offscreen storage excluded");
  r.renderableWindows.add(window(1280,720,0,0,true,false,false));
  t=transform(r,3200,2136);
  centeredContent(t,3200,2136,1280,720,1280,720,3200,1800);
  yes(t.viewWidth==3200,"full-sized second client prevents crop");
  touch.updateXform(3200,2136,1280,720);
  p=XForm.transformPoint(touch.xform,0,168);
  close(p[0],0,.6f,"pointer refresh after client resize x");
  close(p[1],0,.6f,"pointer refresh after client resize y");
  r=renderer(640,480);r.renderableWindows.add(window(640,448,640,448,true,false,false));
  t=transform(r,3200,2136);centeredContent(t,3200,2136,640,480,640,448,3051.4286f,2136);
  r=renderer(640,448);
  t=transform(r,3200,2136);centeredContent(t,3200,2136,640,448,640,448,3051.4286f,2136);
  r=renderer(1280,720);r.renderableWindows.add(window(720,1280,0,0,true,false,true));
  t=transform(r,3200,2136);centeredContent(t,3200,2136,1280,720,405,720,1201.5f,2136);
  // A landscape native client presented counterclockwise is portrait on screen.
  r.renderableWindows.clear();r.renderableWindows.add(window(1280,720,0,0,true,false,true));
  t=transform(r,3200,2136);centeredContent(t,3200,2136,1280,720,405,720,1201.5f,2136);
  // Explicit 3:4 portrait presentation from either backing orientation.
  r.renderableWindows.clear();r.renderableWindows.add(window(480,640,0,0,true,false,true));
  t=transform(r,3200,2136);centeredContent(t,3200,2136,1280,720,540,720,1602,2136);
  r.renderableWindows.clear();r.renderableWindows.add(window(640,480,0,0,true,false,true));
  t=transform(r,3200,2136);centeredContent(t,3200,2136,1280,720,540,720,1602,2136);
  r=renderer(1280,720);r.renderableWindows.add(window(640,480,960,720,true,false,false));
  t=transform(r,1920,1080);centeredContent(t,1920,1080,1280,720,960,720,1440,1080);
  t=transform(r,1080,1920);centeredContent(t,1080,1920,1280,720,960,720,1080,810);
  t=transform(r,1280,720);centeredContent(t,1280,720,1280,720,960,720,960,720);
  r.renderableWindows.clear();t=transform(r,3200,2136);close(t.viewHeight,1800,1,"empty scene resets stale client");
  ViewTransformation fallback=new ViewTransformation();
  for(int[] invalid:new int[][]{{0,720},{960,0},{-1,720},{1281,720},{960,721}}){
   fallback.updateForContent(3200,2136,1280,720,invalid[0],invalid[1]);
   close(fallback.viewHeight,1800,1,"invalid content falls back to complete desktop");
  }
  ActivityHarness activity=new ActivityHarness();
  yes(!activity.shouldAspectFitPreparedOpenParrotWindow(true),"no prepared launch unchanged");
  activity.preparedWindowsLaunch=new PreparedLaunch();
  activity.preparedWindowsLaunch.productionBridge=true;
  for(String loader:new String[]{"OpenParrotWin32\\OpenParrotLoader.exe","OpenParrotWin64\\OpenParrotLoader64.exe","OpenParrotWin32Legacy\\OpenParrotLoader.exe"}){
   activity.preparedWindowsLaunch.executable="E:\\TeknoParrotRuntime\\"+loader;
   yes(activity.shouldAspectFitPreparedOpenParrotWindow(true),"production OpenParrot route fits");
   yes(!activity.shouldAspectFitPreparedOpenParrotWindow(false),"centered route unchanged");
  }
  for(String loader:new String[]{null,"E:\\TeknoParrotRuntime\\TeknoNaomi\\TeknoNaomi.exe","F:\\TeknoParrotRuntime\\OpenParrotWin32\\OpenParrotLoader.exe","E:\\TeknoParrotRuntime\\OpenParrotWin32\\other.exe"}){
   activity.preparedWindowsLaunch.executable=loader;
   yes(!activity.shouldAspectFitPreparedOpenParrotWindow(true),"other modules and routes unchanged");
  }
  activity.preparedWindowsLaunch.executable="E:\\TeknoParrotRuntime\\OpenParrotWin32\\OpenParrotLoader.exe";
  activity.preparedWindowsLaunch.productionBridge=false;
  yes(!activity.shouldAspectFitPreparedOpenParrotWindow(true),"diagnostic prepared route unchanged");
  System.out.println("Prepared viewport actual-math checks PASS: "+checks);
 }
}
'''

def run(before_root=None):
    javac = shutil.which('javac') or r'C:\Program Files\Java\jdk-17\bin\javac.exe'
    java = str(Path(javac).with_name('java.exe' if Path(javac).suffix=='.exe' else 'java'))
    with tempfile.TemporaryDirectory(prefix='tp-viewport-') as tmp:
        folder=Path(tmp)
        def write(name,text):
            p=folder/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');return p
        if before_root:
            before=Path(before_root)/'ViewTransformation.java'
            write('com/winlator/renderer/ViewTransformation.java',before.read_text())
            write('com/winlator/renderer/Before.java','package com.winlator.renderer; public class Before { public static void main(String[]a){ViewTransformation t=new ViewTransformation();t.update(3200,2136,1280,720);if(Math.abs(720*t.aspect-2136)>1)throw new AssertionError("Old fullscreen leaves1800px client height on2136px panel");}}')
            subprocess.run([javac,'-d',str(folder),*[str(p) for p in folder.rglob('*.java')]],check=True)
            result=subprocess.run([java,'-cp',str(folder),'com.winlator.renderer.Before'],capture_output=True,text=True)
            assert result.returncode!=0 and 'Old fullscreen leaves1800px' in result.stderr
            print('Pre-edit reproduction PASS: old actual ViewTransformation leaves 336 vertical pixels unused')
            return
        write('com/winlator/renderer/ViewTransformation.java',(JAVA/'renderer/ViewTransformation.java').read_text())
        write('com/winlator/math/XForm.java',(JAVA/'math/XForm.java').read_text())
        renderer=(JAVA/'renderer/GLRenderer.java').read_text()
        methods='\n'.join(method(renderer,s) for s in [
            'private void updatePreparedContentDimensions()',
            'public void updateViewTransformation(',
            'public float[] getPreparedGameViewportBounds(',
            'private void updateWindowPosition(',
            'public void setPreparedWindowAspectFit('])
        methods=methods.replace('private void updatePreparedContentDimensions()', 'void updatePreparedContentDimensions()')
        methods=methods.replace('private void updateWindowPosition(', 'void updateWindowPosition(')
        write('com/winlator/renderer/RendererHarness.java',r'''package com.winlator.renderer;
import java.util.ArrayList;
class Drawable { int width,height;boolean offscreen;Drawable(int w,int h,boolean o){width=w;height=h;offscreen=o;}boolean isOffscreenStorage(){return offscreen;} }
class FullscreenTransformation {short width,height;}
class RenderableWindow {Drawable content;FullscreenTransformation fullscreenTransformation;boolean rotateCounterClockwise,preparedContentCandidate,preparedViewportBlocker;int rootX,rootY;
 RenderableWindow(Drawable d,FullscreenTransformation f,boolean r,boolean c){content=d;fullscreenTransformation=f;rotateCounterClockwise=r;preparedContentCandidate=c;}}
class Screen {int width,height;}
class Window {Drawable content;short x,y;Window(Drawable d,int a,int b){content=d;x=(short)a;y=(short)b;}Drawable getContent(){return content;}short getRootX(){return x;}short getRootY(){return y;}}
class Server {Screen screenInfo=new Screen();RendererHarness renderer;RendererHarness getRenderer(){return renderer;} }
class RendererHarness {boolean preparedEnEinsCenteredNativeSize,preparedWindowAspectFit,forceWindowsFullscreen,fullscreen,viewportNeedsUpdate;long preparedContentDimensions;
 Server xServer=new Server();ArrayList<RenderableWindow> renderableWindows=new ArrayList<>();boolean isFullscreen(){return fullscreen;}
''' +methods+'\n}')
        touch=method((JAVA/'widget/TouchpadView.java').read_text(),'private void updateXform(')
        touch=touch.replace('private void updateXform(', 'void updateXform(')
        write('com/winlator/renderer/TouchpadHarness.java','package com.winlator.renderer;import com.winlator.math.XForm; class TouchpadHarness{Server xServer;float[] xform=XForm.getInstance();TouchpadHarness(RendererHarness r){xServer=r.xServer;xServer.renderer=r;}'+touch+'}')
        activity=(JAVA/'XServerDisplayActivity.java').read_text()
        policy=method(activity,'private boolean shouldAspectFitPreparedOpenParrotWindow(')
        policy=policy.replace('private boolean shouldAspectFitPreparedOpenParrotWindow(', 'boolean shouldAspectFitPreparedOpenParrotWindow(')
        write('com/winlator/renderer/ActivityHarness.java','package com.winlator.renderer;class PreparedLaunch{boolean productionBridge;String executable;}class ActivityHarness{PreparedLaunch preparedWindowsLaunch;'+policy+'}')
        write('com/winlator/renderer/PreparedViewportChecks.java',CHECKS)
        subprocess.run([javac,'-d',str(folder),*[str(p) for p in folder.rglob('*.java')]],check=True)
        subprocess.run([java,'-cp',str(folder),'com.winlator.renderer.PreparedViewportChecks'],check=True)
        # These hooks connect the actual tested methods to live scene updates,
        # rendering, and every pointer event rather than only the size callback.
        assert 'updatePreparedContentDimensions();' in method(renderer,'private void updateScene()')
        assert 'updateViewTransformation(viewTransformation, surfaceWidth, surfaceHeight);' in method(renderer,'protected void drawFrame()')
        assert 'updateXform(getWidth(), getHeight(),' in method((JAVA/'widget/TouchpadView.java').read_text(),'public boolean onTouchEvent(')
        assert 'renderer.setPreparedWindowAspectFit(shouldAspectFitPreparedOpenParrotWindow(preparedAspectFit));' in activity
        print('4 production integration hooks PASS; no device commands')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--before-root',type=Path)
    run(parser.parse_args().before_root)

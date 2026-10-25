"""Compile actual policy against independent pre-change79331185.

Run anywhere. JDK via JAVA_HOME/PATH; --root selects a checkout. No APK/device.
The independent baseline stays pre-Haunted; exact Taisen routes are asserted separately.
--activity is an optional source-only override for baseline/candidate regression checks.
"""
import argparse,hashlib,json,os,shutil,subprocess,tempfile
from pathlib import Path
BASELINE_COMMIT='7933118560a739699af45c67c46e87a180383473'
BASELINE_BLOB='bc88af3ce54f6e1b00a96de181eff15ab01583f5'
RELATIVE='app/src/main/java/com/winlator/XServerDisplayActivity.java'
def tool(name):
 home=os.environ.get('JAVA_HOME')
 if home:
  for leaf in(name+'.exe',name):
   path=Path(home)/'bin'/leaf
   if path.is_file():return str(path)
 value=shutil.which(name)
 if not value:raise RuntimeError('JDK '+name+' required viaJAVA_HOME/PATH')
 return value

def method(source,signature):
 i=source.index(signature);opening=source.index('{',i);level=1;end=opening+1
 while level:
  if source[end]=='{':level+=1
  elif source[end]=='}':level-=1
  end+=1
 return source[i:end]

def branch(source):
 start=source.index('                if (classic640Client || "centered".equals(preparedWindowsLaunch.displayMode))')
 end=source.index('                envVars.remove("TP_CRUISN_USE_REGAL");',start)
 return source[start:end]

def activity(name,source,extra=''):
 return 'class '+name+' { Launch preparedWindowsLaunch; '+method(source,'private boolean shouldUsePreparedPortraitBorderlessAspectFit()')+' '+extra+''' java.util.Map<String,String> apply(Launch value){ preparedWindowsLaunch=value; java.util.Map<String,String> envVars=new java.util.HashMap<>();envVars.put("TP_BORDERLESS_WINDOW","old");envVars.put("TP_CENTER_WINDOW","old");envVars.put("TP_ANDROID_TAISEN_PRESERVE_CLIENT","stale"); if(value!=null){boolean classic640Client="classic-640-client".equals(value.compatibilityPreset);'''+branch(source)+'} return envVars;} boolean nullGuard(){preparedWindowsLaunch=null;return '+('shouldUsePreparedHauntedMuseumBorderlessAspectFit()'if extra else 'shouldUsePreparedPortraitBorderlessAspectFit()')+';} }'

def run(root,output,activity_source=None):
 root=root.resolve();source=activity_source or root/RELATIVE;object_name=BASELINE_COMMIT+':'+RELATIVE
 blob=subprocess.run(['git','rev-parse',object_name],cwd=root,capture_output=True,text=True,check=True).stdout.strip()
 if blob!=BASELINE_BLOB:raise RuntimeError('Independent pre-change blob differs')
 before=subprocess.run(['git','show',object_name],cwd=root,capture_output=True,text=True,check=True,encoding='utf8').stdout
 after=source.read_text(encoding='utf8')
 portrait='private boolean shouldUsePreparedPortraitBorderlessAspectFit()';haunted='private boolean shouldUsePreparedHauntedMuseumBorderlessAspectFit()'
 taisen='private boolean shouldUsePreparedTaisenMahjongBorderlessAspectFit()'
 # Absent on the real pre-policy source: compile it, then fail the new positive checks.
 taisen_extra=method(after,taisen) if taisen in after else ''
 client='private boolean shouldUsePreparedTaisenClientPreservation()'
 client_extra=method(after,client) if client in after else ''
 if method(before,portrait)!=method(after,portrait):raise RuntimeError('Portrait helper must remain identical')
 output.mkdir(parents=True,exist_ok=True)
 code=r'''
 import java.util.*;
 class Launch { boolean productionBridge;int controlsProfileId;String compatibilityPreset,displayMode,executable; }
 BASELINE
 PROPOSED
 public class HauntedPolicyChecks {
  static int assertions;static void check(boolean b,String s){assertions++;if(!b)throw new AssertionError(s);}
  public static void main(String[]args){
   Baseline old=new Baseline();Proposed candidate=new Proposed();int combinations=0,changed=0;
   int taisenChanged=0;
   String x86="E:\\TeknoParrotRuntime\\OpenParrotWin32\\OpenParrotLoader.exe";
   check(!old.nullGuard()&&!candidate.nullGuard(),"null launch helper");
   check(old.apply(null).equals(candidate.apply(null)),"null launch untouched");
   for(int id:new int[]{-1,9004,9008,9013,9014,9015,9061,9079,9999})
   for(String preset:new String[]{null,"shared-jvs-dual-io","portrait-window-counter-clockwise","classic-640-client","taito-legacy-scard","other"})
   for(String mode:new String[]{null,"centered","aspect-fit","fullscreen","other"})
   for(String exe:new String[]{null,x86,x86.toLowerCase(Locale.ROOT),"E:\\TeknoParrotRuntime\\OpenParrotWin64\\OpenParrotLoader64.exe","E:\\TeknoParrotRuntime\\OpenParrotWin32Legacy\\OpenParrotLoader.exe","E:/TeknoParrotRuntime/OpenParrotWin32/OpenParrotLoader.exe","C:\\other.exe"})
   for(boolean production:new boolean[]{false,true}) {
    Launch v=new Launch();v.controlsProfileId=id;v.compatibilityPreset=preset;v.displayMode=mode;v.executable=exe;v.productionBridge=production;
    Map<String,String>a=old.apply(v),b=candidate.apply(v);boolean exact=production&&id==9061&&"shared-jvs-dual-io".equals(preset)&&"aspect-fit".equals(mode)&&x86.equalsIgnoreCase(exe);
    boolean taisenExact=production&&id==9079&&"taito-legacy-scard".equals(preset)&&"aspect-fit".equals(mode)&&x86.equalsIgnoreCase(exe);
    boolean clientExact=production&&id==9079&&"taito-legacy-scard".equals(preset)&&("centered".equals(mode)||"aspect-fit".equals(mode))&&x86.equalsIgnoreCase(exe);
    check(clientExact?"1".equals(b.get("TP_ANDROID_TAISEN_PRESERVE_CLIENT")):!b.containsKey("TP_ANDROID_TAISEN_PRESERVE_CLIENT"),"only exact Taisen centered/aspect-fit sets client preservation; stale optin removed otherwise");
    a.remove("TP_ANDROID_TAISEN_PRESERVE_CLIENT");b.remove("TP_ANDROID_TAISEN_PRESERVE_CLIENT");
    if(taisenExact){
     check(!a.containsKey("TP_BORDERLESS_WINDOW"),"prechange Taisen borderless absent");
     check(!a.containsKey("TP_CENTER_WINDOW"),"prechange Taisen centering absent");
     check("1".equals(b.get("TP_BORDERLESS_WINDOW")),"exact Taisen borderless flag1");
     check("1".equals(b.get("TP_CENTER_WINDOW")),"exact Taisen centering flag1");
     taisenChanged++;combinations++;continue;
    }
    check(a.equals(b)==!exact,"only exact haunted aspect-fit differs");
    check(!exact||("1".equals(b.get("TP_BORDERLESS_WINDOW"))&&"1".equals(b.get("TP_CENTER_WINDOW"))),"exact flags1");
    check(!"centered".equals(mode)||a.equals(b),"W unchanged");
    check((id!=9013&&id!=9014)||a.equals(b),"Raiden Giga unchanged");
    if(!a.equals(b))changed++;combinations++;
   }
   check(combinations==3780,"all combinations");check(changed==2,"two exact case variants only");
   check(taisenChanged==2,"two exact Taisen loader casing variants only");
   System.out.println("PASS "+assertions+" assertions / "+combinations+" combinations / "+changed+" exact Haunted changes / "+taisenChanged+" exact Taisen changes");
  }
 }
 '''.replace('BASELINE',activity('Baseline',before)).replace('PROPOSED',activity('Proposed',after,method(after,haunted)+taisen_extra+client_extra))
 p=output/'HauntedPolicyChecks.java';p.write_text(code,encoding='utf8');classes=output/'classes';classes.mkdir(exist_ok=True)
 built=subprocess.run([tool('javac'),'-encoding','UTF-8','-d',str(classes),str(p)],capture_output=True,text=True)
 if built.returncode:raise RuntimeError(built.stderr)
 test=subprocess.run([tool('java'),'-cp',str(classes),'HauntedPolicyChecks'],capture_output=True,text=True)
 receipt={'scope':'Host actual-method only; no APK/device/game qualification','compile_exit':built.returncode,'test_exit':test.returncode,'stdout':test.stdout,'stderr':test.stderr,'assertions':18905,'original_assertions':15125,'client_optin_assertions':3780,'combinations':3780,'only_changed_exact_routes':2,'taisen_exact_routes':2,'total_exact_changed_routes':4,'independent_prechange_commit':BASELINE_COMMIT,'independent_prechange_blob':BASELINE_BLOB,'activity_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'portrait_helper_byte_equal':True}
 (output/'result.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf8');print(json.dumps(receipt,indent=2))
 if test.returncode:raise RuntimeError('Haunted policy regression failed')
 return receipt

if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1]);p.add_argument('--output',type=Path);p.add_argument('--activity',type=Path);a=p.parse_args();run(a.root,a.output or Path(tempfile.mkdtemp(prefix='tp-haunted-policy-')),a.activity)

"""Compile current Activity policy excerpts; no APK or device work.

Run from any directory. Requires a JDK on PATH; --root selects another checkout.
"""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile


def method(text, signature):
    start = text.index(signature)
    opening = text.index('{', start)
    cursor, depth = opening + 1, 1
    while depth:
        depth += (text[cursor] == '{') - (text[cursor] == '}')
        cursor += 1
    return text[start:cursor]


def run(root):
    source = (root / 'app/src/main/java/com/winlator/XServerDisplayActivity.java').read_text(encoding='utf-8')
    helper = method(source, '    private boolean shouldUsePreparedPortraitBorderlessAspectFit()')
    start = source.index('                boolean classic640Client =')
    end = source.index('                envVars.remove("TP_CRUISN_USE_REGAL");', start)
    policy = source[start:end]
    start = source.index('            boolean useNativeGuestFullscreen =')
    end = source.index('            List<String> result =', start)
    windowed = source[start:end] + '\nreturn windowedValue;'
    assert policy.count('shouldUsePreparedPortraitBorderlessAspectFit()') == 1
    assert 'if (classic640Client || "centered".equals(preparedWindowsLaunch.displayMode))' in policy
    assert 'else if (shouldUsePreparedPortraitBorderlessAspectFit())' in policy

    checks = r'''
import java.util.*;
class Launch {boolean productionBridge;int controlsProfileId;String compatibilityPreset,displayMode,executable;}
class ActivityPolicy {
 Launch preparedWindowsLaunch;Map<String,String> envVars=new HashMap<>();
 void apply(){POLICY}
 HELPER
 String windowed(){WINDOWED}
}
public class GigaPolicyChecks {
 static int checks,combinations,newRoutes;
 static void yes(boolean b,String reason){checks++;if(!b)throw new AssertionError(reason);}
 public static void main(String[] args){
  String loader="E:\\TeknoParrotRuntime\\OpenParrotWin32\\OpenParrotLoader.exe";
  String[] presets={null,"","portrait-window-counter-clockwise","classic-640-client","en-eins-native-fullscreen","music-gungun-native-fullscreen","other"};
  String[] modes={null,"centered","aspect-fit","fullscreen","stretch","other"};
  String[] executables={null,"",loader,loader.toLowerCase(Locale.ROOT),"E:\\TeknoParrotRuntime\\OpenParrotWin64\\OpenParrotLoader64.exe","E:\\TeknoParrotRuntime\\OpenParrotWin32Legacy\\OpenParrotLoader.exe","F:\\TeknoParrotRuntime\\OpenParrotWin32\\OpenParrotLoader.exe","other.exe"};
  for(boolean production:new boolean[]{false,true})for(int id:new int[]{0,9005,9013,9014,9015,9016})for(String preset:presets)for(String mode:modes)for(String exe:executables){
   Launch launch=new Launch();launch.productionBridge=production;launch.controlsProfileId=id;launch.compatibilityPreset=preset;launch.displayMode=mode;launch.executable=exe;
   ActivityPolicy activity=new ActivityPolicy();activity.preparedWindowsLaunch=launch;
   activity.envVars.put("unrelated","preserve");activity.envVars.put("TP_CENTER_WINDOW","stale");activity.envVars.put("TP_BORDERLESS_WINDOW","stale");activity.envVars.put("TP_ANDROID_CLASSIC_640_CLIENT","stale");
   activity.apply();combinations++;
   boolean classic="classic-640-client".equals(preset);
   boolean portraitFit=production&&(id==9014||id==9013)&&"portrait-window-counter-clockwise".equals(preset)&&"aspect-fit".equals(mode)&&loader.equalsIgnoreCase(exe);
   boolean borderless=classic||"centered".equals(mode)||portraitFit;
   yes(borderless?"1".equals(activity.envVars.get("TP_BORDERLESS_WINDOW")):!activity.envVars.containsKey("TP_BORDERLESS_WINDOW"),"exact borderless policy");
   yes(borderless?"1".equals(activity.envVars.get("TP_CENTER_WINDOW")):!activity.envVars.containsKey("TP_CENTER_WINDOW"),"exact centering policy");
   yes(classic?"1".equals(activity.envVars.get("TP_ANDROID_CLASSIC_640_CLIENT")):!activity.envVars.containsKey("TP_ANDROID_CLASSIC_640_CLIENT"),"classic opt-in unchanged");
   yes("preserve".equals(activity.envVars.get("unrelated")),"unrelated setting preserved");
   boolean nativePreset="en-eins-native-fullscreen".equals(preset)||"music-gungun-native-fullscreen".equals(preset);
   String expectedWindowed=classic?"1":("fullscreen".equals(mode)||nativePreset)?"0":"1";
   yes(expectedWindowed.equals(activity.windowed()),"guest native fullscreen/windowed choice unchanged");
   if(portraitFit){newRoutes++;yes("1".equals(activity.windowed()),"Giga/Raiden Fit screen remains guest windowed");}
   if(id==9013&&!classic&&"aspect-fit".equals(mode)&&!portraitFit)yes(!borderless,"other 9013 Fit screen combinations unchanged");
  }
  ActivityPolicy absent=new ActivityPolicy();yes(!absent.shouldUsePreparedPortraitBorderlessAspectFit(),"missing launch excluded");
  yes(newRoutes==4,"only exact Giga/Raiden profile and loader casing variants add borderless policy");
  System.out.println("Giga/Raiden borderless policy PASS: "+checks+" assertions; "+combinations+" combinations; "+newRoutes+" exact route variants");
 }
}
'''.replace('POLICY', policy).replace('HELPER', helper.replace('private boolean', 'boolean')).replace('WINDOWED', windowed)
    javac, java = shutil.which('javac'), shutil.which('java')
    if not javac or not java:
        raise SystemExit('A JDK with javac and java on PATH is required.')
    with tempfile.TemporaryDirectory(prefix='giga-policy-') as temp:
        directory = Path(temp)
        target = directory / 'GigaPolicyChecks.java'
        target.write_text(checks, encoding='utf-8')
        subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(directory), str(target)], check=True)
        subprocess.run([java, '-cp', str(directory), 'GigaPolicyChecks'], check=True)
    print('2 current-source wiring checks PASS; no snapshots, APK builds or device commands')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    run(parser.parse_args().root.resolve())

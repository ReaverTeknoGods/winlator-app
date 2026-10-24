"""Compile/run the portable BG4 projection with real local symlinks; no APK/device."""
from pathlib import Path
import argparse, hashlib, json, shutil, subprocess, tempfile

def method(source, signature):
    start=source.index(signature); opening=source.index('{',start); depth=1; cursor=opening+1
    while depth:
        if source[cursor]=='{':depth+=1
        elif source[cursor]=='}':depth-=1
        cursor+=1
    return source[start:cursor]

def main():
    repo=Path(__file__).resolve().parent.parent
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path)
    args=parser.parse_args();output=args.output or Path(tempfile.mkdtemp(prefix='tpui-bg4-projection-'));output.mkdir(parents=True,exist_ok=True)
    activity=repo/'app/src/main/java/com/winlator/XServerDisplayActivity.java';source=activity.read_text(encoding='utf-8')
    setup=method(source,'private void setupXEnvironment()');destroy=method(source,'protected void onDestroy()')
    assert setup.index('ProcessHelper.killGuestProcesses();')<setup.index('prepareBg4DataProjection()')<setup.index('environment.startEnvironmentComponents();')
    assert destroy.index('environment.stopEnvironmentComponents();')<destroy.index('ProcessHelper.killGuestProcesses();')<destroy.index('restorePreparedBg4DataProjection();')
    prepare=method(source,'private boolean prepareBg4DataProjection()');restore=method(source,'private boolean restorePreparedBg4DataProjection()')
    assert 'container.setDrives' not in prepare and 'saveData' not in prepare
    assert 'awaitPreparedBg4GuestsStopped()' in prepare and 'awaitPreparedBg4GuestsStopped()' in restore
    helper=repo/'app/src/main/java/com/winlator/teknoparrot/PreparedBg4DataProjection.java';paths=repo/'app/src/main/java/com/winlator/teknoparrot/PreparedGameExecutablePath.java';test=repo/'tools/PreparedBg4DataProjectionTest.java'
    classes=output/'classes';classes.mkdir(exist_ok=True)
    javac,java=shutil.which('javac'),shutil.which('java')
    if not javac or not java:raise RuntimeError('Java JDK required')
    built=subprocess.run([javac,'-encoding','UTF-8','-d',str(classes),str(helper),str(paths),str(test)],capture_output=True,text=True)
    result={'scope':'Actual portable policy + real local symlinks. Windows mirrors e: filename and emulates POSIX directory-symlink replacement; this does not verify Android atomic publication. Production has no unlink fallback.','activity_sha256':hashlib.sha256(activity.read_bytes()).hexdigest(),'helper_sha256':hashlib.sha256(helper.read_bytes()).hexdigest(),'compile_exit_code':built.returncode,'compile_stdout':built.stdout,'compile_stderr':built.stderr,'activity_lifecycle_order_checks':4}
    if built.returncode==0:
        tested=subprocess.run([java,'-cp',str(classes),'com.winlator.teknoparrot.PreparedBg4DataProjectionTest',str(output/'fixtures')],capture_output=True,text=True)
        result.update(test_exit_code=tested.returncode,stdout=tested.stdout,stderr=tested.stderr)
    (output/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(result,indent=2));raise SystemExit(result.get('test_exit_code',built.returncode))

if __name__=='__main__':main()

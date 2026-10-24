"""Rebuild the exact reviewed AMD64 APM builtin into a NEW output directory.

Never changes the checked-in APK asset. MSVC/toolchain drift fails comparison
and retains outputs. Only two proven linker timestamp DWORDs are normalized.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess

EXPECTED='b5601e4e27fa314c3176c2503b8518e5e3736e383c36e1b35bf15d61b2077ec6'
NORMALIZED='7ae008791541b778c5362b76c3650330f8d1e616ff8e3001893e7bfd1dc789ef'
TIMESTAMP=1791880533
SOURCES={'APMExportProxy64.cpp':'f55f1e54072188463cf674ae4832e95ba0c653984b2d768e6f3930a8bfc60af6',
         'APMExportProxy64.def':'572a11882668db262e5671f06a1dad323a80f860415071dedfc8c511b91c5fb2'}
def sha(raw):return hashlib.sha256(raw).hexdigest()
def reviewed_identity(raw):
    data=bytearray(raw)
    if len(data)!=12288 or data[:2]!=b'MZ':raise RuntimeError('Unexpected APM image size/signature')
    pe=struct.unpack_from('<I',data,60)[0]
    if pe!=200 or data[pe:pe+4]!=b'PE\0\0' or struct.unpack_from('<H',data,pe+4)[0]!=0x8664:
        raise RuntimeError('Unexpected reviewed AMD64 PE layout')
    # The complete normalized hash below verifies these exact field locations,
    # export layout, all code/imports/headers and padding before writing them.
    if struct.unpack_from('<I',data,6156)[0]!=13:raise RuntimeError('Unexpected type13 debug-directory layout')
    data[64:96]=b'Wine builtin DLL'+bytes(16)
    for offset in (208,6148):data[offset:offset+4]=bytes(4)
    if sha(data)!=NORMALIZED:raise RuntimeError('Compiler output differs beyond the two proven timestamps; retain for review')
    for offset in (208,6148):struct.pack_into('<I',data,offset,TIMESTAMP)
    if sha(data)!=EXPECTED:raise RuntimeError('Exact reviewed APM identity did not reproduce')
    return bytes(data)
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--out',required=True,type=Path);args=parser.parse_args()
    source=Path(__file__).resolve().parent;out=args.out.resolve()
    for name,digest in SOURCES.items():
        path=source/name
        if path.is_symlink()or sha(path.read_bytes().replace(b'\r\n',b'\n'))!=digest:raise RuntimeError('Reviewed source changed: '+name)
    out.mkdir(parents=True,exist_ok=False)
    vswhere=Path(os.environ['ProgramFiles(x86)'])/'Microsoft Visual Studio/Installer/vswhere.exe'
    vs=subprocess.check_output([str(vswhere),'-latest','-products','*','-requires','Microsoft.VisualStudio.Component.VC.Tools.x86.x64','-property','installationPath'],text=True).strip()
    if not vs:raise RuntimeError('Visual Studio C++ tools are required')
    target=out/'apm.dll';command=out/'build.cmd';developer=Path(vs)/'Common7/Tools/VsDevCmd.bat'
    compiler=['cl','/nologo','/LD','/Od','/GS-','/Zl',str(source/'APMExportProxy64.cpp'),'/Fo'+str(out/'APMExportProxy64.obj'),'/link','/NODEFAULTLIB','/ENTRY:DllMain','/DEF:'+str(source/'APMExportProxy64.def'),'/OUT:'+str(target)]
    command.write_text('@echo off\ncall "'+str(developer)+'" -no_logo -arch=x64 -host_arch=x64\nif errorlevel 1 exit /b 1\n'+subprocess.list2cmdline(compiler)+'\n')
    result=subprocess.run(['cmd.exe','/d','/c',str(command)],cwd=out,capture_output=True)
    (out/'build.log').write_bytes(result.stdout+result.stderr)
    if result.returncode:raise RuntimeError('Compiler failed; output/log retained')
    raw=target.read_bytes();(out/'compiler-output-before-marker-and-time-normalization.dll').write_bytes(raw)
    final=reviewed_identity(raw);target.write_bytes(final)
    receipt={'sha256':sha(final),'bytes':len(final),'source_origin':'OpenParrot Tools/WineApmCompatibility local commitd19f57d844de332b1e2c8c81895c3b6727293193',
             'canonical_source_sha256':SOURCES,'normalization':'Wine DOS builtin marker plus exact COFF/type13 DEBUG timestamps; complete normalized-file identity required first.',
             'normalized_sha256':NORMALIZED,'script_sha256':sha(Path(__file__).read_bytes()),'asset_modified':False,'executed':False}
    (out/'build-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(target)
if __name__=='__main__':main()

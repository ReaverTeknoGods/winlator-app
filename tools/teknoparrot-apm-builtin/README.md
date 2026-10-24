# Otoshu Wine APM export surface

These source-only C++ stubs and the explicit ordinal specification originate in
OpenParrot `Tools/WineApmCompatibility`, local commit
`d19f57d844de332b1e2c8c81895c3b6727293193`. They contain no proprietary APM
implementation. OpenParrot replaces the 106 separate entry points with its
existing APM API hooks before game code uses them; the zero-return functions
alone do not implement inputs, accounting, networking or APM behavior.

## License

The copied `APMExportProxy64.cpp` and `APMExportProxy64.def` sources, and the
`apm.dll` built from them, retain OpenParrot's GNU General Public License
version 3. The complete license is included in [COPYING](COPYING). This
component's GPLv3 license is separate from the repository's root LGPLv2.1
license. The original source revision is identified above; the corresponding
source and rebuild instructions are included in this directory.

The Android candidate asset is exactly 12,288 bytes, AMD64/PE32+, SHA256
`b5601e4e27fa314c3176c2503b8518e5e3736e383c36e1b35bf15d61b2077ec6`.
Its 106 export names, ordinals and distinct RVAs match the supplied original
Otoshu APM interface. It has no imports and its own simple successful DllMain;
no game library or game executable is patched or copied.

The Linux-qualified deployment uses a distinct raw SHA256, `b504b4af6b24a4f40bac1b63fdd3204b8d686fe7a0ea792bc84fdf7ede0f38dc`, whose
entire image equals this asset after normalizing only the COFF and type-13 debug
timestamps. Do not call this exact Android asset device-qualified based on that
equivalence. Native Windows default launch paths do not select this asset.

Rebuild with Visual Studio C++ tools and a new output directory:

```powershell
python tools/teknoparrot-apm-builtin/build.py --out C:\work\new-apm-build
```

The script verifies normalized source identity, compiles with the original
MSVC flags, and requires complete binary equality after the two known linker
timestamps and Wine DOS marker are normalized. It then reproduces the exact
asset timestamps and records provenance. Any other toolchain/output difference
halts and retains the new files. It never overwrites the checked-in APK asset.

At runtime only the exact reviewed Otoshu executable/preset/controls/loader
route may stage the asset in a fresh owned app-private UUID directory. The
activity composes a host-absolute `WINEDLLPATH` parent and appends `apm=b` after
normal per-launch overrides. Original EXE, Unity data, working directory, game
saves, proprietary APM DLLs, prefix system32 and updater runtime roots remain
untouched. All staged roots are retained.

Android acceptance still requires proof of the actual loaded backing file and
all 106 hookable exports under its installed Wine build. Wine's compiled builtin
directory can precede `WINEDLLPATH`; reject a collision or prove the selected
module. Then test both saved display modes and actual TPUI player controls.

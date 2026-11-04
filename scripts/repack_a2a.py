#!/usr/bin/env python3
"""Replace lib/arm64-v8a/liba2a.so in an APK without touching anything else.

Keeps every entry's original compression; writes the new native lib as STORED
so Android can map it directly (extractNativeLibs=false). Old v1 signature
files are dropped; the caller re-signs with apksigner.
"""
import sys, zipfile, os


def main(base, newlib, out):
    lib_name = "lib/arm64-v8a/liba2a.so"
    if not os.path.isfile(newlib):
        sys.exit(f"missing new lib: {newlib}")
    new_size = os.path.getsize(newlib)
    replaced = False
    with zipfile.ZipFile(base) as zin, zipfile.ZipFile(out, "w", allowZip64=True) as zout:
        for info in zin.infolist():
            name = info.filename
            if name.startswith("META-INF/") and (
                name.endswith((".SF", ".RSA", ".DSA")) or name.endswith("MANIFEST.MF")
            ):
                continue
            data = zin.read(name)
            if name == lib_name:
                data = open(newlib, "rb").read()
                info.compress_type = zipfile.ZIP_STORED
                replaced = True
            zi = zipfile.ZipInfo(name, date_time=info.date_time)
            zi.compress_type = info.compress_type
            zi.external_attr = info.external_attr
            zi.internal_attr = info.internal_attr
            zi.create_system = info.create_system
            zi.comment = info.comment
            zout.writestr(zi, data)
    if not replaced:
        sys.exit(f"{lib_name} not found in {base}")
    print(f"repacked {out}: {lib_name} <- {newlib} ({new_size} bytes)")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit("usage: repack_a2a.py <base.apk> <new-liba2a.so> <out.apk>")
    main(*sys.argv[1:])

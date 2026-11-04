#!/usr/bin/env python3
"""Patch an OpenMinis APK into a plugin-host APK.

- lib/arm64-v8a/liba2a.so          -> loader stub (bionic PIE)
- lib/arm64-v8a/liba2a_server.so   -> bundled Agent2API server (bionic PIE)
Everything else is copied byte-for-byte. Old v1 signature files are dropped;
the caller re-signs with apksigner.
"""
import sys, zipfile, os


def main(base, stub, server, out):
    lib_stub = "lib/arm64-v8a/liba2a.so"
    lib_server = "lib/arm64-v8a/liba2a_server.so"
    for p in (stub, server):
        if not os.path.isfile(p):
            sys.exit(f"missing file: {p}")
    seen_stub = seen_server = False
    with zipfile.ZipFile(base) as zin, zipfile.ZipFile(out, "w", allowZip64=True) as zout:
        for info in zin.infolist():
            name = info.filename
            if name.startswith("META-INF/") and (
                name.endswith((".SF", ".RSA", ".DSA")) or name.endswith("MANIFEST.MF")
            ):
                continue
            data = zin.read(name)
            if name == lib_stub:
                data = open(stub, "rb").read()
                info.compress_type = zipfile.ZIP_STORED
                seen_stub = True
            elif name == lib_server:
                data = open(server, "rb").read()
                info.compress_type = zipfile.ZIP_STORED
                seen_server = True
            zi = zipfile.ZipInfo(name, date_time=info.date_time)
            zi.compress_type = info.compress_type
            zi.external_attr = info.external_attr
            zi.internal_attr = info.internal_attr
            zi.create_system = info.create_system
            zi.comment = info.comment
            zout.writestr(zi, data)
        if not seen_stub:
            sys.exit(f"{lib_stub} not found in {base}")
        if not seen_server:
            zi = zipfile.ZipInfo(lib_server, date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_STORED
            zi.external_attr = (0o100755 << 16)
            zout.writestr(zi, open(server, "rb").read())
    print(f"patched {out}: {lib_stub} <- {stub}, {lib_server} <- {server}")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        sys.exit("usage: patch_plugin_apk.py <base.apk> <stub.so> <server.so> <out.apk>")
    main(*sys.argv[1:])

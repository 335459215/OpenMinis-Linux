#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""[T-005] Guard the rolling release asset against being clobbered.

`main` in this repo is a *pure mirror* of upstream (`git push --force`, no local
commits) and upstream's own `android-apk.yml` publishes `android-latest` too —
without the private A2A assets that only exist on `dev`. So every upstream sync
lands an APK of the crippled class (≈104 MiB instead of ≈141 MiB) into the
release tag users download from, silently wiping the complete build.

The build-side gates in `android-apk.yml` cannot stop that: they run on `dev`
and prove *our* artifact is complete, minutes before somebody else overwrites
it. This script is the read/verify half of the recovery path — it answers "is
the asset currently on the tag shippable?" using the exact same policy as the
build gate ([SIZE_FLOOR] from `check_release_baseline.py`), so the two can
never drift.

Usage:
  guard_release_asset.py floor
  guard_release_asset.py check --release-json rel.json --asset minis-....apk

Exit codes for `check`: 0 shippable, 1 crippled, 2 missing/unknown.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

# Single source of truth: the build gate's floor and the guard's floor are the
# same number, imported rather than copied.
from check_release_baseline import SIZE_FLOOR  # noqa: E402

MIB = 1048576

SHIPPABLE = 0
CRIPPLED = 1
MISSING = 2


def asset_of(release: dict, name: str) -> dict | None:
    """The one asset called [name] in a `gh release view --json assets` dump."""
    for asset in release.get("assets") or []:
        if asset.get("name") == name:
            return asset
    return None


def verdict(size: int | None) -> int:
    """Map an asset size to a [SHIPPABLE]/[CRIPPLED]/[MISSING] exit code."""
    if not size or size <= 0:
        return MISSING
    return SHIPPABLE if size >= SIZE_FLOOR else CRIPPLED


def describe(size: int | None) -> str:
    if not size:
        return "missing"
    return f"{size:,} B ({size / MIB:.1f} MiB)"


def check(release_json: Path, name: str) -> int:
    release = json.loads(release_json.read_text())
    asset = asset_of(release, name)
    size = int(asset["size"]) if asset and asset.get("size") else None
    code = verdict(size)
    label = {SHIPPABLE: "shippable", CRIPPLED: "CRIPPLED", MISSING: "MISSING"}[code]
    print(f"asset {name}: {describe(size)} -> {label} (floor {SIZE_FLOOR:,} B)")
    if asset and asset.get("digest"):
        print(f"digest {asset['digest']}")
    if code == CRIPPLED:
        print(
            f"the tag ships {SIZE_FLOOR - size:,} B less than a complete build — "
            "this is the class produced without the private A2A assets",
            file=sys.stderr,
        )
    return code


def main() -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("floor", help="print the size floor in bytes")
    c = sub.add_parser("check", help="verdict for one asset of a release dump")
    c.add_argument("--release-json", type=Path, required=True)
    c.add_argument("--asset", required=True)
    a = ap.parse_args()

    if a.cmd == "floor":
        print(SIZE_FLOOR)
        return 0
    return check(a.release_json, a.asset)


if __name__ == "__main__":
    sys.exit(main())

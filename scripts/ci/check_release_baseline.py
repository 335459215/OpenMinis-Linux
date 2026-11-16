#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""[T-release-baseline] Refuse to publish an APK that is missing private assets.

Why this exists (measured, not guessed):

    c63cfcb (dev)   147,940,417 B   complete
    161c1b7 (main)  108,175,488 B   missing 90 files / 54.8 MB
    13226f7 / 9572caa / 6af6ff5    108,175,488 B   same defect

The upstream mirror branch (`main`) does not carry the closed-source payloads
(`liba2a_*.so`, `assets/a2a/a2a-ui.zip`, the kotlin-memory jars), so every APK
built from it silently shipped without the Agent2API gateway, the panel UI and
the in-guest memory engine. Nothing failed; the artifact simply weighed 40 MB
less and those features were dead on arrival.

Two independent gates:

  * `--assets`  the private payloads exist in the tree (pre-build)
  * `--apk`     the built APK still contains them and clears the size floor

Usage:
    python3 scripts/ci/check_release_baseline.py --assets
    python3 scripts/ci/check_release_baseline.py --apk dist/minis-ultra-com.openminis.linux.apk
    python3 scripts/ci/check_release_baseline.py --record --apk ... (append a
        build_baseline row to the SQLite ledger when one is mounted)
"""
from __future__ import annotations

import argparse
import hashlib
import os
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / "src" / "android" / "app" / "src" / "main"

# Files whose absence means the APK is crippled. Sizes are the exact tracked
# sizes as of c63cfcb — a wild size swing means the asset was replaced by a
# stub, which is worse than missing because it looks fine.
PRIVATE_ASSETS = [
    ("jniLibs/arm64-v8a/liba2a_server.so", 16_433_232, "lib/arm64-v8a/liba2a_server.so"),
    ("jniLibs/arm64-v8a/liba2a_providers.so", 11_040_808, "lib/arm64-v8a/liba2a_providers.so"),
    ("jniLibs/arm64-v8a/liba2a_web_providers.so", 3_192_888, "lib/arm64-v8a/liba2a_web_providers.so"),
    ("assets/a2a/a2a-ui.zip", 5_859_970, "assets/a2a/a2a-ui.zip"),
    (
        "assets/default_mount/usr/local/share/minis-extra/kotlin-memory/sqlite-jdbc.jar",
        14_342_332,
        "assets/default_mount/usr/local/share/minis-extra/kotlin-memory/sqlite-jdbc.jar",
    ),
    (
        "assets/default_mount/usr/local/share/minis-extra/kotlin-memory/mem.jar",
        5_716_863,
        "assets/default_mount/usr/local/share/minis-extra/kotlin-memory/mem.jar",
    ),
]

# Complete builds measured so far. 147,940,417 B on every dev build; the
# 108,175,488 B class is the crippled one. 140 MiB leaves room for real growth
# while still catching a 40 MB hole.
SIZE_FLOOR = 140 * 1024 * 1024
EXPECTED_BRANCH = "dev"

failures: list[str] = []


def fail(msg: str) -> None:
    failures.append(msg)
    print(f"  FAIL  {msg}")


def ok(msg: str) -> None:
    print(f"  ok    {msg}")


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def check_assets() -> None:
    print(f"private assets in {APP}")
    for rel, want, _ in PRIVATE_ASSETS:
        p = APP / rel
        if not p.is_file():
            fail(f"missing {rel}")
            continue
        got = p.stat().st_size
        # 5% band: enough for a harmless rebuild, tight enough to catch a stub.
        if abs(got - want) > max(want * 0.05, 4096):
            fail(f"{rel} is {got:,} B, expected ~{want:,} B (stub or wrong build?)")
        else:
            ok(f"{rel} ({got:,} B)")


def check_apk(path: Path) -> dict:
    if not path.is_file():
        fail(f"APK not found: {path}")
        return {}
    size = path.stat().st_size
    print(f"APK {path.name}  {size:,} B ({size / 1048576:.1f} MiB)")
    if size < SIZE_FLOOR:
        fail(
            f"APK is {size:,} B, below the {SIZE_FLOOR:,} B floor "
            f"({SIZE_FLOOR / 1048576:.0f} MiB) — this is the crippled build class"
        )
    else:
        ok(f"size above floor ({SIZE_FLOOR:,} B)")

    with zipfile.ZipFile(path) as z:
        names = set(z.namelist())
        for _rel, _want, entry in PRIVATE_ASSETS:
            if entry not in names:
                fail(f"APK is missing {entry}")
            else:
                ok(f"APK contains {entry}")
        a2a_packs = sum(1 for n in names if n.startswith("assets/a2a/packs/"))
        if a2a_packs == 0:
            fail("APK has no assets/a2a/packs/** (provider UI payloads gone)")
        else:
            ok(f"APK carries {a2a_packs} a2a pack files")
    return {
        "apk_name": path.name,
        "apk_size": size,
        "apk_sha256": sha256_of(path),
    }


def check_branch() -> None:
    branch = os.environ.get("GITHUB_REF_NAME", "").strip()
    if not branch:
        print("branch: (not in CI, skipped)")
        return
    print(f"branch: {branch}")
    if branch != EXPECTED_BRANCH:
        fail(f"releases come from '{EXPECTED_BRANCH}', not '{branch}'")
    else:
        ok(f"releasing from {EXPECTED_BRANCH}")


def record(meta: dict, branch: str) -> None:
    """Append a build_baseline row when the ledger is reachable."""
    import sqlite3

    db = Path("/var/minis/shared/minis-ultra/tasks.db")
    if not db.is_file() or not meta:
        print("ledger: skipped (no tasks.db)")
        return
    with sqlite3.connect(db) as con:
        con.execute(
            """INSERT INTO build_baseline
               (apk_name, apk_size, apk_sha256, head_sha, head_branch,
                base_upstream, has_private_assets, artifact_id, built_at, note)
               VALUES (?,?,?,?,?,?,?,?,datetime('now','localtime'),?)""",
            (
                meta["apk_name"],
                meta["apk_size"],
                meta["apk_sha256"],
                os.environ.get("GITHUB_SHA", "local"),
                branch or "local",
                "",
                1,
                os.environ.get("GITHUB_RUN_ID", ""),
                "verified by check_release_baseline.py",
            ),
        )
        con.commit()
    print(f"ledger: recorded {meta['apk_name']}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", action="store_true", help="check the source tree (pre-build)")
    ap.add_argument("--apk", type=Path, help="check a built APK")
    ap.add_argument("--record", action="store_true", help="append a build_baseline row")
    ap.add_argument("--skip-branch", action="store_true", help="ignore the branch gate")
    a = ap.parse_args()

    if not a.assets and not a.apk:
        ap.error("give --assets and/or --apk")

    branch = os.environ.get("GITHUB_REF_NAME", "").strip()
    if not a.skip_branch:
        check_branch()
    if a.assets:
        check_assets()
    meta = check_apk(a.apk) if a.apk else {}
    if a.record:
        record(meta, branch)

    if failures:
        print(f"\nrelease baseline: {len(failures)} problem(s) — refusing to publish", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1
    print("\nrelease baseline: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())

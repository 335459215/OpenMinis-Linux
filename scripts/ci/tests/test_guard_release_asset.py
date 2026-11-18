#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""[T-005] Unit tests for the rolling-release guard.

Run with:  python3 -m unittest discover -s scripts/ci/tests -p 'test_*.py'
"""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import check_release_baseline  # noqa: E402
import guard_release_asset as g  # noqa: E402


class VerdictTest(unittest.TestCase):
    def test_floor_is_shared_with_the_build_gate(self) -> None:
        # One number, one owner: the guard must never re-declare the floor.
        self.assertEqual(g.SIZE_FLOOR, check_release_baseline.SIZE_FLOOR)

    def test_complete_build_is_shippable(self) -> None:
        self.assertEqual(g.verdict(147_940_417), g.SHIPPABLE)

    def test_crippled_class_is_rejected(self) -> None:
        # The measured 108,175,488 B / 109,221,032 B classes.
        self.assertEqual(g.verdict(108_175_488), g.CRIPPLED)
        self.assertEqual(g.verdict(109_221_032), g.CRIPPLED)
        self.assertEqual(g.verdict(53_700_000), g.CRIPPLED)

    def test_boundary_is_inclusive(self) -> None:
        self.assertEqual(g.verdict(g.SIZE_FLOOR), g.SHIPPABLE)
        self.assertEqual(g.verdict(g.SIZE_FLOOR - 1), g.CRIPPLED)

    def test_missing_size_is_not_shippable(self) -> None:
        self.assertEqual(g.verdict(None), g.MISSING)
        self.assertEqual(g.verdict(0), g.MISSING)


class AssetLookupTest(unittest.TestCase):
    RELEASE = {
        "tagName": "android-latest",
        "assets": [
            {"name": "libunwind.a", "size": 353_722, "digest": "sha256:aa"},
            {"name": "minis-ultra-com.openminis.linux.apk", "size": 147_940_417,
             "digest": "sha256:bb"},
        ],
    }

    def test_picks_the_named_asset(self) -> None:
        asset = g.asset_of(self.RELEASE, "minis-ultra-com.openminis.linux.apk")
        self.assertIsNotNone(asset)
        self.assertEqual(asset["size"], 147_940_417)

    def test_absent_asset_is_none(self) -> None:
        self.assertIsNone(g.asset_of(self.RELEASE, "nope.apk"))
        self.assertIsNone(g.asset_of({}, "nope.apk"))


class CliTest(unittest.TestCase):
    APK = "minis-ultra-com.openminis.linux.apk"

    def _check(self, size: int | None) -> int:
        release = {"assets": [{"name": self.APK, "size": size}] if size is not None else []}
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "rel.json"
            p.write_text(json.dumps(release))
            return g.check(p, self.APK)

    def test_cli_exit_codes(self) -> None:
        self.assertEqual(self._check(147_940_417), 0)
        self.assertEqual(self._check(109_221_032), 1)
        self.assertEqual(self._check(None), 2)


if __name__ == "__main__":
    unittest.main()

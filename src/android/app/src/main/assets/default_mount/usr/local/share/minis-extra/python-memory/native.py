"""Bridge between Minis' native Markdown memory and the SQLite engine.

Minis Ultra already gives every chat a ``/var/minis/memory`` directory:

- ``YYYY-MM-DD.md`` daily notes written by ``memory_write``
- ``GLOBAL.md`` standing notes for this chat

The bridge indexes those files into the structured store, so the MCP recall
tool can rank them with the hybrid scorer. New high-value memories can also
be mirrored back into the daily log, which keeps the built-in keyword recall
working as a second, always-on safety net.
"""

from __future__ import annotations

import os
import re
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable

from .config import Config
from .store import Store
from .util import (
    normalize_text,
    strip_native_prefix,
    summary_of,
    text_hash,
)

DATE_FILE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
HEADING_RE = re.compile(r"^#{1,6}\s*(.+?)\s*$")


def _base_timestamp(path: Path) -> float:
    if DATE_FILE_RE.match(path.stem):
        try:
            return datetime.strptime(path.stem, "%Y-%m-%d").timestamp()
        except ValueError:
            pass
    try:
        return path.stat().st_mtime
    except OSError:
        return time.time()


def _kind_for(path: Path, section: str) -> tuple[str, float]:
    name = path.name.lower()
    if name == "global.md":
        return "rule", 0.82
    section_text = section or ""
    if any(word in section_text for word in ("规则", "约定", "长期", "原则")):
        return "rule", 0.80
    if any(word in section_text for word in ("偏好", "喜好", "习惯")):
        return "preference", 0.75
    if any(word in section_text for word in ("任务", "待办", "计划")):
        return "task", 0.65
    if any(word in section_text for word in ("事实", "信息", "资料")):
        return "fact", 0.65
    return "episode", 0.50


def parse_markdown(path: Path) -> list[dict[str, Any]]:
    """Turn a Markdown memory file into structured, insertable items."""
    try:
        raw = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return []
    lines = raw.splitlines()
    base_time = _base_timestamp(path)
    section = ""
    items: list[dict[str, Any]] = []

    for line_number, raw_line in enumerate(lines, 1):
        line = raw_line.strip()
        if not line:
            continue

        heading = HEADING_RE.match(line)
        if heading:
            section = heading.group(1).strip()
            continue

        if line.startswith("<!--") or line.startswith("```") or line in {"---", "***"}:
            continue

        content = strip_native_prefix(line)
        if not content or len(content) < 4 or len(content) > 1200:
            continue
        if content.startswith("#"):
            continue

        kind, importance = _kind_for(path, section)
        # A line under a "偏好" heading is more valuable than a random
        # daily episode, but an explicitly pinned GLOBAL note stays highest.
        if path.name.lower() == "global.md":
            kind, importance = "rule", 0.82

        items.append(
            {
                "text": content,
                "kind": kind,
                "importance": importance,
                "pinned": path.name.lower() == "global.md",
                "source_ref": f"native:{path.name}:{line_number}",
                "created_at": base_time + line_number * 60.0,
                "meta": {
                    "file": path.name,
                    "line": line_number,
                    "section": section,
                },
            }
        )

    return items


def _memory_dir(config: Config) -> Path:
    return Path(config.memory_dir)


def _iter_markdown(memory_dir: Path) -> list[Path]:
    if not memory_dir.exists():
        return []
    files: list[Path] = []
    for path in sorted(memory_dir.glob("*.md")):
        if path.name.startswith("minis-memory"):
            continue
        files.append(path)
    return files


def sync_native(store: Store, config: Config, namespace: str = "default") -> dict[str, Any]:
    """Incrementally index the native Markdown files into the store."""
    memory_dir = _memory_dir(config)
    files = _iter_markdown(memory_dir)
    seen: set[str] = set()
    imported = 0
    changed_files = 0
    skipped_files = 0
    # 防回声：镜像到 Markdown 的条目已经在库里（source != native），
    # 下次 sync 读到同一行时按正文跳过，避免一条记忆变两条。
    existing = {
        normalize_text(row.get("text") or "")
        for row in store.list_memories(namespace, status=None, limit=10000)
        if row.get("source") != "native"
    }

    for path in files:
        name = path.name
        seen.add(name)
        try:
            raw = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            skipped_files += 1
            continue
        digest = text_hash(raw)
        key = f"native_sha:{name}"
        if store.kv_get(key) == digest:
            skipped_files += 1
            continue

        store.delete_by_source("native", f"native:{name}:")
        items = parse_markdown(path)
        for item in items:
            norm = normalize_text(item["text"])
            if norm and norm in existing:
                continue
            store.insert_memory(
                namespace=namespace,
                kind=item["kind"],
                text=item["text"],
                summary=summary_of(item["text"]),
                importance=item["importance"],
                confidence=0.85,
                source="native",
                source_ref=item["source_ref"],
                visibility="private",
                pinned=item["pinned"],
                meta=item["meta"],
                created_at=item["created_at"],
                updated_at=item["created_at"],
            )
            imported += 1
            existing.add(norm)
        store.kv_set(key, digest)
        changed_files += 1

    # Remove rows whose source file disappeared.
    for key in store.kv_keys("native_sha:"):
        name = key.split(":", 1)[1] if ":" in key else ""
        if name and name not in seen:
            store.delete_by_source("native", f"native:{name}:")
            store.kv_set(key, "")

    return {
        "memory_dir": str(memory_dir),
        "files": len(files),
        "changed_files": changed_files,
        "skipped_files": skipped_files,
        "imported": imported,
        "namespace": namespace,
    }


def append_daily(memory_dir: str, text: str, kind: str, importance: float) -> str:
    """Append a mirrored line to today's native memory log."""
    directory = Path(memory_dir)
    directory.mkdir(parents=True, exist_ok=True)
    today = datetime.now().strftime("%Y-%m-%d")
    path = directory / f"{today}.md"
    if not path.exists():
        path.write_text(f"# {today}\n\n", encoding="utf-8")

    existing = path.read_text(encoding="utf-8", errors="ignore")
    value = normalize_text(text)
    if value and value in existing:
        return str(path)

    clock = datetime.now().strftime("%H:%M")
    marker = text_hash(value)[:10]
    line = f"- {clock} [{kind}·{importance:.1f}] {value} <!-- mm:{marker} -->\n"
    with path.open("a", encoding="utf-8") as handle:
        handle.write(line)
        handle.flush()
        try:
            os.fsync(handle.fileno())
        except OSError:
            pass
    return str(path)


def append_global(memory_dir: str, text: str, kind: str, importance: float) -> str:
    """Append a line to this chat's GLOBAL.md standing notes."""
    directory = Path(memory_dir)
    directory.mkdir(parents=True, exist_ok=True)
    target = Path(os.environ.get("MINIS_GLOBAL_MEMORY") or (directory / "GLOBAL.md"))
    if not target.exists():
        target.write_text("# GLOBAL\n\n## 长期记忆\n\n", encoding="utf-8")
    content = target.read_text(encoding="utf-8", errors="ignore")
    if "## 长期记忆" not in content:
        with target.open("a", encoding="utf-8") as handle:
            handle.write("\n## 长期记忆\n\n")
    value = normalize_text(text)
    line = f"- [{kind}·{importance:.1f}] {value} <!-- mm:{text_hash(value)[:10]} -->\n"
    with target.open("a", encoding="utf-8") as handle:
        handle.write(line)
        handle.flush()
        try:
            os.fsync(handle.fileno())
        except OSError:
            pass
    return str(target)

"""Command-line entry point for Minis Memory Ultra."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from .config import Config
from .engine import MemoryEngine
from .httpd import run_http
from .mcp import serve_stdio


def _build_engine(args: argparse.Namespace) -> MemoryEngine:
    config = Config.load(getattr(args, "config", None))
    return MemoryEngine(config)


def _print(data: Any) -> None:
    print(json.dumps(data, ensure_ascii=False, indent=2, default=str))


def cmd_serve(args: argparse.Namespace) -> int:
    engine = _build_engine(args)
    if args.http:
        try:
            run_http(engine, host=args.host, port=args.port)
        except OSError as exc:
            # 端口已被另一个实例占用：静默退出，别让 stdio MCP 跟着崩
            if getattr(exc, "errno", None) in (98, 48):
                return 0
            raise
    else:
        serve_stdio(engine)
    return 0


def cmd_remember(args: argparse.Namespace) -> int:
    engine = _build_engine(args)
    _print(
        engine.remember(
            text=args.text,
            kind=args.kind,
            importance=args.importance,
            source=args.source,
            pinned=args.pinned,
            namespace=args.namespace,
        )
    )
    return 0


def cmd_recall(args: argparse.Namespace) -> int:
    engine = _build_engine(args)
    result = engine.recall(
        query=args.query,
        k=args.k,
        budget_chars=args.budget,
        namespace=args.namespace,
        kind=args.kind,
        explain=args.explain,
    )
    if args.json:
        _print(result)
    else:
        print(result["block"] or "（无匹配记忆）")
    return 0


def cmd_profile(args: argparse.Namespace) -> int:
    engine = _build_engine(args)
    print(engine.profile(namespace=args.namespace, max_chars=args.max_chars) or "（暂无画像）")
    return 0


def cmd_stats(args: argparse.Namespace) -> int:
    engine = _build_engine(args)
    _print(engine.stats(args.namespace))
    return 0


def cmd_maintain(args: argparse.Namespace) -> int:
    engine = _build_engine(args)
    _print(engine.maintain(dry_run=args.dry_run, namespace=args.namespace))
    return 0


def cmd_sync(args: argparse.Namespace) -> int:
    engine = _build_engine(args)
    _print(engine.sync_native(namespace=args.namespace))
    return 0


def cmd_export(args: argparse.Namespace) -> int:
    engine = _build_engine(args)
    _print(engine.export(path=args.out or "", namespace=args.namespace))
    return 0


def cmd_import(args: argparse.Namespace) -> int:
    engine = _build_engine(args)
    _print(engine.import_data(args.input, namespace=args.namespace))
    return 0


def cmd_health(args: argparse.Namespace) -> int:
    engine = _build_engine(args)
    _print(engine.health())
    return 0


def build_parser() -> argparse.ArgumentParser:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--config", help="JSON 配置文件路径")

    parser = argparse.ArgumentParser(
        prog="minis-memory",
        description="Minis Memory Ultra：SQLite + 混合召回 + MCP 的长期记忆引擎",
    )
    sub = parser.add_subparsers(dest="command")

    serve = sub.add_parser("serve", parents=[common], help="启动 stdio 或 HTTP 服务")
    serve.add_argument("--stdio", action="store_true", help="stdio 模式（默认）")
    serve.add_argument("--http", action="store_true", help="HTTP/面板模式")
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=18903)
    serve.set_defaults(func=cmd_serve)

    stdio = sub.add_parser("stdio", parents=[common], help="MCP stdio 模式")
    stdio.set_defaults(func=cmd_serve, http=False, host="127.0.0.1", port=18903)

    remember = sub.add_parser("remember", parents=[common], help="写入一条记忆")
    remember.add_argument("text")
    remember.add_argument("--kind", default="fact")
    remember.add_argument("--importance", type=float, default=None)
    remember.add_argument("--source", default="cli")
    remember.add_argument("--pinned", action="store_true")
    remember.add_argument("--namespace", default=None)
    remember.set_defaults(func=cmd_remember)

    recall = sub.add_parser("recall", parents=[common], help="召回相关记忆")
    recall.add_argument("query")
    recall.add_argument("--k", type=int, default=8)
    recall.add_argument("--budget", type=int, default=1400)
    recall.add_argument("--kind", default=None)
    recall.add_argument("--namespace", default=None)
    recall.add_argument("--explain", action="store_true")
    recall.add_argument("--json", action="store_true")
    recall.set_defaults(func=cmd_recall)

    profile = sub.add_parser("profile", parents=[common], help="输出记忆画像")
    profile.add_argument("--namespace", default=None)
    profile.add_argument("--max-chars", type=int, default=1200)
    profile.set_defaults(func=cmd_profile)

    stats = sub.add_parser("stats", parents=[common], help="输出统计")
    stats.add_argument("--namespace", default=None)
    stats.set_defaults(func=cmd_stats)

    maintain = sub.add_parser("maintain", parents=[common], help="衰减、合并、清理")
    maintain.add_argument("--dry-run", action="store_true")
    maintain.add_argument("--namespace", default=None)
    maintain.set_defaults(func=cmd_maintain)

    sync = sub.add_parser("sync", parents=[common], help="同步原生 Markdown 记忆")
    sync.add_argument("--namespace", default=None)
    sync.set_defaults(func=cmd_sync)

    export = sub.add_parser("export", parents=[common], help="导出 JSON")
    export.add_argument("--out", default="")
    export.add_argument("--namespace", default=None)
    export.set_defaults(func=cmd_export)

    import_cmd = sub.add_parser("import", parents=[common], help="导入 JSON")
    import_cmd.add_argument("input")
    import_cmd.add_argument("--namespace", default=None)
    import_cmd.set_defaults(func=cmd_import)

    health = sub.add_parser("health", parents=[common], help="健康检查")
    health.set_defaults(func=cmd_health)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if not getattr(args, "command", None):
        args.command = "stdio"
        args.config = None
        args.http = False
        args.host = "127.0.0.1"
        args.port = 18903
        return cmd_serve(args)
    if not hasattr(args, "func"):
        parser.print_help()
        return 2
    return int(args.func(args) or 0)


if __name__ == "__main__":
    sys.exit(main())

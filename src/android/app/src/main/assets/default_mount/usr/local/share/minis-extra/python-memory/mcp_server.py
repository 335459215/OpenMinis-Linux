#!/usr/bin/env python3
"""MCP stdio entry point for Minis Memory Ultra.

Side responsibility: every time the MCP server is spawned (i.e. once per
session), make sure the local HTTP hook service on 127.0.0.1:18903 is
alive. The APK's HookClient talks to that port for automatic recall;
without this guard the service dies with the spawning shell and the
hook silently no-ops until someone notices.

The ensure step is deliberately cheap and never blocks entry: a 0.3s
health probe, then a detached spawn. Any failure is swallowed — MCP
stdio must still come up even if the hook cannot.
"""

from __future__ import annotations

import os
import subprocess
import sys
import urllib.request

CURRENT = os.path.dirname(os.path.abspath(__file__))
if CURRENT not in sys.path:
    sys.path.insert(0, CURRENT)

HOST = "127.0.0.1"
PORT = int(os.environ.get("MINIS_MEMORY_PORT", "18903"))
HEALTH_URL = f"http://{HOST}:{PORT}/health"
PID_FILE = os.path.join(CURRENT, "data", "minis-memory.pid")
LOG_FILE = os.path.join(CURRENT, "data", "minis-memory.log")


def _healthy(timeout: float = 0.3) -> bool:
    try:
        with urllib.request.urlopen(HEALTH_URL, timeout=timeout) as resp:
            return resp.status == 200
    except Exception:
        return False


def _alive_pid() -> bool:
    """A listening socket is not proof of health; check the pid too."""
    try:
        with open(PID_FILE, encoding="utf-8") as fh:
            pid = int(fh.read().strip())
        os.kill(pid, 0)
        return True
    except Exception:
        return False


def ensure_http() -> None:
    if _healthy() or _alive_pid():
        return
    lock_path = os.path.join(CURRENT, "data", "http.lock")
    try:
        os.makedirs(os.path.dirname(lock_path), exist_ok=True)
        lock = open(lock_path, "a+")
        import fcntl
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except Exception:
        return  # 别的进程正在拉起，交给它
    try:
        if _healthy() or _alive_pid():
            return
        with open(LOG_FILE, "ab") as log:
            proc = subprocess.Popen(
                [
                    sys.executable,
                    os.path.join(CURRENT, "mcp_server.py"),
                    "serve",
                    "--http",
                    "--host",
                    HOST,
                    "--port",
                    str(PORT),
                ],
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=log,
                start_new_session=True,  # detach from the spawning shell
                close_fds=True,
                cwd=CURRENT,
            )
        # 等端口真正起来再落 PID，避免把已崩的进程写进文件
        for _ in range(40):
            if _healthy(0.2):
                with open(PID_FILE, "w", encoding="utf-8") as fh:
                    fh.write(str(proc.pid))
                return
            if proc.poll() is not None:
                return
            import time as _t
            _t.sleep(0.05)
    except Exception:
        pass  # never let the hook break MCP stdio
    finally:
        try:
            lock.close()
        except Exception:
            pass


if __name__ == "__main__":
    ensure_http()
    from minis_memory.cli import main  # noqa: E402

    sys.exit(main(sys.argv[1:]))

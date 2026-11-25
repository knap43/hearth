"""Running games: process groups, log files and playtime."""

from __future__ import annotations

import os
import re
import signal
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path

from .config import LOG_DIR
from .runners import LaunchSpec


def log_path(key: str) -> Path:
    return LOG_DIR / (re.sub(r"[^A-Za-z0-9._-]+", "_", key).strip("_") + ".log")


@dataclass
class Session:
    key: str
    proc: subprocess.Popen
    started: float
    log: Path
    stop_requested: float = 0.0

    @property
    def elapsed(self) -> float:
        return time.time() - self.started


def start(key: str, spec: LaunchSpec, detach: bool = True) -> Session:
    """Start a game in its own session, output going to its log file."""
    log = log_path(key)
    log.parent.mkdir(parents=True, exist_ok=True)
    fh = open(log, "w", encoding="utf-8", buffering=1)
    fh.write(f"# {time.strftime('%Y-%m-%d %H:%M:%S')}  runner: {spec.runner}\n")
    if spec.note:
        fh.write(f"# {spec.note}\n")
    fh.write(f"# cwd: {spec.cwd}\n# {spec.describe()}\n\n")
    try:
        proc = subprocess.Popen(
            spec.argv,
            env=spec.env,
            cwd=spec.cwd,
            stdin=subprocess.DEVNULL,
            stdout=fh if detach else None,
            stderr=subprocess.STDOUT if detach else None,
            start_new_session=detach,
        )
    except OSError as exc:
        fh.write(f"Failed to start: {exc}\n")
        fh.close()
        raise
    fh.close()  # the child holds its own descriptor
    return Session(key, proc, time.time(), log)


def stop(session: Session) -> None:
    """SIGTERM the game's process group; a second request within 10 s sends SIGKILL."""
    now = time.time()
    sig = signal.SIGKILL if session.stop_requested and now - session.stop_requested < 10 else signal.SIGTERM
    session.stop_requested = now
    try:
        os.killpg(session.proc.pid, sig)
    except ProcessLookupError:
        pass


def tail(path: Path, lines: int = 400) -> list[str]:
    try:
        with open(path, "rb") as fh:
            fh.seek(0, os.SEEK_END)
            size = fh.tell()
            fh.seek(max(0, size - 256 * 1024))
            data = fh.read().decode("utf-8", errors="replace")
    except OSError:
        return []
    return data.splitlines()[-lines:]


def format_duration(seconds: float) -> str:
    seconds = int(seconds)
    if seconds < 60:
        return f"{seconds}s" if seconds else "—"
    hours, rem = divmod(seconds, 3600)
    return f"{hours}h {rem // 60:02d}m" if hours else f"{rem // 60}m"


def format_when(timestamp: float) -> str:
    if not timestamp:
        return "never"
    delta = time.time() - timestamp
    if delta < 3600:
        return "just now" if delta < 120 else f"{int(delta // 60)} min ago"
    if delta < 86400:
        return f"{int(delta // 3600)} h ago"
    if delta < 86400 * 30:
        days = int(delta // 86400)
        return "yesterday" if days == 1 else f"{days} days ago"
    return time.strftime("%Y-%m-%d", time.localtime(timestamp))

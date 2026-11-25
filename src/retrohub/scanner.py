"""Finding games in the library and guessing which executable starts each one.

Layout: <root>/<windows_dir>/... and <root>/<linux_dir>/... ; console ROMs are
found by scan_roms in a folder per system (see emulators.py). Beneath each, a folder
that holds only folders is a *category* and is descended into (up to max_depth);
a folder holding files, or the usual game subfolders (bin, Binaries, *_Data, ...),
is a *game*. Marker files override the guess:

    .retrohub-game      treat this folder as a game
    .retrohub-category  treat this folder as a category
    .retrohub-ignore    skip this folder entirely
"""

from __future__ import annotations

import math
import os
import re
import struct
from dataclasses import dataclass, field
from pathlib import Path

WINDOWS = "windows"
LINUX = "linux"
PLATFORMS = (WINDOWS, LINUX)

GAME_MARK = ".retrohub-game"
CATEGORY_MARK = ".retrohub-category"
IGNORE_MARK = ".retrohub-ignore"

TRIVIAL_FILES = {"desktop.ini", "thumbs.db", ".directory", ".ds_store", "folder.jpg", "folder.png"}
GAME_SUBDIRS = {
    "bin", "bin32", "bin64", "binaries", "x64", "x86", "x86_64", "win32", "win64",
    "game", "data", "content", "engine", "lib", "lib64", "redist", "_commonredist",
}

# Folders never searched for executables.
SKIP_DIRS = {
    "_commonredist", "commonredist", "redist", "_redist", "__redist", "redistributables",
    "directx", "dotnet", "dotnetfx", "vcredist", "__installer", "installer", "installers",
    "prerequisites", "prereqs", "__support", "support", "easyanticheat", "battleye",
    "crashreportclient", "crashpad", "__pycache__", "docs", "manual", "soundtrack", "ost",
}

# Executable names that are almost never the game itself.
BAD_NAMES = re.compile(
    r"unins|uninst|setup|install|redist|vcredist|dxsetup|dxwebsetup|directx|crash|report"
    r"|dotnet|ndp\d|prereq|easyanticheat|eac_|battleye|beservice|helper|updat|patch"
    r"|sandbox|cefsharp|webhelper|register|activat|oalinst|physx|touchup|quicksfv"
    r"|notification|dumper|debug|errorhandler|unitycrash|vc_?redist|xnafx|steamworks"
    r"|crs-|handler|dxdiag|cleanup|repair",
    re.I,
)
MEH_NAMES = {  # sometimes right, usually not: mild penalties
    "launcher": 15, "config": 25, "settings": 25, "server": 30, "editor": 30,
    "benchmark": 20, "tool": 20, "dedicated": 30, "modmanager": 30,
}
LINUX_SUFFIXES = {".sh", ".x86_64", ".x86", ".appimage", ".x64", ".run"}
MAX_EXE_DEPTH = 5
MAX_ENTRIES = 40_000  # per game, to stay quick on enormous installs


@dataclass
class Game:
    key: str              # "<platform>/<path under the platform folder>"
    platform: str
    path: Path
    folder: str
    category: str         # e.g. "RPG" or "RPG / Classics"; "" when uncategorised
    candidates: list[Path] = field(default_factory=list)  # best first


def _visible_entries(path: Path) -> list[os.DirEntry]:
    try:
        with os.scandir(path) as it:
            return [e for e in it if not e.name.startswith(".")]
    except OSError:
        return []


def _has(path: Path, name: str) -> bool:
    return (path / name).exists()


def is_game_dir(path: Path) -> bool:
    if _has(path, GAME_MARK):
        return True
    if _has(path, CATEGORY_MARK):
        return False
    for entry in _visible_entries(path):
        try:
            if entry.is_file():
                if entry.name.lower() not in TRIVIAL_FILES:
                    return True
            elif entry.is_dir():
                low = entry.name.lower()
                if low in GAME_SUBDIRS or low.endswith("_data"):
                    return True
        except OSError:
            continue
    return False


def scan_platform(base: Path, platform: str, max_depth: int = 4) -> list[Game]:
    games: list[Game] = []

    def walk(directory: Path, parts: list[str], depth: int) -> None:
        for entry in sorted(_visible_entries(directory), key=lambda e: e.name.lower()):
            try:
                if not entry.is_dir():
                    continue
            except OSError:
                continue
            child = Path(entry.path)
            if _has(child, IGNORE_MARK):
                continue
            if is_game_dir(child):
                rel = "/".join([*parts, entry.name])
                games.append(Game(
                    key=f"{platform}/{rel}",
                    platform=platform,
                    path=child,
                    folder=entry.name,
                    category=" / ".join(parts),
                    candidates=find_executables(child, platform),
                ))
            elif depth < max_depth:
                walk(child, [*parts, entry.name], depth + 1)

    if base.is_dir():
        walk(base, [], 1)
    return games


# --- executables -----------------------------------------------------------------

def elf_kind(path: Path) -> str | None:
    """"exec" for a runnable ELF program, "lib" for a shared library, None if not ELF."""
    try:
        with open(path, "rb") as fh:
            head = fh.read(64)
            if len(head) < 52 or head[:4] != b"\x7fELF":
                return None
            is64 = head[4] == 2
            endian = "<" if head[5] == 1 else ">"
            (e_type,) = struct.unpack_from(endian + "H", head, 16)
            if e_type == 2:          # ET_EXEC
                return "exec"
            if e_type != 3:          # not ET_DYN either: object file, core dump...
                return "lib"
            # ET_DYN is both PIE programs and libraries; only programs ask for an interpreter.
            if is64:
                phoff, = struct.unpack_from(endian + "Q", head, 0x20)
                phentsize, phnum = struct.unpack_from(endian + "HH", head, 0x36)
            else:
                phoff, = struct.unpack_from(endian + "I", head, 0x1C)
                phentsize, phnum = struct.unpack_from(endian + "HH", head, 0x2A)
            for i in range(min(phnum, 64)):
                fh.seek(phoff + i * phentsize)
                raw = fh.read(4)
                if len(raw) == 4 and struct.unpack(endian + "I", raw)[0] == 3:  # PT_INTERP
                    return "exec"
            return "lib"
    except (OSError, struct.error):
        return None


def _is_linux_candidate(entry: os.DirEntry) -> bool:
    name = entry.name.lower()
    if ".so" in name or name.endswith((".dll", ".exe", ".txt", ".json", ".py", ".pyc")):
        return False
    suffix = os.path.splitext(name)[1]
    if suffix in LINUX_SUFFIXES:
        return suffix != ".x86_64" or elf_kind(Path(entry.path)) != "lib"
    try:
        if not os.access(entry.path, os.X_OK):
            return False
        with open(entry.path, "rb") as fh:
            magic = fh.read(2)
    except OSError:
        return False
    if magic == b"#!":
        return True
    return elf_kind(Path(entry.path)) == "exec"


def _normalize(text: str) -> str:
    return re.sub(r"[^a-z0-9]", "", text.lower())


def _tokens(text: str) -> set[str]:
    spaced = re.sub(r"([a-z])([A-Z])", r"\1 \2", text)
    return {t for t in re.split(r"[^a-z0-9]+", spaced.lower()) if len(t) > 1}


def score_executable(path: Path, game_dir: Path, game_name: str, platform: str) -> float:
    rel = path.relative_to(game_dir)
    depth = len(rel.parts) - 1
    stem = path.stem if path.suffix.lower() in {".exe", ".sh", ".x86_64", ".x86", ".appimage"} else path.name
    score = 0.0

    if BAD_NAMES.search(stem):
        score -= 100
    low = stem.lower()
    for word, penalty in MEH_NAMES.items():
        if word in low:
            score -= penalty

    game_norm, stem_norm = _normalize(game_name), _normalize(stem)
    if stem_norm and stem_norm == game_norm:
        score += 60
    elif stem_norm and game_norm and (stem_norm in game_norm or game_norm in stem_norm):
        score += 40
    else:
        score += 12 * len(_tokens(game_name) & _tokens(stem))

    score -= 8 * depth
    dirs = {p.lower() for p in rel.parts[:-1]}
    try:
        size_mb = path.stat().st_size / 1_048_576
    except OSError:
        size_mb = 0
    score += min(math.log2(size_mb + 1) * 4, 30)

    if platform == WINDOWS:
        if dirs & {"win64", "x64", "bin64", "binaries", "bin"}:
            score += 10
        if dirs & {"win32", "x86", "bin32"}:
            score -= 3
    else:
        name = path.name.lower()
        if name == "start.sh":          # GOG
            score += 50
        elif name.endswith(".sh"):
            score += 10
        if name.endswith((".x86_64", ".x64")) or "64" in name:
            score += 8
        if dirs & {"lib", "lib64", "lib32", "libs"}:
            score -= 40
    return score


def find_executables(game_dir: Path, platform: str, game_name: str | None = None) -> list[Path]:
    name = game_name or game_dir.name
    found: list[Path] = []
    seen = 0
    root_depth = len(game_dir.parts)
    for dirpath, dirnames, filenames in os.walk(game_dir):
        depth = len(Path(dirpath).parts) - root_depth
        dirnames[:] = [
            d for d in dirnames
            if not d.startswith(".") and d.lower() not in SKIP_DIRS and depth < MAX_EXE_DEPTH
        ]
        seen += len(filenames)
        if platform == WINDOWS:
            found += [Path(dirpath, f) for f in filenames if f.lower().endswith(".exe")]
        else:
            try:
                with os.scandir(dirpath) as it:
                    found += [Path(e.path) for e in it if e.is_file() and _is_linux_candidate(e)]
            except OSError:
                pass
        if seen > MAX_ENTRIES:
            break
    found.sort(key=lambda p: (-score_executable(p, game_dir, name, platform), str(p)))
    return found


def scan_roms(base: Path, system: str, max_depth: int = 4) -> list[Game]:
    """Console games: every ROM file under the system's folder (see emulators.py)."""
    from .emulators import SYSTEMS, display_name, roms_in

    spec = SYSTEMS[system]
    games: list[Game] = []

    def walk(directory: Path, parts: list[str], depth: int) -> None:
        for rom in roms_in(directory, spec):
            rel = "/".join([*parts, rom.name])
            games.append(Game(key=f"{system}/{rel}", platform=system, path=rom,
                              folder=display_name(rom), category=" / ".join(parts), candidates=[rom]))
        if depth >= max_depth:
            return
        for entry in sorted(_visible_entries(directory), key=lambda e: e.name.lower()):
            try:
                if entry.is_dir() and not _has(Path(entry.path), IGNORE_MARK):
                    walk(Path(entry.path), [*parts, entry.name], depth + 1)
            except OSError:
                continue

    if base.is_dir():
        walk(base, [], 0)
    return games


def scan_library(cfg) -> list[Game]:
    from .emulators import SYSTEMS

    games: list[Game] = []
    for platform in PLATFORMS:
        games += scan_platform(cfg.platform_dir(platform), platform, cfg.library.max_depth)
    for system in SYSTEMS:
        games += scan_roms(cfg.platform_dir(system), system, cfg.library.max_depth)
    return games

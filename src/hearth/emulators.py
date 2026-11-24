"""Console games: ROM files in a folder per system, each run by its emulator.

Layout: <root>/<system folder>/<rom>, e.g. ~/Games/PS1/Crash Bandicoot.chd. Every ROM
file is a game; sub-folders are categories, as for PC games. Files that belong to
another file are folded into it: the .bin tracks named in a .cue, and the discs (and
their tracks) listed in an .m3u playlist, which is then one multi-disc game.
"""

from __future__ import annotations

import os
import re
import shlex
import shutil
from dataclasses import dataclass
from pathlib import Path

ROM = "%rom%"


@dataclass(frozen=True)
class System:
    id: str
    label: str           # short, for the table: "PS1"
    title: str           # "PlayStation"
    emulator: str        # "DuckStation"
    folder: str          # default folder under the library root
    command: str         # default command line; %rom% is replaced by the ROM's path
    extensions: frozenset[str]


SYSTEMS: dict[str, System] = {s.id: s for s in (
    System("ps1", "PS1", "PlayStation", "DuckStation", "PS1",
           f"duckstation -batch -fullscreen -- {ROM}",
           frozenset({".cue", ".chd", ".pbp", ".m3u", ".iso", ".img", ".bin", ".ecm", ".mds"})),
    System("ps2", "PS2", "PlayStation 2", "PCSX2", "PS2",
           f"pcsx2 -batch -nogui -fullscreen -- {ROM}",
           frozenset({".iso", ".chd", ".cso", ".zso", ".gz", ".bin", ".cue", ".img", ".mdf", ".nrg", ".m3u", ".elf"})),
    System("n64", "N64", "Nintendo 64", "Mupen64Plus", "N64",
           f"mupen64plus --fullscreen {ROM}",
           frozenset({".z64", ".n64", ".v64", ".zip"})),
    System("gamecube", "GameCube", "GameCube", "Dolphin", "GameCube",
           f"dolphin-emu -b -C Dolphin.Display.Fullscreen=True -e {ROM}",
           frozenset({".iso", ".gcm", ".gcz", ".rvz", ".ciso", ".wia", ".dol", ".elf", ".m3u"})),
    System("wii", "Wii", "Wii", "Dolphin", "Wii",
           f"dolphin-emu -b -C Dolphin.Display.Fullscreen=True -e {ROM}",
           frozenset({".iso", ".wbfs", ".rvz", ".wia", ".gcz", ".ciso", ".wad", ".dol", ".elf"})),
)}

_CUE_FILE = re.compile(r'^\s*FILE\s+(?:"([^"]+)"|(\S+))', re.I)


def _read_lines(path: Path) -> list[str]:
    try:
        return path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []


def cue_tracks(cue: Path) -> set[Path]:
    """The files a .cue sheet names (usually its .bin tracks)."""
    found = set()
    for line in _read_lines(cue):
        m = _CUE_FILE.match(line)
        if m:
            found.add((cue.parent / (m.group(1) or m.group(2))).resolve())
    return found


def m3u_entries(m3u: Path) -> set[Path]:
    """The discs an .m3u lists, with the tracks of any .cue among them."""
    found = set()
    for line in _read_lines(m3u):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        disc = (m3u.parent / line).resolve()
        found.add(disc)
        if disc.suffix.lower() == ".cue":
            found |= cue_tracks(disc)
    return found


def roms_in(directory: Path, system: System) -> list[Path]:
    """The games among the files of one folder: ROMs that no .cue or .m3u claims."""
    try:
        with os.scandir(directory) as it:
            files = [Path(e.path) for e in it
                     if not e.name.startswith(".") and e.is_file() and Path(e.name).suffix.lower() in system.extensions]
    except OSError:
        return []
    claimed: set[Path] = set()
    for f in files:
        suffix = f.suffix.lower()
        if suffix == ".m3u":
            claimed |= m3u_entries(f)
        elif suffix == ".cue":
            claimed |= cue_tracks(f)
    return sorted((f for f in files if f.resolve() not in claimed), key=lambda p: p.name.lower())


def display_name(rom: Path) -> str:
    return rom.stem


def find_emulator(command: str) -> str | None:
    try:
        tokens = shlex.split(command)
    except ValueError:
        return None
    return shutil.which(tokens[0]) if tokens else None


def command_argv(command: str, rom: Path) -> list[str]:
    """The command's words with %rom% replaced; the ROM goes last when %rom% is missing."""
    tokens = shlex.split(command)
    if ROM in tokens:
        return [str(rom) if t == ROM else t for t in tokens]
    return [*tokens, str(rom)]

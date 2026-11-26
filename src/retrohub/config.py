"""Settings (config.toml) and per-game state (games.json), stored in XDG locations."""

from __future__ import annotations

import json
import os
import tomllib
from dataclasses import asdict, dataclass, field, fields, make_dataclass
from pathlib import Path

from .emulators import SYSTEMS


def _xdg(var: str, fallback: str) -> Path:
    value = os.environ.get(var)
    return Path(value) if value else Path.home() / fallback


CONFIG_DIR = _xdg("XDG_CONFIG_HOME", ".config") / "retrohub"
DATA_DIR = _xdg("XDG_DATA_HOME", ".local/share") / "retrohub"
STATE_DIR = _xdg("XDG_STATE_HOME", ".local/state") / "retrohub"
CONFIG_FILE = CONFIG_DIR / "config.toml"
GAMES_FILE = DATA_DIR / "games.json"
LOG_DIR = STATE_DIR / "logs"


def expand(path: str) -> Path:
    return Path(os.path.expandvars(path)).expanduser()


@dataclass
class Library:
    root: str = "~/Games"
    # Folder names under root; absolute paths are honoured as-is.
    windows_dir: str = "Windows"
    linux_dir: str = "Linux"
    # How many levels of category folders to descend through looking for games.
    max_depth: int = 4


@dataclass
class Proton:
    # "" lets umu pick UMU-Proton (downloaded on demand); "GE-Proton" means the latest
    # GE-Proton (also downloaded); anything else is the path of an installed build.
    build: str = ""
    prefix: str = str(DATA_DIR / "prefixes" / "proton")
    game_id: str = "umu-default"


@dataclass
class Wine:
    binary: str = "wine"
    # Kept apart from the Proton prefix: mixing Wine versions in one prefix
    # makes each of them "upgrade" it in turn.
    prefix: str = str(DATA_DIR / "prefixes" / "wine")


@dataclass
class Launch:
    # Applied to every game, e.g. "gamemoderun %command%".
    default_options: str = ""


# Per console: <id>_dir, its ROM folder under the root (or an absolute path), and
# <id>, the emulator's command line with %rom% where the ROM goes.
Emulators = make_dataclass("Emulators", [
    item for s in SYSTEMS.values()
    for item in ((f"{s.id}_dir", str, field(default=s.folder)), (s.id, str, field(default=s.command)))
])


@dataclass
class Interface:
    # Use the terminal's own colours (its 16-colour palette and background), so
    # RetroHub matches the terminal's theme; false: Textual's built-in dark theme.
    terminal_colours: bool = True


@dataclass
class Config:
    library: Library = field(default_factory=Library)
    proton: Proton = field(default_factory=Proton)
    wine: Wine = field(default_factory=Wine)
    launch: Launch = field(default_factory=Launch)
    emulators: Emulators = field(default_factory=Emulators)
    interface: Interface = field(default_factory=Interface)

    def platform_dir(self, platform: str) -> Path:
        if platform in SYSTEMS:
            name = getattr(self.emulators, f"{platform}_dir")
        else:
            name = self.library.windows_dir if platform == "windows" else self.library.linux_dir
        return expand(self.library.root) / expand(name)


def _toml_value(value) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    return json.dumps(str(value), ensure_ascii=False)  # JSON strings are valid TOML strings


def load_config(path: Path = CONFIG_FILE) -> Config:
    cfg = Config()
    if not path.is_file():
        save_config(cfg, path)
        return cfg
    with open(path, "rb") as fh:
        raw = tomllib.load(fh)
    for section in fields(cfg):
        target = getattr(cfg, section.name)
        for key, value in (raw.get(section.name) or {}).items():
            if hasattr(target, key) and isinstance(value, type(getattr(target, key))):
                setattr(target, key, value)
    return cfg


def save_config(cfg: Config, path: Path = CONFIG_FILE) -> None:
    lines = ["# RetroHub game launcher settings", ""]
    for section in fields(cfg):
        lines.append(f"[{section.name}]")
        for key, value in asdict(getattr(cfg, section.name)).items():
            lines.append(f"{key} = {_toml_value(value)}")
        lines.append("")
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text("\n".join(lines), encoding="utf-8")
    tmp.replace(path)


@dataclass
class GameState:
    name: str = ""          # display name override
    exe: str = ""           # chosen executable, relative to the game folder (or absolute)
    runner: str = "auto"    # Windows games: auto | proton | wine
    options: str = ""       # Steam-style launch options
    command: str = ""       # console games: this game's emulator command, instead of the system's
    game_id: str = ""       # umu GAMEID override, for protonfixes
    playtime: int = 0       # seconds
    last_played: float = 0  # unix time


class GameStore:
    """Remembered choices and statistics, keyed by "<platform>/<path under platform dir>"."""

    def __init__(self, path: Path = GAMES_FILE):
        self.path = path
        self._games: dict[str, GameState] = {}
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            raw = {}
        known = {f.name for f in fields(GameState)}
        for key, data in raw.items():
            if isinstance(data, dict):
                self._games[key] = GameState(**{k: v for k, v in data.items() if k in known})

    def get(self, key: str) -> GameState:
        return self._games.setdefault(key, GameState())

    def save(self) -> None:
        defaults = asdict(GameState())
        out = {}
        for key, state in sorted(self._games.items()):
            data = {k: v for k, v in asdict(state).items() if v != defaults[k]}
            if data:
                out[key] = data
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(out, indent=2, ensure_ascii=False), encoding="utf-8")
        tmp.replace(self.path)

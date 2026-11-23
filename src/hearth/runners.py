"""Turning a game into a command line: Proton via umu-launcher, Wine, or native.

umu-launcher runs Proton inside Steam's runtime container without Steam. It reads:
    WINEPREFIX  the prefix (created on first run)
    GAMEID      umu database id ("umu-default", or e.g. "umu-1245620" to get protonfixes)
    PROTONPATH  a Proton build folder, or "GE-Proton" for the latest GE build;
                unset means UMU-Proton. umu downloads builds it does not have.
"""

from __future__ import annotations

import os
import shlex
import shutil
import stat
from dataclasses import dataclass
from pathlib import Path

from .config import Config, GameState, expand
from .scanner import WINDOWS, Game

RUNNERS = ("auto", "proton", "wine")
RUNNER_LABELS = {
    "auto": "Auto (Proton, Wine if umu is missing)",
    "proton": "Proton (umu-launcher)",
    "wine": "Wine",
}


class LaunchError(Exception):
    pass


@dataclass
class LaunchSpec:
    argv: list[str]
    env: dict[str, str]
    cwd: str
    runner: str           # "proton", "wine" or "native"
    note: str = ""        # e.g. why Wine was used instead of Proton

    def describe(self) -> str:
        keys = ("WINEPREFIX", "GAMEID", "PROTONPATH")
        env = " ".join(f"{k}={shlex.quote(self.env[k])}" for k in keys if self.env.get(k))
        return f"{env} {shlex.join(self.argv)}".strip()


def find_umu() -> str | None:
    return shutil.which("umu-run")


# --- launch options ----------------------------------------------------------------

def _is_assignment(token: str) -> bool:
    name, eq, _ = token.partition("=")
    return bool(eq) and name.replace("_", "a").isalnum() and not name[0].isdigit()


@dataclass
class Options:
    env: dict[str, str]
    wrapper: list[str]
    args: list[str]


def parse_options(text: str) -> Options:
    """Steam's syntax: ``VAR=1 wrapper --flag %command% -game-args``.

    Without %command%, leading VAR=value pairs set the environment and the rest are
    arguments for the game.
    """
    try:
        tokens = shlex.split(text or "")
    except ValueError as exc:
        raise LaunchError(f"Cannot parse launch options {text!r}: {exc}") from exc
    if "%command%" in tokens:
        i = tokens.index("%command%")
        before, args = tokens[:i], tokens[i + 1:]
    else:
        before, args = [], tokens
        while args and _is_assignment(args[0]):
            before.append(args.pop(0))
    env: dict[str, str] = {}
    wrapper: list[str] = []
    for token in before:
        if not wrapper and _is_assignment(token):
            key, _, value = token.partition("=")
            env[key] = value
        else:
            wrapper.append(token)
    return Options(env, wrapper, args)


def combine_options(default: str, game: str) -> Options:
    """Default options wrap the game's: env merged (game wins), wrappers chained, args appended."""
    a, b = parse_options(default), parse_options(game)
    return Options({**a.env, **b.env}, [*a.wrapper, *b.wrapper], [*a.args, *b.args])


# --- Proton builds -------------------------------------------------------------------

def compat_dirs() -> list[Path]:
    home = Path.home()
    data = Path(os.environ.get("XDG_DATA_HOME", home / ".local/share"))
    return [
        data / "Steam" / "compatibilitytools.d",
        home / ".steam" / "root" / "compatibilitytools.d",
        home / ".steam" / "steam" / "compatibilitytools.d",
        Path("/usr/share/steam/compatibilitytools.d"),
        data / "umu" / "compatibilitytools",
        data / "Steam" / "steamapps" / "common",
    ]


def installed_protons() -> list[Path]:
    """Installed Proton builds, newest-looking names first, without duplicates."""
    seen: set[Path] = set()
    builds: list[Path] = []
    for base in compat_dirs():
        try:
            children = list(base.iterdir())
        except OSError:
            continue
        for child in children:
            if not (child / "proton").is_file():
                continue
            real = child.resolve()
            if real in seen:
                continue
            seen.add(real)
            builds.append(child)
    builds.sort(key=lambda p: p.name.lower(), reverse=True)
    return builds


def proton_label(build: str) -> str:
    if not build:
        return "UMU-Proton (umu default)"
    if build == "GE-Proton":
        return "GE-Proton (latest)"
    return Path(build).name


# --- building the command ------------------------------------------------------------

def resolve_exe(game: Game, state: GameState) -> Path:
    if state.exe:
        exe = expand(state.exe)
        exe = exe if exe.is_absolute() else game.path / exe
        if not exe.is_file():
            raise LaunchError(f"The chosen executable no longer exists: {exe}")
        return exe
    if not game.candidates:
        kind = ".exe" if game.platform == WINDOWS else "executable"
        raise LaunchError(f"No {kind} found in {game.path}. Choose one with 'e'.")
    return game.candidates[0]


def windows_runner(state: GameState) -> tuple[str, str]:
    """The runner a Windows game will use, and a note when it differs from the wish."""
    wish = state.runner if state.runner in RUNNERS else "auto"
    if wish == "wine":
        return "wine", ""
    if find_umu():
        return "proton", ""
    if wish == "proton":
        raise LaunchError(
            "umu-run was not found. Install umu-launcher (sudo pacman -S umu-launcher) "
            "or set this game's runner to Wine."
        )
    return "wine", "umu-launcher is not installed, so Wine is used instead of Proton."


def proton_env(cfg: Config, state: GameState | None = None) -> dict[str, str]:
    prefix = expand(cfg.proton.prefix)
    prefix.mkdir(parents=True, exist_ok=True)
    env = {"WINEPREFIX": str(prefix), "GAMEID": (state and state.game_id) or cfg.proton.game_id or "umu-default"}
    if cfg.proton.build:
        env["PROTONPATH"] = str(expand(cfg.proton.build)) if "/" in cfg.proton.build else cfg.proton.build
    return env


def wine_cmd(cfg: Config) -> tuple[str, dict[str, str]]:
    wine = shutil.which(str(expand(cfg.wine.binary))) if cfg.wine.binary else None
    if not wine:
        raise LaunchError(f"Wine was not found ({cfg.wine.binary!r}). Install it: sudo pacman -S wine")
    prefix = expand(cfg.wine.prefix)
    prefix.mkdir(parents=True, exist_ok=True)
    return wine, {"WINEPREFIX": str(prefix)}


def _make_executable(path: Path) -> None:
    mode = path.stat().st_mode
    if not mode & stat.S_IXUSR:
        path.chmod(mode | stat.S_IXUSR | (stat.S_IXGRP if mode & stat.S_IRGRP else 0))


def build_launch(game: Game, state: GameState, cfg: Config, base_env=None) -> LaunchSpec:
    exe = resolve_exe(game, state)
    opts = combine_options(cfg.launch.default_options, state.options)
    env = dict(os.environ if base_env is None else base_env)
    cwd = str(exe.parent)

    if game.platform == WINDOWS:
        runner, note = windows_runner(state)
        if runner == "proton":
            env.update(proton_env(cfg, state))
            command = [find_umu(), str(exe)]
        else:
            wine, wine_env = wine_cmd(cfg)
            env.update(wine_env)
            command = [wine, str(exe)]
    else:
        runner, note = "native", ""
        with open(exe, "rb") as fh:
            magic = fh.read(4)
        if exe.suffix.lower() == ".sh" and not os.access(exe, os.X_OK):
            command = ["/bin/sh", str(exe)]
        else:
            if magic == b"\x7fELF" or magic[:2] == b"#!":
                _make_executable(exe)
            command = [str(exe)]

    env.update(opts.env)
    return LaunchSpec([*opts.wrapper, *command, *opts.args], env, cwd, runner, note)


def prefix_tool(tool: str, cfg: Config, runner: str) -> LaunchSpec:
    """winecfg / winetricks inside the shared Proton or Wine prefix."""
    if runner == "proton":
        umu = find_umu()
        if not umu:
            raise LaunchError("umu-run was not found. Install umu-launcher.")
        env = {**os.environ, **proton_env(cfg)}
        return LaunchSpec([umu, tool], env, str(Path.home()), "proton")
    wine, wine_env = wine_cmd(cfg)
    env = {**os.environ, **wine_env}
    if tool == "winetricks":
        winetricks = shutil.which("winetricks")
        if not winetricks:
            raise LaunchError("winetricks is not installed: sudo pacman -S winetricks")
        env["WINE"] = wine
        return LaunchSpec([winetricks], env, str(Path.home()), "wine")
    return LaunchSpec([wine, tool], env, str(Path.home()), "wine")

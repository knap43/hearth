"""Command line: `hearth` opens the TUI; `hearth list` and `hearth run NAME` work without it."""

from __future__ import annotations

import argparse
import sys
import time

from . import __version__, sessions
from .config import CONFIG_FILE, GameStore, load_config
from .runners import LaunchError, build_launch, find_umu
from .scanner import scan_library


def _name(game, store) -> str:
    return store.get(game.key).name or game.folder


def _find(query: str, games, store):
    q = query.lower()
    exact = [g for g in games if q in (_name(g, store).lower(), g.folder.lower(), g.key.lower())]
    if exact:
        return exact
    return [g for g in games if q in _name(g, store).lower() or q in g.key.lower()]


def cmd_list(args) -> int:
    cfg, store = load_config(), GameStore()
    games = sorted(scan_library(cfg), key=lambda g: _name(g, store).lower())
    if args.names:
        for g in games:
            print(_name(g, store))
        return 0
    if not games:
        print(f"No games found under {cfg.platform_dir('windows')} or {cfg.platform_dir('linux')}.")
        return 0
    width = max(len(_name(g, store)) for g in games)
    for g in games:
        state = store.get(g.key)
        exe = state.exe or (str(g.candidates[0].relative_to(g.path)) if g.candidates else "(no executable found)")
        print(f"{_name(g, store):<{width}}  {g.platform:<7}  {sessions.format_duration(state.playtime):>8}  {exe}")
    return 0


def cmd_run(args) -> int:
    cfg, store = load_config(), GameStore()
    matches = _find(" ".join(args.game), scan_library(cfg), store)
    if not matches:
        print("No game matches that name. See `hearth list`.", file=sys.stderr)
        return 1
    if len(matches) > 1:
        print("Several games match:\n  " + "\n  ".join(_name(g, store) for g in matches), file=sys.stderr)
        return 1
    game = matches[0]
    state = store.get(game.key)
    try:
        spec = build_launch(game, state, cfg)
    except LaunchError as exc:
        print(f"hearth: {exc}", file=sys.stderr)
        return 1
    if spec.note:
        print(f"hearth: {spec.note}", file=sys.stderr)
    print(f"hearth: {spec.describe()}", file=sys.stderr)
    state.last_played = time.time()
    store.save()
    session = sessions.start(game.key, spec, detach=not args.wait)
    if not args.wait:
        print(f"hearth: started {_name(game, store)}; log at {session.log}", file=sys.stderr)
        return 0
    try:
        code = session.proc.wait()
    except KeyboardInterrupt:
        code = session.proc.wait()
    state.playtime += int(session.elapsed)
    store.save()
    return code


def cmd_doctor(_args) -> int:
    import shutil
    from .runners import installed_protons, proton_label

    cfg = load_config()
    umu = find_umu()
    print(f"config:      {CONFIG_FILE}")
    for platform in ("windows", "linux"):
        path = cfg.platform_dir(platform)
        print(f"{platform + ':':<12} {path}{'' if path.is_dir() else '  (missing)'}")
    print(f"umu-run:     {umu or 'NOT FOUND - install umu-launcher; Wine will be used instead'}")
    print(f"wine:        {shutil.which(cfg.wine.binary) or 'not found'}")
    print(f"proton:      {proton_label(cfg.proton.build)}")
    print(f"prefix:      {cfg.proton.prefix}")
    builds = installed_protons()
    print("installed:   " + (", ".join(p.name for p in builds) if builds else "none (umu downloads UMU-Proton)"))
    return 0


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(prog="hearth", description=__doc__)
    parser.add_argument("--version", action="version", version=f"hearth {__version__}")
    sub = parser.add_subparsers(dest="command")
    p = sub.add_parser("list", help="list the games in the library")
    p.add_argument("--names", action="store_true", help="names only, one per line")
    p.set_defaults(func=cmd_list)
    p = sub.add_parser("run", help="launch a game by (part of) its name")
    p.add_argument("game", nargs="+")
    p.add_argument("-w", "--wait", action="store_true",
                   help="stay attached: show the game's output here and count playtime")
    p.set_defaults(func=cmd_run)
    p = sub.add_parser("doctor", help="show where Hearth looks and which runners it found")
    p.set_defaults(func=cmd_doctor)
    args = parser.parse_args(argv)
    if not args.command:
        from .tui import run_tui
        run_tui()
        return
    sys.exit(args.func(args))

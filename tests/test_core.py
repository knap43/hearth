import os
import shutil
from pathlib import Path

import pytest

from retrohub import runners
from retrohub.config import Config, GameState, GameStore, load_config, save_config
from retrohub.runners import LaunchError, build_launch, parse_options
from retrohub.scanner import LINUX, WINDOWS, elf_kind, scan_library

ELF_EXEC = Path("/usr/bin/true")
ELF_LIB = Path("/usr/lib/libz.so.1")  # a plain library (libc.so.6 is itself runnable)


def touch(path: Path, size: int = 10, data: bytes | None = None) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data if data is not None else b"MZ" + b"\0" * size)
    return path


@pytest.fixture
def library(tmp_path):
    win, lin = tmp_path / "Win", tmp_path / "Nix"
    # plain game, installer next to it
    touch(win / "Hollow Peak" / "HollowPeak.exe", 5_000_000)
    touch(win / "Hollow Peak" / "unins000.exe", 9_000_000)
    touch(win / "Hollow Peak" / "_CommonRedist" / "vcredist_x64.exe")
    # Unreal-style layout, inside a category
    touch(win / "RPG" / "Ashen Crown" / "AshenCrown.exe", 300_000)
    touch(win / "RPG" / "Ashen Crown" / "AshenCrown" / "Binaries" / "Win64" / "AshenCrown-Win64-Shipping.exe", 80_000_000)
    touch(win / "RPG" / "Ashen Crown" / "Engine" / "Binaries" / "Win64" / "CrashReportClient.exe", 20_000_000)
    # nested categories, game with only subfolders (bin/)
    touch(win / "RPG" / "Classics" / "Old Keep" / "bin" / "x64" / "oldkeep.exe", 2_000_000)
    touch(win / "RPG" / "Classics" / "Old Keep" / "bin" / "x64" / "crashpad_handler.exe", 2_000_000)
    # ignored folder
    touch(win / "Junk" / ".retrohub-ignore", data=b"")
    touch(win / "Junk" / "thing.exe")
    # Linux: GOG layout, Unity layout with a .so, script-less ELF
    touch(lin / "Lantern Isle" / "start.sh", data=b"#!/bin/sh\necho hi\n")
    shutil.copy(ELF_EXEC, touch(lin / "Lantern Isle" / "game" / "LanternIsle.bin"))
    shutil.copy(ELF_EXEC, touch(lin / "Tidewater" / "Tidewater.x86_64"))
    shutil.copy(ELF_LIB, touch(lin / "Tidewater" / "UnityPlayer.so"))
    touch(lin / "Tidewater" / "Tidewater_Data" / "level0", data=b"x")
    shutil.copy(ELF_EXEC, touch(lin / "Indie" / "Bramble" / "bramble"))
    os.chmod(lin / "Indie" / "Bramble" / "bramble", 0o755)
    shutil.copy(ELF_LIB, touch(lin / "Indie" / "Bramble" / "libfoo.so.1"))

    cfg = Config()
    cfg.library.root = str(tmp_path)
    cfg.library.windows_dir = "Win"
    cfg.library.linux_dir = "Nix"
    cfg.proton.prefix = str(tmp_path / "pfx-proton")
    cfg.wine.prefix = str(tmp_path / "pfx-wine")
    return cfg


def by_folder(games):
    return {g.folder: g for g in games}


def test_scan_finds_games_and_categories(library):
    games = by_folder(scan_library(library))
    assert set(games) == {"Hollow Peak", "Ashen Crown", "Old Keep", "Lantern Isle", "Tidewater", "Bramble"}
    assert games["Ashen Crown"].category == "RPG"
    assert games["Old Keep"].category == "RPG / Classics"
    assert games["Bramble"].category == "Indie"
    assert games["Hollow Peak"].key == "windows/Hollow Peak"
    assert games["Tidewater"].platform == LINUX


def test_best_executables(library):
    games = by_folder(scan_library(library))

    def best(name):
        g = games[name]
        return str(g.candidates[0].relative_to(g.path))

    assert best("Hollow Peak") == "HollowPeak.exe"
    assert best("Ashen Crown") == "AshenCrown.exe"
    assert best("Old Keep") == "bin/x64/oldkeep.exe"
    assert best("Lantern Isle") == "start.sh"
    assert best("Tidewater") == "Tidewater.x86_64"
    assert best("Bramble") == "bramble"
    # redistributables are never offered; shared libraries are not programs
    assert not any("vcredist" in str(p) for p in games["Hollow Peak"].candidates)
    assert not any(".so" in p.name for g in games.values() for p in g.candidates)


def test_elf_kind():
    assert elf_kind(ELF_EXEC) == "exec"
    assert elf_kind(ELF_LIB) == "lib"


@pytest.mark.parametrize("text, env, wrapper, args", [
    ("", {}, [], []),
    ("-dx11 -windowed", {}, [], ["-dx11", "-windowed"]),
    ("DXVK_HUD=fps -dx11", {"DXVK_HUD": "fps"}, [], ["-dx11"]),
    ("MANGOHUD=1 gamemoderun %command% -skip", {"MANGOHUD": "1"}, ["gamemoderun"], ["-skip"]),
    ("A=1 B='x y' %command%", {"A": "1", "B": "x y"}, [], []),
])
def test_parse_options(text, env, wrapper, args):
    o = parse_options(text)
    assert (o.env, o.wrapper, o.args) == (env, wrapper, args)


def fake_umu(monkeypatch, present: bool):
    monkeypatch.setattr(runners, "find_umu", lambda: "/usr/bin/umu-run" if present else None)


def test_proton_launch(library, monkeypatch):
    fake_umu(monkeypatch, True)
    library.proton.build = "GE-Proton"
    library.launch.default_options = "gamemoderun %command%"
    game = by_folder(scan_library(library))["Hollow Peak"]
    spec = build_launch(game, GameState(options="DXVK_HUD=1 %command% -dx11"), library, base_env={})
    assert spec.runner == "proton"
    assert spec.argv == ["gamemoderun", "/usr/bin/umu-run", str(game.path / "HollowPeak.exe"), "-dx11"]
    assert spec.env["WINEPREFIX"] == library.proton.prefix
    assert spec.env["GAMEID"] == "umu-default"
    assert spec.env["PROTONPATH"] == "GE-Proton"
    assert spec.env["DXVK_HUD"] == "1"
    assert spec.cwd == str(game.path)


def test_wine_fallback_when_umu_missing(library, monkeypatch):
    fake_umu(monkeypatch, False)
    monkeypatch.setattr(runners.shutil, "which", lambda name: f"/usr/bin/{name}")
    game = by_folder(scan_library(library))["Hollow Peak"]
    spec = build_launch(game, GameState(), library, base_env={})
    assert spec.runner == "wine" and "umu-launcher" in spec.note
    assert spec.argv[0] == "/usr/bin/wine"
    assert spec.env["WINEPREFIX"] == library.wine.prefix
    with pytest.raises(LaunchError):
        build_launch(game, GameState(runner="proton"), library, base_env={})


def test_native_launch_and_chosen_exe(library):
    games = by_folder(scan_library(library))
    spec = build_launch(games["Lantern Isle"], GameState(), library, base_env={})
    assert spec.runner == "native"
    assert spec.argv in (["/bin/sh", str(games["Lantern Isle"].path / "start.sh")],
                         [str(games["Lantern Isle"].path / "start.sh")])
    chosen = build_launch(games["Lantern Isle"], GameState(exe="game/LanternIsle.bin"), library, base_env={})
    assert chosen.argv == [str(games["Lantern Isle"].path / "game" / "LanternIsle.bin")]
    assert os.access(chosen.argv[0], os.X_OK)  # made executable
    with pytest.raises(LaunchError):
        build_launch(games["Lantern Isle"], GameState(exe="gone.sh"), library, base_env={})


def test_config_and_store_roundtrip(tmp_path):
    cfg = Config()
    cfg.library.windows_dir = 'Win "games"'
    cfg.library.max_depth = 2
    cfg.proton.build = "/opt/GE-Proton10-34"
    save_config(cfg, tmp_path / "c.toml")
    loaded = load_config(tmp_path / "c.toml")
    assert loaded == cfg

    store = GameStore(tmp_path / "g.json")
    store.get("windows/A").playtime = 90
    store.get("linux/B")  # untouched entries are not written
    store.save()
    again = GameStore(tmp_path / "g.json")
    assert again.get("windows/A").playtime == 90
    assert "linux/B" not in (tmp_path / "g.json").read_text()


# --- consoles ----------------------------------------------------------------------

@pytest.fixture
def consoles(tmp_path):
    ps1, n64, gc = tmp_path / "PS1", tmp_path / "N64", tmp_path / "GameCube"
    # a .cue with its tracks, a .chd, a multi-disc set behind an .m3u, a stray file
    (ps1 / "Crash.cue").parent.mkdir(parents=True)
    (ps1 / "Crash.cue").write_text('FILE "Crash (Track 1).bin" BINARY\n  TRACK 01 MODE2/2352\n'
                                   'FILE "Crash (Track 2).bin" BINARY\n')
    touch(ps1 / "Crash (Track 1).bin")
    touch(ps1 / "Crash (Track 2).bin")
    touch(ps1 / "Spyro.chd")
    touch(ps1 / "notes.txt")
    fin = ps1 / "RPG" / "Final"
    fin.mkdir(parents=True)
    for d in (1, 2):
        (fin / f"Final (Disc {d}).cue").write_text(f'FILE "Final (Disc {d}).bin" BINARY\n')
        touch(fin / f"Final (Disc {d}).bin")
    (fin / "Final.m3u").write_text("# discs\nFinal (Disc 1).cue\nFinal (Disc 2).cue\n")
    touch(ps1 / "Junk" / ".retrohub-ignore", data=b"")
    touch(ps1 / "Junk" / "Skip.chd")
    touch(n64 / "Mario.z64")
    touch(gc / "Zelda.rvz")

    cfg = Config()
    cfg.library.root = str(tmp_path)
    return cfg


def test_scan_consoles(consoles):
    games = {g.key: g for g in scan_library(consoles)}
    assert set(games) == {"ps1/Crash.cue", "ps1/Spyro.chd", "ps1/RPG/Final/Final.m3u",
                          "n64/Mario.z64", "gamecube/Zelda.rvz"}
    final = games["ps1/RPG/Final/Final.m3u"]
    assert (final.folder, final.category, final.platform) == ("Final", "RPG / Final", "ps1")


def test_console_launch(consoles, monkeypatch):
    monkeypatch.setattr(shutil, "which", lambda name: f"/usr/bin/{name}")
    games = {g.key: g for g in scan_library(consoles)}
    spec = build_launch(games["ps1/Spyro.chd"], GameState(), consoles, base_env={})
    rom = str(games["ps1/Spyro.chd"].path)
    assert spec.runner == "emulator"
    assert spec.argv == ["duckstation", "-batch", "-fullscreen", "--", rom]
    # a game's own command; without %rom% the ROM goes last; launch options wrap it
    state = GameState(command="mupen64plus --windowed", options="MANGOHUD=1 gamemoderun %command%")
    spec = build_launch(games["n64/Mario.z64"], state, consoles, base_env={})
    assert spec.argv == ["gamemoderun", "mupen64plus", "--windowed", str(games["n64/Mario.z64"].path)]
    assert spec.env["MANGOHUD"] == "1"


def test_console_emulator_missing(consoles, monkeypatch):
    monkeypatch.setattr(shutil, "which", lambda name: None)
    game = next(g for g in scan_library(consoles) if g.platform == "gamecube")
    with pytest.raises(LaunchError, match="Dolphin"):
        build_launch(game, GameState(), consoles, base_env={})


def test_console_config_roundtrip(tmp_path):
    cfg = Config()
    cfg.emulators.ps2 = "pcsx2 -fullscreen %rom%"
    cfg.emulators.wii_dir = "/mnt/wii"
    save_config(cfg, tmp_path / "c.toml")
    back = load_config(tmp_path / "c.toml")
    assert back.emulators.ps2 == "pcsx2 -fullscreen %rom%"
    assert back.platform_dir("wii") == Path("/mnt/wii")

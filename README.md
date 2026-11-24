# Hearth

A terminal game launcher for Linux. It scans your games folder, runs Windows games through
**Proton via [umu-launcher](https://github.com/Open-Wine-Components/umu-launcher)** (with
**Wine** as the fallback), starts native Linux ports directly, and plays **PlayStation,
PlayStation 2, Nintendo 64, GameCube and Wii** ROMs through their emulators.

## Library layout

```
~/Games/                    library root (configurable)
├── Windows/                Windows games folder (name configurable, or an absolute path)
│   ├── Hollow Peak/        a game: a folder with files in it
│   └── RPG/                a category: a folder holding only folders
│       └── Ashen Crown/
├── Linux/                  Linux games folder (name configurable)
│   └── Lantern Isle/
│       └── start.sh
├── PS1/                    console ROMs: a folder per system (names configurable)
│   ├── Spyro.chd           each ROM file is a game
│   ├── Crash.cue           a .cue stands for the .bin tracks it names
│   └── RPG/                sub-folders are categories
│       └── Final.m3u       an .m3u is one multi-disc game
├── PS2/  N64/  GameCube/  Wii/
```

A folder holding files, or typical game sub-folders (`bin`, `Binaries`, `*_Data`, …), is
a game. A folder holding only folders is a category, searched up to *Category depth* levels
deep. Marker files settle the matter when the guess goes wrong:

| file               | effect                          |
|--------------------|---------------------------------|
| `.hearth-game`     | treat this folder as a game     |
| `.hearth-category` | treat it as a category          |
| `.hearth-ignore`   | skip it                         |

The executable is detected automatically. Installers, redistributables, crash reporters and
shared libraries are passed over, and names resembling the folder are preferred (`start.sh`
for GOG Linux games). If the guess is wrong, press `e` to choose another; Hearth remembers
the choice.

## Consoles

| folder | system | emulator | ROMs |
|---|---|---|---|
| `PS1` | PlayStation | DuckStation | `.cue` `.chd` `.pbp` `.m3u` `.iso` `.img` `.bin` `.ecm` `.mds` |
| `PS2` | PlayStation 2 | PCSX2 | `.iso` `.chd` `.cso` `.zso` `.gz` `.bin` `.cue` `.img` `.mdf` `.nrg` `.m3u` `.elf` |
| `N64` | Nintendo 64 | Mupen64Plus | `.z64` `.n64` `.v64` `.zip` |
| `GameCube` | GameCube | Dolphin | `.iso` `.gcm` `.gcz` `.rvz` `.ciso` `.wia` `.dol` `.elf` `.m3u` |
| `Wii` | Wii | Dolphin | `.iso` `.wbfs` `.rvz` `.wia` `.gcz` `.ciso` `.wad` `.dol` `.elf` |

Games start fullscreen and straight into the game, and the emulator closes with it:

```
duckstation -batch -fullscreen -- %rom%
pcsx2 -batch -nogui -fullscreen -- %rom%
mupen64plus --fullscreen %rom%
dolphin-emu -b -C Dolphin.Display.Fullscreen=True -e %rom%      (GameCube and Wii)
```

Each command can be changed in Settings, and per game with `e`; `%rom%` is the ROM's path (without
it, the ROM goes last). Launch options wrap the emulator like any other game:
`gamemoderun %command%`. For DuckStation to close when the game does, untick *Confirm Power Off* in
its settings. The PlayStation emulators need a BIOS from your own console, set up in the emulator.

## Runners

* **Proton (default for Windows games):** `umu-run` with a single, shared prefix
  (`~/.local/share/hearth/prefixes/proton`). The build is *UMU-Proton* (umu's default,
  downloaded on demand), *GE-Proton* (latest, downloaded on demand), or any installed build
  from `compatibilitytools.d`, chosen in Settings.
* **Wine:** used automatically when umu-launcher is missing, or for any game whose runner you
  set to *Wine*. It has its own prefix (`…/prefixes/wine`), because Proton and system Wine
  would keep "upgrading" one shared prefix back and forth.
* **Native:** Linux executables and scripts run directly from their own folder.

**Launch options** use Steam's syntax, per game and as a global default:
`MANGOHUD=1 gamemoderun %command% -dx11`. Without `%command%`, leading `VAR=value` pairs set
the environment and everything else is passed to the game.

## Keys

| key       | action                         | key | action                         |
|-----------|--------------------------------|-----|--------------------------------|
| `p` / `⏎` | play                           | `f` | filter: all / Windows / Linux / consoles |
| `e`       | edit game (exe, runner, opts)  | `o` | sort: name / recent / playtime |
| `x`       | stop (twice: kill)             | `r` | rescan                         |
| `l`       | view the game's last log       | `w` | winecfg in the prefix          |
| `/`       | search                         | `t` | winetricks in the prefix       |
| `s`       | settings                       | `q` | quit (games keep running)      |

## Command line

```fish
hearth                  # the TUI
hearth list             # every game, its platform, playtime and executable
hearth run hollow       # launch by (part of) a name, detached
hearth run -w hollow    # stay attached: output here, playtime counted
hearth doctor           # paths, umu/wine and installed Proton builds
```

## Files

* `~/.config/hearth/config.toml`: settings (also editable in the TUI with `s`)
* `~/.local/share/hearth/games.json`: per-game choices and playtime
* `~/.local/share/hearth/prefixes/`: the Proton and Wine prefixes
* `~/.local/state/hearth/logs/`: the latest output of each game

## Installing

As an Arch package, which pulls in `umu-launcher` and `python-textual`:

```fish
cd packaging; makepkg -si
```

Or for your user only, with umu-launcher installed separately:

```fish
sudo pacman -S umu-launcher
uv tool install --editable .
cp packaging/hearth.fish ~/.config/fish/completions/
```

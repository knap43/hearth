# Hearth

A terminal game launcher for Linux. It scans your games folder, runs Windows games through
**Proton via [umu-launcher](https://github.com/Open-Wine-Components/umu-launcher)** (with
**Wine** as the fallback) and starts native Linux ports directly.

## Library layout

```
~/Games/                    library root (configurable)
├── Windows/                Windows games folder (name configurable, or an absolute path)
│   ├── Hollow Peak/        a game: a folder with files in it
│   └── RPG/                a category: a folder holding only folders
│       └── Ashen Crown/
└── Linux/                  Linux games folder (name configurable)
    └── Lantern Isle/
        └── start.sh
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
| `p` / `⏎` | play                           | `f` | filter: all / Windows / Linux  |
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

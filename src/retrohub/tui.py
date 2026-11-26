"""The Textual interface."""

from __future__ import annotations

import time
from pathlib import Path

from rich.markup import escape
from rich.text import Text
from textual import on, work
from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Button, DataTable, Footer, Header, Input, Label, RichLog, Select, Static

from . import __version__, sessions
from .config import CONFIG_FILE, Config, GameState, GameStore, expand, load_config, save_config
from .emulators import SYSTEMS, find_emulator
from .runners import (
    RUNNER_LABELS, LaunchError, build_launch, emulator_command, find_umu, installed_protons,
    prefix_tool, proton_label, resolve_exe, windows_runner,
)
from .scanner import LINUX, WINDOWS, Game, scan_library

PLATFORM_LABEL = {WINDOWS: "Windows", LINUX: "Linux", **{s.id: s.label for s in SYSTEMS.values()}}
CONSOLES = "consoles"
FILTERS = ("all", WINDOWS, LINUX, CONSOLES)
SORTS = ("name", "recent", "playtime")


def display_name(game: Game, state: GameState) -> str:
    return state.name or game.folder


def tilde(path) -> str:
    text, home = str(path), str(Path.home())
    return "~" + text[len(home):] if text == home or text.startswith(home + "/") else text


def rel(path: Path, base: Path) -> str:
    try:
        return str(path.relative_to(base))
    except ValueError:
        return str(path)


# --- dialogs -------------------------------------------------------------------------

class Dialog(ModalScreen):
    BINDINGS = [Binding("escape", "cancel", "Cancel")]

    def action_cancel(self) -> None:
        self.dismiss(None)

    @on(Button.Pressed, "#cancel")
    def _cancel(self) -> None:
        self.dismiss(None)


def field(label: str, widget, hint: str = ""):
    yield Label(label, classes="field-label")
    yield widget
    if hint:
        yield Static(hint, classes="hint")


class EditGame(Dialog):
    def __init__(self, game: Game, state: GameState):
        super().__init__()
        self.game, self.state = game, state

    def compose(self) -> ComposeResult:
        g, s = self.game, self.state
        rels = [rel(p, g.path) for p in g.candidates[:40]]
        best = rels[0] if rels else "nothing found"
        options = [(Text(f"Automatic: {best}"), "")] + [(Text(r), r) for r in rels]
        chosen = s.exe if s.exe in rels else ""
        custom = s.exe if s.exe and s.exe not in rels else ""
        if g.platform in SYSTEMS:
            yield from self.compose_console()
            return
        with VerticalScroll(classes="dialog"):
            yield Static(f"[b]{escape(display_name(g, s))}[/b]  [dim]{escape(tilde(g.path))}[/dim]")
            yield from field("Name", Input(s.name, placeholder=g.folder, id="name"))
            yield from field("Executable", Select(options, value=chosen, allow_blank=False, id="exe"))
            yield from field("…or another file", Input(custom, placeholder="path relative to the game folder, or absolute", id="custom"))
            if g.platform == WINDOWS:
                runners = [(label, key) for key, label in RUNNER_LABELS.items()]
                yield from field("Runner", Select(runners, value=s.runner or "auto", allow_blank=False, id="runner"))
                yield from field(
                    "umu GAMEID", Input(s.game_id, placeholder="default from settings, e.g. umu-1245620", id="game_id"),
                    "Matching a game's id in the umu database applies its protonfixes.",
                )
            yield from field(
                "Launch options", Input(s.options, placeholder="e.g. MANGOHUD=1 gamemoderun %command% -dx11", id="options"),
                "Steam syntax. Without %command%, everything is passed to the game as arguments.",
            )
            with Horizontal(classes="buttons"):
                yield Button("Save", variant="primary", id="save")
                yield Button("Cancel", id="cancel")

    def compose_console(self) -> ComposeResult:
        g, s = self.game, self.state
        system = SYSTEMS[g.platform]
        with VerticalScroll(classes="dialog"):
            yield Static(f"[b]{escape(display_name(g, s))}[/b]  [dim]{escape(system.title)} · "
                         f"{escape(tilde(g.path))}[/dim]")
            yield from field("Name", Input(s.name, placeholder=g.folder, id="name"))
            yield from field(
                "Emulator command", Input(s.command, placeholder="the system's, from Settings", id="command"),
                f"Only for this game. {escape('%rom%')} is replaced by the ROM; without it the ROM goes last.",
            )
            yield from field(
                "Launch options", Input(s.options, placeholder="e.g. MANGOHUD=1 gamemoderun %command%", id="options"),
                "Steam syntax, wrapped around the emulator's command.",
            )
            with Horizontal(classes="buttons"):
                yield Button("Save", variant="primary", id="save")
                yield Button("Cancel", id="cancel")

    @on(Button.Pressed, "#save")
    def save(self) -> None:
        if self.game.platform in SYSTEMS:
            self.dismiss({
                "name": self.query_one("#name", Input).value.strip(),
                "command": self.query_one("#command", Input).value.strip(),
                "options": self.query_one("#options", Input).value.strip(),
            })
            return
        custom = self.query_one("#custom", Input).value.strip()
        if custom:
            path = expand(custom)
            path = path if path.is_absolute() else self.game.path / path
            if not path.is_file():
                self.notify(f"No such file: {escape(str(path))}", severity="error")
                return
        result = {
            "name": self.query_one("#name", Input).value.strip(),
            "exe": custom or self.query_one("#exe", Select).value,
            "options": self.query_one("#options", Input).value.strip(),
        }
        if self.game.platform == WINDOWS:
            result["runner"] = self.query_one("#runner", Select).value
            result["game_id"] = self.query_one("#game_id", Input).value.strip()
        self.dismiss(result)


class Settings(Dialog):
    def __init__(self, cfg: Config):
        super().__init__()
        self.cfg = cfg

    def compose(self) -> ComposeResult:
        c = self.cfg
        builds = [
            ("UMU-Proton (umu default, downloaded when needed)", ""),
            ("GE-Proton (latest, downloaded when needed)", "GE-Proton"),
        ] + [(Text(p.name), str(p)) for p in installed_protons()]
        if c.proton.build and c.proton.build not in {v for _, v in builds}:
            builds.append((Text(c.proton.build), c.proton.build))
        umu = find_umu()
        umu_line = (
            f"[green]umu-run found:[/green] {escape(umu)}" if umu
            else "[yellow]umu-run not found[/yellow] — Windows games fall back to Wine. "
                 "Install it with: sudo pacman -S umu-launcher"
        )
        with VerticalScroll(classes="dialog"):
            yield Static(f"[b]Settings[/b]  [dim]{escape(tilde(CONFIG_FILE))}[/dim]")
            yield Label("Library", classes="section")
            yield from field("Library root", Input(c.library.root, id="root"))
            with Horizontal(classes="pair"):
                with Vertical():
                    yield from field("Windows games folder", Input(c.library.windows_dir, id="windows_dir"))
                with Vertical():
                    yield from field("Linux games folder", Input(c.library.linux_dir, id="linux_dir"))
            yield from field(
                "Category depth", Input(str(c.library.max_depth), type="integer", id="max_depth"),
                "How many levels of folders-of-folders (e.g. Windows/RPG/…) to look through.",
            )
            yield Label("Proton", classes="section")
            yield Static(umu_line, classes="hint")
            yield from field("Proton build", Select(builds, value=c.proton.build, allow_blank=False, id="build"))
            yield from field("Shared prefix", Input(c.proton.prefix, id="proton_prefix"))
            yield from field("Default umu GAMEID", Input(c.proton.game_id, id="game_id"))
            yield Label("Wine fallback", classes="section")
            with Horizontal(classes="pair"):
                with Vertical():
                    yield from field("Wine binary", Input(c.wine.binary, id="wine_binary"))
                with Vertical():
                    yield from field("Wine prefix", Input(c.wine.prefix, id="wine_prefix"))
            yield Label("Consoles", classes="section")
            yield Static(f"One folder of ROMs per system, under the library root. In a command, "
                         f"{escape('%rom%')} is the ROM's path.", classes="hint")
            for s in SYSTEMS.values():
                found = find_emulator(getattr(c.emulators, s.id))
                state = "" if found else "  [yellow](not found)[/yellow]"
                with Horizontal(classes="pair"):
                    with Vertical(classes="narrow"):
                        yield from field(f"{s.title} folder", Input(getattr(c.emulators, f"{s.id}_dir"), id=f"{s.id}_dir"))
                    with Vertical():
                        yield from field(f"{s.emulator}{state}", Input(getattr(c.emulators, s.id), id=f"cmd_{s.id}"))
            yield Label("Launching", classes="section")
            yield from field(
                "Default launch options", Input(c.launch.default_options, placeholder="e.g. gamemoderun %command%", id="default_options"),
                "Wrapped around every game's own options.",
            )
            with Horizontal(classes="buttons"):
                yield Button("Save", variant="primary", id="save")
                yield Button("Cancel", id="cancel")

    def _value(self, wid: str) -> str:
        return self.query_one(f"#{wid}", Input).value.strip()

    @on(Button.Pressed, "#save")
    def save(self) -> None:
        c = self.cfg
        c.library.root = self._value("root") or "~/Games"
        c.library.windows_dir = self._value("windows_dir") or "Windows"
        c.library.linux_dir = self._value("linux_dir") or "Linux"
        try:
            c.library.max_depth = max(1, min(10, int(self._value("max_depth") or 4)))
        except ValueError:
            c.library.max_depth = 4
        c.proton.build = self.query_one("#build", Select).value
        c.proton.prefix = self._value("proton_prefix") or c.proton.prefix
        c.proton.game_id = self._value("game_id") or "umu-default"
        c.wine.binary = self._value("wine_binary") or "wine"
        c.wine.prefix = self._value("wine_prefix") or c.wine.prefix
        c.launch.default_options = self._value("default_options")
        for s in SYSTEMS.values():
            setattr(c.emulators, f"{s.id}_dir", self._value(f"{s.id}_dir") or s.folder)
            setattr(c.emulators, s.id, self._value(f"cmd_{s.id}") or s.command)
        self.dismiss(True)


class LogView(Dialog):
    BINDINGS = [Binding("escape,q", "cancel", "Close"), Binding("r", "reload", "Reload")]

    def __init__(self, title: str, path: Path):
        super().__init__()
        self.title_text, self.path = title, path

    def compose(self) -> ComposeResult:
        with Vertical(classes="dialog log-dialog"):
            yield Static(f"[b]{escape(self.title_text)}[/b]  [dim]{escape(str(self.path))}[/dim]")
            yield RichLog(markup=False, highlight=False, wrap=True, id="log")
            yield Static("[dim]r reload · esc close[/dim]")

    def on_mount(self) -> None:
        self.action_reload()

    def action_reload(self) -> None:
        log = self.query_one(RichLog)
        log.clear()
        lines = sessions.tail(self.path)
        for line in lines or ["(no log yet — launch the game first)"]:
            log.write(Text(line))


# --- main screen ---------------------------------------------------------------------

class RetroHub(App):
    TITLE = "RetroHub"
    CSS = """
    #main { height: 1fr; }
    #left { width: 2fr; }
    /* one line, flush with the table below it: Input's own tall border is drawn
       with half blocks that sit off the table's edge in many terminal fonts */
    #search { border: none; height: 1; padding: 0 1; margin: 0 0 1 0; background: $panel; }
    #search:focus { border: none; background: $panel-lighten-1; }
    /* With the terminal's colours, raised parts use its first palette colour */
    #search:ansi, #search:ansi:focus { background: ansi_black; }
    #details:ansi { border-left: solid ansi_black; }
    #log:ansi { border: tall ansi_black; }
    #games { height: 1fr; }
    #message { padding: 1 2; color: $text-muted; }
    #details { width: 1fr; min-width: 38; border-left: solid $panel; padding: 0 1; }
    Dialog { align: center middle; }
    .dialog { width: 96; max-width: 96%; height: auto; max-height: 92%;
              border: thick $primary; background: $surface; padding: 1 2; }
    .log-dialog { width: 96%; height: 90%; max-height: 90%; }
    #log { height: 1fr; border: tall $panel; }
    .field-label { margin-top: 1; color: $text-muted; }
    .section { margin-top: 1; text-style: bold; color: $accent; }
    .hint { color: $text-muted; text-style: italic; }
    .pair { height: auto; }
    .pair > Vertical { height: auto; width: 1fr; margin-right: 1; }
    .pair > Vertical.narrow { width: 22; }
    .buttons { height: auto; margin-top: 1; align-horizontal: right; }
    .buttons Button { margin-left: 1; }
    """
    BINDINGS = [
        Binding("p", "launch", "Play"),
        Binding("enter", "launch", "Play", show=False),
        Binding("e", "edit", "Edit"),
        Binding("x", "stop", "Stop"),
        Binding("l", "log", "Log"),
        Binding("slash", "search", "Search"),
        Binding("f", "filter", "Filter"),
        Binding("o", "sort", "Sort"),
        Binding("r", "rescan", "Rescan"),
        Binding("w", "prefix_tool('winecfg')", "winecfg"),
        Binding("t", "prefix_tool('winetricks')", "winetricks"),
        Binding("s", "settings", "Settings"),
        Binding("q", "quit", "Quit"),
        Binding("escape", "focus_table", show=False),
    ]

    def __init__(self, cfg: Config | None = None, store: GameStore | None = None):
        super().__init__()
        self.cfg = cfg or load_config()
        if self.cfg.interface.terminal_colours:
            # The terminal's palette and background, so RetroHub follows its theme
            self.ansi_color = True
            self.theme = "ansi-dark"
        self.store = store or GameStore()
        self.games: list[Game] = []
        self.by_key: dict[str, Game] = {}
        self.running: dict[str, sessions.Session] = {}
        self.tools: list[sessions.Session] = []
        self.filter = "all"
        self.sort = "name"
        self.scanning = False

    # layout

    def compose(self) -> ComposeResult:
        yield Header()
        with Horizontal(id="main"):
            with Vertical(id="left"):
                yield Input(placeholder="Search games  ( / )", id="search")
                table = DataTable(id="games", cursor_type="row", zebra_stripes=True, show_row_labels=False)
                yield table
                yield Static("", id="message")
            with VerticalScroll(id="details"):
                yield Static("", id="info")
        yield Footer()

    def on_mount(self) -> None:
        table = self.query_one(DataTable)
        table.add_column(" ", key="status", width=1)
        table.add_column("Game", key="name")
        table.add_column("Platform", key="platform", width=8)
        table.add_column("Category", key="category")
        table.add_column("Last played", key="last", width=12)
        table.add_column("Time", key="time", width=8)
        table.focus()
        self.set_interval(1.0, self.poll)
        self.action_rescan()

    def update_subtitle(self) -> None:
        names = {"all": "all games", WINDOWS: "Windows only", LINUX: "Linux only", CONSOLES: "consoles only"}
        self.sub_title = f"{tilde(expand(self.cfg.library.root))} · {names[self.filter]} · sort: {self.sort}"

    # scanning

    def action_rescan(self) -> None:
        if self.scanning:
            return
        self.scanning = True
        self.show_message("Scanning the library…")
        self.scan_worker()

    @work(thread=True, exclusive=True)
    def scan_worker(self) -> None:
        try:
            games = scan_library(self.cfg)
            error = None
        except Exception as exc:  # noqa: BLE001 - shown to the user
            games, error = [], str(exc)
        self.call_from_thread(self.scanned, games, error)

    def scanned(self, games: list[Game], error: str | None) -> None:
        self.scanning = False
        self.games = games
        self.by_key = {g.key: g for g in games}
        if error:
            self.notify(f"Scan failed: {escape(error)}", severity="error")
        self.refresh_table()

    # table

    def visible_games(self) -> list[Game]:
        query = self.query_one("#search", Input).value.strip().lower()
        out = []
        for g in self.games:
            if self.filter == CONSOLES:
                if g.platform not in SYSTEMS:
                    continue
            elif self.filter != "all" and g.platform != self.filter:
                continue
            state = self.store.get(g.key)
            if query and query not in f"{display_name(g, state)} {g.folder} {g.category}".lower():
                continue
            out.append(g)
        if self.sort == "recent":
            out.sort(key=lambda g: -self.store.get(g.key).last_played)
        elif self.sort == "playtime":
            out.sort(key=lambda g: -self.store.get(g.key).playtime)
        else:
            out.sort(key=lambda g: display_name(g, self.store.get(g.key)).lower())
        return out

    def show_message(self, text: str) -> None:
        message = self.query_one("#message", Static)
        message.update(text)
        message.display = bool(text)

    def refresh_table(self) -> None:
        table = self.query_one(DataTable)
        selected = self.selected_key()
        table.clear()
        rows = self.visible_games()
        for g in rows:
            s = self.store.get(g.key)
            table.add_row(
                Text("▶", style="bold green") if g.key in self.running else "",
                Text(display_name(g, s)),
                PLATFORM_LABEL[g.platform],
                Text(g.category, style="dim"),
                sessions.format_when(s.last_played) if s.last_played else Text("never", style="dim"),
                sessions.format_duration(s.playtime),
                key=g.key,
            )
        if selected:
            for i, g in enumerate(rows):
                if g.key == selected:
                    table.move_cursor(row=i)
                    break
        self.update_subtitle()
        if self.scanning:
            pass
        elif not self.games:
            self.show_message(self.empty_hint())
        elif not rows:
            self.show_message("No games match.")
        else:
            self.show_message("")
        self.update_details()

    def empty_hint(self) -> str:
        lines = ["[b]No games found.[/b]", "", "RetroHub looks in:"]
        places = [("Windows", WINDOWS), ("Linux", LINUX)] + [(s.title, s.id) for s in SYSTEMS.values()]
        for label, platform in places:
            path = self.cfg.platform_dir(platform)
            state = "" if path.is_dir() else "  [yellow](missing)[/yellow]"
            lines.append(f"  {label + ':':<15}{escape(tilde(path))}{state}")
        lines += ["", "Put each PC game in its own folder, and console ROMs straight into their",
                  "system's folder (sub-folders for categories are fine either way),",
                  "or press [b]s[/b] to point RetroHub elsewhere."]
        return "\n".join(lines)

    def selected_key(self) -> str | None:
        table = self.query_one(DataTable)
        if not table.row_count:
            return None
        try:
            return table.coordinate_to_cell_key(table.cursor_coordinate).row_key.value
        except Exception:  # noqa: BLE001
            return None

    def selected(self) -> tuple[Game, GameState] | None:
        key = self.selected_key()
        if key and key in self.by_key:
            return self.by_key[key], self.store.get(key)
        return None

    @on(DataTable.RowHighlighted)
    def _highlighted(self) -> None:
        self.update_details()

    @on(DataTable.RowSelected)
    def _selected(self) -> None:
        self.action_launch()

    @on(Input.Changed, "#search")
    def _search_changed(self) -> None:
        self.refresh_table()

    @on(Input.Submitted, "#search")
    def _search_done(self) -> None:
        self.action_focus_table()

    def update_details(self) -> None:
        info = self.query_one("#info", Static)
        sel = self.selected()
        if not sel:
            info.update("")
            return
        g, s = sel
        lines = [f"[b]{escape(display_name(g, s))}[/b]", ""]
        if g.platform in SYSTEMS:
            system = SYSTEMS[g.platform]
            line = emulator_command(g, s, self.cfg)
            how = escape(system.emulator) + ("" if find_emulator(line) else "  [red](not installed)[/red]")
            lines.append(f"[dim]{escape(system.title)} ·[/dim] {how}")
            if g.category:
                lines.append(f"[dim]Category:[/dim] {escape(g.category)}")
            lines += ["", "[dim]ROM[/dim]", escape(tilde(g.path)), "",
                      "[dim]Command[/dim]" + ("  [dim](this game's)[/dim]" if s.command else ""), escape(line)]
            if s.options:
                lines += ["", "[dim]Launch options[/dim]", escape(s.options)]
            lines += ["", f"[dim]Played:[/dim] {sessions.format_duration(s.playtime)}"
                          f"  [dim]· last:[/dim] {sessions.format_when(s.last_played)}"]
            if g.key in self.running:
                run = self.running[g.key]
                lines.append(f"[green]Running for {sessions.format_duration(run.elapsed)}[/green]  [dim](x to stop)[/dim]")
            info.update("\n".join(lines))
            return
        if g.platform == WINDOWS:
            try:
                runner, note = windows_runner(s)
                how = f"Proton · {escape(proton_label(self.cfg.proton.build))}" if runner == "proton" else "Wine"
                if note:
                    how += "  [yellow](fallback)[/yellow]"
            except LaunchError as exc:
                how = f"[red]{escape(str(exc))}[/red]"
            lines.append(f"[dim]Windows ·[/dim] {how}")
        else:
            lines.append("[dim]Linux · native[/dim]")
        if g.category:
            lines.append(f"[dim]Category:[/dim] {escape(g.category)}")
        lines += ["", "[dim]Folder[/dim]", escape(tilde(g.path)), "", "[dim]Executable[/dim]"]
        try:
            exe = resolve_exe(g, s)
            origin = "chosen" if s.exe else f"auto-detected, {len(g.candidates)} candidate(s)"
            lines.append(f"{escape(rel(exe, g.path))}  [dim]({origin})[/dim]")
        except LaunchError as exc:
            lines.append(f"[red]{escape(str(exc))}[/red]")
        if s.options:
            lines += ["", "[dim]Launch options[/dim]", escape(s.options)]
        if s.game_id:
            lines += ["", f"[dim]GAMEID:[/dim] {escape(s.game_id)}"]
        lines += ["", f"[dim]Played:[/dim] {sessions.format_duration(s.playtime)}"
                      f"  [dim]· last:[/dim] {sessions.format_when(s.last_played)}"]
        if g.key in self.running:
            run = self.running[g.key]
            lines.append(f"[green]Running for {sessions.format_duration(run.elapsed)}[/green]  [dim](x to stop)[/dim]")
        info.update("\n".join(lines))

    # actions

    def action_focus_table(self) -> None:
        self.query_one(DataTable).focus()

    def action_search(self) -> None:
        self.query_one("#search", Input).focus()

    def action_filter(self) -> None:
        self.filter = FILTERS[(FILTERS.index(self.filter) + 1) % len(FILTERS)]
        self.refresh_table()

    def action_sort(self) -> None:
        self.sort = SORTS[(SORTS.index(self.sort) + 1) % len(SORTS)]
        self.refresh_table()

    def action_launch(self) -> None:
        sel = self.selected()
        if not sel:
            return
        g, s = sel
        name = escape(display_name(g, s))
        if g.key in self.running:
            self.notify(f"{name} is already running.")
            return
        try:
            spec = build_launch(g, s, self.cfg)
            session = sessions.start(g.key, spec)
        except (LaunchError, OSError) as exc:
            self.notify(escape(str(exc)), title="Cannot launch", severity="error", timeout=8)
            return
        self.running[g.key] = session
        s.last_played = time.time()
        self.store.save()
        if spec.note:
            self.notify(escape(spec.note), severity="warning")
        via = {"proton": "Proton", "wine": "Wine", "native": "native"}.get(spec.runner) \
            or SYSTEMS[g.platform].emulator
        self.notify(f"Starting {name} ({via})…")
        self.refresh_table()

    def action_stop(self) -> None:
        sel = self.selected()
        if not sel or sel[0].key not in self.running:
            self.notify("The selected game is not running.")
            return
        session = self.running[sel[0].key]
        forced = bool(session.stop_requested)
        sessions.stop(session)
        self.notify("Killing…" if forced else "Asked the game to close (press x again to kill).")

    def action_log(self) -> None:
        sel = self.selected()
        if sel:
            g, s = sel
            self.push_screen(LogView(display_name(g, s), sessions.log_path(g.key)))

    def action_edit(self) -> None:
        sel = self.selected()
        if not sel:
            return
        g, s = sel

        def done(result: dict | None) -> None:
            if result is None:
                return
            for key, value in result.items():
                setattr(s, key, value)
            self.store.save()
            self.refresh_table()

        self.push_screen(EditGame(g, s), done)

    def action_settings(self) -> None:
        def done(saved: bool | None) -> None:
            if saved:
                save_config(self.cfg)
                self.notify("Settings saved.")
                self.action_rescan()

        self.push_screen(Settings(self.cfg), done)

    def action_prefix_tool(self, tool: str) -> None:
        sel = self.selected()
        state = sel[1] if sel and sel[0].platform == WINDOWS else GameState()
        try:
            runner, _ = windows_runner(state)
            spec = prefix_tool(tool, self.cfg, runner)
            self.tools.append(sessions.start(f"prefix-{runner}-{tool}", spec))
        except (LaunchError, OSError) as exc:
            self.notify(escape(str(exc)), title=f"Cannot start {tool}", severity="error")
            return
        prefix = self.cfg.proton.prefix if runner == "proton" else self.cfg.wine.prefix
        self.notify(f"Opening {tool} in the {runner.capitalize()} prefix ({escape(prefix)})…")

    # bookkeeping

    def poll(self) -> None:
        self.tools = [t for t in self.tools if t.proc.poll() is None]
        finished = [(k, s) for k, s in self.running.items() if s.proc.poll() is not None]
        for key, session in finished:
            del self.running[key]
            elapsed = session.elapsed
            state = self.store.get(key)
            state.playtime += int(elapsed)
            game = self.by_key.get(key)
            name = escape(display_name(game, state) if game else key)
            code = session.proc.returncode
            if code not in (0, None) and elapsed < 20 and not session.stop_requested:
                self.notify(f"{name} quit after {int(elapsed)} s with code {code}. Press l for its log.",
                            severity="error", timeout=10)
            else:
                self.notify(f"{name} closed after {sessions.format_duration(elapsed)}.")
        if finished:
            self.store.save()
            self.refresh_table()
        elif self.running:
            self.update_details()

    def record_running(self) -> int:
        """Credit playtime of still-running games before quitting; they keep running."""
        for key, session in self.running.items():
            self.store.get(key).playtime += int(session.elapsed)
        if self.running:
            self.store.save()
        return len(self.running)


def run_tui() -> None:
    app = RetroHub()
    app.run()
    still = app.record_running()
    if still:
        print(f"{still} game(s) still running; RetroHub counted their playtime up to now.")

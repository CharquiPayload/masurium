"""The main window, laid out as Prism Launcher's so it feels familiar: a bar
of buttons on top, every instance by group in the middle, and on the right
what can be done with the one selected."""
import collections
import time
from dataclasses import dataclass

from PySide6.QtCore import QSettings, QSize, Qt, QTimer, QUrl
from PySide6.QtGui import QAction, QDesktopServices, QIcon, QImage, QKeySequence
from PySide6.QtWidgets import (QApplication, QFileDialog, QFrame, QHBoxLayout, QLineEdit, QListWidget,
                               QMainWindow, QMenu, QMessageBox, QPushButton, QScrollArea, QSizePolicy, QStatusBar,
                               QToolBar, QToolButton, QVBoxLayout, QWidget)

from .. import __version__, groups, operations, settings
from ..bots import Instance
from ..events import Cancelled, Fail
from . import anim, dialogs, icons, state, theme
from .common import ConsoleDialog, MessageBox, ask, muted, open_help, title
from .tasks import Background, Tasks
from .widgets import DependencySection, GroupSection, InstanceTile, icon_of

REFRESH_MS = 3000
LOOSE = ""            # the section of the instances in no group
ISSUES = "https://github.com/CharquiPayload/marionette/issues"


@dataclass
class Action:
    """Something that can be done with what is selected: a button on the
    side panel and a line in its right-click menu. `extra` ones go under
    Launch's arrow on the panel."""
    text: str
    icon: str
    run: object
    enabled: bool = True
    extra: bool = False
    tip: str = ""


SEPARATOR = None


class MainWindow(QMainWindow):
    def __init__(self, ws, store=None):
        super().__init__()
        self.ws = ws
        self.tasks = Tasks(self)
        self.background = Background(self)
        self.snapshot = state.Snapshot()
        self.shape = None
        self.tiles, self.sections = {}, {}
        self.selected = None                  # ("instance", key) or ("group", key)
        self.activity = collections.defaultdict(lambda: collections.deque(maxlen=60))
        self.reading = False
        self.side_signature = None
        self.editors = {}                     # instance key -> its open Edit Instance window
        self.just_moved = None                # what was dropped, to show it arriving
        # What folds, the style, motion, how often it looks: remembered between
        # runs (a test hands in a file of its own).
        self.store = store or QSettings("Marionette", "launcher")
        anim.enabled = self.store.value("appearance/animations", True, type=bool)
        icons.animated = self.store.value("appearance/animated_icons", True, type=bool)
        theme.apply(QApplication.instance(), self.store.value("appearance/style", theme.DEFAULT))
        self.setWindowTitle("Marionette")
        self.resize(1180, 720)

        self._toolbar()
        body = QWidget()
        outer = QHBoxLayout(body)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.setSpacing(0)
        self.page = QWidget()
        self.page.setObjectName("page")
        self.page.setContextMenuPolicy(Qt.CustomContextMenu)
        self.page.customContextMenuRequested.connect(
            lambda pos: self._space_menu(LOOSE, self.page.mapToGlobal(pos)))
        self.grid = QVBoxLayout(self.page)
        self.grid.setContentsMargins(12, 8, 12, 12)
        self.grid.setSpacing(6)
        scroll = QScrollArea()
        scroll.setWidget(self.page)
        scroll.setWidgetResizable(True)
        outer.addWidget(scroll, 1)
        self.side = QFrame()
        self.side.setObjectName("side")
        self.side.setFixedWidth(250)
        self.side_holder = QVBoxLayout(self.side)
        self.side_holder.setContentsMargins(0, 0, 0, 0)
        self.side_content = None
        outer.addWidget(self.side)
        self.setCentralWidget(body)
        self.setStatusBar(QStatusBar())

        self.tasks.event.connect(self._event)
        self.tasks.done.connect(self._done)
        self.tasks.failed.connect(self._failed)
        self.tasks.changed.connect(self._tasks_changed)
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.refresh)
        self.timer.start(self.refresh_seconds() * 1000)
        self._draw_side()
        self.refresh()

    # --- the bar on top, Prism's ---------------------------------------------------------

    def _toolbar(self):
        bar = QToolBar()
        bar.setMovable(False)
        bar.setToolButtonStyle(Qt.ToolButtonTextBesideIcon)
        bar.setIconSize(QSize(18, 18))
        bar.setContextMenuPolicy(Qt.PreventContextMenu)
        self.addToolBar(bar)
        self.toolbar = bar
        self.bar_buttons = {}

        def button(text, icon, fn=None, tip="", menu=None):
            b = QToolButton()
            b.setText(text)
            icons.set_on(b, icon)
            b.setToolButtonStyle(Qt.ToolButtonTextBesideIcon)
            b.setFocusPolicy(Qt.NoFocus)
            b.setToolTip(tip or text)
            if fn:
                b.clicked.connect(fn)
            if menu:
                b.setMenu(menu)
                b.setPopupMode(QToolButton.MenuButtonPopup if fn else QToolButton.InstantPopup)
            bar.addWidget(b)
            self.bar_buttons[text] = b
            return b

        add = QMenu(self)
        add.addAction(icons.icon("plus"), "Add Instance…", self.new_instance)
        add.addAction(icons.icon("group"), "Add Group…", self.new_group)
        button("Add Instance", "plus", self.new_instance, "A bot on a server (the arrow: a group)", add)
        button("Folders", "folder", tip="The launcher's folders", menu=self._folders_menu())
        button("Settings", "gear", lambda: self.open_settings(), "The launcher's settings: its style, Java, "
                                                                   "global settings and rules, accounts, servers")
        help_ = QMenu(self)
        help_.addAction(icons.icon("doctor"), "Doctor…", lambda: dialogs.DoctorDialog(self).exec())
        help_.addAction(icons.icon("help"), "Documentation", lambda: open_help())
        help_.addAction(icons.icon("logs"), "Report an issue", lambda: QDesktopServices.openUrl(QUrl(ISSUES)))
        help_.addSeparator()
        help_.addAction(icons.icon("info"), "About Marionette", self.about)
        button("Help", "help", tip="Doctor, the documentation", menu=help_)
        spacer = QWidget()
        spacer.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)
        bar.addWidget(spacer)
        self.search = QLineEdit()
        self.search.setPlaceholderText("Search instances…")
        self.search.setClearButtonEnabled(True)
        self.search.setFixedWidth(220)
        self.search.textChanged.connect(self._filter)
        bar.addWidget(self.search)
        button("Bots", "bot", lambda: dialogs.BotsDialog(self).exec(), "The characters: settings, rules, "
                                                                        "personality")
        button("Accounts", "account", lambda: self.open_settings("Accounts"), "Minecraft accounts, "
                                                                               "Microsoft and offline")
        quit_ = QAction(icons.icon("quit"), "Quit", self)
        quit_.setToolTip("Close the launcher (Ctrl+Q). The bots keep running.")
        quit_.setShortcut(QKeySequence("Ctrl+Q"))
        quit_.triggered.connect(self.close)
        bar.addAction(quit_)
        self.quit_action = quit_
        icons.set_on(bar.widgetForAction(quit_), "quit")

    def _folders_menu(self):
        """Prism's Folders menu. Opening one needs a file manager where the
        launcher runs, which a machine without a screen (reached through
        waypipe) has not: their paths can be copied instead."""
        ws = self.ws
        places = [("Instances", ws.instances_dir), ("Bots", ws.bots_dir), ("Groups", ws.groups_dir),
                  ("Servers", ws.servers_dir), ("Shared", ws.shared_dir), ("Accounts", ws.accounts_dir)]
        menu = QMenu(self)
        for name, path in places:
            menu.addAction(icons.icon("folder"), name, lambda p=path: self.open_folder(p))
        menu.addSeparator()
        copy = menu.addMenu(icons.icon("copy"), "Copy a folder's path")
        for name, path in places:
            copy.addAction(name, lambda p=path: self.copy_path(p))
        return menu

    def open_folder(self, path):
        path.mkdir(parents=True, exist_ok=True)
        if not QDesktopServices.openUrl(QUrl.fromLocalFile(str(path))):
            self.copy_path(path)

    def copy_path(self, path):
        QApplication.clipboard().setText(str(path))
        self.say_text(f"copied: {path}")

    def open_settings(self, start=None):
        dialogs.SettingsWindow(self, start=start).exec()
        self.refresh()

    def new_instance(self, group=None):
        dialogs.NewInstanceDialog(self, group=group).exec()

    def new_group(self, inside=None):
        if dialogs.NewGroupDialog(self, inside=inside).exec():
            self.refresh()

    def about(self):
        box = MessageBox(QMessageBox.NoIcon, "About Marionette",
                         f"<b>Marionette {__version__}</b><br>Minecraft bots that play as real clients, with "
                         "Claude as their brain.<br><br>Its window follows Prism Launcher's, so it feels "
                         "familiar. Marionette is an independent project, not affiliated with or endorsed by "
                         "Prism Launcher, HeadlessMC or Baritone, and not an official Minecraft product: not "
                         "approved by or associated with Mojang or Microsoft.<br><br>MIT licence.",
                         QMessageBox.Ok, self)
        box.setIconPixmap(theme.avatar("M", 56))
        box.exec()

    def toolbar_actions(self):
        return self.toolbar.actions()

    # --- reading --------------------------------------------------------------------------

    def refresh(self):
        """A new snapshot, read on a thread; the window draws it when it comes."""
        if self.reading:
            return
        self.reading = True
        self.background.ask(lambda: state.read(self.ws), self._snapshot, self._snapshot_failed)

    def _snapshot(self, snap):
        self.reading = False
        self.snapshot = snap
        if snap.shape() != self.shape:
            self.shape = snap.shape()
            self._build()
        else:
            self._update_tiles()
        self._draw_side()
        self._status()

    def _snapshot_failed(self, e):
        self.reading = False
        self.statusBar().showMessage(f"could not read the instances: {e}")

    # --- the instances, by group ------------------------------------------------------------

    def _clear_grid(self):
        while self.grid.count():
            item = self.grid.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        self.tiles, self.sections = {}, {}

    def _build(self):
        self._clear_grid()
        snap = self.snapshot
        for key in sorted(snap.top):
            self.grid.addWidget(self._section(key))
        if snap.loose:
            loose = GroupSection(LOOSE, "In no group" if snap.groups else "Instances", self._folded(LOOSE))
            for key in sorted(snap.loose):
                loose.flow.addWidget(self._tile(key))
            loose.toggled.connect(self._fold)
            loose.dropped.connect(self.move_node)
            loose.space_menu.connect(self._space_menu)
            loose.menu.connect(self._space_menu)
            self.sections[LOOSE] = loose
            self.grid.addWidget(loose)
        if not snap.instances and not snap.groups:
            self.grid.addWidget(muted(f"No instances yet under {self.ws.instances_dir}. Add one with "
                                      "“Add Instance”, or right-click here."))
        self.grid.addStretch()
        self._update_tiles()
        self._filter(self.search.text())
        moved, self.just_moved = self.just_moved, None
        if moved:
            kind, _, key = moved.partition(":")
            anim.fade_in(self.tiles.get(key) if kind == "instance" else self.sections.get(key), 320)

    def _section(self, key):
        g = self.snapshot.groups[key]
        locked = "  ·  locked" if g.locked else ""
        if g.kind == groups.DEPENDENCY:
            # A small map: the leader on top, its guards hanging from it.
            n = len(g.instances) - 1
            sec = DependencySection(key, f"{key}  ({n} guard{'' if n == 1 else 's'}){locked}", self._folded(key))
            if g.leader in self.snapshot.instances:
                sec.set_leader(self._tile(g.leader))
            for k in g.instances:
                if k != g.leader and k in self.snapshot.instances:
                    sec.flow.addWidget(self._tile(k))
        else:
            sec = GroupSection(key, f"{key}  ({len(g.instances) + len(g.groups)}){locked}", self._folded(key))
            for k in g.instances:
                if k in self.snapshot.instances:
                    sec.flow.addWidget(self._tile(k))
        for k in g.groups:
            if k in self.snapshot.groups:
                sec.inner.addWidget(self._section(k))
        sec.toggled.connect(self._fold)
        sec.dropped.connect(self.move_node)
        sec.clicked.connect(lambda k: self.select(("group", k)))
        sec.menu.connect(self._group_menu)
        sec.space_menu.connect(self._space_menu)
        self.sections[key] = sec
        return sec

    def _tile(self, key):
        tile = InstanceTile(self.snapshot.instances[key])
        tile.clicked.connect(lambda k: self.select(("instance", k)))
        tile.menu.connect(self._instance_menu)
        tile.opened.connect(lambda k: self.edit_instance(k, "Logs"))
        self.tiles[key] = tile
        return tile

    def _update_tiles(self):
        for key, tile in self.tiles.items():
            view = self.snapshot.instances.get(key)
            if view:
                tile.update_view(view, self.tasks.busy(key))
            tile.set_selected(self.selected == ("instance", key))
        for key, sec in self.sections.items():
            sec.set_selected(self.selected == ("group", key))

    def _folded(self, key):
        return self.store.value(f"folded/{key}", False, type=bool)

    def _fold(self, key, folded):
        self.store.setValue(f"folded/{key}", folded)

    def _filter(self, text):
        text = text.strip().lower()
        for key, tile in self.tiles.items():
            v = self.snapshot.instances.get(key)
            tile.setVisible(not text or (v is not None and any(text in s.lower() for s in (v.key, v.name, v.server))))

    # --- the selected one, on the right ---------------------------------------------------------

    def select(self, what):
        changed = what != self.selected
        self.selected = what
        self._update_tiles()
        self._draw_side()
        if changed:
            anim.fade_in(self.side_content)

    def _clear_side(self):
        """A fresh panel, swapped in whole: the old one is hidden at once and
        deleted later, so the two are never drawn on top of each other."""
        if self.side_content is not None:
            self.side_content.hide()
            self.side_holder.removeWidget(self.side_content)
            self.side_content.deleteLater()
        self.side_content = QWidget()
        self.side_layout = QVBoxLayout(self.side_content)
        self.side_layout.setContentsMargins(10, 14, 10, 10)
        self.side_layout.setSpacing(1)
        self.side_holder.addWidget(self.side_content)

    def _draw_side(self):
        what = self.selected
        target = self._target(what)
        view = self.snapshot.instances.get(what[1]) if what and what[0] == "instance" else None
        group = self.snapshot.groups.get(what[1]) if what and what[0] == "group" else None
        signature = (what, view, group, self.tasks.busy(target) if target else None, theme.current["name"],
                     tuple(self.activity.get(target or "*", ())))
        if signature == self.side_signature:
            return
        self.side_signature = signature
        self._clear_side()
        s = self.side_layout
        if view is None and group is None:
            self.selected = None
            s.addWidget(title("Marionette"))
            s.addSpacing(4)
            s.addWidget(muted("Select an instance to see what can be done with it, or a group by its name. "
                              "Right-click works too, and a double click opens an instance's logs."))
            s.addStretch()
            self._recent(None)
            return
        # Prism's head: the big face, its name under it.
        face = QToolButton()
        face.setObjectName("face")
        face.setIconSize(QSize(72, 72))
        face.setIcon(QIcon(theme.avatar(view.bot or view.name if view else group.key, 72,
                                        image=icon_of(view) if view else None)))
        if view and view.bot:
            face.setCursor(Qt.PointingHandCursor)
            face.setToolTip(f"{view.bot}'s picture: click to change it")
            face.clicked.connect(lambda: self._face_menu(view.bot, face))
        s.addWidget(face, 0, Qt.AlignHCenter)
        name = title(what[1])
        name.setAlignment(Qt.AlignHCenter)
        s.addWidget(name)
        if view:
            line = muted(f"{view.name} on {view.server}\n{self._state_line(view)}")
        else:
            line = muted(f"{group.kind} group" + (", locked" if group.locked else ""))
        line.setAlignment(Qt.AlignHCenter)
        s.addWidget(line)
        s.addSpacing(10)
        actions = self._instance_actions(what[1]) if view else self._group_actions(what[1])
        self._side_buttons(actions)
        s.addStretch()
        self._recent(target)

    def _side_buttons(self, actions):
        """Flat buttons with an icon, Prism's; the first is Launch (or what
        stops what is running), with the extra ways to launch under its arrow."""
        s = self.side_layout
        extras = [a for a in actions if a is not SEPARATOR and a.extra]
        first = True
        for a in actions:
            if a is SEPARATOR:
                line = QFrame()
                line.setObjectName("sideLine")
                line.setFrameShape(QFrame.HLine)
                s.addSpacing(4)
                s.addWidget(line)
                s.addSpacing(4)
                continue
            if a.extra:
                continue
            b = self._side_button(a)
            if first and extras:
                b.setObjectName("primary")
                row = QHBoxLayout()
                row.setSpacing(0)
                row.addWidget(b, 1)
                more = QToolButton()
                more.setObjectName("more")
                more.setArrowType(Qt.DownArrow)
                more.setPopupMode(QToolButton.InstantPopup)
                more.setToolTip("Other ways to launch it")
                more.setMenu(self._menu_of(extras))
                more.setFixedHeight(b.sizeHint().height())
                row.addWidget(more)
                s.addLayout(row)
            else:
                if first:
                    b.setObjectName("primary")
                s.addWidget(b)
            first = False

    def _side_button(self, a):
        b = QPushButton(a.text)
        icons.set_on(b, a.icon)
        b.setIconSize(QSize(18, 18))
        b.setEnabled(a.enabled)
        b.setToolTip(a.tip)
        b.setCursor(Qt.PointingHandCursor)
        b.clicked.connect(a.run)
        return b

    def _recent(self, target):
        lines = list(self.activity.get(target, ())) if target else list(self.activity.get("*", ()))
        if not lines:
            return
        self.side_layout.addWidget(muted("Recent"))
        lst = QListWidget()
        lst.setObjectName("recent")
        lst.setMaximumHeight(170)
        lst.setWordWrap(True)
        lst.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        lst.addItems(lines[-40:])
        lst.scrollToBottom()
        self.side_layout.addWidget(lst)

    def _state_line(self, view):
        busy = self.tasks.busy(view.key)
        if busy:
            return f"● {busy}…"
        return {"in": "● in the server · bridge on", "mute": "● in the server, but no bridge: it is mute",
                "loading": "● a game is running, not in the server", "stopped": "○ stopped"}[view.state]

    @staticmethod
    def _target(what):
        if not what:
            return None
        return what[1] if what[0] == "instance" else f"group {what[1]}"

    # --- what can be done -----------------------------------------------------------------------

    def _instance_actions(self, key):
        """Prism's column: Launch (with Restart, Connect again and Start its
        bridge under its arrow), Kill, then Edit, Change Group, Folder, Copy,
        Delete; each enabled when it makes sense in the state it is in."""
        view = self.snapshot.instances[key]
        inst = self.ws.instance(key)
        busy = self.tasks.busy(key)
        running = bool(view.client or view.bridge)
        guards = [g.key for g in inst.guards()]
        out = []
        if busy:
            out.append(Action(f"Cancel ({busy})", "cancel", lambda: self.tasks.cancel(key)))
        else:
            out.append(Action("Launch", "play", lambda: self.launch(key), not running,
                              tip="Start its game and, once it is in, its bridge"))
        out += [
            Action("Restart", "restart", lambda: self._run(key, "restarting",
                                                           lambda ev, c: operations.restart(inst, ev, c)),
                   not busy, extra=True),
            Action("Connect again", "connect", lambda: self._run(key, "connecting",
                                                                 lambda ev, c: operations.connect(inst, ev, c)),
                   bool(not busy and view.client and not view.inside), extra=True),
            Action("Start its bridge", "bridge", lambda: self._run(key, "starting its bridge",
                                                                   lambda ev, c: operations.start_bridge(inst, ev)),
                   not busy and view.state == "mute", extra=True),
            Action("Kill", "stop", lambda: self.kill(key), not busy and running,
                   tip="Stop its game and its bridge" + (f", and its guards ({', '.join(guards)})" if guards else "")),
            SEPARATOR,
            Action("Edit", "edit", lambda: self.edit_instance(key), tip="Settings, rules, personality, mods, logs"),
            Action("Change Group", "move", lambda: self._dialog(dialogs.MoveDialog(self, inst))),
            Action("Folder", "folder", lambda: self._folder(inst.dir)),
            Action("Copy", "copy", lambda: self._dialog(dialogs.CloneDialog(self, inst)),
                   tip="The same bot again, here or on another server"),
            Action("Delete", "delete", lambda: self.delete_instance(key), not busy and not running),
        ]
        if settings.get(inst, "account") == "online":
            out.append(Action("Log its account in", "key", lambda: self._login(inst), not running))
        return out

    def _group_actions(self, key):
        group = self.ws.group(key)
        target = f"group {key}"
        busy = self.tasks.busy(target)
        ws = self.ws
        out = []
        if busy:
            out.append(Action(f"Cancel ({busy})", "cancel", lambda: self.tasks.cancel(target)))
        else:
            out.append(Action("Launch all", "play", lambda: self._run(
                target, "starting", lambda ev, c: operations.start_group(ws, key, ev, c)),
                tip="Everything in it, leaders before their guards"))
        out += [
            Action("Kill all", "stop", lambda: self._run(target, "stopping",
                                                         lambda ev, c: operations.stop_group(ws, key, ev)),
                   not busy),
            SEPARATOR,
            Action("Edit", "edit", lambda: self._dialog(dialogs.EditGroupDialog(self, group)),
                   tip="Its settings and rules, for everything in it"),
            Action("Add to it", "plus", lambda: self._dialog(dialogs.AddToGroupDialog(self, group)),
                   tip="Instances or groups that are in none" if group.kind == groups.NORMAL
                   else "Guards: instances on its leader's server"),
        ]
        if group.kind == groups.NORMAL:
            out.append(Action("Add instance here", "bot", lambda: self.new_instance(group=key),
                              tip="A new instance, straight into this group"))
        out += [
            Action("Change Group", "move", lambda: self._dialog(dialogs.MoveDialog(self, group))),
            Action("Folder", "folder", lambda: self._folder(group.dir)),
            Action("Copy", "copy", lambda: self._run(target, "cloning",
                                                     lambda ev, c: operations.clone_group(ws, key, on_event=ev)),
                   not busy, tip="Clone it, and everything in it"),
            Action("Delete", "delete", lambda: self._delete_group(key), not busy,
                   tip="The group only: what is in it stays, in no group"),
        ]
        return out

    def launch(self, key):
        inst = self.ws.instance(key)
        self._run(key, "starting", lambda ev, c: operations.bring_up(inst, ev, c))

    def kill(self, key):
        inst = self.ws.instance(key)
        self._run(key, "stopping", lambda ev, c: operations.stop(inst, on_event=ev))

    def edit_instance(self, key, start=None):
        """Prism's Edit Instance, beside the main window rather than over it:
        one per instance, brought forward when it is open already."""
        editor = self.editors.get(key)
        if editor is not None:
            if start:
                editor.page(start)
            editor.raise_()
            editor.activateWindow()
            return editor
        editor = dialogs.EditInstanceDialog(self, self.ws.instance(key), start=start)
        editor.setAttribute(Qt.WA_DeleteOnClose)
        editor.destroyed.connect(lambda _=None, k=key: self.editors.pop(k, None))
        editor.finished.connect(lambda _=None: self.refresh())
        self.editors[key] = editor
        editor.show()
        return editor

    def delete_instance(self, key):
        if not ask(self, "Delete", f"Delete the instance {key}, its game folder and all (what it keeps about "
                                   "its world, its logs, its extra mods)? Its bot stays. This cannot be undone."):
            return
        try:
            operations.delete_instance(self.ws, key, on_event=self.say)
        except Fail as e:
            self.fail(e)
        if self.selected == ("instance", key):
            self.selected = None
        self.refresh()

    def _delete_group(self, key):
        if not ask(self, "Delete", f"Delete the group {key}? What is in it stays, in no group."):
            return
        try:
            operations.delete_group(self.ws, key, on_event=self.say)
        except Fail as e:
            self.fail(e)
        self.selected = None
        self.refresh()

    def _dialog(self, dialog):
        dialog.exec()
        self.refresh()

    def _run(self, target, title, fn):
        if not self.tasks.run(target, title, fn):
            self.alert("Busy", f"{target} is busy: {self.tasks.busy(target)}")

    def _login(self, inst):
        try:
            argv, cwd, env = operations.login_command(inst)
        except Fail as e:
            self.fail(e)
            return
        ConsoleDialog(self, f"Log in the account of {inst.key}",
                      "HeadlessMC: press `login`, open the link in a browser and sign in; when it says the account "
                      "is saved, press `quit`.", argv, cwd, env, lambda code: self.refresh()).exec()

    # --- right-click menus ----------------------------------------------------------------------

    def _menu_of(self, actions):
        menu = QMenu(self)
        for a in actions:
            if a is SEPARATOR:
                menu.addSeparator()
                continue
            item = menu.addAction(icons.icon(a.icon), a.text)
            item.setEnabled(a.enabled)
            item.triggered.connect(a.run)
        return menu

    def _instance_menu(self, key, pos):
        self._menu_of(self._instance_actions(key)).exec(pos)

    def _group_menu(self, key, pos):
        self.select(("group", key))
        self._menu_of(self._group_actions(key)).exec(pos)

    def _space_actions(self, key):
        """Right-click on a group's empty space: add an instance or a group
        right there. On the background, or among the instances in no group:
        the same, in no group."""
        group = self.snapshot.groups.get(key)
        loose = [Action("Add Instance…", "plus", lambda: self.new_instance()),
                 Action("Add Group…", "group", lambda: self.new_group())]
        if group is None:
            return loose
        if group.kind == groups.NORMAL:
            here = [Action(f"Add instance to {key}…", "plus", lambda: self.new_instance(group=key)),
                    Action(f"Add group inside {key}…", "group", lambda: self.new_group(inside=key))]
        else:
            here = [Action(f"Add guards to {key}…", "plus",
                           lambda: self._dialog(dialogs.AddToGroupDialog(self, self.ws.group(key))))]
        return here + [SEPARATOR, Action("Add Instance (in no group)…", "plus", lambda: self.new_instance())]

    def _space_menu(self, key, pos):
        self._menu_of(self._space_actions(key)).exec(pos)

    # --- moving by dragging, and a bot's picture ------------------------------------------------

    def move_node(self, ref, to):
        """What was dropped ("instance:alice", "group:team") into the group
        `to` ("" for none). A leader or a guard stays with its dependency
        group: that group moves. When it cannot go there it stays where it was,
        and why is said."""
        kind, _, key = ref.partition(":")
        ws = self.ws
        node = Instance(ws, key) if kind == "instance" else ws.group(key)
        if not node.exists():
            return
        here = groups.parent_of(ws, node)
        if isinstance(node, Instance) and here is not None and here.kind == groups.DEPENDENCY:
            if to == here.key:
                return
            node, here = here, groups.parent_of(ws, here)
            ref = f"group:{node.key}"
        if to == (here.key if here else LOOSE) or (isinstance(node, groups.Group) and to == node.key):
            return
        try:
            if here is not None:
                operations.group_remove(ws, here.key, [ref], on_event=self.say)
            if to:
                try:
                    operations.group_add(ws, to, [ref], on_event=self.say)
                except Fail:
                    if here is not None:            # back where it was
                        operations.group_add(ws, here.key, [ref], on_event=self.say)
                    raise
        except Fail as e:
            self.fail(e, "It cannot go there")
        else:
            self.just_moved = ref
        self.refresh()

    def _face_menu(self, bot, button):
        menu = QMenu(self)
        menu.addAction("Choose a picture…", lambda: self._pick_face(bot))
        menu.addAction("Paste a copied picture", lambda: self.set_face(bot, QApplication.clipboard().image()))
        if self.ws.bot(bot).dir.joinpath("icon.png").is_file():
            menu.addAction("Remove the picture", lambda: self.set_face(bot, None))
        menu.exec(button.mapToGlobal(button.rect().bottomLeft()))

    def _pick_face(self, bot):
        path, _ = QFileDialog.getOpenFileName(self, f"A picture for {bot}", str(self.ws.home),
                                              "Pictures (*.png *.jpg *.jpeg *.webp *.gif *.bmp)")
        if path:
            self.set_face(bot, QImage(path))

    def set_face(self, bot, image):
        """A bot's picture: bots/<bot>/icon.png, cut down to 256 pixels; None
        takes it away (its letter again)."""
        target = self.ws.bot(bot).dir / "icon.png"
        if image is None:
            target.unlink(missing_ok=True)
        elif image.isNull():
            self.alert("No picture", "There is no picture there (for pasting: copy an image first).")
            return
        else:
            image.scaled(256, 256, Qt.KeepAspectRatio, Qt.SmoothTransformation).save(str(target), "PNG")
        self.side_signature = None
        self.refresh()

    def _folder(self, path):
        menu = QMenu(self)
        menu.addAction(icons.icon("folder"), "Open it in the file manager", lambda: self.open_folder(path))
        menu.addAction(icons.icon("copy"), f"Copy its path ({path})", lambda: self.copy_path(path))
        menu.exec(self.cursor().pos())

    # --- the launcher's own settings ---------------------------------------------------------

    def refresh_seconds(self):
        return max(1, min(60, int(self.store.value("refresh/seconds", REFRESH_MS // 1000))))

    def set_refresh_seconds(self, seconds):
        self.store.setValue("refresh/seconds", int(seconds))
        self.timer.setInterval(self.refresh_seconds() * 1000)

    def set_animations(self, on):
        """Motion on or off, at once and for the next time."""
        anim.enabled = bool(on)
        self.store.setValue("appearance/animations", bool(on))

    def set_animated_icons(self, on):
        """Icons that make their gesture when hovered, and dots that pulse
        while their instance works: on or off, at once and for the next time."""
        icons.animated = bool(on)
        self.store.setValue("appearance/animated_icons", bool(on))
        self._update_tiles()

    def set_style(self, name, keep=True):
        """A colour preset, applied to the open window at once; with `keep`,
        remembered for the next time."""
        name = theme.apply(QApplication.instance(), name)
        if keep:
            self.store.setValue("appearance/style", name)
        for tile in self.tiles.values():
            tile.face = None
        self._update_tiles()
        for sec in self.sections.values():
            sec.update()
        for text, b in self.bar_buttons.items():
            icons.set_on(b, _BAR_ICONS[text])
        icons.set_on(self.toolbar.widgetForAction(self.quit_action), "quit")
        self.side_signature = None
        self._draw_side()

    # --- what operations say --------------------------------------------------------------------

    def _note(self, target, text):
        line = f"{time.strftime('%H:%M')}  {text}"
        self.activity[target].append(line)
        self.activity["*"].append(f"{time.strftime('%H:%M')}  {target}: {text}")

    def _event(self, target, e):
        self._note(target, e.text)
        if e.kind != "detail":
            self.statusBar().showMessage(f"{target}: {e.text}", 8000)
        self._draw_side()

    def _done(self, target, title, result):
        self._note(target, f"{title}: done")
        self.statusBar().showMessage(f"{target}: {title}, done", 8000)
        self.refresh()

    def _failed(self, target, title, e):
        if isinstance(e, Cancelled):
            self._note(target, f"{title}: cancelled")
            self.statusBar().showMessage(f"{target}: {title}, cancelled", 8000)
        else:
            self._note(target, f"{title} failed: {e}")
            self.fail(e, f"{target}: {title}")
        self.refresh()

    def _tasks_changed(self):
        self._update_tiles()
        self._draw_side()

    def _status(self):
        snap = self.snapshot
        views = snap.instances.values()
        text = (f"{len(snap.instances)} instance(s) · {sum(v.state == 'in' for v in views)} in their server · "
                f"{sum(v.state == 'loading' for v in views)} loading")
        if snap.problems:
            text += "      " + "   ".join(snap.problems)
        running = self.tasks.running()
        if running:
            text += "      working: " + ", ".join(f"{t} ({title})" for t, title in running.items())
        self.statusBar().showMessage(text)

    # --- for the dialogs ------------------------------------------------------------------------

    def say(self, e):
        """An on_event for operations run right here (quick ones)."""
        self.activity["*"].append(f"{time.strftime('%H:%M')}  {e.text}")
        if e.kind != "detail":
            self.statusBar().showMessage(e.text, 8000)
        self.refresh()

    def say_text(self, text):
        self.activity["*"].append(f"{time.strftime('%H:%M')}  {text}")
        self.statusBar().showMessage(text, 8000)
        self.refresh()

    def alert(self, heading, text):
        MessageBox(QMessageBox.Information, heading, text, QMessageBox.Ok, self).exec()

    def fail(self, e, heading="It did not work"):
        lines = getattr(e, "lines", ()) or ()
        trace = getattr(e, "trace", "")
        box = MessageBox(QMessageBox.Warning, heading, str(e), QMessageBox.Ok, self)
        if lines or trace:
            box.setDetailedText("\n".join(lines) + ("\n\n" + trace if trace else ""))
        box.exec()


_BAR_ICONS = {"Add Instance": "plus", "Folders": "folder", "Settings": "gear", "Help": "help", "Bots": "bot",
              "Accounts": "account"}

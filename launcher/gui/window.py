"""The main window: every instance, by group, and what can be done with the
one selected."""
import collections
import time

from PySide6.QtCore import QSettings, QSize, Qt, QTimer, QUrl
from PySide6.QtGui import QAction, QDesktopServices, QIcon, QImage, QKeySequence
from PySide6.QtWidgets import (QApplication, QFileDialog, QFrame, QHBoxLayout, QLineEdit, QListWidget,
                               QMainWindow, QMenu, QMessageBox, QPushButton, QScrollArea, QSizePolicy, QStatusBar,
                               QToolBar, QToolButton, QVBoxLayout, QWidget)

from .. import groups, operations, settings
from ..bots import Instance
from ..events import Cancelled, Fail
from . import dialogs, state, theme
from .tasks import Background, Tasks
from .widgets import DependencySection, GroupSection, InstanceTile, icon_of

REFRESH_MS = 3000
LOOSE = ""            # the section of the instances in no group


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
        # What folds, the style, how often it looks: remembered between runs (a
        # test hands in a file of its own).
        self.store = store or QSettings("Marionette", "launcher")
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
        self.grid = QVBoxLayout(self.page)
        self.grid.setContentsMargins(12, 8, 12, 12)
        self.grid.setSpacing(6)
        scroll = QScrollArea()
        scroll.setWidget(self.page)
        scroll.setWidgetResizable(True)
        outer.addWidget(scroll, 1)
        self.side = QFrame()
        self.side.setObjectName("side")
        self.side.setFixedWidth(270)
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

    # --- the parts -----------------------------------------------------------------

    def _toolbar(self):
        bar = QToolBar()
        bar.setMovable(False)
        self.addToolBar(bar)

        self.toolbar = bar

        def act(text, fn, tip=""):
            a = QAction(text, self)
            a.setToolTip(tip or text)
            a.triggered.connect(fn)
            bar.addAction(a)
            return a

        act("+  New instance", lambda: dialogs.NewInstanceDialog(self).exec(), "A bot on a server")
        act("+  New group", lambda: dialogs.NewGroupDialog(self).exec(),
            "Instances started together, or a leader and its guards")
        act("Bots", lambda: dialogs.BotsDialog(self).exec(), "The characters: settings, rules, personality")
        act("Accounts", lambda: dialogs.AccountsDialog(self).exec(), "Minecraft accounts, logged in once")
        act("Servers", lambda: dialogs.ServersDialog(self).exec(), "The servers and their client packs")
        glob = QToolButton()
        glob.setText("Global")
        glob.setToolTip("What is imposed on every instance")
        glob.setPopupMode(QToolButton.InstantPopup)
        menu = QMenu(glob)
        menu.addAction("Global settings…", lambda: dialogs.SettingsDialog(self, self.ws.global_config()).exec())
        menu.addAction("Global rules…", lambda: dialogs.RulesDialog(self, "global").exec())
        glob.setMenu(menu)
        bar.addWidget(glob)
        act("Doctor", lambda: dialogs.DoctorDialog(self).exec(), "Check the machine, the folders and the servers")
        act("Launcher", lambda: dialogs.LauncherSettingsDialog(self).exec(), "The launcher's own settings: its style")
        spacer = QWidget()
        spacer.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)
        bar.addWidget(spacer)
        self.search = QLineEdit()
        self.search.setPlaceholderText("Search instances…")
        self.search.setClearButtonEnabled(True)
        self.search.setFixedWidth(230)
        self.search.textChanged.connect(self._filter)
        bar.addWidget(self.search)
        quit_ = act("Quit", self.close, "Close the launcher (Ctrl+Q). The bots keep running.")
        quit_.setShortcut(QKeySequence("Ctrl+Q"))

    # --- reading --------------------------------------------------------------------

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

    # --- the grid --------------------------------------------------------------------

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
            self.sections[LOOSE] = loose
            self.grid.addWidget(loose)
        if not snap.instances and not snap.groups:
            empty = dialogs.muted(f"No instances yet under {self.ws.instances_dir}. Create one with "
                                  "“+ New instance”.")
            self.grid.addWidget(empty)
        self.grid.addStretch()
        self._update_tiles()
        self._filter(self.search.text())

    def _section(self, key):
        g = self.snapshot.groups[key]
        n = len(g.instances)
        locked = "   ·   locked" if g.locked else ""
        if g.kind == groups.DEPENDENCY:
            # A small map: the leader on top, its guards hanging from it.
            sec = DependencySection(key, f"{key}   ·   {n - 1} guard(s){locked}", self._folded(key))
            if g.leader in self.snapshot.instances:
                sec.set_leader(self._tile(g.leader))
            for k in g.instances:
                if k != g.leader and k in self.snapshot.instances:
                    sec.flow.addWidget(self._tile(k))
        else:
            what = f"{n} instance(s)" + (f", {len(g.groups)} group(s)" if g.groups else "")
            sec = GroupSection(key, f"{key}   ·   {what}{locked}", self._folded(key))
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
        self.sections[key] = sec
        return sec

    def _tile(self, key):
        tile = InstanceTile(self.snapshot.instances[key])
        tile.clicked.connect(lambda k: self.select(("instance", k)))
        tile.menu.connect(self._instance_menu)
        tile.opened.connect(lambda k: dialogs.LogDialog(self, self.ws.instance(k)).show())
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

    # --- selection and the side panel ------------------------------------------------------

    def select(self, what):
        self.selected = what
        self._update_tiles()
        self._draw_side()

    def _clear_side(self):
        """A fresh panel, swapped in whole: the old one is hidden at once and
        deleted later, so the two are never drawn on top of each other."""
        if self.side_content is not None:
            self.side_content.hide()
            self.side_holder.removeWidget(self.side_content)
            self.side_content.deleteLater()
        self.side_content = QWidget()
        self.side_layout = QVBoxLayout(self.side_content)
        self.side_layout.setContentsMargins(14, 14, 14, 14)
        self.side_layout.setSpacing(6)
        self.side_holder.addWidget(self.side_content)

    def _draw_side(self):
        what = self.selected
        target = self._target(what)
        view = self.snapshot.instances.get(what[1]) if what and what[0] == "instance" else None
        group = self.snapshot.groups.get(what[1]) if what and what[0] == "group" else None
        signature = (what, view, group, self.tasks.busy(target) if target else None,
                     tuple(self.activity.get(target, ())) if target else ())
        if signature == self.side_signature:
            return
        self.side_signature = signature
        self._clear_side()
        s = self.side_layout
        if view is None and group is None:
            self.selected = None
            s.addWidget(dialogs.title("Marionette"))
            s.addWidget(dialogs.muted("Select an instance, or a group by its name, to see what can be done with "
                                      "it. Right-click works too; a double click opens its logs."))
            s.addStretch()
            self._recent(None)
            return
        head = QHBoxLayout()
        face = QToolButton()
        face.setObjectName("face")
        face.setIconSize(QSize(56, 56))
        face.setIcon(QIcon(theme.avatar(view.bot or view.name if view else group.key, 56,
                                        image=icon_of(view) if view else None)))
        if view and view.bot:
            face.setCursor(Qt.PointingHandCursor)
            face.setToolTip(f"{view.bot}'s picture: click to change it")
            face.clicked.connect(lambda: self._face_menu(view.bot, face))
        head.addWidget(face)
        names = QVBoxLayout()
        names.addWidget(dialogs.title(what[1]))
        if view:
            names.addWidget(dialogs.muted(f"{view.name} on {view.server}\n{self._state_line(view)}"))
        else:
            names.addWidget(dialogs.muted(f"{group.kind} group" + (", locked" if group.locked else "")))
        head.addLayout(names, 1)
        s.addLayout(head)
        s.addSpacing(6)
        actions = self._instance_actions(what[1]) if view else self._group_actions(what[1])
        first = True
        for text, fn, enabled in actions:
            if text is None:
                s.addSpacing(6)
                continue
            b = QPushButton(text)
            if first:
                b.setObjectName("primary")
                first = False
            b.setEnabled(enabled)
            b.clicked.connect(fn)
            s.addWidget(b)
        s.addStretch()
        self._recent(target)

    def _recent(self, target):
        lines = list(self.activity.get(target, ())) if target else list(self.activity.get("*", ()))
        if not lines:
            return
        self.side_layout.addWidget(dialogs.muted("Recent"))
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

    # --- what can be done ---------------------------------------------------------------------

    def _instance_actions(self, key):
        """(label, what it does, enabled), the first being the main one: what
        makes sense in the state it is in."""
        view = self.snapshot.instances[key]
        inst = self.ws.instance(key)
        busy = self.tasks.busy(key)
        running = view.client or view.bridge
        out = []
        if busy:
            out.append((f"✕  Cancel ({busy})", lambda: self.tasks.cancel(key), True))
        elif running:
            guards = [g.key for g in inst.guards()]
            out.append(("■  Stop" + (" (and its guards)" if guards else ""), lambda: self._run(
                key, "stopping", lambda ev, c: operations.stop(inst, on_event=ev)), True))
        else:
            out.append(("▶  Start", lambda: self._run(key, "starting",
                                                      lambda ev, c: operations.bring_up(inst, ev, c)), True))
        out += [
            ("⟳  Restart", lambda: self._run(key, "restarting", lambda ev, c: operations.restart(inst, ev, c)),
             not busy),
            ("⇄  Connect again", lambda: self._run(key, "connecting", lambda ev, c: operations.connect(inst, ev, c)),
             not busy and view.client and not view.inside),
            ("♪  Start its bridge", lambda: self._run(key, "starting its bridge",
                                                      lambda ev, c: operations.start_bridge(inst, ev)),
             not busy and view.state == "mute"),
            (None, None, None),
            ("☰  Rules…", lambda: dialogs.RulesDialog(self, "instance", inst).exec(), True),
            ("⚙  Settings…", lambda: dialogs.SettingsDialog(self, inst).exec(), True),
            ("✎  Personality…", lambda: dialogs.PersonalityDialog(self, inst.bot).exec(), inst.bot.exists()),
            ("⊞  Clone…", lambda: dialogs.CloneDialog(self, inst).exec(), True),
            ("⌂  Change group…", lambda: dialogs.MoveDialog(self, inst).exec(), True),
        ]
        if settings.get(inst, "account") == "online":
            out.append(("⚿  Log its account in…", lambda: self._login(inst), not running))
        out += [
            ("▤  Its folder…", lambda: self._folder(inst), True),
            ("≡  Logs", lambda: dialogs.LogDialog(self, inst).show(), True),
        ]
        return out

    def _group_actions(self, key):
        group = self.ws.group(key)
        target = f"group {key}"
        busy = self.tasks.busy(target)
        ws = self.ws
        out = []
        if busy:
            out.append((f"✕  Cancel ({busy})", lambda: self.tasks.cancel(target), True))
        else:
            out.append(("▶  Start everything in it", lambda: self._run(
                target, "starting", lambda ev, c: operations.start_group(ws, key, ev, c)), True))
        out += [
            ("■  Stop everything in it", lambda: self._run(target, "stopping",
                                                           lambda ev, c: operations.stop_group(ws, key, ev)),
             not busy),
            (None, None, None),
            ("+  Add to it…", lambda: dialogs.AddToGroupDialog(self, group).exec(), True),
            ("☰  Rules…", lambda: dialogs.RulesDialog(self, "group", group).exec(), True),
            ("⚙  Settings…", lambda: dialogs.SettingsDialog(self, group).exec(), True),
            ("⊞  Clone it, and all in it", lambda: self._run(
                target, "cloning", lambda ev, c: operations.clone_group(ws, key, on_event=ev)), not busy),
            ("⌂  Change group…", lambda: dialogs.MoveDialog(self, group).exec(), True),
            ("✕  Delete the group", lambda: self._delete_group(key), not busy),
        ]
        return out

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

    def _folder(self, inst):
        """Opening a folder needs a file manager where the launcher runs,
        which a machine without a screen (reached through waypipe) has not:
        its path can be copied instead."""
        menu = QMenu(self)
        menu.addAction("Open it in the file manager",
                       lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(inst.dir))))
        menu.addAction(f"Copy its path ({inst.dir})", lambda: (QApplication.clipboard().setText(str(inst.dir)),
                                                               self.say_text(f"copied: {inst.dir}")))
        menu.exec(self.cursor().pos())

    def toolbar_actions(self):
        return self.toolbar.actions()

    # --- the launcher's own settings ---------------------------------------------------------

    def refresh_seconds(self):
        return max(1, min(60, int(self.store.value("refresh/seconds", REFRESH_MS // 1000))))

    def set_refresh_seconds(self, seconds):
        self.store.setValue("refresh/seconds", int(seconds))
        self.timer.setInterval(self.refresh_seconds() * 1000)

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
        self.side_signature = None
        self._draw_side()

    def _instance_menu(self, key, pos):
        self._menu(self._instance_actions(key), pos)

    def _group_menu(self, key, pos):
        self.select(("group", key))
        self._menu(self._group_actions(key), pos)

    def _menu(self, actions, pos):
        menu = QMenu(self)
        for text, fn, enabled in actions:
            if text is None:
                menu.addSeparator()
                continue
            a = menu.addAction(text)
            a.setEnabled(enabled)
            a.triggered.connect(fn)
        menu.exec(pos)

    def _run(self, target, title, fn):
        if not self.tasks.run(target, title, fn):
            self.alert("Busy", f"{target} is busy: {self.tasks.busy(target)}")

    def _delete_group(self, key):
        if not dialogs.ask(self, "Delete", f"Delete the group {key}? What is in it stays, in no group."):
            return
        try:
            operations.delete_group(self.ws, key, on_event=self.say)
        except Fail as e:
            self.fail(e)
        self.selected = None
        self.refresh()

    def _login(self, inst):
        try:
            argv, cwd, env = operations.login_command(inst)
        except Fail as e:
            self.fail(e)
            return
        dialogs.ConsoleDialog(self, f"Log in the account of {inst.key}",
                              "HeadlessMC: press `login`, open the link in a browser and sign in; when it says "
                              "the account is saved, press `quit`.", argv, cwd, env,
                              lambda code: self.refresh()).exec()

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
        dialogs.MessageBox(QMessageBox.Information, heading, text, QMessageBox.Ok, self).exec()

    def fail(self, e, heading="It did not work"):
        lines = getattr(e, "lines", ()) or ()
        trace = getattr(e, "trace", "")
        box = dialogs.MessageBox(QMessageBox.Warning, heading, str(e), QMessageBox.Ok, self)
        if lines or trace:
            box.setDetailedText("\n".join(lines) + ("\n\n" + trace if trace else ""))
        box.exec()


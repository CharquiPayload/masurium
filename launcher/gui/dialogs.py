"""The dialogs: rules, settings, a new instance, groups, cloning, the
personality, logs, doctor, accounts, servers and bots.

A dialog decides nothing the command line would decide differently: it
gathers what to do and hands it to the same operations, run as a task of the
main window, whose events land in the window's activity list.
"""
from PySide6.QtCore import QProcess, QProcessEnvironment, Qt, QTimer
from PySide6.QtGui import QColor, QFontDatabase, QIcon
from PySide6.QtWidgets import (QAbstractItemView, QComboBox, QDialog, QDialogButtonBox, QFormLayout,
                               QHBoxLayout, QHeaderView, QLabel, QLineEdit, QListWidget, QListWidgetItem,
                               QMessageBox, QPlainTextEdit, QPushButton, QRadioButton, QSpinBox, QTableWidget,
                               QTableWidgetItem, QTabWidget, QVBoxLayout, QWidget)

from .. import accounts, doctor, groups, operations, rules, settings
from ..bots import Instance
from ..events import Fail
from ..files import tail_lines
from . import theme
from .widgets import Switch, switch_row


class Dialog(QDialog):
    """Every dialog: a shade lighter than the main window, with a lavender
    edge, so it stands out from what it opened over."""

    def paintEvent(self, event):
        super().paintEvent(event)
        theme.frame(self)


class MessageBox(QMessageBox):
    def paintEvent(self, event):
        super().paintEvent(event)
        theme.frame(self)


def close_row(dialog):
    """A Close button: some window managers draw no title bar to close from."""
    buttons = QDialogButtonBox(QDialogButtonBox.Close)
    buttons.rejected.connect(dialog.reject)
    return buttons


def ask(parent, heading, text):
    """A yes or no, framed like the rest."""
    box = MessageBox(QMessageBox.Question, heading, text, QMessageBox.Yes | QMessageBox.No, parent)
    return box.exec() == QMessageBox.Yes


def muted(text):
    lab = QLabel(text)
    lab.setObjectName("muted")
    lab.setWordWrap(True)
    return lab


def title(text):
    lab = QLabel(text)
    lab.setObjectName("title")
    return lab


def monospace():
    return QFontDatabase.systemFont(QFontDatabase.FixedFont)


# --- rules ------------------------------------------------------------------------

class RulesDialog(Dialog):
    """A bot's rules as they come out, each thing with who decides it, and
    changes to one layer: an instance's own (kept on its server), or a bot's,
    a server's, a group's or the global ones (kept by the launcher). The
    changes are collected, shown as they would come out, and sent with Apply;
    what is imposed from above cannot be touched, and says by whom.

    `kind` is "instance", "bot", "server", "group" or "global"."""

    def __init__(self, win, kind, obj=None):
        super().__init__(win)
        self.win, self.ws, self.kind, self.obj = win, win.ws, kind, obj
        self.changes = []
        self.base = self.own = self.imposed = rules.empty()
        self.setWindowTitle("Rules")
        self.resize(760, 580)
        v = QVBoxLayout(self)
        self.head = title(self._title())
        v.addWidget(self.head)
        v.addWidget(muted("“Set by” says where each value comes from: default (what every bot starts with), "
                          "the bot's config, this instance, or a group or the global config, which lock it."))
        self.note = muted("")
        v.addWidget(self.note)
        self.tabs = QTabWidget()
        self.toggles = QTableWidget(len(rules.TOGGLES), 3)
        self.toggles.setHorizontalHeaderLabels(["", "set by", ""])
        self.toggles.verticalHeader().hide()
        self.toggles.horizontalHeader().setSectionResizeMode(1, QHeaderView.Stretch)
        self.toggles.setColumnWidth(0, 240)
        self.toggles.setColumnWidth(2, 110)
        self.toggles.setSelectionMode(QAbstractItemView.NoSelection)
        self.toggles.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.tabs.addTab(self.toggles, "Toggles")
        self.lists = {}
        for family, label in (("food", "Food"), ("break", "Blocks")):
            self.tabs.addTab(self._list_tab(family), label)
        v.addWidget(self.tabs, 1)
        self.pending = muted("")
        v.addWidget(self.pending)
        buttons = QDialogButtonBox(QDialogButtonBox.Apply | QDialogButtonBox.Cancel)
        self.apply_button = buttons.button(QDialogButtonBox.Apply)
        self.apply_button.setObjectName("primary")
        self.apply_button.clicked.connect(self._apply)
        buttons.rejected.connect(self.reject)
        v.addWidget(buttons)
        self.setEnabled(False)
        self._load()

    def _title(self):
        if self.kind == "instance":
            return f"Rules of {self.obj.key} · {self.obj.name} on {self.obj.slug}"
        if self.kind == "global":
            return "Global rules · imposed on every instance"
        return f"Rules of the {self.kind} {self.obj if isinstance(self.obj, str) else self.obj.key}"

    def _list_tab(self, family):
        on, off, _ = rules.FAMILIES[family]
        w = QWidget()
        v = QVBoxLayout(w)
        v.addWidget(muted("Food it does not eat on its own (handed to it by name, it still eats it)."
                          if family == "food" else
                          "Blocks it may break on its own, to make its way. What it is told to dig needs "
                          "no permission."))
        lst = QListWidget()
        v.addWidget(lst, 1)
        row = QHBoxLayout()
        field = QLineEdit()
        field.setPlaceholderText("an id, like rotten_flesh" if family == "food" else "an id, like oak_log")
        row.addWidget(field, 1)
        buttons = {}
        for verb in (on, off, "default"):
            b = QPushButton(verb.capitalize() if verb != "default" else "Back to default")
            b.clicked.connect(lambda _=False, f=family, verb=verb: self._list_change(f, verb))
            row.addWidget(b)
            buttons[verb] = b
        v.addLayout(row)
        row, replace = switch_row("This list replaces what is under it (" + (
            "the golden apples every bot starts with, and the layers under this one)" if family == "food"
            else "the blocks every bot starts with, and the layers under this one)"))
        replace.toggled.connect(lambda checked, f=family: self._replace(f, checked))
        v.addWidget(row)
        lst.currentItemChanged.connect(
            lambda item, _prev, fld=field: fld.setText(item.data(Qt.UserRole)) if item else None)
        self.lists[family] = (lst, field, replace, buttons)
        return w

    # loading and drawing

    def _load(self):
        def read():
            if self.kind != "instance":
                layer = {"bot": lambda: rules.of_bot(self.obj), "server": lambda: rules.of_server(self.ws, self.obj),
                         "group": lambda: rules.of_group(self.obj), "global": lambda: rules.of_global(self.ws)}
                return rules.empty(), layer[self.kind](), rules.empty(), ""
            inst = self.obj
            base, imposed = rules.base_of(inst), rules.imposed_of(inst)
            waiting = len(rules.pending(inst))
            try:
                own = rules.layers_in(rules.ask(self.ws.api_for(self.ws.server(inst.slug)), inst.name))[1]
                note = ""
            except rules.NotKnownYet:
                own, note = rules.empty(), "Its server has not seen it yet: this is what it gets on its first start."
            except Exception as e:
                own = rules.empty()
                why = getattr(e, "reason", e)
                note = (f"Its server does not answer ({why}): its own rules live there, so they are not shown; "
                        "changes wait for its next start.")
            if waiting:
                note += f" {waiting} change(s) already wait for its server."
            return base, own, imposed, note

        self.win.background.ask(read, self._loaded, self._broke)

    def _loaded(self, got):
        self.base, self.own, self.imposed, note = got
        if self.kind != "instance":
            note = ("What this layer says, over what every bot starts with. "
                    + {"bot": "It is the base of every instance of the bot.",
                       "server": "It is the base of every bot on the server, over each bot's own.",
                       "group": "It is imposed on everything in the group.",
                       "global": "It is imposed on every instance that does not ignore it."}[self.kind])
        self.note.setText(note)
        self.setEnabled(True)
        self._draw()

    def _broke(self, e):
        self.note.setText(f"Could not read the rules: {e}")
        self.setEnabled(True)
        self.apply_button.setEnabled(False)

    def _who(self, family, key):
        """(the layer that decides it, and that said shortly: "default",
        "bot config", "this instance", "group team · locked")."""
        layer, label = rules.source(self.base, self.own, self.imposed, family, key)
        if layer == "imposed":
            return layer, f"{label or 'the launcher'} · locked"
        if layer == "own":
            return layer, "this instance" if self.kind == "instance" else "set here"
        return layer, "bot config" if layer == "base" else "default"

    def _draw(self):
        held = rules.effective(self.base, self.own, self.imposed)
        for r, key in enumerate(rules.TOGGLES):
            layer, who = self._who("prefs", key)
            row, box = switch_row(key, held["prefs"][key])
            box.setEnabled(layer != "imposed")
            box.toggled.connect(lambda checked, k=key: self._change(["pref", k, "on" if checked else "off"]))
            self.toggles.setCellWidget(r, 0, row)
            self.toggles.setRowHeight(r, 34)
            item = QTableWidgetItem(who)
            item.setForeground(QColor(theme.BUSY if layer == "imposed" else theme.MUTED))
            self.toggles.setItem(r, 1, item)
            reset = QPushButton("Reset")
            reset.setToolTip("Back to what applies without this layer")
            reset.setEnabled(key in self.own["prefs"] and layer != "imposed")
            reset.clicked.connect(lambda _=False, k=key: self._change(["pref", k, "default"]))
            self.toggles.setCellWidget(r, 2, reset)
        for family, (lst, field, replace, buttons) in self.lists.items():
            lst.clear()
            on = held["food_banned" if family == "food" else "break_allowed"]
            everything = rules.merge(rules.merge(self.base, self.own), self.imposed)
            off = sorted(i for i, v in everything[family].items() if not v)
            verb_off = rules.FAMILIES[family][1]
            for i in on:
                layer, who = self._who(family, i)
                it = QListWidgetItem(f"{i}      — {who}")
                it.setData(Qt.UserRole, i)
                if layer == "imposed":
                    it.setForeground(QColor(theme.BUSY))
                lst.addItem(it)
            for i in off:
                layer, who = self._who(family, i)
                it = QListWidgetItem(f"({verb_off}) {i}      — {who}")
                it.setData(Qt.UserRole, i)
                it.setForeground(QColor(theme.MUTED))
                lst.addItem(it)
            if not on and not off:
                lst.addItem(QListWidgetItem("(nothing)"))
            replace.blockSignals(True)
            replace.setChecked(family in self.own["replace"])
            replace.setEnabled(family not in self.imposed["replace"])
            replace.blockSignals(False)
        self.pending.setText(("Changes to send: " + "; ".join(" ".join(c) for c in self.changes))
                             if self.changes else "No changes yet.")
        self.apply_button.setEnabled(bool(self.changes))

    # changes

    def _imposed(self, words):
        family = "prefs" if words[0] == "pref" else words[0]
        key = words[1] if words[0] == "pref" else (words[2] if len(words) == 3 else "*")
        layer, label = rules.source(rules.empty(), rules.empty(), self.imposed, family, key)
        return rules.say_source(layer, label) if layer == "imposed" else None

    def _change(self, words):
        imposed = self._imposed(words)
        if imposed:
            self.win.alert("Imposed", f"{words[1] if len(words) > 1 else words[0]} is {imposed}: it is changed there.")
            self._draw()
            return
        try:
            self.own, _ = rules.edit(self.own, words)
        except Fail as e:
            self.win.alert("That is not a change", str(e))
            return
        self.changes.append(words)
        self._draw()

    def _list_change(self, family, verb):
        _, field, _, _ = self.lists[family]
        what = field.text().strip()
        if not what:
            return
        self._change([family, verb, what])
        field.clear()

    def _replace(self, family, checked):
        self._change([family, "replace" if checked else "add"])

    def _apply(self):
        changes, kind, obj, ws = list(self.changes), self.kind, self.obj, self.ws

        def work(on_event, cancel):
            for words in changes:
                if kind == "instance":
                    operations.edit_rules(obj, words, on_event=on_event)
                else:
                    operations.edit_layer(ws, words, bot=obj if kind == "bot" else None,
                                          slug=obj if kind == "server" else None,
                                          group=obj if kind == "group" else None, on_event=on_event)

        target = obj.key if kind == "instance" else f"{kind} {obj.key if hasattr(obj, 'key') else obj or ''}".strip()
        if self.win.tasks.run(target, "rules", work):
            self.accept()
        else:
            self.win.alert("Busy", f"{target} is busy: {self.win.tasks.busy(target)}")


# --- settings -----------------------------------------------------------------------

# What a yes-or-no setting's switch says next to it; the whole story is its tooltip.
SWITCH_LABELS = {"ignore_global": "ignore the global config", "lock": "keep the groups around it out",
                 "fast_responses": "Pregenerate fast responses"}


class SettingsDialog(Dialog):
    """The settings of one layer: an instance's, a bot's, a group's or the
    global ones. Each row shows what applies now and where it comes from;
    what is changed here goes into this layer only."""

    def __init__(self, win, target):
        super().__init__(win)
        self.win, self.target = win, target
        self.layer = settings.layer_of(target)
        self.setWindowTitle("Settings")
        self.resize(760, 470)
        v = QVBoxLayout(self)
        v.addWidget(title(f"Settings of {self._name()}"))
        v.addWidget(muted({"instance": "Its own layer: over its bot's, under its groups and the global config.",
                           "bot": "The bot's: under the settings of each of its instances.",
                           "group": "The group's: imposed on everything inside it.",
                           "global": "Imposed on every instance that does not ignore it."}[self.layer]))
        keys = [k for k, s in settings.SETTINGS.items() if self.layer in s.layers]
        self.table = QTableWidget(len(keys), 3)
        self.table.setHorizontalHeaderLabels(["setting", "here", "what applies now"])
        self.table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.table.verticalHeader().hide()
        self.table.horizontalHeader().setSectionResizeMode(2, QHeaderView.Stretch)
        self.table.setColumnWidth(0, 130)
        self.table.setColumnWidth(1, 260)
        self.editors = {}
        own = settings.own_values(target)
        for r, key in enumerate(keys):
            s = settings.SETTINGS[key]
            item = QTableWidgetItem(key)
            item.setToolTip(s.help)
            self.table.setItem(r, 0, item)
            current = "" if own.get(key) is None else str(own.get(key))
            under, from_ = settings.resolve(target, key, own_layer=False)
            if tuple(s.choices) == ("no", "yes"):
                # Yes or no is a switch. Off where nothing above says yes is
                # simply not set here.
                row, editor = switch_row(SWITCH_LABELS.get(key, key), (current or under) == "yes")
                row.setToolTip(s.help)
                self.table.setCellWidget(r, 1, row)
                self.table.setRowHeight(r, 34)
            else:
                editor = QComboBox()
                editor.setEditable(key not in ("role", "account"))
                # The first entry is what applies when this layer says nothing,
                # shown as the value it is and where it comes from: never a
                # blank box to guess at.
                editor.addItem(f"{under or '(none)'}   ·   {from_}", None)
                for c in settings.suggestions(target, key):
                    editor.addItem(c, c)
                if current:
                    i = editor.findData(current)
                    if i < 0:
                        editor.addItem(current, current)
                        i = editor.count() - 1
                    editor.setCurrentIndex(i)
                editor.setToolTip(s.help)
                self.table.setCellWidget(r, 1, editor)
            self.editors[key] = (editor, current, under)
            value, source = settings.resolve(target, key)
            self.table.setItem(r, 2, QTableWidgetItem(f"{value or '-'}   ({source})   · counts "
                                                      f"{settings.APPLIES[s.applies]}"))
        v.addWidget(self.table, 1)
        v.addWidget(muted("The first choice of each list is what applies when this layer says nothing: choosing "
                          "it takes the setting out of this layer."))
        buttons = QDialogButtonBox(QDialogButtonBox.Apply | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Apply).setObjectName("primary")
        buttons.button(QDialogButtonBox.Apply).clicked.connect(self._apply)
        buttons.rejected.connect(self.reject)
        v.addWidget(buttons)

    def _name(self):
        t = self.target
        return {"instance": f"the instance {getattr(t, 'key', '')}", "bot": f"the bot {getattr(t, 'key', '')}",
                "group": f"the group {getattr(t, 'key', '')}", "global": "everything (global)"}[self.layer]

    def value_of(self, key):
        """What the row says for this layer: a value, or "" for nothing here."""
        editor, current, under = self.editors[key]
        if isinstance(editor, Switch):
            # Untouched, it says what it said; moved to what applies anyway
            # without this layer, it says nothing here.
            value = "yes" if editor.isChecked() else "no"
            if value == current:
                return current
            return "" if value == (under or "no") else value
        if editor.currentIndex() == 0 and editor.currentText() == editor.itemText(0):
            return ""
        data = editor.currentData()
        return (data if data is not None and editor.currentText() == editor.itemText(editor.currentIndex())
                else editor.currentText()).strip()

    def _apply(self):
        changes = [(k, self.value_of(k)) for k, (_, was, _) in self.editors.items() if self.value_of(k) != was]
        if not changes:
            self.reject()
            return
        target = self.target

        def work(on_event, cancel):
            for key, value in changes:
                operations.configure(target, key, value or None, clear=not value, on_event=on_event)

        name = getattr(target, "key", "global")
        key = name if isinstance(target, Instance) else f"{self.layer} {name}"
        if self.win.tasks.run(key, "settings", work):
            self.accept()


# --- creating --------------------------------------------------------------------------

class NewInstanceDialog(Dialog):
    """A bot on a server: a new bot with its player name and account, or one
    that exists; the instance's name is the bot's unless one is given."""

    def __init__(self, win):
        super().__init__(win)
        self.win, self.ws = win, win.ws
        self.setWindowTitle("New instance")
        form = QFormLayout(self)
        self.bot = QComboBox()
        self.bot.addItem("(a new bot)", None)
        for b in self.ws.bots():
            self.bot.addItem(f"{b.key}  ·  plays as {b.name}", b.key)
        self.bot.currentIndexChanged.connect(self._bot_changed)
        form.addRow("Bot", self.bot)
        self.name = QLineEdit()
        self.name.setPlaceholderText("its player name in the game: letters, digits, _")
        form.addRow("Player name", self.name)
        self.account = QComboBox()
        self.account.addItem("online (a purchased account, logged in once)", "online")
        self.account.addItem("offline (private servers only)", "offline")
        for key in self.ws.account_keys():
            self.account.addItem(f"account {key}", key)
        form.addRow("Account", self.account)
        self.server = QComboBox()
        for s in self.ws.servers():
            self.server.addItem(f"{s.slug}  ·  {s.description or s.address}", s.slug)
        form.addRow("Server", self.server)
        self.key = QLineEdit()
        self.key.setToolTip("Its name in the launcher: the bot's, or <bot>-1, -2… when that is taken")
        self.key.textEdited.connect(lambda _: setattr(self, "key_edited", True))
        self.key_edited = False
        form.addRow("Instance name", self.key)
        self.name.textChanged.connect(self._suggest)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("Create")
        buttons.button(QDialogButtonBox.Ok).setObjectName("primary")
        buttons.accepted.connect(self._create)
        buttons.rejected.connect(self.reject)
        form.addRow(buttons)

    def _bot_changed(self):
        new = self.bot.currentData() is None
        self.name.setEnabled(new)
        self.account.setEnabled(new)
        if not new:
            self.name.setText(self.ws.bot(self.bot.currentData()).own_name)
        self._suggest()

    def _suggest(self):
        """The instance's name, filled in as it would be chosen, until it is
        edited by hand."""
        if self.key_edited:
            return
        base = (self.bot.currentData() or self.name.text().strip()).lower()
        self.key.setText(self.ws.free_key(base, self.ws.instance_keys()) if base else "")

    def _create(self):
        ws, bot_key = self.ws, self.bot.currentData()
        name, slug = self.name.text().strip(), self.server.currentData()
        account = self.account.currentData() if bot_key is None else None
        key = self.key.text().strip() or None
        if not name or not slug:
            self.win.alert("Missing", "A player name and a server.")
            return

        def work(on_event, cancel):
            return operations.create(ws, name, slug, account=account, bot_key=bot_key, key=key, on_event=on_event)

        if self.win.tasks.run("new instance", "creating", work):
            self.accept()


class NewGroupDialog(Dialog):
    def __init__(self, win):
        super().__init__(win)
        self.win, self.ws = win, win.ws
        self.setWindowTitle("New group")
        form = QFormLayout(self)
        self.key = QLineEdit(self.ws.free_key("group", self.ws.group_keys()))
        self.key.setToolTip("lowercase letters, digits, - and _")
        form.addRow("Name", self.key)
        self.normal = QRadioButton("Normal: instances and groups, started and stopped together")
        self.normal.setChecked(True)
        self.dependency = QRadioButton("Dependency: a leader and its guards, on one server")
        form.addRow(self.normal)
        form.addRow(self.dependency)
        self.leader = QComboBox()
        for inst in self.ws.instances():
            if groups.parent_of(self.ws, inst) is None:
                self.leader.addItem(f"{inst.key}  ·  {inst.name} on {inst.slug}", inst.key)
        form.addRow("Leader", self.leader)
        # The leader is only asked for a dependency group.
        form.setRowVisible(self.leader, False)
        self.dependency.toggled.connect(lambda on: (form.setRowVisible(self.leader, on), self.adjustSize()))
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("Create")
        buttons.button(QDialogButtonBox.Ok).setObjectName("primary")
        buttons.accepted.connect(self._create)
        buttons.rejected.connect(self.reject)
        form.addRow(buttons)

    def _create(self):
        key = self.key.text().strip()
        leader = self.leader.currentData() if self.dependency.isChecked() else None
        if not key or (self.dependency.isChecked() and not leader):
            self.win.alert("Missing", "A name, and for a dependency group, its leader.")
            return
        try:
            operations.create_group(self.ws, key, leader=leader, on_event=self.win.say)
        except Fail as e:
            self.win.fail(e)
            return
        self.accept()


class MoveDialog(Dialog):
    """Into another group, or into none. For a guard, its dependency group is
    what says whom it guards: this moves an instance between normal groups,
    or a group into another."""

    def __init__(self, win, node):
        super().__init__(win)
        here = groups.parent_of(win.ws, node)
        if isinstance(node, Instance) and here is not None and here.kind == groups.DEPENDENCY:
            # A leader or a guard stays with its dependency group: that group moves.
            node, here = here, groups.parent_of(win.ws, here)
        self.win, self.ws, self.node = win, win.ws, node
        self.setWindowTitle("Change group")
        form = QFormLayout(self)
        form.addRow(muted(f"{node.id} is in {here.id if here else 'no group'}."))
        self.to = QComboBox()
        self.to.addItem("(no group)", None)
        inside = set(g.key for g in groups.subgroups(node)) if isinstance(node, groups.Group) else set()
        for g in self.ws.groups():
            if g.kind == groups.NORMAL and g != node and g.key not in inside and g != here:
                self.to.addItem(g.key, g.key)
        form.addRow("Move to", self.to)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("Move")
        buttons.button(QDialogButtonBox.Ok).setObjectName("primary")
        buttons.accepted.connect(self._move)
        buttons.rejected.connect(self.reject)
        form.addRow(buttons)

    def _move(self):
        ref = f"{'group' if isinstance(self.node, groups.Group) else 'instance'}:{self.node.key}"
        here = groups.parent_of(self.ws, self.node)
        try:
            if here is not None:
                operations.group_remove(self.ws, here.key, [ref], on_event=self.win.say)
            if self.to.currentData():
                operations.group_add(self.ws, self.to.currentData(), [ref], on_event=self.win.say)
        except Fail as e:
            self.win.fail(e)
            return
        self.accept()


class AddToGroupDialog(Dialog):
    """What to put into a group: instances and groups in none (and, for a
    dependency group, instances on its leader's server)."""

    def __init__(self, win, group):
        super().__init__(win)
        self.win, self.ws, self.group = win, win.ws, group
        self.setWindowTitle(f"Add to {group.key}")
        v = QVBoxLayout(self)
        v.addWidget(muted("Its guards: instances on its leader's server, in no group."
                          if group.kind == groups.DEPENDENCY else "Instances and groups that are in no group."))
        self.list = QListWidget()
        self.list.setSelectionMode(QAbstractItemView.MultiSelection)
        leader = Instance(self.ws, group.leader) if group.kind == groups.DEPENDENCY else None
        for inst in self.ws.instances():
            if groups.parent_of(self.ws, inst) is not None or groups.dependency_of(inst)[1] == "leader":
                continue
            if leader is not None and inst.slug != leader.slug:
                continue
            it = QListWidgetItem(f"{inst.key}  ·  {inst.name} on {inst.slug}")
            it.setData(Qt.UserRole, f"instance:{inst.key}")
            self.list.addItem(it)
        if group.kind == groups.NORMAL:
            inside = {g.key for g in groups.subgroups(group)}
            for g in self.ws.groups():
                if g != group and g.key not in inside and groups.parent_of(self.ws, g) is None \
                        and group not in groups.subgroups(g):
                    it = QListWidgetItem(f"group {g.key}  ·  {g.kind}")
                    it.setData(Qt.UserRole, f"group:{g.key}")
                    self.list.addItem(it)
        v.addWidget(self.list, 1)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("Add")
        buttons.button(QDialogButtonBox.Ok).setObjectName("primary")
        buttons.accepted.connect(self._add)
        buttons.rejected.connect(self.reject)
        v.addWidget(buttons)

    def _add(self):
        refs = [i.data(Qt.UserRole) for i in self.list.selectedItems()]
        if refs:
            try:
                operations.group_add(self.ws, self.group.key, refs, on_event=self.win.say)
            except Fail as e:
                self.win.fail(e)
                return
        self.accept()


class CloneDialog(Dialog):
    def __init__(self, win, inst):
        super().__init__(win)
        self.win, self.ws, self.inst = win, win.ws, inst
        self.setWindowTitle(f"Clone {inst.key}")
        form = QFormLayout(self)
        form.addRow(muted("The same bot again: its settings, extra mods and, on the same server, what it keeps "
                          "about its world. Not its login."))
        self.server = QComboBox()
        for s in self.ws.servers():
            self.server.addItem(s.slug, s.slug)
        self.server.setCurrentText(inst.slug)
        form.addRow("On the server", self.server)
        self.key = QLineEdit(self.ws.free_key(inst.key, self.ws.instance_keys()))
        form.addRow("Its name", self.key)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("Clone")
        buttons.button(QDialogButtonBox.Ok).setObjectName("primary")
        buttons.accepted.connect(self._clone)
        buttons.rejected.connect(self.reject)
        form.addRow(buttons)

    def _clone(self):
        ws, key, slug = self.ws, self.inst.key, self.server.currentData()
        new_key = self.key.text().strip() or None
        if self.win.tasks.run(f"clone {key}", "cloning",
                              lambda on_event, cancel: operations.clone_instance(ws, key, new_key, slug, on_event)):
            self.accept()


# --- the bot itself ---------------------------------------------------------------------

class PersonalityDialog(Dialog):
    """Who the bot is, in second person, the language it speaks included: the
    start of its prompt. Saving can also have it write again, in its new
    voice, what it says without its brain."""

    def __init__(self, win, bot):
        super().__init__(win)
        self.win, self.bot = win, bot
        self.setWindowTitle(f"Personality of {bot.key}")
        self.resize(720, 520)
        v = QVBoxLayout(self)
        v.addWidget(title(f"{bot.key} · plays as {bot.name}"))
        v.addWidget(muted("Who it is, in second person, including the language it speaks and how. It goes at the "
                          "start of its prompt; a running bridge reads it when it restarts."))
        self.text = QPlainTextEdit()
        try:
            self.text.setPlainText(bot.personality.read_text(encoding="utf-8"))
        except OSError:
            pass
        v.addWidget(self.text, 1)
        v.addWidget(muted("If its fast responses are pregenerated (a setting), they are written again in this "
                          "voice the next time its bridge starts."))
        buttons = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Save).setObjectName("primary")
        buttons.accepted.connect(self._save)
        buttons.rejected.connect(self.reject)
        v.addWidget(buttons)

    def _save(self):
        self.bot.personality.write_text(self.text.toPlainText().rstrip() + "\n", encoding="utf-8")
        for inst in self.bot.instances():
            settings.render(inst)
        self.win.say_text(f"personality of {self.bot.key} saved")
        self.accept()


class LogDialog(Dialog):
    """An instance's logs, followed as they grow."""

    def __init__(self, win, inst):
        super().__init__(win)
        self.inst = inst
        self.setWindowTitle(f"Logs of {inst.key}")
        self.resize(980, 600)
        v = QVBoxLayout(self)
        self.tabs = QTabWidget()
        self.views = []
        for name, path in (("client", inst.client_log), ("bridge", inst.bridge_log), ("keeper", inst.keeper_log)):
            view = QPlainTextEdit()
            view.setReadOnly(True)
            view.setFont(monospace())
            view.setLineWrapMode(QPlainTextEdit.NoWrap)
            self.tabs.addTab(view, name)
            self.views.append((view, path))
        v.addWidget(self.tabs, 1)
        v.addWidget(close_row(self))
        self.timer = QTimer(self)
        self.timer.timeout.connect(self._read)
        self.timer.start(2000)
        self._read()

    def _read(self):
        for view, path in self.views:
            text = "\n".join(tail_lines(path, 400, width=400)) or f"(nothing yet in {path})"
            if text != view.toPlainText():
                bar = view.verticalScrollBar()
                following = bar.value() >= bar.maximum() - 4
                view.setPlainText(text)
                if following:
                    bar.setValue(bar.maximum())


# --- the rest of the launcher -------------------------------------------------------------

class DoctorDialog(Dialog):
    """doctor's checks, in the order things break."""

    def __init__(self, win):
        super().__init__(win)
        self.win = win
        self.setWindowTitle("Doctor")
        self.resize(900, 600)
        v = QVBoxLayout(self)
        v.addWidget(title("Doctor"))
        self.summary = muted("checking…")
        v.addWidget(self.summary)
        self.list = QListWidget()
        v.addWidget(self.list, 1)
        row = QHBoxLayout()
        again = QPushButton("Check again")
        again.clicked.connect(self._run)
        row.addWidget(again)
        row.addStretch()
        row.addWidget(close_row(self))
        v.addLayout(row)
        self._run()

    def _run(self):
        self.list.clear()
        self.summary.setText("checking…")
        self.win.background.ask(lambda: doctor.checks(self.win.ws), self._show,
                                lambda e: self.summary.setText(f"doctor failed: {e}"))

    def _show(self, checks):
        bad = 0
        for c in checks:
            mark = "✓" if c.ok else ("✗" if c.ok is False else "–")
            bad += c.ok is False
            it = QListWidgetItem(f"{mark}  {c.label}:  {c.detail}")
            it.setForeground(QColor(theme.IN_SERVER if c.ok else (theme.WRONG if c.ok is False else theme.MUTED)))
            it.setToolTip(c.detail)
            self.list.addItem(it)
        self.summary.setText("Everything checks out." if not bad else f"{bad} problem(s).")


class ConsoleDialog(Dialog):
    """A program that asks things, HeadlessMC logging an account in: what it
    says, and a line to answer it. `finished(code)` is called when it ends."""

    def __init__(self, win, heading, hint, argv, cwd, env, finished):
        super().__init__(win)
        self.finished_cb = finished
        self.setWindowTitle(heading)
        self.resize(860, 520)
        v = QVBoxLayout(self)
        v.addWidget(title(heading))
        v.addWidget(muted(hint))
        self.out = QPlainTextEdit()
        self.out.setReadOnly(True)
        self.out.setFont(monospace())
        v.addWidget(self.out, 1)
        row = QHBoxLayout()
        self.line = QLineEdit()
        self.line.setPlaceholderText("type here and press Enter")
        self.line.returnPressed.connect(self._send)
        row.addWidget(self.line, 1)
        for word in ("login", "quit"):
            b = QPushButton(word)
            b.clicked.connect(lambda _=False, w=word: self._send(w))
            row.addWidget(b)
        v.addLayout(row)
        self.proc = QProcess(self)
        environment = QProcessEnvironment()
        for k, val in env.items():
            environment.insert(k, val)
        self.proc.setProcessEnvironment(environment)
        self.proc.setWorkingDirectory(str(cwd))
        self.proc.setProcessChannelMode(QProcess.MergedChannels)
        self.proc.readyReadStandardOutput.connect(self._read)
        self.proc.finished.connect(self._ended)
        self.proc.start(argv[0], [str(a) for a in argv[1:]])

    def _read(self):
        self.out.appendPlainText(bytes(self.proc.readAllStandardOutput()).decode("utf-8", "replace").rstrip())

    def _send(self, text=None):
        text = text if isinstance(text, str) else self.line.text()
        self.line.clear()
        self.out.appendPlainText(f"> {text}")
        self.proc.write((text + "\n").encode())

    def _ended(self, code, _status):
        self.out.appendPlainText(f"(it ended, code {code})")
        self.line.setEnabled(False)
        self.finished_cb(code)

    def reject(self):
        if self.proc.state() != QProcess.NotRunning:
            self.proc.kill()
            self.proc.waitForFinished(3000)
        super().reject()


class AccountsDialog(Dialog):
    """Minecraft accounts, each logged in once and used by the bots set to
    it."""

    def __init__(self, win):
        super().__init__(win)
        self.win, self.ws = win, win.ws
        self.setWindowTitle("Accounts")
        self.resize(760, 420)
        v = QVBoxLayout(self)
        v.addWidget(title("Accounts"))
        v.addWidget(muted("A purchased Minecraft Java account, logged in once here; every bot set to it plays as "
                          "its player, and one account plays in one game at a time."))
        self.list = QListWidget()
        v.addWidget(self.list, 1)
        row = QHBoxLayout()
        add = QPushButton("Add an account…")
        add.setObjectName("primary")
        add.clicked.connect(self._add)
        row.addWidget(add)
        remove = QPushButton("Remove")
        remove.clicked.connect(self._remove)
        row.addWidget(remove)
        row.addStretch()
        row.addWidget(close_row(self))
        v.addLayout(row)
        self._fill()

    def _fill(self):
        self.list.clear()
        rows = operations.account_list(self.ws)
        for account, logged, bots_, insts in rows:
            users = ", ".join(bots_ + insts) or "nobody yet"
            it = QListWidgetItem(f"{account.key}  ·  plays as {account.name}  ·  "
                                 f"{'logged in' if logged else 'LOGIN GONE: remove it and add it again'}  ·  "
                                 f"used by {users}")
            it.setData(Qt.UserRole, account.key)
            self.list.addItem(it)
        if not rows:
            self.list.addItem("(none yet)")

    def _add(self):
        try:
            argv, cwd, env, folder = accounts.prepare_login(self.ws)
        except Fail as e:
            self.win.fail(e)
            return

        def finished(code):
            try:
                account = accounts.finish_login(self.ws, folder)
            except Fail as e:
                self.win.fail(e)
            else:
                self.win.say_text(f"account {account.key} added: plays as {account.name}")
            self._fill()

        ConsoleDialog(self, "Add an account", "HeadlessMC: press `login`, open the link it gives in a browser and "
                      "sign in with the Microsoft account; when it says the account is saved, press `quit`.",
                      argv, cwd, env, finished).exec()

    def _remove(self):
        item = self.list.currentItem()
        key = item.data(Qt.UserRole) if item else None
        if not key:
            return
        if not ask(self, "Remove", f"Remove the account {key}, and its login?"):
            return
        try:
            operations.remove_account(self.ws, key, on_event=self.win.say)
        except Fail as e:
            self.win.fail(e)
        self._fill()


class ServersDialog(Dialog):
    def __init__(self, win):
        super().__init__(win)
        self.win, self.ws = win, win.ws
        self.setWindowTitle("Servers")
        self.resize(820, 420)
        v = QVBoxLayout(self)
        v.addWidget(title("Servers"))
        v.addWidget(muted(f"Each is a folder under {self.ws.servers_dir}: server.conf (how to get in), mods/ (its "
                          "client pack), and optionally server.env (its server mod's address and token)."))
        servers = self.ws.servers()
        self.table = QTableWidget(len(servers), 4)
        self.table.setHorizontalHeaderLabels(["server", "address", "client mods", "description"])
        self.table.verticalHeader().hide()
        self.table.horizontalHeader().setSectionResizeMode(3, QHeaderView.Stretch)
        self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
        # What a server is comes from its folder: shown here, changed there.
        self.table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        for r, s in enumerate(servers):
            for c, text in enumerate((s.slug, s.address, str(s.mod_count()), s.description)):
                self.table.setItem(r, c, QTableWidgetItem(text))
        v.addWidget(self.table, 1)
        row = QHBoxLayout()
        b = QPushButton("Rules of this server…")
        b.clicked.connect(self._rules)
        row.addWidget(b)
        row.addStretch()
        row.addWidget(close_row(self))
        v.addLayout(row)

    def _rules(self):
        r = self.table.currentRow()
        if r >= 0:
            RulesDialog(self.win, "server", self.table.item(r, 0).text()).exec()


class BotsDialog(Dialog):
    def __init__(self, win):
        super().__init__(win)
        self.win, self.ws = win, win.ws
        self.setWindowTitle("Bots")
        self.resize(820, 440)
        v = QVBoxLayout(self)
        v.addWidget(title("Bots"))
        v.addWidget(muted("A bot is a character: its player name, personality, settings and rules. Its "
                          "instances are that bot on a server."))
        self.list = QListWidget()
        v.addWidget(self.list, 1)
        row = QHBoxLayout()
        for text, fn in (("Settings…", self._settings), ("Rules…", self._rules), ("Personality…", self._personality),
                         ("Clone", self._clone)):
            b = QPushButton(text)
            b.clicked.connect(fn)
            row.addWidget(b)
        row.addStretch()
        row.addWidget(close_row(self))
        v.addLayout(row)
        self._fill()

    def _fill(self):
        self.list.clear()
        for b in self.ws.bots():
            insts = [i.key for i in b.instances()]
            # Its player name first; the bot's folder name only when it says something else.
            text = b.name + (f"   (bot {b.key})" if b.key != b.name.lower() else "")
            text += "   ·   " + (f"{len(insts)} instance(s): {', '.join(insts)}" if insts else "no instances")
            icon = b.dir / "icon.png"
            it = QListWidgetItem(QIcon(theme.avatar(b.key, 24, image=icon if icon.is_file() else None)), text)
            it.setData(Qt.UserRole, b.key)
            self.list.addItem(it)

    def _bot(self):
        item = self.list.currentItem()
        return self.ws.bot(item.data(Qt.UserRole)) if item else None

    def _settings(self):
        if self._bot():
            SettingsDialog(self.win, self._bot()).exec()

    def _rules(self):
        if self._bot():
            RulesDialog(self.win, "bot", self._bot()).exec()

    def _personality(self):
        if self._bot():
            PersonalityDialog(self.win, self._bot()).exec()

    def _clone(self):
        bot = self._bot()
        if bot:
            try:
                operations.clone_bot(self.ws, bot.key, on_event=self.win.say)
            except Fail as e:
                self.win.fail(e)
            self._fill()




class LauncherSettingsDialog(Dialog):
    """The launcher's own settings, apart from any bot's: how it looks and
    how often it looks at the instances. Kept for this person on this machine
    (Qt's settings), and applied as they are changed."""

    def __init__(self, win):
        super().__init__(win)
        self.win = win
        self.setWindowTitle("Launcher settings")
        form = QFormLayout(self)
        form.addRow(title("Launcher settings"))
        self.preset = QComboBox()
        self.preset.addItems(list(theme.PRESETS))
        self.preset.setCurrentText(theme.current["name"])
        self.preset.currentTextChanged.connect(self._preview)
        form.addRow("Style", self.preset)
        self.every = QSpinBox()
        self.every.setRange(1, 60)
        self.every.setSuffix(" s")
        self.every.setValue(win.refresh_seconds())
        self.every.setToolTip("How often the window asks how every instance is doing")
        form.addRow("Look at the instances every", self.every)
        self.was = theme.current["name"]
        buttons = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Save).setObjectName("primary")
        buttons.accepted.connect(self._save)
        buttons.rejected.connect(self.reject)
        form.addRow(buttons)

    def _preview(self, name):
        self.win.set_style(name, keep=False)

    def _save(self):
        self.win.set_style(self.preset.currentText(), keep=True)
        self.win.set_refresh_seconds(self.every.value())
        self.accept()

    def reject(self):
        if theme.current["name"] != self.was:
            self.win.set_style(self.was, keep=False)
        super().reject()

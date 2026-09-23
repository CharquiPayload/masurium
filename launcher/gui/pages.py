"""The pages of the paged windows: an instance's (Edit Instance), a
group's, and the launcher's own Settings. Each page is a plain
widget with its own Apply or Save; a window stacks them, Prism's way, with
their names down the left.
"""
from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtGui import QColor
from PySide6.QtWidgets import (QAbstractItemView, QComboBox, QFileDialog, QFormLayout, QFrame, QGridLayout,
                               QHBoxLayout, QHeaderView, QInputDialog, QLabel, QLineEdit, QListWidget,
                               QListWidgetItem, QPlainTextEdit, QPushButton, QScrollArea, QSpinBox, QTableWidget,
                               QTableWidgetItem, QTabWidget, QTreeWidget, QTreeWidgetItem, QVBoxLayout, QWidget)

from .. import accounts, operations, rules, settings
from ..instances import Instance
from ..events import Fail
from ..files import tail_lines
from . import anim, icons, theme
from .common import ConsoleDialog, ask, monospace, muted, title
from .widgets import Switch, switch_row

def side_buttons(*buttons):
    """Prism's column of buttons to the right of a list: (text, icon, what it
    does), the first one the main one."""
    col = QVBoxLayout()
    col.setSpacing(6)
    for i, (text, icon, fn) in enumerate(buttons):
        b = QPushButton(text)
        icons.set_on(b, icon, colour="#ffffff" if i == 0 else None)
        if i == 0:
            b.setObjectName("primary")
        b.setStyleSheet("text-align: left;")
        b.clicked.connect(fn)
        col.addWidget(b)
    col.addStretch()
    return col


# What a yes-or-no setting's switch says next to it; the whole story is its tooltip.
def picture(name, size):
    """One of the icons, as a picture beside a name."""
    lab = QLabel()
    lab.setPixmap(icons.pixmap(name, size))
    lab.setFixedSize(size, size)
    return lab


SWITCH_LABELS = {"ignore_global": "ignore the global config", "lock": "keep the groups around it out",
                 "fast_responses": "Pregenerate fast responses"}


class RulesPage(QWidget):
    """A bot's rules as they come out, each thing with who decides it, and
    changes to one layer: an instance's own (kept on its server), or a
    group's or the global ones (kept by the launcher). The changes are
    collected, shown as they would come out, and sent with Apply; what is
    imposed from above cannot be touched, and says by whom.

    `kind` is "instance", "group" or "global"."""

    def __init__(self, win, kind, obj=None):
        super().__init__()
        self.win, self.ws, self.kind, self.obj = win, win.ws, kind, obj
        self.changes = []
        self.base = self.own = self.imposed = rules.empty()
        v = QVBoxLayout(self)
        v.setContentsMargins(0, 0, 0, 0)
        self.head = title(self._title())
        v.addWidget(self.head)
        v.addWidget(muted("In bold, what has been changed from what every bot starts with; “set by” says where: "
                          "this instance, or a group or the global config, which lock it."))
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
        row = QHBoxLayout()
        row.addStretch()
        self.apply_button = QPushButton("Apply")
        self.apply_button.setObjectName("primary")
        self.apply_button.clicked.connect(self._apply)
        row.addWidget(self.apply_button)
        v.addLayout(row)
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
        field.setPlaceholderText("an id, like rotten_flesh or farmersdelight:tomato" if family == "food"
                                 else "an id, like oak_log or create:cog")
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
        lst.itemClicked.connect(lambda item, fld=field: fld.setText(item.data(Qt.UserRole) or ""))
        self.lists[family] = (lst, field, replace, buttons)
        return w

    # loading and drawing

    def _load(self):
        def read():
            if self.kind != "instance":
                layer = {"group": lambda: rules.of_group(self.obj), "global": lambda: rules.of_global(self.ws)}
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
                    + {"group": "It is imposed on everything in the group.",
                       "global": "It is imposed on every instance that does not ignore it."}[self.kind])
        self.note.setText(note)
        self.setEnabled(True)
        self._draw()

    def _broke(self, e):
        self.note.setText(f"Could not read the rules: {e}")
        self.setEnabled(True)
        self.apply_button.setEnabled(False)

    def _who(self, family, key):
        """(the layer that decides it, and that said shortly: "bot config",
        "this instance", "group team · locked"; "" for what every bot starts
        with, which is shown as it is, not bold)."""
        layer, label = rules.source(self.base, self.own, self.imposed, family, key)
        if layer == "imposed":
            return layer, f"{label or 'the launcher'} · locked"
        if layer == "own":
            return layer, "this instance" if self.kind == "instance" else "set here"
        return layer, "bot config" if layer == "base" else ""

    def _draw(self):
        held = rules.effective(self.base, self.own, self.imposed)
        for r, key in enumerate(rules.TOGGLES):
            layer, who = self._who("prefs", key)
            row, box = switch_row(key, held["prefs"][key])
            row.findChild(QLabel).setStyleSheet("font-weight: bold;" if who else "")
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
                it = QListWidgetItem(i + (f"      — {who}" if who else ""))
                it.setData(Qt.UserRole, i)
                self._mark(it, layer, who)
                lst.addItem(it)
            for i in off:
                layer, who = self._who(family, i)
                it = QListWidgetItem(f"({verb_off}) {i}      — {who}")
                it.setData(Qt.UserRole, i)
                self._mark(it, layer, who)
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

    @staticmethod
    def _mark(item, layer, who):
        """Bold for what some layer changed, as everywhere: what every bot
        starts with is shown plainly; locked, in the colour of a warning."""
        if who:
            font = item.font()
            font.setBold(True)
            item.setFont(font)
        if layer == "imposed":
            item.setForeground(QColor(theme.BUSY))

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
                    operations.edit_layer(ws, words, group=obj if kind == "group" else None, on_event=on_event)

        target = obj.key if kind == "instance" else f"{kind} {obj.key if hasattr(obj, 'key') else obj or ''}".strip()
        if self.win.tasks.run(target, "rules", work, then=self._sent):
            self.setEnabled(False)
        else:
            self.win.alert("Busy", f"{target} is busy: {self.win.tasks.busy(target)}")

    def _sent(self, ok):
        """Sent (or refused): what there is now, read again."""
        self.changes = []
        self._load()


# The settings in sections, each with its icon and a name a person reads;
# the key the command line knows it by goes under the name.
SECTIONS = (("The game", "cube", ("account", "name", "heap", "port", "java", "java_args")),
            ("The brain", "spark", ("model", "fast_responses", "owner", "role")),
            ("Groups", "group", ("ignore_global", "lock")))
NAMES = {"account": ("Account", "account"), "heap": ("Memory", "chip"), "port": ("Port", "connect"),
         "java": ("Java", "cup"), "java_args": ("Java arguments", "logs"), "model": ("Model", "spark"),
         "fast_responses": ("Fast responses", "bolt"), "owner": ("Owner", "crown"), "role": ("Role", "shield"),
         "ignore_global": ("Global config", "globe"), "lock": ("Lock", "lock")}


NEW_ACCOUNT = "__new__"


class AccountChoice(QWidget):
    """How an instance plays, Prism's way: offline, as a player name written
    here, or with one of the Microsoft accounts of Settings, Accounts; a
    switch between the two. `on_new` opens the place to add an account."""

    changed = Signal()

    def __init__(self, ws, account="offline", name="", on_new=None):
        super().__init__()
        self.ws, self.on_new = ws, on_new
        v = QVBoxLayout(self)
        v.setContentsMargins(0, 0, 0, 0)
        v.setSpacing(6)
        row, self.microsoft = switch_row("Microsoft account", account != "offline")
        v.addWidget(row)
        self.name = QLineEdit(name)
        self.name.setMaxLength(16)
        self.name.setPlaceholderText("its player name, like Alice_42")
        self.name.setToolTip("Its name in the game, offline: letters, digits and underscore, up to 16. "
                             "Only for private servers with online-mode=false.")
        self.name.textChanged.connect(lambda _: self.changed.emit())
        v.addWidget(self.name)
        self.accounts = QComboBox()
        self.accounts.setToolTip("One of the Microsoft accounts of Settings, Accounts: it plays as its player")
        self.accounts.activated.connect(self._chosen)
        v.addWidget(self.accounts)
        self._fill(None if account == "offline" else account)
        self.microsoft.toggled.connect(lambda _: self._show())
        self._show()

    def _fill(self, choose=None):
        self.accounts.clear()
        for key in self.ws.account_keys():
            a = self.ws.account(key)
            if not a.offline:
                self.accounts.addItem(f"{a.name}   ·   {key}", key)
        self.accounts.addItem("+ New account…", NEW_ACCOUNT)
        if choose:
            self.accounts.setCurrentIndex(max(0, self.accounts.findData(choose)))

    def _show(self):
        on = self.microsoft.isChecked()
        self.name.setVisible(not on)
        self.accounts.setVisible(on)
        self.changed.emit()

    def _chosen(self, index):
        if self.accounts.itemData(index) == NEW_ACCOUNT and self.on_new:
            before = set(self.ws.account_keys())
            self.on_new()
            added = sorted(set(self.ws.account_keys()) - before)
            self._fill(added[-1] if added else None)
        self.changed.emit()

    def account(self):
        """"offline", an account's key, or None while none is chosen."""
        if not self.microsoft.isChecked():
            return "offline"
        key = self.accounts.currentData()
        return None if key in (None, NEW_ACCOUNT) else key

    def player(self):
        """The player it would be."""
        account = self.account()
        if account == "offline":
            return self.name.text().strip()
        return self.ws.account(account).name if account else ""


class SettingsPage(QWidget):
    """The settings of one layer: an instance's, a group's or the global
    ones. Each row shows what applies now and where it comes from;
    what is changed here goes into this layer only."""

    def __init__(self, win, target, keys=None, heading=None):
        super().__init__()
        self.win, self.target, self.only = win, target, keys
        self.layer = settings.layer_of(target)
        v = QVBoxLayout(self)
        v.setContentsMargins(0, 0, 0, 0)
        v.setSpacing(6)
        v.addWidget(title(heading or f"Settings of {self._name()}"))
        v.addWidget(muted({"instance": "Its own layer: under its groups and the global config, which impose.",
                           "group": "The group's: imposed on everything inside it.",
                           "global": "Imposed on every instance that does not ignore it."}[self.layer]))
        scroll = QScrollArea()
        scroll.setObjectName("plain")
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.NoFrame)
        body = QWidget()
        body.setObjectName("plain")
        self.sections = QVBoxLayout(body)
        self.sections.setContentsMargins(0, 6, 8, 6)
        self.sections.setSpacing(14)
        scroll.setWidget(body)
        v.addWidget(scroll, 1)
        v.addWidget(muted("The first choice of each list is what applies when this layer says nothing: choosing "
                          "it takes the setting out of this layer. In bold, what differs from the default."))
        row = QHBoxLayout()
        row.addStretch()
        self.apply_button = QPushButton("Apply")
        self.apply_button.setObjectName("primary")
        self.apply_button.clicked.connect(self._apply)
        row.addWidget(self.apply_button)
        v.addLayout(row)
        self._fill()

    def _fill(self):
        """The sections, as they stand now (again after Apply)."""
        target = self.target
        while self.sections.count():
            item = self.sections.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        keys = [k for k, s in settings.SETTINGS.items()
                if self.layer in s.layers and (self.only is None or k in self.only)]
        sections = [(heading, pic, [k for k in ks if k in keys]) for heading, pic, ks in SECTIONS]
        known = {k for _, _, ks in SECTIONS for k in ks}
        sections.append(("Other", "gear", [k for k in keys if k not in known]))
        self.editors = {}
        own = settings.own_values(target)
        for heading, pic, ks in sections:
            if ks:
                self.sections.addWidget(self._section(heading, pic, ks, own))
        self.sections.addStretch()
        self.apply_button.setEnabled(True)

    def _section(self, heading, pic, keys, own):
        card = QFrame()
        card.setObjectName("card")
        grid = QGridLayout(card)
        grid.setContentsMargins(16, 12, 16, 14)
        grid.setHorizontalSpacing(14)
        grid.setVerticalSpacing(12)
        head = QHBoxLayout()
        head.setSpacing(8)
        head.addWidget(picture(pic, 20))
        name = QLabel(heading)
        name.setObjectName("cardTitle")
        head.addWidget(name)
        head.addStretch()
        grid.addLayout(head, 0, 0, 1, 4)
        line = QFrame()
        line.setObjectName("headerLine")
        line.setFrameShape(QFrame.HLine)
        grid.addWidget(line, 1, 0, 1, 4)
        for r, key in enumerate(keys, start=2):
            if key == "name" and "account" in keys:
                continue                # the account's row says it: offline, it is the name there
            s = settings.SETTINGS[key]
            label, icon_name = NAMES.get(key, (key, "gear"))
            current = "" if own.get(key) is None else str(own.get(key))
            under, from_ = settings.resolve(self.target, key, own_layer=False)
            if key == "account":
                # Prism's way: a switch between offline, with a name, and a
                # Microsoft account of the launcher's.
                editor = widget = AccountChoice(self.target.ws, settings.get(self.target, "account"),
                                                self.target.own_name, on_new=self._new_account)
                self.editors["name"] = (editor, str(own.get("name") or ""), self.target.key)
                self.editors[key] = (editor, current, under)
                value, source = self.target.name, (
                    "offline" if settings.get(self.target, "account") == "offline"
                    else f"the account {settings.get(self.target, 'account')}")
                name = QLabel(f"<b>{label}</b><br><span style='color:{theme.FAINT}; font-size:8pt'>"
                              "account, name</span>")
                name.setToolTip(s.help)
                grid.addWidget(picture(icon_name, 18), r, 0)
                grid.addWidget(name, r, 1)
                grid.addWidget(widget, r, 2)
                grid.addWidget(muted(f"plays as {value}  ·  {source}  ·  counts {settings.APPLIES[s.applies]}"),
                               r, 3)
                continue
            if tuple(s.choices) == ("no", "yes"):
                # Yes or no is a switch. Off where nothing above says yes is
                # simply not set here.
                widget, editor = switch_row(SWITCH_LABELS.get(key, key), (current or under) == "yes")
            else:
                editor = widget = QComboBox()
                editor.setEditable(key not in ("role", "account"))
                editor.setMinimumWidth(260)
                # The first entry is what applies when this layer says nothing,
                # shown as the value it is and where it comes from: never a
                # blank box to guess at.
                editor.addItem(f"{under or '(none)'}   ·   {from_}", None)
                for c in settings.suggestions(self.target, key):
                    editor.addItem(c, c)
                if current:
                    i = editor.findData(current)
                    if i < 0:
                        editor.addItem(current, current)
                        i = editor.count() - 1
                    editor.setCurrentIndex(i)
            widget.setToolTip(s.help)
            self.editors[key] = (editor, current, under)
            value, source = settings.resolve(self.target, key)
            changed = source != "default"
            # Bold, as everywhere: what differs from the default.
            name = QLabel(f"{'<b>' if changed else ''}{label}{'</b>' if changed else ''}"
                          f"<br><span style='color:{theme.FAINT}; font-size:8pt'>{key}</span>")
            name.setToolTip(s.help)
            now = muted(f"now {value or '—'}  ·  {'the default' if source == 'default' else source}"
                        f"  ·  counts {settings.APPLIES[s.applies]}")
            if changed:
                font = now.font()
                font.setBold(True)
                now.setFont(font)
            icon = picture(icon_name, 18)
            icon.setToolTip(s.help)
            grid.addWidget(icon, r, 0)
            grid.addWidget(name, r, 1)
            grid.addWidget(widget, r, 2)
            grid.addWidget(now, r, 3)
        grid.setColumnMinimumWidth(1, 140)
        grid.setColumnStretch(3, 1)
        return card

    def _name(self):
        t = self.target
        return {"instance": f"the instance {getattr(t, 'key', '')}",
                "group": f"the group {getattr(t, 'key', '')}", "global": "everything (global)"}[self.layer]

    def _new_account(self):
        from .dialogs import SettingsWindow
        SettingsWindow(self.win, start="Accounts").exec()

    def value_of(self, key):
        """What the row says for this layer: a value, or "" for nothing here."""
        editor, current, under = self.editors[key]
        if isinstance(editor, AccountChoice):
            if key == "account":
                account = editor.account()
                return current if account is None else ("" if account == "offline" else account)
            if editor.account() != "offline":
                return current          # a Microsoft account's player plays: the name is kept
            name = editor.name.text().strip()
            return "" if name in ("", under) else name
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
            self.win.say_text("nothing changed")
            return
        target = self.target

        def work(on_event, cancel):
            for key, value in changes:
                operations.configure(target, key, value or None, clear=not value, on_event=on_event)

        name = getattr(target, "key", "global")
        key = name if isinstance(target, Instance) else f"{self.layer} {name}"
        if self.win.tasks.run(key, "settings", work, then=lambda ok: self._fill()):
            self.apply_button.setEnabled(False)


class PersonalityPage(QWidget):
    """Who the bot is, in second person, the language it speaks included: the
    start of its prompt. Saving can also have it write again, in its new
    voice, what it says without its brain."""

    def __init__(self, win, inst):
        super().__init__()
        self.win, self.inst = win, inst
        v = QVBoxLayout(self)
        v.setContentsMargins(0, 0, 0, 0)
        v.addWidget(title(f"{inst.key} · plays as {inst.name}"))
        v.addWidget(muted("Who it is, in second person, including the language it speaks and how. It goes at the "
                          "start of its prompt; a running bridge reads it when it restarts."))
        self.text = QPlainTextEdit()
        try:
            self.text.setPlainText(inst.personality.read_text(encoding="utf-8"))
        except OSError:
            pass
        v.addWidget(self.text, 1)
        v.addWidget(muted("If its fast responses are pregenerated (a setting), they are written again in this "
                          "voice the next time its bridge starts."))
        row = QHBoxLayout()
        row.addStretch()
        save = QPushButton("Save")
        save.setObjectName("primary")
        save.clicked.connect(self._save)
        row.addWidget(save)
        v.addLayout(row)

    def _save(self):
        self.inst.personality.write_text(self.text.toPlainText().rstrip() + "\n", encoding="utf-8")
        self.win.say_text(f"personality of {self.inst.key} saved")


class LogsPage(QWidget):
    """An instance's logs, followed as they grow."""

    def __init__(self, win, inst):
        super().__init__()
        self.inst = inst
        v = QVBoxLayout(self)
        v.setContentsMargins(0, 0, 0, 0)
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


class AccountsPage(QWidget):
    """Minecraft accounts, each logged in once and used by the bots set to
    it."""

    def __init__(self, win):
        super().__init__()
        self.win, self.ws = win, win.ws
        v = QVBoxLayout(self)
        v.setContentsMargins(0, 0, 0, 0)
        v.addWidget(title("Accounts"))
        v.addWidget(muted("A Microsoft account is a purchased Minecraft Java account, logged in once here. An "
                          "instance set to one plays as its player, in one game at a time; an offline instance "
                          "only needs a player name, and plays on private servers."))
        row = QHBoxLayout()
        self.list = QTreeWidget()
        self.list.setHeaderLabels(["Player", "Login", "Used by"])
        self.list.setRootIsDecorated(False)
        self.list.setColumnWidth(0, 200)
        self.list.setColumnWidth(1, 260)
        row.addWidget(self.list, 1)
        row.addLayout(side_buttons(("Add Microsoft", "plus", self._add), ("Remove", "delete", self._remove)))
        v.addLayout(row, 1)
        self._fill()

    def _fill(self):
        self.list.clear()
        rows = operations.account_list(self.ws)
        for account, logged, users in rows:
            if account.offline:
                continue                # from before: migrate folds it into its instances
            state = "logged in" if logged else "GONE: remove it and add it again"
            it = QTreeWidgetItem([account.name, state, ", ".join(users) or "nobody yet"])
            it.setIcon(0, icons.icon("account"))
            it.setData(0, Qt.UserRole, account.key)
            self.list.addTopLevelItem(it)
        if not self.list.topLevelItemCount():
            self.list.addTopLevelItem(QTreeWidgetItem(["(none yet)", "", ""]))

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
        chosen = self.list.selectedItems()
        key = chosen[0].data(0, Qt.UserRole) if chosen else None
        if not key:
            self.win.alert("Nothing chosen", "Choose an account in the list first.")
            return
        if not ask(self, "Remove", f"Remove the account {key}, and its login?"):
            return
        try:
            operations.remove_account(self.ws, key, on_event=self.win.say)
        except Fail as e:
            self.win.fail(e)
        self._fill()


class ServersPage(QWidget):
    def __init__(self, win):
        super().__init__()
        self.win, self.ws = win, win.ws
        v = QVBoxLayout(self)
        v.setContentsMargins(0, 0, 0, 0)
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
        self.table.setSelectionMode(QAbstractItemView.NoSelection)


class LauncherPage(QWidget):
    """The launcher's own settings, apart from any bot's: how it looks and
    how often it looks at the instances. Kept for this person on this machine
    (Qt's settings), and applied as they are changed."""

    def __init__(self, win):
        super().__init__()
        self.win = win
        v = QVBoxLayout(self)
        v.setContentsMargins(0, 0, 0, 0)
        v.addWidget(title("Launcher"))
        form = QFormLayout()
        self.preset = QComboBox()
        self.preset.addItems(list(theme.PRESETS))
        self.preset.setCurrentText(theme.current["name"])
        self.preset.currentTextChanged.connect(lambda name: win.set_style(name))
        form.addRow("Style", self.preset)
        row, self.animations = switch_row("Animations: switches that slide, windows and panels that fade in",
                                          anim.enabled)
        self.animations.toggled.connect(win.set_animations)
        form.addRow("Motion", row)
        row, self.animated_icons = switch_row("Animated icons: each makes its gesture when the pointer is over "
                                              "it, and an instance's dot pulses while it loads", icons.animated)
        self.animated_icons.toggled.connect(win.set_animated_icons)
        form.addRow("", row)
        self.every = QSpinBox()
        self.every.setRange(1, 60)
        self.every.setSuffix(" s")
        self.every.setValue(win.refresh_seconds())
        self.every.setToolTip("How often the window asks how every instance is doing")
        self.every.valueChanged.connect(win.set_refresh_seconds)
        form.addRow("Look at the instances every", self.every)
        v.addLayout(form)
        v.addStretch()


class ModsPage(QWidget):
    """An instance's extra mods: jars of its own, on top of its server's pack
    and the shared ones, put in its game on every start."""

    def __init__(self, win, inst):
        super().__init__()
        self.win, self.inst = win, inst
        v = QVBoxLayout(self)
        v.setContentsMargins(0, 0, 0, 0)
        v.addWidget(title("Extra mods"))
        v.addWidget(muted(f"Jars of this instance only, in {inst.extra_mods}: on top of its server's client pack "
                          "and the shared mods, put in its game when it starts."))
        row = QHBoxLayout()
        self.list = QListWidget()
        row.addWidget(self.list, 1)
        row.addLayout(side_buttons(("Add file", "plus", self._add), ("Remove", "delete", self._remove),
                                   ("View folder", "folder", self._folder)))
        v.addLayout(row, 1)
        self._fill()

    def _folder(self):
        self.inst.extra_mods.mkdir(parents=True, exist_ok=True)
        self.win.open_folder(self.inst.extra_mods)

    def _fill(self):
        self.list.clear()
        jars = sorted(self.inst.extra_mods.glob("*.jar")) if self.inst.extra_mods.is_dir() else []
        for jar in jars:
            it = QListWidgetItem(jar.name)
            it.setData(Qt.UserRole, str(jar))
            self.list.addItem(it)
        if not jars:
            self.list.addItem("(none: it plays with its server's pack and the shared mods)")

    def _add(self):
        path, _ = QFileDialog.getOpenFileName(self, "A mod for this instance", str(self.win.ws.home), "Mods (*.jar)")
        if not path:
            return
        import shutil
        self.inst.extra_mods.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, self.inst.extra_mods)
        self.win.say_text(f"{self.inst.key}: extra mod added; it counts on its next start")
        self._fill()

    def _remove(self):
        chosen = [i for i in self.list.selectedItems() if i.data(Qt.UserRole)]
        if not chosen:
            self.win.alert("Nothing chosen", "Choose a mod in the list first.")
            return
        import pathlib
        name = pathlib.Path(chosen[0].data(Qt.UserRole)).name
        if ask(self, "Remove", f"Take {name} out of this instance's extra mods?"):
            pathlib.Path(chosen[0].data(Qt.UserRole)).unlink(missing_ok=True)
            self._fill()

"""The dialogs: a new instance or group, moving and cloning, doctor, bots,
and the paged windows (Edit Instance, a group's, a bot's, and Settings),
whose pages are in pages.py.

A dialog decides nothing the command line would decide differently: it
gathers what to do and hands it to the same operations, run as a task of the
main window, whose events land in the window's activity list.
"""
from PySide6.QtCore import QSize, Qt
from PySide6.QtGui import QColor, QIcon
from PySide6.QtWidgets import (QAbstractItemView, QComboBox, QFormLayout, QHBoxLayout, QLineEdit,
                               QListWidget, QListWidgetItem, QPushButton, QRadioButton, QStackedWidget, QVBoxLayout)

from .. import doctor, groups, operations
from ..bots import Instance
from ..events import Fail
from . import anim, icons, theme
from .common import Dialog, close_row, muted, ok_row, title
from .pages import (AccountsPage, LauncherPage, LogsPage, ModsPage, PersonalityPage, RulesPage, ServersPage,
                    SettingsPage)

NEW_ACCOUNT = "__new__"
PAGE_ICONS = {"Settings": "gear", "Rules": "rules", "Personality": "bot", "Mods": "cube", "Logs": "logs",
              "Launcher": "window", "Java": "cup", "Global settings": "globe", "Global rules": "rules",
              "Accounts": "account", "Servers": "server"}
# The part of the setup guide each dialog's Help opens.
HELP_CREATE = "4-create-and-start-a-bot"
HELP_MOVE = HELP_ADD = "groups"
HELP_CLONE = "4-create-and-start-a-bot"


class PagedDialog(Dialog):
    """Prism's way: the pages' names down the left, the page on the right,
    Close at the bottom."""

    def __init__(self, win, heading, pages, start=None, help=""):
        super().__init__(win)
        self.win = win
        self.setWindowTitle(heading)
        self.resize(1000, 640)
        v = QVBoxLayout(self)
        v.addWidget(title(heading))
        body = QHBoxLayout()
        self.index = QListWidget()
        self.index.setObjectName("pages")
        self.index.setFixedWidth(180)
        self.stack = QStackedWidget()
        self.pages = {}
        for name, make in pages:
            page = make()
            self.pages[name] = page
            item = QListWidgetItem(icons.icon(PAGE_ICONS.get(name, "info")), name)
            item.setSizeHint(QSize(0, 36))
            self.index.addItem(item)
            self.stack.addWidget(page)
        self.index.currentRowChanged.connect(self._turn)
        body.addWidget(self.index)
        body.addWidget(self.stack, 1)
        v.addLayout(body, 1)
        self.bottom = close_row(self, help=help)
        v.addWidget(self.bottom)
        names = [n for n, _ in pages]
        self.index.setCurrentRow(names.index(start) if start in names else 0)

    def _turn(self, row):
        anim.turn(self.stack, row)

    def page(self, name):
        self.index.setCurrentRow(list(self.pages).index(name))
        return self.pages[name]


class EditInstanceDialog(PagedDialog):
    """Prism's Edit Instance: its settings, its rules, its bot's personality,
    its extra mods and its logs; Launch and Kill at the bottom, as in Prism's
    console window."""

    def __init__(self, win, inst, start=None):
        pages = [("Settings", lambda: SettingsPage(win, inst)),
                 ("Rules", lambda: RulesPage(win, "instance", inst))]
        if inst.bot.exists():
            pages.append(("Personality", lambda: PersonalityPage(win, inst.bot)))
        pages += [("Mods", lambda: ModsPage(win, inst)), ("Logs", lambda: LogsPage(win, inst))]
        super().__init__(win, f"Edit instance · {inst.key}  ({inst.name} on {inst.slug})", pages, start,
                         help="configuring-a-bot")
        self.kill = QPushButton("Kill")
        icons.set_on(self.kill, "stop")
        self.kill.clicked.connect(lambda: win.kill(inst.key))
        self.launch = QPushButton("Launch")
        icons.set_on(self.launch, "play", colour="#ffffff")
        self.launch.setObjectName("primary")
        self.launch.clicked.connect(lambda: win.launch(inst.key))
        self.bottom.add(self.kill)
        self.bottom.add(self.launch)


class EditGroupDialog(PagedDialog):
    def __init__(self, win, group, start=None):
        super().__init__(win, f"Edit group · {group.key}", [
            ("Settings", lambda: SettingsPage(win, group)),
            ("Rules", lambda: RulesPage(win, "group", group))], start, help="groups")


class EditBotDialog(PagedDialog):
    def __init__(self, win, bot, start=None):
        super().__init__(win, f"Edit bot · {bot.key}  (plays as {bot.name})", [
            ("Settings", lambda: SettingsPage(win, bot)),
            ("Rules", lambda: RulesPage(win, "bot", bot)),
            ("Personality", lambda: PersonalityPage(win, bot))], start, help="configuring-a-bot")


class SettingsWindow(PagedDialog):
    """The launcher's Settings, Prism's way: the launcher itself, Java, what
    is imposed on every instance, accounts and servers."""

    def __init__(self, win, start=None):
        glob = win.ws.global_config()
        super().__init__(win, "Settings", [
            ("Launcher", lambda: LauncherPage(win)),
            ("Java", lambda: SettingsPage(win, glob, keys=("java", "java_args"),
                                          heading="Java, for every instance (a group or an instance can have "
                                                  "its own)")),
            ("Global settings", lambda: SettingsPage(win, glob, heading="Settings imposed on every instance")),
            ("Global rules", lambda: RulesPage(win, "global")),
            ("Accounts", lambda: AccountsPage(win)),
            ("Servers", lambda: ServersPage(win))], start, help="configuring-a-bot")


class NewInstanceDialog(Dialog):
    """A bot on a server: a new bot, playing as one of the accounts, or one
    that exists (which may play with another account on this instance). Its
    name, and the group it goes into, come filled in."""

    def __init__(self, win, group=None):
        super().__init__(win)
        self.win, self.ws = win, win.ws
        self.setWindowTitle("Add instance")
        form = QFormLayout(self)
        form.addRow(title("Add instance"))
        self.bot = QComboBox()
        self.bot.addItem("(a new bot)", None)
        for b in self.ws.bots():
            self.bot.addItem(f"{b.name}   ·   bot {b.key}", b.key)
        self.bot.currentIndexChanged.connect(self._bot_changed)
        form.addRow("Bot", self.bot)
        self.account = QComboBox()
        self.account.activated.connect(self._account_chosen)
        form.addRow("Account", self.account)
        self.server = QComboBox()
        for s in self.ws.servers():
            self.server.addItem(f"{s.slug}   ·   {s.description or s.address}", s.slug)
        form.addRow("Server", self.server)
        self.group = QComboBox()
        self.group.addItem("(no group)", None)
        for g in self.ws.groups():
            if g.kind == groups.NORMAL:
                self.group.addItem(g.key, g.key)
        if group:
            self.group.setCurrentIndex(max(0, self.group.findData(group)))
        form.addRow("Group", self.group)
        self.key = QLineEdit()
        self.key.setToolTip("Its name in the launcher: the bot's, or <bot>-1, -2… when that is taken")
        self.key.textEdited.connect(lambda _: setattr(self, "key_edited", True))
        self.key_edited = False
        form.addRow("Instance name", self.key)
        buttons = ok_row(self, "Create", self._create, help=HELP_CREATE)
        form.addRow(buttons)
        self._fill_accounts()
        self._suggest()

    def _fill_accounts(self, choose=None):
        """The accounts, offline and Microsoft, and a way to add one; for a
        bot that exists, its own first."""
        self.account.clear()
        bot_key = self.bot.currentData()
        if bot_key:
            self.account.addItem(f"the bot's ({self.ws.bot(bot_key).name})", None)
        for key in self.ws.account_keys():
            a = self.ws.account(key)
            self.account.addItem(f"{a.name}   ·   {'offline' if a.offline else 'Microsoft'}", key)
        self.account.addItem("+ New account…", NEW_ACCOUNT)
        if choose:
            self.account.setCurrentIndex(max(0, self.account.findData(choose)))
        self._suggest()

    def _account_chosen(self, index):
        if self.account.itemData(index) != NEW_ACCOUNT:
            self._suggest()
            return
        before = set(self.ws.account_keys())
        SettingsWindow(self.win, start="Accounts").exec()
        added = sorted(set(self.ws.account_keys()) - before)
        self._fill_accounts(choose=added[-1] if added else None)

    def _bot_changed(self):
        self._fill_accounts()

    def _player(self):
        """The player it will be: the account's, or the bot's."""
        key = self.account.currentData()
        if key and key != NEW_ACCOUNT:
            return self.ws.account(key).name
        bot_key = self.bot.currentData()
        return self.ws.bot(bot_key).name if bot_key else ""

    def _suggest(self):
        """Its name, filled in as it would be chosen, until it is edited by hand."""
        if self.key_edited:
            return
        base = (self.bot.currentData() or self._player()).lower()
        self.key.setText(self.ws.free_key(base, self.ws.instance_keys()) if base else "")

    def _create(self):
        ws, bot_key = self.ws, self.bot.currentData()
        account = self.account.currentData()
        slug, group = self.server.currentData(), self.group.currentData()
        key = self.key.text().strip() or None
        if account == NEW_ACCOUNT or (bot_key is None and not account):
            self.win.alert("Missing", "Choose the account it plays with (or add one).")
            return
        if not slug:
            self.win.alert("Missing", "Choose a server (a folder under servers/: see Settings, Servers).")
            return
        name = self._player()
        existing = ws.bot(name.lower())
        if bot_key is None and existing.exists():
            bot_key = existing.key          # that player's bot is there already: another instance of it

        def work(on_event, cancel):
            from .. import settings
            inst = operations.create(ws, name, slug, account=account if bot_key is None else None,
                                     bot_key=bot_key, key=key, on_event=on_event)
            if bot_key is not None and account:
                settings.set_value(inst, "account", account)
                settings.render(inst)
            if group:
                operations.group_add(ws, group, [f"instance:{inst.key}"], on_event=on_event)
            return inst

        if self.win.tasks.run("new instance", "creating", work):
            self.accept()


class NewGroupDialog(Dialog):
    """A normal group or a dependency one. Given a `leader` (an instance's
    right-click), only a dependency group led by it, and only its name asked."""

    def __init__(self, win, inside=None, leader=None):
        super().__init__(win)
        self.inside = inside
        self.win, self.ws = win, win.ws
        form = QFormLayout(self)
        taken = self.ws.group_keys()
        self.key = QLineEdit(self.ws.free_key(f"{leader.key}-guards" if leader else "group", taken))
        self.key.setToolTip("lowercase letters, digits, - and _")
        self.normal = QRadioButton("Normal: instances and groups, started and stopped together")
        self.dependency = QRadioButton("Dependency: a leader and its guards, on one server")
        self.leader = QComboBox()
        if leader is not None:
            self.setWindowTitle(f"New dependency group, led by {leader.key}")
            self.dependency.setChecked(True)
            self.leader.addItem(leader.key, leader.key)
            here = groups.parent_of(self.ws, leader)
            form.addRow(muted(f"{leader.key} ({leader.name} on {leader.slug}) leads it"
                              + (f", and it stays in {here.id}, inside the new group." if here else ".")
                              + " Its guards are added afterwards: Add to it."))
            form.addRow("Name", self.key)
        else:
            self.setWindowTitle("New group")
            form.addRow("Name", self.key)
            self.normal.setChecked(True)
            form.addRow(self.normal)
            form.addRow(self.dependency)
            # A leader may be in a normal group (its new group takes its place
            # there), not already in a dependency one.
            for inst in self.ws.instances():
                if groups.dependency_of(inst)[1] is None:
                    self.leader.addItem(f"{inst.key}  ·  {inst.name} on {inst.slug}", inst.key)
            form.addRow("Leader", self.leader)
            # The leader is only asked for a dependency group.
            form.setRowVisible(self.leader, False)
            self.dependency.toggled.connect(lambda on: (form.setRowVisible(self.leader, on), self.adjustSize()))
        buttons = ok_row(self, "Create", self._create, help="groups")
        form.addRow(buttons)

    def _create(self):
        key = self.key.text().strip()
        leader = self.leader.currentData() if self.dependency.isChecked() else None
        if not key or (self.dependency.isChecked() and not leader):
            self.win.alert("Missing", "A name, and for a dependency group, its leader.")
            return
        try:
            group = operations.create_group(self.ws, key, leader=leader, on_event=self.win.say)
            if self.inside:
                operations.group_add(self.ws, self.inside, [f"group:{group.key}"], on_event=self.win.say)
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
        buttons = ok_row(self, "Move", self._move, help=HELP_MOVE)
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
        buttons = ok_row(self, "Add", self._add, help=HELP_ADD)
        v.addWidget(buttons)

    def _add(self):
        refs = [i.data(Qt.UserRole) for i in self.list.selectedItems()]
        if not refs:
            self.win.alert("Nothing chosen", "Choose what to add in the list first.")
            return
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
        self.setWindowTitle(f"Copy {inst.key}")
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
        buttons = ok_row(self, "Copy", self._clone, help=HELP_CLONE)
        form.addRow(buttons)

    def _clone(self):
        ws, key, slug = self.ws, self.inst.key, self.server.currentData()
        new_key = self.key.text().strip() or None
        if self.win.tasks.run(f"copy {key}", "copying",
                              lambda on_event, cancel: operations.clone_instance(ws, key, new_key, slug, on_event)):
            self.accept()


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
        for text, fn in (("Edit…", self._edit), ("Clone", self._clone)):
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
            it.setSizeHint(QSize(0, 34))
            it.setData(Qt.UserRole, b.key)
            self.list.addItem(it)

    def _bot(self):
        """The bot chosen in the list; nothing is chosen by itself, and a
        button pressed with nothing chosen says so."""
        chosen = self.list.selectedItems()
        if not chosen:
            self.win.alert("Nothing chosen", "Choose a bot in the list first.")
            return None
        return self.ws.bot(chosen[0].data(Qt.UserRole))

    def _edit(self):
        bot = self._bot()
        if bot:
            EditBotDialog(self.win, bot).exec()
            self._fill()

    def _clone(self):
        bot = self._bot()
        if bot:
            try:
                operations.clone_bot(self.ws, bot.key, on_event=self.win.say)
            except Fail as e:
                self.win.fail(e)
            self._fill()


def theme_icon(name):
    return QIcon(theme.avatar(name, 24))

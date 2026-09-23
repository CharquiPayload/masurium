#!/usr/bin/env python3
"""Tests of the window, drawn offscreen: no screen, no Minecraft, no server.

What is tested is that the window shows what is there and hands what a
person does to the same operations the command line uses; the operations
themselves are tested in launcher/tests.py.

Run:  python3 -m launcher.gui.tests   (needs PySide6)
With MASURIUM_GUI_SHOTS=<folder>, it also leaves screenshots there.
"""
import os
import pathlib
import sys
import tempfile
import time

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QEvent, QSettings, Qt, QVariantAnimation  # noqa: E402
from PySide6.QtGui import QColor, QImage  # noqa: E402
from PySide6.QtWidgets import QApplication, QLabel, QLineEdit, QPushButton, QToolButton  # noqa: E402

from .. import brain, groups, instances, memory, operations as ops, rules, updates, workspace  # noqa: E402
from ..workspace import Workspace  # noqa: E402
from . import anim, dialogs, icons, theme, window as window_module  # noqa: E402
from .widgets import Switch  # noqa: E402

# Ports of their own, far from real bots': stopping an instance stops whatever
# java carries its port on the whole machine (see launcher/tests.py).
workspace.FIRST_PORT = instances.FIRST_PORT = 18478
brain.fetch_latest = lambda: "v2.1.280"
LATEST = lambda: ("v" + updates.__version__, "https://example.invalid/releases/latest")  # noqa: E731
updates.fetch_latest = LATEST
from .window import MainWindow  # noqa: E402

TMP = pathlib.Path(tempfile.mkdtemp(prefix="masurium-gui-test-"))
SHOTS = os.environ.get("MASURIUM_GUI_SHOTS")

failures = []
done = 0


def check(description, condition, detail=""):
    global done
    done += 1
    if condition:
        print(f"  ok   {description}")
    else:
        print(f"  FAIL {description}" + (f"\n         {detail}" if detail else ""))
        failures.append(description)


def wait(predicate, seconds=15):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        QApplication.processEvents()
        if predicate():
            return True
        time.sleep(0.02)
    QApplication.processEvents()
    return predicate()


def shot(widget, name):
    if SHOTS:
        pathlib.Path(SHOTS).mkdir(parents=True, exist_ok=True)
        wait(lambda: False, 0.4)                   # the fades and gestures, over
        widget.grab().save(str(pathlib.Path(SHOTS) / f"{name}.png"))


def workspace():
    for d in ("bots", "instances", "servers/test/mods", "servers/other/mods", "shared/mods"):
        (TMP / d).mkdir(parents=True, exist_ok=True)
    (TMP / "shared" / "headlessmc-launcher.jar").write_bytes(b"not really a jar")
    (TMP / "shared" / "mods" / "masurium-1.0.0.jar").write_bytes(b"m")
    (TMP / "shared" / "mods" / "hmc-specifics-1.21.1-neoforge.jar").write_bytes(b"h")
    (TMP / "servers" / "test" / "server.conf").write_text("HOST=127.0.0.1\nDESCRIPTION=\"the test one\"\n")
    (TMP / "servers" / "other" / "server.conf").write_text("HOST=127.0.0.2\n")
    # A server mod that refuses at once: every question to it fails in no time.
    (TMP / "server.env").write_text("MASURIUM_HOST=127.0.0.1\nMASURIUM_PORT=1\nMASURIUM_TOKEN=t\n")
    environ = {k: v for k, v in os.environ.items() if not k.startswith("MASURIUM_")}
    ws = Workspace(TMP / "bots", TMP / "servers", TMP / "shared", TMP / "server.env", home=TMP, environ=environ,
                   instances_dir=TMP / "instances", state_dir=TMP / "state", accounts_dir=TMP / "accounts",
                   groups_dir=TMP / "groups")
    for name in ("Alice", "Bob", "Carol"):
        ops.create(ws, name, "test", account="offline")
    ops.create_group(ws, "alice-guards", leader="alice")
    ops.group_add(ws, "alice-guards", ["bob"])
    ops.create_group(ws, "team")
    ops.group_add(ws, "team", ["carol", "group:alice-guards"])
    return ws


def buttons(widget):
    return [b.text() for b in widget.findChildren(QPushButton) if b.isVisible()]


def buttons_of(widget):
    return [b.text().replace("&", "") for b in widget.findChildren(QPushButton)]


def enabled(widget, text):
    return next(b.isEnabled() for b in widget.findChildren(QPushButton) if b.text() == text and b.isVisible())


def launch_extras(win):
    more = win.side.findChild(QToolButton, "more")
    return [a.text() for a in more.menu().actions()] if more else []


def tests(app):
    ws = workspace()
    win = MainWindow(ws, QSettings(str(TMP / "window.ini"), QSettings.IniFormat))
    said = {"alerts": [], "fails": []}
    win.alert = lambda heading, text: said["alerts"].append(text)
    win.fail = lambda e, heading="": said["fails"].append(e)
    window_module.ask = lambda *a: True            # every "are you sure?" answered yes
    win.resize(1180, 720)
    win.show()

    print("\nThe main window, laid out as Prism's")
    check("the bar on top: Add Instance, Folders, Settings, Help; Accounts on the right",
          list(win.bar_buttons) == ["Add Instance", "Folders", "Settings", "Help", "Accounts"],
          list(win.bar_buttons))
    add = win.bar_buttons["Add Instance"]
    check("...Add Instance with Add Group under its arrow",
          [a.text() for a in add.menu().actions()] == ["Add Instance…", "Add Group…"])
    folders = [a.text() for a in win.bar_buttons["Folders"].menu().actions() if a.text()]
    check("...Folders opens each folder, or copies its path (for a machine with no file manager)",
          folders[:5] == ["Instances", "Groups", "Servers", "Shared", "Accounts"]
          and "Copy a folder's path" in folders, folders)
    check("...Help has Doctor and the documentation",
          {"Doctor…", "Documentation", "About Masurium Launcher"}
          <= {a.text() for a in win.bar_buttons["Help"].menu().actions()})
    check("the window is called Masurium Launcher", win.windowTitle() == "Masurium Launcher", win.windowTitle())
    check("the logo is there, for the window's icon and About", theme.logo(64).width() == 64, theme.LOGO)
    check("there is a way out without a title bar: Quit (Ctrl+Q)",
          any(a.text() == "Quit" and not a.shortcut().isEmpty() for a in win.toolbar_actions()))

    print("\nEvery instance, by group")
    check("it reads the instances on a thread and draws them",
          wait(lambda: set(win.tiles) == {"alice", "bob", "carol"}), list(win.tiles))
    check("a section per group, the dependency group inside the one it is in",
          set(win.sections) == {"team", "alice-guards"}
          and win.sections["team"].isAncestorOf(win.sections["alice-guards"]), list(win.sections))
    tile = win.tiles["carol"]
    check("a tile is Prism's: its face over its name", tile.title.text() == "carol"
          and tile.pic.pixmap() is not None and tile.pic.geometry().bottom() <= tile.title.geometry().top())
    check("a guard's tile says whom it guards", "guard of Alice" in win.tiles["bob"].note.toolTip(),
          win.tiles["bob"].note.toolTip())
    dep = win.sections["alice-guards"]
    check("a dependency group is a small map: its leader above, its guards below it",
          dep.leader is win.tiles["alice"] and not dep.tiles.isAncestorOf(win.tiles["alice"])
          and dep.tiles.isAncestorOf(win.tiles["bob"]))
    wait(lambda: win.tiles["bob"].isVisibleTo(win), 5)
    dep._fold()
    check("folded, its guards hide and its leader stays", win.tiles["alice"].isVisibleTo(win)
          and not win.tiles["bob"].isVisibleTo(win))
    dep._fold()
    check("the status bar counts them", "3 instance(s)" in win.statusBar().currentMessage(),
          win.statusBar().currentMessage())
    shot(win, "main")

    # Widgets added to a window already showing are shown by Qt a moment later.
    wait(lambda: win.tiles["alice"].isVisibleTo(win), 5)
    win.search.setText("bob")
    check("search leaves only what matches", not win.tiles["alice"].isVisibleTo(win)
          and win.tiles["bob"].isVisibleTo(win))
    win.search.setText("")

    print("\nThe panel on the right: what can be done with the one selected")
    win.select(("instance", "alice"))
    QApplication.processEvents()
    side = buttons(win.side)
    check("Prism's column: Launch, Kill, Edit, Change Group, Folder, Copy, Delete",
          side[:7] == ["Launch", "Kill", "Edit", "Change Group", "Folder", "Copy", "Delete"], side)
    check("...stopped, it can be launched and not killed", enabled(win.side, "Launch")
          and not enabled(win.side, "Kill"))
    check("...Restart, Connect again and Start its bridge under Launch's arrow",
          launch_extras(win) == ["Restart", "Connect again", "Start its bridge"], launch_extras(win))
    check("...the tile selected is marked", win.tiles["alice"].property("selected")
          and not win.tiles["carol"].property("selected"))
    menu = {k: [a.text for a in win._instance_actions(k) if a] for k in ("alice", "bob", "carol")}
    check("right-click: an instance can lead a new dependency group, from its own menu",
          "New Dependency Group…" in menu["carol"], menu["carol"])
    check("...a leader adds guards to its own instead, and a guard cannot lead",
          "Add Guards…" in menu["alice"]
          and not next(a for a in win._instance_actions("bob") if a and a.text == "New Dependency Group…").enabled,
          menu)
    check("...only in the menu: the panel stays Prism's", "Add Guards…" not in side, side)
    dialog = dialogs.NewGroupDialog(win, leader=ws.instance("carol"))
    check("...its dialog asks only for the name, carol-guards to start with",
          dialog.key.text() == "carol-guards" and dialog.dependency.isChecked()
          and "carol" in dialog.windowTitle(), dialog.windowTitle())
    dialog._create()
    team = ws.group("team")
    check("...and the new group takes carol's place in team, led by carol",
          "carol" not in team.instance_keys() and "carol-guards" in team.group_keys()
          and ws.group("carol-guards").leader == "carol", team.data)
    ops.delete_group(ws, "carol-guards")
    ops.group_add(ws, "team", ["carol"])
    win.refresh()
    shot(win, "instance-selected")
    win.select(("group", "team"))
    QApplication.processEvents()
    side = buttons(win.side)
    check("a group: Launch all, Kill all, Edit, Add to it, Add instance here…",
          side[:5] == ["Launch all", "Kill all", "Edit", "Add to it", "Add instance here"], side)
    shot(win, "group-selected")

    print("\nRight-click on empty space: add right there")
    space = [a.text for a in win._space_actions("team") if a]
    check("in a group: an instance or a group into it, or an instance in none",
          space == ["Add instance to team…", "Add group inside team…", "Add Instance (in no group)…"], space)
    space = [a.text for a in win._space_actions("")]
    check("on the background: an instance or a group, in no group", space == ["Add Instance…", "Add Group…"],
          space)
    space = [a.text for a in win._space_actions("alice-guards") if a]
    check("in a dependency group: guards", space[0] == "Add guards to alice-guards…", space)

    print("\nStarting: on a thread, and what goes wrong is said")
    win.select(("instance", "alice"))
    alice = ws.instance("alice")
    win.launch("alice")
    check("while it works the tile says so", wait(lambda: win.tasks.busy("alice") is None or
                                                   "starting" in win.tiles["alice"].note.text(), 5))
    check("a server mod that does not answer is a failure, shown",
          wait(lambda: said["fails"], 20) and getattr(said["fails"][0], "code", "") == "server_mod_down",
          said["fails"])
    check("...and noted in the instance's recent activity",
          any("failed" in line for line in win.activity["alice"]), list(win.activity["alice"]))

    print("\nAnimated icons")
    check("every icon draws, at rest and mid-gesture",
          all(not icons.pixmap(n, 18, t=t).isNull() for n in icons.DRAW for t in (0.0, 0.5)))
    gear = win.bar_buttons["Settings"]
    QApplication.sendEvent(gear, QEvent(QEvent.Enter))
    check("hovered, an icon makes its gesture", gear._gesture.motion.state() == QVariantAnimation.Running)
    gear._gesture.motion.stop()
    view = win.snapshot.instances["carol"]
    tile.update_view(view, "starting")
    check("an instance's dot pulses while it works", tile.pulse.state() == QVariantAnimation.Running)
    tile.update_view(view, None)
    check("...and stops when it is done", tile.pulse.state() != QVariantAnimation.Running)

    print("\nEdit Instance: its pages, Prism's way")
    ed = win.edit_instance("alice")
    check("its pages down the left", list(ed.pages) == ["Settings", "Rules", "Personality", "Memory", "Mods", "Logs"],
          list(ed.pages))
    check("...Launch and Kill at the bottom, and Help", {"Launch", "Kill", "Help", "Close"} <= set(buttons_of(ed)),
          buttons_of(ed))
    check("one window per instance, brought forward when open", win.edit_instance("alice") is ed)
    d = ed.pages["Settings"]
    box, _, _ = d.editors["model"]
    check("nothing is a blank box: what applies without it is there, and where it comes from",
          box.currentText() == "opus medium   ·   default" and d.value_of("model") == "", box.currentText())
    choice = d.editors["account"][0]
    check("how it plays is a switch: offline, with its player name, or a Microsoft account",
          isinstance(choice, dialogs.AccountChoice) and not choice.microsoft.isChecked()
          and choice.name.text() == "Alice" and d.value_of("account") == "" and d.value_of("name") == "Alice",
          (choice.name.text(), d.value_of("account"), d.value_of("name")))
    check("yes or no is a switch", isinstance(d.editors["lock"][0], Switch)
          and isinstance(d.editors["ignore_global"][0], Switch))
    check("...fast responses too, on by default", isinstance(d.editors["fast_responses"][0], Switch)
          and d.editors["fast_responses"][0].isChecked())
    check("switches left alone change nothing", all(d.value_of(k) == "" for k in ("lock", "ignore_global",
                                                                               "fast_responses")))
    check("Java can be chosen per instance", "java" in d.editors and "java_args" in d.editors)
    cooldown = d.editors["chat_cooldown"][0]
    check("the chat cooldown is there to pick or type, 10 s by default",
          cooldown.isEditable() and cooldown.currentText().startswith("10") and d.value_of("chat_cooldown") == "",
          cooldown.currentText())
    box.setCurrentText("sonnet")
    d._apply()
    check("a setting changed lands in the instance's layer, and only it",
          wait(lambda: alice.data.get("model") == "sonnet") and "fast_responses" not in alice.data, alice.data)
    shot(ed, "edit-instance")
    config = alice.gamedir / "config"
    config.mkdir(parents=True, exist_ok=True)
    (config / "masurium-places-test.txt").write_text("table,overworld,1,64,2\npoint,the_nether,9,70,9,the bridge\n")
    (config / "masurium-diary-test.txt").write_text("2026-09-20 10:00 | I built a house\n")
    mem = ed.page("Memory")
    mem._fill()
    check("memory: its places, with their coordinates and dimension",
          mem.places.topLevelItemCount() == 2 and mem.places.topLevelItem(1).text(0) == "the bridge"
          and mem.places.topLevelItem(1).text(2) == "the_nether", mem.places.topLevelItemCount())
    mem.dimension.setCurrentIndex(mem.dimension.findData("the_nether"))
    check("...one dimension at a time, if asked", mem.places.topLevelItemCount() == 1)
    check("...and the texts it wrote down", mem.texts.topLevelItem(0).text(1) == "I built a house")
    dlg = dialogs.PlaceDialog(mem)
    dlg.name.setText("  fábrica ")
    for box, value in zip(dlg.xyz, (32, 67, 323)):
        box.setValue(value)
    check("a place to remember: its name, a point by default, the overworld, x y z",
          dlg.place() == memory.Place("point", "overworld", 32, 67, 323, "fábrica"), dlg.place())
    mem._do(memory.remember_place, dlg.place())
    mem.dimension.setCurrentIndex(0)
    check("...remembered, and listed", any(mem.places.topLevelItem(i).text(0) == "fábrica"
                                          for i in range(mem.places.topLevelItemCount())))
    mem.places.clearSelection()
    mem._forget_place()
    check("...Forget with nothing chosen says so", "Choose a place" in said["alerts"][-1])
    shot(ed, "memory")
    logs = ed.page("Logs")
    check("logs: each one, or that there is nothing yet", logs.tabs.count() == 3
          and logs.views[0][0].toPlainText() != "")
    ed.close()
    wait(lambda: "alice" not in win.editors, 3)
    win.tiles["alice"].opened.emit("alice")
    check("a double click opens it at its logs", wait(lambda: "alice" in win.editors, 3)
          and win.editors["alice"].stack.currentWidget() is win.editors["alice"].pages["Logs"])
    win.editors["alice"].close()
    g = dialogs.EditGroupDialog(win, ws.group("team"))
    g.pages["Settings"].editors["lock"][0].setChecked(True)
    g.pages["Settings"]._apply()
    check("a group's settings land in its group.json", wait(lambda: ws.group("team").locked))
    g.close()

    print("\nRules")
    ops.create_group(ws, "tidy")
    r = dialogs.EditGroupDialog(win, ws.group("tidy")).pages["Rules"]
    check("a layer's rules load", wait(lambda: r.isEnabled()))
    cell = r.toggles.cellWidget(list(rules.TOGGLES).index("hunt_players"), 0)
    box = cell.findChild(Switch)
    check("each toggle is a switch, with its name", box is not None
          and cell.findChild(QLabel).text() == "hunt_players")
    box.setChecked(True)
    r.lists["food"][1].setText("rotten_flesh")
    r._list_change("food", "ban")
    check("changes are collected and shown before they are sent",
          r.changes == [["pref", "hunt_players", "on"], ["food", "ban", "rotten_flesh"]]
          and "food ban rotten_flesh" in r.pending.text(), r.changes)
    r._apply()
    check("Apply sends them: the group's rules have them",
          wait(lambda: ws.group("tidy").data.get("rules") == {"prefs": {"hunt_players": True},
                                                              "food": {"ban": ["rotten_flesh"]}}),
          ws.group("tidy").data)
    ops.delete_group(ws, "tidy")
    ops.edit_layer(ws, ["pref", "tame_wolves", "off"])
    ri = dialogs.EditInstanceDialog(win, alice, start="Rules").pages["Rules"]
    check("an instance's rules load, and say its server does not answer",
          wait(lambda: ri.isEnabled()) and "does not answer" in ri.note.text(), ri.note.text())
    tame = ri.toggles.cellWidget(list(rules.TOGGLES).index("tame_wolves"), 0).findChild(Switch)
    check("what is imposed cannot be ticked", not tame.isEnabled())
    ri._change(["pref", "tame_wolves", "on"])
    check("...and a change to it is refused, saying who imposes it",
          said["alerts"] and "imposed by global" in said["alerts"][-1] and not ri.changes, said["alerts"])
    ri.lists["food"][1].setText("beef")
    ri._list_change("food", "ban")
    ri._apply()
    check("an instance's change waits for its server when it does not answer",
          wait(lambda: rules.pending(alice) == [{"kind": "food", "key": "beef", "value": "ban"}]),
          rules.pending(alice))
    ops.edit_layer(ws, ["pref", "tame_wolves", "default"])

    print("\nSettings: the launcher's own, Java, global, accounts")
    sw = dialogs.SettingsWindow(win)
    check("its pages", list(sw.pages) == ["Launcher", "Java", "Global settings", "Global rules", "Accounts",
                                          "Servers"], list(sw.pages))
    check("Java: which one the bots run on, and its arguments, for every instance",
          set(sw.pages["Java"].editors) == {"java", "java_args"}, list(sw.pages["Java"].editors))
    lp = sw.pages["Launcher"]
    lp.preset.setCurrentText("Classic light")
    check("a style is seen at once, and remembered", theme.current["name"] == "Classic light"
          and win.store.value("appearance/style") == "Classic light")
    shot(win, "classic-light")
    lp.preset.setCurrentText("Lavender light")
    shot(win, "lavender-light")
    lp.preset.setCurrentText("Lavender dark")
    lp.every.setValue(5)
    check("how often it looks, too", win.timer.interval() == 5000)
    lp.animations.setChecked(False)
    check("animations can be turned off, and that is remembered", not anim.enabled
          and win.store.value("appearance/animations") in (False, "false"))
    lp.animations.setChecked(True)
    lp.animated_icons.setChecked(False)
    check("animated icons too, on their own", not icons.animated and anim.enabled
          and win.store.value("appearance/animated_icons") in (False, "false"))
    tile, view = win.tiles["carol"], win.snapshot.instances["carol"]     # the grid was drawn again since
    tile.update_view(view, "starting")
    check("...off, a working instance's dot stays still", tile.pulse.state() != QVariantAnimation.Running)
    tile.update_view(view, None)
    lp.animated_icons.setChecked(True)
    shot(sw, "settings-launcher")
    sw.show()
    wait(lambda: sw.isVisible(), 2)

    def covers():
        return [c for c in sw.stack.findChildren(QLabel, options=Qt.FindDirectChildrenOnly)
                if not c.pixmap().isNull()]
    acc = sw.page("Accounts")
    check("turning a page: the new one is there at once, a picture of the old one fading over it",
          sw.stack.currentWidget() is acc and len(covers()) == 1, covers())
    check("...gone when the fade ends, and the page itself never faded",
          wait(lambda: not covers(), 2) and acc.graphicsEffect() is None, covers())
    sw.hide()
    check("accounts: none yet, said", acc.list.topLevelItemCount() == 1
          and "none" in acc.list.topLevelItem(0).text(0))
    dave = ws.account("dave")
    (dave.auth).mkdir(parents=True)
    dave.json.write_text('{"name": "Dave"}')
    dave.logins.write_text('{"accounts": [{"mcProfile": {"name": "Dave"}}]}')
    acc._fill()
    first = acc.list.topLevelItem(0)
    check("a Microsoft account is listed with its player and its login", acc.list.topLevelItemCount() == 1
          and (first.text(0), first.text(1)) == ("Dave", "logged in"), first.text(1))
    check("...and offline ones are no more: an offline instance only has a name",
          "Add Offline" not in buttons_of(acc))
    acc._remove()
    check("...Remove with nothing chosen says so", "Choose an account" in said["alerts"][-1])
    shot(sw, "settings-accounts")
    sw.close()

    print("\nAdding, grouping, copying, deleting")
    n = dialogs.NewInstanceDialog(win)
    check("a new instance plays offline by default, with its player name to write",
          not n.account.microsoft.isChecked() and not n.account.name.isHidden() and n.account.accounts.isHidden())
    check("...with Help, as every dialog", "Help" in buttons_of(n))
    n.account.name.setText("Erin")
    check("its name in the launcher is filled in from the player's", n.key.text() == "erin", n.key.text())
    n._create()
    check("an offline instance is created, playing as that name",
          wait(lambda: "erin" in ws.instance_keys()) and ws.instance("erin").name == "Erin"
          and ws.instance("erin").data.get("name") == "Erin", ws.instance_keys())
    wait(lambda: win.tasks.busy("new instance") is None, 5)
    ms = dialogs.NewInstanceDialog(win)
    ms.account.microsoft.setChecked(True)
    offered = [ms.account.accounts.itemData(i) for i in range(ms.account.accounts.count())]
    check("switched to Microsoft, it plays with one of the accounts, or a new one is added",
          offered == ["dave", dialogs.NEW_ACCOUNT] and ms.account.name.isHidden(), offered)
    ms.account.accounts.setCurrentIndex(ms.account.accounts.findData("dave"))
    ms._suggest()
    check("...and its name comes from the account's player", ms.key.text() == "dave", ms.key.text())
    ms._create()
    check("a new instance is created, playing as that account",
          wait(lambda: "dave" in ws.instance_keys()) and ws.instance("dave").name == "Dave"
          and ws.instance("dave").data.get("account") == "dave", ws.instance_keys())
    check("...and gets its tile", wait(lambda: "dave" in win.tiles),
          f"{list(win.tiles)}; said: {said['alerts'][-2:]} "
          f"{[repr(e) + getattr(e, 'trace', '')[-800:] for e in said['fails'][-2:]]}")
    here = dialogs.NewInstanceDialog(win, group="team")
    check("added from a group's right-click, it goes into that group", here.group.currentData() == "team")
    here.close()
    ng = dialogs.NewGroupDialog(win)
    ng.show()
    check("a new group's leader is only asked for a dependency group", not ng.leader.isVisible())
    ng.dependency.setChecked(True)
    check("...and asked for one", ng.leader.isVisible())
    ng.normal.setChecked(True)
    ng.key.setText("extra")
    ng._create()
    check("a new group is created", ws.group("extra").exists())
    ng.close()
    mv = dialogs.MoveDialog(win, ws.instance("dave"))
    mv.to.setCurrentIndex(mv.to.findData("extra"))
    mv._move()
    check("an instance changes group", groups.parent_of(ws, ws.instance("dave")) == ws.group("extra"))
    mg = dialogs.MoveDialog(win, ws.instance("bob"))
    check("changing a guard's moves its dependency group instead", mg.node == ws.group("alice-guards"))
    c = dialogs.CloneDialog(win, ws.instance("carol"))
    check("a copy's name is filled in", c.key.text() == "carol-1", c.key.text())
    c._clone()
    check("an instance is copied", wait(lambda: "carol-1" in ws.instance_keys()), ws.instance_keys())
    check("the window follows: new tiles and sections",
          wait(lambda: {"dave", "carol-1"} <= set(win.tiles) and "extra" in win.sections),
          f"{list(win.tiles)} {list(win.sections)}; running: {win.tasks.running()}; "
          f"status: {win.statusBar().currentMessage()!r}; said: {said['alerts'][-2:]} "
          f"{[repr(e) + getattr(e, 'trace', '')[-800:] for e in said['fails'][-2:]]}")
    win.select(("instance", "carol-1"))
    win.delete_instance("carol-1")
    check("an instance is deleted, and its tile goes",
          "carol-1" not in ws.instance_keys() and wait(lambda: "carol-1" not in win.tiles), list(win.tiles))
    said["fails"].clear()
    win.delete_instance("alice")
    check("a leader is not deleted from under its guards, and why is said",
          "alice" in ws.instance_keys() and said["fails"] and said["fails"][-1].code == "leader", said["fails"])

    print("\nDragging into groups, and a bot's picture")
    dave = ws.instance("dave")
    win.move_node("instance:dave", "team")
    check("an instance dropped on a group moves into it", groups.parent_of(ws, dave) == ws.group("team"))
    check("...and is shown arriving", win.just_moved == "instance:dave" or wait(lambda: win.just_moved is None))
    win.move_node("instance:bob", "")
    check("a guard dropped outside takes its dependency group along",
          groups.parent_of(ws, ws.group("alice-guards")) is None
          and groups.parent_of(ws, ws.instance("bob")) == ws.group("alice-guards"))
    win.move_node("group:alice-guards", "team")
    check("a group dropped on another goes inside it", groups.parent_of(ws, ws.group("alice-guards")) == ws.group("team"))
    said["fails"].clear()
    win.move_node("group:team", "alice-guards")
    check("one that cannot go there stays where it was, and says why",
          said["fails"] and groups.parent_of(ws, ws.group("team")) is None, said["fails"])
    check("the window redraws the tree after a move", wait(lambda: "dave" in win.tiles
                                                          and win.sections["team"].isAncestorOf(win.tiles["dave"])))
    image = QImage(64, 64, QImage.Format_ARGB32)
    image.fill(QColor("#e0a040"))
    win.set_face("alice", image)
    check("an instance's picture is kept in its folder", ws.instance("alice").icon.is_file())
    check("...and its tiles show it", wait(lambda: win.tiles["alice"].face and win.tiles["alice"].face[0]))
    win.set_face("alice", QImage())
    check("pasting with no picture copied says so", said["alerts"] and "no picture" in said["alerts"][-1].lower())
    win.select(("instance", "alice"))
    shot(win, "picture")
    win.set_face("alice", None)
    check("...and it can be taken away", not ws.instance("alice").icon.exists()
          and wait(lambda: not win.tiles["alice"].face[0]))
    ops.create_group(ws, "night shift")
    check("a group's name may have spaces: it is no player", ws.group("night shift").exists())

    print("\nThe rest")
    doc = dialogs.DoctorDialog(win)
    check("doctor lists its checks", wait(lambda: doc.list.count() > 5), doc.list.count())
    check("...and can be closed from a button", "Close" in buttons_of(doc))
    shot(doc, "doctor")
    check("there are no bots apart from the instances: no Bots window",
          not hasattr(dialogs, "BotsDialog") and not hasattr(dialogs, "EditBotDialog"))

    print("\nSetting up a new machine")
    from .firstrun import SetupDialog
    check("Help offers to set up this machine",
          "Set up this machine…" in {a.text() for a in win.bar_buttons["Help"].menu().actions()})
    check("the app's icon is the tile with only its symbol, at every size a desktop asks for",
          sorted(s.width() for s in theme.app_icon().availableSizes()) == [32, 48, 64, 128, 256, 512],
          theme.app_icon().availableSizes())
    fresh_root = TMP / "fresh"
    fresh = Workspace(fresh_root / "bots", fresh_root / "servers", fresh_root / "shared",
                      fresh_root / "server.env", home=fresh_root, environ=ws.environ,
                      instances_dir=fresh_root / "instances", state_dir=fresh_root)
    new_win = MainWindow(fresh, QSettings(str(TMP / "fresh.ini"), QSettings.IniFormat))
    new_said = []
    new_win.alert = lambda heading, text: new_said.append(text)
    new_win.fail = lambda e, heading="": new_said.append(str(e))
    offered = []
    new_win.open_setup = lambda: offered.append(True)
    new_win.first_run()
    check("a new machine is offered the setup when the window opens", wait(lambda: offered, 20))
    offered.clear()
    win_offered = []
    win.open_setup = lambda: win_offered.append(True)
    win.first_run()
    wait(lambda: False, 1.5)
    check("...one that has everything is not", not win_offered)
    del win.open_setup

    print("\nWhat is newer, said on top")
    check("the latest launcher, and the bots on its jars: nothing is said",
          wait(lambda: False, 1) or not win.notices, list(win.notices))
    (ws.state_dir / updates.CACHE).unlink(missing_ok=True)
    updates.fetch_latest = lambda: ("v99.0.0", "https://example.invalid/v99.0.0")
    win.news()
    check("a newer Masurium is said in a notice on top", wait(lambda: "release" in win.notices))
    bar = win.notices.get("release")
    said = " ".join(lab.text() for lab in bar.findChildren(QLabel)) if bar else ""
    check("...with its version, how this copy is updated, and its page",
          "99.0.0" in said and updates.how_to_update() in said and "What is new" in buttons_of(bar), said)
    shot(win, "notice")
    next(b for b in bar.findChildren(QPushButton) if b.text() == "Not now").click()
    check("...closed, it goes", "release" not in win.notices)
    win.news()
    wait(lambda: False, 1)
    check("...and is not said again for that release", "release" not in win.notices)
    real_made, real_install = updates.made_by_installer, updates.install_latest
    installed, restarted = [], []
    try:
        updates.made_by_installer = lambda root=None: True
        updates.install_latest = lambda on_event=None, cancel=None: installed.append(1) or "99.0.1"
        win.restart = lambda: restarted.append(1)
        (ws.state_dir / updates.CACHE).unlink(missing_ok=True)
        updates.fetch_latest = lambda: ("v99.0.1", "https://example.invalid/v99.0.1")
        win.news()
        check("a copy install.sh made is offered to update itself, from the notice",
              wait(lambda: "release" in win.notices) and "Update now…" in buttons_of(win.notices["release"]))
        bar = win.notices.get("release")
        next(b for b in bar.findChildren(QPushButton) if b.text() == "Update now…").click()
        check("...asked, it installs the release, and offers to restart the launcher",
              wait(lambda: installed and restarted) and "release" not in win.notices, (installed, restarted))
    finally:
        updates.made_by_installer, updates.install_latest = real_made, real_install
        del win.restart
        updates.fetch_latest = LATEST
        (ws.state_dir / updates.CACHE).unlink(missing_ok=True)

    fake = TMP / "fake-launcher"
    (fake / "jars").mkdir(parents=True)
    (fake / "jars" / "masurium-1.0.1.jar").write_bytes(b"newer")
    real_repo = ops.REPO
    ops.REPO = fake
    try:
        win.news()
        check("a launcher that brings a newer mod than the bots run says so", wait(lambda: "jars" in win.notices))
        bar = win.notices.get("jars")
        said = " ".join(lab.text() for lab in bar.findChildren(QLabel)) if bar else ""
        check("...naming both jars, with a button to put it in and one to find it",
              "masurium-1.0.0.jar" in said and "masurium-1.0.1.jar" in said
              and {"Update the bots' mod…", "Show the jar"} <= set(buttons_of(bar)), said)
        asked = []
        window_module.ask = lambda parent, heading, text: asked.append(text) or True
        next(b for b in bar.findChildren(QPushButton) if b.text() == "Update the bots' mod…").click()
        check("...it asks first, saying the server needs the same jar",
              asked and "server needs the same jar" in asked[0] and "masurium-1.0.1.jar" in asked[0], asked)
        mods = ws.shared_dir / "mods"
        check("...then puts it in: the old one goes, and the notice with it",
              wait(lambda: (mods / "masurium-1.0.1.jar").is_file() and "jars" not in win.notices)
              and not (mods / "masurium-1.0.0.jar").exists(), sorted(p.name for p in mods.iterdir()))
    finally:
        ops.REPO = real_repo
        window_module.ask = lambda *a: True
    setup = SetupDialog(new_win)
    check("the setup lists what a first bot needs", wait(lambda: setup.list.count() >= 7 and setup.needs),
          setup.list.count())
    lacking = {n.key for n in setup.needs if not n.ok}
    check("...on a new machine: the downloads, the way to the server, a server",
          {"download:headlessmc-launcher.jar", "server_env", "server"} <= lacking, lacking)
    check("...the button to get the downloads is on, the one to add the first instance is not yet",
          setup.get.isEnabled() and not setup.first.isVisible() and setup.add_button.isEnabled())
    check("...the token is not shown as it is typed", setup.token.echoMode() == QLineEdit.Password)
    setup.host.setText("10.0.0.5")
    setup.token.setText("")
    setup._connect()
    check("...a way to the server without its token is refused, saying what it needs",
          wait(lambda: new_said, 10) and "token" in new_said[-1] and not (fresh_root / "server.env").exists(),
          new_said)
    shot(setup, "setup")
    setup.reject()
    new_win.close()
    shot(win, "main-after")
    win.close()


def main():
    app = QApplication.instance() or QApplication(sys.argv)
    theme.apply(app)
    try:
        tests(app)
    except Exception as e:
        import traceback
        traceback.print_exc()
        failures.append(f"crashed: {e}")
    print(f"\n{done - len(failures)}/{done} checks pass")
    if failures:
        print("failed:")
        for f in failures:
            print(f"  - {f}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())


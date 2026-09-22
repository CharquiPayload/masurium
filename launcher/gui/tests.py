#!/usr/bin/env python3
"""Tests of the window, drawn offscreen: no screen, no Minecraft, no server.

What is tested is that the window shows what is there and hands what a
person does to the same operations the command line uses; the operations
themselves are tested in launcher/tests.py.

Run:  python3 -m launcher.gui.tests   (needs PySide6)
With MARIONETTE_GUI_SHOTS=<folder>, it also leaves screenshots there.
"""
import os
import pathlib
import sys
import tempfile
import time

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QSettings  # noqa: E402
from PySide6.QtGui import QColor, QImage  # noqa: E402
from PySide6.QtWidgets import QApplication, QLabel, QPushButton  # noqa: E402

from .. import groups, operations as ops, rules  # noqa: E402
from ..workspace import Workspace  # noqa: E402
from . import dialogs, theme  # noqa: E402
from .widgets import Switch  # noqa: E402
from .window import MainWindow  # noqa: E402

TMP = pathlib.Path(tempfile.mkdtemp(prefix="marionette-gui-test-"))
SHOTS = os.environ.get("MARIONETTE_GUI_SHOTS")

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
        QApplication.processEvents()
        widget.grab().save(str(pathlib.Path(SHOTS) / f"{name}.png"))


def workspace():
    for d in ("bots", "instances", "servers/test/mods", "servers/other/mods", "shared/mods"):
        (TMP / d).mkdir(parents=True, exist_ok=True)
    (TMP / "shared" / "headlessmc-launcher.jar").write_bytes(b"not really a jar")
    (TMP / "shared" / "mods" / "marionette-1.0.0.jar").write_bytes(b"m")
    (TMP / "servers" / "test" / "server.conf").write_text("HOST=127.0.0.1\nDESCRIPTION=\"the test one\"\n")
    (TMP / "servers" / "other" / "server.conf").write_text("HOST=127.0.0.2\n")
    # A server mod that refuses at once: every question to it fails in no time.
    (TMP / "server.env").write_text("MARIONETTE_HOST=127.0.0.1\nMARIONETTE_PORT=1\nMARIONETTE_TOKEN=t\n")
    environ = {k: v for k, v in os.environ.items() if not k.startswith("MARIONETTE_")}
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


def tests(app):
    ws = workspace()
    win = MainWindow(ws, QSettings(str(TMP / "window.ini"), QSettings.IniFormat))
    said = {"alerts": [], "fails": []}
    win.alert = lambda heading, text: said["alerts"].append(text)
    win.fail = lambda e, heading="": said["fails"].append(e)
    win.resize(1180, 720)
    win.show()

    print("\nThe main window: every instance, by group")
    check("it reads the instances on a thread and draws them",
          wait(lambda: set(win.tiles) == {"alice", "bob", "carol"}), list(win.tiles))
    check("a section per group, the dependency group inside the one it is in",
          set(win.sections) == {"team", "alice-guards"}
          and win.sections["team"].isAncestorOf(win.sections["alice-guards"]), list(win.sections))
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

    win.select(("instance", "alice"))
    QApplication.processEvents()
    check("a stopped instance offers to start, first", buttons(win.side)[:1] == ["▶  Start"], buttons(win.side))
    check("...and its rules, settings, clone, group, logs",
          all(any(t in b for b in buttons(win.side)) for t in ("Rules", "Settings", "Clone", "Change group", "Logs")),
          buttons(win.side))
    win.select(("group", "team"))
    QApplication.processEvents()
    check("a group offers to start everything in it", "▶  Start everything in it" in buttons(win.side),
          buttons(win.side))
    shot(win, "group-selected")

    print("\nStarting: on a thread, and what goes wrong is said")
    win.select(("instance", "alice"))
    alice = ws.instance("alice")
    win._run("alice", "starting", lambda ev, c: ops.bring_up(alice, ev, c))
    check("while it works the tile says so", wait(lambda: win.tasks.busy("alice") is None or
                                                   "starting" in win.tiles["alice"].note.text(), 5))
    check("a server mod that does not answer is a failure, shown",
          wait(lambda: said["fails"], 20) and getattr(said["fails"][0], "code", "") == "server_mod_down",
          said["fails"])
    check("...and noted in the instance's recent activity",
          any("failed" in line for line in win.activity["alice"]), list(win.activity["alice"]))

    print("\nSettings")
    d = dialogs.SettingsDialog(win, alice)
    box, _, _ = d.editors["model"]
    check("nothing is a blank box: what applies without it is there, and where it comes from",
          box.currentText() == "opus medium   ·   default" and d.value_of("model") == "", box.currentText())
    heap, _, _ = d.editors["heap"]
    check("...the bot's, when the bot says it", d.editors["account"][0].currentText() == "offline   ·   bot",
          d.editors["account"][0].currentText())
    check("yes or no is a switch", isinstance(d.editors["lock"][0], Switch)
          and isinstance(d.editors["ignore_global"][0], Switch))
    check("...fast responses too, on by default", isinstance(d.editors["fast_responses"][0], Switch)
          and d.editors["fast_responses"][0].isChecked())
    check("switches left alone change nothing", all(d.value_of(k) == "" for k in ("lock", "ignore_global",
                                                                               "fast_responses")))
    box.setCurrentText("sonnet")
    d._apply()
    check("a setting changed in the dialog lands in the instance's layer, and only it",
          wait(lambda: alice.data.get("model") == "sonnet") and "fast_responses" not in alice.data, alice.data)
    g = dialogs.SettingsDialog(win, ws.group("team"))
    g.editors["lock"][0].setChecked(True)
    g._apply()
    check("...and a group's, in its group.json", wait(lambda: ws.group("team").locked))
    shot(d, "settings")

    print("\nRules")
    r = dialogs.RulesDialog(win, "bot", ws.bot("alice"))
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
    check("Apply sends them: the bot's config has them",
          wait(lambda: ws.bot("alice").data.get("rules") == {"prefs": {"hunt_players": True},
                                                               "food": {"ban": ["rotten_flesh"]}}),
          ws.bot("alice").data)
    ops.edit_layer(ws, ["pref", "tame_wolves", "off"])
    ri = dialogs.RulesDialog(win, "instance", alice)
    check("an instance's rules load, and say its server does not answer",
          wait(lambda: ri.isEnabled()) and "does not answer" in ri.note.text(), ri.note.text())
    shot(ri, "rules")
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

    print("\nCreating, grouping, cloning")
    n = dialogs.NewInstanceDialog(win)
    n.name.setText("Dave")
    check("the new instance's name is filled in as it would be chosen", n.key.text() == "dave", n.key.text())
    n.name.setText("Carol")
    check("...taken, the next free one", n.key.text() == "carol-1", n.key.text())
    n.name.setText("Dave")
    n.account.setCurrentIndex(n.account.findData("offline"))
    n._create()
    check("a new instance is created", wait(lambda: ws.instance_keys().count("dave") == 1), ws.instance_keys())
    check("...and gets its tile", wait(lambda: "dave" in win.tiles), list(win.tiles))
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
    check("an instance moves into a group", groups.parent_of(ws, ws.instance("dave")) == ws.group("extra"))
    mg = dialogs.MoveDialog(win, ws.instance("bob"))
    check("moving a guard moves its dependency group instead", mg.node == ws.group("alice-guards"))
    c = dialogs.CloneDialog(win, ws.instance("carol"))
    check("a clone's name is filled in", c.key.text() == "carol-1", c.key.text())
    c._clone()
    check("an instance is cloned", wait(lambda: "carol-1" in ws.instance_keys()), ws.instance_keys())
    check("the window follows: new tiles and sections",
          wait(lambda: {"dave", "carol-1"} <= set(win.tiles) and "extra" in win.sections), list(win.tiles))

    print("\nDragging into groups, and a bot's picture")
    dave = ws.instance("dave")
    win.move_node("instance:dave", "team")
    check("an instance dropped on a group moves into it", groups.parent_of(ws, dave) == ws.group("team"))
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
    check("a bot's picture is kept with the bot", (ws.bot("alice").dir / "icon.png").is_file())
    check("...and its tiles show it", wait(lambda: win.tiles["alice"].face))
    win.set_face("alice", QImage())
    check("pasting with no picture copied says so", said["alerts"] and "no picture" in said["alerts"][-1].lower())
    win.select(("instance", "alice"))
    shot(win, "picture")
    win.set_face("alice", None)
    check("...and it can be taken away", not (ws.bot("alice").dir / "icon.png").exists()
          and wait(lambda: not win.tiles["alice"].face))
    ops.create_group(ws, "night shift")
    check("a group's name may have spaces: it is no player", ws.group("night shift").exists())

    print("\nThe launcher's own settings")
    ls = dialogs.LauncherSettingsDialog(win)
    ls.preset.setCurrentText("Classic light")
    check("a style is seen at once, before it is saved", theme.current["name"] == "Classic light")
    shot(win, "classic-light")
    ls.reject()
    check("...and cancelled, the one before comes back", theme.current["name"] == "Lavender dark")
    ls = dialogs.LauncherSettingsDialog(win)
    ls.preset.setCurrentText("Lavender light")
    ls.every.setValue(5)
    ls._save()
    check("saved, it is remembered, and so is how often it looks",
          win.store.value("appearance/style") == "Lavender light" and win.timer.interval() == 5000)
    shot(win, "lavender-light")
    win.set_style("Lavender dark")
    check("there is a way out without a title bar: Quit (Ctrl+Q)",
          any(a.text() == "Quit" and not a.shortcut().isEmpty() for a in win.toolbar_actions()))

    print("\nThe rest")
    doc = dialogs.DoctorDialog(win)
    check("doctor lists its checks", wait(lambda: doc.list.count() > 5), doc.list.count())
    check("...and can be closed from a button", "Close" in buttons_of(doc))
    shot(doc, "doctor")
    shot(dialogs.PersonalityDialog(win, ws.bot("alice")), "personality")
    acc = dialogs.AccountsDialog(win)
    check("accounts: none yet, said", acc.list.count() == 1 and "none" in acc.list.item(0).text())
    bots = dialogs.BotsDialog(win)
    shot(bots, "bots")
    check("bots: each with its instances", bots.list.count() == 4
          and any("alice" in bots.list.item(i).text() for i in range(bots.list.count())))
    logs = dialogs.LogDialog(win, alice)
    check("logs: each one, or that there is nothing yet", logs.tabs.count() == 3
          and logs.views[0][0].toPlainText() != "")
    logs.close()
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


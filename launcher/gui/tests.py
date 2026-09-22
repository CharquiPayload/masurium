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
from PySide6.QtWidgets import QApplication, QCheckBox, QPushButton  # noqa: E402

from .. import groups, operations as ops, rules  # noqa: E402
from ..workspace import Workspace  # noqa: E402
from . import dialogs, theme  # noqa: E402
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
    box, _ = d.editors["model"]
    box.setCurrentText("sonnet")
    d._apply()
    check("a setting changed in the dialog lands in the instance's layer",
          wait(lambda: alice.data.get("model") == "sonnet"), alice.data)
    g = dialogs.SettingsDialog(win, ws.group("team"))
    box, _ = g.editors["lock"]
    box.setCurrentText("yes")
    g._apply()
    check("...and a group's, in its group.json", wait(lambda: ws.group("team").locked))
    shot(d, "settings")

    print("\nRules")
    r = dialogs.RulesDialog(win, "bot", ws.bot("alice"))
    check("a layer's rules load", wait(lambda: r.isEnabled()))
    box = r.toggles.cellWidget(list(rules.TOGGLES).index("hunt_players"), 0)
    check("each toggle is a check box", isinstance(box, QCheckBox) and box.text() == "hunt_players")
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
    tame = ri.toggles.cellWidget(list(rules.TOGGLES).index("tame_wolves"), 0)
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
    n.account.setCurrentIndex(n.account.findData("offline"))
    n._create()
    check("a new instance is created", wait(lambda: ws.instance_keys().count("dave") == 1), ws.instance_keys())
    check("...and gets its tile", wait(lambda: "dave" in win.tiles), list(win.tiles))
    ng = dialogs.NewGroupDialog(win)
    ng.key.setText("extra")
    ng._create()
    check("a new group is created", ws.group("extra").exists())
    mv = dialogs.MoveDialog(win, ws.instance("dave"))
    mv.to.setCurrentIndex(mv.to.findData("extra"))
    mv._move()
    check("an instance moves into a group", groups.parent_of(ws, ws.instance("dave")) == ws.group("extra"))
    mg = dialogs.MoveDialog(win, ws.instance("bob"))
    check("moving a guard moves its dependency group instead", mg.node == ws.group("alice-guards"))
    c = dialogs.CloneDialog(win, ws.instance("carol"))
    c._clone()
    check("an instance is cloned", wait(lambda: "carol-1" in ws.instance_keys()), ws.instance_keys())
    check("the window follows: new tiles and sections",
          wait(lambda: {"dave", "carol-1"} <= set(win.tiles) and "extra" in win.sections), list(win.tiles))

    print("\nThe rest")
    doc = dialogs.DoctorDialog(win)
    check("doctor lists its checks", wait(lambda: doc.list.count() > 5), doc.list.count())
    shot(doc, "doctor")
    acc = dialogs.AccountsDialog(win)
    check("accounts: none yet, said", acc.list.count() == 1 and "none" in acc.list.item(0).text())
    bots = dialogs.BotsDialog(win)
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


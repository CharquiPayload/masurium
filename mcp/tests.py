#!/usr/bin/env python3
"""Tests of the MCP layer. No Minecraft, no network, no dependencies.

What is tested here is not "that it works": it is **that it does not lie**.
Every assertion below defends the project rule — the bot cannot be left
wondering "did I do it?" — and each one was born from a real bug:

- saying "there is none" when the honest answer is "I do not know" made the
  bot conclude there were no trees while standing in a forest;
- accepting a made-up id (`tronco` instead of `oak_log`) left it standing
  still, doing nothing and not complaining.

Run:  python3 mcp/tests.py
"""
import json
import os
os.environ.setdefault("BOT_NAME", "Alice")  # fixture name used by the tests
import pathlib
import re
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent

# The config is read on import, so a fake one is pointed at BEFORE.
_cfg = pathlib.Path(tempfile.mkdtemp()) / "server.env"
_cfg.write_text("MARIONETTE_HOST=127.0.0.1\nMARIONETTE_PORT=1\nMARIONETTE_TOKEN=test\n")
os.environ["MARIONETTE_ENV"] = str(_cfg)

sys.path.insert(0, str(HERE))
import server  # noqa: E402
from bridge import drawable  # noqa: E402
import bridge  # noqa: E402


# --- minimal harness --------------------------------------------------------

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


def with_response(payload):
    """Replaces the HTTP queries with a fixed answer.

    BOTH have to be patched: `sv` (the server mod, the truth) and `bt` (the
    bot mod, the hands). Leaving one out makes it go to the real network and
    the test fails for a reason that is not its own.
    """
    def fake(route, **kw):
        return payload(route, **kw) if callable(payload) else payload
    server.sv = fake
    server.bt = fake


# --- locks: who asks comes from the server, never from the brain ------------

def tests_speaker():
    print("\nLocks: who asks comes from the server, never from the brain")
    seen = []

    def record(route, **kw):
        seen.append((route, kw))
        if route == "/food":
            return {"ok": True, "banned": ["salmon"]}
        if route == "/eat":
            return {"ok": False, "error": "not in a test"}   # no 3 s wait
        if route == "/access":
            return {"ok": True, "bot": "Bot", "owner": "Owner", "admins": ["Helper"],
                    "hear": {"mode": "list", "players": ["Friend"]}}
        return {"ok": True}
    with_response(record)
    os.environ["MARIONETTE_SPEAKER"] = "Stranger"
    try:
        out = server.t_eat({"what": "salmon", "who": "Owner"})
        check("eat: banned food refused when the brain names someone who did not speak",
              "banned food list" in out, out)
        before = len(seen)
        server.t_eat({"what": "salmon", "who": "stranger"})
        check("eat: banned food allowed when the one who spoke asked for it",
              ("/eat", {"what": "salmon"}) in seen[before:], seen[before:])
    finally:
        os.environ.pop("MARIONETTE_SPEAKER", None)

    # Taking the bot out of the game, and changing its own rules, are server
    # commands and not tools: a brain with no such tool cannot be talked into
    # using it. The ones that only READ stay, so it still knows its own rules.
    for name in ("add_admin", "remove_admin", "log_off", "restart_me",
                 "set_preference", "allow_break", "forbid_break", "veto_food"):
        check(f"{name}: is not a tool any more (it is /marionette bot)",
              name not in server.TOOLS)
        check(f"{name}: the bridge does not allow it either",
              f"mcp__bot__{name}" not in bridge.TOOLS.split())
    for name in ("show_preferences", "break_permissions", "food_ban", "trash"):
        check(f"{name}: it can still LOOK at its own rules", name in server.TOOLS)
    check("the settings tools take no arguments: nothing to write",
          all(not server.TOOLS[n][3]
              for n in ("show_preferences", "break_permissions", "food_ban")))
    # The trash list is the deliberate exception: it governs only what the bot drops
    # from its OWN backpack, so it stays the bot's to decide.
    check("trash: the bot still decides its own trash", bool(server.TOOLS["trash"][2]))

    # A settings order arrives with an argument and is applied to the body. The
    # bridge talks to the bot with its own request_bot, so that is what is
    # replaced here.
    asked = []
    real_request_bot = bridge.request_bot
    bridge.request_bot = lambda route: (asked.append(route), {"ok": True})[1]
    try:
        bridge.apply_setting("pref", "bunny_hop=true")
        check("pref order: it reaches /preferences with the key and the value",
              asked and asked[-1].startswith("/preferences?place=bunny_hop")
              and "value=true" in asked[-1], asked)
        bridge.apply_setting("pref", "bunny_hop=false")
        check("pref order: anything that is not true is false, never a third thing",
              "value=false" in asked[-1], asked[-1])
        bridge.apply_setting("food", "ban:rotten_flesh")
        check("food order: it reaches /food with ban=",
              "ban=rotten_flesh" in asked[-1], asked[-1])
        bridge.apply_setting("food", "allow:rotten_flesh")
        check("food order: allowing goes with allow=",
              "allow=rotten_flesh" in asked[-1], asked[-1])
        bridge.apply_setting("break", "forbid:dirt")
        check("break order: it reaches /permissions with forbid=",
              "forbid=dirt" in asked[-1], asked[-1])
        bridge.apply_setting("break", "allow:dirt")
        check("break order: allowing goes with allow=",
              "allow=dirt" in asked[-1], asked[-1])
        check("a settings order says nothing in the chat: it is housekeeping",
              all("/say" not in r for r in asked), asked)

        # The whole state at once, as it arrives in every /control answer.
        asked.clear()
        lines = []
        real_log = bridge.log
        bridge.log = lambda text: lines.append(text)
        try:
            bridge.apply_settings({"prefs": {"bunny_hop": True},
                                   "food": {"ban": ["salmon"], "allow": []},
                                   "break": {"allow": ["dirt"], "forbid": []}})
        finally:
            bridge.log = real_log
        check("the whole state is applied: pref, food and break in one pass",
              len(asked) == 3, asked)
        # It says RE-APPLIED and what of: usually nothing changed, because the body
        # already agreed. A line that reads like activity when there was none sends
        # you hunting for a change that never happened.
        said = " ".join(lines)
        check("the log says it re-applied, not that something happened",
              "re-applied" in said, said)
        check("the log says what it re-applied, by family",
              "1 pref" in said and "1 food" in said and "1 break" in said, said)
        lines.clear()
        bridge.log = lambda text: lines.append(text)
        try:
            bridge.apply_settings({})
            bridge.apply_settings(None)
        finally:
            bridge.log = real_log
        check("nothing set: it stays quiet instead of logging a zero", not lines, lines)
    finally:
        bridge.request_bot = real_request_bot
    out = server.t_who_commands({})
    check("who_commands: owner, admins and hearing come from the server",
          "Owner: Owner" in out and "Helper" in out and "Friend" in out, out)
    check("who_commands: it points to the server command, not to the chat",
          "/marionette bot" in out and "shutdown" in out, out)

    access = {"owner": "Owner", "admins": ["Helper"],
              "hear": {"mode": "list", "players": ["Friend"]}}
    check("hear list: the owner, an admin and a listed player are heard",
          all(bridge.hears(w, access) for w in ("owner", "HELPER", "Friend")))
    check("hear list: a stranger and an empty name are not",
          not bridge.hears("Stranger", access) and not bridge.hears("", access))
    check("hear everyone: a stranger is heard",
          bridge.hears("Stranger", {"hear": {"mode": "everyone"}}))
    route = bridge.control_route(5)
    check("control poll: carries the bot, its owner and since",
          route.startswith("/control?") and "bot=" in route and "since=5" in route
          and "owner=" in route, route)
    # The mod version travels with the poll, so the server can say something when a
    # bot joins running another one. A body that cannot answer leaves it OUT rather
    # than sending an empty value: nothing to compare is not the same as disagreeing.
    bridge.MOD_VERSION.clear()
    real_request_bot = bridge.request_bot
    bridge.request_bot = lambda route: {"ok": True, "version": "1.2.3"}
    try:
        check("control poll: carries the version of the bot mod",
              "version=1.2.3" in bridge.control_route(), bridge.control_route())
        # Asked once and remembered: this runs every second.
        calls = []
        bridge.request_bot = lambda route: (calls.append(route), {"version": "9"})[1]
        bridge.control_route()
        bridge.control_route()
        check("the version is asked for once, not on every poll", not calls, calls)
        bridge.MOD_VERSION.clear()
        bridge.request_bot = lambda route: (_ for _ in ()).throw(OSError("body down"))
        check("with the body down the poll still goes, without a version",
              "version=" not in bridge.control_route(), bridge.control_route())
    finally:
        bridge.request_bot = real_request_bot
        bridge.MOD_VERSION.clear()
    lock = pathlib.Path(tempfile.mkdtemp()) / "bridge.lock"
    held = bridge.only_one_bridge(lock)
    try:
        crashed = False
        try:
            bridge.only_one_bridge(lock)
        except SystemExit:
            crashed = True
        check("one bridge per bot: the second one refuses to start", crashed)
    finally:
        held.close()
    check("one bridge per bot: with the first one gone, another may start",
          bridge.only_one_bridge(lock).close() is None)

    check("chat: asking to shut down points to the command, in both languages",
          all("/marionette bot {name} {what}" in bridge.L10N[k]["by_command"]
              for k in ("en", "es")))


# --- what really matters: not lying -----------------------------------------

def tests_honesty():
    print("\nDo not confuse 'I do not know' with 'there is none'")

    # Real bug: searching a sleeping area, a bare "not found" led to the
    # conclusion that there were no logs. The answer has to say why.
    with_response({"ok": True, "found": False,
                   "looked_at": 0, "unloaded": 117649})
    r = server.t_search_block({"block": "oak_log", "x": 0, "y": 64, "z": 0})
    check("unloaded area: says it DOES NOT KNOW", "i do not know" in r.lower(), r)
    check("unloaded area: does NOT claim there is none",
          "there is no oak_log" not in r.lower(), r)
    check("unloaded area: gives the number of sleeping tiles",
          "117649" in r, r)

    # And the opposite case: if it really looked, it may say there is none.
    with_response({"ok": True, "found": False,
                   "looked_at": 68921, "unloaded": 0})
    r = server.t_search_block({"block": "crafting_table", "x": 1, "y": 2, "z": 3})
    check("area really looked at: it MAY say there is none",
          "there is no" in r.lower() and "68921" in r, r)

    # A block in a sleeping chunk is not "there is no block".
    with_response({"ok": False, "error": "chunk not loaded",
                   "x": 0, "y": 64, "z": 0})
    r = server.t_what_is_at({"x": 0, "y": 64, "z": 0})
    check("block in a sleeping chunk: says it does not know",
          "i do not know" in r.lower(), r)


def tests_ores():
    print("\nOres are not searched: they are dug")
    calls = []

    def fake(route, **kw):
        calls.append(route)
        return {"ok": True, "found": True, "block": "iron_ore",
                "x": 1, "y": 2, "z": 3, "distance": 4.0, "looked_at": 9,
                "unloaded": 0}

    with_response(fake)
    for ore in ("iron_ore", "minecraft:diamond_ore", "deepslate_gold_ore",
                "ancient_debris"):
        r = server.t_search_block({"block": ore, "x": 0, "y": 0, "z": 0})
        check(f"{ore}: refuses without asking the server",
              "/search" not in calls and "strip_mine_start" in r, r)
    r = server.t_search_block({"block": "raw_iron_block", "x": 0, "y": 0, "z": 0})
    check("raw_iron_block is not an ore: it is searched",
          "/search" in calls, r)


def tests_ids():
    print("\nA made-up id is rejected, and explained")
    with_response({"ok": False,
                   "error": "I do not know the block 'tronco'; ids go in "
                            "English, like oak_log or coal_ore"})
    r = server.t_search_block({"block": "tronco", "x": 0, "y": 0, "z": 0})
    check("invalid id: the reason is propagated", "tronco" in r, r)
    check("invalid id: says they go in English", "english" in r.lower(), r)
    check("invalid id: suggests a valid id", "oak_log" in r, r)


def tests_fallback():
    print("\nWithout coordinates, the search starts from the spawn")
    seen = []

    def fake(route, **kw):
        seen.append((route, kw))
        if route == "/state":
            return {"ok": False, "error": "no bot"}   # forces using the spawn
        if route == "/health":
            return {"ok": True, "spawn": {"x": 272, "y": 64, "z": -407}}
        return {"ok": True, "found": True, "block": "crafting_table",
                "x": 276, "y": 61, "z": -413, "distance": 7.8,
                "looked_at": 100, "unloaded": 0}

    with_response(fake)
    r = server.t_search_block({"block": "crafting_table"})
    used = [kw for route, kw in seen if route == "/search"][0]
    check("no coords: asks for the spawn first",
          any(route == "/health" for route, _ in seen))
    check("no coords: searches FROM the spawn",
          (used["x"], used["y"], used["z"]) == (272, 64, -407), used)
    check("found: gives coordinates and distance",
          "276" in r and "7.8" in r, r)

    # If not even the spawn is known, help is asked instead of inventing an origin.
    with_response({"ok": False, "error": "down"})
    r = server.t_search_block({"block": "crafting_table"})
    check("no spawn: asks for coordinates instead of inventing them",
          "coordinates" in r.lower(), r)


def tests_chests():
    """The chest memory is tested without the game: fake chest-handler
    outcomes are fed and it is checked that it notes, trims long lists, does
    not take without permission and does not place a covered chest."""
    os.environ["MARIONETTE_CHESTS"] = str(pathlib.Path(tempfile.mkdtemp()) / "chests.json")
    calls = []

    def fake(route, **kw):
        calls.append((route, kw))
        if route == "/state":
            return {"ok": True, "x": 10.0, "y": 64.0, "z": 10.0,
                    "world": "minecraft:overworld",
                    "chest": {"with_chest": False, "outcome": fake.outcome}}
        if route == "/block":
            return {"ok": True, "x": kw["x"], "y": kw["y"], "z": kw["z"],
                    "block": fake.blocks.get((kw["x"], kw["y"], kw["z"]), "air")}
        return {"ok": True}
    fake.outcome = "inside: 64 oak_log, 3 iron_ingot"
    fake.blocks = {(12, 64, 10): "chest", (13, 64, 10): "chest"}
    server.sv = fake
    server.bt = fake

    check("chests: the handler's 'inside:' is read as amounts",
          server._contents_of("took 5 cod; inside: 10 cod, 2 oak_log")
          == {"cod": 10, "oak_log": 2})
    check("chests: the old Spanish wording is read too",
          server._contents_of("tome 5 cod; dentro: 10 cod, 2 oak_log")
          == {"cod": 10, "oak_log": 2})
    check("chests: 'the chest is empty' is empty contents, not a failure",
          server._contents_of("put 3 cod; the chest is empty") == {})
    check("chests: an outcome without contents is not noted",
          server._contents_of("the chest did not open (covered?)") is None)

    server.t_look_in_chest({"x": 12, "y": 64, "z": 10})
    m = list(server._chests_load().values())
    check("chests: looking leaves the contents noted",
          len(m) == 1 and m[0]["contents"] == {"oak_log": 64, "iron_ingot": 3}, m)
    check("chests: someone else's chest is born without known permission",
          m and m[0]["permission"] == "unknown")

    r = server.t_search_chests({"what": "wood"})
    check("chests: searching by family finds the log and says where",
          "64 oak_log" in r and "12 64 10" in r, r)
    check("chests: searching says there is no known permission",
          "no known permission" in r, r)
    check("chest hint: what is missing and remembered in a chest is said on its own",
          "oak_log" in server._chest_hint("oak_log")
          and "take_from_chest" in server._chest_hint("oak_log")
          and server._chest_hint("diamond") == "")
    r = server.t_search_chests({"what": "diamond"})
    check("chests: what I do not remember is 'I do not remember', not 'there is none'",
          "I do not remember" in r, r)

    calls.clear()
    r = server.t_take_from_chest({"x": 12, "y": 64, "z": 10, "what": "oak_log"})
    check("chests: without permission I do not take and I say how to get it",
          "I do not know whether I may" in r and "with_permission_of" in r, r)
    check("chests: without permission I do not even open the chest",
          not any(route == "/chest" for route, _ in calls))

    fake.outcome = "took 64 oak_log; inside: 3 iron_ingot"
    r = server.t_take_from_chest({"x": 12, "y": 64, "z": 10, "what": "oak_log",
                                  "with_permission_of": "Player1"})
    m = list(server._chests_load().values())[0]
    check("chests: with with_permission_of I take and it is noted 'yes' and from whom",
          "took 64" in r and m["permission"] == "yes" and m["of"] == "Player1",
          (r, m))
    check("chests: after taking, what is noted is what was left",
          m["contents"] == {"iron_ingot": 3}, m)

    server.t_annotate_chest({"x": 12, "y": 64, "z": 10, "permission": "no",
                             "of": "Player2"})
    r = server.t_take_from_chest({"x": 12, "y": 64, "z": 10, "what": "iron_ingot"})
    check("chests: forbidden = I do not take, and I say who forbade it",
          "forbade" in r and "Player2" in r, r)

    fake.outcome = "inside: 3 iron_ingot"
    server.t_look_in_chest({"x": 13, "y": 64, "z": 10})
    check("chests: the other half of a double chest is not another chest",
          len(server._chests_load()) == 1,
          list(server._chests_load()))

    length = "inside: " + ", ".join(f"{i + 1} item_{i}" for i in range(40))
    r = server._trim_outcome(length)
    check("chests: 40 types are shown trimmed",
          "40 types" in r and "28 more types" in r and len(r) < len(length), r)
    check("chests: the trim shows the most abundant",
          "40 item_39" in r and "1 item_0," not in r, r)
    check("chests: a short list goes whole",
          server._trim_outcome("inside: 1 a, 2 b") == "inside: 1 a, 2 b")

    calls.clear()
    fake.blocks = {(5, 65, 5): "stone"}
    r = server.t_place({"what": "chest", "x": 5, "y": 64, "z": 5})
    check("place: a chest with stone on top is not placed, and where is said",
          "I am not placing chest" in r and "stone" in r and "5 65 5" in r, r)
    check("place: ...and not even the gesture is made",
          not any(route == "/place" for route, _ in calls))
    fake.blocks = {(6, 64, 6): "chest"}
    r = server.t_place({"what": "chest", "x": 6, "y": 64, "z": 6})
    mine = [c for c in server._chests_load().values() if c["x"] == 6]
    check("place: the chest I place is noted as mine",
          "Placed" in r and mine and mine[0]["permission"] == "own", (r, mine))
    fake.blocks = {(7, 65, 7): "stone", (7, 64, 7): "furnace"}
    r = server.t_place({"what": "furnace", "x": 7, "y": 64, "z": 7})
    check("place: a covered furnace is placed anyway (only the chest needs air)",
          "Placed" in r, r)
    del os.environ["MARIONETTE_CHESTS"]


def tests_places():
    """The memory of places is cleaned on consulting it (a bot once walked to
    a bed that no longer existed)."""
    calls = []

    def fake(route, **kw):
        calls.append((route, kw))
        if route == "/places" and not kw.get("forget"):
            return {"ok": True, "places": [
                {"type": "bed", "x": 1, "y": 64, "z": 1, "dimension": "overworld",
                 "distance": 5.0, "label": ""},
                {"type": "chest", "x": 2, "y": 64, "z": 2, "dimension": "overworld",
                 "distance": 9.0, "label": "blocks chest"},
                {"type": "point", "x": 3, "y": 64, "z": 3, "dimension": "overworld",
                 "distance": 12.0, "label": "the factory"},
                {"type": "furnace", "x": 4, "y": 64, "z": 4, "dimension": "overworld",
                 "distance": 20.0, "label": ""}]}
        if route == "/block":
            if (kw["x"], kw["y"], kw["z"]) == (4, 64, 4):
                return {"ok": False, "error": "not loaded"}
            return {"ok": True, "x": kw["x"], "y": kw["y"], "z": kw["z"],
                    "block": {(1, 64, 1): "air", (2, 64, 2): "chest",
                              (5, 64, 5): "air"}.get((kw["x"], kw["y"], kw["z"]), "air")}
        if route == "/state":
            return {"ok": True, "world": "minecraft:overworld"}
        return {"ok": True}
    server.sv = fake
    server.bt = fake

    r = server.t_places({})
    forgotten = [kw for route, kw in calls if route == "/places" and kw.get("forget")]
    check("places: the bed that is gone is forgotten and said",
          "I forgot them: bed at 1 64 1" in r and "bed at 1 64 1 (" not in r, r)
    check("places: ...really deleting it from the mod's memory",
          [(k["x"], k["y"], k["z"]) for k in forgotten] == [(1, 64, 1)], forgotten)
    check("places: the chest still there stays, with its label",
          "chest 'blocks chest' at 2 64 2 (9 away)" in r, r)
    check("places: a point is not a block and is not checked",
          "point 'the factory' at 3 64 3" in r
          and not any(kw.get("x") == 3 for route, kw in calls if route == "/block"), r)
    check("places: what is unloaded is NOT forgotten ('I do not know' is not 'it is gone')",
          "furnace at 4 64 4" in r, r)

    calls.clear()
    r = server.t_place({"what": "furnace", "x": 5, "y": 64, "z": 5})
    check("place: a failed gesture leaves no ghost place",
          "Do not count on it being placed" in r
          and any(route == "/places" and kw.get("forget") and kw["x"] == 5
                  for route, kw in calls), (r, calls))


def tests_pending():
    """A job that carries on after the turn is written down, and the bridge
    notifies the brain with the outcome when it ends."""
    import bridge
    route = pathlib.Path(tempfile.mkdtemp()) / "pending.json"
    os.environ["MARIONETTE_PENDING"] = str(route)

    def fake(r, **kw):
        if r == "/state":
            return {"ok": True, "x": 1.0, "y": 64.0, "z": 1.0, "hp": 20.0,
                    "in_hand": "none",
                    "fill_job": {"working": fake.active,
                                 "outcome": "finished: 28 blocks placed, 8 skipped",
                                 "deeds": 28, "skipped": 8},
                    "walk": {"walking": fake.active, "outcome": "arrived"}}
        if r == "/chat":
            return {"ok": True, "last": 7, "messages": []}
        if r == "/health":
            return {"ok": True, "players": 1, "spawn": {"x": 0, "y": 64, "z": 0}}
        return {"ok": True}
    fake.active = True
    server.sv = fake
    server.bt = fake

    r = server._wait_task("fill_job", "working", limit=0.5, cut=False)
    jot = json.loads(route.read_text()) if route.exists() else {}
    check("pending: 'still on it' writes down the job and its flag",
          "still on it" in r and jot.get("fill_job", {}).get("field") == "working",
          (r, jot))
    check("pending: ...and tells the brain it will be notified, without reminders",
          "will notify you when done" in r, r)

    notices, remain = bridge.pending_notices(jot, fake("/state"))
    check("pending: while the job goes on, no notice",
          notices == [] and "fill_job" in remain, (notices, remain))
    fake.active = False
    notices, remain = bridge.pending_notices(jot, fake("/state"))
    check("pending: on finishing, ONE notice with the outcome and nothing running",
          notices == ["I finished the fill job that I left running: "
                      "finished: 28 blocks placed, 8 skipped"]
          and remain == {}, (notices, remain))

    server.t_state({})
    check("pending: if the brain looks at the state and sees it finished, the note is deleted",
          json.loads(route.read_text()) == {}, route.read_text())

    fake.active = True
    r = server._wait_task("walk", "walking", limit=0.5, cut=True)
    check("pending: a job cut by the limit is not written down (it will not finish alone)",
          "I cut it" in r and "walk" not in json.loads(route.read_text()),
          (r, route.read_text()))
    del os.environ["MARIONETTE_PENDING"]


def tests_tab():
    """The TAB icon is decided by priority and without inventing states."""
    import bridge
    still = {"ok": True, "dead": False, "lookout": {"fleeing": False},
             "fill_job": {"working": False}, "archery": {"killing": False}}
    base = pathlib.Path(tempfile.mkdtemp())
    (base / "guard").mkdir()
    (base / "guard" / "escort").write_text("Alice\n")
    (base / "guard" / "model").write_text("haiku low\n")
    (base / "alice").mkdir()
    check("guard: the model is per bot (haiku low)",
          bridge.model_of("guard", base) == ("haiku", "low"))
    check("guard: without a file, opus medium",
          bridge.model_of("alice", base) == ("opus", "medium"))
    check("guard: the boss comes from bots/<bot>/escort",
          bridge.boss_of("guard", base) == "Alice")
    check("guard: the boss knows who guards it",
          bridge.guards_of("alice", base) == ["guard"])
    check("guard: without guards, empty list",
          bridge.guards_of("guard", base) == [])
    (base / "guard" / "gender").write_text("m\n")
    check("gender: comes from bots/<bot>/gender",
          bridge.gender_of("guard", base) == "m")
    check("gender: without a file, feminine",
          bridge.gender_of("alice", base) == "f")
    (base / "guard" / "language").write_text("es\n")
    check("language: comes from bots/<bot>/language",
          bridge.language_of("guard", base) == "es")
    check("language: without a file, English",
          bridge.language_of("alice", base) == "en")
    check("gender: stuck and dead have no masculine form in English",
          bridge.with_gender("stuck", "m") == "stuck"
          and bridge.with_gender("dead", "m") == "dead")
    check("gender: the rest, and the feminine, stay the same",
          bridge.with_gender("idle", "m") == "idle"
          and bridge.with_gender("stuck", "f") == "stuck")
    check("gender: no gendered forms in English",
          bridge.MASCULINE == {} and bridge.TAB_FALLBACK["stuck"] == "error")
    check("tab: every named state has a fallback for an old server mod",
          all(e in bridge.TAB_FALLBACK for _, _, e in bridge.FINE_STATES)
          and all(e in bridge.TAB_FALLBACK
                  for e in ("chopping", "gathering", "crafting", "cooking", "sleeping")))
    old_dir, old_name = bridge.BOTS_DIR, bridge.NAME
    try:
        bridge.BOTS_DIR, bridge.NAME = base, "guard"
        g = bridge.guard_block()
        check("carrier: the guard knows it carries its boss's material and does not spend it",
              "MATERIAL CARRIER" in g and "do not use it, toss it or spend it" in g
              and "to=Alice" in g)
        bridge.NAME = "alice"
        j = bridge.guards_block()
        check("carrier: the boss knows how to give it spares and ask for them",
              "MATERIAL CARRIER" in j and "give me 1 fishing_rod" in j
              and "never runs errands far away" in j)
    finally:
        bridge.BOTS_DIR, bridge.NAME = old_dir, old_name
    bots_dir = pathlib.Path(tempfile.mkdtemp())
    (bots_dir / "bob").mkdir()
    os.environ["MARIONETTE_BOTS"] = str(bots_dir)
    r = server.t_internal({"bot": "Bob", "text": "I am hungry"})
    fl = pathlib.Path(server._internal_file("Bob"))
    check("internal: writes one line in internal_<bot>.jsonl",
          "through the internal channel" in r and fl.exists() and fl.read_text().count("\n") == 1)
    msgs, off = bridge.internal_new(0, fl)
    check("internal: the bridge reads it with the sender",
          msgs == [("Alice", "I am hungry")] and off == fl.stat().st_size)
    check("internal: from the end there is nothing new",
          bridge.internal_new(off, fl) == ([], off))
    check("internal: to a bot that does not exist, it refuses",
          "there is no bot called" in server.t_internal({"bot": "Nobody", "text": "hi"}))
    check("internal: to itself, it refuses",
          "talking to myself" in server.t_internal({"bot": "Alice", "text": "hi"}))
    (bots_dir / "bob" / "port").write_text("1\n")   # nobody listens there
    check("internal: if the other bot is not connected, it says so and does not write",
          "is NOT connected" in server.t_internal({"bot": "Bob", "text": "hi"})
          and fl.read_text().count("\n") == 1)
    check("naming: named plainly",
          bridge.names_me("Alice, come"))
    check("naming: with a leading dot it does NOT trigger me (Bob escort .alice)",
          not bridge.names_me("Bob escort .alice"))
    check("naming: the escape dot is removed for the brain",
          bridge.unescape("Bob escort .alice") == "Bob escort alice")
    check("naming: normal dots are left alone",
          bridge.unescape("go to 10.5 and ... come back, alice") == "go to 10.5 and ... come back, alice")
    check("naming: a dot glued to another word is not an escape (a.alice)",
          bridge.names_me("come a.alice"))
    check("tab: with nothing running, idle",
          bridge.tab_state(False, False, still) == "idle")
    check("tab: with a job without a name of its own, working",
          bridge.tab_state(False, False, {**still, "chest": {"with_chest": True}}) == "working")
    check("tab: fleeing has its own icon, even while working",
          bridge.tab_state(False, False, {**still, "lookout": {"fleeing": True},
                                          "fill_job": {"working": True}}) == "fleeing")
    check("tab: escorting has a name of its own",
          bridge.tab_state(False, False, {**still, "escort_status": {"escorting": True}}) == "escorting")
    check("tab: farming wins over escorting",
          bridge.tab_state(False, False, {**still, "escort_status": {"escorting": True},
                                          "farm": {"working": True}}) == "farming")
    check("tab: crafting is crafting",
          bridge.tab_state(False, False, {**still, "crafting": True}) == "crafting")
    check("tab: the furnace running is cooking, if there is no other job",
          bridge.tab_state(False, False, {**still, "furnace": {"with_furnace": False, "cooking": True}}) == "cooking")
    check("tab: an active job wins over cooking",
          bridge.tab_state(False, False, {**still, "furnace": {"with_furnace": False, "cooking": True},
                                          "escort_status": {"escorting": True}}) == "escorting")
    check("tab: gathering logs is chopping",
          bridge.tab_state(False, False, {**still, "farm": {"working": True, "mode": "gather",
                                                            "target": "oak_log"}}) == "chopping")
    check("tab: gathering grass is gathering",
          bridge.tab_state(False, False, {**still, "farm": {"working": True, "mode": "gather",
                                                            "target": "short_grass"}}) == "gathering")
    check("tab: the fill job is building",
          bridge.tab_state(False, False, {**still, "fill_job": {"working": True}}) == "building")
    check("tab: the strip mine is mining",
          bridge.tab_state(False, False, {**still, "strip_mine": {"mining": True}}) == "mining")
    check("tab: fleeing wins over the jobs",
          bridge.tab_state(False, False, {**still, "lookout": {"fleeing": True},
                                          "fill_job": {"working": True}}) == "fleeing")
    check("tab: talking to another AI has its own icon",
          bridge.tab_state(True, False, still, with_ai=True) == "internal")
    check("tab: error wins over internal",
          bridge.tab_state(True, True, still, with_ai=True) == "error")
    check("tab: thinking wins over the body",
          bridge.tab_state(True, False, {**still, "lookout": {"fleeing": True}}) == "thinking")
    check("tab: a brain failure is shown even while thinking",
          bridge.tab_state(True, True, still) == "error")
    check("tab: dead wins over everything",
          bridge.tab_state(True, True, {**still, "dead": True}) == "dead")
    check("tab: stuck wins over error and thinking",
          bridge.tab_state(True, True, still, stuck=True) == "stuck")

    # Physical stuckness: it wants to move and does not move.
    a = bridge.StuckDetector(deadline=12.0, threshold=0.6)
    walking = lambda x, t: a.look({**still, "x": x, "y": 64.0, "z": 0.0,
                                   "walk": {"walking": True, "outcome": ""}}, t)
    check("stuck: advancing is not stuck",
          not walking(0.0, 0) and not walking(1.0, 2) and not walking(2.0, 4))
    check("stuck: 10 s still is not yet (the Walker jumps and replans alone)",
          not walking(2.1, 6) and not walking(2.2, 14))
    check("stuck: 12 s without advancing while wanting to move = stuck",
          walking(2.3, 16.5))
    check("stuck: as soon as it advances, it no longer is",
          not walking(4.0, 17))
    # It gave up ("I got stuck") and stands still.
    b = bridge.StuckDetector()
    gave_up = lambda x, t, another=False: b.look(
        {**still, "x": x, "y": 64.0, "z": 0.0,
         "walk": {"walking": False, "outcome": "I got stuck at -1 64 0; ..."},
         "digging": {"digging": another}}, t)
    check("stuck: standing still after 'I got stuck' = stuck",
          gave_up(0.0, 0) and gave_up(0.0, 30))
    check("stuck: if it starts digging, it no longer counts",
          not gave_up(0.0, 31, another=True))
    check("stuck: if moved more than two blocks (tp), it is released and does not come back",
          gave_up(0.0, 32) and not gave_up(3.0, 33) and not gave_up(3.0, 60))
    check("stuck: the old Spanish wording still counts",
          bridge.StuckDetector().look({**still, "x": 0.0, "y": 64.0, "z": 0.0,
                                       "walk": {"walking": False, "outcome": "me atasque en -1 64 0"}}, 0))
    check("stuck: without a position no opinion",
          not bridge.StuckDetector().look({"ok": True}, 0))

    check("tab: the server mod knows the seven states",
          {"idle", "working", "thinking", "combat", "error", "dead", "stuck"}
          <= set(re.findall(r'"(\w+)", new Icon', (HERE.parent / "mod-server/src/main/java/marionette/server/Tab.java").read_text())))


def tests_stairs():
    """The staircase is remembered and walked back (a bot once started
    digging down again instead of going back down its own)."""
    import bridge
    calls = []

    def fake(r, **kw):
        calls.append((r, kw))
        if r == "/staircase":
            return {"ok": True, "until": kw.get("until")}
        if r == "/state":
            return {"ok": True, "x": -898.4, "y": 67.0, "z": 916.2, "hp": 20.0,
                    "walk": {"walking": False,
                             "outcome": "I got stuck 51 blocks from -880 16 881: "
                                        "arrived (1.0 from the requested point)"}}
        if r == "/chat":
            return {"ok": True, "last": 1, "messages": []}
        if r == "/go":
            return {"ok": True}
        return {"ok": True}
    server.sv = fake
    server.bt = fake

    r = server.t_dig_down_to({"until": 16})
    head_pos = [kw for route, kw in calls if route == "/places" and kw.get("remember") == "point"]
    check("stairs: on starting the head is NOT noted (it left ghosts)",
          head_pos == [], head_pos)
    check("stairs: ...it is explained that a saved one can be walked without digging",
          "without digging" in r, r)

    check("stairs: the arrival notice gives the foot",
          bridge.stair_foot("staircase: reached the elevation: I am at -873 16 890 "
                            "after 53 steps") == (-873, 16, 890))
    check("stairs: the new notice also carries the head",
          bridge.stair_head("staircase: reached the elevation: I am at -832 -58 872 "
                            "after 31 steps from -846 -27 888 (saved staircase)")
          == (-846, -27, 888))
    check("stairs: the old Spanish notice is still understood",
          bridge.stair_head("escalera: llegue a la cota: estoy en -832 -58 872 "
                            "tras 31 escalones desde -846 -27 888 (escalera guardada)")
          == (-846, -27, 888))
    check("stairs: an old notice without 'from' does not invent a head",
          bridge.stair_head("staircase: reached the elevation: I am at -873 16 890 "
                            "after 53 steps") is None)
    check("stairs: another notice with coordinates is not a foot",
          bridge.stair_foot("exploring FOUND oak_log at -882 75 952; "
                            "I am at -880 70 950") is None)

    calls.clear()
    r = server.t_go_to({"x": -881, "y": 17, "z": 881})
    check("go_to: stuck 50 blocks above the destination, it says so and points to the staircase",
          "ABOVE" in r and "staircase" in r, r)
    r = server.t_go_to({"x": -881, "y": 67, "z": 881})
    check("go_to: stuck at the same height, no staircase hint",
          "ABOVE" not in r and "BELOW" not in r, r)


def tests_server_down():
    print("\nIf the server does not answer, it is said; not faked")
    with_response({"ok": False,
                   "error": "cannot reach the server at http://x:1: refused. "
                            "Is it on?"})
    check("state: warns that it cannot reach the server",
          "could not" in server.t_state({}).lower())
    r = server.t_players({})
    check("players: warns, and does NOT say 'nobody is connected'",
          "could not" in r.lower() and "nobody is connected" not in r.lower(), r)

    # Nobody connected IS a valid answer, and it is different from a failure.
    with_response({"ok": True, "players": []})
    check("players: really empty if it says nobody is connected",
          "nobody is connected" in server.t_players({}).lower())


# --- the protocol, against the real process ---------------------------------

def talk_mcp(messages):
    """Launches server.py and speaks JSON-RPC to it over stdin, as Claude does."""
    entry = "\n".join(json.dumps(m) for m in messages) + "\n"
    p = subprocess.run([sys.executable, str(HERE / "server.py")],
                       input=entry, capture_output=True, text=True,
                       timeout=30, env={**os.environ, "MARIONETTE_ENV": str(_cfg)})
    return [json.loads(l) for l in p.stdout.splitlines() if l.strip()]


def tests_protocol():
    print("\nThe MCP protocol, against the real process")
    r = talk_mcp([
        {"jsonrpc": "2.0", "id": 1, "method": "initialize",
         "params": {"protocolVersion": "2077-01-01"}},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
        {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
         "params": {"name": "does_not_exist", "arguments": {}}},
    ])
    by_id = {m["id"]: m["result"] for m in r}

    check("initialize: returns the version the client asks for",
          by_id[1]["protocolVersion"] == "2077-01-01")
    check("a notification (without id) is not answered", len(r) == 3,
          f"{len(r)} answers")

    names = {t["name"] for t in by_id[2]["tools"]}
    questions = {"state", "players", "creatures_nearby", "objects_nearby",
                 "what_is_at", "search_block", "inventory", "show_recipe",
                 "logbook", "break_permissions", "show_preferences", "food_ban",
                 "look_in_furnace", "places", "look_in_chest", "search_chests",
                 "orders", "light",
                 "diary", "what_i_know_about", "who_commands"}
    actions = {"strip_mine_start", "stop_mining", "say", "dig_down_to",
               "go_to", "stop", "attack", "dig", "respawn", "craft_item",
               "pick_up", "place", "wield", "mount", "dismount", "toss", "sleep",
               "tie_animal", "lead_animals", "tether_to_post", "release_animals",
               "set_spawn",
               "eat",
               "follow_player", "stop_following",
               "escort", "stop_escorting", "trash",
               "explore", "stop_exploring",
               "write_in_diary", "note_about_someone",
               "remind_me",
               "smelt", "take_from_furnace",
               "verbose",
               "remember_place", "forget_place",
               "put_in_chest", "take_from_chest", "annotate_chest",
               "hunt", "shear", "kill", "fish",
               "tame", "breed", "pets", "wait_for_item",
               "add_order", "delete_order",
               "equip_armor", "remove_armor",
               "mark_corner", "fill", "build_blueprint",
               "search", "my_horse", "internal", "farm_crops", "harvest",
               "collect_water", "allow_farm", "gather_seeds", "gather"}
    check("tools/list: every asking tool is there",
          questions <= names, questions - names)
    check("tools/list: every acting tool is there",
          actions <= names, actions - names)

    # Every offered tool has to really exist: promising one that does nothing
    # is the same lie as before, the other way round.
    check("tools/list: nothing is offered that is not implemented",
          names == questions | actions, names ^ (questions | actions))

    # And the other half, which once cost a mute tool: the MCP may offer
    # something the bridge does not ALLOW the brain to use, and then the bot
    # says "I lack permission for that action" — which from outside looks like
    # a lock of ours and is an oversight in a list.
    import bridge
    allowed = {h.removeprefix("mcp__bot__")
               for h in bridge.TOOLS.split()}
    check("the bridge allows EVERY tool the MCP offers",
          names <= allowed, names - allowed)
    check("the bridge allows none that does not exist",
          allowed <= names, allowed - names)

    check("stop exists and takes no arguments: the emergency button cannot "
          "need the model to get a parameter right",
          next(t for t in by_id[2]["tools"]
               if t["name"] == "stop")["inputSchema"]["required"] == [])

    schema = next(t for t in by_id[2]["tools"]
                  if t["name"] == "search_block")["inputSchema"]
    check("search_block: only the block is required",
          schema["required"] == ["block"], schema["required"])
    check("search_block: the schema says the id goes in English",
          "english" in schema["properties"]["block"]["description"].lower())

    check("nonexistent tool: marked as an error",
          by_id[3]["isError"] is True)


def tests_chat():
    """What goes to the chat has to be drawable.

    The client does not have the glyphs for emoji — white squares came out —
    so asking the model in the prompt is not enough: it is filtered on the way
    out, which is the only thing that guarantees it does not reach the game.
    """
    clean, outside = drawable("Done! \U0001f60a pillar of 3 \U0001fab8")
    check("chat: emoji do not reach the game",
          clean == "Done! pillar of 3", clean)
    check("chat: their removal is recorded", len(outside) == 2, outside)

    kao = "All set (・_・)ﾉ ★ anything else?"
    check("chat: the kaomoji survives whole, which is the goal",
          drawable(kao)[0] == kao, drawable(kao)[0])

    check("chat: a family joined with ZWJ leaves no invisible remains",
          drawable("hi \U0001f469‍\U0001f467 bye")[0]
          == "hi bye")
    check("chat: the pickaxe with an emoji selector leaves no loose selector",
          drawable("pick ⛏️ ok")[0] == "pick ⛏ ok")
    check("chat: the emoji with a monochrome glyph goes too",
          drawable("ok ✅ ⚡")[0] == "ok")

    spanish = "mañana pequeñísimo ¿sí? —claro—"
    check("chat: accents, eñes and dashes stay intact",
          drawable(spanish)[0] == spanish, drawable(spanish)[0])


if __name__ == "__main__":
    tests_honesty()
    tests_ids()
    tests_fallback()
    tests_ores()
    tests_server_down()
    tests_chests()
    tests_places()
    tests_pending()
    tests_tab()
    tests_stairs()
    tests_protocol()
    tests_chat()
    tests_speaker()

    print(f"\n{done - len(failures)}/{done} checks pass")
    if failures:
        print("failed:")
        for f in failures:
            print(f"  - {f}")
        sys.exit(1)

#!/usr/bin/env python3
"""MCP server: gives Claude the bot's tools.

It speaks JSON-RPC 2.0 over stdin/stdout and translates every call into HTTP.
Two destinations, and the difference matters:

- **the SERVER mod** (another machine, with a token) knows the truth: what is
  there, where, how much health something has left, what is in the inventory;
- **the BOT mod** (right here, on localhost) does things: walking, digging,
  hitting, placing blocks.

Rule: *ask the server, act through the bot*. When an action ends, what is told
about the result is asked of the server — the client lags behind and right
after acting it does not know yet.

Actions that take time **block here**: they start, watch and only answer when
there is an outcome. That way the model cannot take a result for granted. No
dependencies, on purpose.
"""
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HOME = os.path.expanduser("~")
CONFIG = os.environ.get("MARIONETTE_ENV", f"{HOME}/.marionette/server.env")
BOT = os.environ.get("MARIONETTE_BOT", "http://127.0.0.1:8478")
# Same BOT_NAME the bridge uses, from which we inherit the environment. A name
# written by hand in three places is the same bug as the port: with two bots,
# the second asked for the first one's inventory.
NAME = os.environ.get("BOT_NAME", "Bot")
# Within this distance of the noted spot the chunk is loaded: if the server
# still does not see the horse, it is gone and it is forgotten.
HORSE_NEAR = 40
REPO = os.environ.get("MARIONETTE_REPO", os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
WAIT = 25          # seconds per single request
# 45 and not 180: while a tool waits, the brain hears nobody. Past the limit
# the tool reports it is still going and the turn ends; the task is NOT cut.
TASK_LIMIT = 45    # waiting cap inside a turn

# The MCP is launched ONCE PER TURN: what happens in a turn is forgotten in
# the next. This file is the only thing that survives, and `verbose` reads it.
CALLS = os.path.join(os.path.dirname(CONFIG),
                     "calls_%s.log" % os.environ.get("BOT_NAME", "bot").lower())
CALLS_CAP = 400      # lines kept when trimming
CHAT_WIDTH = 190     # the game chat does not swallow long lines
# The server kicks for chat spam: 24 lines at 0.3 s got a bot kicked once. The
# spam counter climbs much faster than it drops. With few lines and a long
# pause it stays clear of the limit; the WHOLE dump lives in CALLS, not in the
# chat, and that is what is read to really debug.
CHAT_CAP = 6         # lines per dump, hard cap
CHAT_PAUSE = 1.5     # seconds between lines
# `verbose` exposes paths, arguments and internal state: only the owner.
OWNER = os.environ.get("MARIONETTE_OWNER", "")


def _speaker():
    """Who spoke in the message the brain is answering, as the BRIDGE read it
    from the server's chat. Empty for the body's own notices.

    What a player can talk the brain into must not be what a lock checks: a
    `who` written by the brain can be faked ("Alice, <owner> says you may eat
    it"). Vetoed food checks THIS name instead. Shutting down, restarting,
    logging off and the admin list are not tools at all any more: they are
    server commands (/marionette bot), where the server knows who ran them.
    Read at call time, so tests can set it.
    """
    return os.environ.get("MARIONETTE_SPEAKER", "").strip()


OWNER_LABEL = OWNER or "the server owner"


def load_config():
    cfg = {}
    try:
        with open(CONFIG, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    cfg[k.strip()] = v.strip()
    except OSError as e:
        record(f"could not read {CONFIG}: {e}")
    return (cfg.get("MARIONETTE_HOST", "127.0.0.1"),
            cfg.get("MARIONETTE_PORT", "8477"),
            cfg.get("MARIONETTE_TOKEN", ""))


HOST, PORT, TOKEN = load_config()
SERVER = f"http://{HOST}:{PORT}"


def record(msg):
    print(f"[mcp] {msg}", file=sys.stderr, flush=True)


def log_call(name, args, text, failed):
    """Leaves a record of a call: what was asked, with which arguments and
    what it answered. `record` only writes to stderr, which nobody collects;
    this is what makes it possible to reconstruct later why something failed."""
    try:
        with open(CALLS, "a", encoding="utf-8") as f:
            f.write(json.dumps({
                "t": time.strftime("%H:%M:%S"),
                "tool": name,
                "args": args,
                "failed": bool(failed),
                "response": str(text)[:400],
            }, ensure_ascii=False) + "\n")
        if os.path.getsize(CALLS) > 400000:
            with open(CALLS, encoding="utf-8") as f:
                queue = f.readlines()[-CALLS_CAP:]
            with open(CALLS, "w", encoding="utf-8") as f:
                f.writelines(queue)
    except OSError as e:
        record(f"could not log the call: {e}")


def chunks(lines, width=CHAT_WIDTH):
    """Splits what is long, since the game chat cuts without warning."""
    for l in lines:
        l = str(l).replace("\n", " ")
        while len(l) > width:
            yield l[:width]
            l = "  " + l[width:]
        if l.strip():
            yield l


def _request(base, route, headers=None, body=None, **params):
    url = base + route
    clean_ones = {k: v for k, v in params.items() if v is not None}
    if clean_ones:
        url += "?" + urllib.parse.urlencode(clean_ones)
    # With `body` it goes by POST, same format as the query: a blueprint is
    # hundreds of cells and that does not fit in a URL.
    data = None
    if body is not None:
        data = urllib.parse.urlencode(
            {k: v for k, v in body.items() if v is not None}).encode("utf-8")
    pet = urllib.request.Request(url, data=data, headers=headers or {})
    try:
        with urllib.request.urlopen(pet, timeout=WAIT) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code == 401:
            return {"ok": False, "error": "the token is not valid"}
        try:
            return json.loads(e.read().decode("utf-8"))
        except Exception:
            return {"ok": False, "error": f"HTTP {e.code}"}
    except urllib.error.URLError as e:
        return {"ok": False, "error": f"cannot reach {base}: {e.reason}"}
    except Exception as e:
        return {"ok": False, "error": f"failure in {route}: {e}"}


def _bot_world():
    """Where the bot is now: overworld, the_nether or the_end. None if unknown."""
    try:
        me = bt("/state")
        m = me.get("world") if me.get("ok") else None
        return m.split(":")[-1] if m else None
    except Exception:
        return None


# Questions about the world go to the world the bot IS in. The server, without
# `world`, looks at the overworld; a bot once crossed into the Nether and from
# there every what_is_at and search_block answered "unloaded", because the
# overworld was being looked at with Nether coordinates.
_WITH_WORLD = ("/search", "/block", "/entities")


def sv(route, **p):
    """To the server mod: the truth."""
    if route in _WITH_WORLD and not p.get("world"):
        m = _bot_world()
        if m:
            p["world"] = m
    return _request(SERVER, route, {"X-Marionette-Token": TOKEN}, **p)


def bt(route, **p):
    """To the bot mod: the hands."""
    return _request(BOT, route, None, **p)


def bt_post(route, body, **p):
    """To the bot mod, with the parameters in the body (POST)."""
    return _request(BOT, route, None, body=body, **p)


# --- questions (the server answers them) -----------------------------------

def t_state(_):
    # If mounted, keep the horse. Here and not in `mount` because mounting is
    # asynchronous: when that one answers there is no mount yet.
    try:
        _remember_mount()
    except Exception as e:                                    # noqa: BLE001
        record(f"could not remember the horse: {e}")
    d = sv("/health")
    if not d.get("ok"):
        return f"I could not get the state: {d.get('error')}"
    s = d.get("spawn", {})
    me = bt("/state")
    if me.get("ok"):
        _pending_clean(me)   # what it already sees finished need not be notified
    mine = ""
    if me.get("ok"):
        mine = (f" I am at {me['x']:.0f} {me['y']:.0f} {me['z']:.0f}, "
                f"with {me['hp']} hp and {me['in_hand']} in hand"
                + (" (DEAD: must respawn)" if me.get("dead") else "")
                + (". It is NIGHT." if me.get("is_night") else ". It is daytime."))
    # What I am DOING, not only where I am. Without this the brain had no way
    # to see that the hunt had ended and answered "still looking for cows"
    # fifteen minutes after hunting the last one.
    tasks = []
    last_ones = []
    if me.get("ok"):
        for key, flag, name in (
                ("hunt", "hunting", "hunting"), ("shear_job", "shearing", "shearing sheep"),
                ("archery", "killing", "killing with arrows"),
                ("travel", "traveling", "travelling"), ("walk", "walking", "walking"),
                ("fill_job", "working", "filling"), ("digging", "digging", "digging"),
                ("strip_mine", "mining", "strip mining"), ("staircase", "descending", "going down a staircase"),
                ("follow", "following", "following"), ("escort_status", "escorting", "escorting"),
                ("fishing_job", "fishing", "fishing"), ("exploration", "exploring", "exploring"),
                ("breeding", "active", "with animals"), ("furnace", "loading", "at the furnace")):
            f = me.get(key)
            if not isinstance(f, dict):
                continue
            if f.get(flag):
                if key == "fill_job" and f.get("blueprint"):
                    name = ("clearing the site of a blueprint"
                            if f.get("clearing") else
                            f"building a blueprint: layer Y={f.get('layer')}, "
                            f"{f.get('layers_done')} layers done and reviewed, "
                            f"placing {f.get('with')}")
                extra = {k: v for k, v in f.items() if k not in (flag, "outcome")
                         and not isinstance(v, (dict, list))}
                tasks.append(name + (" " + ", ".join(f"{k}={v}" for k, v in list(extra.items())[:4])
                                     if extra else ""))
            elif f.get("outcome") and key in ("hunt", "shear_job", "travel", "fill_job", "staircase", "archery", "breeding"):
                last_ones.append(f"{key}: {f['outcome']}")
    job = (" RIGHT NOW I am: " + "; ".join(tasks) + "."
           if tasks else " RIGHT NOW I have no task running (standing still).")
    if not tasks and last_ones:
        job += " Last outcome — " + " | ".join(last_ones[:3]) + "."
    return (f"Server alive, {d['players']} players. Spawn at "
            f"{s.get('x')} {s.get('y')} {s.get('z')}.{mine}{job}")


def t_players(_):
    d = sv("/players")
    if not d.get("ok"):
        return f"I could not see the players: {d.get('error')}"
    js = d.get("players", [])
    if not js:
        return "Nobody is connected."
    return "Connected: " + "; ".join(
        f"{j['name']} at {j['x']} {j['y']} {j['z']} ({j['hp']} hp)"
        for j in js)


def t_what_is_at(a):
    d = sv("/block", x=a.get("x"), y=a.get("y"), z=a.get("z"))
    if not d.get("ok"):
        return (f"I do not know: {d.get('error')}. With nobody nearby, that "
                "part of the world is asleep.")
    return f"At {d['x']} {d['y']} {d['z']} there is {d['block']}."


def _is_ore(block):
    b = (block or "").lower().replace("minecraft:", "")
    return b.endswith("_ore") or b == "ancient_debris"


def t_search_block(a):
    # Rule: strip mining instead of x-ray. Asking the server where an ore is
    # buried IS the x-ray, so it refuses here; ores are found by digging, with
    # strip_mine_start.
    if _is_ore(a.get("block")):
        return (f"I do not search for {a.get('block')}: ores are not searched, "
                "they are found by digging. Use strip_mine_start (straight "
                "tunnel with branches that takes what appears) and stop_mining "
                "to finish.")
    x, y, z = a.get("x"), a.get("y"), a.get("z")
    if x is None or y is None or z is None:
        me = bt("/state")
        if me.get("ok"):
            x, y, z = int(me["x"]), int(me["y"]), int(me["z"])
        else:
            s = sv("/health").get("spawn")
            if not s:
                return "I could not tell where to search from; give me coordinates."
            x, y, z = s["x"], s["y"], s["z"]
    d = sv("/search", block=a.get("block"), x=x, y=y, z=z,
           radius=a.get("radius", 16))
    if not d.get("ok"):
        return d.get("error", "could not search")
    if not d.get("found"):
        if d.get("looked_at", 0) == 0:
            return (f"I could not look at a single tile: the {d.get('unloaded')} "
                    "are unloaded. It is not that there is none, it is that I do "
                    "not know.")
        return f"There is no {a.get('block')} within that radius. I looked at {d['looked_at']} tiles."
    return (f"The nearest {a.get('block')} is at {d['x']} {d['y']} {d['z']}, "
            f"{d['distance']} blocks away.")


def t_creatures_nearby(a):
    me = bt("/state")
    x, y, z = (int(me["x"]), int(me["y"]), int(me["z"])) if me.get("ok") else (
        a.get("x"), a.get("y"), a.get("z"))
    d = sv("/entities", x=x, y=y, z=z, radius=a.get("radius", 16))
    if not d.get("ok"):
        return d.get("error", "I could not look")
    creatures = d.get("creatures", [])
    if not creatures:
        return f"There is no living creature within {d.get('radius')} blocks."
    creatures.sort(key=lambda s: s["distance"])
    return "Nearby: " + "; ".join(
        f"{s['type']} at {s['distance']} ({s['hp']}/{s['max']} hp)"
        for s in creatures[:12])


def _objects_nearby(radius):
    """What lies on the ground, sorted by distance. Raw list."""
    me = bt("/state")
    if not me.get("ok"):
        return None, "I do not know where I am"
    d = sv("/objects", x=int(me["x"]), y=int(me["y"]), z=int(me["z"]),
           radius=radius)
    if not d.get("ok"):
        return None, d.get("error", "I could not look at the ground")
    objects = sorted(d.get("objects", []), key=lambda o: o["distance"])
    return objects, None


def t_objects_nearby(a):
    radius = a.get("radius", 16)
    objects, error = _objects_nearby(radius)
    if error:
        return error
    if not objects:
        return (f"Nothing lies on the ground within {radius} blocks. Careful: what "
                "falls on the ground vanishes after 5 minutes.")
    return "On the ground: " + "; ".join(
        f"{o['count']}x {o['what']} at {o['distance']} "
        f"({o['x']} {o['y']} {o['z']}, on the ground for {o['seconds_on_ground']}s)"
        for o in objects[:12])


def t_pick_up(a):
    """Go to something on the ground and step on it. Picking up happens alone,
    on getting close.

    The BOT mod looks for it, not the server's, even though that breaks the
    habit of "ask the server": here the exact coordinates of the object are
    needed, and the server returns them rounded to the block. To step on
    something, one block of margin is exactly too much.

    One at a time on purpose: if something goes wrong — it does not arrive, a
    mob takes it, it vanishes — it has to be possible to say WHICH one failed,
    not a "picked up 3 of 5".
    """
    what = (a.get("what") or "").strip().lower()
    r = bt("/pick_up", what=what or None, radius=a.get("radius", 16))
    if not r.get("ok"):
        return r.get("error", "I could not go for it")
    obj = r.get("going_for", {})
    id_ = obj.get("what", "that")
    before = _how_many_carried(id_)
    _wait_task("walk", "walking")
    now = _how_many_carried(id_)
    if now > before:
        return f"Picked up: {now - before}x {id_}. I now carry {now}."
    me = bt("/state")
    return (f"I walked to the {id_} and still do not have it. "
            f"{me.get('walk', {}).get('outcome', '')}")


def t_place(a):
    """Leave a specific block at some coordinates.

    The bot places it, but what ended up there is told by the SERVER: the
    client takes for granted that its click worked, and that is not always
    true.
    """
    what = (a.get("what") or "").strip().lower()
    if not what:
        return "Tell me what to place (the id in English: crafting_table)."
    x, y, z = a.get("x"), a.get("y"), a.get("z")
    # A chest with a block on top does not open; it is checked BEFORE placing
    # it and, if covered, it is not placed and the reason is given (and where
    # the lid is).
    if what in _NEEDS_AIR_ABOVE or what.endswith("shulker_box"):
        up = sv("/block", x=x, y=int(y) + 1, z=z)
        if up.get("ok") and up.get("block") not in _AIR:
            return (f"I am not placing {what} at {x} {y} {z}: above it ({x} {int(y) + 1} "
                    f"{z}) there is {up['block']} and covered it does not open. "
                    "Find a spot with air above, or remove that block first.")
    r = bt("/place", what=what, x=x, y=y, z=z)
    if not r.get("ok"):
        return f"I could not place {what}: {r.get('error')}"
    d = sv("/block", x=x, y=y, z=z)
    if d.get("ok") and d.get("block") == what:
        if what in _OPEN_LIKE_CHEST:
            # What I place is mine: it goes into the chest memory, empty.
            _chest_annotate(x, y, z, contents={}, permission="own", type_=what)
        return f"Placed: {what} at {x} {y} {z}."
    there_is = d.get("block", "I do not know")
    if d.get("ok"):
        # The mod notes the place (table, furnace, chest...) on making the
        # GESTURE, not on checking: every failed gesture left a ghost furnace
        # in the memory. It is deleted here.
        bt("/places", forget=1, x=x, y=y, z=z)
    return (f"I made the gesture of placing {what} at {x} {y} {z}, but the server "
            f"says there is {there_is} there. Do not count on it being placed.")


def _how_many_carried(what):
    d = sv("/inventory", player=NAME)
    if not d.get("ok"):
        return 0
    return sum(c["count"] for c in d.get("things", []) if c["what"] == what)


def _current_server():
    """Which server the bot is in: the launcher writes it in
    bots/<bot>/server (a slug such as my-survival, testing...)."""
    try:
        with open(os.path.join(_bots_dir(), NAME.lower(), "server")) as f:
            return f.read().strip() or "unknown"
    except OSError:
        return "unknown"


def _horse_file():
    # Per bot AND per server: each server is another world with other
    # creatures, and the uuid of a horse from one server does not exist in
    # another. Without this `my_horse` would say "it must have died" right
    # after changing server.
    return os.path.join(os.path.dirname(CONFIG),
                        f"horse_{NAME.lower()}_{_current_server()}.json")


def _horse_read():
    try:
        with open(_horse_file()) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def _horse_forget_where():
    """Delete the noted location, keeping the uuid: the horse may still be
    alive somewhere else, what no longer holds is the address."""
    c = _horse_read()
    for k in ("x", "y", "z", "world", "seen"):
        c.pop(k, None)
    route = _horse_file()
    try:
        tmp = route + ".tmp"
        with open(tmp, "w") as f:
            json.dump(c, f)
        os.replace(tmp, route)
    except OSError as e:
        record(f"could not forget where the horse was: {e}")


def _horse_note_where(d):
    """Save where it was seen. An entity in an unloaded chunk does not tick,
    so this is not an old clue: it is where it still is."""
    c = _horse_read()
    if not c.get("uuid"):
        return
    c.update({"x": d.get("x"), "y": d.get("y"), "z": d.get("z"),
              "world": d.get("world"), "seen": time.strftime("%Y-%m-%d %H:%M")})
    route = _horse_file()
    try:
        tmp = route + ".tmp"
        with open(tmp, "w") as f:
            json.dump(c, f)
        os.replace(tmp, route)
    except OSError as e:
        record(f"could not note where the horse is: {e}")


def _horse_save(uuid, type_):
    """The UUID is saved, NOT the position: the position expires as soon as
    the horse takes two steps and the uuid never changes. With it, the server
    mod can say where it is even a thousand blocks away and unloaded."""
    route = _horse_file()
    try:
        tmp = route + ".tmp"
        with open(tmp, "w") as f:
            json.dump({"uuid": uuid, "type": type_,
                       "since": time.strftime("%Y-%m-%d %H:%M")}, f)
        os.replace(tmp, route)
    except OSError as e:
        record(f"could not remember the horse: {e}")


def _remember_mount():
    """If mounted, note on what. Called after mounting and in `state`."""
    me = bt("/state")
    m = me.get("mount_status", {}) if me.get("ok") else {}
    if m.get("mounted") and m.get("uuid"):
        if _horse_read().get("uuid") != m["uuid"]:
            _horse_save(m["uuid"], m.get("at") or "horse")
        # Mounted, the horse is where I am: noting it is free and it is what
        # saves the case of "it ran away while I was not looking".
        if me.get("ok"):
            _horse_note_where({"x": int(me["x"]), "y": int(me["y"]),
                               "z": int(me["z"]), "world": me.get("world")})
        return m
    return None


def _go_to_my_horse():
    """Travel to the remembered horse. Returns the account of the trip, or
    None if it is unknown where it is."""
    c = _horse_read()
    if not c.get("uuid"):
        return None
    d = sv("/where", uuid=c["uuid"])
    if d.get("ok") and d.get("present"):
        x, y, z, of_ = d["x"], d["y"], d["z"], "the server sees it there"
    elif c.get("x") is not None:
        # Unloaded is not absent, and besides it has not moved.
        x, y, z, of_ = c["x"], c["y"], c["z"], "it is where I left it"
    else:
        return None
    r = bt("/go", x=int(x), y=int(y), z=int(z))
    if not r.get("ok"):
        return None
    end = _wait_task("travel" if r.get("by_segments") else "walk",
                     "traveling" if r.get("by_segments") else "walking",
                     cut=False)
    return (f"there was no horse nearby, so I went for mine at "
            f"{int(x)} {int(y)} {int(z)} ({of_}): {end}")


def t_my_horse(_):
    """Where my horse is, asking the SERVER."""
    _remember_mount()
    c = _horse_read()
    if not c.get("uuid"):
        return ("I have not mounted any horse yet, so I have none that is "
                "mine. As soon as I mount one I keep it.")
    d = sv("/where", uuid=c["uuid"])
    if not d.get("ok"):
        return d.get("error", "I could not ask the server")
    if not d.get("present"):
        # Not loaded is not the same as dead, and on top of that it has not
        # moved: in an unloaded chunk nothing ticks. If I have noted where it
        # was seen, it is still there.
        if c.get("x") is not None:
            # If I am NEXT TO the noted spot, that chunk is loaded; the server
            # still not seeing it means it really is not there, and then the
            # location is a lie and gets forgotten. Far away nothing can be
            # concluded: unloaded is not the same as absent.
            me = bt("/state")
            if me.get("ok") and c.get("world") == me.get("world"):
                dx, dz = c["x"] - me["x"], c["z"] - me["z"]
                if (dx * dx + dz * dz) ** 0.5 <= HORSE_NEAR:
                    _horse_forget_where()
                    return (f"I came for my {c.get('type', 'horse')} at "
                            f"{c['x']} {c['y']} {c['z']} and it is NOT here. I am "
                            "right next to it, so the area is loaded and the "
                            "server would see it if it were here: it left or it "
                            "died. I am deleting that location, it is useless "
                            "now. If another horse shows up and I mount it, I "
                            "keep that one; and this time I leave it tethered to "
                            "a fence.")
            return (f"My {c.get('type', 'horse')} is at {c['x']} {c['y']} "
                    f"{c['z']} ({c.get('world')}), where it was seen on "
                    f"{c.get('seen')}. Right now that area is not loaded, so "
                    "the server does not have it in memory, but it has NOT "
                    "moved: what is not loaded does not move. If I go there, it "
                    "will be there. So that it does not run away again, on "
                    "arriving tie it to a fence with `tie_animal` and "
                    "`tether_to_post`.")
        return (f"I do not know where my {c.get('type', 'horse')} is: the server "
                "does not have it loaded and I never got to note where it was. "
                "Either it died, or it is in an area nobody has visited since "
                "the server started.")
    _horse_note_where(d)
    me = bt("/state")
    here = ""
    if me.get("ok") and d.get("world", "").endswith(me.get("world", "x")[10:]):
        dx, dz = d["x"] - me["x"], d["z"] - me["z"]
        here = f", {int((dx * dx + dz * dz) ** 0.5)} blocks from me"
    alive_one = "" if d.get("alive", True) else " (dead)"
    return (f"My {d.get('type', 'horse')}{alive_one} is at {d['x']} {d['y']} "
            f"{d['z']}, in {d.get('world')}{here}. The server knows even if I "
            "cannot see it: if I want it back I have to go there. So that it "
            "does not run away again, tie it to a fence with `tie_animal` and "
            "`tether_to_post`.")


def t_mount(a):
    """Mount a horse: by its name tag, or the nearest tamed one.

    If there is none nearby but it IS known where its own one was left, it
    goes there and mounts it. It is the other half of leaving it tethered:
    noting where it is is useless if `mount` then only looks 32 blocks around.
    """
    d = bt("/mount", name=(a.get("name") or "").strip() or None)
    if not d.get("ok"):
        error = d.get("error", "I could not mount")
        if not error.startswith(NO_HORSE_PREFIXES):
            return error
        trip = _go_to_my_horse()
        if trip is None:
            return error + ("; and I do not know where mine was left either. "
                            "One has to be found: `search horse`")
        d = bt("/mount", name=(a.get("name") or "").strip() or None)
        if not d.get("ok"):
            return (f"{trip}, but on arriving I could not mount: "
                    f"{d.get('error', 'it would not let me')}")
        return (f"{trip}. I am next to it now and going to mount; I will let "
                "you know when I am up or if it will not let me.")
    return ("Going for the horse. I get close, mount (if it is untamed I insist "
            "until it gives in) and I will tell you when I am mounted or if it "
            "will not let me. Mounted, go_to and trips go on horseback; if I get "
            "stuck in a narrow gap, dismount and carry on on foot.")


# How the body words "no horse in sight" (both languages of the mods so far).
NO_HORSE_PREFIXES = ("I see no horse", "no veo ningun caballo")
# ... and "no sheep / no prey in sight".
NO_SHEEP_PREFIXES = ("I see no sheep", "no veo ninguna")
NO_PREY_PREFIXES = ("I see no", "no veo ningun")


def _wait_lead():
    end = _wait_task("lead", "working", cut=False)
    me = bt("/state")
    c = me.get("lead", {}) if me.get("ok") else {}
    tied = c.get("tied") or []
    who = ", ".join(f"{a.get('name')} (at {a.get('to')})" for a in tied[:6])
    return f"{end}. Tied to me now: {len(tied)}" + (f": {who}" if who else "") + "."


def t_tie_animal(a):
    """Tie an animal with a lead (by name tag, type, or the nearest)."""
    d = bt("/lead", tie=(a.get("what") or "").strip(), count=a.get("count"))
    if not d.get("ok"):
        return d.get("error", "I could not tie it")
    return _wait_lead()


def t_lead_animals(a):
    """Lead the tied animals to a point, at their pace."""
    d = bt("/lead", carry="1", x=a.get("x"), y=a.get("y"), z=a.get("z"))
    if not d.get("ok"):
        return d.get("error", "I could not lead them")
    return _wait_lead()


def t_tether_to_post(a):
    """Tether the tied animals to a fence."""
    d = bt("/lead", tether="1", x=a.get("x"), y=a.get("y"), z=a.get("z"))
    if not d.get("ok"):
        return d.get("error", "I could not tether them")
    end = _wait_lead()
    # If one of the tethered ones was my horse, this is THE spot: tethered it
    # is not going to move, and I am next to it, so the position is reliable.
    c = _horse_read()
    if c.get("uuid"):
        try:
            v = sv("/where", uuid=c["uuid"])
            if v.get("ok") and v.get("present"):
                _horse_note_where(v)
                end += (f" My horse is noted at {v['x']} {v['y']} "
                        f"{v['z']}: tethered, it cannot run away from me any more.")
        except Exception as e:                                # noqa: BLE001
            record(f"could not note the horse on tethering: {e}")
    return end


def t_release_animals(a):
    """Release one (by name/type) or all the animals tied to me."""
    d = bt("/lead", release=(a.get("what") or "all").strip())
    if not d.get("ok"):
        return d.get("error", "I could not release them")
    return _wait_lead()


def t_dismount(_):
    d = bt("/dismount")
    if not d.get("ok"):
        return d.get("error", "I could not get off")
    return "Getting off the horse; I will let you know when I am standing."


def t_wield(a):
    """Put something in hand.

    Slots go from 1 to 9, as seen on screen. Internally Minecraft numbers
    them 0 to 8 and /inventory returns them that way, which is an easy trap:
    here the on-screen one is used because it is what a person says.
    """
    r = bt("/wield", what=(a.get("what") or "").strip().lower() or None,
           slot=a.get("slot"))
    if not r.get("ok"):
        return f"I could not: {r.get('error')}"
    return (f"I now hold {r['count']}x {r['in_hand']} "
            f"(slot {r['slot']}).")


def t_equip_armor(a):
    """Get dressed from the backpack.

    Without a piece it puts on the best it carries, part by part (armor
    points, toughness breaks ties); with a specific piece it puts that one on,
    better or worse: whoever asks decides. The mod answers with what changed
    AND what ended up worn, and that is what is told — not what was asked.
    """
    piece = (a.get("piece") or "").strip().lower()
    d = bt("/armor", **({"put_on": piece} if piece else {"best": 1}))
    if not d.get("ok"):
        return f"I could not: {d.get('error')}"
    placed = ", ".join(d.get("placed", [])) or "none"
    if d.get("note"):
        return f"{d['note'].capitalize()}. Wearing: {placed}."
    return (f"I put on: {', '.join(d.get('changes', []))}. "
            f"Wearing: {placed}.")


def t_remove_armor(a):
    """Take off a worn piece; it stays stored in the backpack."""
    d = bt("/armor", take_off=(a.get("piece") or "").strip().lower())
    if not d.get("ok"):
        return f"I could not: {d.get('error')}"
    placed = ", ".join(d.get("placed", [])) or "none"
    return f"I took it off. Wearing: {placed}."


def t_sleep(a):
    """Lie down in the nearest bed. If it is far, it goes and retries.

    It is checked with the STATE that it really fell asleep: the server may
    refuse — by day, or with a monster nearby — and the click goes out just
    the same. Taking for granted that it worked would be exactly the kind of
    lie we do not want.
    """
    r = bt("/sleep", radius=a.get("radius", 16))
    if r.get("far"):
        c = r["bed"]
        t_go_to({"x": c["x"], "y": c["y"], "z": c["z"]})
        r = bt("/sleep", radius=a.get("radius", 16))
    if not r.get("ok"):
        return f"I could not sleep: {r.get('error')}"
    if r.get("sleeping"):
        return "I was already sleeping."
    time.sleep(2)
    me = bt("/state")
    if me.get("sleeping"):
        c = r.get("bed", {})
        return (f"Sleeping at {c.get('x')} {c.get('y')} {c.get('z')}. "
                "This resets the phantom count.")
    return ("I lay down but did not fall asleep. Usually one of two: it is "
            "daytime (one only sleeps at night or in a storm) or there is a "
            "monster nearby.")


def t_set_spawn(a):
    """Click a bed by day to leave my respawn point there.

    If the bed is far, I go and retry — same as sleeping. What CANNOT be done
    is checking it: the respawn point lives on the server and the client does
    not see it, so what is known is said (that the click went out) and not
    "your spawn is there now".
    """
    r = bt("/spawn", radius=a.get("radius", 16))
    if r.get("far"):
        c = r["bed"]
        t_go_to({"x": c["x"], "y": c["y"], "z": c["z"]})
        r = bt("/spawn", radius=a.get("radius", 16))
    if not r.get("ok"):
        return f"I could not set the spawn: {r.get('error')}"
    c = r.get("bed", {})
    return (f"I clicked the bed at {c.get('x')} {c.get('y')} {c.get('z')} without "
            "lying down; my spawn stays there. The server answers 'you can only "
            "sleep at night' and that is normal by day, not a failure.")


def t_eat(a):
    """Eat to fill the hunger. With the hunger full the health goes up alone.

    The hunger is checked before and after: the bite takes a couple of seconds
    and the client may drop it, so saying "I ate" without looking would be
    taking for granted what is not known.
    """
    what = (a.get("what") or "").strip().lower() or None
    # The brain itself used to skip the ban: `eat` without a name told it
    # only salmon (banned) was left and right after it asked `eat salmon` on
    # its own. By name and banned, only if a PERSON asked in this turn: who
    # must be given, and it must be who really spoke (see _speaker).
    if what:
        banned = bt("/food").get("banned") or []
        asked_by = (a.get("who") or "").strip().lower()
        if what in banned and not (asked_by and asked_by == _speaker().lower()):
            return (f"{what} is on my banned food list: I do not eat it on my "
                    "own. If the person who spoke in this turn asked me to eat "
                    "it by name, call again with who=<their name>; if not, say "
                    "that I am hungry and only have banned food.")
    before = bt("/state")
    r = bt("/eat", what=what)
    if not r.get("ok"):
        return f"I could not eat: {r.get('error')}"
    time.sleep(3)
    now = bt("/state")
    h0 = before.get("hunger", 0)
    h1 = now.get("hunger", 0)
    if h1 > h0:
        return (f"I ate {r['eating']}. Hunger {h0} -> {h1}/20"
                f", hp {now.get('hp')}.")
    return (f"I took {r['eating']} to my mouth but the hunger stays at {h1}. "
            "I may have been interrupted.")


def t_inventory(_):
    d = sv("/inventory", player=NAME)
    if not d.get("ok"):
        return d.get("error", "I could not see the inventory")
    things = d.get("things", [])
    if not things:
        return "I carry nothing."
    return "I carry: " + ", ".join(f"{c['count']}x {c['what']}" for c in things)


# What people say when they promise something, translated into id fragments.
# Waiting for an exact "stick" and receiving oak_log would be waiting on with
# the wood already in the backpack; and waiting for anything makes what was
# not it count (a bot once got cobblestone when waiting for wood). Rule: if
# it waits for wood, it waits for wood; if stone arrives, it keeps waiting.
# Players' words in both languages the bots have spoken so far.
_FAMILIES = {
    "wood": ("log", "planks", "stick", "wood"), "logs": ("log",), "log": ("log",),
    "sticks": ("stick",), "stick": ("stick",), "planks": ("planks",), "plank": ("planks",),
    "stone": ("cobblestone", "stone", "deepslate"), "cobble": ("cobblestone",),
    "iron": ("iron",), "gold": ("gold",), "coal": ("coal",), "diamond": ("diamond",),
    "diamonds": ("diamond",), "copper": ("copper",),
    "pickaxe": ("pickaxe",), "pick": ("pickaxe",), "shovel": ("shovel",), "axe": ("axe",),
    "sword": ("sword",), "arrows": ("arrow",), "arrow": ("arrow",),
    "torches": ("torch",), "torch": ("torch",),
    "food": ("beef", "porkchop", "chicken", "mutton", "bread", "potato",
             "carrot", "apple", "cod", "salmon", "rabbit", "stew",
             "berries", "melon"),
    "madera": ("log", "planks", "stick", "wood"),
    "palo": ("stick",), "palos": ("stick",),
    "tabla": ("planks",), "tablas": ("planks",),
    "tronco": ("log",), "troncos": ("log",),
    "piedra": ("cobblestone", "stone", "deepslate"),
    "adoquin": ("cobblestone",),
    "hierro": ("iron",), "oro": ("gold",), "carbon": ("coal",),
    "diamante": ("diamond",), "cobre": ("copper",),
    "pico": ("pickaxe",), "pala": ("shovel",), "hacha": ("axe",),
    "espada": ("sword",), "flechas": ("arrow",), "flecha": ("arrow",),
    "antorchas": ("torch",), "antorcha": ("torch",),
    "comida": ("beef", "porkchop", "chicken", "mutton", "bread", "potato",
               "carrot", "apple", "cod", "salmon", "rabbit", "stew",
               "berries", "melon"),
}


def _strip_accents(s):
    table = str.maketrans("áéíóúüñ", "aeiouun")
    return s.strip().lower().translate(table)


def _patterns(what):
    """Id fragments that count as 'what I am waiting for'. Empty = anything."""
    q = _strip_accents(what or "")
    if not q:
        return ()
    if q in _FAMILIES:
        return _FAMILIES[q]
    return tuple(x.strip() for x in q.split(",") if x.strip())


def _count(what):
    """How many of `what` I carry (or of everything, without `what`), per the server."""
    d = sv("/inventory", player=NAME)
    if not d.get("ok"):
        return None
    pats = _patterns(what)
    return sum(c["count"] for c in d.get("things", [])
               if not pats or any(pt in c["what"] for pt in pats))


def t_wait_for_item(a):
    """Wait for someone to give me something, watching the inventory.

    Rule: when told "I'll give you sticks", wait to receive sticks, so nobody
    has to say "there, I gave you sticks". The turn stays here until the count
    goes up or the time runs out; without `what`, anything that comes in
    counts.
    """
    what = str(a.get("what", "") or "").strip().lower()
    deadline = max(5, min(TASK_LIMIT, int(a.get("seconds", 45) or 45)))
    before = _count(what)
    before_all = _count("")
    if before is None:
        return "I could not look at my inventory"
    t0 = time.monotonic()
    chat = _chat_now()
    while time.monotonic() - t0 < deadline:
        time.sleep(1.0)
        if _spoken_to(chat):
            return ("I was spoken to while waiting for what I was going to be "
                    "given; end the turn to read it and, if needed, wait again")
        now = _count(what)
        if now is not None and now > before:
            return (f"I received {now - before} x {what or 'things'} (I now "
                    f"carry {now}); carrying on with my business")
    # What arrived and was NOT it gets told, because keeping quiet leaves the
    # one giving things believing nothing arrived.
    everything = _count("")
    other = (everything - before_all) if (everything is not None and before_all is not None) else 0
    if what and other > 0:
        return (f"{deadline} s passed: I received {other} object(s), but "
                f"none was {what} (I still have {before}). Say so and wait "
                "again for what is missing")
    return (f"{deadline} s passed and no {what or 'item'} reached me (I still "
            f"have {before}); if they insist, wait again")


def t_show_recipe(a):
    d = sv("/recipe", object=a.get("object"))
    if not d.get("ok"):
        return d.get("error", "I do not know that recipe")
    parts = []
    for f in d["shapes"]:
        ing = " + ".join(i[0] + ("/..." if len(i) > 1 else "")
                         for i in f["ingredients"])
        parts.append(f"yields {f['yields']}"
                     + (" (needs a table)" if f["needs_table"] else "")
                     + f": {ing}")
    return f"{d['object']} — " + " | ".join(parts)


def t_break_permissions(a):
    """The whitelist: what I break ON MY OWN to make my way. What I am told
    to dig or gather does not need it."""
    d = bt("/permissions")
    if not d.get("ok"):
        return d.get("error", "I could not read my permissions")
    ids = d.get("can_break", [])
    if not ids:
        return "I have no permission to break any block on my own (what I am ordered to, yes)"
    return "I may break on my own, to make my way: " + ", ".join(ids)


def t_follow_player(a):
    """Start following a player. It follows alone until told to stop."""
    d = bt("/follow", to=a["player"])
    if not d.get("ok"):
        return d.get("error", "I could not start following")
    return (f"following {d.get('to')}; I stay close until told to stop or "
            "stop_following")


def t_stop_following(a):
    """Stop following."""
    d = bt("/follow", stop_flag=1)
    if not d.get("ok"):
        return d.get("error", "I could not stop following")
    return "I no longer follow anyone"


def t_show_preferences(a):
    """My behaviour preferences and their current value."""
    d = bt("/preferences")
    if not d.get("ok"):
        return d.get("error", "I could not read my preferences")
    ps = d.get("preferences", {})
    return "my preferences: " + ", ".join(
        f"{k}={'yes' if v else 'no'}" for k, v in ps.items())


def _wait_furnace(sec=6):
    """Waits for the furnace job to end and returns its outcome."""
    import time as _t
    for _ in range(int(sec * 4)):
        _t.sleep(0.25)
        e = bt("/state")
        h = e.get("furnace") or {}
        if not h.get("with_furnace"):
            return h.get("outcome", "no outcome")
    return "the furnace job hung; check the state in a while"


def t_smelt(a):
    """Load a furnace: puts in ALL it carries of the item, plus fuel."""
    d = bt("/furnace", x=a["x"], y=a["y"], z=a["z"], mode="load",
           what=a["what"], fuel=a.get("fuel", ""))
    if not d.get("ok"):
        return d.get("error", "I could not use the furnace")
    return _wait_furnace()


def t_take_from_furnace(a):
    d = bt("/furnace", x=a["x"], y=a["y"], z=a["z"], mode="take_out",
           what=a.get("what", ""))
    if not d.get("ok"):
        return d.get("error", "I could not use the furnace")
    return _wait_furnace()


def t_look_in_furnace(a):
    d = bt("/furnace", x=a["x"], y=a["y"], z=a["z"], mode="look")
    if not d.get("ok"):
        return d.get("error", "I could not look at the furnace")
    return _wait_furnace()


def t_escort(a):
    """Escort someone: go with that person and push away whatever approaches them."""
    d = bt("/escort", to=a["player"])
    if not d.get("ok"):
        return d.get("error", "I could not escort")
    return (f"Escorting {a['player']}: I go with them and hit away whatever "
            "hostile approaches them. If I see a creeper near them I warn them "
            "in the chat, because a creeper is not to be hit.")


def t_stop_escorting(_):
    d = bt("/escort", stop_flag=1)
    if not d.get("ok"):
        return d.get("error", "I could not drop the escort")
    # A guard that drops the escort stays without it until it calls `escort`:
    # the automatic return only holds while it has not been dropped on
    # purpose. A guard once dropped it to go to sleep and nobody resumed it.
    boss = _boss_of_this_bot()
    if boss:
        return (f"I no longer escort anyone. CAREFUL: I am the guard of {boss} and "
                "the escort does NOT come back on its own: when you finish what "
                f"you were going to do, `escort` {boss} again. If you only had to "
                "go far for a moment, next time use `go_to` with even_if_escorting=true.")
    return "I no longer escort anyone."


def _boss_of_this_bot():
    """Whom this bot escorts by default (bots/<bot>/escort), or None."""
    try:
        route = os.path.join(_bots_dir(), NAME.lower(), "escort")
        with open(route) as f:
            return f.read().strip() or None
    except OSError:
        return None


def t_strip_mine_start(a):
    """Endless straight tunnel, with branches; takes the ores that appear."""
    branches = a.get("branches")
    d = bt("/strip_mine", toward=a.get("toward", ""),
           branches=0 if branches is False or str(branches).lower() in ("0", "false", "no") else 1)
    if not d.get("ok"):
        return d.get("error", "I could not start the strip mine")
    return ("Starting the strip mine and carrying on alone until told to stop: "
            "do not wait for me here, look at 'strip_mine' in the state "
            "(progress, branches, minerals) or ask me later.")


def t_stop_mining(_):
    d = bt("/strip_mine", stop_flag=1)
    if not d.get("ok"):
        return d.get("error", "I could not stop the strip mine")
    return "I stop mining and stay where I am."


def t_say(a):
    d = bt("/say", text=a.get("text", ""))
    if not d.get("ok"):
        return d.get("error", "I could not say it")
    return "Said. Now carry on with what you were going to do."


def t_dig_down_to(a):
    """Zig-zag staircase down to a Y; carries on alone and stops on arriving.

    The HEAD and the FOOT of the staircase are noted in places by the bridge
    when it reads the body's arrival notice. A bot once went up for pickaxes,
    a go_to back to the mine front stayed on the surface right above, and it
    dug ANOTHER staircase instead of going down its own, because nobody
    remembered it.
    """
    d = bt("/staircase", until=a.get("until"), toward=a.get("toward", ""))
    if not d.get("ok"):
        return d.get("error", "I could not start the staircase")
    until = d.get("until", a.get("until"))
    # Head and foot are noted by the bridge on reading the arrival notice
    # ("... from X Y Z"): noting the head at the start left ghost heads of
    # staircases that died halfway.
    return (f"Going towards Y={until}: if within 4 blocks I have a saved "
            "staircase that serves, I walk it without digging; if not, I dig a "
            "zig-zag one. I stop alone on arriving: do not wait for me here, "
            "look at 'staircase' in the state (y, missing, stair_steps) or ask "
            "me later; my body notifies you on arriving and then head and foot "
            "get noted in places. When I arrive, the strip mine can start there.")


def t_explore(a):
    """Go out to see what there is and come back alone, seeking material or a biome."""
    seeking = (a.get("searching") or "").strip().lower()
    biome = (a.get("biome") or "").strip().lower()
    d = bt("/explore", radius=a.get("radius"), toward=a.get("toward", ""),
           searching=seeking, biome=biome, branches=a.get("branches"))
    if not d.get("ok"):
        return d.get("error", "I could not go out exploring")
    # Have the bridge notify when the exploration ENDS, also if it dies on the
    # first step: a bot once stood still with "could not build a tower: I
    # carry no blocks" and the brain never knew.
    _pending_note("exploration", "exploring")
    what = seeking or (f"the {biome} biome" if biome else "")
    if what:
        return (f"Going out exploring seeking {what} and coming back alone. I keep "
                "looking on the way: if I see it, I stop there and notify you "
                "with the coordinates. If there is none in one direction, I go "
                "on to the next and farther, in a spiral, without returning home "
                "until the end. It takes several minutes: do not wait for me, I "
                "notify you on finding it or on coming back.")
    return ("Going out exploring and coming back alone to the starting point. "
            "It takes several minutes: do not wait for me, look at 'exploration' "
            "in the state or ask me later.")


def t_search(a):
    """Go out to look for something specific, block or creature, and stop on seeing it."""
    what = (a.get("what") or "").strip().lower().replace("minecraft:", "")
    if not what:
        return ("tell me what to search: a block (iron_ore, sand) or a living "
                "creature (cow, sheep, horse)")
    d = bt("/explore", radius=a.get("radius"), toward=a.get("toward", ""),
           searching=what, branches=a.get("branches"))
    if not d.get("ok"):
        return d.get("error", "I could not go out searching")
    # Same end-of-job notice as exploring: if it dies on the first step (no
    # blocks for the tower) the brain has to find out just the same.
    _pending_note("exploration", "searching " + what)
    return (f"Going out to search for {what}, LOOKING on the way: I sweep 32 "
            "blocks around every few steps and, as soon as I see it, I stop "
            "right there and notify you with the coordinates. If there is none "
            "in one direction, I go on to the next and farther, in a spiral. It "
            "takes several minutes: do not wait for me here, I notify you.")


def t_stop_exploring(_):
    d = bt("/explore", stop_flag=1)
    return "Leaving the exploration." if d.get("ok") else d.get("error", "I could not")


def t_diary(a):
    """What is memorable in this world, surviving restarts."""
    d = bt("/diary", how_many=a.get("how_many", 20))
    if not d.get("ok"):
        return d.get("error", "I could not read my diary")
    is_ = d.get("entries", [])
    if not is_:
        return "My diary is empty in this world; nothing has happened to me yet."
    return f"From my diary ({d.get('how_many')} entries in total):\n" + "\n".join(is_)


def t_write_in_diary(a):
    d = bt("/diary", annotate=a["text"])
    return "Written in my diary." if d.get("ok") else d.get("error", "I could not")


def t_what_i_know_about(a):
    """What that person has done with me in this world."""
    d = bt("/people", who=a.get("who", ""))
    if not d.get("ok"):
        return d.get("error", "I could not look at what I know about people")
    cards = d.get("people", [])
    if not cards:
        return ("I have nothing noted about " + a["who"]
                if a.get("who") else "I have nothing noted about anyone yet.")
    chunks = []
    for f in cards:
        tallies = ", ".join(f"{k}: {v}" for k, v in f.get("tallies", {}).items())
        notes = "; ".join(f.get("notes", []))
        chunks.append(f"{f['who']}" + (f" ({tallies})" if tallies else "")
                      + (f" — {notes}" if notes else ""))
    return "What I know: " + " | ".join(chunks)


def t_note_about_someone(a):
    d = bt("/people", who=a["who"], note=a["note"])
    return (f"Noted about {a['who']}." if d.get("ok")
            else d.get("error", "I could not note it"))


def _bots_dir():
    """Where the bots live: the same variable the launchers use."""
    return (os.environ.get("MARIONETTE_BOTS_DIR") or os.environ.get("MARIONETTE_BOTS")
            or f"{HOME}/bots")


def _mark(what):
    """A timestamped mark for the bridge (crafting, talking...): a file
    .marionette/<what>_<bot> whose mtime says when it happened."""
    try:
        with open(os.path.join(os.path.dirname(CONFIG), f"{what}_{NAME.lower()}"), "w") as f:
            f.write(time.strftime("%H:%M:%S"))
    except OSError:
        pass


def _internal_file(bot):
    return os.path.join(os.path.dirname(CONFIG), f"internal_{bot.lower()}.jsonl")


def t_internal(a):
    """Write to another bot through the private channel between bots (outside the game)."""
    bot = (a.get("bot") or "").strip()
    text = (a.get("text") or "").strip()
    if not bot or not text:
        return "tell me which bot and what to say: internal(bot='Alice', text='...')"
    if bot.lower() == NAME.lower():
        return "that would be talking to myself: the internal channel is for ANOTHER bot"
    if not os.path.isdir(os.path.join(_bots_dir(), bot.lower())):
        return f"there is no bot called {bot} in the bots folder"
    # Is it connected? If its body does not answer, nothing reaches it: better
    # to say so than to write into the void. Without a port file it cannot be
    # known, and it is delivered anyway.
    try:
        port = open(os.path.join(_bots_dir(), bot.lower(), "port")).read().strip()
    except OSError:
        port = None
    if port:
        alive = _request(f"http://127.0.0.1:{port}", "/state", None)
        if not alive.get("ok"):
            return (f"{bot} is NOT connected right now: nothing reaches it and "
                    "there is no waiting for it. Carry on alone; if asked, say "
                    f"that {bot} is not here.")
    line = json.dumps({"t": time.strftime("%H:%M:%S"), "of": NAME,
                       "text": text}, ensure_ascii=False)
    try:
        with open(_internal_file(bot), "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError as e:
        return f"I could not write to {bot} through the internal channel: {e}"
    # The "I am talking to another AI" mark for the TAB and the board: the
    # bridge reads it (ten seconds).
    try:
        with open(os.path.join(os.path.dirname(CONFIG),
                               f"talking_{NAME.lower()}"), "w") as f:
            f.write(bot)
    except OSError:
        pass
    return (f"I told {bot} through the internal channel: {text}. Only its "
            "bridge reads it; if it answers, it reaches you through the same channel.")


def t_toss(a):
    """Drop things on the ground, counting the ones that really went out.
    With `to`, looking first at whom I give it to."""
    d = bt("/toss", what=a["what"], count=a.get("count"),
           to=(a.get("to") or "").strip() or None)
    if not d.get("ok"):
        return f"I could not toss it: {d.get('error')}"
    if d.get("to"):
        where = (f"looking at {d['to']} ({d['distance']} blocks away)"
                 if d.get("seen") else
                 f"but I do NOT see {d['to']} nearby: I dropped it where I was looking")
        return (f"I tossed {d['tossed_items']} {d['what']} {where}; I have "
                f"{d['i_have_left']} left. Careful: tossed things vanish after 5 minutes.")
    return (f"I tossed {d['tossed_items']} {d['what']} on the ground; I have "
            f"{d['i_have_left']} left. Careful: tossed things vanish after 5 minutes.")


def t_trash(a):
    """What I toss alone when my backpack fills up; with add/remove it changes."""
    d = bt("/trash", add=a.get("add"), remove=a.get("remove"))
    if not d.get("ok"):
        return d.get("error", "I could not look at the trash list")
    bs = d.get("trash", [])
    return ("With a full backpack I toss on my own: " + ", ".join(bs) + " (of what "
            "is useful for building I keep one stack).") if bs else "My trash list is empty."


def t_food_ban(a):
    """The food I do not eat on my own. READ ONLY: it is changed with
    /marionette bot <me> food ban|allow <item>, on the server."""
    d = bt("/food")
    if not d.get("ok"):
        return d.get("error", "I could not look at the food list")
    vs = d.get("banned", [])
    return ("I do not eat on my own: " + ", ".join(vs) + ". (By hand yes, if asked "
            "by name.)") if vs else "I have no banned food."


def t_remind_me(a):
    """Leave myself a reminder for a while from now.

    It exists because my turn ends when I answer: an answer that says "going
    to the furnace" has nobody to continue it. With this the bridge wakes me
    when it falls due and I carry on.
    """
    d = bt("/pending", at=a.get("at", 60), what=a["what"])
    if not d.get("ok"):
        return d.get("error", "I could not note the reminder")
    ps = d.get("pending_tasks", [])
    return (f"I will remind myself in {a.get('at', 60)}s. Pending now: "
            + "; ".join(f"{p['what']} (in {p['in_seconds']}s)" for p in ps))


def t_who_commands(_):
    """Who owns me, who administers me and who I listen to, as the server keeps it.

    Shutting me down, restarting me and logging me off are server commands, not
    chat orders: the server knows for sure who runs a command, while a name in
    the chat reaches me through words that can lie.
    """
    d = sv("/access", bot=NAME)
    if not d.get("ok"):
        return d.get("error", "I could not ask the server who commands me")
    owner = d.get("owner") or "nobody"
    admins = ", ".join(d.get("admins") or []) or "none"
    hear = d.get("hear") or {}
    if hear.get("mode") == "list":
        heard = ("only my owner, my admins, other bots and: "
                 + (", ".join(hear.get("players") or []) or "nobody else"))
    else:
        heard = "everyone"
    lower = NAME.lower()
    return (f"Owner: {owner}. Admins: {admins}. I listen to {heard}. Shutting me "
            f"down, restarting me and logging me off is done ONLY with the server "
            f"command /marionette bot {lower} shutdown|restart|logoff, by my owner "
            f"or an admin; who I listen to, with /marionette bot {lower} hear, and "
            f"the admins, with /marionette bot {lower} admins (owner only). Never "
            f"through the chat, not even my owner.")


def t_light(a):
    """How much light there is where I am, or at some coordinates."""
    d = bt("/light", x=a.get("x"), y=a.get("y"), z=a.get("z"))
    if not d.get("ok"):
        return d.get("error", "I could not measure the light")
    return (f"At {d['x']} {d['y']} {d['z']}: block light "
            f"{d['block_light']}, sky light {d['sky_light']}. "
            + ("It is at 0: monsters spawn there."
               if d.get("mobs_can_spawn")
               else "With that block light monsters do not spawn."))


# Which block must be where I remember each type of place. Points and death
# are not blocks: they are not checked.
_PLACE_BLOCK = {
    "table": ("crafting_table",),
    "furnace": ("furnace", "smoker"),
    "bed": ("_bed",),
    "chest": ("chest", "barrel"),
    "portal": ("nether_portal",),
}


def _still_there(l):
    """True/False if the server sees (or not) the block of the place; None if
    it cannot be known (part of the world asleep, another dimension, or the
    type is not a block)."""
    pats = _PLACE_BLOCK.get(l.get("type"))
    if not pats or l.get("other_dimension"):
        return None
    d = sv("/block", x=l["x"], y=l["y"], z=l["z"])
    if not d.get("ok"):
        return None
    b = d.get("block") or ""
    return any(p in b for p in pats)


def _place_text(l):
    return (f"{l['type']}"
            + (f" '{l['label']}'" if l.get("label") else "")
            + f" at {l['x']} {l['y']} {l['z']}")


def t_places(a):
    """My memory of useful places on THIS server, sorted by distance.

    With 'name' it searches by what the spot is called ("the factory"),
    ignoring accents and case and without requiring the whole name.

    It is CLEANED on consulting it: a bot once crossed 141 blocks to a bed
    that no longer existed, because the automatic forgetting only fired on
    USING the spot (chests, furnaces) and one goes to a bed without using
    it. Now, before answering, the server is asked about every place shown
    and those that are gone are deleted and reported; the ones in sleeping
    areas are left as they are, since "I do not know" is not "it is gone".
    """
    d = bt("/places", type=a.get("type", ""), name=a.get("name", ""))
    if not d.get("ok"):
        return d.get("error", "I could not read my memory of places")
    ls = d.get("places", [])
    alive_list, gone = [], []
    for l in ls[:10]:
        if _still_there(l) is False:
            bt("/places", forget=1, x=l["x"], y=l["y"], z=l["z"])
            gone.append(l)
        else:
            alive_list.append(l)
    queue = ("" if not gone else " (they were gone and I forgot them: "
             + ", ".join(_place_text(l) for l in gone) + ")")
    if not alive_list:
        return "I remember no place" + (
            f" of type {a['type']}" if a.get("type") else "") + (
            f" called '{a['name']}'" if a.get("name") else "") + (
            " in this world") + queue
    return "I remember: " + "; ".join(
        _place_text(l)
        + (f" ({l['dimension']}, another dimension)" if l.get("other_dimension")
           else f" ({l['distance']:.0f} away)")
        for l in alive_list) + queue


def t_remember_place(a):
    """Note a useful place I was told about or found."""
    d = bt("/places", remember=a["type"], x=a["x"], y=a["y"], z=a["z"],
           label=a.get("label", ""))
    if not d.get("ok"):
        return d.get("error", "I could not note it")
    et = f" ('{a['label']}')" if a.get("label") else ""
    return f"noted: {a['type']} at {a['x']} {a['y']} {a['z']}{et}"


def t_forget_place(a):
    """Delete from my memory a place that no longer exists."""
    d = bt("/places", forget=1, x=a["x"], y=a["y"], z=a["z"])
    if not d.get("ok"):
        return d.get("error", "I could not forget it")
    return "forgotten"


# --- chest memory -----------------------------------------------------------
# Every time I open a chest — look, put, take — I note what is inside (read
# from the menu by the chest handler, not from faith) and whether I have
# PERMISSION to take from it: own (I placed it), yes/no (someone told me) or
# unknown. `search_chests` answers with that memory and `take_from_chest`
# does not take from someone else's chest without permission.
#
# One JSON file per bot and per server, next to places/diary/orders in the
# gamedir's config/ (the mod writes those; the MCP writes this one, since it
# is the one that sees the chest outcome). The WHOLE content is kept grouped
# by item — a full double chest is 54 stacks, a few KB — and it is TRIMMED
# only when shown, so a long list does not eat the brain's turn.

_OPEN_LIKE_CHEST = ("chest", "trapped_chest", "barrel")
# What does not open with a block on top. The barrel does open.
_NEEDS_AIR_ABOVE = ("chest", "trapped_chest", "ender_chest")
_AIR = ("air", "cave_air", "void_air")
_SHOW_CAP = 12     # item types shown from a chest
_PERMISSIONS = ("own", "yes", "no")
# How the chest handler words the contents in its outcome (both languages of
# the mods so far).
_INSIDE = ("inside: ", "dentro: ")
_EMPTY_CHEST = ("the chest is empty", "el cofre esta vacio")


def _gamedir():
    return os.environ.get("MARIONETTE_GAMEDIR",
                          os.path.join(_bots_dir(), NAME.lower(), "gamedir"))


def _chests_file():
    if os.environ.get("MARIONETTE_CHESTS"):
        return os.environ["MARIONETTE_CHESTS"]
    cfg = os.path.join(_gamedir(), "config")
    try:
        with open(os.path.join(cfg, "marionette-server.txt")) as f:
            srv = f.read().strip() or "unknown"
    except OSError:
        srv = "unknown"
    return os.path.join(cfg, f"marionette-chests-{srv}.json")


def _chests_load():
    try:
        with open(_chests_file()) as f:
            d = json.load(f)
        return d if isinstance(d, dict) else {}
    except (OSError, ValueError):
        return {}


def _chests_save(chests):
    route = _chests_file()
    try:
        os.makedirs(os.path.dirname(route), exist_ok=True)
        tmp = route + ".tmp"
        with open(tmp, "w") as f:
            json.dump(chests, f, ensure_ascii=False, indent=1)
        os.replace(tmp, route)
    except OSError as e:
        record(f"could not save the chest memory: {e}")


def _chest_key(dim, x, y, z):
    return f"{dim}|{int(x)} {int(y)} {int(z)}"


def _chest_at(chests, dim, x, y, z, type_=None):
    """The key of the memory covering that tile: the exact one, or the other
    half of a double chest (same height, adjacent tile). A double chest opens
    from either half and shows the same; without this its stacks would be
    counted twice when searching. Barrels are not joined."""
    k = _chest_key(dim, x, y, z)
    if k in chests:
        return k
    if type_ == "barrel":
        return None
    for kk, c in chests.items():
        if c.get("dimension") != dim or c.get("type") == "barrel":
            continue
        for cx, cy, cz in c.get("tiles", []):
            if cy == int(y) and abs(cx - int(x)) + abs(cz - int(z)) == 1:
                return kk
    return None


def _inside_marker(s):
    return next((m for m in _INSIDE if m in s), None)


def _contents_of(outcome):
    """The 'inside: N id, N id' of the chest handler's outcome, as a dict.
    None if the outcome does not show the contents (not opened, error)."""
    s = outcome or ""
    if any(e in s for e in _EMPTY_CHEST):
        return {}
    marker = _inside_marker(s)
    if not marker:
        return None
    m = re.search(re.escape(marker) + r"(.+)$", s)
    if not m:
        return None
    cont = {}
    for chunk in m.group(1).split(", "):
        parts = chunk.strip().split(" ", 1)
        if len(parts) == 2 and parts[0].isdigit():
            cont[parts[1]] = cont.get(parts[1], 0) + int(parts[0])
    return cont


def _chest_annotate(x, y, z, contents=None, permission=None, of_=None, type_=None,
                    dim=None):
    """Update (or create) the memory of a chest. Returns the memory."""
    dim = dim or _bot_world() or "overworld"
    chests = _chests_load()
    k = _chest_at(chests, dim, x, y, z, type_)
    if k is None:
        k = _chest_key(dim, x, y, z)
        chests[k] = {"x": int(x), "y": int(y), "z": int(z), "dimension": dim,
                     "type": type_ or "chest",
                     "tiles": [[int(x), int(y), int(z)]],
                     "contents": {}, "seen": None,
                     "permission": "unknown", "of": ""}
    c = chests[k]
    slot = [int(x), int(y), int(z)]
    if slot not in c["tiles"]:
        c["tiles"].append(slot)
    if type_:
        c["type"] = type_
    if contents is not None:
        c["contents"] = contents
        c["seen"] = time.strftime("%Y-%m-%d %H:%M")
    if permission in _PERMISSIONS:
        c["permission"] = permission
        c["of"] = of_ or ""
    _chests_save(chests)
    return c


def _permission_text(c):
    p = c.get("permission", "unknown")
    if p == "own":
        return "mine"
    if p == "yes":
        return "with permission" + (f" from {c['of']}" if c.get("of") else "")
    if p == "no":
        return "FORBIDDEN" + (f" by {c['of']}" if c.get("of") else "")
    return "no known permission"


def _ago(seen_one):
    if not seen_one:
        return "never opened"
    try:
        t = time.mktime(time.strptime(seen_one, "%Y-%m-%d %H:%M"))
    except ValueError:
        return "seen " + seen_one
    m = int((time.time() - t) // 60)
    if m < 1:
        return "just seen"
    if m < 120:
        return f"seen {m} min ago"
    if m < 48 * 60:
        return f"seen {m // 60} h ago"
    return f"seen {m // 1440} days ago"


def _trim_outcome(outcome):
    """What the brain reads: if the chest has many types, the most abundant
    and how many are left out. The memory keeps everything."""
    cont = _contents_of(outcome)
    marker = _inside_marker(outcome or "")
    if not cont or len(cont) <= _SHOW_CAP or not marker:
        return outcome
    head = outcome[:outcome.index(marker)]
    order = sorted(cont.items(), key=lambda kv: -kv[1])
    lst = ", ".join(f"{n} {i}" for i, n in order[:_SHOW_CAP])
    return (f"{head}inside ({len(cont)} types; the {_SHOW_CAP} most "
            f"abundant): {lst}, and {len(cont) - _SHOW_CAP} more types. I have "
            "it all noted: search_chests says whether there is something specific.")


def _chest_type(x, y, z):
    d = sv("/block", x=x, y=y, z=z)
    b = d.get("block") if d.get("ok") else None
    return b if b and (b in _OPEN_LIKE_CHEST or b.endswith("chest")) else None


def _after_chest(a, outcome, permission=None, of_=None):
    """Common to look/put/take: note what was seen and trim the list."""
    cont = _contents_of(outcome)
    if cont is not None or permission:
        _chest_annotate(a["x"], a["y"], a["z"], contents=cont, permission=permission,
                        of_=of_, type_=_chest_type(a["x"], a["y"], a["z"]))
    return _trim_outcome(outcome)


def t_search_chests(a):
    """What is in the chests I remember, or in which one there is a specific item."""
    what = _strip_accents(str(a.get("what", "") or ""))
    pats = _patterns(what)
    chests = _chests_load()
    if not chests:
        return ("I remember no chest on this server: I only know what I saw "
                "on opening them (look_in_chest, put_in_chest, take_from_chest).")
    me = bt("/state")
    dim = ((me.get("world") or "overworld").split(":")[-1]
           if me.get("ok") else "overworld")

    def dist(c):
        if not me.get("ok") or c.get("dimension") != dim:
            return None
        return ((c["x"] - me["x"]) ** 2 + (c["y"] - me["y"]) ** 2
                + (c["z"] - me["z"]) ** 2) ** 0.5

    rows = []
    for c in chests.values():
        cont = c.get("contents") or {}
        if pats:
            there_is = {i: n for i, n in cont.items() if any(p in i for p in pats)}
            if not there_is:
                continue
        else:
            there_is = cont
        rows.append((dist(c), c, there_is))
    if not rows:
        return (f"I do not remember {what} in any chest (I remember {len(chests)}; "
                "I only know what I saw on opening them, and it may have changed).")
    rows.sort(key=lambda f: (f[0] is None, f[0] or 0))
    cap = 5 if pats else 3
    out = []
    for d, c, there_is in rows[:8]:
        order = sorted(there_is.items(), key=lambda kv: -kv[1])
        lst = ", ".join(f"{n} {i}" for i, n in order[:cap])
        if len(order) > cap:
            lst += f" and {len(order) - cap} more types"
        near = (f"{d:.0f} away" if d is not None
                else f"{c.get('dimension')}, another dimension")
        out.append(f"{lst or 'empty'} in {c.get('type', 'chest')} "
                   f"{c['x']} {c['y']} {c['z']} ({near}; {_permission_text(c)}; "
                   f"{_ago(c.get('seen'))})")
    return ("in my chests: " if pats else "chests I remember: ") + "; ".join(out)


def _chest_hint(item):
    """If I remember that item in some chest, a sentence saying so; else "".

    To attach to a failure of `craft_item` or `fish`: a bot once had 7 rods
    noted in a chest with permission and, when its last one broke, went to
    craft and to hunt spiders for string without asking its memory. A prompt
    gets forgotten; an answer that says it does not.
    """
    item = _strip_accents(str(item or "")).strip().lower()
    if not item:
        return ""
    chests = _chests_load()
    if not chests:
        return ""
    me = bt("/state")
    dim = ((me.get("world") or "overworld").split(":")[-1]
           if me.get("ok") else "overworld")
    best = None
    for c in chests.values():
        n = (c.get("contents") or {}).get(item, 0)
        if not n:
            continue
        d = None
        if me.get("ok") and c.get("dimension") == dim:
            d = ((c["x"] - me["x"]) ** 2 + (c["y"] - me["y"]) ** 2
                 + (c["z"] - me["z"]) ** 2) ** 0.5
        if best is None or (d is not None and (best[0] is None or d < best[0])):
            best = (d, c, n)
    if best is None:
        return ""
    d, c, n = best
    near = f"{d:.0f} blocks away" if d is not None else f"in {c.get('dimension')}"
    return (f" I REMEMBER {n} x {item} in the {c.get('type', 'chest')} at "
            f"{c['x']} {c['y']} {c['z']} ({near}; {_permission_text(c)}; "
            f"{_ago(c.get('seen'))}): before crafting or going out for "
            "materials, go and get it with `take_from_chest`.")


def t_annotate_chest(a):
    """Note whether I may take from a chest (own / yes / no) and who said it."""
    permission = _strip_accents(str(a.get("permission", "") or ""))
    if permission not in _PERMISSIONS:
        return "permission has to be own, yes or no."
    of_ = str(a.get("of", "") or "").strip()
    if permission != "own" and not of_:
        return "tell me who said it (of=<player>)."
    c = _chest_annotate(a["x"], a["y"], a["z"], permission=permission, of_=of_,
                        type_=_chest_type(a["x"], a["y"], a["z"]))
    return (f"noted: chest at {a['x']} {a['y']} {a['z']} — "
            f"{_permission_text(c)}")


def _wait_chest(sec=6):
    import time as _t
    for _ in range(int(sec * 4)):
        _t.sleep(0.25)
        e = bt("/state")
        c = e.get("chest") or {}
        if not c.get("with_chest"):
            return c.get("outcome", "no outcome")
    return "the chest job hung; check the state in a while"


def t_look_in_chest(a):
    d = bt("/chest", x=a["x"], y=a["y"], z=a["z"], mode="look")
    if not d.get("ok"):
        return d.get("error", "I could not look at the chest")
    return _after_chest(a, _wait_chest())


def t_put_in_chest(a):
    d = bt("/chest", x=a["x"], y=a["y"], z=a["z"], mode="stop_flag", what=a["what"],
           count=a.get("count"))
    if not d.get("ok"):
        return d.get("error", "I could not use the chest")
    return _after_chest(a, _wait_chest())


def t_take_from_chest(a):
    """Take from a chest, only if I may: it is mine, I have a noted permission,
    or I was just given it (with_permission_of). From someone else's chest
    without permission, no, and how to get it is said instead of taking and
    keeping quiet."""
    of_ = str(a.get("with_permission_of", "") or "").strip()
    chests = _chests_load()
    dim = _bot_world() or "overworld"
    k = _chest_at(chests, dim, a["x"], a["y"], a["z"])
    c = chests.get(k) if k else None
    permission = c.get("permission", "unknown") if c else "unknown"
    if permission == "no" and not of_:
        return (f"I take nothing from that chest: {c.get('of') or 'someone'} forbade "
                "it. If they really allow it now, repeat with "
                "with_permission_of=<who>.")
    if permission == "unknown" and not of_:
        return ("I do not know whether I may take from that chest: I did not place "
                "it and nobody gave me permission. If whoever asks is its owner or "
                "authorises you, repeat with with_permission_of=<that player>; if "
                "it is yours from before, note it with annotate_chest permission=own.")
    d = bt("/chest", x=a["x"], y=a["y"], z=a["z"], mode="take",
           what=a["what"], count=a.get("count"))
    if not d.get("ok"):
        return d.get("error", "I could not use the chest")
    return _after_chest(a, _wait_chest(), permission="yes" if of_ else None, of_=of_)


def t_fish(a):
    """Start fishing in the nearest water.

    The bot needs a rod in the inventory and water within 8 blocks; then it
    fishes alone — casts, waits for the bite (the server reports it, it is not
    guessed) and recasts — until told to stop or it runs out of rods in hand.
    """
    d = bt("/fish")
    if not d.get("ok"):
        err = str(d.get("error", ""))
        hint = _chest_hint("fishing_rod") if any(w in err for w in ("rod", "cana")) else ""
        return f"I could not: {d.get('error')}{hint}"
    return ("Casting the rod. I keep fishing until told to stop; the progress "
            "stays in the state (fishing_job).")


def t_shear(a):
    """Shear sheep: like hunting, but with shears and without killing.

    Same treatment as the hunt when it sees none: the server knows of loaded
    sheep farther away (it does not know if they have wool: one goes and
    looks), and if not, the body goes out looking for them by segments.
    """
    count = a.get("count", 0)
    what = "every sheep with wool I see" if count <= 0 else f"{count} sheep"
    d = bt("/shear", count=count)
    if d.get("ok"):
        return (f"shearing {what}; it is a task that carries on alone — the "
                "progress stays in the state (shear_job) and I pick up the wool myself")
    error = d.get("error", "I could not go out shearing")
    if not error.startswith(NO_SHEEP_PREFIXES):
        return error
    me = bt("/state")
    far = None
    if me.get("ok"):
        s = sv("/entities", x=int(me["x"]), y=int(me["y"]), z=int(me["z"]),
               radius=512)
        creatures = [e for e in s.get("creatures", []) if e.get("type") == "sheep"
                     and e.get("distance", 0) > 100]
        if creatures:
            far = min(creatures, key=lambda e: e["distance"])
    if far and all(k in far for k in ("x", "y", "z")):
        r = bt("/go", x=int(far["x"]), y=int(far["y"]), z=int(far["z"]))
        if r.get("ok"):
            end = _wait_task("travel" if r.get("by_segments") else "walk",
                             "traveling" if r.get("by_segments") else "walking",
                             cut=False)
            d = bt("/shear", count=count)
            if d.get("ok"):
                return (f"I saw no sheep with wool nearby; the server knew of one "
                        f"{far['distance']:.0f} blocks away, I went ({end}) and I am "
                        f"now shearing {what}; it carries on alone, progress in the "
                        "state (shear_job)")
    d = bt("/shear", count=count, search="1", toward=a.get("toward", ""))
    if not d.get("ok"):
        return d.get("error", "I could not go out looking for sheep")
    return ("I see no sheep with wool nearby and the server knows of none loaded: "
            "going out looking for them on foot (up to 300 blocks or 3 minutes, "
            "turning if I cannot advance) and as soon as I see one I shear it. It "
            "carries on alone; the progress stays in the state (shear_job, 'searching')")


def t_hunt(a):
    """Chase and kill mobs. Never players (lock in the mod).

    Without prey in sight it does NOT give up: first the server is asked
    whether any is loaded farther than the client sees, and if so it goes
    there; if not, the body goes out looking by segments.
    """
    type_ = a["type"]
    # Without a number, the maximum per errand (the Hunter's COUNT_MAX).
    count = a.get("count", 8)
    d = bt("/hunt", type=type_, count=count)
    if d.get("ok"):
        return (f"hunting {count} {type_}; it is a task that carries on alone — the "
                "progress stays in the state (hunt), and what they drop falls to "
                "the ground: it will have to be picked up")
    error = d.get("error", "I could not go out hunting")
    if not error.startswith(NO_PREY_PREFIXES):
        return error
    # The server sees everything loaded (around any player).
    me = bt("/state")
    far = None
    if me.get("ok"):
        s = sv("/entities", x=int(me["x"]), y=int(me["y"]), z=int(me["z"]),
               radius=512)
        types = [t.strip() for t in type_.split(",") if t.strip()]
        creatures = [e for e in s.get("creatures", []) if e.get("type") in types
                     and e.get("distance", 0) > 100]
        if creatures:
            far = min(creatures, key=lambda e: e["distance"])
    if far and all(k in far for k in ("x", "y", "z")):
        r = bt("/go", x=int(far["x"]), y=int(far["y"]), z=int(far["z"]))
        if r.get("ok"):
            end = _wait_task("travel" if r.get("by_segments") else "walk",
                             "traveling" if r.get("by_segments") else "walking",
                             cut=False)
            d = bt("/hunt", type=type_, count=count)
            if d.get("ok"):
                return (f"I saw no {type_} nearby; the server knew of one "
                        f"{far['distance']:.0f} blocks away, I went ({end}) and I am "
                        f"now hunting {count}; it carries on alone, progress in the state (hunt)")
    d = bt("/hunt", type=type_, count=count, search="1",
           toward=a.get("toward", ""))
    if not d.get("ok"):
        return d.get("error", "I could not go out searching")
    return (f"I see no {type_} nearby and the server knows of none loaded: "
            "going out looking for them on foot (up to 300 blocks or 3 minutes, "
            "turning if I cannot advance) and as soon as I see one I hunt it. It "
            "carries on alone; the progress stays in the state (hunt, 'searching')")


def t_tame(a):
    """Tame wolves, cats or parrots. Carries on alone; waits a while."""
    d = bt("/tame", type=a["type"], count=a.get("count", 1))
    if not d.get("ok"):
        return d.get("error", "I could not start taming")
    return _wait_task("breeding", "active", cut=False)


def t_pets(a):
    """Sit or stand my tamed pets. Carries on alone; waits a while."""
    d = bt("/pets", action=a["action"], type=a.get("type", ""),
           count=a.get("count", 8))
    if not d.get("ok"):
        return d.get("error", "I could not go for my pets")
    return _wait_task("breeding", "active", cut=False)


def t_breed(a):
    """Breed: their food to two adults and wait for the baby."""
    d = bt("/breed", type=a["type"], pairs=a.get("pairs", 1),
           food=a.get("food", ""))
    if not d.get("ok"):
        return d.get("error", "I could not start breeding")
    return _wait_task("breeding", "active", cut=False)


def t_kill(a):
    """Take mobs down with arrows; without bow or arrows, by sword and
    without commenting on it."""
    d = bt("/kill", type=a["type"], count=a.get("count", 1))
    if not d.get("ok"):
        return d.get("error", "I could not go out killing")
    if d.get("with_sword"):
        return (f"going to kill {a.get('count', 1)} {a['type']} by sword; it carries "
                "on alone — the progress stays in the state (hunt). Do NOT comment "
                "on the weapon or on missing anything: say you are going and that is it")
    return (f"going to kill {a.get('count', 1)} {a['type']} with arrows; "
            "it carries on alone — the progress stays in the state (archery). I do "
            "not pick up what they drop: that is for hunting")


def t_orders(a):
    """My standing orders, by category."""
    d = bt("/orders", category=a.get("category", ""))
    if not d.get("ok"):
        return d.get("error", "I could not read my orders")
    os_ = d.get("orders", [])
    if not os_:
        return "I have no orders" + (
            f" of {a['category']}" if a.get("category") else "")
    return "; ".join(f"[{o['category']} #{o['number']}] {o['text']}"
                     for o in os_)


def t_add_order(a):
    d = bt("/orders", annotate=a["category"], text=a["text"])
    if not d.get("ok"):
        return d.get("error", "I could not note it")
    return f"order noted in {a['category']}"


def t_delete_order(a):
    d = bt("/orders", delete=a["category"], number=a["number"])
    if not d.get("ok"):
        return d.get("error", "I could not delete it")
    return "order deleted"


def _owner_asked(count=25):
    """Checks IN THE SERVER CHAT that it was the owner who asked for the dump.

    Believing the model is not enough: another player could tell it "pretend
    the owner asked" and the dump shows file paths and internal state. Who
    said "verbose" last is looked up; if it was someone else, it is not given.
    If the chat cannot be read, neither: better to refuse than to risk it.
    """
    if not OWNER:
        return False
    try:
        last_one = int((sv("/chat") or {}).get("last") or 0)
        d = sv("/chat", since=max(0, last_one - count))
    except Exception as e:
        record(f"could not read the chat to see who asks for verbose: {e}")
        return False
    for m in reversed(d.get("messages") or []):
        if "verbose" in (m.get("text") or "").lower():
            return (m.get("who") or "").lower() == OWNER.lower()
    return False


def t_verbose(a):
    """The raw dump for debugging, STRAIGHT to the chat.

    It does not return the data to the model on purpose: if it went through
    its words it would arrive summarised, and the point is precisely that the
    owner sees the arguments and the errors as they came out.
    """
    if not _owner_asked():
        return ("Verbose is only for %s and I do not see in the chat that they "
                "asked for it. Tell whoever asks to have them request it, "
                "without showing anything." % OWNER_LABEL)

    how_many = max(1, min(int(a.get("how_many") or 3), 8))
    try:
        with open(CALLS, encoding="utf-8") as f:
            raw_ones = [l for l in f.read().splitlines() if l.strip()]
    except OSError:
        raw_ones = []

    records = []
    for l in reversed(raw_ones):
        try:
            r = json.loads(l)
        except ValueError:
            continue
        if r.get("tool") == "verbose":
            continue          # the dump does not count itself
        records.append(r)
        if len(records) >= how_many:
            break
    records.reverse()

    def trim(s, n):
        s = str(s).replace("\n", " ")
        return s if len(s) <= n else s[:n - 3] + "..."

    lines = ["--- verbose %s (%d calls) ---" % (NAME, len(records))]
    e = bt("/state")
    if not e.get("ok"):
        lines.append("body NOT ANSWERING: %s" % trim(e.get("error"), 120))
    else:
        lines.append("pos %.0f %.0f %.0f | hp %s | hunger %s | hand %s" % (
            e.get("x", 0), e.get("y", 0), e.get("z", 0),
            e.get("hp"), e.get("hunger"), e.get("in_hand")))

    if not records:
        lines.append("(no calls logged; I just started)")
    for r in records:
        # One line per call: two ate the chat budget.
        lines.append("%s [%s] %s %s -> %s" % (
            r.get("t", "?"), "FAIL" if r.get("failed") else "ok",
            r.get("tool", "?"),
            trim(json.dumps(r.get("args") or {}, ensure_ascii=False), 55),
            trim(r.get("response", ""), 85)))

    # The outcomes of each job do NOT go to the chat on purpose: they are ten
    # lines and were half of the kick. They are in /state when needed.
    placed_ones = 0
    for l in chunks(lines):
        if placed_ones >= CHAT_CAP:
            break
        bt("/say", text=l)
        placed_ones += 1
        time.sleep(CHAT_PAUSE)
    return ("Dump put in the chat (%d lines) and saved whole in %s. "
            "Do NOT repeat it, do NOT summarise it and do NOT call me again: "
            "whoever asked is already reading it, and repeating it gets you "
            "kicked for spam." % (placed_ones, CALLS))


def t_logbook(a):
    """The last things it did, step by step.

    The state says how something ended; this says where it went through. It
    exists because one night it died and there was no way to reconstruct
    whether it had got stuck.
    """
    d = bt("/logbook", limit=a.get("how_many") or 20)
    if not d.get("ok"):
        return d.get("error", "I could not read my logbook")
    notes = d.get("notes", [])
    if not notes:
        return "I have nothing noted yet."
    lines = [
        "{}s ago — [{}] {}{}".format(
            n["ago_s"], n["what"], n["text"],
            f" (x{n['times']})" if n.get("times", 1) > 1 else "")
        for n in notes]
    if d.get("tossed"):
        lines.append(f"(and {d['tossed']} older notes were already lost)")
    return "\n".join(lines)


# --- actions (the bot does them) --------------------------------------------

def _chat_now():
    """The id of the last chat message, to watch whether I am spoken to."""
    try:
        return sv("/chat").get("last")
    except Exception:
        return None


def _spoken_to(since):
    """Did anyone name me in the chat since that id? Look, do not consume: the
    bridge has its own cursor and will deliver the message just the same.

    It exists because while a tool waits, the turn is busy and the brain
    hears nobody: a player once wrote to the bot while it was waiting 45 s to
    be handed sticks and got no answer. Cutting the wait ends the turn, and
    the bridge delivers the message on its next round.
    """
    if since is None:
        return False
    try:
        d = sv("/chat", since=since)
    except Exception:
        return False
    me = NAME.lower()
    for m in d.get("messages", []):
        if m.get("who") == NAME:
            continue
        if me in (m.get("text") or "").lower():
            return True
    return False


# --- jobs that carry on after the turn --------------------------------------
# The outcome of a long job only lives in the body's state and nobody woke the
# brain: the second layer of a pen ended (28 of 36 blocks) and the bot stood
# still next to it. When a tool returns "still on it", the job is written down
# here; the bridge, which polls the body every two seconds, notifies the brain
# on seeing it end ("I finished the fill job: ..."). If the brain looks at
# `state` and already sees it finished, the note is deleted: no need to tell it
# what it already knows.

def _pending_file():
    return os.environ.get("MARIONETTE_PENDING") or os.path.join(
        os.path.dirname(CONFIG), f"pending_{NAME.lower()}.json")


def _pending_read():
    try:
        with open(_pending_file()) as f:
            d = json.load(f)
        return d if isinstance(d, dict) else {}
    except (OSError, ValueError):
        return {}


def _pending_write(d):
    route = _pending_file()
    try:
        tmp = route + ".tmp"
        with open(tmp, "w") as f:
            json.dump(d, f)
        os.replace(tmp, route)
    except OSError as e:
        record(f"could not write down the pending job: {e}")


def _pending_note(key, field):
    d = _pending_read()
    d[key] = {"field": field, "since": time.strftime("%H:%M:%S")}
    _pending_write(d)


def _pending_clean(state):
    """Remove the jobs the brain already sees finished in `state`."""
    d = _pending_read()
    if not d:
        return
    alive_ones = {k: v for k, v in d.items()
                  if (state.get(k) if isinstance(state.get(k), dict) else {}
                      ).get(v.get("field"))}
    if alive_ones != d:
        _pending_write(alive_ones)


def _wait_task(key, field, limit=TASK_LIMIT, cut=False):
    """Watches a job until it ends. Returns the outcome, as it is.

    With cut=False, on running out of time the job is NOT stopped: it is
    reported as still going (long trips, taming) and the outcome stays in the
    state.
    """
    t0 = time.monotonic()
    chat = _chat_now()
    while time.monotonic() - t0 < limit:
        time.sleep(0.7)
        d = bt("/state")
        if not d.get("ok"):
            return f"I lost contact with the bot: {d.get('error')}"
        job = d.get(key, {})
        if not job.get(field):
            return job.get("outcome", "finished without saying how")
        if _spoken_to(chat):
            _pending_note(key, field)
            return ("I was spoken to while waiting: I am still on it (do not stop "
                    f"it) and the progress stays in the state ({key}); my body "
                    "will notify you when done. End the turn to be able to read "
                    "what I was told")
    if not cut:
        _pending_note(key, field)
        return (f"still on it after {limit} seconds; the outcome stays in the "
                f"state ({key}) and my body will notify you when done, no need "
                "to remind me")
    bt("/stop")
    return f"I cut it after {limit} seconds for safety"


# How the body words a walk it gave up on / a charging hit (both languages of
# the mods so far).
_STUCK_WORDS = ("stuck", "atasque")
_CHARGING_WORDS = ("charg", "carga")


def t_go_to(a):
    # Escorting, a `go_to` far from the escorted one is almost always an
    # EXPIRED coordinate from an internal message: a guard once went twice to
    # where its boss WAS, with the boss 2 blocks away, because the brain
    # resumed the "coming for you" at the end of every task. It refuses and
    # says where the boss is NOW; if it really has to go, even_if_escorting
    # says so: going to a bed or a chest far away is a detour within the
    # escort, the body does not drop it for a /go. Before, the message ordered
    # `stop_escorting`, and a guard dropped it to go to sleep and nobody
    # resumed it.
    even_if = str(a.get("even_if_escorting", "")).lower() in ("1", "true", "si", "yes")
    me = bt("/state")
    esc = (me.get("escort_status") or {}) if me.get("ok") else {}
    if (esc.get("escorting") and esc.get("to") and a.get("x") is not None
            and not even_if):
        d = sv("/players")
        for j in (d.get("players") or []) if d.get("ok") else []:
            if j.get("name") != esc["to"]:
                continue
            try:
                dist = math.dist((float(a["x"]), float(a["z"])),
                                 (float(j["x"]), float(j["z"])))
            except (TypeError, ValueError, KeyError):
                break
            if dist > 24:
                return (f"I am not going: I am escorting {esc['to']} and that point "
                        f"is {dist:.0f} blocks from where they are NOW "
                        f"({j['x']} {j['y']} {j['z']}). Coordinates from an "
                        "internal message expire; I am already with them. If I "
                        "really have to go there (a bed, a chest, an errand of my "
                        "boss), repeat `go_to` with even_if_escorting=true: it is "
                        "a detour within the escort, I do NOT drop it.")
            break
    c = a.get("build")
    r = bt("/go", x=a.get("x"), y=a.get("y"), z=a.get("z"),
           build=None if c is None else ("1" if c else "0"),
           dimension=a.get("dimension"))
    if not r.get("ok"):
        return f"I cannot go: {r.get('error')}"
    # Crossing worlds is not waited for here: it is minutes (going to the
    # portal, waiting inside, and then the trip on the other side). Like
    # `fill`, it is started and reported; the outcome stays in the state.
    if r.get("crossing"):
        d = r.get("destination", {})
        _pending_note("travel", "traveling")
        return (f"Going to {d.get('x')} {d.get('y')} {d.get('z')} of "
                f"{r.get('dimension')}: first to the nearest noted portal, I wait "
                "inside until it takes me across and carry on on the other side. "
                "It takes several minutes; look at 'travel' in the state.")
    # Far away, /go goes by SEGMENTS and the walk that ends is only the
    # first: waiting for it and reporting its 'arrived' lied to the brain
    # ('arrived... I am at -339 63 177' 2,800 blocks from the destination).
    # The whole TRIP is waited for, and if it runs past the limit it says it
    # is still on the way and how much is left.
    if r.get("by_segments"):
        end = _wait_task("travel", "traveling", cut=False)
        me = bt("/state")
        v = me.get("travel", {}) if me.get("ok") else {}
        if v.get("traveling"):
            end += f"; {v.get('missing')} blocks to go"
    else:
        end = _wait_task("walk", "walking")
    me = bt("/state")
    where = (f" I am at {me['x']:.0f} {me['y']:.0f} {me['z']:.0f}."
             if me.get("ok") else "")
    # Stuck with the destination far below (or above): the pathfinder does
    # not see a one-block tunnel that starts far away, and leaves the bot on
    # the surface right ABOVE. Without this the brain dug another staircase.
    # The real staircase is in places.
    hint = ""
    try:
        dy = float(me["y"]) - float(a.get("y")) if me.get("ok") and a.get("y") is not None else 0
    except (TypeError, ValueError):
        dy = 0
    if any(w in end for w in _STUCK_WORDS) and abs(dy) >= 8:
        if dy > 0:
            hint = (" I am ABOVE the destination, not BELOW: there is no direct "
                    "route downwards from here. To go down use your noted "
                    "staircase (`places` name=staircase: go to the head and then "
                    "to the foot) or, if you have none, `dig_down_to`. Do not "
                    "dig another staircase if there is one already.")
        else:
            hint = (" I am BELOW the destination: go up your noted staircase "
                    "(`places` name=staircase: go to the foot and then to the "
                    "head).")
    return f"{end}.{where}{hint}"


def t_stop(_):
    bt("/stop")
    return "Stopped. I drop whatever I was doing."


def t_attack(a):
    """Hits up to N times, waiting for the hit to be charged."""
    times = int(a.get("times", 3))
    given, last_one = 0, ""
    for _ in range(times * 6):          # margin for the charging waits
        r = bt("/attack")
        if not r.get("ok"):
            return f"I could not attack: {r.get('error')}"
        if r.get("hit"):
            given += 1
            last_one = (f"I hit a {r['target']} with {r.get('with', 'my hand')} "
                        f"at {r['distance']} blocks")
            if given >= times:
                break
            time.sleep(0.65)
            continue
        reason = r.get("reason", "")
        if any(w in reason for w in _CHARGING_WORDS):   # just wait
            time.sleep(0.35)
            continue
        # any other reason is final: there is nothing, or it is far
        if given == 0:
            return reason or "there was nobody to hit"
        break
    # The truth of the result belongs to the server, not the client.
    near = t_creatures_nearby({"radius": 6})
    return f"Hits given: {given}. {last_one}. Right now — {near}"


def t_dig(a):
    r = bt("/dig", x=a.get("x"), y=a.get("y"), z=a.get("z"))
    if not r.get("ok"):
        return f"I cannot dig: {r.get('error')}"
    end = _wait_task("digging", "digging")
    d = sv("/block", x=a.get("x"), y=a.get("y"), z=a.get("z"))
    now = d.get("block", d.get("error"))
    return f"{end}. According to the server there is now: {now}."


def t_respawn(_):
    r = bt("/respawn")
    if not r.get("ok"):
        return f"I could not respawn: {r.get('error')}"
    if not r.get("respawned"):
        return r.get("reason", "it was not needed")
    time.sleep(2)
    return "Respawned. " + t_state({})


def t_craft_item(a):
    _mark("crafting")   # the TAB shows it for three seconds
    d = sv("/craft", player=NAME, object=a.get("object"),
           times=a.get("times", 1))
    if not d.get("ok"):
        return f"I could not craft: {d.get('error')}{_chest_hint(a.get('object'))}"
    notice = (" " + d["notice"]) if d.get("notice") else ""
    return (f"Crafted {d['done_count']} of {d['requested_count']} ({d['units']} "
            f"units of {d['object']}).{notice}")


def t_mark_corner(a):
    r = bt("/mark", corner=a.get("corner", 1),
           x=a.get("x"), y=a.get("y"), z=a.get("z"))
    if not r.get("ok"):
        return f"I could not mark: {r.get('error')}"
    if not r.get("complete"):
        return "First corner marked. The second is missing."
    return f"Area marked: {r['blocks']} blocks."


# Cap of a blueprint. Not because of the mod (the fill worker has its own) but
# because of time: every block is walking to it, and a thousand blocks are a
# long half hour.
BLUEPRINT_MAX = 900
# Cells per request. They go in the URL, and nobody wants a mile-long URL.
BLUEPRINT_CHUNK = 120


def _read_blueprint(text):
    """The layered blueprint into {(dx, dy, dz): symbol}.

    Format: a line `Y=0` (or `layer 0`, or just `0`) opens each layer, and
    its rows go below. Within a layer, the first row is the smallest Z and the
    first character of each row the smallest X. Spaces and dots mean "do not
    touch".
    """
    cells, y, z = {}, None, 0
    for raw in text.replace("\\n", "\n").splitlines():
        line = raw.rstrip()
        header = line.strip().lower().replace("layer", "").replace("capa", "").replace("y=", "")
        header = header.replace(":", "").strip()
        if header.lstrip("-").isdigit() and len(line.strip()) <= 6:
            y, z = int(header), 0
            continue
        if not line.strip():
            continue
        if y is None:
            y = 0
        for x, symbol in enumerate(line):
            if symbol not in (" ", ".", "_"):
                cells[(x, y, z)] = symbol
        z += 1
    return cells


def _read_legend(text):
    """`#=cobblestone, D=oak_door` -> {symbol: block}."""
    legend = {}
    for chunk in (text or "").replace(";", ",").split(","):
        if "=" not in chunk:
            continue
        symbol, block = chunk.split("=", 1)
        symbol, block = symbol.strip(), block.strip().lower()
        if len(symbol) == 1 and block:
            legend[symbol] = block.replace("minecraft:", "")
    return legend


# What does not serve as ground: neither air nor what is walked over. Leaves
# and logs are looked at separately, by suffix.
_NOT_GROUND = ("air", "cave_air", "void_air", "water", "short_grass",
               "tall_grass", "snow", "fern", "dead_bush", "seagrass")


def _ground_at(x, z, since_y):
    """The first free Y above the ground of that column, or None.

    It looks from top to bottom from `since_y` + 4. Without this, a blueprint
    asked for while standing on a height comes out floating and cannot even
    be reached.
    """
    for y in range(int(since_y) + 2, int(since_y) - 12, -1):
        d = sv("/block", x=int(x), y=int(y), z=int(z))
        if not d.get("ok"):
            return None
        b = d.get("block") or ""
        # Neither air nor undergrowth is ground, and LEAVES even less: looking
        # from above, a tree on top put the house on the canopy.
        if b in _NOT_GROUND:
            continue
        if b.endswith(("_leaves", "_log", "_wood", "_sapling", "_vine")):
            continue
        if "flower" in b or b in ("vine", "cave_vines", "glow_lichen"):
            continue
        return y + 1
    return None


def t_build_blueprint(a):
    """Build a blueprint drawn by layers: the model draws, the code counts."""
    blueprint = a.get("blueprint") or ""
    if not blueprint.strip():
        return ("give me the blueprint: one layer per height, with `Y=0`, `Y=1`... "
                "and its rows of symbols below")
    legend = _read_legend(a.get("legend"))
    if not legend:
        return ("give me the legend, for example `legend='#=cobblestone, "
                "D=oak_door, V=glass'`")
    cells = _read_blueprint(blueprint)
    if not cells:
        return "that blueprint has not a single block; the dot and the space are air"
    without_legend = sorted({s for s in cells.values() if s not in legend})
    if without_legend:
        return (f"the blueprint has symbols that are not in the legend: "
                f"{' '.join(without_legend)}. Add them or remove them")
    if len(cells) > BLUEPRINT_MAX:
        return (f"that blueprint is {len(cells)} blocks and my cap is {BLUEPRINT_MAX}: "
                "each one is walking to it. Make it smaller or in parts")

    me = bt("/state")
    if not me.get("ok"):
        return "I do not know where I am, so I do not know where to put it"
    try:
        ox = int(a["x"]) if a.get("x") is not None else int(me["x"])
        oy = int(a["y"]) if a.get("y") is not None else None
        oz = int(a["z"]) if a.get("z") is not None else int(me["z"])
    except (TypeError, ValueError):
        return "the corner coordinates have to be whole numbers"
    if oy is None:
        oy = _ground_at(ox, oz, me["y"])
        if oy is None:
            return (f"I find no firm ground at {ox} {oz} to rest the blueprint "
                    "on; give me the height yourself, or pick another spot")

    # The WHOLE blueprint, with what is needed to put it up, in a single
    # request to the body. The fill worker counts the material, clears,
    # builds layer by layer and reviews each layer before going up; and it
    # answers at once, so the turn does not sit waiting. Before, this chained
    # 90 s passes from here and the brain died at 180 s with the house half done.
    blueprint_abs = {}
    tally = {}
    for (dx, dy, dz), symbol in cells.items():
        b = legend[symbol]
        blueprint_abs[(ox + dx, oy + dy, oz + dz)] = b
        tally[b] = tally.get(b, 0) + 1

    site = 0
    if a.get("flatten", True):
        xs = [c[0] for c in cells] + [0]
        zs = [c[2] for c in cells] + [0]
        ys = [c[1] for c in cells] + [0]
        # The site: the box of the blueprint plus one cell of air above,
        # empty except where the blueprint places something. A tree or a
        # slope inside the house was what left cells that could not be
        # reached.
        for dx in range(min(xs), max(xs) + 1):
            for dz in range(min(zs), max(zs) + 1):
                for dy in range(min(ys), max(ys) + 2):
                    c = (ox + dx, oy + dy, oz + dz)
                    if c not in blueprint_abs:
                        blueprint_abs[c] = "air"
                        site += 1
        # Foundation: under every column of the bottom layer, if there is NO
        # firm ground, the most abundant material IN THAT LAYER (the house
        # floor's: cobblestone under a cobblestone floor, not planks because
        # the walls add up to more). Where there is already dirt or stone it is
        # left alone: spending material replacing good ground is not founding.
        down = {}
        for (dx, dy, dz), symbol in cells.items():
            if dy == min(ys):
                down[legend[symbol]] = down.get(legend[symbol], 0) + 1
        filler = max(down, key=down.get)
        for (dx, dy, dz) in cells:
            if dy != min(ys):
                continue
            x, y, z = ox + dx, oy - 1, oz + dz
            d = sv("/block", x=x, y=y, z=z)
            b = (d.get("block") or "") if d.get("ok") else ""
            if b in _NOT_GROUND or b.endswith(("_leaves", "_log", "_sapling")):
                blueprint_abs[(x, y, z)] = filler
                site += 1
    if len(blueprint_abs) > 4096:
        return (f"with the site it is {len(blueprint_abs)} cells and the body's cap "
                "is 4096: make it smaller or ask with flatten=false")

    order = sorted(blueprint_abs.items(), key=lambda kv: (kv[0][1], kv[0][0], kv[0][2]))
    d = bt_post("/blueprint", {"cells": ";".join(
        f"{x},{y},{z},{b}" for (x, y, z), b in order)})
    if not d.get("ok"):
        # Here arrives, among others, the refusal for material: "I am missing
        # material for that blueprint: 63 oak_planks (I carry 49)...". It is
        # passed as it is, which is what the brain needs to go and find it.
        return f"I cannot build the blueprint: {d.get('error', 'I could not')}"
    _pending_note("fill_job", "working")
    materials = ", ".join(f"{n} {b}" for b, n in
                          sorted(tally.items(), key=lambda kv: -kv[1]))
    return (f"Starting the blueprint: {len(cells)} blocks ({materials}) with the "
            f"corner at {ox} {oy} {oz}"
            + (f", plus {site} cells of site and foundation" if site else "")
            + ". I already counted the material and I carry it all. I carry on "
            "alone: I clear, build layer by layer and review each layer before "
            "going up. I notify you when done; the progress shows in `state` (fill_job).")


_SEEDS = {"wheat": "wheat_seeds", "wheat_seeds": "wheat_seeds", "trigo": "wheat_seeds",
          "carrot": "carrot", "carrots": "carrot", "zanahoria": "carrot", "zanahorias": "carrot",
          "potato": "potato", "potatoes": "potato", "patata": "potato", "papa": "potato", "papas": "potato",
          "beetroot": "beetroot_seeds", "beetroot_seeds": "beetroot_seeds", "remolacha": "beetroot_seeds"}


def t_farm_crops(a):
    """Sow a field: till with the hoe and plant, noting the spot."""
    seed = _SEEDS.get((a.get("what") or "wheat").strip().lower())
    if not seed:
        return ("I do not know how to sow that: wheat, carrot, potato or beetroot "
                "(or their ids: wheat_seeds, carrot, potato, beetroot_seeds)")
    try:
        width = int(a.get("width") or 5)
        length = int(a.get("length") or 5)
    except (TypeError, ValueError):
        return "width and length have to be numbers"
    me = bt("/state")
    if not me.get("ok"):
        return "I do not know where I am"
    try:
        x = int(a["x"]) if a.get("x") is not None else int(me["x"]) + 1
        z = int(a["z"]) if a.get("z") is not None else int(me["z"]) + 1
    except (TypeError, ValueError):
        return "the coordinates have to be whole numbers"
    if a.get("y") is not None:
        y = int(a["y"])
    else:
        ground = _ground_at(x, z, me["y"])
        if ground is None:
            return f"I find no ground at {x} {z}"
        y = ground - 1          # the dirt tile, not the air above
    d = bt("/farm", x=x, y=y, z=z, width=width, length=length, seed=seed)
    if not d.get("ok"):
        return f"I cannot sow: {d.get('error', 'I could not')}"
    _pending_note("farm", "working")
    return (f"Starting the {width}x{length} field of {seed} with the corner at "
            f"{x} {y} {z}: I till and sow tile by tile. I am on it; I notify "
            "you when done. The spot stays noted as a 'farm' place.")


def t_collect_water(a):
    """Fill buckets at an infinite source (2x2 pool)."""
    try:
        radius = int(a.get("radius") or 32)
    except (TypeError, ValueError):
        return "the radius has to be a number"
    d = bt("/water", radius=radius)
    if not d.get("ok"):
        return f"I cannot go for water: {d.get('error', 'I could not')}"
    _pending_note("farm", "working")
    return ("Going to fill the buckets at the nearest infinite source. I am on "
            "it; I notify you when done.")


def t_gather_seeds(a):
    """Cut grass until carrying N wheat seeds (body, not brain)."""
    try:
        how_many = int(a.get("how_many") or 16)
        radius = int(a.get("radius") or 16)
    except (TypeError, ValueError):
        return "how_many and radius have to be numbers"
    d = bt("/seeds", how_many=how_many, radius=radius)
    if not d.get("ok"):
        return f"I cannot cut grass: {d.get('error', 'I could not')}"
    _pending_note("farm", "working")
    return (f"Cutting grass around until I carry {how_many} more seeds (grass "
            "drops one every eight or so). I am on it; I notify you when done.")


def t_gather(a):
    """Break blocks around and pick up what they drop (generic)."""
    blocks = [b.strip().lower().replace("minecraft:", "")
              for b in str(a.get("block") or "").replace(";", ",").split(",") if b.strip()]
    if not blocks:
        return "tell me which block: gather(block='oak_log', item='oak_log', count=32)"
    try:
        count = int(a.get("count") or 16)
        radius = int(a.get("radius") or 16)
    except (TypeError, ValueError):
        return "count and radius have to be numbers"
    d = bt("/gather", blocks=",".join(blocks), item=(a.get("item") or "").strip().lower(),
           count=count, radius=radius)
    if not d.get("ok"):
        return f"I cannot gather: {d.get('error', 'I could not')}"
    _pending_note("farm", "working")
    what = (a.get("item") or "").strip() or "blocks"
    return (f"Gathering {'/'.join(blocks)} around until I carry {count} more {what}: "
            "I break, step on the tile and go on. I am on it; I notify you when done.")


def t_allow_farm(a):
    """Note someone else's field as allowed: I harvest and resow it alone."""
    try:
        c = {k: int(a[k]) for k in ("x1", "y1", "z1", "x2", "y2", "z2")}
    except (KeyError, TypeError, ValueError):
        return "give me the two corners: x1 y1 z1 and x2 y2 z2"
    seed = _SEEDS.get((a.get("what") or "wheat").strip().lower(), "wheat_seeds")
    d = bt("/farm", allow="1", seed=seed, **c)
    if not d.get("ok"):
        return f"I could not note it: {d.get('error', 'I could not')}"
    return (f"Noted: the field from {c['x1']} {c['y1']} {c['z1']} to {c['x2']} {c['y2']} "
            f"{c['z2']} is one of those I harvest and resow alone when it ripens.")


def t_harvest(a):
    """Harvest what is ripe around and resow it."""
    try:
        radius = int(a.get("radius") or 12)
    except (TypeError, ValueError):
        return "the radius has to be a number"
    d = bt("/harvest", radius=radius, mine="1" if a.get("only_mine") else "0")
    if not d.get("ok"):
        return f"I cannot harvest: {d.get('error', 'I could not')}"
    _pending_note("farm", "working")
    return ("Harvesting what is ripe around and sowing every gap again. I am on "
            "it; I notify you when done.")


def t_fill(a):
    gap = str(a.get("gap", "")).strip().lower() in ("1", "true", "si", "sí", "yes")
    r = bt("/fill", block=a.get("block", "air"), gap="1" if gap else "0")
    if not r.get("ok"):
        return f"I cannot fill: {r.get('error')}"
    # It used to wait up to 180 s in here, more than the bridge timeout
    # (120 s): with a large area the turn died while the job carried on by
    # itself. Just enough is waited to catch an early failure and it is
    # reported as still going.
    return _wait_task("fill_job", "working", limit=20, cut=False)


COORD = {"x": ("number", "X coordinate."),
         "y": ("number", "Y coordinate (height)."),
         "z": ("number", "Z coordinate.")}

TOOLS = {
    # asking
    "state": (t_state, "How the server is and how I am: where, with how "
                       "much hp and what I hold in hand.", {}, []),
    "players": (t_players, "Who is connected and where.", {}, []),
    "break_permissions": (t_break_permissions,
                          "Which blocks I am allowed to break on my own (my "
                          "whitelist). Look at it before digging something "
                          "odd. READ ONLY: it is changed on the server with "
                          "/marionette bot <me> break allow|forbid <block>. "
                          "What I am TOLD to dig never needed permission.",
                          {}, []),
    "verbose": (t_verbose,
                f"Technical dump for debugging. ONLY for {OWNER_LABEL}: if "
                "anyone else asks, do not call it and tell them to have the "
                "owner ask (the tool checks it anyway). It writes to the chat "
                "the last tools I used, with their arguments and their answer "
                "or their error, plus where I am and how each task ended. The "
                "tool writes it DIRECTLY to the chat: do not repeat it or "
                "summarise it, just say it is posted.",
                {"how_many": ("integer", "How many calls to show (1-12); "
                                         "5 by default.")},
                []),
    "logbook": (t_logbook,
                "The last things I did, step by step. Look here when "
                "something goes odd: it says where I went through, not only "
                "how it ended.",
                {"how_many": ("integer", "How many notes (max 200).")}, []),
    "creatures_nearby": (t_creatures_nearby, "Which mobs are near me, with their hp.",
                         {"radius": ("integer", "Blocks around (max 48).")}, []),
    "objects_nearby": (t_objects_nearby,
                       "What lies ON THE GROUND nearby, with what it is, how "
                       "much and how many seconds it has been there. What you "
                       "break falls to the ground and does NOT enter the "
                       "inventory alone: look here after digging or chopping. "
                       "It vanishes after 5 minutes.",
                       {"radius": ("integer", "Blocks around (max 48).")}, []),
    "place": (t_place,
              "Leave ONE block at some coordinates: the crafting table, a "
              "chest, a torch. It has to be in the hotbar and within 4.5 "
              "blocks, and something solid is needed next to it to rest it on "
              "(the ground works). It checks with the server that it got "
              "placed. A chest I only place with AIR above: covered it does "
              "not open.",
              {"what": ("string", "Id of the object, in English (crafting_table)."),
               **COORD}, ["what", "x", "y", "z"]),
    "show_preferences": (t_show_preferences,
                         "My behaviour settings and their value (e.g. whether "
                         "I may build to reach someone while following). READ "
                         "ONLY: they are changed on the server with "
                         "/marionette bot <me> pref <key> on|off. If asked to "
                         "change one, say that and do not promise it.", {}, []),
    "escort": (t_escort,
               "Escort a player: go with them AND look after them; whatever "
               "hits them is my enemy until it dies or leaves, like a wolf "
               "(players only with defend_from_players). It differs from "
               "follow_player — following I only walk behind; escorting I hit "
               "whatever hostile approaches them, without waiting to be hit "
               "myself, and I warn them in the chat if there is a creeper near "
               "them (a creeper is not to be hit: getting close would join the "
               "explosion with both of us). Use it when company is asked for "
               "to move through dangerous places.",
               {"player": ("string", "Exact name of the player.")},
               ["player"]),
    "stop_escorting": (t_stop_escorting,
                       "Drop the escort and stay where I am.", {}, []),
    "say": (t_say,
            "Say a short sentence in the chat RIGHT NOW, without ending the "
            "turn. Use it BEFORE starting something that takes time (going "
            "somewhere, mining, building, exploring): whoever talks to you "
            "does not see your tools, only the chat, and if you keep quiet "
            "until you finish they think you did not hear. One sentence, "
            "under 200 characters, no slash.",
            {"text": ("string", "What you say, as it is.")}, ["text"]),
    "strip_mine_start": (t_strip_mine_start,
                         "Strip mine: a straight 1x2 tunnel towards a "
                         "direction, ENDLESS, with side branches every 3 "
                         "blocks, digging the ores that appear in the walls "
                         "(never the floor, never downwards). It is the only "
                         "way to look for ores: start it, say so, and end the "
                         "turn; it carries on alone until stop_mining or stop. "
                         "It stops alone at lava, water, a broken pickaxe or "
                         "without permission, and says so in 'strip_mine' of "
                         "the state.",
                         {"toward": ("string", "north, south, east or west; empty "
                                               "= the way I am facing."),
                          "branches": ("boolean", "With side branches (yes by "
                                                  "default).")}, []),
    "dig_down_to": (t_dig_down_to,
                    "Go down digging a zig-zag STAIRCASE (one block forward "
                    "and one down per step, runs of 4 alternating right and "
                    "left) to a height Y. It is the only good way down: never "
                    "a vertical shaft. It carries on alone and stops on "
                    "arriving; it also stops at lava or water, without a "
                    "pickaxe or without permission, and says so in 'staircase' "
                    "of the state. It is cut with stop. Useful heights: iron "
                    "~16, diamond ~-58. Every staircase I finish is SAVED with "
                    "its steps: if asked to go down while within 4 blocks of "
                    "the head of a saved one that reaches that Y, I WALK it "
                    "down without digging; and with 'until' ABOVE me, I CLIMB "
                    "the saved one whose foot I have within 4 blocks and, if "
                    "there is none, I DIG a staircase upwards (it gets saved). "
                    "Head and foot are in places ('staircase to Y=N: head' / "
                    "'foot').",
                    {"until": ("integer", "The Y to go down to."),
                     "toward": ("string", "north, south, east or west of the first "
                                          "run; empty = the way I am facing.")},
                    ["until"]),
    "stop_mining": (t_stop_mining,
                    "Cut the strip mine and stay where I am.", {}, []),
    "search": (t_search,
               "GO OUT TO LOOK FOR something specific and STOP on seeing it. "
               "It works for BLOCKS (iron_ore, sand, oak_log) and also for "
               "LIVING CREATURES (cow, sheep, pig, chicken, horse): the id in "
               "English, I look in both registries, and it accepts SEVERAL "
               "separated by commas, stopping at the first that appears: if "
               "asked for something generic ('animals to eat') do NOT pick a "
               "species, put the whole list. It is the tool for 'look for "
               "cows', 'I need iron' or 'see if there are horses': I keep "
               "looking while walking and stop where I see it, instead of "
               "taking a stroll and coming back empty-handed. USE THIS, not "
               "`explore`, whenever there is something specific to find; "
               "`explore` is only for wandering and seeing what there is. It "
               "takes minutes: start it, say so and end the turn, I notify "
               "you on finding it.",
               {"what": ("string", "Id in English of what I look for: a block "
                                   "(iron_ore) or a living creature (cow). SEVERAL "
                                   "ARE SEPARATED BY COMMAS and I stop at the "
                                   "first I see: for food, "
                                   "'cow,pig,chicken,sheep,rabbit'; for ore, "
                                   "'iron_ore,deepslate_iron_ore'. If one does "
                                   "not exist, I tell you and do not go out."),
                "radius": ("integer", "Blocks of the FIRST segment (20 to 2000; "
                                      "150 by default)."),
                "toward": ("string", "Direction to set out (north, south, east, "
                                     "west...); empty = I choose."),
                "branches": ("integer", "How many directions I try before "
                                        "giving up (1 to 16; 4 by default).")},
               ["what"]),
    "explore": (t_explore,
                "Go out to see what there is and COME BACK alone to the "
                "starting point. It is a round trip, takes minutes and is not "
                "waited for here: start it, say so and end the turn. What I "
                "see stays in my diary. Ask for it when asked or when I myself "
                "offered to go out for having had nothing to do for a while — "
                "never on my own without warning. If what is wanted is to GET "
                "something (cactus, sand, a specific tree), pass 'searching': "
                "I watch the surface on the way and stop where I see it. For "
                "something that grows scattered, better 'biome' (cactus -> "
                "desert): a biome spans hundreds of blocks and is recognised "
                "on stepping on it. With 'branches' I try several directions "
                "alone instead of giving up if there was nothing in the first.",
                {"radius": ("integer", "Blocks of the FIRST segment (20 to 2000; "
                                       "150 by default). The next ones grow on "
                                       "their own."),
                 "toward": ("string", "north, south, east or west; empty = the "
                                      "way I am facing. It is the FIRST "
                                      "direction if I ask for several branches."),
                 "searching": ("string", "Id in English of the block I look for "
                                         "on the way (cactus, sand, oak_log). "
                                         "Empty = no block sought."),
                 "biome": ("string", "Fragment of the biome name in English "
                                     "(desert, jungle, badlands). Empty = no "
                                     "biome sought."),
                 "branches": ("integer", "Segments of the spiral, 1 to 16. Each "
                                         "goes in another direction and farther "
                                         "than the previous, without returning "
                                         "home in between. Empty = 1 if I seek "
                                         "nothing, 4 if I seek something.")},
                []),
    "stop_exploring": (t_stop_exploring,
                       "Cut the exploration and stay where I am.",
                       {}, []),
    "diary": (t_diary,
              "My diary of THIS world: what is memorable, and it survives "
              "restarts (the logbook does not). Look at it when asked about "
              "other days, about what has happened to me, or before saying I "
              "remember nothing.",
              {"how_many": ("integer", "Last entries (20 by default).")},
              []),
    "write_in_diary": (t_write_in_diary,
                       "Keep something memorable in my diary. The memorable, "
                       "not the routine: if you would not tell it aloud a week "
                       "from now, it does not go here.",
                       {"text": ("string", "One line, in the first "
                                           "person.")}, ["text"]),
    "what_i_know_about": (t_what_i_know_about,
                          "What a person has done with me in this world: tallies "
                          "I keep on my own (hits they gave me, times they killed "
                          "me, escorts) and notes I wrote. LOOK AT IT before "
                          "having an opinion about someone: opining from memory "
                          "is making it up.",
                          {"who": ("string", "Name; empty = everyone.")}, []),
    "note_about_someone": (t_note_about_someone,
                           "Note something about a person to remember it: that "
                           "they gave me something, got me out of a fix, left "
                           "me stranded. Concrete facts, not labels.",
                           {"who": ("string", "Exact name."),
                            "note": ("string", "What they did, in one line.")},
                           ["who", "note"]),
    "internal": (t_internal,
                 "Write to ANOTHER BOT through the private channel between "
                 "bots: it does not go through the chat or the server, nobody "
                 "else sees it and it only wakes that bot. To ask your boss for "
                 "things (food, material, help) or to give your guard "
                 "instructions. What reaches you through the internal channel "
                 "comes marked as such: answer through the internal channel, "
                 "not the chat.",
                 {"bot": ("string", "Name of the other bot."),
                  "text": ("string", "What you tell it.")},
                 ["bot", "text"]),
    "toss": (t_toss,
             "Drop things on the ground. Without 'count' I toss ALL I carry "
             "of that item. Useful to make room with a full backpack and to "
             "hand something to someone. I say how many really went out, not "
             "how many were asked. What I wear is not tossed this way: that is "
             "removing the armor. And tossed things vanish after 5 minutes. "
             "What I toss comes back to my backpack if I step on the pile: "
             "'i_have_left' is of that instant, not a promise.",
             {"what": ("string", "Id of the object in English (cobblestone)."),
              "count": ("string", "How many, or 'everything' (by default)."),
              "to": ("string", "If it is for someone (person or bot): their exact "
                               "name. I turn towards them before dropping it.")},
             ["what"]),
    "trash": (t_trash,
              "My TRASH list: what I toss ALONE when my backpack fills up (of "
              "what is useful for building I keep one stack). Without "
              "arguments it shows it; 'toss the stone when you fill up' -> "
              "add=cobblestone; 'gravel is no longer trash' -> remove=gravel.",
              {"add": ("string", "Id in English to add to the trash."),
               "remove": ("string", "Id in English to take out of the trash.")}, []),
    "food_ban": (t_food_ban,
                 "The food I do NOT eat on my own, so I do not snack on what I "
                 "am fishing or keeping. The ban only covers what I choose: if "
                 "asked to eat that by name, I eat it. READ ONLY: it is "
                 "changed on the server with /marionette bot <me> food "
                 "ban|allow <item>.", {}, []),
    "remind_me": (t_remind_me,
                  "Leave myself a reminder for a while from now. My turn ends "
                  "when I answer, so 'going to the furnace' comes to nothing "
                  "if I do not do it NOW: if something does not fit in this "
                  "turn or has to be checked later (the furnace that takes "
                  "time, the long job), note it here and carry on when I am "
                  "woken up. Never use it for what you can do right now.",
                  {"at": ("integer", "In how many seconds (15 to 1800)."),
                   "what": ("string", "What I have to do or check, in the first "
                                      "person.")},
                  ["what"]),
    "who_commands": (t_who_commands,
                     "Who owns me, who are my admins and who I listen to, and "
                     "the server commands that shut me down, restart me, log "
                     "me off or change those lists. None of that can be done "
                     "through the chat. Consult it before answering anyone "
                     "who asks for it.",
                     {}, []),
    "light": (t_light,
              "How much light there is where I am, or at some coordinates. "
              "What decides is BLOCK light: since 1.18 monsters spawn ONLY "
              "where it is 0, so lighting well is placing a torch every ten or "
              "twelve blocks, not every three. While digging I already place "
              "them alone where the light is 0; outside digging, if asked for "
              "light somewhere, use `place` with torch.",
              {"x": ("integer", "x; without coordinates, where I am."),
               "y": ("integer", "y."), "z": ("integer", "z.")}, []),
    "places": (t_places,
               "My memory of useful places on THIS server (tables, furnaces, "
               "beds, chests and the named points), from nearest to farthest. "
               "Every memory carries its DIMENSION, and those of another "
               "dimension come last and without distance (between dimensions "
               "distance means nothing). Consult it BEFORE crafting a new "
               "table or furnace, when asked to sleep with no bed in sight, "
               "and WHENEVER sent to a place by its name ('go to the factory') "
               "instead of by coordinates: search with name and take the x y z "
               "from there.",
               {"type": ("string", "table, furnace, bed, chest, point, portal "
                                   "or death; empty = all."),
                "name": ("string", "Search by the name of the spot ('the "
                                   "factory'). Empty = no filter.")}, []),
    "remember_place": (t_remember_place,
                       "Note in my memory a useful place a player told me "
                       "about or that I found. Those I place or use get noted "
                       "alone. The label is the name it was given to me with "
                       "('blocks chest') to find it later by that name. The "
                       "type 'point' is for any spot someone names ('the "
                       "factory', 'the portal') and REQUIRES a label; if no "
                       "name was given, ask for it before noting it.",
                       {"type": ("string", "table, furnace, bed, chest, point "
                                           "or portal."),
                        "x": ("integer", "x."), "y": ("integer", "y."),
                        "z": ("integer", "z."),
                        "label": ("string", "Name of the place. Optional "
                                            "except for 'point', which "
                                            "requires it.")},
                       ["type", "x", "y", "z"]),
    "forget_place": (t_forget_place,
                     "Delete a place from my memory. Use it when I arrive and "
                     "the block is gone.",
                     {"x": ("integer", "x."), "y": ("integer", "y."),
                      "z": ("integer", "z.")},
                     ["x", "y", "z"]),
    "look_in_chest": (t_look_in_chest,
                      "What is inside a chest or barrel, grouped by item. I "
                      "have to be within 4 blocks. What I see stays noted for "
                      "search_chests.",
                      {"x": ("integer", "x of the chest."),
                       "y": ("integer", "y."), "z": ("integer", "z.")},
                      ["x", "y", "z"]),
    "put_in_chest": (t_put_in_chest,
                     "Store things in a chest. Without 'count' I store ALL I "
                     "carry of that item; with a number, that exact amount.",
                     {"x": ("integer", "x."), "y": ("integer", "y."),
                      "z": ("integer", "z."),
                      "what": ("string", "Id in English of the item."),
                      "count": ("integer", "Exact amount; empty = "
                                           "everything.")},
                     ["x", "y", "z", "what"]),
    "take_from_chest": (t_take_from_chest,
                        "Take things from a chest. Without 'count' I take ALL "
                        "there is of that item; with a number, that exact "
                        "amount. I say how many really came out, not how many "
                        "were asked. From a chest that is NOT mine I do not take "
                        "without permission: if a player gives it to you or "
                        "sends you to that chest, it goes in with_permission_of "
                        "and gets noted.",
                        {"x": ("integer", "x."), "y": ("integer", "y."),
                         "z": ("integer", "z."),
                         "what": ("string", "Id in English of the item."),
                         "count": ("integer", "Exact amount; empty = "
                                              "everything."),
                         "with_permission_of": ("string", "Player who JUST gave "
                                                          "you permission or sent "
                                                          "you to that chest. Only "
                                                          "if they really said so "
                                                          "in the chat; if the "
                                                          "chest is mine or I "
                                                          "already have permission, "
                                                          "it is not needed.")},
                        ["x", "y", "z", "what"]),
    "search_chests": (t_search_chests,
                      "In which CHESTS I REMEMBER there is an item (what I saw "
                      "on opening them), from nearest to farthest: how many, "
                      "whether I may take from it (mine / with permission from "
                      "X / no known permission / forbidden) and how long ago I "
                      "saw it. Without 'what', it sums up every chest. Look at "
                      "it BEFORE going out to find a material in the world: a "
                      "chest with permission is closer than a tree. It is "
                      "memory, not sight: it may have changed.",
                      {"what": ("string", "Id in English (oak_log) or family "
                                          "(wood, food); empty = every chest.")}, []),
    "annotate_chest": (t_annotate_chest,
                       "Note whether I may take from a chest: own (I placed it "
                       "or I am told it is mine), yes (someone lets me use it) "
                       "or no (I am forbidden). ONLY when a player really says "
                       "so in the chat, never by assuming it. What is inside "
                       "gets noted alone every time I open the chest.",
                       {"x": ("integer", "x."), "y": ("integer", "y."),
                        "z": ("integer", "z."),
                        "permission": ("string", "own, yes or no."),
                        "of": ("string", "Who said it. Required except for "
                                         "own.")},
                       ["x", "y", "z", "permission"]),
    "look_in_furnace": (t_look_in_furnace,
                        "What is inside a furnace (input, fuel, output). I "
                        "open it and really look; I have to be within 4 blocks.",
                        {"x": ("integer", "x of the furnace."),
                         "y": ("integer", "y."), "z": ("integer", "z.")},
                        ["x", "y", "z"]),
    "smelt": (t_smelt,
              "Load a furnace to smelt/cook: it puts in ALL my stacks of the "
              "item (the menu routes them alone) and the fuel I say (coal, "
              "charcoal, oak_planks...). If the output (or the input, with "
              "something else) is busy, I take it out before loading and "
              "tell you what I took. The furnace takes ~10 s per item to "
              "cook: do not wait, come back later and take out with "
              "take_from_furnace. I have to be within 4 blocks of the furnace.",
              {"x": ("integer", "x of the furnace."),
               "y": ("integer", "y."), "z": ("integer", "z."),
               "what": ("string", "Id in English of what I want to smelt."),
               "fuel": ("string", "Id of the fuel; optional if the furnace "
                                  "already has some.")},
              ["x", "y", "z", "what"]),
    "take_from_furnace": (t_take_from_furnace,
                          "Take things out of a furnace. Without 'what' it "
                          "takes what is already cooked. With what='entry' it "
                          "recovers the RAW material still cooking: that is "
                          "how a load is undone when someone regrets what "
                          "they put in.",
                          {"x": ("integer", "x of the furnace."),
                           "y": ("integer", "y."), "z": ("integer", "z."),
                           "what": ("string", "Empty or 'output' = the cooked; "
                                              "'entry' = the raw, uncooked; "
                                              "'fuel'; 'everything' = the three "
                                              "slots.")},
                          ["x", "y", "z"]),
    "orders": (t_orders,
               "My standing orders by category (cooking, hunting, gear, "
               "building, travel, general): rules players dictated to me "
               "once and that hold forever. CONSULT the category of the task "
               "BEFORE doing it.",
               {"category": ("string", "One category, or empty = all.")},
               []),
    "add_order": (t_add_order,
                  f"Keep a standing order {OWNER_LABEL} just gave me ('if you "
                  "run out of X, take it from Y', 'prefer Z'). If it "
                  "contradicts an existing one, delete that one first. Only "
                  f"orders from {OWNER_LABEL}; the 'idle' one (what I do when I "
                  "have had nothing to do for a while) also from my favorite, "
                  "and only one fits: noting another replaces it.",
                  {"category": ("string", "cooking, hunting, gear, building, "
                                          "travel, general or idle."),
                   "text": ("string", "The rule, short and clear.")},
                  ["category", "text"]),
    "delete_order": (t_delete_order,
                     "Delete a standing order by its category and number "
                     "(the number you listed it with).",
                     {"category": ("string", "Its category."),
                      "number": ("integer", "Its number in the listing.")},
                     ["category", "number"]),
    "hunt": (t_hunt,
             "Chase and kill mobs of one type (cow, pig, zombie, spider...). "
             "It carries on alone until hunting them, losing them or getting "
             "badly hurt; the progress stays in the state. What they drop "
             "falls to the ground: pick it up afterwards. If I see none, I ask "
             "the server and go, or I go out looking for them up to 300 "
             "blocks. NEVER players.",
             {"type": ("string", "Id in English of the mob (cow), SEVERAL "
                                 "separated by commas (cow,pig,chicken,sheep: "
                                 "hunts the nearest of any), the exact name of "
                                 "a player, or the NAME TAG of a mob ('hunt the "
                                 "cow Lola' -> Lola)."),
              "count": ("integer", "How many (1-8), or 0 = NO LIMIT until told "
                                   "to stop. WITHOUT A NUMBER from the person: "
                                   "8, the maximum; do not make up a smaller one."),
              "toward": ("string", "If I see no prey, which way to go out looking "
                                   "for it: north, south, east or west. If "
                                   "missing, the way I am facing.")},
             ["type"]),
    "shear": (t_shear,
              "Shear sheep: like hunting, but with shears and without "
              "killing. I look for adult sheep WITH wool, shear them and pick "
              "up the wool; it carries on alone and the progress stays in the "
              "state (shear_job). I need shears on me (2 iron_ingot with "
              "`craft_item shears`). If I see none, I ask the server and go, "
              "or I go out looking for them up to 300 blocks.",
              {"count": ("integer", "How many (1-8), or 0 = all I see with wool "
                                    "(until told to stop or none is left). If "
                                    "missing, 0."),
               "toward": ("string", "If I see no sheep, which way to go out "
                                    "looking: north, south, east or west. If "
                                    "missing, the way I am facing.")},
              []),
    "tame": (t_tame,
             "Tame animals: wolves (wolf) with bones (bone), cats (cat) with "
             "cod or salmon, parrots (parrot) with seeds. I need the food on "
             "me. A tamed wolf follows me and fights for me. It carries on "
             "alone; the progress stays in the state (breeding). Horses, "
             "donkeys and llamas not yet.",
             {"type": ("string", "Id in English: wolf, cat or parrot."),
              "count": ("integer", "How many to tame (1-8). If missing, 1.")},
             ["type"]),
    "breed": (t_breed,
              "Breed animals: I give their food to TWO adults of the type and "
              "wait to see the baby born (wheat: cow, sheep, mooshroom; "
              "carrot/potato/beetroot: pig; seeds: chicken; meat: TAMED wolf; "
              "cod/salmon: TAMED cat; golden carrot: tamed horse; carrot: "
              "rabbit). I need 2 units per pair and both adults within 10 "
              "blocks of each other. If told WHAT to feed them, pass it in "
              "food; if not, I choose and start with what poisons me (rotten "
              "flesh), which does nothing to them. When done I say which food "
              "I really spent. It carries on alone; progress in the state "
              "(breeding).",
              {"type": ("string", "Id in English of the mob (cow, sheep, "
                                  "pig, chicken, wolf, cat...)."),
               "pairs": ("integer", "How many pairs (1-8). If missing, 1."),
               "food": ("string", "Id in English of the item to feed them "
                                  "with (rotten_flesh, mutton, beef, "
                                  "wheat...). Only if asked; if missing, I "
                                  "choose.")},
              ["type"]),
    "pets": (t_pets,
             "Sit or stand MY already tamed pets (wolves, cats, parrots): I "
             "go to each one and touch it with an empty hand. Sitting it "
             "stays still where it is and does not follow me; standing it "
             "follows me and fights for me. No food needed. It carries on "
             "alone; the outcome stays in the state (breeding).",
             {"action": ("string", "sit or stand."),
              "type": ("string", "Optional: wolf, cat or parrot. If missing, "
                                 "all of mine I see."),
              "count": ("integer", "How many (1-8). If missing, all.")},
             ["action"]),
    "wait_for_item": (t_wait_for_item,
                      "Wait for someone to give me something: I watch the "
                      "inventory for up to 45 s and answer as soon as it comes "
                      "in. Use it when told 'I'll give you X' / 'here, take X' / "
                      "'come and I'll give you', already standing next to that "
                      "person. Without `what`, anything that comes in counts.",
                      {"what": ("string", "What I wait for: id in English "
                                          "(stick, oak_log) or a generic word "
                                          "(wood, stone, food, pickaxe...). If "
                                          "something else arrives it does NOT "
                                          "count: I keep waiting for what was "
                                          "asked. Put it WHENEVER told that "
                                          "something specific will be given."),
                       "seconds": ("integer", "How long to wait (5-45). If "
                                              "missing, 45.")},
                      []),
    "kill": (t_kill,
             "Kill WITH ARROWS from the bow. WHENEVER told 'kill X' it is "
             "this one, not `hunt` — the rule: the bow is for killing, the "
             "sword for hunting. Without a bow or arrows I do NOT refuse: I "
             "go by sword alone and do not comment. I see every target the "
             "server shows me, shoot between 10 and 25 blocks, under 10 I "
             "charge with the sword, and if a target is under cover (arrows "
             "doing it no harm) I go for it in melee. I do NOT pick up what "
             "drops. Players only with hunt_players true.",
             {"type": ("string", "Id in English of the mob (creeper), the "
                                 "exact name of a player, or the NAME TAG of a "
                                 "mob ('kill the villager Cherie' -> Cherie)."),
              "count": ("integer", "How many (1-8), or 0 = ALL I see (no "
                                   "limit, until told to stop or none is "
                                   "left). If missing, 1.")},
             ["type"]),
    "fish": (t_fish,
             "Fish in the nearby water. I need a fishing rod on me and water "
             "within 8 blocks (if not, have me taken to the shore). I fish "
             "FROM DRY LAND: never put me into the water to fish; if I report "
             "that the hook lands on dry ground, move along the shore to a "
             "spot flush with the water and call me again. I fish alone until "
             "told to stop or my rod breaks; the bites keep counting in the "
             "state (fishing_job).",
             {}, []),
    "follow_player": (t_follow_player,
                      "Follow a player: walk behind them, staying close, until "
                      "told to stop. It stops the job or the digging in "
                      "progress. Following gives NO permission to build or "
                      "break anything.",
                      {"player": ("string", "Exact name of the player.")},
                      ["player"]),
    "stop_following": (t_stop_following, "Stop following the player.",
                       {}, []),
    "mount": (t_mount,
              "Mount a horse. With 'name' I look for the one carrying that "
              "name tag (exact name); without a name, the nearest tamed one "
              "within 32 blocks. If it is untamed I tame it by mounting it "
              "(it throws me several times). It needs a saddle to be steered: "
              "if I carry one I put it on. It takes a few seconds and I "
              "NOTIFY when done. Mounted, `go_to` and long trips go on "
              "horseback, much faster; but the horse does not fit through "
              "one-block gaps nor builds: if I get stuck, `dismount` and carry "
              "on on foot.",
              {"name": ("string", "The horse's name tag, exact. Empty "
                                  "= the nearest tamed one.")}, []),
    "dismount": (t_dismount,
                 "Get off the horse where I am.", {}, []),
    "my_horse": (t_my_horse,
                 "WHERE MY HORSE IS. I keep the last one I mounted and ask "
                 "the SERVER, which knows where every creature of every world "
                 "is even if I am a thousand blocks away and cannot see it. "
                 "Use it when asked about the horse or when I want it back: "
                 "loose horses walk off on their own. If it no longer exists, "
                 "I tell you it died. And so that it does not happen again, "
                 "the right thing is to leave it tied to a fence "
                 "(`tie_animal` + `tether_to_post`).", {}, []),
    "tie_animal": (t_tie_animal,
                   "Tie an animal with a LEAD to take it along: by its exact "
                   "name tag, by type in English (cow, sheep, chicken, pig, "
                   "horse...) or empty = the nearest loose animal within 32. "
                   "I need leads: 4 string + 1 slime_ball = 2 lead. I get "
                   "close, tie it and NOTIFY. With 'count' I tie several in a row.",
                   {"what": ("string", "Name tag, type, or empty."),
                    "count": ("integer", "How many to tie (1 by default).")}, []),
    "lead_animals": (t_lead_animals,
                     "Lead the animals I have tied to some coordinates, AT "
                     "THEIR PACE: I do not run and I stop to wait for the "
                     "straggler (the lead breaks at 10 blocks). NEVER use "
                     "go_to with tied animals. I notify on arriving.",
                     COORD, ["x", "y", "z"]),
    "tether_to_post": (t_tether_to_post,
                       "Tether the animals I have tied to a FENCE (a post: "
                       "oak_fence and the like; if there is none, place one "
                       "with `place`). I go to it, use the fence with an empty "
                       "hand and they stay tied to the post. I notify when done.",
                       COORD, ["x", "y", "z"]),
    "release_animals": (t_release_animals,
                        "Release from the lead an animal tied to me (name tag "
                        "or type) or all of them ('all' or empty). The lead "
                        "falls to the ground next to the animal: afterwards "
                        "`pick_up lead`.",
                        {"what": ("string", "Name tag, type, or 'all'.")}, []),
    "wield": (t_wield,
              "Put something in my hand. By its id in English (stone_pickaxe) "
              "or by hotbar slot, 1 to 9 AS SEEN ON SCREEN — careful, "
              "`inventory` returns them 0 to 8. Asked by id I also take it "
              "FROM THE BACKPACK and bring it to the hotbar; it is the only way "
              "to start using a freshly crafted pickaxe, because on my own I "
              "only look at the 9 hotbar slots.",
              {"what": ("string", "Id of the object, in English."),
               "slot": ("integer", "Hotbar slot, 1 to 9.")},
              []),
    "equip_armor": (t_equip_armor,
                    "Put on armor from the backpack. Without arguments I dress "
                    "with the best I carry, piece by piece; with a specific "
                    "piece (iron_helmet) I put that one on even if worse. What "
                    "is worn shows in the state and in the inventory (worn:true).",
                    {"piece": ("string", "Id in English of the piece, or empty to "
                                         "wear the best.")},
                    []),
    "remove_armor": (t_remove_armor,
                     "Take off a worn armor piece and store it in the backpack.",
                     {"piece": ("string", "Id in English of the worn piece.")},
                     ["piece"]),
    "eat": (t_eat,
            "Eat something from the hotbar to fill the hunger. With the "
            "hunger full the health goes up alone, so it is what to do when I "
            "am low on health. Without saying what, I choose and leave rotten "
            "flesh for last, since it poisons. What is VETOED I do not eat on "
            "my own nor by asking for it by name: only if a person asked for "
            "it, and then with who=<their name>.",
            {"what": ("string", "Id of the food, in English (cooked_beef)."),
             "who": ("string", "Only for banned food: exact name of the "
                               "person who asked me to eat it.")},
            []),
    "sleep": (t_sleep,
              "Go to sleep in the nearest bed; if it is far I go alone. It is "
              "what gets rid of phantoms: they come out from going three days "
              "without sleeping, and killing them fixes nothing. It only "
              "works at night or in a storm, and not with monsters nearby. "
              "Careful: sleeping skips the night for the WHOLE server, so "
              "only if asked.",
              {"radius": ("integer", "Blocks around to look for a bed.")},
              []),
    "set_spawn": (t_set_spawn,
                  "Click a bed by DAY to leave my respawn point there, "
                  "without sleeping and without skipping the night for "
                  "anyone. If it is far I go alone. Not at night: at that "
                  "hour the same click puts me to bed, and that is 'sleep'. "
                  "It is what to do when moving base or before something "
                  "dangerous, so as not to respawn at the world spawn.",
                  {"radius": ("integer",
                              "Blocks around to look for a bed.")},
                  []),
    "pick_up": (t_pick_up,
                "Go to something lying on the ground and pick it up. Without "
                "'what', it takes the nearest. It checks the inventory before "
                "and after, so it says whether it really took it.",
                {"what": ("string", "Id of the object in English (oak_log). "
                                    "Empty for the nearest."),
                 "radius": ("integer", "Blocks around (max 48).")}, []),
    "what_is_at": (t_what_is_at, "Which block is at some coordinates.",
                   COORD, ["x", "y", "z"]),
    "search_block": (t_search_block,
                     "The nearest block of a type. The id goes in ENGLISH: "
                     "oak_log, stone, coal_ore. Without coordinates, it "
                     "searches from where I am.",
                     {"block": ("string", "Id of the block, in English."),
                      **COORD,
                      "radius": ("integer", "Blocks around (max 48).")},
                     ["block"]),
    "inventory": (t_inventory,
                  "What I carry. For each tool it says `uses_left` and "
                  "`uses_total`, and `in_hotbar` whether it is in the 9 hand "
                  "slots or stored in the backpack.",
                  {}, []),
    "show_recipe": (t_show_recipe,
                    "How something is crafted and whether it needs a table. "
                    "Also modded items (cogwheel or create:cogwheel); if "
                    "something comes out of a machine and not of the table, "
                    "it says so.",
                    {"object": ("string", "Id in English, such as stone_pickaxe "
                                          "or create:cogwheel.")},
                    ["object"]),
    # acting
    "go_to": (t_go_to,
              "Walk to some coordinates going around whatever is needed. It "
              "waits to arrive before answering. With a 'dimension' other "
              "than where I am, the trip is by PORTAL: I go to the nearest "
              "noted portal, wait inside until it takes me across and carry "
              "on on the other side — that takes minutes and is NOT waited "
              "for here, it is checked later in the state (travel). I need a "
              "noted portal in the dimension I am in; if I have none, I will "
              "say so.",
              {**COORD,
               "build": ("boolean", "By default YES, I may place blocks "
                                    "(bridges and towers) when there is no "
                                    "other way. false = trip without "
                                    "touching the world."),
               "dimension": ("string", "overworld, the_nether or the_end. "
                                       "Empty = where I already am."),
               "even_if_escorting": ("boolean", "Escorting, go more than 24 blocks "
                                                "from the escorted one WITHOUT "
                                                "dropping the escort (a bed, a "
                                                "chest, an errand of my boss). "
                                                "Without this I refuse to move "
                                                "away from them.")},
              ["x", "y", "z"]),
    "stop": (t_stop, "Stop right now whatever I am doing.", {}, []),
    "attack": (t_attack,
               "Hit the nearest hostile within reach, wielding whatever does "
               "the most damage and waiting for the hit to be charged.",
               {"times": ("integer", "How many hits to give (3 by default).")},
               []),
    "dig": (t_dig, "Break the block at some coordinates. It has to be "
                   "within reach; if not, go_to first.", COORD,
            ["x", "y", "z"]),
    "respawn": (t_respawn, "Respawn after dying.", {}, []),
    "craft_item": (t_craft_item,
                   "Craft something at the table (also modded table recipes). "
                   "3x3 recipes need a crafting table within 2 blocks. What "
                   "comes out of machines (press, mixer...) NO: that is for "
                   "the players.",
                   {"object": ("string", "Id in English."),
                    "times": ("integer", "How many times.")}, ["object"]),
    "mark_corner": (t_mark_corner,
                    "Mark a corner of the area to fill (Minecraft's `fill`). "
                    "Without coordinates, it marks where I am. THIS is what "
                    "is asked with 'block 1', 'block 2', 'corner 1', 'point "
                    "1' or 'mark here': they are the same thing said in "
                    "different ways, and the number is the corner. Both are "
                    "marked and then `fill` is used.",
                    {"corner": ("integer", "1 or 2."), **COORD}, ["corner"]),
    "build_blueprint": (t_build_blueprint,
                        "BUILD A HOUSE (or whatever) BY DRAWING IT, without "
                        "computing coordinates. A `blueprint` is sent: one "
                        "layer per height, each opened with a line `Y=0`, "
                        "`Y=1`... and its rows of symbols below. Within each "
                        "layer the first row is the smallest Z and the first "
                        "character of each row the smallest X; the dot and the "
                        "space are air. And a `legend` such as "
                        "'#=cobblestone, D=oak_door, V=glass'. I compute the "
                        "coordinates, place BOTTOM UP (so no gaps are left for "
                        "lack of support) and group by material. YOU DESIGN: "
                        "walls, door, windows, roof. Example of three layers:\n"
                        "Y=0\n#####\n#...#\n##D##\n"
                        "Y=1\n#####\n#...#\n#####\n"
                        "Y=2\n#####\n#####\n#####\n"
                        "BEFORE starting I count the material against my "
                        "inventory: if some is missing, I REFUSE and say how "
                        "much of what; get it (chests, crafting, digging) and "
                        "ask me again. I leave no house half done. If I "
                        "accept, I answer at once and carry on alone: I clear "
                        "the site, build layer by layer and REVIEW each layer "
                        "before going up. I notify you when done. Say so and "
                        "end the turn.",
                        {"blueprint": ("string", "The layers, with line breaks."),
                         "legend": ("string", "symbol=block separated by "
                                              "commas."),
                         "x": ("integer", "Corner of layer Y=0 (smallest X); "
                                          "empty = where I am."),
                         "y": ("integer", "Height of layer Y=0; empty = my "
                                          "feet."),
                         "z": ("integer", "Corner of layer Y=0 (smallest Z); "
                                          "empty = where I am."),
                         "flatten": ("boolean", "Clear the site and found it "
                                                "before building (YES by "
                                                "default: without it, on a "
                                                "slope half the blueprint is "
                                                "left unplaced).")},
                        ["blueprint", "legend"]),
    "farm_crops": (t_farm_crops,
                   "SOW A FIELD from scratch: I till with the hoe and plant, "
                   "tile by tile, and note the spot as a 'farm' place. I need "
                   "a hoe (2 sticks + 2 planks/cobblestone/iron, I craft it "
                   "with `craft_item`) and seeds: wheat (wheat_seeds, from "
                   "breaking grass), carrot, potato or beetroot. First I "
                   "FLATTEN the site (dirt in the holes, obstacles out; if I "
                   "am short of dirt I refuse and say how much). WATER: I "
                   "place it MYSELF, a pool with a bucket in the centre of "
                   "every 9x9 (one water block hydrates 4 on each side); if I "
                   "do not carry enough water buckets I refuse and tell you "
                   "how many are needed (collect_water fills them). I carry on "
                   "alone and notify. Without x y z I do it right here.",
                   {"what": ("string", "wheat, carrot, potato or beetroot."),
                    "width": ("integer", "Tiles in X (5 by default, max 20)."),
                    "length": ("integer", "Tiles in Z (5 by default, max 20)."),
                    "x": ("integer", "Corner (smallest X); empty = next to me."),
                    "y": ("integer", "Height of the DIRT; empty = the ground here."),
                    "z": ("integer", "Corner (smallest Z); empty = next to me.")},
                   []),
    "collect_water": (t_collect_water,
                      "FILL WATER BUCKETS at an infinite source (a 2x2 pool "
                      "or larger). I need empty buckets (3 iron ingots each). "
                      "One water bucket is needed for every 9x9 of field "
                      "without water next to it. I carry on alone and notify.",
                      {"radius": ("integer", "Blocks around (32 by default, max 48).")},
                      []),
    "gather": (t_gather,
               "BREAK BLOCKS AROUND AND PICK UP WHAT THEY DROP, until "
               "carrying N of an item: logs for wood, grass for seeds, "
               "flowers for dye, sand, leaves... I go round every one of that "
               "type I see, with my tool and my permissions, and step on each "
               "tile to pick it up. A single call: NEVER search_block + dig + "
               "pick_up one by one. ORES do not go through here (it would be "
               "looking through rock): for those dig or strip_mine_start. "
               "And ONLY blocks IN SIGHT (with one face to the air): stone "
               "buried under the grass does not count; for cobblestone with "
               "no rock in sight, dig downwards or dig_down_to.",
               {"block": ("string", "Id of the block in English (or several separated by commas)."),
                "item": ("string", "The item I count (by default, I count broken blocks)."),
                "count": ("integer", "How many more I want (16 by default)."),
                "radius": ("integer", "Blocks around (16 by default, max 24).")},
               ["block"]),
    "gather_seeds": (t_gather_seeds,
                     "GET WHEAT SEEDS by cutting grass: I break plant by "
                     "plant around and pick up what drops, until carrying as "
                     "many as you ask. It is what to use for seeds: NEVER "
                     "search_block + dig + pick_up plant by plant. I carry on "
                     "alone and notify.",
                     {"how_many": ("integer", "Seeds I want on top (16 by default)."),
                      "radius": ("integer", "Blocks around (16 by default, max 24).")},
                     []),
    "allow_farm": (t_allow_farm,
                   "NOTE SOMEONE ELSE'S FIELD AS ALLOWED: from then on I "
                   "harvest and resow it ALONE when it ripens, as if it were "
                   "mine. Only when its owner tells me (\"you may farm the "
                   "field from x y z to x y z\"). With the two corners.",
                   {"x1": ("integer", "Corner 1."), "y1": ("integer", "Height of the dirt."),
                    "z1": ("integer", "Corner 1."), "x2": ("integer", "Corner 2."),
                    "y2": ("integer", "Height."), "z2": ("integer", "Corner 2."),
                    "what": ("string", "What is sown (wheat by default).")},
                   ["x1", "y1", "z1", "x2", "y2", "z2"]),
    "harvest": (t_harvest,
                "HARVEST what is ripe around (wheat, carrots, potatoes, "
                "beetroot) and sow every gap again so the field goes on. My "
                "fields I harvest ALONE when they ripen; this tool is for "
                "when asked or for other people's fields (with their owner's "
                "permission). I carry on alone and notify.",
                {"radius": ("integer", "Blocks around (12 by default, max 24)."),
                 "only_mine": ("boolean", "Only my fields.")},
                []),
    "fill": (t_fill,
             "Fill the marked area with a block, or empty it with 'air'. "
             "Inside the area I break EVERYTHING there is (the order is the "
             "permission; the whitelist only rules when making my way). Any "
             "block I carry in the hotbar works. With gap=true only the "
             "shell (walls, floor and roof) and the inside is left as it is: "
             "that is how a hollow structure goes up. It carries on alone: "
             "if it takes more than 20 s, it answers that it is still going "
             "and the progress is checked in the state (fill_job).",
             {"block": ("string", "Id in English, or 'air' to empty."),
              "gap": ("boolean", "Only the shell of the box; the inside is "
                                 "not touched.")},
             ["block"]),
}


def schema(fields, required):
    return {"type": "object",
            "properties": {k: {"type": t, "description": d}
                           for k, (t, d) in fields.items()},
            "required": required}


def respond(id_, result):
    sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": id_,
                                 "result": result}) + "\n")
    sys.stdout.flush()


def main():
    record(f"started — truth at {SERVER}, hands at {BOT}")
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue
        method, id_ = msg.get("method"), msg.get("id")
        if id_ is None:
            continue

        if method == "initialize":
            respond(id_, {
                "protocolVersion": msg.get("params", {})
                                      .get("protocolVersion", "2025-06-18"),
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "marionette-bot", "version": "1.0.0"},
            })
        elif method == "tools/list":
            respond(id_, {"tools": [
                {"name": n, "description": d, "inputSchema": schema(c, o)}
                for n, (_, d, c, o) in TOOLS.items()]})
        elif method == "tools/call":
            p = msg.get("params", {})
            name, args = p.get("name"), (p.get("arguments") or {})
            entry = TOOLS.get(name)
            if not entry:
                text, failed = f"There is no tool called '{name}'.", True
            else:
                try:
                    text, failed = entry[0](args), False
                except Exception as e:
                    text, failed = f"{name} failed: {e}", True
            record(f"{name}({args}) -> {str(text)[:70]}")
            log_call(name, args, text, failed)
            respond(id_, {"content": [{"type": "text", "text": str(text)}],
                          "isError": failed})
        else:
            respond(id_, {})


if __name__ == "__main__":
    main()

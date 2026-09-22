"""A bot's rules: its behaviour toggles, the food it does not eat on its own,
and the blocks it may break on its own.

They are enforced on the SERVER: the server mod keeps them for each bot and
its bridge makes the body hold what they come to. In three layers, weakest
first:

    base      the bot's config (bots/<bot>/bot.json, "rules"), with its
              server's on top (servers/<slug>/rules.json)
    own       the instance's. `marionette.py rules <instance> ...` edits it
              and so does /marionette bot in the game: it lives on the
              server, once, and this launcher sends it CHANGES, never a copy
              that would undo what was changed in the game. A change that
              cannot reach the server waits in the instance's folder
              (rules-pending.json) and goes on its next start.
    imposed   the global config (launcher.json, "rules") unless the instance
              ignores it (`set <instance> ignore_global yes`); groups will
              impose here too. Nothing in the game changes it: the command
              is refused and says who imposes it.

Each layer names only what it decides: a toggle, or an id put on a list or
taken off it. Where two layers name the same thing the stronger one wins;
otherwise lists add up. What no layer names keeps what every bot starts with.
A layer may also REPLACE a list: its entries are then the whole list, and
what is under it, what every bot starts with included, no longer counts.

The same rules are worked out by the server mod (mod/.../server/Rules.java);
both are held to the same cases (mod/src/test/resources/marionette/
rules-cases.json).

In a file, a layer looks like this, every part optional:

    {"prefs": {"hunt_players": true},
     "food":  {"ban": ["rotten_flesh"], "allow": ["golden_apple"]},
     "break": {"allow": ["oak_log"], "forbid": ["dirt"], "replace": false}}
"""
import json
import re
import urllib.error
import urllib.parse

from .api import UNREACHABLE
from .bots import read_json, write_json
from .events import Fail

# What every bot starts with. The server mod's table (common/Settings.java) is
# the one the game reads; this is a copy for checking and showing rules while
# the server is away, and a test holds the two together.
TOGGLES = {
    "break_to_advance": False,
    "build_while_following": True,
    "defend_from_players": False,
    "dress_alone": True,
    "harvest_alone": True,
    "hunt_players": False,
    "night_routine": True,
    "recover_on_death": True,
    "retreat_when_hurt": True,
    "shoot_creepers": True,
    "sleep_alone": True,
    "stand_when_done": True,
    "tame_wolves": True,
    "torches_while_mining": True,
}
FAMILIES = {
    # family: (the verb that puts an id on the list, the one that takes it off,
    #          what the list starts with)
    "food": ("ban", "allow", ("enchanted_golden_apple", "golden_apple")),
    "break": ("allow", "forbid", ("cobblestone", "dirt", "grass_block", "stone")),
}
LAYERS = ("base", "own", "imposed")
START = "start"
ID_RULE = re.compile(r"^[a-z0-9_]{1,64}$")
FROM_RULE = re.compile(r"^(prefs|food|break)\.([a-z0-9_]{1,64}|\*)$")
PENDING = "rules-pending.json"


# --- a layer ------------------------------------------------------------------

def empty():
    return {"prefs": {}, "food": {}, "break": {}, "replace": set(), "from": {}}


def an_id(value):
    """An item or block id as a layer holds it, or None. `minecraft:` is
    taken off: it is the only namespace the body resolves."""
    if not isinstance(value, str):
        return None
    v = value.strip().lower()
    if v.startswith("minecraft:"):
        v = v[len("minecraft:"):]
    return v if ID_RULE.match(v) else None


def parse(data, where="rules"):
    """A layer from its JSON shape (see the top), checked: anything it does
    not understand is a Fail that says what and where."""
    def bad(text):
        raise Fail(f"{where}: {text}", code="bad_rules")

    layer = empty()
    if data is None:
        return layer
    if not isinstance(data, dict):
        bad("rules are a JSON object")
    for part, value in data.items():
        if part == "prefs":
            if not isinstance(value, dict):
                bad('prefs is an object: {"toggle": true}')
            for key, v in value.items():
                k = str(key).strip().lower()
                if k not in TOGGLES:
                    bad(f"there is no toggle '{k}'. There are: {', '.join(TOGGLES)}")
                if not isinstance(v, bool):
                    bad(f"{k} is true or false")
                layer["prefs"][k] = v
        elif part in FAMILIES:
            on, off, _ = FAMILIES[part]
            if not isinstance(value, dict):
                bad(f'{part} is an object: {{"{on}": [...], "{off}": [...], "replace": false}}')
            for verb, v in value.items():
                if verb == "replace":
                    if not isinstance(v, bool):
                        bad(f"{part}.replace is true or false")
                    if v:
                        layer["replace"].add(part)
                    continue
                if verb not in (on, off):
                    bad(f"{part} has {on}, {off} and replace, not '{verb}'")
                if not isinstance(v, list):
                    bad(f"{part}.{verb} is a list of ids")
                for raw in v:
                    i = an_id(raw)
                    if i is None:
                        bad(f"'{raw}' is not an id; they go in English and without a namespace, "
                            "like rotten_flesh or dirt")
                    before = layer[part].get(i)
                    if before is not None and before != (verb == on):
                        bad(f"{i} is both {on} and {off}")
                    layer[part][i] = verb == on
        elif part == "from":
            if not isinstance(value, dict):
                bad('from is an object: {"food.beef": "global"}')
            for key, label in value.items():
                label = str(label or "").strip()
                if not FROM_RULE.match(str(key)) or not label or len(label) > 64:
                    bad(f"from: '{key}' -> '{label}' is not like \"food.beef\": \"global\"")
                layer["from"][key] = label
        else:
            bad(f"rules have no '{part}': they have prefs, food, break and from")
    return layer


def dump(layer):
    """A layer in its JSON shape: only the parts it has, lists sorted."""
    out = {}
    if layer["prefs"]:
        out["prefs"] = dict(sorted(layer["prefs"].items()))
    for family, (on, off, _) in FAMILIES.items():
        part = {}
        ons = sorted(i for i, v in layer[family].items() if v)
        offs = sorted(i for i, v in layer[family].items() if not v)
        if ons:
            part[on] = ons
        if offs:
            part[off] = offs
        if family in layer["replace"]:
            part["replace"] = True
        if part:
            out[family] = part
    if layer["from"]:
        out["from"] = dict(sorted(layer["from"].items()))
    return out


def is_empty(layer):
    return not dump(layer)


def merge(lower, upper):
    """`upper` on top of `lower`: a new layer, neither is touched."""
    m = {"prefs": {**lower["prefs"], **upper["prefs"]}, "replace": set(lower["replace"]),
         "from": dict(lower["from"])}
    for family in FAMILIES:
        if family in upper["replace"]:
            m[family] = dict(upper[family])
            m["replace"].add(family)
            m["from"] = {k: v for k, v in m["from"].items() if not k.startswith(family + ".")}
        else:
            m[family] = {**lower[family], **upper[family]}
    m["from"].update(upper["from"])
    return m


def labeled(layer, label):
    """The layer with `label` as who put each thing in it: what an imposed
    layer carries, so a refused command can say who imposes it."""
    out = merge(empty(), layer)
    for key in layer["prefs"]:
        out["from"][f"prefs.{key}"] = label
    for family in FAMILIES:
        for i in layer[family]:
            out["from"][f"{family}.{i}"] = label
        if family in layer["replace"]:
            out["from"][f"{family}.*"] = label
    return out


def effective(base, own, imposed):
    """What the body holds: every toggle, and each list whole."""
    everything = merge(merge(base, own), imposed)
    out = {"prefs": {**TOGGLES, **everything["prefs"]}}
    for family, (_, _, start) in FAMILIES.items():
        held = set() if family in everything["replace"] else set(start)
        for i, on in everything[family].items():
            (held.add if on else held.discard)(i)
        out["food_banned" if family == "food" else "break_allowed"] = sorted(held)
    return out


def source(base, own, imposed, family, key):
    """Who decides a toggle (family "prefs") or an id of a list: (the layer,
    who put it there). The strongest layer that names it, or that replaces its
    list; START when none does."""
    for name, layer in (("imposed", imposed), ("own", own), ("base", base)):
        if family == "prefs":
            if key in layer["prefs"]:
                return name, layer["from"].get(f"prefs.{key}", "")
        else:
            if key in layer[family]:
                return name, layer["from"].get(f"{family}.{key}", layer["from"].get(f"{family}.*", ""))
            if family in layer["replace"]:
                return name, layer["from"].get(f"{family}.*", "")
    return START, ""


def say_source(layer, label=""):
    return {"imposed": f"imposed by {label or 'the launcher'}", "own": "set here",
            "base": "its config"}.get(layer, "")


def edit(layer, words, where="rules"):
    """One change to a layer, in the words of /marionette bot:

        pref <toggle> on|off|default
        food ban|allow|default <item>       break allow|forbid|default <block>
        food|break replace|add              the list replaces what is under it, or adds

    Returns (the layer changed, the change as it goes to the server: kind,
    key, value). Raises Fail on anything else."""
    words = [w.strip().lower() for w in words if w.strip()]
    usage = ("say  pref <toggle> on|off|default,  food ban|allow|default <item>,  "
             "break allow|forbid|default <block>,  or  food|break replace|add")
    if not words:
        raise Fail(usage, code="bad_rules")
    out = merge(empty(), layer)
    kind = words[0]
    if kind == "pref":
        if len(words) != 3:
            raise Fail(usage, code="bad_rules")
        key, value = words[1], words[2]
        if key not in TOGGLES:
            raise Fail(f"there is no toggle '{key}'. There are: {', '.join(TOGGLES)}", code="bad_rules")
        if value in ("on", "true", "yes"):
            out["prefs"][key] = True
        elif value in ("off", "false", "no"):
            out["prefs"][key] = False
        elif value == "default":
            out["prefs"].pop(key, None)
        else:
            raise Fail(f"{key} is on, off or default", code="bad_rules")
        return out, ("pref", key, "default" if value == "default" else
                     "on" if out["prefs"][key] else "off")
    if kind not in FAMILIES:
        raise Fail(usage, code="bad_rules")
    on, off, _ = FAMILIES[kind]
    if len(words) == 2 and words[1] in ("replace", "add"):
        (out["replace"].add if words[1] == "replace" else out["replace"].discard)(kind)
        return out, (kind, "*", words[1])
    if len(words) != 3 or words[1] not in (on, off, "default"):
        raise Fail(f"{kind} {on}|{off}|default <id>, or {kind} replace|add", code="bad_rules")
    i = an_id(words[2])
    if i is None:
        raise Fail(f"'{words[2]}' is not an id; they go in English and without a namespace, "
                   "like rotten_flesh or dirt", code="bad_rules")
    if words[1] == "default":
        out[kind].pop(i, None)
    else:
        out[kind][i] = words[1] == on
    return out, (kind, i, words[1])


def describe(layer):
    """A layer in a few lines, for a person."""
    lines = []
    for key, v in sorted(layer["prefs"].items()):
        lines.append(f"{key} {'on' if v else 'off'}")
    for family, (on, off, _) in FAMILIES.items():
        if family in layer["replace"]:
            lines.append(f"{family}: replaces the whole list")
        for verb, want in ((on, True), (off, False)):
            ids = sorted(i for i, v in layer[family].items() if v == want)
            if ids:
                lines.append(f"{family} {verb} {', '.join(ids)}")
    return lines


# --- where the launcher keeps its layers ---------------------------------------

def of_bot(bot):
    return parse(bot.data.get("rules"), f"{bot.json} (rules)")


def of_server(ws, slug):
    f = ws.servers_dir / slug / "rules.json"
    return parse(read_json(f) if f.is_file() else None, str(f))


def of_global(ws):
    return parse(ws.config().get("rules"), f"{ws.config_file} (rules)")


def base_of(inst):
    return merge(of_bot(inst.bot), of_server(inst.ws, inst.slug))


def imposed_of(inst):
    from . import settings
    if settings.get(inst, "ignore_global") == "yes":
        return empty()
    return labeled(of_global(inst.ws), "global")


def save_bot(bot, layer):
    data = bot.data
    if is_empty(layer):
        data.pop("rules", None)
    else:
        data["rules"] = dump(layer)
    bot.save(data)


def save_server(ws, slug, layer):
    f = ws.servers_dir / slug / "rules.json"
    if is_empty(layer):
        f.unlink(missing_ok=True)
    else:
        write_json(f, dump(layer))


def save_global(ws, layer):
    data = ws.config()
    if is_empty(layer):
        data.pop("rules", None)
    else:
        data["rules"] = dump(layer)
    if data:
        ws.save_config(data)
    else:
        ws.config_file.unlink(missing_ok=True)


# --- the server's side ----------------------------------------------------------

class OldServerMod(Fail):
    """A server mod from before the rules: it has no /rules."""


class NotKnownYet(Fail):
    """A bot its server has not seen: never started there, no rules sent yet."""


def ask(api, player, **params):
    """/rules of one bot on one server, with a change or not: its three
    layers and what they come to. Refused changes are a Fail with the
    server's reason; a server that does not answer raises UNREACHABLE, as
    the rest of the API."""
    query = urllib.parse.urlencode({"bot": player, **params})
    try:
        answer = json.loads(api.get(f"/rules?{query}"))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            raise OldServerMod(f"the server mod at {api.address} is older than the rules: "
                               "deploy the new one (marionette.py deploy-mod)", code="old_server_mod")
        if e.code in (401, 403):
            raise Fail(f"the server mod at {api.address} refused the token", code="server_mod_refused")
        try:
            said = json.loads(e.read().decode("utf-8", "replace")).get("error")
        except (ValueError, OSError, AttributeError):
            said = None
        raise Fail(f"the server refused it: {said or f'HTTP {e.code}'}", code="rules_refused")
    if not isinstance(answer, dict):
        raise Fail(f"the server mod at {api.address} answered something that is not rules",
                   code="rules_refused")
    if answer.get("ok") is False:
        raise NotKnownYet(f"the server has not seen {player} yet", code="not_known_yet")
    return answer


def layers_in(answer):
    """(base, own, imposed) from the server's answer."""
    return tuple(parse(answer.get(name), f"the server's {name} layer") for name in LAYERS)


def send_layer(api, inst, name, layer):
    return ask(api, inst.name, layer=name, set=json.dumps(dump(layer), separators=(",", ":")))


def pending(inst):
    try:
        data = json.loads((inst.dir / PENDING).read_text(encoding="utf-8"))
        return data if isinstance(data, list) else []
    except (OSError, ValueError):
        return []


def keep_pending(inst, items):
    f = inst.dir / PENDING
    if items:
        write_json(f, items)
    else:
        f.unlink(missing_ok=True)


def send_pending(api, inst):
    """The own-layer changes that waited for the server, in order. Returns
    (sent, refused): lines saying what went and what the server refused (a
    refused one is dropped: sent again it would be refused again). Stops at
    the first that does not reach it, keeping the rest."""
    items = pending(inst)
    sent, refused = [], []
    while items:
        item = items[0]
        try:
            if "set" in item:
                send_layer(api, inst, "own", parse(item["set"], "a waiting copy of the own layer"))
                what = "its own rules, copied"
            else:
                ask(api, inst.name, layer="own", kind=item["kind"], key=item["key"], value=item["value"])
                what = " ".join(pretty_change(item["kind"], item["key"], item["value"]))
            sent.append(what)
        except UNREACHABLE:
            break
        except OldServerMod:
            raise
        except Fail as e:
            refused.append(f"{' '.join(pretty_change(item.get('kind'), item.get('key'), item.get('value')))}"
                           f": {e}")
        items = items[1:]
        keep_pending(inst, items)
    return sent, refused


def pretty_change(kind, key, value):
    """A change in the order a person says it: pref hunt_players on, food ban
    beef, food replace."""
    if key == "*":
        return [kind, value]
    if kind == "pref":
        return [kind, key, value]
    return [kind, value, key]


def push(inst, api):
    """What the launcher holds for an instance goes to its server: base and
    imposed whole, then the own-layer changes that were waiting. Returns
    (the server's answer after all of it, lines for what waited)."""
    base, imposed = base_of(inst), imposed_of(inst)
    send_layer(api, inst, "base", base)
    answer = send_layer(api, inst, "imposed", imposed)
    sent, refused = send_pending(api, inst)
    if sent or refused:
        answer = ask(api, inst.name)
    return answer, sent, refused

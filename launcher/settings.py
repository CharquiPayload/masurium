"""Settings, in layers, and what each one accepts.

One table says, for each setting, what it means, what it accepts, what
applies without it, what a menu would offer, when a change counts, and at
which layers it may be set. The command line, doctor and a window check a
value the same way.

The layers, weakest first: the bot (bot.json), the instance
(instance.json), the groups it is in, from its own outwards (group.json,
"settings"), and the global config (launcher.json, "settings"). A layer sets
only what it names; what no layer names takes the default. The outer ones
IMPOSE: a group's model is the model of every instance inside it, unless an
instance or a group on the way has a `lock` (the groups outside it stop
there) or `ignore_global` (the global config leaves it alone).

The bridge and the MCP server do not know about layers. They read one small
file per setting in the instance's folder, as they always read a bot's
folder; `render` writes those files from the layers, on every start and
every change, so they are an output, never edited by hand.
"""
import json
import re
import shutil
import shlex
from dataclasses import dataclass

import pathlib

from . import groups
from .bots import Character, Instance
from .events import Fail
from .files import read_java_properties, unlink_quietly

PLAYER_NAME = re.compile(r"^[A-Za-z0-9_]{1,16}$")
EFFORTS = ("low", "medium", "high", "xhigh", "max")
BOT, INSTANCE, GROUP, GLOBAL = "bot", "instance", "group", "global"
EVERYWHERE = (BOT, INSTANCE, GROUP, GLOBAL)


@dataclass(frozen=True)
class Setting:
    key: str
    help: str
    default: object       # what applies without it: a value, or a function of the target
    choices: tuple = ()   # what a menu offers; a free-form setting has none
    applies: str = "restart"  # when a change counts: see APPLIES
    layers: tuple = EVERYWHERE
    # False: it says something about the very target it is set on, and is not
    # passed down to what is inside (a lock).
    inherited: bool = True

    def default_for(self, target):
        return self.default(target) if callable(self.default) else self.default

    @property
    def at_start(self):
        """Read when the client starts, and only then: changing it under a
        running client would make the file say one thing and the game another."""
        return self.applies == "start"


APPLIES = {
    "start": "on its next start",
    "restart": "when the bot restarts (the client and the bridge read it when they start)",
    "bridge": "when its bridge restarts",
    "now": "right away",
}


def _check_port(target, value):
    if not value.isdigit() or not 1024 <= int(value) <= 65535:
        return "a port number between 1024 and 65535"
    if int(value) == 8477:
        return "8477 is the server mod's port"
    if target.ws.port_reserved(int(value), target.key):
        return f"port {value} already belongs to another instance"
    return None


def _check_account(target, value):
    if value in ("offline", "online") or target.ws.account(value).exists():
        return None
    return ("offline, online, or one of the accounts: "
            + (", ".join(target.ws.account_keys()) or "(none yet: marionette.py account add)"))


def _check_owner(target, value):
    return None if PLAYER_NAME.match(value) else "a Minecraft player name: letters, digits, underscore"


def _check_model(target, value):
    parts = value.split()
    if not 1 <= len(parts) <= 2 or not re.match(r"^[A-Za-z0-9._:\[\]-]+$", parts[0]):
        return "a model and an optional effort, such as `sonnet` or `haiku low`"
    if len(parts) == 2 and parts[1] not in EFFORTS:
        return "the effort is one of " + ", ".join(EFFORTS)
    return None


def _check_heap(target, value):
    m = re.match(r"^(\d+)([gm])$", value)
    if not m:
        return "a heap size such as 3g or 2048m"
    mb = int(m.group(1)) * (1024 if m.group(2) == "g" else 1)
    if mb < 1024:
        return "at least 1g: a modded client does not start with less"
    return None


def _check_java(target, value):
    found = shutil.which(value) if not ("/" in value or "\\" in value) else (value if pathlib.Path(value).is_file() else None)
    if not found:
        return "a java that is there: a path to a java executable, or a name on the PATH"
    return None


def _check_java_args(target, value):
    try:
        words = shlex.split(value)
    except ValueError as e:
        return f"arguments as a shell would split them ({e})"
    if '"' in value or any(not w.startswith("-") for w in words):
        return "JVM flags, each starting with -, such as -XX:+UseZGC, without quotes"
    if any(w.startswith("-Xmx") for w in words):
        return "the heap is its own setting (heap), not a flag here"
    return None


def _java_default(target):
    return target.ws.java_command()[0]


def java_command(inst):
    """The Java an instance's game runs on (its `java` setting), as a command."""
    return [get(inst, "java")]


def _owner_default(target):
    return target.ws.env_values().get("MARIONETTE_OWNER", "")


def _heap_default(target):
    return target.ws.heap()


SETTINGS = {s.key: s for s in [
    Setting("account", "one of the launcher's accounts (marionette.py account add), offline "
            "(private servers with online-mode=false), or online (a login kept in the instance)",
            "online", ("offline", "online"), applies="start", layers=(BOT, INSTANCE)),
    Setting("heap", "the game's memory (Java heap)", _heap_default, ("2g", "3g", "4g", "6g"),
            applies="start"),
    Setting("port", "the local port of the bot mod: its hands", "", applies="start", layers=(INSTANCE,)),
    Setting("role", "main (takes orders, does jobs) or guard (follows and protects the leader of its "
            "dependency group)", "main", ("main", "guard"), applies="start", layers=(BOT, INSTANCE, GROUP)),
    Setting("model", "its brain's model and effort: an alias of Claude Code (opus, sonnet, haiku, fable, "
            "each always the newest of its family; opus[1m] and the like for a million tokens of context) or "
            "a model's full id, and optionally an effort", "opus medium",
            ("opus medium", "opus high", "sonnet", "sonnet low", "haiku low", "fable"), applies="bridge"),
    Setting("owner", "the player it belongs to: their delicate orders, /marionette bot anywhere",
            _owner_default, applies="now"),
    Setting("java", "the Java its game runs on: a path to a java executable, or a name on the PATH "
            "(NeoForge 21.1 wants Java 21)", _java_default, applies="start", layers=(INSTANCE, GROUP, GLOBAL)),
    Setting("java_args", "extra flags for its Java (the JVM), such as -XX:+UseZGC; the heap is its own "
            "setting", "", applies="start", layers=(INSTANCE, GROUP, GLOBAL)),
    Setting("fast_responses", "its brain writes ahead of time, in its voice and language, the few things it "
            "says without thinking (warning of a creeper, being cornered, shutting down); again when its "
            "personality changes. No: those are said in plain English", "yes", ("no", "yes"), applies="bridge"),
    Setting("ignore_global", "yes: the global config (launcher.json: settings and rules) leaves it alone",
            "no", ("no", "yes"), applies="now", layers=(INSTANCE, GROUP)),
    Setting("lock", "yes: the groups around it do not impose on it (the global config still does)",
            "no", ("no", "yes"), applies="now", layers=(INSTANCE, GROUP), inherited=False),
]}

CHECKS = {"account": _check_account, "port": _check_port, "owner": _check_owner,
          "model": _check_model, "heap": _check_heap, "java": _check_java, "java_args": _check_java_args}
# Case matters in names (a player, a bot as the game shows it); in codes and
# sizes it does not.
LOWERCASE = ("account", "heap", "ignore_global", "lock", "role", "fast_responses")
# Settings there were once, and where what they said goes now.
RETIRED = {
    "language": "the personality says which language the bot speaks (personality.txt)",
    "gender": "the personality says who the bot is (personality.txt)",
    "escort": "a guard's leader is its dependency group (marionette.py group create <group> "
              "--leader <instance>, then group add <group> <guard>; marionette.py migrate turns an "
              "escort into one)",
}


# Claude Code's model aliases. Each one always means the newest model of its
# family (the day a new Opus came out, `opus` was it), so a list of them does
# not go stale. Claude Code has no command that lists the models an account
# may use; what its settings allow (availableModels) is added when there is.
MODEL_ALIASES = ("opus", "opus[1m]", "sonnet", "sonnet[1m]", "haiku", "fable", "fable[1m]")


def claude_models(ws):
    """The models to offer: the aliases, and the availableModels of this
    machine's Claude Code settings, if it names any."""
    out = list(MODEL_ALIASES)
    try:
        data = json.loads((ws.home / ".claude" / "settings.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        data = {}
    for m in (data.get("availableModels") if isinstance(data, dict) else None) or []:
        if isinstance(m, str) and m.strip() and m.strip() not in out:
            out.append(m.strip())
    return out


def suggestions(target, key):
    """What a menu offers for a setting: its choices, plus the accounts there
    are, or the models Claude Code knows."""
    out = list(setting(key).choices)
    if key == "account":
        out += target.ws.account_keys()
    if key == "model":
        out += [m for m in claude_models(target.ws) if m not in out]
    return out


def setting(key):
    try:
        return SETTINGS[key]
    except KeyError:
        if key in RETIRED:
            raise Fail(f"'{key}' is no longer a setting: {RETIRED[key]}.", code="bad_setting")
        raise Fail(f"there is no setting '{key}'. These are:", lines=list(SETTINGS), code="bad_setting")


def layer_of(target):
    if isinstance(target, Instance):
        return INSTANCE
    if isinstance(target, Character):
        return BOT
    if isinstance(target, groups.Group):
        return GROUP
    if isinstance(target, groups.Global):
        return GLOBAL
    raise TypeError(target)


def own_values(target):
    """What a target's own layer says: the top of bot.json and instance.json,
    the "settings" of group.json and launcher.json (which hold more)."""
    data = target.data
    if isinstance(target, (groups.Group, groups.Global)):
        values = data.get("settings")
        return values if isinstance(values, dict) else {}
    return data


def _save_values(target, values):
    data = target.data
    if isinstance(target, (groups.Group, groups.Global)):
        if values:
            data["settings"] = values
        else:
            data.pop("settings", None)
    else:
        data = values
    target.save(data)


def normalize(key, value):
    value = " ".join(str(value).split())
    return value.lower() if key in LOWERCASE else value


def problem(target, key, value):
    """What is wrong with this value, set at this target's layer, or None."""
    s = setting(key)
    if layer_of(target) not in s.layers:
        return f"it is set per {' or '.join(s.layers)}, not per {layer_of(target)}"
    value = normalize(key, value)
    if s.choices and key not in CHECKS and value not in s.choices:
        return "one of " + ", ".join(s.choices)
    check = CHECKS.get(key)
    return check(target, value) if check else None


def _raw(target, key):
    v = own_values(target).get(key)
    return "" if v is None else str(v)


def _above(chain_, s, key, ignoring_global, ws):
    """(value, layer) from the global config or the groups, strongest
    first, or None."""
    if GLOBAL in s.layers and not ignoring_global and _raw(groups.Global(ws), key):
        return _raw(groups.Global(ws), key), GLOBAL
    if GROUP in s.layers:
        for g in reversed(chain_):
            if _raw(g, key):
                return _raw(g, key), f"group {g.key}"
    return None


def group_chain(target):
    """The groups that impose on an instance or a group: the ones around it,
    innermost first, up to a lock."""
    if isinstance(target, Instance):
        return groups.chain(target)
    if isinstance(target, groups.Group):
        if _raw(target, "lock") == "yes":
            return []
        parent = groups.parent_of(target.ws, target)
        if parent is None:
            return []
        return [parent] + ([] if _raw(parent, "lock") == "yes" else group_chain(parent))
    return []


def resolve(target, key, own_layer=True):
    """(the value that applies, the layer it comes from: "global",
    "group <name>", "instance", "bot" or "default"). For an instance the
    strongest first: the global config, its groups from the outermost in,
    itself, its bot. With `own_layer` False, what would apply if the
    target's own layer said nothing: what a window offers as "not set here"."""
    s = setting(key)
    own = layer_of(target) if own_layer else None
    if not s.inherited:
        if own in s.layers and _raw(target, key):
            return _raw(target, key), own
        return s.default_for(target), "default"
    if isinstance(target, (Instance, groups.Group)):
        chain_ = group_chain(target)
        ignoring = key != "ignore_global" and resolve(target, "ignore_global")[0] == "yes"
        found = _above(chain_, s, key, ignoring, target.ws)
        if found:
            return found
        if own in s.layers and _raw(target, key):
            return _raw(target, key), own
        if isinstance(target, Instance):
            bot = target.bot
            if BOT in s.layers and bot.exists() and _raw(bot, key):
                return _raw(bot, key), BOT
    elif own in s.layers and _raw(target, key):
        return _raw(target, key), own
    return s.default_for(target), "default"


def get(target, key):
    return resolve(target, key)[0]


def is_set(target, key):
    """Whether this target's OWN layer names it."""
    return bool(_raw(target, key))


def who(target):
    return target.id if not isinstance(target, Character) else f"the bot {target.key}"


def set_value(target, key, value):
    """Check, then write into this target's own layer. Returns the value written."""
    value = normalize(key, value)
    wrong = problem(target, key, value)
    if wrong:
        raise Fail(f"{who(target)}: '{value}' is not a valid {key}: {wrong}.", code="bad_setting")
    values = own_values(target)
    values[key] = int(value) if key == "port" else value
    _save_values(target, values)
    return value


def clear(target, key):
    """Back to what the layer below says: the key leaves this layer."""
    s = setting(key)
    if layer_of(target) not in s.layers:
        raise Fail(f"{key} is set per {' or '.join(s.layers)}, not per {layer_of(target)}.",
                   code="bad_setting")
    if key == "port":
        raise Fail("port has no default: every instance needs one.", code="bad_setting")
    values = own_values(target)
    values.pop(key, None)
    _save_values(target, values)


def problems(target):
    """(key, what is wrong) for every value this target's own layer holds
    that would be refused if it were set now: the JSON files can also be
    edited by hand."""
    out = []
    for key, value in own_values(target).items():
        # Not settings: who it is, and its rules (checked in rules.py).
        if key in ("name", "bot", "server", "rules") or value in (None, ""):
            continue
        if key in RETIRED:
            gone = RETIRED[key]
            out.append((key, f"'{key}' is no longer a setting: {gone}"
                        + ("" if "migrate" in gone else "; it can go")))
            continue
        if key not in SETTINGS:
            out.append((key, f"'{key}' is not a setting"))
            continue
        wrong = problem(target, key, str(value))
        if wrong:
            out.append((key, f"'{value}' is not a valid {key}: {wrong}"))
    return out


# --- what the bridge reads -------------------------------------------------------

# The flat files the bridge and the MCP server read in an instance's folder, one
# value each, written only when a layer sets them: without the file they apply
# the same defaults as the table above (MARIONETTE_OWNER for the owner). Plus
# `escort`, the player of a guard's leader, from its dependency group.
RENDERED = ("account", "model", "owner", "fast_responses")
# Files an older launcher rendered, removed wherever they are left.
STALE = ("language", "gender")


def render(inst):
    """Write what the bridge reads, from the layers: one file per setting,
    the port, the server's slug, the personality, and HeadlessMC's own config
    (the player name, offline or not, and the game folder, which moves with
    the instance)."""
    inst.dir.mkdir(parents=True, exist_ok=True)

    def put(name, text):
        f = inst.dir / name
        text = f"{text}\n"
        try:
            if f.read_text(encoding="utf-8") == text:
                return
        except OSError:
            pass
        f.write_text(text, encoding="utf-8")

    for key in RENDERED:
        value, layer = resolve(inst, key)
        if layer == "default" or not value:
            unlink_quietly(inst.dir / key)
        else:
            put(key, value)
    for key in STALE:
        unlink_quietly(inst.dir / key)
    leader = groups.leader_of(inst)
    if leader is not None:
        put("escort", leader.name)
    else:
        unlink_quietly(inst.dir / "escort")
    put("port", inst.port)
    put("server", inst.slug)
    bot = inst.bot
    if bot.personality.is_file():
        shutil.copyfile(bot.personality, inst.dir / "personality.txt")
    else:
        unlink_quietly(inst.dir / "personality.txt")
    cfg = inst.hmc / "HeadlessMC" / "config.properties"
    if cfg.parent.is_dir():
        props = read_java_properties(cfg)
        props.setdefault("hmc.jline.enabled", "false")
        from .accounts import plays_offline
        props["hmc.offline"] = "true" if plays_offline(inst.ws, get(inst, "account")) else "false"
        # The Java HeadlessMC launches the game with: the instance's.
        props["hmc.java.versions"] = shutil.which(get(inst, "java")) or get(inst, "java")
        props["hmc.offline.username"] = inst.name
        props.setdefault("hmc.invert.command.modifiers", "false")
        props["hmc.gamedir"] = str(inst.gamedir)
        cfg.write_text("".join(f"{k}={v}\n" for k, v in props.items()), encoding="utf-8")
    # Its login: the account's, through a link, or one of its own.
    from .accounts import kind, link_login, unlink_login
    value = get(inst, "account")
    if kind(value) == "account" and inst.ws.account(value).exists() and not inst.ws.account(value).offline:
        link_login(inst, inst.ws.account(value))
    elif (inst.hmc / "HeadlessMC").is_dir():
        unlink_login(inst)

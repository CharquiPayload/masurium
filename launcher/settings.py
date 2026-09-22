"""Settings, in layers, and what each one accepts.

One table says, for each setting, what it means, what it accepts, what
applies without it, what a menu would offer, when a change counts, and at
which layers it may be set. The command line, doctor and a window check a
value the same way.

The layers, weakest first: the bot (bot.json), then the instance
(instance.json). A layer sets only what it names; what no layer names takes
the default. (Groups and the global layer come on top, later.)

The bridge and the MCP server do not know about layers. They read one small
file per setting in the instance's folder, as they always read a bot's
folder; `render` writes those files from the layers, on every start and
every change, so they are an output, never edited by hand.
"""
import re
import shutil
from dataclasses import dataclass

from .bots import Character, Instance
from .events import Fail
from .files import read_java_properties, unlink_quietly

PLAYER_NAME = re.compile(r"^[A-Za-z0-9_]{1,16}$")
EFFORTS = ("low", "medium", "high", "xhigh", "max")
BOT, INSTANCE = "bot", "instance"


@dataclass(frozen=True)
class Setting:
    key: str
    help: str
    default: object       # what applies without it: a value, or a function of the target
    choices: tuple = ()   # what a menu offers; a free-form setting has none
    applies: str = "restart"  # when a change counts: see APPLIES
    layers: tuple = (BOT, INSTANCE)

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


def _check_escort(target, value):
    if value.lower() == target.player:
        return "a bot cannot escort itself"
    here = sorted({i.player for i in target.ws.instances(target.slug) if i.player != target.player})
    if value.lower() not in here:
        return (f"the player name of a bot with an instance on {target.slug}: "
                + (", ".join(here) or "(there is no other)"))
    return None


def _check_heap(target, value):
    m = re.match(r"^(\d+)([gm])$", value)
    if not m:
        return "a heap size such as 3g or 2048m"
    mb = int(m.group(1)) * (1024 if m.group(2) == "g" else 1)
    if mb < 1024:
        return "at least 1g: a modded client does not start with less"
    return None


def _owner_default(target):
    return target.ws.env_values().get("MARIONETTE_OWNER", "")


def _heap_default(target):
    return target.ws.heap()


SETTINGS = {s.key: s for s in [
    Setting("account", "one of the launcher's accounts (marionette.py account add), offline "
            "(private servers with online-mode=false), or online (a login kept in the instance)",
            "online", ("offline", "online"), applies="start"),
    Setting("heap", "the game's memory (Java heap)", _heap_default, ("2g", "3g", "4g", "6g"),
            applies="start"),
    Setting("port", "the local port of the bot mod: its hands", "", applies="start", layers=(INSTANCE,)),
    Setting("escort", "another instance on its server, by player name: this one becomes its guard",
            "", layers=(INSTANCE,)),
    Setting("model", "its brain's model and effort", "opus medium",
            ("opus medium", "sonnet", "sonnet low", "haiku low"), applies="bridge"),
    Setting("owner", "the player it belongs to: their delicate orders, /marionette bot anywhere",
            _owner_default, applies="now"),
]}

CHECKS = {"account": _check_account, "port": _check_port, "owner": _check_owner,
          "model": _check_model, "escort": _check_escort, "heap": _check_heap}
# Case matters in names (a player, a bot as the game shows it); in codes and
# sizes it does not.
LOWERCASE = ("account", "heap")
# Settings there were once, and where what they said goes now.
RETIRED = {
    "language": "the personality says which language the bot speaks",
    "gender": "the personality says who the bot is",
}


def setting(key):
    try:
        return SETTINGS[key]
    except KeyError:
        if key in RETIRED:
            raise Fail(f"'{key}' is no longer a setting: {RETIRED[key]} (personality.txt).",
                       code="bad_setting")
        raise Fail(f"there is no setting '{key}'. These are:", lines=list(SETTINGS), code="bad_setting")


def layer_of(target):
    if isinstance(target, Instance):
        return INSTANCE
    if isinstance(target, Character):
        return BOT
    raise TypeError(target)


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
    v = target.data.get(key)
    return "" if v is None else str(v)


def resolve(target, key):
    """(the value that applies, the layer it comes from: "instance", "bot"
    or "default")."""
    s = setting(key)
    if isinstance(target, Instance):
        if INSTANCE in s.layers and _raw(target, key):
            return _raw(target, key), INSTANCE
        bot = target.bot
        if BOT in s.layers and bot.exists() and _raw(bot, key):
            return _raw(bot, key), BOT
    elif BOT in s.layers and _raw(target, key):
        return _raw(target, key), BOT
    return s.default_for(target), "default"


def get(target, key):
    return resolve(target, key)[0]


def is_set(target, key):
    """Whether this target's OWN layer names it."""
    return bool(_raw(target, key))


def set_value(target, key, value):
    """Check, then write into this target's own layer. Returns the value written."""
    value = normalize(key, value)
    wrong = problem(target, key, value)
    if wrong:
        who = target.id if isinstance(target, Instance) else f"the bot {target.key}"
        raise Fail(f"{who}: '{value}' is not a valid {key}: {wrong}.", code="bad_setting")
    data = target.data
    data[key] = int(value) if key == "port" else value
    target.save(data)
    return value


def clear(target, key):
    """Back to what the layer below says: the key leaves this layer."""
    s = setting(key)
    if layer_of(target) not in s.layers:
        raise Fail(f"{key} is set per {' or '.join(s.layers)}, not per {layer_of(target)}.",
                   code="bad_setting")
    if key == "port":
        raise Fail("port has no default: every instance needs one.", code="bad_setting")
    data = target.data
    data.pop(key, None)
    target.save(data)


def problems(target):
    """(key, what is wrong) for every value this target's own layer holds
    that would be refused if it were set now: the JSON files can also be
    edited by hand."""
    out = []
    for key, value in target.data.items():
        if key in ("name", "bot", "server") or value in (None, ""):
            continue
        if key in RETIRED:
            out.append((key, f"'{key}' is no longer a setting: {RETIRED[key]}; it can go"))
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
# the same defaults as the table above (MARIONETTE_OWNER for the owner).
RENDERED = ("account", "escort", "model", "owner")
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
        props["hmc.offline"] = "true" if get(inst, "account") == "offline" else "false"
        props["hmc.offline.username"] = inst.name
        props.setdefault("hmc.invert.command.modifiers", "false")
        props["hmc.gamedir"] = str(inst.gamedir)
        cfg.write_text("".join(f"{k}={v}\n" for k, v in props.items()), encoding="utf-8")
    # Its login: the account's, through a link, or one of its own.
    from .accounts import kind, link_login, unlink_login
    value = get(inst, "account")
    if kind(value) == "account" and inst.ws.account(value).exists():
        link_login(inst, inst.ws.account(value))
    elif (inst.hmc / "HeadlessMC").is_dir():
        unlink_login(inst)

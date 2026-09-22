"""A bot's settings: the small files in bots/<name>/, what each one means,
what it accepts, and what applies without it. One table, so the command line,
doctor and a window's drop-down menus all check a value the same way.

The files stay the source of truth, and they stay plain: one value per file,
edited by hand if someone prefers, read by the bridge and the launcher as they
always were. What this adds is saying NO to a bad value when it is set,
instead of when a game that took minutes to load does something odd with it.
"""
import re
from dataclasses import dataclass

from .events import Fail
from .files import read_java_properties

PLAYER_NAME = re.compile(r"^[A-Za-z0-9_]{1,16}$")
EFFORTS = ("low", "medium", "high", "xhigh", "max")


@dataclass(frozen=True)
class Setting:
    key: str              # the file in bots/<name>/
    help: str
    default: object       # what applies without the file: a value, or a function of the bot
    choices: tuple = ()   # what a menu offers; a free-form setting has none
    required: bool = False
    applies: str = "restart"  # when a change counts: see APPLIES

    def default_for(self, bot):
        return self.default(bot) if callable(self.default) else self.default

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


def _check_port(bot, value):
    if not value.isdigit() or not 1024 <= int(value) <= 65535:
        return "a port number between 1024 and 65535"
    if bot.ws.port_reserved(int(value), bot.key):
        return f"port {value} already belongs to another bot"
    if int(value) == 8477:
        return "8477 is the server mod's port"
    return None


def _check_server(bot, value):
    if value not in bot.ws.server_slugs():
        return "one of the registered servers: " + (", ".join(bot.ws.server_slugs()) or "(none yet)")
    return None


def _check_language(bot, value):
    return None if re.match(r"^[a-z]{2,3}$", value) else "a language code such as en or es"


def _check_owner(bot, value):
    return None if PLAYER_NAME.match(value) else "a Minecraft player name: letters, digits, underscore"


def _check_model(bot, value):
    parts = value.split()
    if not 1 <= len(parts) <= 2 or not re.match(r"^[A-Za-z0-9._:\[\]-]+$", parts[0]):
        return "a model and an optional effort, such as `sonnet` or `haiku low`"
    if len(parts) == 2 and parts[1] not in EFFORTS:
        return "the effort is one of " + ", ".join(EFFORTS)
    return None


def _check_escort(bot, value):
    if value.lower() == bot.key:
        return "a bot cannot escort itself"
    if value.lower() not in bot.ws.bot_keys():
        return "the name of another bot here: " + (", ".join(k for k in bot.ws.bot_keys() if k != bot.key)
                                                   or "(there is no other)")
    return None


def _check_heap(bot, value):
    m = re.match(r"^(\d+)([gm])$", value)
    if not m:
        return "a heap size such as 3g or 2048m"
    mb = int(m.group(1)) * (1024 if m.group(2) == "g" else 1)
    if mb < 1024:
        return "at least 1g: a modded client does not start with less"
    return None


def _apply_account(bot, value):
    """HeadlessMC decides offline or not from its own config, not from the
    `account` file: both are written, or they would disagree."""
    cfg = bot.hmc / "HeadlessMC" / "config.properties"
    props = read_java_properties(cfg)
    if props:
        props["hmc.offline"] = "true" if value == "offline" else "false"
        cfg.write_text("".join(f"{k}={v}\n" for k, v in props.items()), encoding="utf-8")


SETTINGS = {s.key: s for s in [
    Setting("server", "the server it joins (a slug from `servers`)", "", required=True, applies="start"),
    Setting("port", "the local port of the bot mod: its hands", "", required=True, applies="start"),
    Setting("account", "online (a purchased account, logged in once) or offline "
            "(private servers with online-mode=false)", "online", ("online", "offline"), applies="start"),
    Setting("heap", "the game's memory (Java heap)", lambda bot: bot.ws.heap(),
            ("2g", "3g", "4g", "6g"), applies="start"),
    Setting("language", "the language it speaks in the chat", "en", ("en", "es", "pt", "fr", "de", "it")),
    Setting("gender", "grammatical gender, for languages that inflect", "f", ("f", "m")),
    Setting("escort", "another bot: this one becomes its guard", ""),
    Setting("model", "its brain's model and effort", "opus medium",
            ("opus medium", "sonnet", "sonnet low", "haiku low"), applies="bridge"),
    Setting("owner", "the player it belongs to: their delicate orders, /marionette bot anywhere",
            lambda bot: bot.ws.env_values().get("MARIONETTE_OWNER", ""), applies="now"),
]}

CHECKS = {"server": _check_server, "port": _check_port, "language": _check_language,
          "owner": _check_owner, "model": _check_model, "escort": _check_escort, "heap": _check_heap}
APPLY = {"account": _apply_account}


def setting(key):
    try:
        return SETTINGS[key]
    except KeyError:
        raise Fail(f"there is no setting '{key}'. These are:", lines=list(SETTINGS), code="bad_setting")


# Case matters in names (a player, a server's folder, a bot as the game shows
# it); in codes and sizes it does not.
LOWERCASE = ("account", "language", "gender", "heap")


def normalize(key, value):
    value = " ".join(str(value).split())
    return value.lower() if key in LOWERCASE else value


def problem(bot, key, value):
    """What is wrong with this value for this bot, or None."""
    s = setting(key)
    value = normalize(key, value)
    if s.choices and key not in CHECKS and value not in s.choices:
        return "one of " + ", ".join(s.choices)
    check = CHECKS.get(key)
    return check(bot, value) if check else None


def get(bot, key):
    """The value that applies: the file's, or the default."""
    s = setting(key)
    return bot.read(key) or s.default_for(bot)


def is_set(bot, key):
    return bool(bot.read(key))


def set_value(bot, key, value):
    """Check, then write. Returns the value written."""
    value = normalize(key, value)
    wrong = problem(bot, key, value)
    if wrong:
        raise Fail(f"{bot.name}: '{value}' is not a valid {key}: {wrong}.", code="bad_setting")
    bot.write(key, value)
    if key in APPLY:
        APPLY[key](bot, value)
    return value


def clear(bot, key):
    """Back to the default: the file goes. A required one cannot."""
    s = setting(key)
    if s.required:
        raise Fail(f"{key} has no default: every bot needs one.", code="bad_setting")
    try:
        (bot.dir / key).unlink()
    except FileNotFoundError:
        pass
    if key in APPLY:
        APPLY[key](bot, s.default_for(bot))


def problems(bot):
    """(key, what is wrong) for every file present with a value that would
    be refused if it were set now: files are also edited by hand."""
    out = []
    for key in SETTINGS:
        value = bot.read(key)
        if value:
            wrong = problem(bot, key, value)
            if wrong:
                out.append((key, f"'{value}' is not a valid {key}: {wrong}"))
    return out

"""Bots and instances.

A bot (a Character here, to keep it apart from the running thing) is who it
is: its name in the game, its personality, its settings. It lives in
bots/<bot>/: bot.json and personality.txt.

An instance is a bot on a server, and it is what runs: its own game folder,
its HeadlessMC, its logs, its port, extra mods, and settings of its own that
win over the bot's. It lives in instances/<name>/, and instance.json says
which bot and which server.

Plus the lock that keeps two launcher commands off one instance at a time.
"""
import contextlib
import json
import re
import threading
import urllib.request

from .events import Fail
from .files import read_pid, try_lock
from .workspace import FIRST_PORT

NAME_RULE = re.compile(r"^[A-Za-z0-9_]{1,16}$")
KEY_RULE = re.compile(r"^[a-z0-9_][a-z0-9_-]{0,31}$")


def check_name(name):
    """Minecraft's rules for a player name, not a whim: up to 16 characters,
    letters, digits and underscore. An invalid name does not fail when the
    bot is created, it fails when it JOINS, minutes later, when the error is
    hard to connect to the cause."""
    if not name or not re.match(r"^[A-Za-z0-9_]*$", name):
        raise Fail(f"'{name}' is not a valid name: only letters, digits and underscore.",
                   code="bad_name")
    if len(name) > 16:
        raise Fail(f"'{name}' has {len(name)} characters; Minecraft allows 16.", code="bad_name")


def check_key(key, what="bot"):
    """A bot's name in the launcher: its folder. Lowercase letters, digits,
    underscore and dash (a clone of alice is alice-1)."""
    if not KEY_RULE.match(key or ""):
        raise Fail(f"'{key}' is not a valid {what} name here: lowercase letters, digits, "
                   "underscore and dash, up to 32.", code="bad_name")


def read_json(path):
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}
    except (OSError, ValueError) as e:
        raise Fail(f"{path} cannot be read: {e}", code="bad_json")
    if not isinstance(data, dict):
        raise Fail(f"{path} is not a JSON object.", code="bad_json")
    return data


def write_json(path, data):
    """Whole or not at all: written next to it, then renamed over it."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    tmp.replace(path)


class Character:
    """A bot: bots/<key>/bot.json and personality.txt."""

    def __init__(self, ws, key):
        self.ws = ws
        self.key = key.lower()
        self.dir = ws.bots_dir / self.key
        self.json = self.dir / "bot.json"
        self.personality = self.dir / "personality.txt"

    def __repr__(self):
        return f"Character({self.key!r})"

    def __eq__(self, other):
        return isinstance(other, Character) and other.dir == self.dir

    def __hash__(self):
        return hash(self.dir)

    def exists(self):
        return self.json.is_file()

    def require(self):
        if not self.exists():
            raise Fail(f"there is no bot {self.key} (no {self.json}).", code="no_bot")
        return self

    @property
    def data(self):
        return read_json(self.json)

    def save(self, data):
        write_json(self.json, data)

    @property
    def own_name(self):
        """The name it was given, which is its name in the game when it plays
        offline."""
        return self.data.get("name") or self.key

    @property
    def name(self):
        """Its name in the game, with its capitals: its account's player when
        it plays with one of the launcher's accounts."""
        return _account_name(self) or self.own_name

    def instances(self):
        return [i for i in self.ws.instances() if i.data.get("bot") == self.key]


class Instance:
    """A bot on a server: instances/<key>/, with instance.json naming both."""

    def __init__(self, ws, key):
        self.ws = ws
        self.key = key.lower()
        self.dir = ws.instances_dir / self.key
        self.json = self.dir / "instance.json"
        self.hmc = self.dir / "hmc"
        self.gamedir = self.dir / "gamedir"
        self.run = self.dir / "run"
        self.extra_mods = self.dir / "mods"

    def __repr__(self):
        return f"Instance({self.key!r})"

    def __eq__(self, other):
        return isinstance(other, Instance) and other.dir == self.dir

    def __hash__(self):
        return hash(self.dir)

    @property
    def id(self):
        return self.key

    def exists(self):
        return self.json.is_file()

    def require(self):
        if not self.exists():
            raise Fail(f"there is no instance {self.key}.", code="no_instance")
        return self

    @property
    def data(self):
        return read_json(self.json)

    def save(self, data):
        write_json(self.json, data)

    @property
    def slug(self):
        return self.data.get("server", "")

    @property
    def bot(self):
        return self.ws.bot(self.data.get("bot") or self.key)

    @property
    def name(self):
        """The player name in the game, with its capitals: its account's player
        when it plays with one of the launcher's accounts, else the bot's."""
        return _account_name(self) or (self.bot.own_name if self.bot.exists() else self.key)

    @property
    def player(self):
        return self.name.lower()

    @property
    def port(self):
        try:
            return int(self.data.get("port"))
        except (TypeError, ValueError):
            return FIRST_PORT

    def read(self, file, default=""):
        """One of the flat files rendered here for the bridge (see settings.render)."""
        try:
            return (self.dir / file).read_text(encoding="utf-8").strip()
        except OSError:
            return default

    # run/: what a running instance leaves behind, and nothing a person edits.
    @property
    def keeper_pid_f(self): return self.run / "keeper.pid"
    @property
    def keeper_port_f(self): return self.run / "keeper.port"
    @property
    def client_pid_f(self): return self.run / "client.pid"
    @property
    def client_log(self): return self.run / "client.log"
    @property
    def keeper_log(self): return self.run / "keeper.log"
    @property
    def bridge_pid_f(self): return self.run / "bridge.pid"
    @property
    def bridge_log(self): return self.run / "bridge.log"

    # Its server's state folder: what its bridge keeps (sessions, channels,
    # jobs), per player, and the place that says which instance plays as
    # which player there right now.
    @property
    def state(self):
        return self.ws.server_state(self.slug)

    @property
    def place(self):
        """state/servers/<slug>/bots/<player>: a link to the instance that
        plays as <player> on that server now. The bridge's bots folder is
        that `bots/`: the bots of its server, as it always read them."""
        return self.state / "bots" / self.player

    @property
    def bridge_lock(self):
        # The bridge's own lock (mcp/bridge.py, only_one_bridge): it writes
        # its pid inside, which makes it the truth about a bridge started by
        # hand, without this launcher.
        return self.state / f"bridge_{self.player}.lock"

    def guards(self):
        """The guards of its dependency group, when it leads one."""
        from .groups import guards_of
        return [g for g in guards_of(self) if g != self]

    def ask(self, route, timeout=5):
        """One question to the bot mod itself, on its own port."""
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}{route}", timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8", "replace"))


def _account_name(target):
    """The player of the account that applies to a bot or an instance, when it
    is one of the launcher's accounts and it has been logged in."""
    from . import settings
    from .accounts import kind
    value = settings.get(target, "account")
    if kind(value) != "account":
        return None
    account = target.ws.account(value)
    return account.data.get("name") if account.exists() else None


_OPERATING = {}            # lock path -> (thread that holds it, open handle)
_GUARD = threading.Lock()


@contextlib.contextmanager
def operating(inst):
    """One launcher command at a time on an instance. Two `start`s of the
    same instance run side by side (two terminals; a double click, the day
    there is a window) both find no keeper and both launch a 3 GB java. The
    pid file cannot prevent that, it is written seconds later; a lock taken
    before looking can.

    Re-entrant for the thread that holds it (`restart` holds it through its
    stop and its start), and only for that one: a window runs operations on
    threads of its own, and a second thread is a second command."""
    lock = inst.run / "launcher.lock"
    me = threading.get_ident()
    with _GUARD:
        held = _OPERATING.get(str(lock))
        if held and held[0] == me:
            handle = None
        elif held:
            raise Fail(f"another operation on {inst.id} is running in this program", code="busy")
        else:
            handle = try_lock(lock)
            if handle is None:
                raise Fail(f"another launcher command is working on {inst.id} right now "
                           f"(pid {read_pid(lock) or '?'}). Wait for it, or see  marionette.py status",
                           code="busy")
            _OPERATING[str(lock)] = (me, handle)
    if handle is None:
        yield
        return
    try:
        yield
    finally:
        with _GUARD:
            del _OPERATING[str(lock)]
        handle.close()

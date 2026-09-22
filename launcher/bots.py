"""A bot: its folder, its files, its name, and the lock that keeps two
launcher commands off it at once."""
import contextlib
import json
import re
import threading
import urllib.request

from .events import Fail
from .files import read_java_properties, read_pid, try_lock
from .workspace import FIRST_PORT

NAME_RULE = re.compile(r"^[A-Za-z0-9_]{1,16}$")


def check_name(name):
    """Minecraft's rules, not a whim: up to 16 characters, letters, digits and
    underscore. An invalid name does not fail when the bot is created, it
    fails when it JOINS, minutes later, when the error is hard to connect to
    the cause."""
    if not name or not re.match(r"^[A-Za-z0-9_]*$", name):
        raise Fail(f"'{name}' is not a valid name: only letters, digits and underscore.",
                   code="bad_name")
    if len(name) > 16:
        raise Fail(f"'{name}' has {len(name)} characters; Minecraft allows 16.", code="bad_name")


class Bot:
    def __init__(self, ws, name):
        self.ws = ws
        self.given = name
        self.key = name.lower()
        self.dir = ws.bots_dir / self.key
        self.hmc = self.dir / "hmc"
        self.gamedir = self.dir / "gamedir"
        self.run = self.dir / "run"

    def __repr__(self):
        return f"Bot({self.name!r})"

    def exists(self):
        return self.dir.is_dir()

    def require(self):
        if not self.exists():
            raise Fail(f"{self.dir} does not exist. Create it first:  "
                       f"marionette.py create {self.given} <server>", code="no_bot")
        return self

    def read(self, file, default=""):
        try:
            return (self.dir / file).read_text(encoding="utf-8").strip()
        except OSError:
            return default

    def write(self, file, value):
        (self.dir / file).write_text(f"{value}\n", encoding="utf-8")

    @property
    def name(self):
        """The name with its capitals, from the hmc config, which is what the
        game and the bridge use; the folder is lowercase."""
        cfg = read_java_properties(self.hmc / "HeadlessMC" / "config.properties")
        return cfg.get("hmc.offline.username") or self.given

    @property
    def port(self):
        try:
            return int(self.read("port"))
        except ValueError:
            return FIRST_PORT

    # run/: what a running bot leaves behind, and nothing a person edits.
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
    @property
    def bridge_lock(self):
        # The bridge's own lock (mcp/bridge.py, only_one_bridge): it writes
        # its pid inside, which makes it the truth about a bridge started by
        # hand, without this launcher.
        return self.ws.home / ".marionette" / f"bridge_{self.key}.lock"

    def guards(self):
        """The bots whose `escort` file names this one."""
        return [g for g in self.ws.bots() if g.read("escort").lower() == self.key]

    def ask(self, route, timeout=5):
        """One question to the bot mod itself, on its own port."""
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}{route}", timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8", "replace"))


_OPERATING = {}            # lock path -> (thread that holds it, open handle)
_GUARD = threading.Lock()


@contextlib.contextmanager
def operating(bot):
    """One launcher command at a time on a bot. Two `start`s of the same bot
    run side by side (two terminals; a double click, the day there is a
    window) both find no keeper and both launch a 3 GB java. The pid file
    cannot prevent that, it is written seconds later; a lock taken before
    looking can.

    Re-entrant for the thread that holds it (`restart` holds it through its
    stop and its start), and only for that one: a window runs operations on
    threads of its own, and a second thread is a second command."""
    lock = bot.run / "launcher.lock"
    me = threading.get_ident()
    with _GUARD:
        held = _OPERATING.get(str(lock))
        if held and held[0] == me:
            handle = None
        elif held:
            raise Fail(f"another operation on {bot.name} is running in this program",
                       code="busy")
        else:
            handle = try_lock(lock)
            if handle is None:
                raise Fail(f"another launcher command is working on {bot.name} right now "
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

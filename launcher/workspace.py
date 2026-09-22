"""Where everything is.

Four folders, outside the repo because they are heavy and are not code:

    servers/<slug>/        one server: how to get in, and ITS pack of client mods
    accounts/<account>/    a Minecraft account, logged in once
    bots/<bot>/            a character: its name, personality and settings
    instances/<name>/      a bot on a server, which is what runs: its game, its
                           HeadlessMC, its logs and its own settings
    shared/                what every instance uses: HeadlessMC and the Marionette mods

plus server.env, the way to the server mod's API; the state folder, where
each server's bridges keep their sessions and channels; and the environment
variables that override the defaults. All of it held by one Workspace object
instead of module globals resolved at import: a window can change a folder
without restarting, and a test gets a workspace of its own.

Instances are named freely and cloned freely: two of them may be the same
bot on the same server. What cannot happen is both RUNNING, since that is one
player joining twice; `start` is where that is refused (see operations).
"""
import os
import pathlib
from dataclasses import dataclass

from .api import ServerApi
from .events import Fail
from .files import read_env_file
from .processes import port_in_use

FIRST_PORT = 8478          # 8477 belongs to the SERVER mod
DEFAULT_VERSION = "neoforge-21.1.248"
DEFAULT_HEAP = "3g"


@dataclass(frozen=True)
class Server:
    """One server of the registry: servers/<slug>/server.conf and its pack."""
    slug: str
    pack: pathlib.Path
    host: str
    mc_port: str
    version: str
    description: str

    @property
    def mods_dir(self):
        return self.pack / "mods"

    @property
    def env_file(self):
        """Its own way to its server mod (MARIONETTE_HOST, MARIONETTE_PORT,
        MARIONETTE_TOKEN), when it has one. Apart from server.conf on
        purpose: server.conf can be shown and passed around, the token never."""
        return self.pack / "server.env"

    @property
    def address(self):
        """25565 goes unsaid because it is the default; any other port is spelled out."""
        return self.host if self.mc_port == "25565" else f"{self.host}:{self.mc_port}"

    def mod_count(self):
        return len(list(self.mods_dir.glob("*.jar")))


class Workspace:
    def __init__(self, bots_dir, servers_dir, shared_dir, env_file, home=None, environ=None,
                 instances_dir=None, state_dir=None, accounts_dir=None):
        self.bots_dir = pathlib.Path(bots_dir)
        self.servers_dir = pathlib.Path(servers_dir)
        self.shared_dir = pathlib.Path(shared_dir)
        self.env_file = pathlib.Path(env_file)
        self.home = pathlib.Path(home) if home else pathlib.Path.home()
        self.environ = dict(os.environ if environ is None else environ)
        self.instances_dir = pathlib.Path(instances_dir) if instances_dir else self.bots_dir.parent / "instances"
        # Next to server.env by default, which is where the bridge and the MCP
        # server always kept their state (~/.marionette).
        self.state_dir = pathlib.Path(state_dir) if state_dir else self.env_file.parent
        self.accounts_dir = pathlib.Path(accounts_dir) if accounts_dir else self.bots_dir.parent / "accounts"

    @classmethod
    def from_environment(cls, environ=None, home=None):
        """The folders as this machine says: the environment first, then
        server.env, so one file can hold everything; then the defaults next
        to the home."""
        environ = dict(os.environ if environ is None else environ)
        home = pathlib.Path(home) if home else pathlib.Path.home()
        env_file = pathlib.Path(environ.get("MARIONETTE_ENV")
                                or home / ".marionette" / "server.env").expanduser()
        values = read_env_file(env_file)

        def pick(key, default):
            value = environ.get(key) or values.get(key)
            return pathlib.Path(value).expanduser() if value else default

        return cls(pick("MARIONETTE_BOTS_DIR", home / "bots"),
                   pick("MARIONETTE_SERVERS_DIR", home / "servers"),
                   pick("MARIONETTE_COMMON_DIR", home / "shared"),
                   env_file, home, environ,
                   instances_dir=pick("MARIONETTE_INSTANCES_DIR", home / "instances"),
                   state_dir=pick("MARIONETTE_STATE_DIR", env_file.parent),
                   accounts_dir=pick("MARIONETTE_ACCOUNTS_DIR", home / "accounts"))

    def __repr__(self):
        return (f"Workspace(bots={self.bots_dir}, instances={self.instances_dir}, "
                f"servers={self.servers_dir}, shared={self.shared_dir})")

    # --- what the environment overrides ---------------------------------------

    def java_command(self):
        """`java` from PATH, or whatever MARIONETTE_JAVA says: a machine whose
        default java is not 21 points this at the one that is."""
        custom = self.environ.get("MARIONETTE_JAVA")
        return [custom] if custom else ["java"]

    def heap(self):
        """The heap of a bot without a `heap` file of its own."""
        return self.environ.get("MARIONETTE_HEAP") or DEFAULT_HEAP

    def version_for(self, server):
        """The NeoForge version HeadlessMC launches: the server's, unless
        MARIONETTE_VERSION says otherwise for every server (a test of a new
        NeoForge, say)."""
        return self.environ.get("MARIONETTE_VERSION") or server.version

    def child_env(self):
        """What the keeper, the bridge and the MCP server inherit: the same
        folders this workspace resolved, whatever way it resolved them, and a
        native Claude Code install (~/.local/bin, updates itself) first on PATH."""
        env = dict(self.environ)
        env["MARIONETTE_BOTS_DIR"] = str(self.bots_dir)
        env["MARIONETTE_SERVERS_DIR"] = str(self.servers_dir)
        env["MARIONETTE_COMMON_DIR"] = str(self.shared_dir)
        env["MARIONETTE_INSTANCES_DIR"] = str(self.instances_dir)
        env["MARIONETTE_STATE_DIR"] = str(self.state_dir)
        env["MARIONETTE_ACCOUNTS_DIR"] = str(self.accounts_dir)
        env["MARIONETTE_ENV"] = str(self.env_file)
        local_bin = str(self.home / ".local" / "bin")
        env["PATH"] = local_bin + os.pathsep + env.get("PATH", "")
        return env

    # --- the server mod ---------------------------------------------------------

    def env_values(self):
        return read_env_file(self.env_file)

    # --- the launcher's own config ----------------------------------------------

    @property
    def config_file(self):
        """launcher.json, next to server.env: what applies to every instance
        (for now, the global rules; see rules.py)."""
        return self.env_file.parent / "launcher.json"

    def config(self):
        from .bots import read_json
        return read_json(self.config_file)

    def save_config(self, data):
        from .bots import write_json
        write_json(self.config_file, data)

    def api(self):
        """The connection to the server mod (MARIONETTE_HOST, MARIONETTE_PORT,
        MARIONETTE_TOKEN, optionally MARIONETTE_OWNER). Without them nothing can
        ask the server who is connected, so the launcher refuses to guess."""
        if not self.env_file.is_file():
            raise Fail(f"missing {self.env_file} (MARIONETTE_HOST, MARIONETTE_PORT, MARIONETTE_TOKEN).",
                       code="no_server_env")
        v = self.env_values()
        return ServerApi(v.get("MARIONETTE_HOST"), v.get("MARIONETTE_PORT"),
                         v.get("MARIONETTE_TOKEN"), v.get("MARIONETTE_OWNER"))

    def api_for(self, server):
        """The server mod of THIS server: its own servers/<slug>/server.env,
        or the global server.env when it has none. With one global file for
        several servers, a bot started on one was looked for in another's
        /players, not found, and reported as not joined."""
        if server.env_file.is_file():
            v = read_env_file(server.env_file)
            return ServerApi(v.get("MARIONETTE_HOST"), v.get("MARIONETTE_PORT"),
                             v.get("MARIONETTE_TOKEN"), self.env_values().get("MARIONETTE_OWNER"))
        return self.api()

    # --- servers ----------------------------------------------------------------

    def server_slugs(self):
        if not self.servers_dir.is_dir():
            return []
        return sorted(d.name for d in self.servers_dir.iterdir()
                      if (d / "server.conf").is_file())

    def server(self, slug):
        """HOST, MC_PORT, VERSION, DESCRIPTION and the pack folder of one server.
        Careful: server.conf must NOT use the variable PORT, which is the bot's
        own port in the launcher."""
        pack = self.servers_dir / slug
        conf = pack / "server.conf"
        if not conf.is_file():
            raise Fail(f"unknown server '{slug}'. These are the ones there are:",
                       lines=self.servers_listing(), code="unknown_server")
        values = read_env_file(conf)
        if not values.get("HOST"):
            raise Fail(f"{slug}/server.conf does not set HOST.", code="bad_server")
        return Server(slug=slug, pack=pack, host=values["HOST"],
                      mc_port=values.get("MC_PORT") or "25565",
                      version=values.get("VERSION") or DEFAULT_VERSION,
                      description=values.get("DESCRIPTION") or "")

    def servers(self):
        """Every server that reads well; a broken server.conf is left out (doctor names it)."""
        out = []
        for slug in self.server_slugs():
            try:
                out.append(self.server(slug))
            except Fail:
                continue
        return out

    def servers_listing(self):
        lines = [f"{s.slug:<14} {s.host + ':' + s.mc_port:<20} {s.mod_count():>3} mods  {s.description}"
                 for s in self.servers()]
        return lines or [f"(none: no servers/<slug>/server.conf under {self.servers_dir})"]

    def server_state(self, slug):
        """Where the bridges of one server keep their state: sessions, jobs
        left pending, the internal channel between bots, the marks the MCP
        leaves. Per server, because two instances of a bot on two servers are
        two different lives, and per bot name inside, which a server keeps
        unique."""
        return self.state_dir / "servers" / slug

    # --- accounts ---------------------------------------------------------------

    def account(self, key):
        from .accounts import Account
        return Account(self, key)

    def account_keys(self):
        if not self.accounts_dir.is_dir():
            return []
        return sorted(d.name for d in self.accounts_dir.iterdir() if (d / "account.json").is_file())

    # --- bots (characters) ------------------------------------------------------

    def bot(self, key):
        from .bots import Character
        return Character(self, key)

    def bot_keys(self):
        """The characters: folders of bots/ with a bot.json. A folder of the
        layout before instances has none (see `legacy_bots`)."""
        if not self.bots_dir.is_dir():
            return []
        return sorted(d.name for d in self.bots_dir.iterdir() if (d / "bot.json").is_file())

    def bots(self):
        return [self.bot(k) for k in self.bot_keys()]

    def legacy_bots(self):
        """Folders of bots/ still in the layout from before instances: the
        bot and its game in one folder (a `port` file, an `hmc/`)."""
        if not self.bots_dir.is_dir():
            return []
        return sorted(d.name for d in self.bots_dir.iterdir()
                      if d.is_dir() and not (d / "bot.json").is_file()
                      and ((d / "port").is_file() or (d / "hmc").is_dir()))

    # --- instances --------------------------------------------------------------

    def instance_keys(self):
        if not self.instances_dir.is_dir():
            return []
        return sorted(d.name for d in self.instances_dir.iterdir() if (d / "instance.json").is_file())

    def instances(self, slug=None):
        from .bots import Instance
        out = [Instance(self, k) for k in self.instance_keys()]
        return [i for i in out if i.slug == slug] if slug else out

    def instance(self, key):
        from .bots import Instance
        inst = Instance(self, key)
        if inst.exists():
            return inst
        hint = []
        if key.lower() in self.legacy_bots():
            hint = [f"{key} is in the layout from before instances: marionette.py migrate"]
        raise Fail(f"there is no instance {key}.",
                   lines=hint or ["these are: " + (", ".join(self.instance_keys()) or "(none yet)")],
                   code="no_instance")

    def free_key(self, wanted, taken):
        """`wanted`, or wanted-1, wanted-2... the first not in `taken`: what a
        clone is called."""
        if wanted not in taken:
            return wanted
        n = 1
        while f"{wanted}-{n}" in taken:
            n += 1
        return f"{wanted}-{n}"

    def port_reserved(self, port, me=None):
        """Busy is not enough: a stopped instance does not listen, but its port
        is still its own. Across every server: they may all run on this
        machine at once."""
        for inst in self.instances():
            if inst.key == me:
                continue
            if str(inst.data.get("port", "")) == str(port):
                return True
        return False

    def free_port(self, me=None, also_taken=()):
        for p in range(FIRST_PORT, FIRST_PORT + 100):
            if p in also_taken or p == 8477:
                continue
            if not self.port_reserved(p, me) and not port_in_use(p):
                return p
        raise Fail(f"no free ports from {FIRST_PORT} on. Something odd is going on.", code="no_port")

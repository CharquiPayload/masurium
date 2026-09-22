"""Where everything is.

Three folders, outside the repo because they are heavy and are not code:

    bots/<name>/      what belongs to each bot: port, server, gamedir, hmc, run/
    servers/<slug>/   one server and ITS pack of client mods
    shared/           what every bot uses: the launcher and the Marionette mods

plus server.env, the way to the server mod's API, and the environment
variables that override the defaults. All of it held by one Workspace object
instead of module globals resolved at import: a window can change a folder
without restarting, and a test gets a workspace of its own.
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
    def __init__(self, bots_dir, servers_dir, shared_dir, env_file, home=None, environ=None):
        self.bots_dir = pathlib.Path(bots_dir)
        self.servers_dir = pathlib.Path(servers_dir)
        self.shared_dir = pathlib.Path(shared_dir)
        self.env_file = pathlib.Path(env_file)
        self.home = pathlib.Path(home) if home else pathlib.Path.home()
        self.environ = dict(os.environ if environ is None else environ)

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
                   env_file, home, environ)

    def __repr__(self):
        return f"Workspace(bots={self.bots_dir}, servers={self.servers_dir}, shared={self.shared_dir})"

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
        env["MARIONETTE_ENV"] = str(self.env_file)
        local_bin = str(self.home / ".local" / "bin")
        env["PATH"] = local_bin + os.pathsep + env.get("PATH", "")
        return env

    # --- the server mod ---------------------------------------------------------

    def env_values(self):
        return read_env_file(self.env_file)

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

    # --- bots -------------------------------------------------------------------

    def bot(self, name):
        from .bots import Bot
        return Bot(self, name)

    def bot_keys(self):
        if not self.bots_dir.is_dir():
            return []
        return sorted(d.name for d in self.bots_dir.iterdir() if d.is_dir())

    def bots(self):
        return [self.bot(k) for k in self.bot_keys()]

    def name_clash(self, key):
        """A name that is a substring of another does not break the launcher but
        the bridge, which reacts when `NAME in text`: with "Ada" and "Adam" in the
        same chat, calling one answers with both. Better to refuse now than to
        find out in the chat."""
        for other in self.bot_keys():
            if other == key:
                continue
            if key in other or other in key:
                return other
        return None

    def port_reserved(self, port, me=None):
        """Busy is not enough: a stopped bot does not listen, but its port is
        still its own."""
        for key in self.bot_keys():
            if key == me:
                continue
            if self.bot(key).read("port") == str(port):
                return True
        return False

    def free_port(self, me=None):
        for p in range(FIRST_PORT, FIRST_PORT + 50):
            if not self.port_reserved(p, me) and not port_in_use(p):
                return p
        raise Fail(f"no free ports from {FIRST_PORT} on. Something odd is going on.", code="no_port")

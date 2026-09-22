#!/usr/bin/env python3
"""The launcher: one command to create, start, stop and watch bots.

    marionette.py servers
    marionette.py create <name> <server> [--account online|offline]
    marionette.py login <name>
    marionette.py start <name> [server]
    marionette.py connect <name>
    marionette.py bridge <name>
    marionette.py stop <name> [--keep-guards]
    marionette.py restart <name> [server]
    marionette.py status [name]
    marionette.py deploy-mod
    marionette.py doctor

Standard library only, and no shell. It replaced eight bash scripts that leaned
on FIFOs, `ss`, `pgrep` and `setsid`, none of which exist on Windows. Every
primitive here has an equivalent on every platform Python runs on, so porting
is testing, not rewriting: sockets instead of `ss`, pid files instead of
`pgrep`, `os.link` instead of `ln`, `urllib` instead of `curl`, and a process
of its own instead of `tail -f` on a named pipe.

Three folders, outside the repo because they are heavy and are not code:

    bots/<name>/      what belongs to each bot: port, server, gamedir, hmc, run/
    servers/<slug>/   one server and ITS pack of client mods
    shared/           what every bot uses: the launcher and the Marionette mods

And one rule: **the pack owns gamedir/mods**. A bot's mods are hard links to
the pack, so switching servers means deleting the links and making them again.
Deleting a hard link does NOT delete the pack's jar, which is why this can be
done lightly and without copying hundreds of MB per bot.

The client's console. HeadlessMC reads commands (`launch`, `connect`, `msg`)
from its stdin, and somebody has to hold that stdin open for as long as the
game runs. That somebody is the **keeper**: a small process per bot, started
here, that owns the java process and listens on a localhost socket. The CLI
and the bridge send it lines; it writes them to the game. Its pid file is also
what says "this bot is already running" before a second 3 GB java is started.
"""
import argparse
import json
import os
import pathlib
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
HOME = pathlib.Path.home()
WINDOWS = os.name == "nt"

FIRST_PORT = 8478          # 8477 belongs to the SERVER mod
DEFAULT_VERSION = "neoforge-21.1.248"
DEFAULT_HEAP = "3g"
HMC_READY = "HMC-Specifics initialized"


class Fail(Exception):
    """Something the user has to fix; the message is the whole story."""


# --- configuration files ------------------------------------------------------

def read_env_file(path):
    """KEY=VALUE lines, as a shell would read them but without running one:
    comments and blanks skipped, an `export ` prefix ignored, matching quotes
    around the value removed."""
    values = {}
    try:
        text = pathlib.Path(path).read_text(encoding="utf-8")
    except OSError:
        return values
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        if line.startswith("export "):
            line = line[len("export "):].lstrip()
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key] = value
    return values


def env_file():
    return pathlib.Path(os.environ.get("MARIONETTE_ENV")
                        or HOME / ".marionette" / "server.env").expanduser()


def _pick_dir(key, default):
    # The environment wins; then server.env, so one file can hold everything;
    # then the default next to the home.
    value = os.environ.get(key) or read_env_file(env_file()).get(key)
    return pathlib.Path(value).expanduser() if value else default


BOTS_DIR = _pick_dir("MARIONETTE_BOTS_DIR", HOME / "bots")
SERVERS_DIR = _pick_dir("MARIONETTE_SERVERS_DIR", HOME / "servers")
COMMON_DIR = _pick_dir("MARIONETTE_COMMON_DIR", HOME / "shared")


def child_env():
    """What the keeper, the bridge and the MCP server inherit: the same
    folders this process resolved, whatever way it resolved them, and a
    native Claude Code install (~/.local/bin, updates itself) first on PATH."""
    env = dict(os.environ)
    env["MARIONETTE_BOTS_DIR"] = str(BOTS_DIR)
    env["MARIONETTE_SERVERS_DIR"] = str(SERVERS_DIR)
    env["MARIONETTE_COMMON_DIR"] = str(COMMON_DIR)
    env["MARIONETTE_ENV"] = str(env_file())
    local_bin = str(HOME / ".local" / "bin")
    env["PATH"] = local_bin + os.pathsep + env.get("PATH", "")
    return env


def mod_env():
    """The connection to the server mod (MARIONETTE_HOST, MARIONETTE_PORT,
    MARIONETTE_TOKEN, optionally MARIONETTE_OWNER). Without them nothing can
    ask the server who is connected, so the launcher refuses to guess."""
    f = env_file()
    if not f.is_file():
        raise Fail(f"missing {f} (MARIONETTE_HOST, MARIONETTE_PORT, MARIONETTE_TOKEN).")
    values = read_env_file(f)
    return {
        "host": values.get("MARIONETTE_HOST") or "127.0.0.1",
        "port": values.get("MARIONETTE_PORT") or "8477",
        "token": values.get("MARIONETTE_TOKEN") or "",
        "owner": values.get("MARIONETTE_OWNER") or "",
    }


def server_slugs():
    if not SERVERS_DIR.is_dir():
        return []
    return sorted(d.name for d in SERVERS_DIR.iterdir()
                  if (d / "server.conf").is_file())


def load_server(slug):
    """HOST, MC_PORT, VERSION, DESCRIPTION and the pack folder of one server.
    Careful: server.conf must NOT use the variable PORT, which is the bot's
    own port in the launcher."""
    pack = SERVERS_DIR / slug
    conf = pack / "server.conf"
    if not conf.is_file():
        raise Fail(f"unknown server '{slug}'. These are the ones there are:\n"
                   + format_servers())
    values = read_env_file(conf)
    if not values.get("HOST"):
        raise Fail(f"{slug}/server.conf does not set HOST.")
    return {
        "slug": slug,
        "pack": pack,
        "host": values["HOST"],
        "mc_port": values.get("MC_PORT") or "25565",
        "version": values.get("VERSION") or DEFAULT_VERSION,
        "description": values.get("DESCRIPTION") or "",
    }


def format_servers():
    lines = []
    for slug in server_slugs():
        try:
            s = load_server(slug)
        except Fail:
            continue
        n = len(list((s["pack"] / "mods").glob("*.jar")))
        lines.append(f"  {slug:<14} {s['host'] + ':' + s['mc_port']:<20} "
                     f"{n:>3} mods  {s['description']}")
    return "\n".join(lines) if lines else f"  (none: no servers/<slug>/server.conf under {SERVERS_DIR})"


def address_of(server):
    """25565 goes unsaid because it is the default; any other port is spelled out."""
    if server["mc_port"] == "25565":
        return server["host"]
    return f"{server['host']}:{server['mc_port']}"


# --- the server mod's API -----------------------------------------------------

def mod_get(route, env=None):
    env = env or mod_env()
    req = urllib.request.Request(
        f"http://{env['host']}:{env['port']}{route}",
        headers={"X-Marionette-Token": env["token"]})
    with urllib.request.urlopen(req, timeout=5) as r:
        return r.read().decode("utf-8", "replace")


def players_text(env=None):
    try:
        return mod_get("/players", env)
    except (urllib.error.URLError, OSError, ValueError):
        return ""


def is_inside(name, env=None):
    """The server is the one to ask whether a bot is in, not the client log:
    the log says what the client believes, /players says what there is."""
    return f'"{name}"' in players_text(env)


def server_mods(env=None):
    """The mods the server loaded, {id: version}, from /mods. None when the
    server mod is older than that route, or does not answer."""
    try:
        data = json.loads(mod_get("/mods", env))
    except (urllib.error.URLError, OSError, ValueError):
        return None
    if not isinstance(data, dict) or not isinstance(data.get("mods"), list):
        return None
    return {m["id"]: str(m.get("version", "")) for m in data["mods"] if isinstance(m, dict) and "id" in m}


def bot_get(bot, route, timeout=5):
    """One question to the bot mod itself, on its own port."""
    with urllib.request.urlopen(f"http://127.0.0.1:{bot.port}{route}", timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


# --- what a pack is made of ---------------------------------------------------

MODS_TOML = "META-INF/neoforge.mods.toml"


def toml_mods(text, manifest_version=""):
    """modId -> version from a mods.toml, without a TOML parser: the two keys
    every [[mods]] block has, and the one placeholder that is common."""
    mods = {}
    current = None

    def flush():
        if current and "modId" in current:
            mods[current["modId"]] = current.get("version", "")

    for raw in text.splitlines():
        line = raw.strip()
        if line == "[[mods]]":
            flush()
            current = {}
            continue
        if line.startswith("["):
            flush()
            current = None
            continue
        if current is None or line.startswith("#") or "=" not in line:
            continue
        key, value = (s.strip() for s in line.split("=", 1))
        value = value.split("#", 1)[0].strip().strip("\"'")
        if key in ("modId", "version"):
            current[key] = value
    flush()
    for k, v in list(mods.items()):
        if v == "${file.jarVersion}":
            mods[k] = manifest_version
    return mods


def jar_mods(jar_path):
    """modId -> version for one jar, and for the jars inside it: Veil, for
    one, is never a jar of its own in a pack, it ships inside Sable and others,
    listed in their META-INF/jarjar/metadata.json."""
    import io
    import zipfile
    found = {}

    def read(z):
        names = set(z.namelist())
        manifest_version = ""
        if "META-INF/MANIFEST.MF" in names:
            for l in z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace").splitlines():
                if l.startswith("Implementation-Version:"):
                    manifest_version = l.split(":", 1)[1].strip()
        if MODS_TOML in names:
            found.update(toml_mods(z.read(MODS_TOML).decode("utf-8", "replace"), manifest_version))
        if "META-INF/jarjar/metadata.json" in names:
            try:
                meta = json.loads(z.read("META-INF/jarjar/metadata.json").decode("utf-8", "replace"))
            except ValueError:
                meta = {}
            for entry in meta.get("jars", []) if isinstance(meta, dict) else []:
                path = str(entry.get("path", ""))
                if path in names:
                    try:
                        with zipfile.ZipFile(io.BytesIO(z.read(path))) as inner:
                            read(inner)
                    except zipfile.BadZipFile:
                        pass

    try:
        with zipfile.ZipFile(jar_path) as z:
            read(z)
    except (OSError, zipfile.BadZipFile):
        pass
    return found


def pack_mods(pack):
    """Every mod a bot joins with from this pack: shared/mods and the pack's own."""
    mods = {}
    for source in (COMMON_DIR / "mods", pathlib.Path(pack) / "mods"):
        for jar in sorted(source.glob("*.jar")):
            mods.update(jar_mods(jar))
    return mods


def compare_packs(client, server):
    """What differs between a bot's pack and the server's mods. The version
    mismatches are the strong signal: the server rejects those, and its
    message names NeoForge instead. The other two lists are for reading: a
    mod only on the server may be server-side, and a mod only on the client
    (a renderer, a minimap) is the normal case."""
    return {
        "mismatch": sorted((i, client[i], server[i]) for i in client
                           if i in server and client[i] != server[i]),
        "server_only": sorted(i for i in server if i not in client),
        "client_only": sorted(i for i in client if i not in server),
    }


# --- bots ---------------------------------------------------------------------

NAME_RULE = re.compile(r"^[A-Za-z0-9_]{1,16}$")


def check_name(name):
    """Minecraft's rules, not a whim: up to 16 characters, letters, digits and
    underscore. An invalid name does not fail when the bot is created, it
    fails when it JOINS, minutes later, when the error is hard to connect to
    the cause."""
    if not re.match(r"^[A-Za-z0-9_]*$", name) or not name:
        raise Fail(f"'{name}' is not a valid name: only letters, digits and underscore.")
    if len(name) > 16:
        raise Fail(f"'{name}' has {len(name)} characters; Minecraft allows 16.")


def bot_keys():
    if not BOTS_DIR.is_dir():
        return []
    return sorted(d.name for d in BOTS_DIR.iterdir() if d.is_dir())


def name_clashes(key):
    """A name that is a substring of another does not break the launcher but
    the bridge, which reacts when `NAME in text`: with "Ada" and "Adam" in the
    same chat, calling one answers with both. Better to refuse now than to
    find out in the chat."""
    for other in bot_keys():
        if other == key:
            continue
        if key in other or other in key:
            return other
    return None


class Bot:
    def __init__(self, name):
        self.given = name
        self.key = name.lower()
        self.dir = BOTS_DIR / self.key
        self.hmc = self.dir / "hmc"
        self.gamedir = self.dir / "gamedir"
        self.run = self.dir / "run"

    def exists(self):
        return self.dir.is_dir()

    def require(self):
        if not self.exists():
            raise Fail(f"{self.dir} does not exist. Create it first:  "
                       f"marionette.py create {self.given} <server>")
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
        return HOME / ".marionette" / f"bridge_{self.key}.lock"

    def guards(self):
        """The bots whose `escort` file names this one."""
        out = []
        for key in bot_keys():
            g = Bot(key)
            if g.read("escort").lower() == self.key:
                out.append(g)
        return out


def read_java_properties(path):
    values = {}
    try:
        for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                values[k.strip()] = v.strip()
    except OSError:
        pass
    return values


# --- ports and processes ------------------------------------------------------

def port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", int(port))) == 0


def port_reserved(port, me=None):
    """Busy is not enough: a stopped bot does not listen, but its port is
    still its own."""
    for key in bot_keys():
        if key == me:
            continue
        if Bot(key).read("port") == str(port):
            return True
    return False


def free_port(me=None):
    for p in range(FIRST_PORT, FIRST_PORT + 50):
        if not port_reserved(p, me) and not port_in_use(p):
            return p
    raise Fail(f"no free ports from {FIRST_PORT} on. Something odd is going on.")


def read_pid(path):
    try:
        return int(pathlib.Path(path).read_text().split()[0])
    except (OSError, ValueError, IndexError):
        return None


def pid_alive(pid):
    if not pid:
        return False
    if WINDOWS:
        # os.kill(pid, 0) is NOT a probe on Windows: it terminates. Ask the
        # kernel for the exit code instead; 259 is STILL_ACTIVE.
        import ctypes
        from ctypes import wintypes
        k32 = ctypes.windll.kernel32
        handle = k32.OpenProcess(0x1000, False, pid)   # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            return False
        code = wintypes.DWORD()
        ok = k32.GetExitCodeProcess(handle, ctypes.byref(code))
        k32.CloseHandle(handle)
        return bool(ok) and code.value == 259
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def terminate(pid, grace=10):
    """Ask nicely, wait, then insist. Returns once the process is gone."""
    if not pid_alive(pid):
        return
    try:
        os.kill(pid, signal.SIGTERM)
    except OSError:
        return
    for _ in range(int(grace * 10)):
        if not pid_alive(pid):
            return
        time.sleep(0.1)
    try:
        os.kill(pid, getattr(signal, "SIGKILL", signal.SIGTERM))
    except OSError:
        pass
    time.sleep(0.5)


def own_group():
    """Popen keywords that put the child in a process group of its own, so
    the whole tree under it can be signalled at once (see kill_tree)."""
    if WINDOWS:
        return {"creationflags": subprocess.DETACHED_PROCESS
                | subprocess.CREATE_NEW_PROCESS_GROUP}
    return {"start_new_session": True}


def detached(args, log_path, cwd=None, env=None):
    """A process that outlives this one: its own session, its stdio on a log
    file and not on ours. Whoever started it from an SSH session that then
    dropped will be glad of it."""
    log = open(log_path, "ab")
    kw = dict(stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
              cwd=str(cwd) if cwd else None, env=env, **own_group())
    try:
        return subprocess.Popen(args, **kw)
    finally:
        log.close()


def kill_tree(pid, hard=False):
    """The process AND its children. HeadlessMC is a launcher: `launch` starts
    the game as a child java, and terminating the launcher alone leaves that
    child alive, holding the port, with nobody at its stdin. It happened.
    The launcher is started in its own group (own_group) precisely so the
    group is the tree."""
    if not pid:
        return
    if WINDOWS:
        cmd = ["taskkill", "/T", "/PID", str(pid)]
        if hard:
            cmd.insert(1, "/F")
        subprocess.run(cmd, capture_output=True)
        return
    sig = signal.SIGKILL if hard else signal.SIGTERM
    # Its group id is its own pid (own_group), and a group outlives its
    # leader: with the launcher already gone and the game still there, this
    # still reaches the game.
    try:
        os.killpg(pid, sig)
    except OSError:
        try:
            os.kill(pid, sig)
        except OSError:
            pass


def kill_game(bot, launcher_pid=None):
    """Everything that is this bot's client: the launcher's group if we know
    it, and whatever java carries the bot's port, found by name. Waits for
    the port to close, which is the one sign that the game is gone."""
    kill_tree(launcher_pid)
    for pid in game_pids(bot.port):
        try:
            os.kill(pid, signal.SIGTERM)
        except OSError:
            pass
    if wait_for(lambda: not port_in_use(bot.port), 25, every=0.5):
        return True
    kill_tree(launcher_pid, hard=True)
    for pid in game_pids(bot.port):
        try:
            os.kill(pid, getattr(signal, "SIGKILL", signal.SIGTERM))
        except OSError:
            pass
    return wait_for(lambda: not port_in_use(bot.port), 10, every=0.5)


def game_pids(port):
    """Every java carrying -Dmarionette.bot.port=<port> on its command line:
    the game itself, whoever started it. The last resort of `stop` and the
    truth for `status` when the keeper is gone. Linux reads /proc; elsewhere
    `ps` (POSIX) or `wmic` (Windows) say the same, more slowly."""
    needle = f"-Dmarionette.bot.port={int(port)}"
    found = []
    proc = pathlib.Path("/proc")
    if proc.is_dir():
        for d in proc.iterdir():
            if not d.name.isdigit():
                continue
            try:
                cmd = (d / "cmdline").read_bytes().replace(b"\0", b" ").decode("utf-8", "replace")
            except OSError:
                continue
            if needle in cmd and "java" in cmd:
                found.append(int(d.name))
        return found
    if WINDOWS:
        code, out = run_quiet(["wmic", "process", "where", "name like 'java%'",
                               "get", "ProcessId,CommandLine", "/format:list"])
        block = {}
        for line in (out or "").splitlines() + [""]:
            if "=" in line:
                k, v = line.split("=", 1)
                block[k.strip()] = v.strip()
            elif block:
                if needle in block.get("CommandLine", "") and block.get("ProcessId", "").isdigit():
                    found.append(int(block["ProcessId"]))
                block = {}
        return found
    code, out = run_quiet(["ps", "-eo", "pid=,args="])
    for line in (out or "").splitlines():
        parts = line.strip().split(None, 1)
        if len(parts) == 2 and needle in parts[1] and "java" in parts[1]:
            found.append(int(parts[0]))
    return found


def spawn_free(args, log_path, cwd=None, env=None):
    """detached(), through a middleman that exits at once. The process then
    belongs to nobody: a `start` still polling the server never holds a dead
    keeper as a zombie, so `stop`, run from elsewhere, sees it gone the moment
    it is. (Windows has no zombies; the middleman costs nothing there.)"""
    middle = detached([sys.executable, str(HERE / "marionette.py"), "spawn",
                       "--log", str(log_path), "--cwd", str(cwd or HOME), "--"] + list(args),
                      log_path, cwd=cwd, env=env)
    middle.wait(timeout=15)


def java_command():
    """`java` from PATH, or whatever MARIONETTE_JAVA says: a machine whose
    default java is not 21 points this at the one that is."""
    custom = os.environ.get("MARIONETTE_JAVA")
    return [custom] if custom else ["java"]


def wait_for(predicate, seconds, every=1.0):
    deadline = time.monotonic() + seconds
    while True:
        if predicate():
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(every)


def tail_lines(path, n, pattern=None, width=160):
    try:
        lines = pathlib.Path(path).read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []
    if pattern:
        rx = re.compile(pattern, re.I)
        lines = [l for l in lines if rx.search(l)]
    return [l[:width] for l in lines[-n:]]


def log_has(path, needle):
    try:
        return needle in pathlib.Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False


# --- mods ---------------------------------------------------------------------

def link_or_copy(src, dst):
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def sync_mods(gamedir, pack):
    """gamedir/mods is a MANAGED folder, not a drawer: it is rebuilt whole. A
    jar someone drops there is gone on the next sync."""
    mods = pathlib.Path(gamedir) / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    for old in mods.glob("*.jar"):
        old.unlink()
    n = 0
    for source in (COMMON_DIR / "mods", pathlib.Path(pack) / "mods"):
        for jar in sorted(source.glob("*.jar")):
            link_or_copy(jar, mods / jar.name)
            n += 1
    return n


# --- the keeper ---------------------------------------------------------------
#
# Protocol, one line per request, one line back: anything is written to the
# game's stdin as it came ("connect 1.2.3.4", "msg hello"), except lines
# starting with "@", which are for the keeper itself:
#
#   @ping   ->  "ok <client pid>"
#   @stop   ->  "stopping"      (the game is terminated and the keeper exits)

def launch_line(bot, server):
    """What HeadlessMC is told. Two things that cost a night when nobody knew
    them: NO -commands (that flag puts HeadlessMC's runtime inside the game,
    and then TWO consoles read the same stdin, stealing each other's lines),
    and -lwjgl, which removes rendering: no screen, no GPU.

    -Dmarionette.name is what makes this client a bot at all.
    -Dmarionette.bot.port: without it every bot would fight over 8478 and
    the second one would have no hands. What the body says on its own (a
    creeper next to whoever it escorts) does not go through the brain, so the
    mod gets the same language and gender the bridge uses; only clean values
    reach the JVM."""
    version = os.environ.get("VERSION") or server["version"]
    heap = os.environ.get("HEAP") or DEFAULT_HEAP
    offline = " -offline" if bot.read("account", "online") == "offline" else ""
    jvm = [f"-Xmx{heap}", f"-Dmarionette.name={bot.name}",
           "-Dmarionette.headless=true", f"-Dmarionette.bot.port={bot.port}"]
    language = re.sub(r"[^A-Za-z]", "", bot.read("language"))[:8]
    gender = re.sub(r"[^A-Za-z]", "", bot.read("gender"))[:1]
    if language:
        jvm.append(f"-Dmarionette.language={language}")
    if gender:
        jvm.append(f"-Dmarionette.gender={gender}")
    return f"launch {version} -lwjgl{offline} -paulscode --jvm \"{' '.join(jvm)}\""


def keeper_main(bot, server):
    bot.run.mkdir(parents=True, exist_ok=True)

    def note(m):
        print(time.strftime("%H:%M:%S"), m, flush=True)

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.bind(("127.0.0.1", 0))
    listener.listen(8)
    listener.settimeout(0.5)
    control_port = listener.getsockname()[1]

    client_log = open(bot.client_log, "ab")
    # In a group of its own: HeadlessMC starts the game as a child java, and
    # the group is how both are stopped together (kill_tree).
    game = subprocess.Popen(
        java_command() + ["-jar", "headlessmc-launcher.jar"],
        cwd=str(bot.hmc), stdin=subprocess.PIPE, stdout=client_log,
        stderr=subprocess.STDOUT, env=child_env(), **own_group())
    bot.keeper_pid_f.write_text(f"{os.getpid()}\n")
    bot.client_pid_f.write_text(f"{game.pid}\n")
    # The port file goes LAST: whoever sees it may talk to a keeper that is whole.
    bot.keeper_port_f.write_text(f"{control_port}\n")
    note(f"keeper of {bot.name}: game pid {game.pid}, control port {control_port}")

    def to_game(line):
        game.stdin.write((line + "\n").encode("utf-8"))
        game.stdin.flush()

    line = launch_line(bot, server)
    note(line)
    to_game(line)

    # HeadlessMC is a console around the game: when the game crashes at
    # startup it can stay at its prompt, alive, with nothing behind it. The
    # line it prints then is the sign that the game is gone, not the process.
    game_over = "Minecraft exited with code"
    stopping = False
    last_look = 0.0
    try:
        while game.poll() is None and not stopping:
            if time.monotonic() - last_look > 2:
                last_look = time.monotonic()
                if log_has(bot.client_log, game_over):
                    note("the game exited under the launcher; leaving too")
                    stopping = True
                    break
            try:
                conn, _ = listener.accept()
            except socket.timeout:
                continue
            with conn:
                conn.settimeout(5)
                try:
                    data = b""
                    while not data.endswith(b"\n"):
                        chunk = conn.recv(4096)
                        if not chunk:
                            break
                        data += chunk
                except OSError:
                    continue
                request = data.decode("utf-8", "replace").strip()
                if not request:
                    continue
                if request == "@ping":
                    reply = f"ok {game.pid}"
                elif request == "@stop":
                    reply = "stopping"
                    stopping = True
                elif request.startswith("@"):
                    reply = "unknown"
                else:
                    try:
                        to_game(request)
                        reply = "sent"
                    except OSError as e:
                        reply = f"error {e}"
                try:
                    conn.sendall((reply + "\n").encode("utf-8"))
                except OSError:
                    pass
    finally:
        # The launcher AND the game under it. A JVM asked to stop runs the
        # game's shutdown hooks first (it saves), which takes a while with a
        # world loaded; kill_game has the patience for that.
        if stopping or game.poll() is None or port_in_use(bot.port):
            note("terminating the game")
            if not kill_game(bot, game.pid):
                note(f"something still holds port {bot.port}: {game_pids(bot.port)}")
        try:
            game.wait(timeout=5)      # reap the launcher; a pid probe would see a zombie
        except subprocess.TimeoutExpired:
            pass
        note(f"game exited with {game.poll()}")
        client_log.close()
        listener.close()
        for f in (bot.keeper_port_f, bot.client_pid_f, bot.keeper_pid_f):
            try:
                f.unlink()
            except OSError:
                pass


def keeper_ask(bot, line, timeout=5):
    """One line to this bot's keeper; its one-line answer. None when there is
    no keeper to talk to."""
    port = read_pid(bot.keeper_port_f)
    if not port:
        return None
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout) as s:
            s.sendall((line + "\n").encode("utf-8"))
            s.settimeout(timeout)
            data = b""
            while not data.endswith(b"\n"):
                chunk = s.recv(256)
                if not chunk:
                    break
                data += chunk
            return data.decode("utf-8", "replace").strip()
    except OSError:
        return None


def keeper_alive(bot):
    """The keeper answers, and the game it holds is the pid it claims."""
    answer = keeper_ask(bot, "@ping")
    return bool(answer and answer.startswith("ok "))


def clear_run_files(bot):
    for f in (bot.keeper_port_f, bot.client_pid_f, bot.keeper_pid_f):
        try:
            f.unlink()
        except OSError:
            pass


# --- commands -----------------------------------------------------------------

def say(m):
    print(m, flush=True)


def cmd_servers(args):
    say("servers:")
    say(format_servers())
    return 0


def cmd_create(args):
    name, slug = args.name, args.server
    check_name(name)
    bot = Bot(name)
    account = args.account or os.environ.get("MARIONETTE_ACCOUNT") or "online"
    if account not in ("online", "offline"):
        raise Fail(f"--account must be online or offline, not '{account}'.")
    if bot.dir.exists():
        raise Fail(f"{bot.dir} already exists. To make it again, delete it yourself first.")
    clash = name_clashes(bot.key)
    if clash:
        raise Fail(f"'{name}' clashes with the bot '{clash}': one name contains the "
                   "other.\nthe bridge would mix them up in the chat. Choose another name.")
    server = load_server(slug)
    launcher_jar = COMMON_DIR / "headlessmc-launcher.jar"
    if not (COMMON_DIR / "mods").is_dir() or not launcher_jar.is_file():
        raise Fail(f"missing {COMMON_DIR}: it holds the launcher and the Marionette mods.")
    port = free_port(bot.key)

    say(f"==> creating {name}  (server {slug}, port {port})")
    (bot.gamedir / "mods").mkdir(parents=True)
    (bot.hmc / "HeadlessMC").mkdir(parents=True)
    link_or_copy(launcher_jar, bot.hmc / "headlessmc-launcher.jar")
    # The only thing that CANNOT be shared is the hmc folder: the name used to
    # join the game is fixed in its config.properties, so there is one per bot.
    (bot.hmc / "HeadlessMC" / "config.properties").write_text(
        "hmc.jline.enabled=false\n"
        f"hmc.offline={'true' if account == 'offline' else 'false'}\n"
        f"hmc.offline.username={name}\n"
        "hmc.invert.command.modifiers=false\n"
        f"hmc.gamedir={bot.gamedir}\n", encoding="utf-8")
    bot.write("port", port)
    bot.write("server", slug)
    bot.write("account", account)
    # The language the bot speaks in the chat: en or es.
    bot.write("language", "en")
    # Its character, in its own file from minute one. A template instead of
    # an empty file, because a bot without a written character sounds like a
    # manual. Editing this is all it takes for this bot not to sound like the
    # others.
    (bot.dir / "personality.txt").write_text(
        f"You are {name}. Write here who you are: how you talk, what you care about, who\n"
        "you trust, what makes you laugh. In second person and in a few lines: this\n"
        "goes at the start of the prompt, before the body's instructions.\n\n"
        "For now: you talk plainly, correct and direct, without flourishes.\n",
        encoding="utf-8")
    # The owner: whose delicate orders the bot accepts, and who can shut it
    # down, restart it and manage its lists with /marionette bot on any
    # server. It is born with the server owner, if the environment names one;
    # to give the bot to someone else, write their EXACT player name here and
    # restart the bridge.
    owner = read_env_file(env_file()).get("MARIONETTE_OWNER", "")
    if owner:
        bot.write("owner", owner)
    n = sync_mods(bot.gamedir, server["pack"])
    say(f"==> {n} mods linked from {server['pack'] / 'mods'} and {COMMON_DIR / 'mods'}")
    say(f"==> files you may want to edit in {bot.dir}: personality.txt, language (en/es),")
    say("    owner (a player name), model, escort (makes it a guard of that bot).")
    say("")
    if account == "online":
        say(f"log in its Minecraft account once:  marionette.py login {name}")
        say(f"then start it with:                 marionette.py start {name}")
    else:
        say("offline account: only for private servers with online-mode=false.")
        say(f"start it with:  marionette.py start {name}")
    return 0


def cmd_login(args):
    """Online bots use a real, purchased Minecraft Java account (a Microsoft
    account), like any player. HeadlessMC keeps the login in the bot's own hmc
    folder, so each bot has its own account. This opens HeadlessMC
    interactively: type `login`, follow its instructions, and `quit`."""
    bot = Bot(args.name).require()
    if bot.read("account") == "offline":
        say(f"{bot.name} uses an offline account; there is nothing to log in.")
        return 0
    say(f"==> HeadlessMC for {bot.name}. Type:  login   (then follow the instructions)")
    say("    and when the account is saved:  quit")
    return subprocess.call(java_command() + ["-jar", "headlessmc-launcher.jar"],
                           cwd=str(bot.hmc), env=child_env())


def prepare_gamedir(bot, server):
    """Everything the mod reads from the gamedir before it starts. ALWAYS
    rewritten, not only when switching servers: add a mod to a server's pack
    and the bots would keep joining with the old one, which the server rejects
    with "Incompatible client! Please use NeoForge ...", nothing like the real
    cause. Rebuilding costs a second; not doing it costs a mysterious
    disconnection."""
    config = bot.gamedir / "config"
    config.mkdir(parents=True, exist_ok=True)
    # The mod has no way of knowing WHICH server it joined (they may all share
    # an address and port): the launcher tells it. Per-server memories
    # (places, chests, orders...) are keyed on this.
    (config / "marionette-server.txt").write_text(server["slug"] + "\n", encoding="utf-8")
    escort = re.sub(r"\s", "", bot.read("escort"))
    escort_f = config / "marionette-escort.txt"
    if escort:
        escort_f.write_text(escort + "\n", encoding="utf-8")
    else:
        try:
            escort_f.unlink()
        except OSError:
            pass
    # The game's first-run accessibility prompt sits in front of the title
    # screen until somebody clicks, and nobody ever will: it is turned off in
    # options.txt, which the game reads on start. Every other option is left
    # as it is.
    options = bot.gamedir / "options.txt"
    try:
        lines = options.read_text(encoding="utf-8").splitlines()
    except OSError:
        lines = []
    kept = [l for l in lines if not l.startswith("onboardAccessibility:")]
    if kept != lines or not lines or "onboardAccessibility:false" not in lines:
        options.write_text("\n".join(kept + ["onboardAccessibility:false"]) + "\n", encoding="utf-8")
    n = sync_mods(bot.gamedir, server["pack"])
    bot.write("server", server["slug"])
    return n


def join(bot, server, env, attempts, patience=18):
    """Send `connect` and wait for the server to list the bot. Each attempt
    waits `patience` × 10 s."""
    address = address_of(server)
    say(f"==> connecting to {server['slug']} ({address})")
    for attempt in range(1, attempts + 1):
        answer = keeper_ask(bot, f"connect {address}")
        if answer != "sent":
            say(f"    the keeper did not take the command ({answer}); is the client alive?")
            return False
        for i in range(1, patience + 1):
            time.sleep(10)
            if is_inside(bot.name, env):
                say(f"==> {bot.name} is IN (attempt {attempt}, {i * 10}s)")
                return True
        say(f"    attempt {attempt} failed")
    return False


def crash_report(bot):
    """The crash report the game said it saved, if this run crashed."""
    for l in tail_lines(bot.client_log, 400, r"Crash report saved to:"):
        m = re.search(r"Crash report saved to: #@!@# (.+\.txt)", l)
        if m and pathlib.Path(m.group(1).strip()).is_file():
            return pathlib.Path(m.group(1).strip())
    return None


def explain_crash(bot):
    """If this run crashed, say where the report is and what it says; True
    when there was one. The game said why, in its own report; its last
    lines are a mod list that says nothing."""
    report = crash_report(bot)
    if not report:
        return False
    # A mod that refused to construct is the FIRST thing to look at: the game
    # goes on to draw the loading-error screen, and what finally crashes is
    # whatever draws it, so the report names the wrong thing. The refusal
    # and its reason are in the log, a few lines apart.
    for l in refusals(bot):
        say("    " + l)
    say(f"==> the game crashed: {report}")
    for l in crash_summary(report):
        say("    " + l)
    return True


def refusals(bot, width=220):
    """The mods that failed to construct, each with the line that says why."""
    try:
        lines = bot.client_log.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []
    out = []
    for i, l in enumerate(lines):
        if "Failed to create mod instance" not in l:
            continue
        out.append(l.strip()[:width])
        for follow in lines[i + 1:i + 4]:
            if "Exception" in follow or "Error" in follow:
                out.append("  " + follow.strip()[:width])
                break
    return out


def crash_summary(report, width=220):
    """What a person reads first in a crash report: the description, the
    exception, and the mod frames nearest the top, which are the ones that
    name the culprit. The mod list at the end says nothing."""
    try:
        lines = report.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []
    out = []
    plumbing = re.compile(r"minecraft@|neoforged|java\.base|modlauncher|bootstraplauncher|securejarhandler")
    for l in lines[:200]:
        s = l.strip()
        if l.startswith("Description:") or s.startswith("Caused by"):
            out.append(s)
        elif re.search(r"Exception|Error:|Missing|requires", s) and len(out) < 6:
            out.append(s)
        elif s.startswith("at ") and not plumbing.search(s) and len(out) < 12:
            out.append(s)
        if len(out) >= 14:
            break
    return [l[:width] for l in out]


def complaints(bot):
    """Fail out loud, with the reason at hand. Since the pack comes from the
    registry, suspect number one is that it does not match the server's: that
    shows up as a mod rejection, not as "could not connect"."""
    return tail_lines(bot.client_log, 8,
                      r"disconnect|kick|refused|timed out|Unknown host|failed|"
                      r"mod rejections|incompatible|missing mods|negotiation")


def cmd_start(args):
    bot = Bot(args.name).require()
    slug = args.server or bot.read("server")
    if not slug:
        raise Fail(f"{bot.name} has no server noted. Tell me which one it joins:\n"
                   + format_servers())
    server = load_server(slug)
    env = mod_env()
    clash = name_clashes(bot.key)
    if clash:
        raise Fail(f"the name '{bot.name}' clashes with the bot '{clash}': one contains "
                   "the other.\nthe bridge would mix them up in the chat. Choose another name.")

    say(f"==> preparing the mods of {slug}")
    say(f"    {prepare_gamedir(bot, server)} mods")
    # Said now, from /mods, instead of by the server three minutes from now
    # with a message that names NeoForge. Not refused: some mods take a
    # version they were not built with, and whoever runs this may know.
    theirs = server_mods(env)
    if theirs is not None:
        diff = compare_packs(pack_mods(server["pack"]), theirs)
        if diff["mismatch"]:
            say("==> the server runs other versions than this pack; expect a rejection:")
            for mod_id, mine, its in diff["mismatch"]:
                say(f"    {mod_id}: pack {mine}, server {its}")

    if is_inside(bot.name, env):
        say(f"{bot.name} is already in. Nothing to do.")
        return 0
    # A keeper that answers holds a live client: either this same bot loaded
    # but not connected, or a start that was cut halfway. Starting anyway
    # would launch a second 3 GB java, which is how the OOM killer gets
    # invited.
    answer = keeper_ask(bot, "@ping")
    if answer and answer.startswith("ok "):
        if not port_in_use(bot.port) and log_has(bot.client_log, "Minecraft exited with code"):
            # A keeper around a game that already died: not a running bot.
            say("==> a keeper was left holding a game that had exited; stopping it first")
            keeper_ask(bot, "@stop")
            wait_for(lambda: not pid_alive(read_pid(bot.keeper_pid_f)), 30, every=0.5)
        else:
            raise Fail(f"{bot.name} is already running (keeper pid {read_pid(bot.keeper_pid_f)}, "
                       f"game pid {answer[3:]}) but not in the server.\n"
                       f"try:  marionette.py connect {bot.name}   or   marionette.py stop {bot.name}")
    if port_in_use(bot.port):
        holders = game_pids(bot.port)
        raise Fail(f"port {bot.port} is already taken by a live client this launcher does not "
                   f"hold (pids {holders or 'unknown'}).\n"
                   f"marionette.py stop {bot.name} takes it down, or give this bot another port.")
    clear_run_files(bot)

    say(f"==> starting {bot.name} (port {bot.port})")
    bot.run.mkdir(parents=True, exist_ok=True)
    # Fresh logs: the signs waited for below ("initialized", "game exited")
    # must be this start's and not the last one's.
    for stale in (bot.client_log, bot.keeper_log):
        try:
            stale.unlink()
        except OSError:
            pass
    spawn_free([sys.executable, str(HERE / "marionette.py"), "keeper", bot.name, slug],
               bot.keeper_log, cwd=HOME, env=child_env())

    say("==> loading the game")
    # The right signal is NOT that the process exists: it is that the mod has
    # registered its commands. Sending `connect` before that talks to nobody.
    def ready_or_dead():
        # A game that died takes its pid file with it (the keeper cleans up).
        return log_has(bot.client_log, HMC_READY) or log_has(bot.keeper_log, "game exited")

    wait_for(ready_or_dead, 300, every=5)
    if not log_has(bot.client_log, HMC_READY):
        say("==> the hmc-specifics mod did not initialize. Without it there is no connect.")
        if not explain_crash(bot):
            if bot.read("account", "online") == "online":
                say(f"    (online account: if HeadlessMC asked for a login, run marionette.py login {bot.name})")
            for l in tail_lines(bot.client_log, 5) + tail_lines(bot.keeper_log, 3):
                say("    " + l)
        return 1
    # The bot can join the server without hands: if the mod could not open
    # its port, `connect` works anyway and the failure only shows much later,
    # when an order does nothing. Better to know here.
    if not wait_for(lambda: port_in_use(bot.port), 20, every=2):
        say(f"==> the bot mod did not open port {bot.port}. It would join without hands.")
        # A mod that refused to construct (a missing add-on, say) shows up
        # here first: the game goes on loading without it, and crashes a
        # little later with the reason in its report.
        wait_for(lambda: crash_report(bot) is not None, 15, every=3)
        if not explain_crash(bot):
            for l in tail_lines(bot.client_log, 5, r"marionette_bot|address already in use|BindException"):
                say("    " + l)
        return 1

    # `connect` sent while the game is still loading talks to nobody, and an
    # attempt was lost on every start. The mod says which screen it is on and
    # whether the loading overlay is still up; a mod older than that says
    # nothing, and then the old way, straight in, is all there is.
    def readiness():
        try:
            return bot_get(bot, "/version")
        except (OSError, ValueError):
            return {}

    if "screen" in readiness():
        say("==> waiting for the title screen")

        def at_title():
            v = readiness()
            # The title screen itself, and not the first-run prompt that can
            # stand in front of it (prepare_gamedir turns that one off).
            return "Title" in str(v.get("screen", "")) and not v.get("loading") and not v.get("in_world")

        if wait_for(at_title, 180, every=3):
            say(f"    {readiness().get('screen')}")
        else:
            say(f"    (no title screen after 3 minutes, on {readiness().get('screen')!r}; trying anyway)")

    if join(bot, server, env, attempts=3):
        say(players_text(env))
        return 0
    say("==> it did NOT join. Last complaints of the client:")
    for l in complaints(bot):
        say("    " + l)
    n = len(list((bot.gamedir / "mods").glob("*.jar")))
    say(f"==> it joined with the '{slug}' pack ({n} mods). If the running server is not "
        "that one, there is the reason.")
    return 1


def cmd_connect(args):
    """Puts back on the server a bot whose client is ALIVE at the title screen
    (after /marionette bot <bot> logoff, or a failed connect). It starts
    nothing, and on purpose it does NOT switch servers: switching servers
    means switching packs, and the pack is only rebuilt by `start`."""
    bot = Bot(args.name).require()
    slug = bot.read("server")
    if not slug:
        raise Fail(f"{bot.name} has no server noted.")
    server = load_server(slug)
    env = mod_env()
    if not keeper_alive(bot):
        raise Fail(f"no live client of {bot.name}: use  marionette.py start {bot.name}")
    if is_inside(bot.name, env):
        say(f"{bot.name} is already in. Nothing to do.")
        return 0
    if join(bot, server, env, attempts=3, patience=12):
        return 0
    say(f"==> it did NOT join. If the client is hung:  marionette.py restart {bot.name}")
    return 1


def bridge_pid(bot):
    """The bridge's pid: from our pid file, or from the lock the bridge itself
    writes, which also covers one started by hand."""
    for f in (bot.bridge_pid_f, bot.bridge_lock):
        pid = read_pid(f)
        if pid_alive(pid):
            return pid
    return None


def cmd_bridge(args):
    """Starts the bridge, detached. It goes AFTER the client, and only if it
    joined: without a body in the game it has nobody to write to."""
    bot = Bot(args.name).require()
    pid = bridge_pid(bot)
    if pid:
        say(f"==> the bridge of {bot.name} is already running (pid {pid})")
        return 0
    bot.run.mkdir(parents=True, exist_ok=True)
    try:
        bot.bridge_log.unlink()
    except OSError:
        pass
    env = child_env()
    env["BOT_NAME"] = bot.name
    say(f"==> starting the bridge of {bot.name}")
    spawn_free([sys.executable, str(REPO / "mcp" / "bridge.py"), bot.name],
               bot.bridge_log, cwd=HOME, env=env)
    # The sign that it started is its own "listening" line, not that a
    # process exists: the bridge can exist and be dying. The log does not lie.
    # Its pid comes from the lock it writes (see bridge_pid).
    if wait_for(lambda: log_has(bot.bridge_log, "listening"), 10, every=1):
        first = tail_lines(bot.bridge_log, 1000)[:1]
        say(f"==> bridge alive: {first[0] if first else ''}")
        say(f"==> log at {bot.bridge_log}")
        return 0
    say(f"==> the bridge did NOT start. {bot.name} is in the game but MUTE:")
    for l in tail_lines(bot.bridge_log, 20):
        say("    " + l)
    return 1


def stop_bot(bot, keep_guards=False):
    """Stops a bot: the client (through its keeper) and its bridge. Processes
    are found by what identifies THAT bot and no other: its own pid files. A
    bare kill by pattern would take down every bot, which is exactly what
    must not happen when there are two."""
    say(f"==> stopping {bot.name} (port {bot.port})")
    keeper = read_pid(bot.keeper_pid_f)
    launcher = read_pid(bot.client_pid_f)
    answer = keeper_ask(bot, "@stop")
    if answer == "stopping":
        # The keeper gives the game 25 s to shut down on its own before it
        # kills it, then leaves; a little more than that here.
        if wait_for(lambda: not pid_alive(keeper) and not port_in_use(bot.port), 45, every=0.5):
            say("==> client: stopped")
        else:
            say("==> client: the keeper did not leave nicely; insisting")
            kill_game(bot, launcher)
            terminate(keeper)
    elif pid_alive(launcher) or pid_alive(keeper) or game_pids(bot.port):
        # No keeper answering, but something of the client is there: a keeper
        # that died, or a game started some other way on this bot's port.
        kill_game(bot, launcher)
        terminate(keeper)
        say("==> client: stopped (the keeper was not answering)")
    else:
        say("==> client: nothing was running")
    clear_run_files(bot)

    pid = bridge_pid(bot)
    if pid:
        terminate(pid)
        say("==> bridge: stopped")
    else:
        say("==> bridge: nothing was running")
    try:
        bot.bridge_pid_f.unlink()
    except OSError:
        pass
    say(f"==> {bot.name} stopped")

    # Its guards leave with it: a guard without a boss has nothing to do. A
    # RESTART is not a disconnection: the guards stay.
    if not keep_guards:
        for g in bot.guards():
            say(f"==> {g.name} is a guard of {bot.name}: stopping it too")
            stop_bot(g, keep_guards=False)


def cmd_stop(args):
    stop_bot(Bot(args.name).require(), keep_guards=args.keep_guards)
    return 0


def cmd_restart(args):
    """Restarts a WHOLE bot: client and bridge. It exists because of an easy
    mistake: after deploying a new mod both processes get killed, but only
    the client gets started again. The bot stays in the game, visible in
    /players, and mute, which from the chat looks exactly like a hang."""
    bot = Bot(args.name).require()
    stop_bot(bot, keep_guards=True)
    say("")
    if cmd_start(args) != 0:
        say("==> the client did not join; NOT starting the bridge.")
        return 1
    say("")
    return cmd_bridge(args)


def status_of(bot, env):
    keeper = keeper_alive(bot)
    game = pid_alive(read_pid(bot.client_pid_f)) or bool(game_pids(bot.port))
    hands = port_in_use(bot.port)
    inside = is_inside(bot.name, env) if env else None
    bridge = bridge_pid(bot)
    return {"name": bot.name, "server": bot.read("server") or "-", "port": bot.port,
            "keeper": keeper, "game": game, "hands": hands, "inside": inside,
            "bridge": bridge, "guard_of": bot.read("escort")}


def cmd_status(args):
    keys = [Bot(args.name).require().key] if args.name else bot_keys()
    if not keys:
        say(f"no bots under {BOTS_DIR}")
        return 0
    try:
        env = mod_env()
        server_reachable = bool(players_text(env)) or mod_get("/players", env) is not None
    except (Fail, Exception):
        env, server_reachable = None, False
    if env and not server_reachable:
        say(f"(the server mod at {env['host']}:{env['port']} does not answer; "
            "'in server' is unknown)")
    yes_no = lambda v: "-" if v is None else ("yes" if v else "no")
    say(f"  {'bot':<16} {'server':<14} {'port':<5} {'client':<7} {'hands':<6} "
        f"{'in server':<10} {'bridge':<7} {'guard of'}")
    for key in keys:
        s = status_of(Bot(key), env)
        say(f"  {s['name']:<16} {s['server']:<14} {s['port']:<5} "
            f"{yes_no(s['keeper'] or s['game']):<7} {yes_no(s['hands']):<6} "
            f"{yes_no(s['inside']):<10} {('pid ' + str(s['bridge'])) if s['bridge'] else 'no':<7} "
            f"{s['guard_of']}")
    return 0


# The core is marionette-<version>.jar; an add-on is marionette-<name>-<version>.jar.
CORE_JAR = re.compile(r"^marionette-\d")


def jar_family(name):
    """What is left of a jar's name before its version: `marionette` for the
    core, `marionette-veil` for that add-on. Two jars of one family declare
    the same mod, and only one may stay."""
    m = re.match(r"^(.*?)-\d", name)
    return m.group(1) if m else name.removesuffix(".jar")


def cmd_deploy_mod(args):
    """Puts a freshly built jar in shared/mods WITHOUT overwriting the one in
    use. Each bot's mods are HARD LINKS to the jar in shared/mods, and a copy
    over that jar writes into the same inode: a running client sees the zip it
    has open change and, as soon as it needs a class it had not loaded yet,
    dies with NoClassDefFoundError. A rename instead creates a new inode: the
    link in the gamedir of the running bot still points at the old jar and
    the bot does not notice. On its next start, the sync links it to the new
    one. Without an argument it deploys the core; with one, that jar (an
    add-on, say)."""
    if args.jar:
        source = pathlib.Path(args.jar).expanduser()
        if not source.is_file():
            raise Fail(f"{source} is not a file")
    else:
        built = sorted(p for p in (REPO / "mod" / "build" / "libs").glob("marionette-*.jar")
                       if "-sources" not in p.name and CORE_JAR.match(p.name))
        if not built:
            raise Fail("no marionette jar in mod/build/libs: build it first")
        source = built[0]
    family = jar_family(source.name)
    target_dir = COMMON_DIR / "mods"
    target_dir.mkdir(parents=True, exist_ok=True)
    target = target_dir / source.name
    tmp = target.with_name(target.name + ".new")
    shutil.copy2(source, tmp)
    os.replace(tmp, target)
    # A jar left behind would declare the same mod twice and the game would
    # refuse to start. Only the SAME family goes: the core never takes an
    # add-on with it, nor the other way round. For the core that covers the
    # OLD TWO-FILE NAMES as well, marionette-bot-*.jar and
    # marionette-server-*.jar. Unlinking is as safe as the rename: running
    # bots keep their own link.
    gone = []
    for old in target_dir.glob("*.jar"):
        if old == target:
            continue
        same = jar_family(old.name) == family
        legacy = family == "marionette" and re.match(r"^marionette-(bot|server)-\d", old.name)
        if same or legacy:
            old.unlink()
            gone.append(old.name)
    say(f"==> deployed {target.stat().st_size} bytes to {target} (new inode)")
    if gone:
        say(f"    out: {', '.join(gone)}")
    say("    bots already in the game keep the old jar until they restart")
    if family == "marionette":
        say("")
        say("    The SAME file goes in the Minecraft server's mods folder, and the old")
        say("    marionette-bot-*.jar / marionette-server-*.jar have to come out of it:")
        say("    two jars declaring marionette_bot means the game does not start.")
    return 0


# --- doctor -------------------------------------------------------------------

# Third-party mods a headless bot cannot run without an add-on, and the add-on's
# jar family. The same table lives in the bot mod (Bot.ADDON_FOR), which refuses
# to start without it; here it is caught before a 3 GB java is launched.
ADDON_FOR = {"veil": "marionette-veil"}


def pack_carries(pack, mod_id):
    """The jars of a pack that are, or carry inside them, the mod with this id."""
    return [jar.name for jar in sorted((pathlib.Path(pack) / "mods").glob("*.jar"))
            if mod_id in jar_mods(jar)]


def run_quiet(args, timeout=20):
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout + r.stderr).strip()
    except (OSError, subprocess.TimeoutExpired) as e:
        return None, str(e)


def java_major(output):
    m = re.search(r'version "(\d+)', output)
    return int(m.group(1)) if m else None


def free_memory_gb():
    try:
        if WINDOWS:
            import ctypes
            from ctypes import wintypes

            class Status(ctypes.Structure):
                _fields_ = [("dwLength", wintypes.DWORD), ("dwMemoryLoad", wintypes.DWORD),
                            ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                            ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                            ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                            ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]
            st = Status()
            st.dwLength = ctypes.sizeof(Status)
            ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(st))
            return st.ullAvailPhys / 2 ** 30
        for line in pathlib.Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) / 2 ** 20
    except (OSError, ValueError, AttributeError):
        pass
    return None


def heap_gb(text):
    m = re.match(r"^(\d+)([gGmM])$", text.strip())
    if not m:
        return None
    n, unit = int(m.group(1)), m.group(2).lower()
    return n if unit == "g" else n / 1024


def doctor_checks():
    """Every check is (label, ok, detail). ok is True, False, or None for a
    warning. The order is the order in which things break: what is missing
    first, what would fail later last."""
    checks = []

    def add(label, ok, detail=""):
        checks.append((label, ok, detail))

    v = sys.version_info
    add("python 3.9+", v >= (3, 9), f"{v.major}.{v.minor}.{v.micro}")

    code, out = run_quiet(java_command() + ["-version"])
    major = java_major(out) if code == 0 else None
    add("java 21", major == 21,
        f"{' '.join(java_command())}: {out.splitlines()[0] if out else 'not found'}"
        + ("" if major in (None, 21) else "  (NeoForge 21.1 wants 21; MARIONETTE_JAVA can point at one)"))

    claude = shutil.which("claude", path=child_env()["PATH"])
    if claude:
        code, out = run_quiet([claude, "--version"])
        add("claude code", code == 0, out.splitlines()[0] if out else claude)
    else:
        add("claude code", False, "not on PATH (nor in ~/.local/bin): the brain has nothing to run")

    f = env_file()
    env = None
    theirs = None
    if f.is_file():
        values = read_env_file(f)
        missing = [k for k in ("MARIONETTE_HOST", "MARIONETTE_PORT", "MARIONETTE_TOKEN")
                   if not values.get(k)]
        add("server.env", not missing, f"{f}" + (f": missing {', '.join(missing)}" if missing else ""))
        if not missing:
            env = mod_env()
            if env["host"] != "127.0.0.1" and not env["token"]:
                add("server mod token", False, "the API listens beyond localhost with no token")
            try:
                text = mod_get("/players", env)
                add("server mod answers", True, f"{env['host']}:{env['port']} -> {text.strip()[:60]}")
                theirs = server_mods(env)
                add("server mod lists its mods", True if theirs else None,
                    f"{len(theirs)} mods" if theirs else "no /mods route: a server mod older than 1.0.0")
            except urllib.error.HTTPError as e:
                add("server mod answers", False, f"{env['host']}:{env['port']} said HTTP {e.code}"
                    + (" (wrong token?)" if e.code in (401, 403) else ""))
            except (urllib.error.URLError, OSError) as e:
                add("server mod answers", False,
                    f"{env['host']}:{env['port']}: {getattr(e, 'reason', e)} (server down, or not that host/port)")
    else:
        add("server.env", False, f"{f} does not exist")

    add("shared/headlessmc-launcher.jar", (COMMON_DIR / "headlessmc-launcher.jar").is_file(), str(COMMON_DIR))
    mods = COMMON_DIR / "mods"
    hmc = list(mods.glob("hmc-specifics*.jar")) if mods.is_dir() else []
    add("shared/mods/hmc-specifics", bool(hmc), hmc[0].name if hmc else "the `connect` command comes from it")
    every = sorted(mods.glob("marionette-*.jar")) if mods.is_dir() else []
    marionette = [j for j in every if CORE_JAR.match(j.name)]
    if len(marionette) == 1:
        add("shared/mods/marionette", True, marionette[0].name)
    elif not marionette:
        add("shared/mods/marionette", False, "no marionette-<version>.jar: build it and run deploy-mod")
    else:
        add("shared/mods/marionette", False,
            "more than one: " + ", ".join(j.name for j in marionette) + " (two jars declaring the same mod)")
    addons = [j for j in every if not CORE_JAR.match(j.name)]
    families = {}
    for j in addons:
        families.setdefault(jar_family(j.name), []).append(j.name)
    twice = {f: n for f, n in families.items() if len(n) > 1}
    if twice:
        add("shared/mods add-ons", False,
            "; ".join(f"{f}: {', '.join(n)}" for f, n in twice.items()) + " (two jars of one add-on)")
    elif addons:
        add("shared/mods add-ons", True, ", ".join(j.name for j in addons))

    slugs = server_slugs()
    add("servers registered", bool(slugs), ", ".join(slugs) if slugs else f"nothing under {SERVERS_DIR}")
    for slug in slugs:
        try:
            s = load_server(slug)
        except Fail as e:
            add(f"servers/{slug}", False, str(e))
            continue
        n = len(list((s["pack"] / "mods").glob("*.jar")))
        add(f"servers/{slug}", True, f"{address_of(s)} {s['version']} {n} client mods")
        # Against the server that answers /mods (one at a time answers, and it
        # may not be this slug's: only mismatches are reported, and a pack
        # that shares nothing with it has none).
        if theirs:
            diff = compare_packs(pack_mods(s["pack"]), theirs)
            if diff["mismatch"]:
                add(f"servers/{slug}: versions", False,
                    "; ".join(f"{i}: pack {a}, server {b}" for i, a, b in diff["mismatch"][:6])
                    + (" ..." if len(diff["mismatch"]) > 6 else ""))
            else:
                shared_ids = len([i for i in pack_mods(s["pack"]) if i in theirs])
                add(f"servers/{slug}: versions", True,
                    f"{shared_ids} mods in common with the server that answers, same versions")
        # A mod that needs an add-on on a headless bot, with the add-on missing:
        # the client would crash at startup, before the mod handshake.
        for mod_id, addon in ADDON_FOR.items():
            carriers = pack_carries(s["pack"], mod_id)
            if carriers and not any(jar_family(j.name) == addon for j in every):
                add(f"servers/{slug}: {mod_id}", False,
                    f"carried by {', '.join(carriers)}; a headless bot needs {addon}-<version>.jar "
                    f"in shared/mods (see addons/)")

    keys = bot_keys()
    add("bots", True if keys else None, ", ".join(keys) if keys else f"none yet under {BOTS_DIR}")
    seen_ports = {}
    for key in keys:
        bot = Bot(key)
        problems = []
        port = bot.read("port")
        if not port.isdigit():
            problems.append("no port")
        elif port in seen_ports:
            problems.append(f"port {port} also belongs to {seen_ports[port]}")
        else:
            seen_ports[port] = key
        if not bot.read("server"):
            problems.append("no server")
        if not (bot.hmc / "HeadlessMC" / "config.properties").is_file():
            problems.append("no hmc config")
        clash = name_clashes(key)
        if clash:
            problems.append(f"name contains or is contained by '{clash}'")
        if bot.read("account", "online") == "online" and not (bot.hmc / "HeadlessMC" / "auth").exists():
            problems.append("online account never logged in (marionette.py login)")
        escort = bot.read("escort").lower()
        if escort and escort not in keys:
            problems.append(f"escorts '{escort}', which is not a bot here")
        add(f"bots/{key}", not problems, "; ".join(problems) or f"port {port}, server {bot.read('server')}")

    heap = heap_gb(os.environ.get("HEAP") or DEFAULT_HEAP)
    avail = free_memory_gb()
    if heap and avail is not None and keys:
        need = heap * len(keys)
        add("memory for every bot at once", need <= avail if need > avail * 0.9 else True,
            f"{len(keys)} bots x {heap:g} GB heap = {need:g} GB; {avail:.1f} GB available"
            + ("" if need <= avail else " (not all of them at once)"))
    return checks


def cmd_doctor(args):
    checks = doctor_checks()
    bad = 0
    for label, ok, detail in checks:
        mark = "ok " if ok else ("!! " if ok is False else "-- ")
        bad += ok is False
        say(f"  {mark} {label:<34} {detail}")
    say("")
    say("everything checks out" if not bad else f"{bad} problem(s) above")
    return 1 if bad else 0


def cmd_keeper(args):
    bot = Bot(args.name).require()
    keeper_main(bot, load_server(args.server))
    return 0


def cmd_spawn(args):
    command = args.args[1:] if args.args[:1] == ["--"] else args.args
    detached(command, args.log, cwd=args.cwd, env=dict(os.environ))
    return 0


# --- entry point --------------------------------------------------------------

def build_parser():
    p = argparse.ArgumentParser(
        prog="marionette.py",
        description="Create, start, stop and watch Marionette bots.")
    sub = p.add_subparsers(dest="command", metavar="command")
    sub.required = True

    sub.add_parser("servers", help="list the servers in the registry").set_defaults(fn=cmd_servers)

    c = sub.add_parser("create", help="create a bot, ready to start")
    c.add_argument("name")
    c.add_argument("server", help="a slug from `servers`")
    c.add_argument("--account", choices=("online", "offline"),
                   help="online (default: a purchased account, logged in once) "
                        "or offline (private servers with online-mode=false)")
    c.set_defaults(fn=cmd_create)

    c = sub.add_parser("login", help="log a bot's Minecraft account in, once")
    c.add_argument("name")
    c.set_defaults(fn=cmd_login)

    c = sub.add_parser("start", help="start a bot's client and put it on the server")
    c.add_argument("name")
    c.add_argument("server", nargs="?", help="switch it to this server (rebuilds its mods)")
    c.set_defaults(fn=cmd_start)

    c = sub.add_parser("connect", help="put a live client back on its server")
    c.add_argument("name")
    c.set_defaults(fn=cmd_connect)

    c = sub.add_parser("bridge", help="start a bot's bridge (its client must be in)")
    c.add_argument("name")
    c.set_defaults(fn=cmd_bridge)

    c = sub.add_parser("stop", help="stop a bot: client, bridge and its guards")
    c.add_argument("name")
    c.add_argument("--keep-guards", action="store_true", help="leave its guards running")
    c.set_defaults(fn=cmd_stop)

    c = sub.add_parser("restart", help="stop and start a whole bot: client and bridge")
    c.add_argument("name")
    c.add_argument("server", nargs="?")
    c.set_defaults(fn=cmd_restart)

    c = sub.add_parser("status", help="what every bot is doing")
    c.add_argument("name", nargs="?")
    c.set_defaults(fn=cmd_status)

    c = sub.add_parser("deploy-mod", help="put a built jar in shared/mods, safely (the core by default)")
    c.add_argument("jar", nargs="?", help="a jar to deploy instead of the core, such as an add-on's")
    c.set_defaults(fn=cmd_deploy_mod)
    sub.add_parser("doctor", help="check the machine, the folders and the server").set_defaults(fn=cmd_doctor)

    c = sub.add_parser("keeper", help=argparse.SUPPRESS)   # internal: started by `start`
    c.add_argument("name")
    c.add_argument("server")
    c.set_defaults(fn=cmd_keeper)

    c = sub.add_parser("spawn", help=argparse.SUPPRESS)    # internal: see spawn_free
    c.add_argument("--log", required=True)
    c.add_argument("--cwd", required=True)
    c.add_argument("args", nargs=argparse.REMAINDER)
    c.set_defaults(fn=cmd_spawn)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        return args.fn(args) or 0
    except Fail as e:
        say(str(e))
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())

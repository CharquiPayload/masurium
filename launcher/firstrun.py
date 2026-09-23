"""A machine's first bot: what it still needs, and doing for it what can be done.

    masurium.py setup     asks in the terminal
    the window            asks in a dialog when it opens and something is missing

Both on these functions. What can be done here is done here: HeadlessMC and
hmc-specifics downloaded from their own releases and checked against the
checksums of the versions Masurium was tested with; the Masurium jars that came
with the program put in shared/mods; server.env written, and kept private; the
first server registered. What cannot be done from here (Java 21, Claude Code) is
checked, and it is said how to get it.

A launcher updated brings newer jars than the bots have: setup puts them in too
(older_jars), and the window offers it, but nothing swaps them by itself: the
server needs the same core jar, and a bot newer than its server no longer gets in.
"""
import hashlib
import os
import re
import shutil
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass

from . import operations
from .api import UNREACHABLE, ServerApi
from .doctor import env_keys, java_major
from .events import Cancelled, Fail, report_to
from .files import write_private
from .packs import CORE_JAR, jar_family, jar_version
from .processes import run_quiet
from .workspace import DEFAULT_VERSION


@dataclass(frozen=True)
class Download:
    name: str
    target: str        # where it goes, under shared/
    url: str
    sha256: str
    size: int


# The versions Masurium was tested with, exactly: HeadlessMC starts the bots'
# games, and hmc-specifics is the mod its `connect` command comes from.
HEADLESSMC = Download(
    "HeadlessMC 2.10.0", "headlessmc-launcher.jar",
    "https://github.com/headlesshq/headlessmc/releases/download/2.10.0/headlessmc-launcher-2.10.0.jar",
    "52bd5006f478377b3893011d458562977d38c65ead6d2b31089beb4d614f13cd", 13010386)
HMC_SPECIFICS = Download(
    "hmc-specifics 2.4.0 for NeoForge 1.21.1", "mods/hmc-specifics-1.21.1-neoforge.jar",
    "https://github.com/headlesshq/hmc-specifics/releases/download/2.4.0/"
    "hmc-specifics-1.21.1-2.4.0-neoforge-release.jar",
    "e821604248d9fdb43fbe217616ba62ac7fad22af8937570dc8b8010f2051de79", 1847964)
DOWNLOADS = (HEADLESSMC, HMC_SPECIFICS)

JAVA_HOW = ("the bots' games run on it: on Ubuntu or Debian  sudo apt install openjdk-21-jre-headless, on Fedora  "
            "sudo dnf install java-21-openjdk-headless, on Arch  sudo pacman -S jre21-openjdk-headless; "
            "or set MASURIUM_JAVA to a Java 21 you already have")
CLAUDE_HOW = ("it is the bots' brain: install it with  curl -fsSL https://claude.ai/install.sh | bash  and "
              "run `claude` once to sign in")
SERVER_ENV_KEYS = ("MASURIUM_HOST", "MASURIUM_PORT", "MASURIUM_TOKEN", "MASURIUM_OWNER")
SLUG = re.compile(r"^[a-z0-9][a-z0-9_-]{0,31}$")
HOST = re.compile(r"^[A-Za-z0-9._:\[\]-]+$")
PLAYER = re.compile(r"^[A-Za-z0-9_]{3,16}$")


@dataclass(frozen=True)
class Need:
    """One thing a machine needs before its first bot. `detail` says what
    there is, or what to do; `can` is True when setup can do it itself."""
    key: str
    label: str
    ok: bool
    detail: str
    can: bool = False


# --- what there is -------------------------------------------------------------

def _present(ws, d):
    target = ws.shared_dir / d.target
    if d is HMC_SPECIFICS:
        mods = ws.shared_dir / "mods"
        return any(mods.glob("hmc-specifics*.jar")) if mods.is_dir() else False
    return target.is_file()


def masurium_in_shared(ws):
    mods = ws.shared_dir / "mods"
    return sorted(j.name for j in mods.glob("masurium-*.jar") if CORE_JAR.match(j.name)) if mods.is_dir() else []


def bundled_jars():
    """The Masurium jars that came with this program: jars/ in an installed
    one or a release, or what a clone of the repository built (the mod and its
    add-ons). The core first."""
    root = operations.REPO
    found = list((root / "jars").glob("masurium*.jar"))
    if not found:
        found = list((root / "mod" / "build" / "libs").glob("masurium-*.jar"))
        found += list(root.glob("addons/*/build/libs/masurium-*.jar"))
    found = [j for j in found if "-sources" not in j.name]
    return sorted(found, key=lambda j: (not CORE_JAR.match(j.name), j.name))


def _newer_than(jar, others):
    """Whether one of `others` is the same mod as `jar`, and newer."""
    mine = jar_version(jar.name)
    return bool(mine) and any(jar_family(o.name) == jar_family(jar.name) and (jar_version(o.name) or ()) > mine
                              for o in others)


def older_jars(ws):
    """The Masurium jars the bots run that this launcher brings newer, as
    (the name in shared/mods, the jar it brings) pairs, the core first. An
    updated launcher leaves the bots on the jars they had until these are put
    in (put_mods). Only mods the bots have: a missing core is setup's
    ordinary job, and a jar put in by hand, newer than the launcher's, is no
    older one."""
    mods = ws.shared_dir / "mods"
    there = sorted(mods.glob("masurium-*.jar")) if mods.is_dir() else []
    brought = bundled_jars()
    out = []
    for jar in brought:
        if _newer_than(jar, brought):
            continue                       # a clone's build folder with an old build left in it
        mine = jar_version(jar.name)
        theirs = [t for t in there if jar_family(t.name) == jar_family(jar.name)]
        if mine and theirs and all((jar_version(t.name) or mine) < mine for t in theirs):
            out.append((theirs[-1].name, jar))
    return out


LOCAL = ("127.0.0.1", "localhost", "::1")


def server_env_missing(ws):
    """The keys server.env still lacks (the owner is optional, and so is the
    token for a server on this same machine)."""
    values = ws.env_values() if ws.env_file.is_file() else {}
    return [k for k in env_keys(values) if not values.get(k)]


def needs(ws):
    """Everything a first bot needs, in the order setup does it."""
    out = []
    java = ws.java_command()
    code, text = run_quiet(java + ["-version"])
    major = java_major(text) if code == 0 else None
    out.append(Need("java", "Java 21", major == 21,
                    (text.splitlines()[0] + ("" if java == ["java"] else f" ({java[0]})") if major == 21 else
                     (f"found Java {major}; " if major else "no Java found; ") + JAVA_HOW)))
    claude = shutil.which("claude", path=ws.child_env()["PATH"])
    out.append(Need("claude", "Claude Code", bool(claude), claude or CLAUDE_HOW))
    for d in DOWNLOADS:
        there = _present(ws, d)
        out.append(Need(f"download:{d.target}", d.name, there,
                        str(ws.shared_dir / d.target) if there else "setup downloads it", can=not there))
    mine = masurium_in_shared(ws)
    bundled = bundled_jars()
    out.append(Need("masurium", "The Masurium mod", bool(mine),
                    ", ".join(mine) if mine else
                    ("setup puts in the one that came with the launcher" if bundled else
                     "no jar came with this launcher: download masurium-<version>.jar from the releases, or "
                     "build it (mod/: ./gradlew build), then  masurium.py deploy-mod <jar>"),
                    can=bool(bundled) and not mine))
    older = older_jars(ws)
    if older:
        out.append(Need("newer_jars", "The Masurium mod, newer", False, older_detail(older), can=True))
    lacking = server_env_missing(ws)
    out.append(Need("server_env", "The server's Masurium mod", not lacking,
                    f"{ws.env_file}" if not lacking else
                    "its address, and the port (8477) and token of its masurium.properties",
                    can=bool(lacking)))
    slugs = ws.server_slugs()
    out.append(Need("server", "A server to play on", bool(slugs),
                    ", ".join(slugs) if slugs else "its game address and a name for it", can=not slugs))
    return out


def older_detail(older, who="setup"):
    """What older_jars found, said: the bots' jars, the launcher's, and the
    server's part in it when the core is among them."""
    core = [jar for _, jar in older if CORE_JAR.match(jar.name)]
    return (f"the bots run {', '.join(name for name, _ in older)}; this launcher brings "
            f"{', '.join(jar.name for _, jar in older)}: {who} puts them in"
            + (f". The server needs the same jar, or the bots do not get in: {core[0]} goes in its mods "
               "folder, in place of the old one" if core else ""))


def missing(ws):
    return [n for n in needs(ws) if not n.ok]


def ready(ws):
    """Whether this machine has what its first bot needs, the things setup
    can do included: what the window checks to decide whether to offer it.
    Newer jars are not wanting: the bots run on the ones they have, and the
    window offers those on their own."""
    return not [n for n in needs(ws) if not n.ok and n.key not in ("java", "claude", "newer_jars")]


# --- doing it ------------------------------------------------------------------

def download(ws, d, on_event=None, cancel=None, opener=None):
    """One download, streamed into a file beside its target and checked
    before it takes the target's place: a cut download or a changed file
    never ends up where the launcher would run it."""
    report = report_to(on_event)
    target = ws.shared_dir / d.target
    target.parent.mkdir(parents=True, exist_ok=True)
    part = target.with_name(target.name + ".part")
    report.step(f"downloading {d.name}", stage="download")
    report.detail(d.url)
    digest = hashlib.sha256()
    got = 0
    request = urllib.request.Request(d.url, headers={"User-Agent": "masurium-launcher"})
    try:
        with (opener or urllib.request.urlopen)(request, timeout=30) as r, open(part, "wb") as f:
            said = 0
            while True:
                if cancel is not None and cancel.is_set():
                    raise Cancelled(f"{d.name}: download cancelled")
                chunk = r.read(1 << 16)
                if not chunk:
                    break
                f.write(chunk)
                digest.update(chunk)
                got += len(chunk)
                if d.size and got * 4 // d.size > said:
                    said = got * 4 // d.size
                    report.detail(f"{got * 100 // d.size}%")
    except UNREACHABLE as e:
        part.unlink(missing_ok=True)
        raise Fail(f"{d.name} could not be downloaded: {getattr(e, 'reason', e)}",
                   lines=[f"download it by hand from {d.url} and put it at {target}"], code="download")
    except BaseException:
        part.unlink(missing_ok=True)
        raise
    if digest.hexdigest() != d.sha256:
        part.unlink(missing_ok=True)
        raise Fail(f"{d.name} did not arrive as the version Masurium was tested with (checksum differs): "
                   "it was not put in", lines=[d.url], code="bad_download")
    os.replace(part, target)
    report.detail(f"-> {target}")
    return target


def fetch(ws, on_event=None, cancel=None, opener=None):
    """What is missing of HeadlessMC and hmc-specifics. Returns what it put."""
    return [download(ws, d, on_event, cancel, opener) for d in DOWNLOADS if not _present(ws, d)]


def put_mods(ws, on_event=None):
    """The Masurium jars that came with the program, into shared/mods (with
    deploy-mod's care: an older jar of the same mod goes). Only those not
    there already, byte for byte, and never one older than what is there: a
    jar put in by hand, newer, stays."""
    report = report_to(on_event)
    mods = ws.shared_dir / "mods"
    put = []
    brought = bundled_jars()
    for jar in brought:
        there = mods / jar.name
        if there.is_file() and there.stat().st_size == jar.stat().st_size \
                and there.read_bytes() == jar.read_bytes():
            continue
        if _newer_than(jar, brought) or _newer_than(jar, list(mods.glob("masurium-*.jar"))):
            continue
        path, _ = operations.deploy_mod(ws, jar, on_event=report)
        put.append(path)
    return put


def check_server(host, port, token):
    """Whether the server's Masurium mod answers there, with that token."""
    api = ServerApi(host, port, token)
    try:
        api.get("/players")
    except urllib.error.HTTPError as e:
        if e.code in (401, 403):
            raise Fail(f"{api.address} answers, but not to that token", code="wrong_token")
        raise Fail(f"{api.address} said HTTP {e.code}", code="server_mod")
    except UNREACHABLE as e:
        raise Fail(f"nothing answers at {api.address}: {getattr(e, 'reason', e)}",
                   lines=["is the server on, with the Masurium mod in its mods folder? The port is `port` "
                          "in the masurium.properties next to the server's jar (8477 unless changed), and "
                          "`host` there must be an address this machine reaches"],
                   code="server_mod")
    return api


def connect(ws, host, port="8477", token="", owner="", test=True, on_event=None):
    """server.env: the way to the server's Masurium mod. Checked first, unless
    told not to; the lines it already had (folders, say) are kept."""
    report = report_to(on_event)
    host, port, token, owner = (str(x or "").strip() for x in (host, port, token, owner))
    if not host or not HOST.match(host):
        raise Fail("the server's address: a name or an IP, such as 192.168.1.10", code="bad_setup")
    if not port.isdigit() or not 0 < int(port) < 65536:
        raise Fail("the port of the server's Masurium mod: a number, 8477 unless changed", code="bad_setup")
    if (not token and host not in LOCAL) or any(c.isspace() for c in token):
        raise Fail("the token: `token` in the masurium.properties next to the server's jar (only a server on "
                   "this same machine may go without one)", code="bad_setup")
    if owner and not PLAYER.match(owner):
        raise Fail("the owner: a Minecraft player name (letters, digits, _), or nothing", code="bad_setup")
    if test:
        report.step(f"asking the server's Masurium mod at {host}:{port}", stage="connect")
        check_server(host, port, token)
    values = {"MASURIUM_HOST": host, "MASURIUM_PORT": port, "MASURIUM_TOKEN": token, "MASURIUM_OWNER": owner}
    lines, seen = [], set()
    try:
        old = ws.env_file.read_text(encoding="utf-8").splitlines()
    except OSError:
        old = ["# Masurium Launcher: the way to the server's Masurium mod. Holds its token: keep it private."]
    for line in old:
        key = line.split("=", 1)[0].strip().removeprefix("export ").strip() if "=" in line else None
        if key in values:
            seen.add(key)
            if values[key]:
                lines.append(f"{key}={values[key]}")
            continue
        lines.append(line)
    lines += [f"{k}={v}" for k, v in values.items() if k not in seen and v]
    ws.env_file.parent.mkdir(parents=True, exist_ok=True)
    write_private(ws.env_file, "\n".join(lines) + "\n")
    report.detail(f"-> {ws.env_file}")
    return ws.env_file


def neoforge_of(ws):
    """The NeoForge the server runs, as HeadlessMC names it, when its
    Masurium mod says (its /mods); None if it cannot be asked."""
    try:
        mods = ws.api().mods()
    except Fail:
        return None
    version = (mods or {}).get("neoforge")
    return f"neoforge-{version}" if version else None


def add_server(ws, slug, host, mc_port="25565", version=None, description="", on_event=None):
    """servers/<slug>/: server.conf (how to get in) and mods/, for the client
    side of its pack. The NeoForge version, when not given, is the one the
    server runs, or the one Masurium is built for."""
    report = report_to(on_event)
    slug, host, mc_port = (str(x or "").strip() for x in (slug, host, mc_port))
    if not SLUG.match(slug):
        raise Fail("a name for the server: lowercase letters, digits, - and _, such as my-server",
                   code="bad_setup")
    if not host or not HOST.match(host):
        raise Fail("the server's game address: a name or an IP", code="bad_setup")
    if not mc_port.isdigit() or not 0 < int(mc_port) < 65536:
        raise Fail("the server's game port: a number, 25565 unless changed", code="bad_setup")
    pack = ws.servers_dir / slug
    if (pack / "server.conf").exists():
        raise Fail(f"there is already a server {slug}", code="exists")
    version = (version or "").strip() or neoforge_of(ws) or DEFAULT_VERSION
    if not re.match(r"^neoforge-[0-9][0-9.]*(-beta)?$", version):
        raise Fail(f"'{version}' is not a NeoForge version as HeadlessMC names it (neoforge-21.1.248)",
                   code="bad_setup")
    (pack / "mods").mkdir(parents=True, exist_ok=True)
    description = " ".join(str(description or "").replace('"', "'").split())
    (pack / "server.conf").write_text(
        f"# how to get into {slug}\nHOST={host}\nMC_PORT={mc_port}\nVERSION={version}\n"
        + (f'DESCRIPTION="{description}"\n' if description else ""), encoding="utf-8")
    report.step(f"server {slug} registered: {host}:{mc_port}, {version}", stage="server")
    report.detail(f"its client-side mods, if its pack has any, go in {pack / 'mods'}")
    return ws.server(slug)


# --- the terminal ------------------------------------------------------------------

def _ask(prompt, default="", secret=False):
    if not sys.stdin.isatty():
        return default
    shown = f"{prompt} [{default}]: " if default and not secret else f"{prompt}: "
    if secret:
        import getpass
        answer = getpass.getpass(shown)
    else:
        answer = input(shown)
    return answer.strip() or default


def run(ws, host=None, port=None, token=None, owner=None, server=None, game_port=None,
        download_them=True, test=True, on_event=None, cancel=None):
    """`masurium.py setup`: every step still missing, asking what it has to
    (or taking it from the arguments). Returns what is still missing after."""
    report = report_to(on_event)
    if download_them:
        fetch(ws, report, cancel)
    if bundled_jars() and (not masurium_in_shared(ws) or older_jars(ws)):
        put_mods(ws, report)
    if server_env_missing(ws):
        values = ws.env_values() if ws.env_file.is_file() else {}
        host = host or _ask("The server's address (where its Masurium mod listens)", values.get("MASURIUM_HOST", ""))
        port = port or _ask("The port of its Masurium mod", values.get("MASURIUM_PORT", "8477"))
        token = token or _ask("Its token (`token` in the masurium.properties next to the server's jar)",
                              values.get("MASURIUM_TOKEN", ""), secret=True)
        owner = owner if owner is not None else _ask("Your player name, the bots' owner (optional)",
                                                     values.get("MASURIUM_OWNER", ""))
        if not host:
            raise Fail("the way to the server is missing: give --host and --token (and --port if not 8477)",
                       code="bad_setup")
        connect(ws, host, port, token, owner, test=test, on_event=report)
    if not ws.server_slugs():
        env = ws.env_values()
        server = server or _ask("A name for the server (lowercase, such as my-server)", "my-server")
        address = _ask("Its game address", env.get("MASURIUM_HOST", ""))
        game_port = game_port or _ask("Its game port", "25565")
        add_server(ws, server, address or env.get("MASURIUM_HOST", ""), game_port, on_event=report)
    return missing(ws)

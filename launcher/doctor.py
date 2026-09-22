"""doctor: the machine, the folders, the jars and the server mod, checked in
the order in which things break."""
import pathlib
import re
import shutil
import sys
import urllib.error
from collections import namedtuple

from . import settings
from .api import UNREACHABLE
from .events import Fail
from .packs import ADDON_FOR, CORE_JAR, compare_packs, jar_family, pack_carries, pack_mods
from .processes import WINDOWS, run_quiet

# ok is True, False, or None for a warning.
Check = namedtuple("Check", "label ok detail")


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


def checks(ws):
    """Every check, as Check(label, ok, detail). The order is the order in
    which things break: what is missing first, what would fail later last."""
    out = []

    def add(label, ok, detail=""):
        out.append(Check(label, ok, detail))

    v = sys.version_info
    add("python 3.9+", v >= (3, 9), f"{v.major}.{v.minor}.{v.micro}")

    code, text = run_quiet(ws.java_command() + ["-version"])
    major = java_major(text) if code == 0 else None
    add("java 21", major == 21,
        f"{' '.join(ws.java_command())}: {text.splitlines()[0] if text else 'not found'}"
        + ("" if major in (None, 21) else "  (NeoForge 21.1 wants 21; MARIONETTE_JAVA can point at one)"))

    claude = shutil.which("claude", path=ws.child_env()["PATH"])
    if claude:
        code, text = run_quiet([claude, "--version"])
        add("claude code", code == 0, text.splitlines()[0] if text else claude)
    else:
        add("claude code", False, "not on PATH (nor in ~/.local/bin): the brain has nothing to run")

    theirs = None
    if ws.env_file.is_file():
        values = ws.env_values()
        missing = [k for k in ("MARIONETTE_HOST", "MARIONETTE_PORT", "MARIONETTE_TOKEN")
                   if not values.get(k)]
        add("server.env", not missing, f"{ws.env_file}" + (f": missing {', '.join(missing)}" if missing else ""))
        if not missing:
            api = ws.api()
            if api.host != "127.0.0.1" and not api.token:
                add("server mod token", False, "the API listens beyond localhost with no token")
            try:
                text = api.get("/players")
                add("server mod answers", True, f"{api.address} -> {text.strip()[:60]}")
                theirs = api.mods()
                add("server mod lists its mods", True if theirs else None,
                    f"{len(theirs)} mods" if theirs else "no /mods route: a server mod older than 1.0.0")
            except urllib.error.HTTPError as e:
                add("server mod answers", False, f"{api.address} said HTTP {e.code}"
                    + (" (wrong token?)" if e.code in (401, 403) else ""))
            except UNREACHABLE as e:
                add("server mod answers", False,
                    f"{api.address}: {getattr(e, 'reason', e)} (server down, or not that host/port)")
    else:
        add("server.env", False, f"{ws.env_file} does not exist")

    shared = ws.shared_dir
    add("shared/headlessmc-launcher.jar", (shared / "headlessmc-launcher.jar").is_file(), str(shared))
    mods = shared / "mods"
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

    slugs = ws.server_slugs()
    add("servers registered", bool(slugs), ", ".join(slugs) if slugs else f"nothing under {ws.servers_dir}")
    for slug in slugs:
        try:
            s = ws.server(slug)
        except Fail as e:
            add(f"servers/{slug}", False, str(e))
            continue
        add(f"servers/{slug}", True, f"{s.address} {s.version} {s.mod_count()} client mods")
        # Against the server that answers /mods (one at a time answers, and it
        # may not be this slug's: only mismatches are reported, and a pack
        # that shares nothing with it has none).
        if theirs:
            mine = pack_mods(ws, s.pack)
            diff = compare_packs(mine, theirs)
            if diff["mismatch"]:
                add(f"servers/{slug}: versions", False,
                    "; ".join(f"{i}: pack {a}, server {b}" for i, a, b in diff["mismatch"][:6])
                    + (" ..." if len(diff["mismatch"]) > 6 else ""))
            else:
                shared_ids = len([i for i in mine if i in theirs])
                add(f"servers/{slug}: versions", True,
                    f"{shared_ids} mods in common with the server that answers, same versions")
        # A mod that needs an add-on on a headless bot, with the add-on missing:
        # the client would crash at startup, before the mod handshake.
        for mod_id, addon in ADDON_FOR.items():
            carriers = pack_carries(s.pack, mod_id)
            if carriers and not any(jar_family(j.name) == addon for j in every):
                add(f"servers/{slug}: {mod_id}", False,
                    f"carried by {', '.join(carriers)}; a headless bot needs {addon}-<version>.jar "
                    f"in shared/mods (see addons/)")

    keys = ws.bot_keys()
    add("bots", True if keys else None, ", ".join(keys) if keys else f"none yet under {ws.bots_dir}")
    seen_ports = {}
    for key in keys:
        bot = ws.bot(key)
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
        clash = ws.name_clash(key)
        if clash:
            problems.append(f"name contains or is contained by '{clash}'")
        if bot.read("account", "online") == "online" and not (bot.hmc / "HeadlessMC" / "auth").exists():
            problems.append("online account never logged in (marionette.py login)")
        escort = bot.read("escort").lower()
        if escort and escort not in keys:
            problems.append(f"escorts '{escort}', which is not a bot here")
        elif escort == key:
            problems.append("escorts itself")
        # Every other file with a value `set` would refuse (the port and the
        # escort are said above, in their own words).
        problems += [why for k, why in settings.problems(bot) if k not in ("port", "escort")]
        add(f"bots/{key}", not problems, "; ".join(problems) or f"port {port}, server {bot.read('server')}")

    heaps = [heap_gb(settings.get(ws.bot(k), "heap")) or heap_gb(ws.heap()) or 0 for k in keys]
    avail = free_memory_gb()
    if heaps and avail is not None:
        need = sum(heaps)
        same = len(set(heaps)) == 1
        add("memory for every bot at once", need <= avail if need > avail * 0.9 else True,
            (f"{len(keys)} bots x {heaps[0]:g} GB heap" if same else f"{len(keys)} bots, heaps "
             + " + ".join(f"{h:g}" for h in heaps) + " GB")
            + f" = {need:g} GB; {avail:.1f} GB available"
            + ("" if need <= avail else " (not all of them at once)"))
    return out

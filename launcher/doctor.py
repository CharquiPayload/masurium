"""doctor: the machine, the folders, the jars and the server mod, checked in
the order in which things break."""
import pathlib
import re
import shutil
import sys
import urllib.error
from collections import namedtuple

from . import __version__, brain, groups, rules, settings, updates
from .api import UNREACHABLE
from .instances import check_name
from .events import Fail
from .files import read_env_file
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


def env_keys(values):
    """What a server.env must say: the token too, unless the server mod is on
    this same machine, where it may listen without one."""
    local = values.get("MASURIUM_HOST") in ("127.0.0.1", "localhost", "::1")
    return ("MASURIUM_HOST", "MASURIUM_PORT") + (() if local else ("MASURIUM_TOKEN",))


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
        + ("" if major in (None, 21) else "  (NeoForge 21.1 wants 21; MASURIUM_JAVA can point at one)"))

    claude = shutil.which("claude", path=ws.child_env()["PATH"])
    if claude:
        code, text = run_quiet([claude, "--version"])
        add("claude code", code == 0, text.splitlines()[0] if text else claude)
        have = brain.version_of(text) if code == 0 else None
        latest = brain.latest(ws)
        if have and latest and latest > have:
            add("claude code up to date", None, f"{brain.dotted(latest)} is out, this machine runs "
                f"{brain.dotted(have)}: {brain.UPDATE}")
        elif have and latest:
            add("claude code up to date", True, f"{brain.dotted(have)} is the latest")
        elif have:
            add("claude code up to date", None, "GitHub could not be asked which is the latest")
    else:
        add("claude code", False, "not on PATH (nor in ~/.local/bin): the brain has nothing to run")
    # Masurium itself: said only when GitHub answers (a machine offline has
    # nothing wrong with it for that).
    fresh = updates.latest(ws)
    if fresh:
        have = brain.version_of(__version__)
        if have and fresh[0] > have:
            add("masurium up to date", None, f"{brain.dotted(fresh[0])} is out, this launcher is "
                f"{brain.dotted(have)}: {updates.how_to_update()} ({fresh[1]})")
        else:
            add("masurium up to date", True, f"{__version__} is the latest")

    theirs = None
    own_api = [s for s in ws.servers() if s.env_file.is_file()]
    everyone_has_own = bool(ws.server_slugs()) and len(own_api) == len(ws.server_slugs())
    if not ws.env_file.is_file() and everyone_has_own:
        add("server.env", None, f"{ws.env_file} does not exist; not needed, every server has its own")
    elif ws.env_file.is_file():
        values = ws.env_values()
        missing = [k for k in env_keys(values) if not values.get(k)]
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
    every = sorted(mods.glob("masurium-*.jar")) if mods.is_dir() else []
    masurium = [j for j in every if CORE_JAR.match(j.name)]
    if len(masurium) == 1:
        add("shared/mods/masurium", True, masurium[0].name)
    elif not masurium:
        add("shared/mods/masurium", False, "no masurium-<version>.jar: build it and run deploy-mod")
    else:
        add("shared/mods/masurium", False,
            "more than one: " + ", ".join(j.name for j in masurium) + " (two jars declaring the same mod)")
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
    from . import firstrun              # here: it imports this module
    older = firstrun.older_jars(ws)
    if older:
        add("shared/mods up to date", None, firstrun.older_detail(older, "masurium.py setup"))

    slugs = ws.server_slugs()
    add("servers registered", bool(slugs), ", ".join(slugs) if slugs else f"nothing under {ws.servers_dir}")
    for slug in slugs:
        try:
            s = ws.server(slug)
        except Fail as e:
            add(f"servers/{slug}", False, str(e))
            continue
        add(f"servers/{slug}", True, f"{s.address} {s.version} {s.mod_count()} client mods")
        if (ws.servers_dir / slug / "rules.json").is_file():
            try:
                layer = rules.of_server(ws, slug)
                add(f"servers/{slug}: rules", True, "; ".join(rules.describe(layer)) or "nothing")
            except Fail as e:
                add(f"servers/{slug}: rules", False, str(e))
        # Against the server that answers the global server.env's API (it may
        # not be this slug's: only mismatches are reported, and a pack that
        # shares nothing with it has none)... unless the server has its own
        # server.env: then against its own server mod, exactly.
        against, its_mods = "the server that answers", theirs
        if s.env_file.is_file():
            its_mods = None
            own = read_env_file(s.env_file)
            missing = [k for k in env_keys(own) if not own.get(k)]
            if not WINDOWS and s.env_file.stat().st_mode & 0o077:
                add(f"servers/{slug}: server.env", False,
                    f"{s.env_file} can be read by other users, and it holds the token: chmod 600 it")
            elif missing:
                add(f"servers/{slug}: server.env", False, f"{s.env_file}: missing {', '.join(missing)}")
            else:
                api = ws.api_for(s)
                try:
                    api.get("/players")
                    its_mods, against = api.mods(), "its server"
                    add(f"servers/{slug}: server mod", True, f"{api.address} answers")
                except urllib.error.HTTPError as e:
                    add(f"servers/{slug}: server mod", False, f"{api.address} said HTTP {e.code}"
                        + (" (wrong token?)" if e.code in (401, 403) else ""))
                except UNREACHABLE as e:
                    add(f"servers/{slug}: server mod", False,
                        f"{api.address}: {getattr(e, 'reason', e)} (server down, or not that host/port)")
        if its_mods:
            mine = pack_mods(ws, s.pack)
            diff = compare_packs(mine, its_mods)
            if diff["mismatch"]:
                add(f"servers/{slug}: versions", False,
                    "; ".join(f"{i}: pack {a}, server {b}" for i, a, b in diff["mismatch"][:6])
                    + (" ..." if len(diff["mismatch"]) > 6 else ""))
            else:
                shared_ids = len([i for i in mine if i in its_mods])
                add(f"servers/{slug}: versions", True,
                    f"{shared_ids} mods in common with {against}, same versions")
        # A mod that needs an add-on on a headless bot, with the add-on missing:
        # the client would crash at startup, before the mod handshake.
        for mod_id, addon in ADDON_FOR.items():
            carriers = pack_carries(s.pack, mod_id)
            if carriers and not any(jar_family(j.name) == addon for j in every):
                add(f"servers/{slug}: {mod_id}", False,
                    f"carried by {', '.join(carriers)}; a headless bot needs {addon}-<version>.jar "
                    f"in shared/mods (see addons/)")

    old = ws.old_bots()
    offline_accounts = [k for k in ws.account_keys() if ws.account(k).offline]
    server_rules = [s for s in slugs if (ws.servers_dir / s / "rules.json").is_file()]
    unfolded = [i.key for i in ws.instances() if "bot" in i.data]
    if old or offline_accounts or server_rules or unfolded:
        add("layout", False, "from before bots lived in their instances: "
            + "; ".join(x for x in (f"bots {', '.join(old)}" if old else "",
                                   f"instances of a bot {', '.join(unfolded)}" if unfolded else "",
                                   f"offline accounts {', '.join(offline_accounts)}" if offline_accounts else "",
                                   f"servers' rules {', '.join(server_rules)}" if server_rules else "") if x)
            + " (masurium.py migrate)")

    if ws.config_file.is_file():
        try:
            wrong = [why for _, why in settings.problems(ws.global_config())]
            layer = rules.of_global(ws)
            given = settings.own_values(ws.global_config())
            add("launcher.json", not wrong, "; ".join(wrong) or (
                "global settings: " + (", ".join(f"{k} {v}" for k, v in given.items()) or "none")
                + "; global rules: " + ("; ".join(rules.describe(layer)) or "none")))
        except Fail as e:
            add("launcher.json", False, str(e))

    for key in ws.account_keys():
        account = ws.account(key)
        if account.offline:
            continue                    # said under layout: migrate folds it into its instances
        users = account.users()
        add(f"accounts/{key}", account.logged_in(),
            (f"plays as {account.name}" if account.logged_in()
             else "its login is gone (HeadlessMC deletes one it could not renew): remove it and add it again")
            + (f"; used by {', '.join(users)}" if users else ""))

    instances = ws.instances()
    add("instances", True if instances else None,
        ", ".join(i.key for i in instances) if instances else f"none yet under {ws.instances_dir}")
    seen_ports = {}
    players = {}
    for inst in instances:
        problems = []
        data = inst.data
        port = str(data.get("port", ""))
        if not port.isdigit():
            problems.append("no port")
        elif port in seen_ports:
            problems.append(f"port {port} also belongs to {seen_ports[port]}")
        else:
            seen_ports[port] = inst.key
        if inst.slug not in slugs:
            problems.append(f"its server '{inst.slug}' is not registered")
        if not (inst.hmc / "HeadlessMC" / "config.properties").is_file():
            problems.append("no hmc config")
        try:
            check_name(inst.name)
        except Fail as e:
            problems.append(str(e))
        group, place = groups.dependency_of(inst)
        if settings.get(inst, "role") == "guard" and place != "guard":
            problems.append("a guard, and no dependency group names it as one: it will not start")
        problems += [why for k, why in settings.problems(inst) if k != "port"]
        players.setdefault((inst.slug, inst.player), []).append(inst.key)
        waiting = len(rules.pending(inst))
        add(f"instances/{inst.key}", not problems,
            "; ".join(problems) or f"{inst.name} on {inst.slug}, port {port}"
            + (f"; {waiting} rule change(s) waiting for its server" if waiting else ""))
    keys = ws.group_keys()
    if keys:
        add("groups", True, ", ".join(keys))
    wrong = groups.problems(ws)
    for g in ws.groups():
        mine = [why for where, why in wrong if where == g.id]
        mine += [why for _, why in settings.problems(g)]
        try:
            rules.of_group(g)
        except Fail as e:
            mine.append(str(e))
        add(f"groups/{g.key}", not mine, "; ".join(mine) or (
            f"{g.leader} and {len(g.guards)} guard(s)" if g.kind == groups.DEPENDENCY
            else f"{len(g.instance_keys())} instance(s), {len(g.group_keys())} group(s)"))
    for where, why in wrong:
        if not where.startswith("group "):
            add(where, False, why)
    for (slug, player), keys_ in sorted(players.items()):
        if len(keys_) > 1:
            add(f"{player} on {slug}", None, f"{len(keys_)} instances ({', '.join(keys_)}); "
                "only one of them can run at a time")

    heaps = [heap_gb(settings.get(i, "heap")) or heap_gb(ws.heap()) or 0 for i in instances]
    avail = free_memory_gb()
    if heaps and avail is not None:
        need = sum(heaps)
        same = len(set(heaps)) == 1
        add("memory for every instance at once", need <= avail if need > avail * 0.9 else True,
            (f"{len(heaps)} instances x {heaps[0]:g} GB heap" if same else f"{len(heaps)} instances, heaps "
             + " + ".join(f"{h:g}" for h in heaps) + " GB")
            + f" = {need:g} GB; {avail:.1f} GB available"
            + ("" if need <= avail else " (not all of them at once)"))
    return out

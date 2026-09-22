"""What the launcher does to bots: create, start, connect, give a voice (the
bridge), stop, restart, look at, and deploy a new mod for all of them.

Every operation reports what it does through `on_event` (see events.py) and
fails by raising Fail; none of them prints. The command line and, later, a
window are two faces on these same functions.
"""
import os
import pathlib
import re
import shutil
import sys
import time
from dataclasses import dataclass

from .api import UNREACHABLE
from .bots import check_name, operating
from .diagnosis import complaints, crash_report, explain_crash
from .events import Cancelled, Fail, pause, report_to, wait_for
from .files import LogWatch, link_or_copy, log_has, read_pid, tail_lines, unlink_quietly
from .keeper import (HMC_READY, KEEPER_ENDED, KEEPER_FAILED, GAME_OVER, clear_run_files,
                     keeper_alive, keeper_ask, keeper_pid, launcher_pid)
from .packs import CORE_JAR, compare_packs, jar_family, pack_mods, sync_mods
from .processes import ENTRY, game_pids, is_ours, port_in_use, spawn_free, stop_game, terminate

REPO = pathlib.Path(__file__).resolve().parent.parent

JOINED = "joined"
ALREADY_IN = "already in"


# --- create -------------------------------------------------------------------

def create_bot(ws, name, slug, account=None, on_event=None):
    """A bot costs a folder, a port and a few small files. Returns the Bot."""
    report = report_to(on_event)
    check_name(name)
    bot = ws.bot(name)
    account = account or ws.environ.get("MARIONETTE_ACCOUNT") or "online"
    if account not in ("online", "offline"):
        raise Fail(f"the account must be online or offline, not '{account}'.", code="bad_account")
    if bot.dir.exists():
        raise Fail(f"{bot.dir} already exists. To make it again, delete it yourself first.",
                   code="exists")
    clash = ws.name_clash(bot.key)
    if clash:
        raise Fail(f"'{name}' clashes with the bot '{clash}': one name contains the other.",
                   lines=["the bridge would mix them up in the chat. Choose another name."],
                   code="name_clash")
    server = ws.server(slug)
    launcher_jar = ws.shared_dir / "headlessmc-launcher.jar"
    if not (ws.shared_dir / "mods").is_dir() or not launcher_jar.is_file():
        raise Fail(f"missing {ws.shared_dir}: it holds the launcher and the Marionette mods.",
                   code="no_shared")
    port = ws.free_port(bot.key)

    report.step(f"creating {name}  (server {slug}, port {port})", stage="creating")
    try:
        bot.dir.mkdir(parents=True)
    except FileExistsError:
        raise Fail(f"{bot.dir} already exists. To make it again, delete it yourself first.",
                   code="exists")
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
    owner = ws.env_values().get("MARIONETTE_OWNER", "")
    if owner:
        bot.write("owner", owner)
    n = sync_mods(ws, bot.gamedir, server.pack)
    report.detail(f"{n} mods linked from {server.mods_dir} and {ws.shared_dir / 'mods'}")
    return bot


def login_command(bot):
    """Online bots use a real, purchased Minecraft Java account (a Microsoft
    account), like any player. HeadlessMC keeps the login in the bot's own hmc
    folder, so each bot has its own account. What to run, interactively, to
    log it in: (argv, cwd, env). None for an offline bot."""
    if bot.read("account") == "offline":
        return None
    return (bot.ws.java_command() + ["-jar", "headlessmc-launcher.jar"],
            bot.hmc, bot.ws.child_env())


# --- start --------------------------------------------------------------------

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
    (config / "marionette-server.txt").write_text(server.slug + "\n", encoding="utf-8")
    escort = re.sub(r"\s", "", bot.read("escort"))
    escort_f = config / "marionette-escort.txt"
    if escort:
        escort_f.write_text(escort + "\n", encoding="utf-8")
    else:
        unlink_quietly(escort_f)
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
    n = sync_mods(bot.ws, bot.gamedir, server.pack)
    bot.write("server", server.slug)
    return n


def join(bot, server, api, report, attempts, patience=18, cancel=None):
    """Send `connect` and wait for the server to list the bot. Each attempt
    waits `patience` x 10 s."""
    report.step(f"connecting to {server.slug} ({server.address})", stage="joining")
    for attempt in range(1, attempts + 1):
        answer = keeper_ask(bot, f"connect {server.address}")
        if answer != "sent":
            report.detail(f"the keeper did not take the command ({answer}); is the client alive?")
            return False
        for i in range(1, patience + 1):
            pause(10, cancel)
            if api.is_inside(bot.name):
                report.step(f"{bot.name} is IN (attempt {attempt}, {i * 10}s)", stage="in")
                return True
        report.detail(f"attempt {attempt} failed")
    return False


def start(bot, switch_to=None, on_event=None, cancel=None):
    """Start a bot's client and put it on its server (or on `switch_to`,
    which rebuilds its mods). Returns JOINED, or ALREADY_IN when it was.

    With `cancel` set halfway, it stops what it started (the client it
    launched is stopped, not left loading with nobody waiting for it) and
    raises Cancelled."""
    bot.require()
    with operating(bot):
        return _start(bot, switch_to, report_to(on_event), cancel)


def _start(bot, switch_to, report, cancel=None):
    launched = []
    try:
        return _start_steps(bot, switch_to, report, cancel, launched)
    except Cancelled:
        if launched:
            report.step("cancelled: stopping the client it had started", stage="cancelling")
            _stop_client(bot, report)
        raise


def _start_steps(bot, switch_to, report, cancel, launched):
    ws = bot.ws
    slug = switch_to or bot.read("server")
    if not slug:
        raise Fail(f"{bot.name} has no server noted. Say which one it joins:",
                   lines=ws.servers_listing(), code="no_server")
    server = ws.server(slug)
    api = ws.api()
    clash = ws.name_clash(bot.key)
    if clash:
        raise Fail(f"the name '{bot.name}' clashes with the bot '{clash}': one contains the other.",
                   lines=["the bridge would mix them up in the chat. Choose another name."],
                   code="name_clash")

    # Whether it is running is asked BEFORE anything is touched. Preparing
    # first rebuilt the mods folder of a live game and noted a server it was
    # not on; on Windows, where an open jar cannot be deleted, it failed.
    moving = switch_to and switch_to != bot.read("server")
    if api.is_inside(bot.name):
        if moving:
            raise Fail(f"{bot.name} is in the game, on {bot.read('server')}. To move it to "
                       f"{slug}, stop it first:  marionette.py stop {bot.name}", code="running")
        report.step(f"{bot.name} is already in. Nothing to do.", stage="in")
        return ALREADY_IN
    # A keeper that answers holds a live client: either this same bot loaded
    # but not connected, or a start that was cut halfway. Starting anyway
    # would launch a second 3 GB java, which is how the OOM killer gets
    # invited.
    answer = keeper_ask(bot, "@ping")
    if answer and answer.startswith("ok "):
        if not port_in_use(bot.port) and log_has(bot.client_log, GAME_OVER):
            # A keeper around a game that already died: not a running bot.
            report.step("a keeper was left holding a game that had exited; stopping it first")
            keeper_ask(bot, "@stop")
            wait_for(lambda: keeper_pid(bot) is None, 30, every=0.5, cancel=cancel)
        else:
            raise Fail(f"{bot.name} is already running (keeper pid {read_pid(bot.keeper_pid_f)}, "
                       f"game pid {answer[3:]}) but not in the server.",
                       lines=[f"try:  marionette.py connect {bot.name}   or   marionette.py stop {bot.name}"],
                       code="running")
    if port_in_use(bot.port):
        holders = game_pids(bot.port)
        raise Fail(f"port {bot.port} is already taken by a live client this launcher does not "
                   f"hold (pids {holders or 'unknown'}).",
                   lines=[f"marionette.py stop {bot.name} takes it down, or give this bot another port."],
                   code="port_taken")
    clear_run_files(bot)
    if cancel:
        cancel.check()

    report.step(f"preparing the mods of {slug}", stage="preparing")
    report.detail(f"{prepare_gamedir(bot, server)} mods")
    # Said now, from /mods, instead of by the server three minutes from now
    # with a message that names NeoForge. Not refused: some mods take a
    # version they were not built with, and whoever runs this may know.
    theirs = api.mods()
    if theirs is not None:
        diff = compare_packs(pack_mods(ws, server.pack), theirs)
        if diff["mismatch"]:
            report.warning("the server runs other versions than this pack; expect a rejection:",
                           [f"{i}: pack {mine}, server {its}" for i, mine, its in diff["mismatch"]])

    report.step(f"starting {bot.name} (port {bot.port})", stage="launching")
    bot.run.mkdir(parents=True, exist_ok=True)
    # Fresh logs: the signs waited for below ("initialized", "game exited")
    # must be this start's and not the last one's.
    unlink_quietly(bot.client_log, bot.keeper_log)
    if cancel:
        cancel.check()
    spawn_free([sys.executable, str(ENTRY), "keeper", bot.name, slug],
               bot.keeper_log, cwd=ws.home, env=ws.child_env())
    launched.append(True)

    report.step("loading the game", stage="loading")
    # The right signal is NOT that the process exists: it is that the mod has
    # registered its commands. Sending `connect` before that talks to nobody.
    ready = LogWatch(bot.client_log, HMC_READY)
    ended = LogWatch(bot.keeper_log, KEEPER_ENDED, KEEPER_FAILED)
    spawned = time.monotonic()

    def ready_or_dead():
        if ready.saw(HMC_READY) or ended.saw(KEEPER_ENDED) or ended.saw(KEEPER_FAILED):
            return True
        # A keeper that died without a word (killed, or Python failing before
        # it could speak) leaves no line to wait for, and this used to wait
        # the whole five minutes for it. Its pid is the sign: written first
        # thing, gone or a stranger's when it is dead.
        if read_pid(bot.keeper_pid_f) is None:
            return time.monotonic() - spawned > 20
        return keeper_pid(bot) is None

    wait_for(ready_or_dead, 300, every=2, cancel=cancel)
    if ended.saw(KEEPER_FAILED) or (not ready.saw(HMC_READY) and not ended.saw(KEEPER_ENDED)
                                    and keeper_pid(bot) is None):
        raise Fail(f"the keeper of {bot.name} did not get the game going:",
                   lines=tail_lines(bot.keeper_log, 6), code="keeper_failed")
    if not ready.saw(HMC_READY):
        crash = explain_crash(bot)
        if crash:
            lines, code = crash[1], "crashed"
        else:
            lines, code = [], "not_initialized"
            if bot.read("account", "online") == "online":
                lines.append(f"(online account: if HeadlessMC asked for a login, run marionette.py login {bot.name})")
            lines += tail_lines(bot.client_log, 5) + tail_lines(bot.keeper_log, 3)
        raise Fail("the hmc-specifics mod did not initialize. Without it there is no connect.",
                   lines=lines, code=code)
    # The bot can join the server without hands: if the mod could not open
    # its port, `connect` works anyway and the failure only shows much later,
    # when an order does nothing. Better to know here.
    report.step("waiting for the bot mod's port", stage="hands")
    if not wait_for(lambda: port_in_use(bot.port), 20, every=2, cancel=cancel):
        # A mod that refused to construct (a missing add-on, say) shows up
        # here first: the game goes on loading without it, and crashes a
        # little later with the reason in its report.
        wait_for(lambda: crash_report(bot) is not None, 15, every=3, cancel=cancel)
        crash = explain_crash(bot)
        raise Fail(f"the bot mod did not open port {bot.port}. It would join without hands.",
                   lines=crash[1] if crash else tail_lines(
                       bot.client_log, 5, r"marionette_bot|address already in use|BindException"),
                   code="crashed" if crash else "no_hands")

    # `connect` sent while the game is still loading talks to nobody, and an
    # attempt was lost on every start. The mod says which screen it is on and
    # whether the loading overlay is still up; a mod older than that says
    # nothing, and then the old way, straight in, is all there is.
    def readiness():
        try:
            return bot.ask("/version")
        except UNREACHABLE:
            return {}

    if "screen" in readiness():
        report.step("waiting for the game to finish loading", stage="menu")

        def loaded():
            v = readiness()
            # Any menu will do, not only the title screen: packs put welcome
            # screens of their own in front of it, and `connect` works from
            # those. What does not work is the loading overlay, which is what
            # the first connect used to hit.
            return bool(v.get("screen")) and not v.get("loading") and not v.get("in_world")

        if wait_for(loaded, 180, every=3, cancel=cancel):
            report.detail(f"at {readiness().get('screen')}")
        else:
            report.detail(f"(still loading after 3 minutes, on {readiness().get('screen')!r}; trying anyway)")

    if join(bot, server, api, report, attempts=3, cancel=cancel):
        report.detail(api.players_text())
        return JOINED
    n = len(list((bot.gamedir / "mods").glob("*.jar")))
    raise Fail("it did NOT join. Last complaints of the client:",
               lines=complaints(bot) + [f"it joined with the '{slug}' pack ({n} mods). If the running "
                                        "server is not that one, there is the reason."],
               code="not_joined")


def connect(bot, on_event=None, cancel=None):
    """Puts back on the server a bot whose client is ALIVE at the title screen
    (after /marionette bot <bot> logoff, or a failed connect). It starts
    nothing, and on purpose it does NOT switch servers: switching servers
    means switching packs, and the pack is only rebuilt by `start`."""
    report = report_to(on_event)
    bot.require()
    slug = bot.read("server")
    if not slug:
        raise Fail(f"{bot.name} has no server noted.", code="no_server")
    server = bot.ws.server(slug)
    api = bot.ws.api()
    with operating(bot):
        if not keeper_alive(bot):
            raise Fail(f"no live client of {bot.name}: use  marionette.py start {bot.name}",
                       code="not_running")
        if api.is_inside(bot.name):
            report.step(f"{bot.name} is already in. Nothing to do.", stage="in")
            return ALREADY_IN
        # Cancelled, it only stops trying: the client was running before and
        # it still is.
        if join(bot, server, api, report, attempts=3, patience=12, cancel=cancel):
            return JOINED
    raise Fail("it did NOT join.",
               lines=[f"If the client is hung:  marionette.py restart {bot.name}"], code="not_joined")


# --- the bridge ---------------------------------------------------------------

def bridge_pid(bot):
    """The bridge's pid: from our pid file, or from the lock the bridge itself
    writes, which also covers one started by hand. The lock FILE stays after
    the bridge is gone, with its last pid inside: that pid counts only while
    it is still a bridge."""
    for f in (bot.bridge_pid_f, bot.bridge_lock):
        pid = read_pid(f)
        if is_ours(pid, "bridge.py"):
            return pid
    return None


def start_bridge(bot, on_event=None):
    """Starts the bridge, detached. It goes AFTER the client, and only if it
    joined: without a body in the game it has nobody to write to. Returns
    its pid, when it could be read."""
    report = report_to(on_event)
    bot.require()
    with operating(bot):
        pid = bridge_pid(bot)
        if pid:
            report.step(f"the bridge of {bot.name} is already running (pid {pid})", stage="bridge")
            return pid
        bot.run.mkdir(parents=True, exist_ok=True)
        unlink_quietly(bot.bridge_log)
        env = bot.ws.child_env()
        env["BOT_NAME"] = bot.name
        report.step(f"starting the bridge of {bot.name}", stage="bridge")
        spawn_free([sys.executable, str(REPO / "mcp" / "bridge.py"), bot.name],
                   bot.bridge_log, cwd=bot.ws.home, env=env)
        # The sign that it started is its own "listening" line, not that a
        # process exists: the bridge can exist and be dying. The log does not
        # lie. Its pid comes from the lock it writes (see bridge_pid).
        if wait_for(lambda: log_has(bot.bridge_log, "listening"), 10, every=1):
            first = tail_lines(bot.bridge_log, 1000)[:1]
            report.detail(f"bridge alive: {first[0] if first else ''}")
            report.detail(f"log at {bot.bridge_log}")
            return bridge_pid(bot)
    raise Fail(f"the bridge did NOT start. {bot.name} is in the game but MUTE:",
               lines=tail_lines(bot.bridge_log, 20), code="bridge_failed")


# --- stop and restart ---------------------------------------------------------

def _stop_client(bot, report):
    """The client alone: the keeper, the HeadlessMC under it and the game."""
    keeper = keeper_pid(bot)
    launcher = launcher_pid(bot)
    answer = keeper_ask(bot, "@stop")
    if answer == "stopping":
        # The keeper gives the game 25 s to shut down on its own before it
        # kills it, then leaves; a little more than that here.
        if wait_for(lambda: not is_ours(keeper, "keeper") and not port_in_use(bot.port),
                    45, every=0.5):
            report.detail("client: stopped")
        else:
            report.detail("client: the keeper did not leave nicely; insisting")
            stop_game(bot.port, launcher)
            if keeper_pid(bot):
                terminate(keeper)
    elif launcher or keeper or game_pids(bot.port):
        # No keeper answering, but something of the client is there: a keeper
        # that died, or a game started some other way on this bot's port.
        stop_game(bot.port, launcher)
        if keeper:
            terminate(keeper)
        report.detail("client: stopped (the keeper was not answering)")
    else:
        report.detail("client: nothing was running")
    clear_run_files(bot)


def stop(bot, keep_guards=False, on_event=None, _stopped=None):
    """Stops a bot: the client (through its keeper) and its bridge. Processes
    are found by what identifies THAT bot and no other: its own pid files,
    each checked to still name the process it was written for. A bare kill
    by pattern would take down every bot, which is exactly what must not
    happen when there are two."""
    report = report_to(on_event)
    bot.require()
    stopped = set() if _stopped is None else _stopped
    stopped.add(bot.key)
    with operating(bot):
        report.step(f"stopping {bot.name} (port {bot.port})", stage="stopping")
        _stop_client(bot, report)

        pid = bridge_pid(bot)
        if pid:
            terminate(pid)
            report.detail("bridge: stopped")
        else:
            report.detail("bridge: nothing was running")
        unlink_quietly(bot.bridge_pid_f)
        report.step(f"{bot.name} stopped", stage="stopped")

    # Its guards leave with it: a guard without a boss has nothing to do. A
    # RESTART is not a disconnection: the guards stay. Each bot is stopped
    # once: two bots guarding each other, or one guarding itself, used to
    # send this round and round until Python gave up.
    if not keep_guards:
        for g in bot.guards():
            if g.key in stopped:
                continue
            report.step(f"{g.name} is a guard of {bot.name}: stopping it too")
            try:
                stop(g, keep_guards=False, on_event=report, _stopped=stopped)
            except Fail as e:
                report.warning(str(e), e.lines)


def restart(bot, switch_to=None, on_event=None, cancel=None):
    """Restarts a WHOLE bot: client and bridge. It exists because of an easy
    mistake: after deploying a new mod both processes get killed, but only
    the client gets started again. The bot stays in the game, visible in
    /players, and mute, which from the chat looks exactly like a hang."""
    report = report_to(on_event)
    bot.require()
    with operating(bot):
        stop(bot, keep_guards=True, on_event=report)
        try:
            _start(bot, switch_to, report, cancel)
        except Cancelled:
            raise
        except Fail as e:
            raise Fail(str(e), lines=e.lines + ("the client did not join: the bridge was NOT started.",),
                       code=e.code)
        return start_bridge(bot, on_event=report)


# --- looking ------------------------------------------------------------------

@dataclass(frozen=True)
class BotStatus:
    name: str
    server: str
    port: int
    client: bool          # a keeper answers, or a game carries its port
    hands: bool           # the bot mod's port is open
    inside: object        # True/False, or None when the server mod was not asked
    bridge: object        # the bridge's pid, or None
    guard_of: str


def status_of(bot, api=None):
    return BotStatus(name=bot.name, server=bot.read("server") or "-", port=bot.port,
                     client=keeper_alive(bot) or launcher_pid(bot) is not None
                     or bool(game_pids(bot.port)),
                     hands=port_in_use(bot.port),
                     inside=api.is_inside(bot.name) if api else None,
                     bridge=bridge_pid(bot), guard_of=bot.read("escort"))


def survey(ws, name=None):
    """(what is wrong with the server mod, or None; the status of every bot,
    or of one). The server mod is asked once here, not once per bot with a
    5 s timeout each."""
    bots = [ws.bot(name).require()] if name else ws.bots()
    problem, api = None, None
    try:
        api = ws.api()
    except Fail as e:
        problem = str(e)
    if api:
        try:
            api.get("/players")
        except UNREACHABLE as e:
            problem = f"the server mod at {api.address} does not answer: {getattr(e, 'reason', e)}"
            api = None
    return problem, [status_of(b, api) for b in bots]


# --- deploy -------------------------------------------------------------------

def deploy_mod(ws, jar=None, on_event=None):
    """Puts a freshly built jar in shared/mods WITHOUT overwriting the one in
    use. Each bot's mods are HARD LINKS to the jar in shared/mods, and a copy
    over that jar writes into the same inode: a running client sees the zip it
    has open change and, as soon as it needs a class it had not loaded yet,
    dies with NoClassDefFoundError. A rename instead creates a new inode: the
    link in the gamedir of the running bot still points at the old jar and
    the bot does not notice. On its next start, the sync links it to the new
    one. Without a jar it deploys the core; with one, that jar (an add-on,
    say). Returns (the deployed path, the names of the jars it replaced)."""
    report = report_to(on_event)
    if jar:
        source = pathlib.Path(jar).expanduser()
        if not source.is_file():
            raise Fail(f"{source} is not a file", code="no_jar")
    else:
        built = sorted(p for p in (REPO / "mod" / "build" / "libs").glob("marionette-*.jar")
                       if "-sources" not in p.name and CORE_JAR.match(p.name))
        if not built:
            raise Fail("no marionette jar in mod/build/libs: build it first", code="no_jar")
        source = built[0]
    family = jar_family(source.name)
    target_dir = ws.shared_dir / "mods"
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
    report.step(f"deployed {target.stat().st_size} bytes to {target} (new inode)", stage="deployed")
    if gone:
        report.detail(f"out: {', '.join(gone)}")
    report.detail("bots already in the game keep the old jar until they restart")
    if family == "marionette":
        report.warning("The SAME file goes in the Minecraft server's mods folder, and the old "
                       "marionette-bot-*.jar / marionette-server-*.jar have to come out of it:",
                       ["two jars declaring marionette_bot means the game does not start."])
    return target, gone

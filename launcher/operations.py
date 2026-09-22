"""What the launcher does: create bots and instances, clone them, start an
instance and put it on its server, give it a voice (the bridge), stop it,
restart it, look at every instance, change settings, deploy a new mod, and
move a workspace from the layout before instances.

Every operation reports what it does through `on_event` (see events.py) and
fails by raising Fail; none of them prints. The command line and, later, a
window are two faces on these same functions.
"""
import contextlib
import os
import pathlib
import re
import shutil
import sys
import tarfile
import time
import urllib.error
from dataclasses import dataclass

from . import accounts, groups, rules, settings
from .api import UNREACHABLE
from .bots import Character, Instance, check_key, check_name, operating, write_json
from .diagnosis import complaints, crash_report, explain_crash
from .events import Cancelled, Fail, pause, report_to, wait_for
from .files import (LogWatch, link_dir, link_or_copy, link_target, log_has, read_java_properties,
                    read_pid, tail_lines, try_lock, unlink_quietly)
from .keeper import (GAME_OVER, HMC_READY, KEEPER_ENDED, KEEPER_FAILED, clear_run_files,
                     keeper_alive, keeper_ask, keeper_pid, launcher_pid)
from .packs import CORE_JAR, compare_packs, jar_family, pack_mods, sync_mods
from .processes import ENTRY, game_pids, is_ours, port_in_use, spawn_free, stop_game, terminate

REPO = pathlib.Path(__file__).resolve().parent.parent

JOINED = "joined"
ALREADY_IN = "already in"

PERSONALITY_TEMPLATE = (
    "You are {name}. Write here who you are: how you talk and in which language,\n"
    "what you care about, who you trust, what makes you laugh. In second person and\n"
    "in a few lines: this goes at the start of the prompt, before the body's\n"
    "instructions.\n\n"
    "For now: you speak English with players, plainly, correct and direct, without\n"
    "flourishes.\n")


# --- bots and instances -------------------------------------------------------

def create_bot(ws, name, key=None, account=None, on_event=None):
    """A character: bots/<key>/bot.json and a personality to fill in.
    `name` is its player name in the game, with its capitals."""
    report = report_to(on_event)
    check_name(name)
    key = (key or name).lower()
    check_key(key)
    bot = ws.bot(key)
    if bot.dir.exists():
        raise Fail(f"{bot.dir} already exists.", code="exists")
    account = account or ws.environ.get("MARIONETTE_ACCOUNT") or "online"
    if account not in ("online", "offline"):
        raise Fail(f"the account must be online or offline, not '{account}'.", code="bad_account")
    try:
        bot.dir.mkdir(parents=True)
    except FileExistsError:
        raise Fail(f"{bot.dir} already exists.", code="exists")
    # Its character, in its own file from minute one, the language it speaks
    # included. A template instead of an empty file, because a bot without a
    # written character sounds like a manual.
    bot.save({"name": name, "account": account})
    bot.personality.write_text(PERSONALITY_TEMPLATE.format(name=name), encoding="utf-8")
    report.step(f"bot {key} created (plays as {name}, {account} account)", stage="created")
    return bot


def create_instance(ws, bot_key, slug, key=None, on_event=None):
    """An instance of a bot on a server: its folder, a port of its own,
    HeadlessMC and a game folder with the server's pack linked in."""
    report = report_to(on_event)
    bot = ws.bot(bot_key).require()
    server = ws.server(slug)
    launcher_jar = ws.shared_dir / "headlessmc-launcher.jar"
    if not (ws.shared_dir / "mods").is_dir() or not launcher_jar.is_file():
        raise Fail(f"missing {ws.shared_dir}: it holds HeadlessMC and the Marionette mods.",
                   code="no_shared")
    key = (key or ws.free_key(bot.key, ws.instance_keys())).lower()
    check_key(key, "instance")
    inst = Instance(ws, key)
    if inst.dir.exists():
        raise Fail(f"{inst.dir} already exists.", code="exists")
    port = ws.free_port(key)
    try:
        inst.dir.mkdir(parents=True)
    except FileExistsError:
        raise Fail(f"{inst.dir} already exists.", code="exists")
    (inst.gamedir / "mods").mkdir(parents=True)
    (inst.hmc / "HeadlessMC").mkdir(parents=True)
    # The only thing of HeadlessMC that CANNOT be shared is its folder: the
    # name used to join and the login are kept there.
    link_or_copy(launcher_jar, inst.hmc / "headlessmc-launcher.jar")
    (inst.hmc / "HeadlessMC" / "config.properties").write_text("", encoding="utf-8")
    inst.save({"bot": bot.key, "server": server.slug, "port": port})
    settings.render(inst)
    n = sync_mods(ws, inst.gamedir, server.pack, inst.extra_mods)
    report.step(f"instance {key}: {bot.name} on {server.slug}, port {port}", stage="created")
    report.detail(f"{n} mods linked from {server.mods_dir} and {ws.shared_dir / 'mods'}")
    return inst


def create(ws, name, slug, account=None, bot_key=None, key=None, on_event=None):
    """The bot (if it is not there yet) and an instance of it on a server:
    what `marionette.py create <name> <server>` does. Returns the instance."""
    report = report_to(on_event)
    bot_key = (bot_key or name).lower()
    bot = ws.bot(bot_key)
    if bot.exists():
        if bot.name.lower() != name.lower():
            raise Fail(f"the bot {bot_key} plays as {bot.name}, not as {name}.", code="exists")
    else:
        ws.server(slug)                  # an unknown server is said before anything is made
        bot = create_bot(ws, name, bot_key, account, report)
    return create_instance(ws, bot.key, slug, key, report)


def clone_bot(ws, key, new_key=None, on_event=None):
    """A new character from another: its settings and personality, under a
    name of its own (alice -> alice-1, playing as Alice_1)."""
    report = report_to(on_event)
    src = ws.bot(key).require()
    new_key = (new_key or ws.free_key(src.key, ws.bot_keys())).lower()
    check_key(new_key)
    dst = ws.bot(new_key)
    if dst.dir.exists():
        raise Fail(f"{dst.dir} already exists.", code="exists")
    suffix = new_key[len(src.key):].replace("-", "_") if new_key.startswith(src.key) else ""
    name = (src.name[:16 - len(suffix)] + suffix) if suffix else new_key.replace("-", "_")[:16]
    check_name(name)
    shutil.copytree(src.dir, dst.dir)
    data = dst.data
    data["name"] = name
    dst.save(data)
    report.step(f"bot {new_key} cloned from {src.key} (plays as {name})", stage="created")
    return dst


def clone_instance(ws, key, new_key=None, slug=None, on_event=None):
    """The same bot again, on this server or another: the instance's own
    settings, extra mods, and what it keeps about its world (its config
    folder: places, chests, orders), with a port of its own. Not its login:
    two copies of a login would drift apart, so a clone logs in again.

    Two instances that would be the same player on the same server may
    exist; `start` is what refuses to run both."""
    report = report_to(on_event)
    src = ws.instance(key)
    data = src.data
    slug = slug or data.get("server")
    ws.server(slug)
    new_key = (new_key or ws.free_key(src.key, ws.instance_keys())).lower()
    check_key(new_key, "instance")
    dst = Instance(ws, new_key)
    if dst.dir.exists():
        raise Fail(f"{dst.dir} already exists.", code="exists")
    dst.dir.mkdir(parents=True)
    (dst.gamedir / "mods").mkdir(parents=True)
    (dst.hmc / "HeadlessMC").mkdir(parents=True)
    link_or_copy(ws.shared_dir / "headlessmc-launcher.jar", dst.hmc / "headlessmc-launcher.jar")
    (dst.hmc / "HeadlessMC" / "config.properties").write_text("", encoding="utf-8")
    if src.extra_mods.is_dir():
        shutil.copytree(src.extra_mods, dst.extra_mods)
    if slug == data.get("server") and (src.gamedir / "config").is_dir():
        shutil.copytree(src.gamedir / "config", dst.gamedir / "config")
    if (src.gamedir / "options.txt").is_file():
        shutil.copy2(src.gamedir / "options.txt", dst.gamedir / "options.txt")
    data.update(server=slug, port=ws.free_port(new_key))
    if slug != src.slug:
        data.pop("escort", None)         # its boss's player is on the other server
    dst.save(data)
    settings.render(dst)
    report.step(f"instance {new_key} cloned from {src.key}: {dst.name} on {slug}, port {dst.port}",
                stage="created")
    _copy_own_rules(src, dst, report)
    if settings.get(dst, "account") == "online":
        report.detail(f"online account: log it in once:  marionette.py login {new_key}")
    return dst


def login_command(inst):
    """Online bots use a real, purchased Minecraft Java account (a Microsoft
    account), like any player. With `account online` HeadlessMC keeps the
    login in the instance's own hmc folder: what to run, interactively, to
    log it in there, (argv, cwd, env). None for an offline bot. An instance
    of one of the launcher's accounts is logged in through the account
    (`account add`), and is refused here."""
    value = settings.get(inst, "account")
    if value == "offline":
        return None
    if accounts.kind(value) == "account":
        raise Fail(f"{inst.key} plays with the account {value}: its login is the account's, "
                   "made once with  marionette.py account add", code="has_account")
    return (inst.ws.java_command() + ["-jar", "headlessmc-launcher.jar"],
            inst.hmc, inst.ws.child_env())


def account_of(target):
    """("offline" | "online" | an Account) for what a bot or instance plays with."""
    value = settings.get(target, "account")
    return target.ws.account(value) if accounts.kind(value) == "account" else value


@contextlib.contextmanager
def account_turn(inst):
    """Instances of one account start one at a time: HeadlessMC renews the
    account's login as it launches the game, and two renewals at once would
    leave one of them with a key Microsoft already replaced. The lock is held
    through the start; the check that refuses a second game of a running
    account (check_can_run) does the rest."""
    account = account_of(inst)
    if not isinstance(account, accounts.Account):
        yield
        return
    if not account.exists():
        raise Fail(f"{inst.key} plays with the account {account.key}, which is not there. "
                   f"Log it in:  marionette.py account add --as {account.key}", code="no_account")
    handle = try_lock(account.lock)
    if handle is None:
        raise Fail(f"another instance of the account {account.key} is starting right now "
                   f"(pid {read_pid(account.lock) or '?'}): one at a time.", code="account_busy")
    try:
        yield
    finally:
        handle.close()


# --- who is running, and where ------------------------------------------------

def client_running(inst):
    return keeper_alive(inst) or launcher_pid(inst) is not None or bool(game_pids(inst.port))


def check_can_run(inst):
    """What would make this instance's start a second copy of somebody
    already playing. Instances are cloned freely; this is where two of them
    being the same player is refused:

    - the same player on the same server (Minecraft takes one of each);
    - an online account already playing anywhere: the same Microsoft account
      in two games at once, which servers refuse and which would also let
      two copies of its login drift apart;
    - on the same server, a player whose name contains this one's or is
      contained in it: the bridge reacts when its name appears in the chat,
      and calling one would wake both."""
    ws = inst.ws
    mine = account_of(inst)
    for other in ws.instances():
        if other == inst or not client_running(other):
            continue
        if other.player == inst.player and other.slug == inst.slug:
            raise Fail(f"{inst.name} is already playing on {inst.slug}, as the instance {other.key}.",
                       lines=[f"stop that one first:  marionette.py stop {other.key}"], code="player_taken")
        theirs = account_of(other)
        if mine != "offline" and theirs != "offline" and (
                other.player == inst.player
                or (isinstance(mine, accounts.Account) and isinstance(theirs, accounts.Account)
                    and mine.key == theirs.key)):
            raise Fail(f"{inst.name}'s account is already playing, on {other.slug} (instance {other.key}): "
                       "one Microsoft account plays in one game at a time.",
                       lines=[f"stop that one first:  marionette.py stop {other.key}"], code="account_in_use")
        if other.slug == inst.slug and (inst.player in other.player or other.player in inst.player):
            raise Fail(f"'{inst.name}' and '{other.name}' would be on {inst.slug} together, and one "
                       "name contains the other.",
                       lines=["the bridge would mix them up in the chat. Rename one of the bots."],
                       code="name_clash")


def take_place(inst):
    """The instance becomes the one that plays as its player on its server:
    state/servers/<slug>/bots/<player> links to it. Its bridge reads that
    folder as its bots folder: the bots of its server, under their player
    names, as it always read them."""
    link_dir(inst.dir, inst.place)


def leave_place(inst):
    target = link_target(inst.place)
    if target is not None and pathlib.Path(target) == inst.dir:
        unlink_quietly(inst.place)


# --- start --------------------------------------------------------------------

def prepare_gamedir(inst, server):
    """Everything the mod reads from the gamedir before it starts. ALWAYS
    rewritten: add a mod to a server's pack and the instance would keep
    joining with the old one, which the server rejects with "Incompatible
    client! Please use NeoForge ...", nothing like the real cause. Rebuilding
    costs a second; not doing it costs a mysterious disconnection."""
    config = inst.gamedir / "config"
    config.mkdir(parents=True, exist_ok=True)
    # The mod has no way of knowing WHICH server it joined (they may all share
    # an address and port): the launcher tells it. Per-server memories
    # (places, chests, orders...) are keyed on this.
    (config / "marionette-server.txt").write_text(server.slug + "\n", encoding="utf-8")
    # A guard's leader, from its dependency group: its player, which the body
    # escorts, and its game folder, where the body reads the blocks the leader
    # may break (a guard shares them).
    leader = groups.leader_of(inst)
    escort_f = config / "marionette-escort.txt"
    leader_f = config / "marionette-escort-gamedir.txt"
    if leader is not None:
        escort_f.write_text(leader.name + "\n", encoding="utf-8")
        leader_f.write_text(str(leader.gamedir.resolve()) + "\n", encoding="utf-8")
    else:
        unlink_quietly(escort_f, leader_f)
    # The game's first-run accessibility prompt sits in front of the title
    # screen until somebody clicks, and nobody ever will: it is turned off in
    # options.txt, which the game reads on start. Every other option is left
    # as it is.
    options = inst.gamedir / "options.txt"
    try:
        lines = options.read_text(encoding="utf-8").splitlines()
    except OSError:
        lines = []
    kept = [l for l in lines if not l.startswith("onboardAccessibility:")]
    if kept != lines or not lines or "onboardAccessibility:false" not in lines:
        options.write_text("\n".join(kept + ["onboardAccessibility:false"]) + "\n", encoding="utf-8")
    return sync_mods(inst.ws, inst.gamedir, server.pack, inst.extra_mods)


def join(inst, server, api, report, attempts, patience=18, cancel=None):
    """Send `connect` and wait for the server to list the bot. Each attempt
    waits `patience` x 10 s."""
    report.step(f"connecting to {server.slug} ({server.address})", stage="joining")
    for attempt in range(1, attempts + 1):
        answer = keeper_ask(inst, f"connect {server.address}")
        if answer != "sent":
            report.detail(f"the keeper did not take the command ({answer}); is the client alive?")
            return False
        for i in range(1, patience + 1):
            pause(10, cancel)
            if api.is_inside(inst.name):
                report.step(f"{inst.name} is IN (attempt {attempt}, {i * 10}s)", stage="in")
                return True
            # A client that closed will not join however long this waits: the
            # bot mod closes the game itself on a server without Marionette.
            if not keeper_alive(inst):
                report.detail("the client closed")
                return False
        report.detail(f"attempt {attempt} failed")
    return False


def start(inst, on_event=None, cancel=None):
    """Start an instance's client and put it on its server. Returns JOINED,
    or ALREADY_IN when it was.

    With `cancel` set halfway, it stops what it started (the client it
    launched is stopped, not left loading with nobody waiting for it) and
    raises Cancelled."""
    inst.require()
    with operating(inst), account_turn(inst):
        return _start(inst, report_to(on_event), cancel)


def _start(inst, report, cancel=None):
    _leader_first(inst, report, cancel)
    launched = []
    try:
        return _start_steps(inst, report, cancel, launched)
    except Cancelled:
        if launched:
            report.step("cancelled: stopping the client it had started", stage="cancelling")
            _stop_client(inst, report)
            leave_place(inst)
        raise


def _leader_first(inst, report, cancel):
    """A guard needs its leader: one with the role and no dependency group
    naming it is refused, and a leader that is not running is started first,
    client and bridge. The dependency is declared by hand on purpose: guessed,
    a guard could start next to a leader on another server."""
    group, place = groups.dependency_of(inst)
    role = settings.get(inst, "role")
    if "escort" in inst.data and place is None:
        raise Fail(f"{inst.key} names an escort, which is now a dependency group.",
                   lines=["marionette.py migrate  turns it into one"], code="escort_retired")
    if role != "guard" and place != "guard":
        return
    if place != "guard":
        raise Fail(f"{inst.key} is a guard, and no dependency group names it as one: a guard "
                   "without a leader has nothing to do.",
                   lines=[f"marionette.py group create <group> --leader <instance>, then  "
                          f"marionette.py group add <group> {inst.key}",
                          f"or it is not a guard:  marionette.py set {inst.key} role main"],
                   code="guard_alone")
    if role != "guard":
        raise Fail(f"{inst.key} is a guard of {group.leader} in {group.id}, and its role is {role}.",
                   lines=[f"marionette.py set {inst.key} role guard   or   "
                          f"marionette.py group remove {group.key} {inst.key}"], code="guard_role")
    leader = groups.leader_of(inst)
    if leader is None:
        raise Fail(f"{group.id} has no leader to guard ({group.leader} is not an instance).",
                   code="no_leader")
    if leader.slug != inst.slug:
        raise Fail(f"{inst.key} plays on {inst.slug} and its leader {leader.key} on {leader.slug}: "
                   "a guard plays where its leader does.", code="leader_elsewhere")
    if not client_running(leader) or not bridge_pid(leader):
        report.step(f"{inst.key} guards {leader.key}, which is not running: starting it first",
                    stage="leader")
        try:
            bring_up(leader, on_event=report.sink, cancel=cancel)
        except Fail as e:
            raise Fail(f"its leader {leader.key} did not start, so {inst.key} does not either: {e}",
                       lines=e.lines, code="leader_failed")


def bring_up(inst, on_event=None, cancel=None):
    """Whatever of an instance is not running: its client, in the server,
    then its bridge. Returns JOINED or ALREADY_IN."""
    result = start(inst, on_event, cancel)
    if not bridge_pid(inst):
        start_bridge(inst, on_event)
    return result


def _start_steps(inst, report, cancel, launched):
    ws = inst.ws
    if not inst.bot.exists():
        raise Fail(f"the instance {inst.key} is of the bot {inst.data.get('bot')}, which is not there.",
                   code="no_bot")
    server = ws.server(inst.slug)
    api = ws.api_for(server)

    # Whether it is running is asked BEFORE anything is touched. Preparing
    # first rebuilt the mods folder of a live game; on Windows, where an open
    # jar cannot be deleted, it failed.
    if api.is_inside(inst.name) and client_running(inst):
        report.step(f"{inst.name} is already in. Nothing to do.", stage="in")
        return ALREADY_IN
    check_can_run(inst)
    if api.is_inside(inst.name):
        raise Fail(f"{inst.name} is already on {inst.slug}, and not through this instance.",
                   lines=["another launcher, or a player with that name, is in with it."],
                   code="player_taken")
    # A keeper that answers holds a live client: either this same instance
    # loaded but not connected, or a start that was cut halfway. Starting
    # anyway would launch a second 3 GB java, which is how the OOM killer
    # gets invited.
    answer = keeper_ask(inst, "@ping")
    if answer and answer.startswith("ok "):
        if not port_in_use(inst.port) and log_has(inst.client_log, GAME_OVER):
            # A keeper around a game that already died: not a running bot.
            report.step("a keeper was left holding a game that had exited; stopping it first")
            keeper_ask(inst, "@stop")
            wait_for(lambda: keeper_pid(inst) is None, 30, every=0.5, cancel=cancel)
        else:
            raise Fail(f"{inst.key} is already running (keeper pid {read_pid(inst.keeper_pid_f)}, "
                       f"game pid {answer[3:]}) but not in the server.",
                       lines=[f"try:  marionette.py connect {inst.key}   or   marionette.py stop {inst.key}"],
                       code="running")
    if port_in_use(inst.port):
        holders = game_pids(inst.port)
        raise Fail(f"port {inst.port} is already taken by a live client this launcher does not "
                   f"hold (pids {holders or 'unknown'}).",
                   lines=[f"marionette.py stop {inst.key} takes it down, or give it another port:  "
                          f"marionette.py set {inst.key} port <number>"],
                   code="port_taken")
    clear_run_files(inst)
    if cancel:
        cancel.check()
    # Without Marionette on the server's side there is nothing for the bot to
    # talk to (no /players, no chat for the bridge, no commands): said now,
    # before 3 GB of game are loaded for nothing. The bot mod checks the same
    # from inside the game, for a bot started without this launcher.
    try:
        api.get("/players")
    except urllib.error.HTTPError as e:
        raise Fail(f"the server mod of {inst.slug} at {api.address} said HTTP {e.code}"
                   + (": the token is not the one the server has." if e.code in (401, 403) else "."),
                   code="server_mod_refused")
    except UNREACHABLE as e:
        raise Fail(f"the server mod of {inst.slug} does not answer at {api.address}: "
                   f"{getattr(e, 'reason', e)}.",
                   lines=["Is Marionette in that server's mods folder, and is the server up?",
                          "(and are that address and port the ones in server.env?)"],
                   code="server_mod_down")
    # Its rules, before the game: what the bot is and what is imposed, and
    # changes to its own that waited. A rule written wrong stops the start
    # here: a bot running under rules other than the ones meant is what they
    # exist to prevent.
    try:
        _, sent, refused = rules.push(inst, api)
        for line in sent:
            report.detail(f"rules that waited for the server, sent: {line}")
        if refused:
            report.warning("the server refused rule changes that were waiting:", refused)
    except rules.OldServerMod as e:
        report.warning(f"{e}; its config and the global rules do not reach it")
    except UNREACHABLE as e:
        raise Fail(f"the server mod of {inst.slug} stopped answering at {api.address}: "
                   f"{getattr(e, 'reason', e)}.", code="server_mod_down")

    settings.render(inst)
    report.step(f"preparing the mods of {inst.slug}", stage="preparing")
    report.detail(f"{prepare_gamedir(inst, server)} mods")
    # Said now, from /mods, instead of by the server three minutes from now
    # with a message that names NeoForge. Not refused: some mods take a
    # version they were not built with, and whoever runs this may know.
    theirs = api.mods()
    if theirs is not None:
        diff = compare_packs(pack_mods(ws, server.pack, inst.extra_mods), theirs)
        if diff["mismatch"]:
            report.warning("the server runs other versions than this pack; expect a rejection:",
                           [f"{i}: pack {mine}, server {its}" for i, mine, its in diff["mismatch"]])

    report.step(f"starting {inst.key}: {inst.name} on {inst.slug} (port {inst.port})", stage="launching")
    inst.run.mkdir(parents=True, exist_ok=True)
    # Fresh logs: the signs waited for below ("initialized", "game exited")
    # must be this start's and not the last one's.
    unlink_quietly(inst.client_log, inst.keeper_log)
    if cancel:
        cancel.check()
    take_place(inst)
    spawn_free([sys.executable, str(ENTRY), "keeper", inst.key],
               inst.keeper_log, cwd=ws.home, env=ws.child_env())
    launched.append(True)

    report.step("loading the game", stage="loading")
    # The right signal is NOT that the process exists: it is that the mod has
    # registered its commands. Sending `connect` before that talks to nobody.
    ready = LogWatch(inst.client_log, HMC_READY)
    ended = LogWatch(inst.keeper_log, KEEPER_ENDED, KEEPER_FAILED)
    spawned = time.monotonic()

    def ready_or_dead():
        if ready.saw(HMC_READY) or ended.saw(KEEPER_ENDED) or ended.saw(KEEPER_FAILED):
            return True
        # A keeper that died without a word (killed, or Python failing before
        # it could speak) leaves no line to wait for, and this used to wait
        # the whole five minutes for it. Its pid is the sign: written first
        # thing, gone or a stranger's when it is dead.
        if read_pid(inst.keeper_pid_f) is None:
            return time.monotonic() - spawned > 20
        return keeper_pid(inst) is None

    wait_for(ready_or_dead, 300, every=2, cancel=cancel)
    if ended.saw(KEEPER_FAILED) or (not ready.saw(HMC_READY) and not ended.saw(KEEPER_ENDED)
                                    and keeper_pid(inst) is None):
        raise Fail(f"the keeper of {inst.key} did not get the game going:",
                   lines=tail_lines(inst.keeper_log, 6), code="keeper_failed")
    if not ready.saw(HMC_READY):
        crash = explain_crash(inst)
        if crash:
            lines, code = crash[1], "crashed"
        else:
            lines, code = [], "not_initialized"
            if settings.get(inst, "account") == "online":
                lines.append(f"(online account: if HeadlessMC asked for a login, run marionette.py login {inst.key})")
            lines += tail_lines(inst.client_log, 5) + tail_lines(inst.keeper_log, 3)
        raise Fail("the hmc-specifics mod did not initialize. Without it there is no connect.",
                   lines=lines, code=code)
    # The bot can join the server without hands: if the mod could not open
    # its port, `connect` works anyway and the failure only shows much later,
    # when an order does nothing. Better to know here.
    report.step("waiting for the bot mod's port", stage="hands")
    if not wait_for(lambda: port_in_use(inst.port), 20, every=2, cancel=cancel):
        # A mod that refused to construct (a missing add-on, say) shows up
        # here first: the game goes on loading without it, and crashes a
        # little later with the reason in its report.
        wait_for(lambda: crash_report(inst) is not None, 15, every=3, cancel=cancel)
        crash = explain_crash(inst)
        raise Fail(f"the bot mod did not open port {inst.port}. It would join without hands.",
                   lines=crash[1] if crash else tail_lines(
                       inst.client_log, 5, r"marionette_bot|address already in use|BindException"),
                   code="crashed" if crash else "no_hands")

    # `connect` sent while the game is still loading talks to nobody, and an
    # attempt was lost on every start. The mod says which screen it is on and
    # whether the loading overlay is still up; a mod older than that says
    # nothing, and then the old way, straight in, is all there is.
    def readiness():
        try:
            return inst.ask("/version")
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

    if join(inst, server, api, report, attempts=3, cancel=cancel):
        report.detail(api.players_text())
        return JOINED
    n = len(list((inst.gamedir / "mods").glob("*.jar")))
    raise Fail("it did NOT join. Last complaints of the client:",
               lines=complaints(inst) + [f"it joined with the '{inst.slug}' pack ({n} mods). If the "
                                         "running server is not that one, there is the reason."],
               code="not_joined")


def connect(inst, on_event=None, cancel=None):
    """Puts back on the server an instance whose client is ALIVE at the title
    screen (after /marionette bot <bot> logoff, or a failed connect). It
    starts nothing."""
    report = report_to(on_event)
    inst.require()
    server = inst.ws.server(inst.slug)
    api = inst.ws.api_for(server)
    with operating(inst):
        if not keeper_alive(inst):
            raise Fail(f"no live client of {inst.key}: use  marionette.py start {inst.key}",
                       code="not_running")
        if api.is_inside(inst.name):
            report.step(f"{inst.name} is already in. Nothing to do.", stage="in")
            return ALREADY_IN
        # Cancelled, it only stops trying: the client was running before and
        # it still is.
        if join(inst, server, api, report, attempts=3, patience=12, cancel=cancel):
            return JOINED
    raise Fail("it did NOT join.",
               lines=[f"If the client is hung:  marionette.py restart {inst.key}"], code="not_joined")


# --- the bridge ---------------------------------------------------------------

def bridge_pid(inst):
    """The bridge's pid: from our pid file, or from the lock the bridge itself
    writes, which also covers one started by hand. The lock FILE stays after
    the bridge is gone, with its last pid inside: that pid counts only while
    it is still a bridge."""
    for f in (inst.bridge_pid_f, inst.bridge_lock):
        pid = read_pid(f)
        if is_ours(pid, "bridge.py"):
            return pid
    return None


def bridge_env(inst):
    """What the bridge (and the MCP server under it) is started with. Its
    bots folder is its server's place folder: the instances playing there
    now, under their player names, which is how it looks up its own port,
    its guards and the other bots. Its state folder is its server's: two
    instances of one bot on two servers are two lives, and do not share a
    session or a channel. Its server's own server.env, by PATH, never the
    token. And the instance, so that a restart ordered from the game
    restarts THIS one."""
    env = inst.ws.child_env()
    env["BOT_NAME"] = inst.name
    env["MARIONETTE_BOTS_DIR"] = str(inst.state / "bots")
    env["MARIONETTE_STATE_DIR"] = str(inst.state)
    env["MARIONETTE_INSTANCE"] = inst.key
    env.pop("MARIONETTE_SERVER_ENV", None)
    own = inst.ws.servers_dir / inst.slug / "server.env"
    if inst.slug and own.is_file():
        env["MARIONETTE_SERVER_ENV"] = str(own)
    return env


def start_bridge(inst, on_event=None):
    """Starts the bridge, detached. It goes AFTER the client, and only if it
    joined: without a body in the game it has nobody to write to. Returns
    its pid, when it could be read."""
    report = report_to(on_event)
    inst.require()
    with operating(inst):
        pid = bridge_pid(inst)
        if pid:
            report.step(f"the bridge of {inst.key} is already running (pid {pid})", stage="bridge")
            return pid
        if link_target(inst.place) != inst.dir:
            check_can_run(inst)
            take_place(inst)
        settings.render(inst)
        inst.run.mkdir(parents=True, exist_ok=True)
        inst.state.mkdir(parents=True, exist_ok=True)
        unlink_quietly(inst.bridge_log)
        report.step(f"starting the bridge of {inst.key}", stage="bridge")
        spawn_free([sys.executable, str(REPO / "mcp" / "bridge.py"), inst.name],
                   inst.bridge_log, cwd=inst.ws.home, env=bridge_env(inst))
        # The sign that it started is its own "listening" line, not that a
        # process exists: the bridge can exist and be dying. The log does not
        # lie. Its pid comes from the lock it writes (see bridge_pid).
        if wait_for(lambda: log_has(inst.bridge_log, "listening"), 10, every=1):
            first = tail_lines(inst.bridge_log, 1000)[:1]
            report.detail(f"bridge alive: {first[0] if first else ''}")
            report.detail(f"log at {inst.bridge_log}")
            return bridge_pid(inst)
    raise Fail(f"the bridge did NOT start. {inst.name} is in the game but MUTE:",
               lines=tail_lines(inst.bridge_log, 20), code="bridge_failed")


# --- stop and restart ---------------------------------------------------------

def _stop_client(inst, report):
    """The client alone: the keeper, the HeadlessMC under it and the game."""
    keeper = keeper_pid(inst)
    launcher = launcher_pid(inst)
    answer = keeper_ask(inst, "@stop")
    if answer == "stopping":
        # The keeper gives the game 25 s to shut down on its own before it
        # kills it, then leaves; a little more than that here.
        if wait_for(lambda: not is_ours(keeper, "keeper") and not port_in_use(inst.port),
                    45, every=0.5):
            report.detail("client: stopped")
        else:
            report.detail("client: the keeper did not leave nicely; insisting")
            stop_game(inst.port, launcher)
            if keeper_pid(inst):
                terminate(keeper)
    elif launcher or keeper or game_pids(inst.port):
        # No keeper answering, but something of the client is there: a keeper
        # that died, or a game started some other way on this port.
        stop_game(inst.port, launcher)
        if keeper:
            terminate(keeper)
        report.detail("client: stopped (the keeper was not answering)")
    else:
        report.detail("client: nothing was running")
    clear_run_files(inst)


def stop(inst, keep_guards=False, on_event=None, _stopped=None):
    """Stops an instance: the client (through its keeper) and its bridge.
    Processes are found by what identifies THAT instance and no other: its
    own pid files, each checked to still name the process it was written
    for. A bare kill by pattern would take down every bot, which is exactly
    what must not happen when there are two."""
    report = report_to(on_event)
    inst.require()
    stopped = set() if _stopped is None else _stopped
    stopped.add(inst.key)
    with operating(inst):
        report.step(f"stopping {inst.key} ({inst.name} on {inst.slug}, port {inst.port})", stage="stopping")
        _stop_client(inst, report)
        pid = bridge_pid(inst)
        if pid:
            terminate(pid)
            report.detail("bridge: stopped")
        else:
            report.detail("bridge: nothing was running")
        unlink_quietly(inst.bridge_pid_f)
        leave_place(inst)
        report.step(f"{inst.key} stopped", stage="stopped")

    # Its guards leave with it: a guard without a boss has nothing to do. A
    # RESTART is not a disconnection: the guards stay. Only guards that are
    # running, and each once: two guarding each other, or one guarding
    # itself, used to send this round and round until Python gave up.
    if not keep_guards:
        for g in inst.guards():
            if g.key in stopped or not client_running(g):
                continue
            report.step(f"{g.key} is a guard of {inst.name}: stopping it too")
            try:
                stop(g, keep_guards=False, on_event=report, _stopped=stopped)
            except Fail as e:
                report.warning(str(e), e.lines)


def restart(inst, on_event=None, cancel=None):
    """Restarts a WHOLE instance: client and bridge. It exists because of an
    easy mistake: after deploying a new mod both processes get killed, but
    only the client gets started again. The bot stays in the game, visible
    in /players, and mute, which from the chat looks exactly like a hang."""
    report = report_to(on_event)
    inst.require()
    with operating(inst), account_turn(inst):
        stop(inst, keep_guards=True, on_event=report)
        try:
            _start(inst, report, cancel)
        except Cancelled:
            raise
        except Fail as e:
            raise Fail(str(e), lines=e.lines + ("the client did not join: the bridge was NOT started.",),
                       code=e.code)
        return start_bridge(inst, on_event=report)


# --- settings -----------------------------------------------------------------

def configure(target, key, value=None, clear=False, on_event=None):
    """Set one setting in a bot's layer or an instance's (see settings.py), or
    with `clear` take it out of that layer. Returns the value that applies
    now (for a bot: in its own layer, or the default). A setting read when
    the client starts is refused while a client it concerns runs: the file
    would say one thing and the running game another."""
    report = report_to(on_event)
    target.require()
    s = settings.setting(key)
    if isinstance(target, Instance):
        affected = [target]
    elif isinstance(target, groups.Group):
        affected = groups.flatten(target)
    elif isinstance(target, groups.Global):
        affected = target.ws.instances()
    else:
        affected = target.instances()
    if s.at_start:
        running = [i.key for i in affected if client_running(i)]
        if running:
            raise Fail(f"{', '.join(running)} running, and {key} is read when it starts. "
                       f"Stop it first:  marionette.py stop {running[0]}", code="running")
    if clear:
        settings.clear(target, key)
    else:
        settings.set_value(target, key, value)
    for inst in affected:
        settings.render(inst)
    if key in ("ignore_global", "lock"):
        _push_running(affected, report)
    now, layer = settings.resolve(target, key)
    who = target.key if isinstance(target, Instance) else (
        f"bot {target.key}" if isinstance(target, Character) else target.id)
    report.step(f"{who}: {key} = {now or '(nothing)'}" + ("" if layer == settings.layer_of(target)
                                                          else f"  (from the {layer})"), stage="configured")
    report.detail(f"it counts {settings.APPLIES[s.applies]}")
    return now


# --- accounts -------------------------------------------------------------------

def add_account(ws, run_login, key=None, on_event=None):
    """A Minecraft account, logged in once: HeadlessMC is opened in a folder of
    its own (`run_login(argv, cwd, env)` runs it, interactively: type
    `login`, follow its steps in a browser, `quit`), and what it saved becomes
    accounts/<player>/. Returns the Account."""
    report = report_to(on_event)
    argv, cwd, env, folder = accounts.prepare_login(ws)
    report.step("HeadlessMC to log an account in. Type:  login   (then follow its steps in a "
                "browser)", lines=["and once it says the account is saved:  quit"], stage="login")
    try:
        run_login(argv, cwd, env)
    except BaseException:
        import shutil as _sh
        _sh.rmtree(folder, ignore_errors=True)
        raise
    account = accounts.finish_login(ws, folder, key)
    report.step(f"account {account.key}: plays as {account.name}", stage="added")
    report.detail(f"a bot plays with it with:  marionette.py set --bot <bot> account {account.key}")
    return account


def account_list(ws):
    """[(account, logged in, bots, instances)] for every account."""
    out = []
    for key in ws.account_keys():
        account = ws.account(key)
        bots, insts = account.users()
        out.append((account, account.logged_in(), bots, insts))
    return out


def remove_account(ws, key, on_event=None):
    report = report_to(on_event)
    accounts.remove(ws, key)
    report.step(f"account {key} removed, with its login", stage="removed")


# --- what it says without its brain ---------------------------------------------

def phrases_file(inst):
    """Where its brain's versions of the sentences said without it are (see the
    bridge's write_phrases): in the game's config folder, which the body reads."""
    return inst.gamedir / "config" / "marionette-phrases.properties"


def rewrite_phrases(inst, on_event=None):
    """Have the brain write again, in its own voice, the sentences said without
    it: after the personality changed, say. With its bridge running, a mark it
    sees within a poll; without, the old ones go and the next start writes
    new ones. Returns "asked" or "on the next start"."""
    report = report_to(on_event)
    inst.require()
    if bridge_pid(inst):
        inst.state.mkdir(parents=True, exist_ok=True)
        (inst.state / f"phrases_{inst.player}").write_text("", encoding="utf-8")
        report.step(f"{inst.key}: its bridge is writing its sentences again", stage="phrases")
        report.detail(f"it takes a brain turn; see {inst.bridge_log}")
        return "asked"
    unlink_quietly(phrases_file(inst))
    report.step(f"{inst.key}: its sentences will be written when its bridge next starts", stage="phrases")
    return "on the next start"


# --- groups ---------------------------------------------------------------------

def _member(ws, ref):
    """An instance or a group, by name: `group:<name>` or `instance:<name>`
    when a name is both."""
    kind, _, name = ref.partition(":") if ":" in ref else ("", "", ref)
    inst, group = Instance(ws, name), ws.group(name)
    if kind == "instance" or (not kind and inst.exists() and not group.exists()):
        return inst.require()
    if kind == "group" or (not kind and group.exists() and not inst.exists()):
        return group.require()
    if not kind and inst.exists() and group.exists():
        raise Fail(f"{name} is an instance and a group: say which, instance:{name} or group:{name}",
                   code="ambiguous")
    raise Fail(f"there is no instance or group {name}.", code="no_member")


def create_group(ws, key, leader=None, on_event=None):
    """A group: a normal one, or, with a leader, a dependency one (a leader
    and its guards)."""
    report = report_to(on_event)
    key = key.lower()
    groups.check_group_key(key)
    group = ws.group(key)
    if group.dir.exists():
        raise Fail(f"there is already a group {key}.", code="exists")
    if leader is None:
        group.save({"kind": groups.NORMAL, "instances": [], "groups": []})
        report.step(f"group {key} created: add instances and groups to it with  "
                    f"marionette.py group add {key} ...", stage="created")
        return group
    lead = ws.instance(leader)
    _check_free(ws, lead)
    group.save({"kind": groups.DEPENDENCY, "leader": lead.key, "guards": []})
    report.step(f"dependency group {key} created, led by {lead.key} ({lead.name} on {lead.slug}): add its "
                f"guards with  marionette.py group add {key} <instance>", stage="created")
    return group


def _check_free(ws, node):
    """One group at most: what imposes on it is then one chain."""
    parent = groups.parent_of(ws, node)
    if parent is not None:
        raise Fail(f"{node.id} is already in {parent.id}: one group at most, so what imposes on it is "
                   "never two groups that disagree.",
                   lines=[f"take it out first:  marionette.py group remove {parent.key} {node.key}"],
                   code="in_a_group")
    if isinstance(node, Instance) and groups.dependency_of(node)[1] == "leader":
        raise Fail(f"{node.key} leads a dependency group: that group goes in, not the instance.",
                   code="in_a_group")


def group_add(ws, key, refs, on_event=None):
    """Instances and groups into a normal group; guards into a dependency
    one (on its leader's server, with the role of a guard, which is set on
    the instance if it had another)."""
    report = report_to(on_event)
    group = ws.group(key).require()
    data = group.data
    for ref in refs:
        node = _member(ws, ref)
        _check_free(ws, node)
        if group.kind == groups.DEPENDENCY:
            if not isinstance(node, Instance):
                raise Fail("a dependency group holds a leader and its guards: instances, not groups.",
                           code="not_an_instance")
            lead = Instance(ws, group.leader)
            if node == lead:
                raise Fail(f"{node.key} is the leader of {group.id}.", code="leader")
            if node.slug != lead.slug:
                raise Fail(f"{node.key} plays on {node.slug} and the leader {lead.key} on {lead.slug}: a guard "
                           "plays where its leader does.", code="leader_elsewhere")
            data["guards"] = data.get("guards", []) + [node.key]
            group.save(data)
            if settings.get(node, "role") != "guard":
                settings.set_value(node, "role", "guard")
                report.detail(f"{node.key}: role guard (set on the instance)")
            settings.render(node)
            report.step(f"{node.key} guards {lead.key} ({group.id})", stage="grouped")
            continue
        if isinstance(node, groups.Group) and (node == group or group in groups.subgroups(node)):
            raise Fail(f"{node.id} would end up inside itself.", code="cycle")
        field = "instances" if isinstance(node, Instance) else "groups"
        data[field] = data.get(field, []) + [node.key]
        group.save(data)
        report.step(f"{node.id} is in {group.id} now", stage="grouped")
    return group


def group_remove(ws, key, refs, on_event=None):
    report = report_to(on_event)
    group = ws.group(key).require()
    data = group.data
    for ref in refs:
        node = _member(ws, ref)
        field = ("guards" if group.kind == groups.DEPENDENCY else
                 "instances" if isinstance(node, Instance) else "groups")
        if node.key not in data.get(field, []):
            if group.kind == groups.DEPENDENCY and node.key == group.leader:
                raise Fail(f"{node.key} leads {group.id}: delete the group instead "
                           f"(marionette.py group delete {group.key}).", code="leader")
            raise Fail(f"{node.id} is not in {group.id}.", code="not_in_group")
        data[field] = [k for k in data[field] if k != node.key]
        group.save(data)
        if isinstance(node, Instance):
            settings.render(node)
        report.step(f"{node.id} is out of {group.id}", stage="ungrouped")
        if group.kind == groups.DEPENDENCY and settings.get(node, "role") == "guard":
            report.detail(f"it is still a guard, with no leader: it will not start until it has one, or "
                          f"marionette.py set {node.key} role main")
    return group


def delete_group(ws, key, on_event=None):
    """The group goes, not what was in it: its instances and groups stay,
    in no group. Nothing is moved into the group around it behind anyone's
    back."""
    report = report_to(on_event)
    group = ws.group(key).require()
    parent = groups.parent_of(ws, group)
    if parent is not None:
        pdata = parent.data
        pdata["groups"] = [k for k in pdata.get("groups", []) if k != group.key]
        parent.save(pdata)
    inside = group.instance_keys() + group.group_keys()
    members = [Instance(ws, k) for k in group.instance_keys()]
    shutil.rmtree(group.dir)
    for inst in members:
        if inst.exists():
            settings.render(inst)         # a guard of it escorts nobody now
    report.step(f"{group.id} deleted" + (f"; {', '.join(inside)} are in no group now" if inside else ""),
                stage="deleted")


def clone_group(ws, key, new_key=None, on_event=None):
    """A copy of a group and of everything in it: each instance cloned (on
    its own server, with a name of its own: alice-1), each group inside
    cloned the same way, and the copy holding the copies. Its settings and
    rules come along. Nothing is asked: the clones are the same players as
    the originals, and `start` is what refuses to run both."""
    report = report_to(on_event)
    src = ws.group(key).require()
    return _clone_group(ws, src, new_key, report)


def _clone_group(ws, src, new_key, report):
    new_key = (new_key or ws.free_key(src.key, ws.group_keys())).lower()
    groups.check_group_key(new_key)
    dst = ws.group(new_key)
    if dst.dir.exists():
        raise Fail(f"there is already a group {new_key}.", code="exists")
    # Taken now, so a group inside called like it does not take the name.
    dst.save({"kind": src.kind})
    data = src.data

    def copy(key_):
        return clone_instance(ws, key_, on_event=report.sink).key

    if src.kind == groups.DEPENDENCY:
        data["leader"] = copy(src.leader) if src.leader else None
        data["guards"] = [copy(k) for k in src.guards]
    else:
        data["instances"] = [copy(k) for k in src.instance_keys()]
        data["groups"] = [_clone_group(ws, ws.group(k), None, report).key for k in src.group_keys()
                          if ws.group(k).exists()]
    dst.save(data)
    for key_ in dst.instance_keys():
        settings.render(Instance(ws, key_))   # its guards escort its leader
    report.step(f"{dst.id} cloned from {src.key}", stage="created")
    return dst


def _heaps_fit(instances, report):
    """A warning when these would not fit in the memory there is."""
    from .doctor import free_memory_gb, heap_gb
    avail = free_memory_gb()
    need = sum(heap_gb(settings.get(i, "heap")) or 0 for i in instances)
    if avail is not None and need > avail:
        report.warning(f"{len(instances)} instance(s) want {need:g} GB of heap and {avail:.1f} GB are "
                       "available: not all of them may start")


def start_group(ws, key, on_event=None, cancel=None):
    """Everything in a group, and in the groups inside it: each client, in
    its server, then its bridge; leaders before their guards (a guard whose
    leader is outside the group brings it along). What is running is left
    as it is. One that does not start does not stop the rest, except its
    guards. Returns (the ones that started or were in, the ones that did not,
    with why)."""
    report = report_to(on_event)
    group = ws.group(key).require()
    every = groups.flatten(group)
    todo = [i for i in every if not (client_running(i) and bridge_pid(i))]
    report.step(f"starting {group.id}: {len(every)} instance(s), {len(todo)} to start", stage="group")
    _heaps_fit(todo, report)
    up, failed = [i for i in every if i not in todo], []
    for inst in todo:
        if cancel:
            cancel.check()
        leader = groups.leader_of(inst)
        if leader is not None and leader.key in {k for k, _ in failed}:
            failed.append((inst.key, f"its leader {leader.key} did not start"))
            report.warning(f"{inst.key}: not started, its leader {leader.key} did not start")
            continue
        try:
            bring_up(inst, on_event=report.sink, cancel=cancel)
            up.append(inst)
        except Cancelled:
            raise
        except Fail as e:
            failed.append((inst.key, str(e)))
            report.warning(f"{inst.key} did not start: {e}", list(e.lines))
    report.step(f"{group.id}: {len(up)} running" + (f", {len(failed)} not: "
                + ", ".join(k for k, _ in failed) if failed else ""), stage="group")
    return up, failed


def stop_group(ws, key, on_event=None):
    """Everything in a group, and in the groups inside it."""
    report = report_to(on_event)
    group = ws.group(key).require()
    stopped = set()
    for inst in groups.flatten(group):
        if inst.key in stopped or not (client_running(inst) or bridge_pid(inst)):
            continue
        stop(inst, on_event=report.sink, _stopped=stopped)
    report.step(f"{group.id} stopped ({len(stopped)} instance(s))", stage="group")
    return stopped


def group_tree(ws):
    """The groups as a tree: [(depth, node, what it is)], top-level groups
    first, then the instances in no group."""
    rows = []

    def walk(g, depth, seen):
        if g.key in seen:
            return
        seen = seen | {g.key}
        lock = settings.get(g, "lock") == "yes"
        rows.append((depth, g, g.kind + (", locked" if lock else "")))
        if g.kind == groups.DEPENDENCY:
            for k in g.instance_keys():
                inst = Instance(ws, k)
                what = "leader" if k == g.leader else "guard"
                rows.append((depth + 1, inst, f"{what}: {inst.name} on {inst.slug}" if inst.exists()
                             else f"{what}: (no such instance)"))
            return
        for k in g.instance_keys():
            inst = Instance(ws, k)
            rows.append((depth + 1, inst, f"{inst.name} on {inst.slug}" if inst.exists()
                         else "(no such instance)"))
        for k in g.group_keys():
            sub = ws.group(k)
            if sub.exists():
                walk(sub, depth + 1, seen)

    for g in ws.groups():
        if groups.parent_of(ws, g) is None:
            walk(g, 0, set())
    loose = [i for i in ws.instances() if groups.parent_of(ws, i) is None]
    return rows, loose


# --- rules ----------------------------------------------------------------------

@dataclass(frozen=True)
class RulesView:
    """What `rules` shows of an instance: every toggle and both lists as they
    come out, each with who decides it ("set here", "its config", "imposed
    by global", or "" for what every bot starts with)."""
    title: str
    note: str
    toggles: tuple        # (key, on, who)
    lists: tuple          # (family, [(id, who)] on the list, [(id, who)] taken off, who replaces it or "")
    waiting: tuple        # own-layer changes that wait for the server


def _view(title, note, base, own, imposed, waiting):
    everything = rules.merge(rules.merge(base, own), imposed)
    held = rules.effective(base, own, imposed)

    def who(family, key):
        return rules.say_source(*rules.source(base, own, imposed, family, key))

    toggles = tuple((k, held["prefs"][k], who("prefs", k)) for k in rules.TOGGLES)
    lists = []
    for family in rules.FAMILIES:
        on = held["food_banned" if family == "food" else "break_allowed"]
        off = sorted(i for i, v in everything[family].items() if not v)
        lists.append((family, [(i, who(family, i)) for i in on], [(i, who(family, i)) for i in off],
                      who(family, "*") if family in everything["replace"] else ""))
    return RulesView(title, note, toggles, tuple(lists), tuple(waiting))


def _api_of(inst):
    return inst.ws.api_for(inst.ws.server(inst.slug))


def show_rules(inst):
    """An instance's rules as they come out: its own layer from its server,
    the rest as this launcher holds it. Changes that waited for the server
    go first, if it answers now."""
    inst.require()
    base, imposed = rules.base_of(inst), rules.imposed_of(inst)
    title = f"{inst.key}: {inst.name} on {inst.slug}"
    own, note = rules.empty(), ""
    try:
        api = _api_of(inst)
        answer = rules.ask(api, inst.name)
        if rules.pending(inst):
            rules.send_pending(api, inst)
            answer = rules.ask(api, inst.name)
        server_base, own, server_imposed = rules.layers_in(answer)
        if rules.dump(server_base) != rules.dump(base) or rules.dump(server_imposed) != rules.dump(imposed):
            note = "its server still holds an older config or global rules for it: they go on its next start"
    except rules.NotKnownYet:
        note = "its server has not seen it yet: this is what it gets on its first start"
    except rules.OldServerMod as e:
        note = f"{e}; without its own layer"
    except UNREACHABLE:
        note = "its server does not answer: shown without its own layer, which lives there"
    waiting = [" ".join(rules.pretty_change(i.get("kind"), i.get("key"), i.get("value")))
               if "set" not in i else "its own rules, copied from another instance"
               for i in rules.pending(inst)]
    return _view(title, note, base, own, imposed, waiting)


def _refuse_imposed(inst, change):
    kind, key, _ = change
    imposed = rules.imposed_of(inst)
    family = "prefs" if kind == "pref" else kind
    layer, label = rules.source(rules.empty(), rules.empty(), imposed, family, key)
    if layer == "imposed":
        what = f"its whole {kind} list" if key == "*" or family in imposed["replace"] else key
        if label.startswith("group "):
            fix = [f"that group's rules:  marionette.py rules --group {label[6:]} ...",
                   f"or the groups around this instance leave it alone:  marionette.py set {inst.key} lock yes"]
        else:
            fix = ["the global rules:  marionette.py rules --global ...",
                   f"or this instance ignores them:  marionette.py set {inst.key} ignore_global yes"]
        raise Fail(f"{what} is {rules.say_source(layer, label)}: it is changed there, not here",
                   lines=fix, code="imposed")


def edit_rules(inst, words, on_event=None):
    """One change to an instance's own rules (see rules.edit for the words).
    They live on its server, so the change goes there; when the server does
    not answer it waits in the instance's folder for its next start. Refused
    here, as in the game, when the global rules decide it."""
    report = report_to(on_event)
    inst.require()
    _, change = rules.edit(rules.empty(), words)
    _refuse_imposed(inst, change)
    kind, key, value = change
    said = " ".join(rules.pretty_change(kind, key, value))
    api = _api_of(inst)
    try:
        try:
            rules.ask(api, inst.name)
            sent, refused = rules.send_pending(api, inst)
        except rules.NotKnownYet:
            # Never started there: its server learns of it from its config
            # first, and takes what waited after that.
            _, sent, refused = rules.push(inst, api)
        if rules.pending(inst):
            raise OSError("changes before this one still wait")
        rules.ask(api, inst.name, layer="own", kind=kind, key=key, value=value)
    except UNREACHABLE:
        rules.keep_pending(inst, rules.pending(inst) + [{"kind": kind, "key": key, "value": value}])
        report.step(f"{inst.key}: {said}", stage="rules")
        report.detail(f"{inst.slug}'s server mod does not answer: it goes on its next start")
        return "waiting"
    for line in sent:
        report.detail(f"sent, it was waiting: {line}")
    if refused:
        report.warning("the server refused changes that were waiting:", refused)
    report.step(f"{inst.key}: {said}", stage="rules")
    report.detail("it applies now" if client_running(inst) else "it applies when it starts")
    return "sent"


def _push_running(instances, report):
    """The launcher's rules to the running ones among these, now; the others
    get them on their next start."""
    running = [i for i in instances if client_running(i)]
    for inst in running:
        try:
            rules.push(inst, _api_of(inst))
            report.detail(f"{inst.key}: sent, it applies now")
        except UNREACHABLE:
            report.warning(f"{inst.key}: its server mod does not answer; it goes on its next start")
        except Fail as e:
            report.warning(f"{inst.key}: {e}")
    rest = len(instances) - len(running)
    if rest:
        report.detail(f"{rest} not running: on their next start")


def edit_layer(ws, words, bot=None, slug=None, group=None, on_event=None):
    """One change to the rules this launcher holds: a bot's (bot.json), a
    server's (servers/<slug>/rules.json), a group's (group.json), which it
    imposes on what is inside it, or, with none of them, the global ones
    (launcher.json), imposed on every instance. The running instances they
    concern get them at once."""
    report = report_to(on_event)
    if bot is not None:
        bot.require()
        layer, where, concerned = rules.of_bot(bot), f"bot {bot.key}", bot.instances()
    elif slug is not None:
        ws.server(slug)
        layer, where, concerned = rules.of_server(ws, slug), f"server {slug}", ws.instances(slug)
    elif group is not None:
        group.require()
        layer, where, concerned = rules.of_group(group), group.id, groups.flatten(group)
    else:
        layer, where = rules.of_global(ws), "global"
        concerned = [i for i in ws.instances() if settings.get(i, "ignore_global") != "yes"]
    layer, change = rules.edit(layer, words)
    if bot is not None:
        rules.save_bot(bot, layer)
    elif slug is not None:
        rules.save_server(ws, slug, layer)
    elif group is not None:
        rules.save_group(group, layer)
    else:
        rules.save_global(ws, layer)
    report.step(f"{where}: {' '.join(rules.pretty_change(*change))}", stage="rules")
    _push_running(concerned, report)
    return layer


def layer_lines(ws, bot=None, slug=None, group=None):
    """(where it is kept, the layer in a few lines)."""
    if bot is not None:
        bot.require()
        return str(bot.json), rules.describe(rules.of_bot(bot))
    if slug is not None:
        ws.server(slug)
        return str(ws.servers_dir / slug / "rules.json"), rules.describe(rules.of_server(ws, slug))
    if group is not None:
        group.require()
        return str(group.json), rules.describe(rules.of_group(group))
    return str(ws.config_file), rules.describe(rules.of_global(ws))


def _copy_own_rules(src, dst, report):
    """An instance's own rules live on its server, per player. A clone that
    is the same player on the same server shares them; one elsewhere gets a
    copy, sent on its first start."""
    if dst.slug == src.slug and dst.player == src.player:
        report.detail(f"it shares its own rules with {src.key}: the same player on the same server")
        return
    try:
        own = rules.layers_in(rules.ask(_api_of(src), src.name))[1]
    except UNREACHABLE + (Fail,):
        report.detail(f"its own rules stayed with {src.key}: {src.slug}'s server mod did not give them")
        return
    if not rules.is_empty(own):
        rules.keep_pending(dst, [{"set": rules.dump(own)}])
        report.detail("its own rules go with it, on its first start")


# --- looking ------------------------------------------------------------------

@dataclass(frozen=True)
class InstanceStatus:
    key: str
    name: str             # the player
    server: str
    port: int
    client: bool          # a keeper answers, or a game carries its port
    hands: bool           # the bot mod's port is open
    inside: object        # True/False, or None when the server mod was not asked
    bridge: object        # the bridge's pid, or None
    guard_of: str


def status_of(inst, api=None):
    running = client_running(inst)
    return InstanceStatus(key=inst.key, name=inst.name, server=inst.slug or "-", port=inst.port,
                          client=running, hands=port_in_use(inst.port),
                          inside=(api.is_inside(inst.name) and running) if api else None,
                          bridge=bridge_pid(inst), guard_of=_leader_name(inst))


def _leader_name(inst):
    leader = groups.leader_of(inst)
    return leader.name if leader is not None else ""


def survey(ws, key=None):
    """(what is wrong with the server mods asked, as a list; the status of
    every instance, or of one). Each instance is looked for in ITS server's
    mod, and each server mod is asked once here, not once per instance with
    a 5 s timeout each."""
    instances = [ws.instance(key)] if key else ws.instances()
    apis, problems = {}, []

    def api_of(inst):
        slug = inst.slug
        if slug not in apis:
            api = None
            try:
                api = ws.api_for(ws.server(slug))
                api.get("/players")
            except Fail as e:
                problems.append(f"{slug or inst.key}: {e}")
                api = None
            except UNREACHABLE as e:
                problems.append(f"the server mod of {slug} at {api.address} does not answer: "
                                f"{getattr(e, 'reason', e)}")
                api = None
            apis[slug] = api
        return apis[slug]

    return problems, [status_of(i, api_of(i)) for i in instances]


# --- the layout before instances ------------------------------------------------

# The files of a bot folder from before instances, and where each one goes.
LEGACY_BOT = ("account", "model", "owner", "heap")
# What was once a setting and is now the personality's to say.
LEGACY_RETIRED = ("language", "gender")
LEGACY_INSTANCE = ("escort",)


def migrate(ws, dry_run=False, on_event=None):
    """bots/<name>/, which held the bot and its game in one folder, becomes a
    bot (bots/<name>/bot.json and its personality) and an instance
    (instances/<name>/: instance.json, and hmc/, gamedir/ and run/ MOVED, not
    copied, so it takes a second and no space). The state its bridge kept
    in the state folder moves to its server's. Before anything, a backup of
    every small file (not the games) goes to state/backups/. A bot that is
    running is left alone: stop it first.

    And an instance that names an `escort` (a guard, from before groups)
    goes into a dependency group led by that player's instance on its server.

    Returns the instances made."""
    report = report_to(on_event)
    legacy = ws.legacy_bots()
    if not legacy and not _escorted(ws):
        report.step("nothing to migrate: no bot folder in the layout from before instances, "
                    "and no escort to turn into a group")
        return []
    made = _migrate_legacy(ws, legacy, dry_run, report) if legacy else []
    _escorts_to_groups(ws, dry_run, report)
    return made


def _escorted(ws):
    return [i for i in ws.instances() if i.data.get("escort")]


def _escorts_to_groups(ws, dry_run, report):
    """`escort` was an instance setting naming its leader's player; it is a
    dependency group now, declared once, which also stops the leader's
    guards with it and starts the leader before them."""
    escorted = _escorted(ws)
    if escorted and not dry_run:
        backups = ws.state_dir / "backups"
        backups.mkdir(parents=True, exist_ok=True)
        backup = backups / time.strftime("escorts-before-groups-%Y%m%d-%H%M%S.tar.gz")
        with tarfile.open(backup, "w:gz") as tar:
            for inst in escorted:
                tar.add(inst.json, arcname=f"{inst.key}/instance.json")
        report.detail(f"backup of the instances with an escort: {backup}")
    for inst in escorted:
        boss = str(inst.data["escort"]).strip().lower()
        here = [i for i in ws.instances(inst.slug) if i.player == boss and i != inst]
        if not here:
            report.warning(f"{inst.key}: its escort {boss} is not an instance on {inst.slug}; left as it was")
            continue
        leader = next((i for i in here if i.key == boss), here[0])
        existing = next((g for g in ws.groups() if g.kind == groups.DEPENDENCY and g.leader == leader.key),
                        None)
        key = existing.key if existing else ws.free_key(f"{leader.key}-guards", ws.group_keys())
        if dry_run:
            report.detail(f"{inst.key}: guard of {leader.key}, in the dependency group {key}")
            continue
        parent = groups.parent_of(ws, inst)
        if parent is not None and parent != existing:
            report.warning(f"{inst.key}: it is in {parent.id} already; its escort is left as it was")
            continue
        if existing is None:
            if groups.parent_of(ws, leader) is not None:
                report.warning(f"{leader.key} is in {groups.parent_of(ws, leader).id}: the new group "
                               f"{key} is not put there; add it:  marionette.py group add ... group:{key}")
            existing = ws.group(key)
            existing.save({"kind": groups.DEPENDENCY, "leader": leader.key, "guards": []})
        gdata = existing.data
        if inst.key not in gdata.get("guards", []):
            gdata["guards"] = gdata.get("guards", []) + [inst.key]
            existing.save(gdata)
        data = inst.data
        data.pop("escort", None)
        if settings.get(inst, "role") != "guard":
            data["role"] = "guard"
        inst.save(data)
        settings.render(inst)
        report.detail(f"{inst.key}: guard of {leader.key}, in the dependency group {key}")


def _migrate_legacy(ws, legacy, dry_run, report):
    plan = []
    for key in legacy:
        d = ws.bots_dir / key
        props = read_java_properties(d / "hmc" / "HeadlessMC" / "config.properties")
        name = props.get("hmc.offline.username") or key
        slug = _read(d / "server")
        try:
            port = int(_read(d / "port"))
        except ValueError:
            port = None
        game_port_busy = port is not None and (port_in_use(port) or bool(game_pids(port)))
        if game_port_busy or is_ours(read_pid(d / "run" / "keeper.pid"), "keeper"):
            raise Fail(f"{key} is running: stop it before migrating (with the launcher of before, "
                       "or by closing its game).", code="running")
        plan.append((key, name, slug, port))
    report.step(f"migrating {len(plan)} bot(s): " + ", ".join(k for k, *_ in plan), stage="migrating")
    if dry_run:
        for key, name, slug, port in plan:
            report.detail(f"{key}: bot {key} (plays as {name}) + instance {key} on {slug or '(no server!)'}, "
                          f"port {port}")
        return []

    backups = ws.state_dir / "backups"
    backups.mkdir(parents=True, exist_ok=True)
    backup = backups / time.strftime("bots-before-instances-%Y%m%d-%H%M%S.tar.gz")
    with tarfile.open(backup, "w:gz") as tar:
        for key, *_ in plan:
            for f in sorted((ws.bots_dir / key).iterdir()):
                if f.is_file():
                    tar.add(f, arcname=f"{key}/{f.name}")
            cfg = ws.bots_dir / key / "hmc" / "HeadlessMC" / "config.properties"
            if cfg.is_file():
                tar.add(cfg, arcname=f"{key}/hmc/HeadlessMC/config.properties")
    report.detail(f"backup of the small files: {backup}")

    made = []
    for key, name, slug, port in plan:
        d = ws.bots_dir / key
        bot_data = {"name": name}
        for k in LEGACY_BOT:
            v = _read(d / k)
            if v:
                bot_data[k] = v
        inst_key = ws.free_key(key, ws.instance_keys())
        inst = Instance(ws, inst_key)
        inst.dir.mkdir(parents=True, exist_ok=True)
        for sub in ("hmc", "gamedir", "run"):
            if (d / sub).exists():
                shutil.move(str(d / sub), str(inst.dir / sub))
        inst_data = {"bot": key, "server": slug, "port": port or ws.free_port(inst_key)}
        for k in LEGACY_INSTANCE:
            v = _read(d / k)
            if v:
                inst_data[k] = v
        write_json(ws.bots_dir / key / "bot.json", bot_data)
        inst.save(inst_data)
        retired = {k: _read(d / k) for k in LEGACY_RETIRED if _read(d / k)}
        for k in LEGACY_BOT + LEGACY_INSTANCE + LEGACY_RETIRED + ("port", "server"):
            unlink_quietly(d / k)
        moved = _move_state(ws, name.lower(), slug) if slug else 0
        settings.render(inst)
        made.append(inst)
        report.detail(f"{key}: bot {key} (plays as {name}) + instance {inst_key} on {slug or '(no server!)'}"
                      + (f"; {moved} state file(s) moved to {ws.server_state(slug)}" if moved else ""))
        if retired:
            report.warning(f"{key}: " + ", ".join(f"{k} {v}" for k, v in retired.items())
                           + " are no longer settings: say them in its personality.txt",
                           [str(ws.bot(key).personality)])
    report.step("migrated. `marionette.py status` lists the instances.", stage="migrated")
    return made


def _read(path):
    try:
        return pathlib.Path(path).read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def _move_state(ws, player, slug):
    """What a bridge and its MCP server kept about this player in the state
    folder (session, jobs left pending, the internal channel, the marks,
    the horse, the call log) moves to its server's state folder, where the
    bridge of the instance looks now."""
    src, dst = ws.state_dir, ws.server_state(slug)
    rx = re.compile(rf"^[a-z]+_{re.escape(player)}(\.[A-Za-z.]+|_.+)?$")
    moved = 0
    if not src.is_dir():
        return 0
    for f in sorted(src.iterdir()):
        if f.is_file() and rx.match(f.name) and not f.name.endswith(".lock"):
            dst.mkdir(parents=True, exist_ok=True)
            shutil.move(str(f), str(dst / f.name))
            moved += 1
    return moved


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

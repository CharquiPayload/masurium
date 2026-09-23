"""The command line: one face on the operations. It parses, calls, and prints
what the operations report; it decides nothing a window would decide
differently."""
import argparse
import os
import subprocess
import threading

from . import doctor, operations, settings
from .events import Cancel, Cancelled, Fail
from .keeper import keeper_main
from .processes import detached
from .workspace import Workspace


def say(text=""):
    print(text, flush=True)


def print_event(e):
    prefix = "    " if e.kind == "detail" else "==> "
    say(prefix + e.text)
    for line in e.lines:
        say("    " + line)


def print_fail(e):
    say(str(e))
    for line in e.lines:
        say("    " + line)


def cancellable(run):
    """run(cancel) with Ctrl+C turned into a cancel: the first one asks the
    operation to stop, and it stops what it had started (a game left loading
    with nobody waiting for it is what a bare Ctrl+C used to leave, since the
    keeper lives in a session of its own). The second one leaves at once.

    The operation runs on a thread and this one waits, which is also how a
    window will run it: the Ctrl+C lands here, in plain code, and not inside
    whatever the operation was doing."""
    cancel = Cancel()
    outcome = {}
    finished = threading.Event()

    def work():
        try:
            outcome["value"] = run(cancel)
        except BaseException as e:          # handed to the waiting thread as it came
            outcome["error"] = e
        finally:
            finished.set()

    worker = threading.Thread(target=work, name="operation", daemon=True)
    worker.start()
    # Waited for through an Event the operation sets, not through join(): on
    # Python 3.12 and older a Ctrl+C that lands inside join() leaves the thread
    # looking finished while it runs, and the command left at once, exit code
    # 0, with the game it had launched still loading and nobody stopping it.
    while not finished.is_set():
        try:
            finished.wait(0.2)
        except KeyboardInterrupt:
            if cancel.is_set():
                raise
            say()
            say("==> cancelling: stopping what it started (Ctrl+C again to leave at once)")
            cancel.set()
    if "error" in outcome:
        raise outcome["error"]
    return outcome.get("value")


# --- commands -----------------------------------------------------------------

def cmd_servers(ws, args):
    say("servers:")
    for line in ws.servers_listing():
        say("  " + line)


def next_steps(inst):
    say()
    say(f"==> its personality, to write:  {inst.personality}")
    say(f"    its settings:  masurium.py set {inst.key}")
    if settings.get(inst, "account") == "offline":
        say("    offline: only for private servers with online-mode=false.")
    say(f"    start it with:  masurium.py start {inst.key}")


def cmd_create(ws, args):
    inst = operations.create(ws, args.name, args.server, args.account, args.as_, print_event)
    next_steps(inst)


def cmd_clone(ws, args):
    inst = operations.clone_instance(ws, args.instance, args.as_, args.server, print_event)
    next_steps(inst)


def cmd_delete(ws, args):
    """masurium.py delete alice --yes   the instance, its folder and all"""
    if not args.yes:
        raise Fail(f"this deletes the instance {args.instance}, its folder and all (its personality, what it "
                   "keeps about its world, its logs, its extra mods). Say it again with --yes.", code="confirm")
    operations.delete_instance(ws, args.instance, print_event)


def cmd_start(ws, args):
    inst = ws.instance(args.name)
    cancellable(lambda cancel: operations.start(inst, print_event, cancel))


def cmd_connect(ws, args):
    inst = ws.instance(args.name)
    cancellable(lambda cancel: operations.connect(inst, print_event, cancel))


def cmd_bridge(ws, args):
    operations.start_bridge(ws.instance(args.name), print_event)


def cmd_stop(ws, args):
    operations.stop(ws.instance(args.name), args.keep_guards, print_event)


def cmd_restart(ws, args):
    inst = ws.instance(args.name)
    cancellable(lambda cancel: operations.restart(inst, print_event, cancel))


def cmd_status(ws, args):
    if not args.name and not ws.instance_keys():
        say(f"no instances under {ws.instances_dir}")
        if ws.old_bots():
            say("(there are bots apart from their instances: masurium.py migrate)")
        return 0
    problems, statuses = operations.survey(ws, args.name)
    for problem in problems:
        say(f"({problem}; 'in server' is unknown for its instances)")
    yes_no = lambda v: "-" if v is None else ("yes" if v else "no")
    say(f"  {'instance':<16} {'plays as':<16} {'server':<14} {'port':<5} {'client':<7} {'hands':<6} "
        f"{'in server':<10} {'bridge':<10} {'guard of'}")
    for s in statuses:
        bridge = f"pid {s.bridge}" if s.bridge else "no"
        say(f"  {s.key:<16} {s.name:<16} {s.server:<14} {s.port:<5} {yes_no(s.client):<7} "
            f"{yes_no(s.hands):<6} {yes_no(s.inside):<10} {bridge:<10} {s.guard_of}")


def cmd_set(ws, args):
    """masurium.py set alice                  every setting of the instance, and where it comes from
    masurium.py set alice heap               one
    masurium.py set alice heap 4g            change it, for this instance
    masurium.py set alice heap --default     take it out of the instance: back to the default
    masurium.py set alice name Alice_2       its player name, offline
    masurium.py set --group team model haiku      imposed on everything in the group
    masurium.py set --global model sonnet         imposed on every instance (launcher.json)"""
    if args.global_:
        # With --global there is no name: what came as one is the setting.
        words = [w for w in (args.name, args.key) if w] + list(args.value or [])
        args.name, args.key, args.value = None, (words[0] if words else None), words[1:]
        target = ws.global_config()
    elif not args.name:
        raise Fail("say whose:  masurium.py set <instance>, or --group <group>, --global",
                   code="bad_setting")
    elif args.group:
        target = ws.group(args.name).require()
    else:
        target = ws.instance(args.name)
    if args.value or args.default:
        if not args.key:
            raise Fail("say which setting:  masurium.py set <instance> <setting> <value>",
                       code="bad_setting")
        operations.configure(target, args.key, " ".join(args.value or []), clear=args.default,
                             on_event=print_event)
        return 0
    layer = settings.layer_of(target)
    keys = [settings.setting(args.key).key] if args.key else [
        k for k, s in settings.SETTINGS.items() if layer in s.layers]
    for key in keys:
        s = settings.SETTINGS[key]
        value, source = settings.resolve(target, key)
        mark = "" if source == layer else f"  ({source})"
        say(f"  {key:<9} {(str(value) or '-') + mark:<26} {s.help}")
        if args.key:
            if s.choices:
                say(f"  {'':<9} choices: {', '.join(s.choices)}")
            say(f"  {'':<9} set per {' or '.join(s.layers)}; counts {settings.APPLIES[s.applies]}")
    for key, why in settings.problems(target):
        if key in keys:
            say(f"  !! {why}")
    return 0


def cmd_account(ws, args):
    """masurium.py account                  every Microsoft account, whether it is logged in, who uses it
    masurium.py account add [--as KEY]       log one in, once (HeadlessMC opens: login, then quit)
    masurium.py account remove KEY           out of the launcher, login and all"""
    if args.action == "add":
        operations.add_account(ws, lambda argv, cwd, env: subprocess.call(argv, cwd=str(cwd), env=env),
                               args.as_, print_event)
        return 0
    if args.action == "remove":
        if not args.key:
            raise Fail("say which:  masurium.py account remove <account>", code="no_account")
        operations.remove_account(ws, args.key, print_event)
        return 0
    rows = operations.account_list(ws)
    if not rows:
        say(f"no accounts under {ws.accounts_dir}: masurium.py account add")
        return 0
    for account, logged, users in rows:
        say(f"  {account.key:<16} plays as {account.name:<16} "
            f"{'logged in' if logged else 'NOT logged in':<14} used by {', '.join(users) or 'nobody'}")
    return 0


def cmd_rules(ws, args):
    """masurium.py rules alice                        its rules as they come out, and who decides each
    masurium.py rules alice food ban rotten_flesh      a change to its own (the same as /masurium bot)
    masurium.py rules alice pref hunt_players on       ... on, off or default
    masurium.py rules --group team break allow oak_log   imposed on everything in the group
    masurium.py rules --global food replace               imposed on every instance"""
    layered = args.group or args.global_
    words = ([args.name] if args.name and layered else []) + list(args.words)
    if layered:
        group = ws.group(args.group) if args.group else None
        if not words:
            where, lines = operations.layer_lines(ws, group=group)
            say(f"{where}:")
            for line in lines or ["(nothing: it decides nothing)"]:
                say(f"  {line}")
            return 0
        operations.edit_layer(ws, words, group=group, on_event=print_event)
        return 0
    if not args.name:
        raise Fail("say whose:  masurium.py rules <instance>, or --group <group>, --global", code="bad_rules")
    inst = ws.instance(args.name)
    if words:
        operations.edit_rules(inst, words, on_event=print_event)
        return 0
    view = operations.show_rules(inst)
    say(view.title)
    if view.note:
        say(f"  ({view.note})")
    say("  toggles")
    for key, on, who in view.toggles:
        say(f"    {key:<24} {'on' if on else 'off':<4} {who}")
    heads = {"food": "food it does not eat on its own", "break": "blocks it may break on its own"}
    offs = {"food": "allowed on purpose", "break": "forbidden on purpose"}
    for family, listed, off, replaced in view.lists:
        say(f"  {heads[family]}" + (f"  (the whole list: {replaced})" if replaced else ""))
        say("    " + (", ".join(i + (f" ({who})" if who else "") for i, who in listed) or "nothing"))
        if off:
            say(f"    {offs[family]}: " + ", ".join(i + (f" ({who})" if who else "") for i, who in off))
    if view.waiting:
        say("  waiting for the server: " + "; ".join(view.waiting))
    return 0


def cmd_groups(ws, args):
    rows, loose = operations.group_tree(ws)
    if not rows:
        say(f"no groups under {ws.groups_dir}")
    for depth, node, what in rows:
        say(f"  {'  ' * depth}{node.key:<{max(4, 22 - 2 * depth)}} {what}")
    if loose:
        say(f"  in no group: {', '.join(i.key for i in loose)}")


def cmd_group(ws, args):
    """masurium.py group create team                   a normal group
    masurium.py group create alice-guards --leader alice   a dependency group: a leader and its guards
    masurium.py group add team carol group:alice-guards   instances and groups into it (guards, in a dependency one)
    masurium.py group remove team carol
    masurium.py group start team | stop team          everything in it
    masurium.py group clone team [--as NAME]          it and everything in it, instances included
    masurium.py group delete team                     the group; what was in it stays
    masurium.py group team                            what it is and what is in it"""
    action, name, rest = args.action, args.name, list(args.members)
    actions = ("create", "add", "remove", "delete", "clone", "start", "stop")
    if action not in actions:
        # `group team`: show it.
        group = ws.group(action).require()
        say(f"{group.id}: {group.kind}" + (", locked" if settings.get(group, "lock") == "yes" else ""))
        if group.kind == "dependency":
            say(f"  leader: {group.leader}")
            say(f"  guards: {', '.join(group.guards) or '(none yet)'}")
        else:
            say(f"  instances: {', '.join(group.instance_keys()) or '(none)'}")
            say(f"  groups: {', '.join(group.group_keys()) or '(none)'}")
        given = settings.own_values(group)
        say("  settings: " + (", ".join(f"{k} {v}" for k, v in given.items()) or "none"))
        where, lines = operations.layer_lines(ws, group=group)
        say("  rules: " + ("; ".join(lines) or "none"))
        return 0
    if not name:
        raise Fail(f"say which group:  masurium.py group {action} <group>", code="bad_group")
    if action == "create":
        operations.create_group(ws, name, leader=args.leader, on_event=print_event)
    elif action in ("add", "remove"):
        if not rest:
            raise Fail(f"say what:  masurium.py group {action} {name} <instance or group>...",
                       code="bad_group")
        fn = operations.group_add if action == "add" else operations.group_remove
        fn(ws, name, rest, on_event=print_event)
    elif action == "delete":
        operations.delete_group(ws, name, on_event=print_event)
    elif action == "clone":
        operations.clone_group(ws, name, args.as_, on_event=print_event)
    elif action == "start":
        _, failed = cancellable(lambda cancel: operations.start_group(ws, name, print_event, cancel))
        return 1 if failed else 0
    elif action == "stop":
        operations.stop_group(ws, name, on_event=print_event)
    return 0


def cmd_gui(ws, args):
    import importlib.util
    if importlib.util.find_spec("PySide6") is None:
        raise Fail("the window needs PySide6 (Qt for Python), which the command line does not:",
                   lines=["the installers bring it (install.sh, the .deb); by hand:  pip install PySide6-Essentials",
                          "then:  masurium.py gui"], code="no_pyside")
    from .gui import main as gui_main
    return gui_main(["masurium"], ws)


def cmd_phrases(ws, args):
    operations.rewrite_phrases(ws.instance(args.name), print_event)


def cmd_deploy_mod(ws, args):
    operations.deploy_mod(ws, args.jar, print_event)


def cmd_doctor(ws, args):
    bad = 0
    for label, ok, detail in doctor.checks(ws):
        mark = "ok " if ok else ("!! " if ok is False else "-- ")
        bad += ok is False
        say(f"  {mark} {label:<34} {detail}")
    say()
    say("everything checks out" if not bad else f"{bad} problem(s) above")
    return 1 if bad else 0


def print_needs(needs):
    for n in needs:
        mark = "ok " if n.ok else ("-> " if n.can else "!! ")
        say(f"  {mark} {n.label:<34} {n.detail}")


def cmd_setup(ws, args):
    """What a first bot needs, done where it can be and said where not."""
    from . import firstrun
    say("Setting up this machine for Masurium:")
    print_needs(firstrun.needs(ws))
    say()
    left = cancellable(lambda cancel: firstrun.run(
        ws, host=args.host, port=args.port, token=args.token, owner=args.owner, server=args.server,
        game_port=args.game_port, download_them=not args.no_download, test=not args.no_check,
        on_event=print_event, cancel=cancel))
    say()
    print_needs(firstrun.needs(ws))
    say()
    if left:
        say(f"{len(left)} thing(s) still to do above (!! is yours to do; -> setup does it)")
        return 1
    say("ready: create a bot with  masurium.py create <player> <server>  or in the window,  masurium.py gui")
    return 0


def cmd_migrate(ws, args):
    operations.migrate(ws, args.dry_run, print_event)


def cmd_keeper(ws, args):
    inst = ws.instance(args.name)
    return keeper_main(inst, ws.server(inst.slug))


def cmd_spawn(ws, args):
    command = args.args[1:] if args.args[:1] == ["--"] else args.args
    detached(command, args.log, cwd=args.cwd, env=dict(os.environ))


# --- entry point --------------------------------------------------------------

def build_parser():
    p = argparse.ArgumentParser(
        prog="masurium.py",
        description="Create, start, stop and watch Masurium bots. Each bot is an instance: a player "
                    "on a server, with its personality and settings, and what starts and stops.")
    sub = p.add_subparsers(dest="command", metavar="command")
    sub.required = True

    sub.add_parser("servers", help="list the servers in the registry").set_defaults(fn=cmd_servers)

    c = sub.add_parser("create", help="create a bot: an instance on a server")
    c.add_argument("name", help="its player name in the game (offline); with --account, the account's player "
                   "plays and this only names the instance")
    c.add_argument("server", help="a slug from `servers`")
    c.add_argument("--account", metavar="ACCOUNT", help="a Microsoft account from  masurium.py account "
                   "(default: offline, for private servers with online-mode=false)")
    c.add_argument("--as", dest="as_", metavar="INSTANCE", help="the instance's name (default: the player "
                   "name in lowercase)")
    c.set_defaults(fn=cmd_create)

    c = sub.add_parser("clone", help="a copy of an instance, on this server or another")
    c.add_argument("instance")
    c.add_argument("--server", help="where the clone plays (default: the same server)")
    c.add_argument("--as", dest="as_", metavar="INSTANCE", help="its name (default: <instance>-1, -2...)")
    c.set_defaults(fn=cmd_clone)

    c = sub.add_parser("delete", help="an instance out of the launcher, its folder and all")
    c.add_argument("instance")
    c.add_argument("--yes", action="store_true", help="yes, delete it")
    c.set_defaults(fn=cmd_delete)

    c = sub.add_parser("start", help="start an instance's client and put it on its server")
    c.add_argument("name", metavar="instance")
    c.set_defaults(fn=cmd_start)

    c = sub.add_parser("connect", help="put a live client back on its server")
    c.add_argument("name", metavar="instance")
    c.set_defaults(fn=cmd_connect)

    c = sub.add_parser("bridge", help="start an instance's bridge (its client must be in)")
    c.add_argument("name", metavar="instance")
    c.set_defaults(fn=cmd_bridge)

    c = sub.add_parser("stop", help="stop an instance: client, bridge and its guards")
    c.add_argument("name", metavar="instance")
    c.add_argument("--keep-guards", action="store_true", help="leave its guards running")
    c.set_defaults(fn=cmd_stop)

    c = sub.add_parser("restart", help="stop and start a whole instance: client and bridge")
    c.add_argument("name", metavar="instance")
    c.set_defaults(fn=cmd_restart)

    c = sub.add_parser("status", help="what every instance is doing")
    c.add_argument("name", nargs="?", metavar="instance")
    c.set_defaults(fn=cmd_status)

    c = sub.add_parser("set", help="see or change settings (model, heap, owner, role...), per instance, "
                                   "group or globally")
    c.add_argument("name", nargs="?", metavar="instance", help="an instance, or a group with --group; "
                   "nothing with --global")
    c.add_argument("key", nargs="?", help="one setting; without it, all of them")
    c.add_argument("value", nargs="*", help="its new value (a model may be two words: haiku low)")
    c.add_argument("--default", action="store_true", help="take it out of this layer")
    c.add_argument("--group", action="store_true", help="NAME is a group: imposed on everything in it")
    c.add_argument("--global", dest="global_", action="store_true",
                   help="imposed on every instance (launcher.json)")
    c.set_defaults(fn=cmd_set)

    sub.add_parser("groups", help="the groups, as a tree, and the instances in none").set_defaults(fn=cmd_groups)
    sub.add_parser("gui", help="the window: the same launcher, with a face").set_defaults(fn=cmd_gui)

    c = sub.add_parser("group", help="create, fill, start, stop, clone or delete a group")
    c.add_argument("action", help="create, add, remove, delete, clone, start, stop; or a group's name to see it")
    c.add_argument("name", nargs="?", metavar="group")
    c.add_argument("members", nargs="*", help="for add and remove: instances and groups (group:<name> "
                   "when a name is both)")
    c.add_argument("--leader", metavar="INSTANCE", help="for create: a dependency group, led by it")
    c.add_argument("--as", dest="as_", metavar="GROUP", help="for clone: its name (default: <group>-1...)")
    c.set_defaults(fn=cmd_group)

    c = sub.add_parser("account", help="Microsoft accounts: list, add (log in once), remove")
    c.add_argument("action", nargs="?", choices=("list", "add", "remove"), default="list")
    c.add_argument("key", nargs="?", metavar="account", help="for remove: which")
    c.add_argument("--as", dest="as_", metavar="ACCOUNT", help="for add: its name here (default: the player's)")
    c.set_defaults(fn=cmd_account)

    c = sub.add_parser("rules", help="see or change a bot's rules: toggles, food it will not eat, "
                                     "blocks it may break")
    c.add_argument("name", nargs="?", metavar="instance", help="an instance (or none, with --global)")
    c.add_argument("words", nargs="*", help="a change: pref <toggle> on|off|default, food ban|allow|default "
                   "<item>, break allow|forbid|default <block>, food|break replace|add")
    c.add_argument("--group", metavar="GROUP", help="a group's, imposed on everything in it")
    c.add_argument("--global", dest="global_", action="store_true",
                   help="imposed on every instance (launcher.json)")
    c.set_defaults(fn=cmd_rules)

    c = sub.add_parser("phrases", help="have the brain write again what the bot says without it")
    c.add_argument("name", metavar="instance")
    c.set_defaults(fn=cmd_phrases)

    c = sub.add_parser("deploy-mod", help="put a built jar in shared/mods, safely (the core by default)")
    c.add_argument("jar", nargs="?", help="a jar to deploy instead of the core, such as an add-on's")
    c.set_defaults(fn=cmd_deploy_mod)
    sub.add_parser("doctor", help="check the machine, the folders and the servers").set_defaults(fn=cmd_doctor)

    c = sub.add_parser("setup", help="get this machine ready for its first bot: downloads, the way to the "
                                     "server, the first server (asks what it needs)")
    c.add_argument("--host", help="where the server's Masurium mod listens")
    c.add_argument("--port", help="its port (8477 unless changed)")
    c.add_argument("--token", help="its token (`token` in the masurium.properties next to the server's jar)")
    c.add_argument("--owner", help="your player name, the bots' owner")
    c.add_argument("--server", help="a name for the first server, such as my-server")
    c.add_argument("--game-port", help="its game port (25565 unless changed)")
    c.add_argument("--no-download", action="store_true", help="do not download HeadlessMC nor hmc-specifics")
    c.add_argument("--no-check", action="store_true", help="save the way to the server without asking it")
    c.set_defaults(fn=cmd_setup)

    c = sub.add_parser("migrate", help="fold the bots kept apart into their instances (a backup first)")
    c.add_argument("--dry-run", action="store_true", help="only say what it would do")
    c.set_defaults(fn=cmd_migrate)

    c = sub.add_parser("keeper", help=argparse.SUPPRESS)   # internal: started by `start`
    c.add_argument("name")
    c.set_defaults(fn=cmd_keeper)

    c = sub.add_parser("spawn", help=argparse.SUPPRESS)    # internal: see spawn_free
    c.add_argument("--log", required=True)
    c.add_argument("--cwd", required=True)
    c.add_argument("args", nargs=argparse.REMAINDER)
    c.set_defaults(fn=cmd_spawn)
    return p


def main(argv=None, ws=None):
    args = build_parser().parse_args(argv)
    try:
        return args.fn(ws or Workspace.from_environment(), args) or 0
    except Cancelled as e:
        print_fail(e)
        return 130
    except Fail as e:
        print_fail(e)
        return 1
    except KeyboardInterrupt:
        return 130

"""The command line: one face on the operations. It parses, calls, and prints
what the operations report; it decides nothing a window would decide
differently."""
import argparse
import os
import subprocess
import threading

from . import doctor, operations
from .bots import operating
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

    def work():
        try:
            outcome["value"] = run(cancel)
        except BaseException as e:          # handed to the waiting thread as it came
            outcome["error"] = e

    worker = threading.Thread(target=work, name="operation", daemon=True)
    worker.start()
    while worker.is_alive():
        try:
            worker.join(0.2)
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


def cmd_create(ws, args):
    bot = operations.create_bot(ws, args.name, args.server, args.account, print_event)
    say(f"==> files you may want to edit in {bot.dir}: personality.txt, language (en/es),")
    say("    owner (a player name), model, escort (makes it a guard of that bot).")
    say()
    if bot.read("account") == "online":
        say(f"log in its Minecraft account once:  marionette.py login {args.name}")
        say(f"then start it with:                 marionette.py start {args.name}")
    else:
        say("offline account: only for private servers with online-mode=false.")
        say(f"start it with:  marionette.py start {args.name}")


def cmd_login(ws, args):
    """Opens HeadlessMC interactively: type `login`, follow its instructions,
    and `quit`."""
    bot = ws.bot(args.name).require()
    command = operations.login_command(bot)
    if command is None:
        say(f"{bot.name} uses an offline account; there is nothing to log in.")
        return 0
    argv, cwd, env = command
    with operating(bot):
        say(f"==> HeadlessMC for {bot.name}. Type:  login   (then follow the instructions)")
        say("    and when the account is saved:  quit")
        return subprocess.call(argv, cwd=str(cwd), env=env)


def cmd_start(ws, args):
    bot = ws.bot(args.name)
    cancellable(lambda cancel: operations.start(bot, args.server, print_event, cancel))


def cmd_connect(ws, args):
    bot = ws.bot(args.name)
    cancellable(lambda cancel: operations.connect(bot, print_event, cancel))


def cmd_bridge(ws, args):
    operations.start_bridge(ws.bot(args.name), print_event)


def cmd_stop(ws, args):
    operations.stop(ws.bot(args.name), args.keep_guards, print_event)


def cmd_restart(ws, args):
    bot = ws.bot(args.name)
    cancellable(lambda cancel: operations.restart(bot, args.server, print_event, cancel))


def cmd_status(ws, args):
    if not args.name and not ws.bot_keys():
        say(f"no bots under {ws.bots_dir}")
        return 0
    problem, statuses = operations.survey(ws, args.name)
    if problem:
        say(f"({problem}; 'in server' is unknown)")
    yes_no = lambda v: "-" if v is None else ("yes" if v else "no")
    say(f"  {'bot':<16} {'server':<14} {'port':<5} {'client':<7} {'hands':<6} "
        f"{'in server':<10} {'bridge':<7} {'guard of'}")
    for s in statuses:
        bridge = f"pid {s.bridge}" if s.bridge else "no"
        say(f"  {s.name:<16} {s.server:<14} {s.port:<5} {yes_no(s.client):<7} {yes_no(s.hands):<6} "
            f"{yes_no(s.inside):<10} {bridge:<7} {s.guard_of}")


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


def cmd_keeper(ws, args):
    bot = ws.bot(args.name).require()
    return keeper_main(bot, ws.server(args.server))


def cmd_spawn(ws, args):
    command = args.args[1:] if args.args[:1] == ["--"] else args.args
    detached(command, args.log, cwd=args.cwd, env=dict(os.environ))


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

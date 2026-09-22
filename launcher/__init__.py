"""The launcher: one tool to create, start, stop and watch bots.

Standard library only, and no shell. It replaced eight bash scripts that leaned
on FIFOs, `ss`, `pgrep` and `setsid`, none of which exist on Windows. Every
primitive here has an equivalent on every platform Python runs on, so porting
is testing, not rewriting.

Two layers. The core never prints: it takes a Workspace (where the folders
are), reports what it does as Events and fails with Fail. The faces show it:
the command line (cli.py, reached through marionette.py) today, a window
tomorrow, both on the same functions.

    workspace.py   the three folders, server.env, the environment; the server registry
    bots.py        a bot's folder and files, and the lock that keeps two commands off it
    settings.py    what each of a bot's files accepts, its default, when a change counts
    packs.py       what a pack is made of, read from the jars; the pack owns gamedir/mods
    api.py         the server mod's HTTP API
    keeper.py      the process that holds a game's console
    processes.py   pids, process groups, ports
    diagnosis.py   why a start failed, from the client's own logs
    operations.py  create, start, connect, bridge, stop, restart, status, set, deploy-mod
    doctor.py      the checks, in the order things break
    events.py      Event and Fail
    files.py       env files, locks, logs read as they grow
    cli.py         the command line

A program uses it like this:

    from launcher import Workspace, operations
    ws = Workspace.from_environment()
    operations.start(ws.bot("Alice"), on_event=print)
"""
from . import doctor, operations
from .bots import Bot
from .events import Event, Fail
from .workspace import Server, Workspace

__all__ = ["Bot", "Event", "Fail", "Server", "Workspace", "doctor", "operations"]

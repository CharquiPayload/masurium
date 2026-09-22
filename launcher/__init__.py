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
    accounts.py    Minecraft accounts: logged in once, linked into each instance
    bots.py        bots (characters) and instances (a bot on a server), and the lock
                   that keeps two commands off an instance
    settings.py    settings in layers (bot < instance), what each accepts, when it counts,
                   and what is rendered for the bridge
    rules.py       a bot's rules (toggles, food, blocks) in three layers, kept on its server
    packs.py       what a pack is made of, read from the jars; the pack owns gamedir/mods
    api.py         the server mod's HTTP API
    keeper.py      the process that holds a game's console
    processes.py   pids, process groups, ports
    diagnosis.py   why a start failed, from the client's own logs
    operations.py  create, clone, start, connect, bridge, stop, restart, status, set,
                   rules, deploy-mod, migrate
    doctor.py      the checks, in the order things break
    events.py      Event and Fail
    files.py       env files, locks, logs read as they grow
    cli.py         the command line

A program uses it like this:

    from launcher import Workspace, operations
    ws = Workspace.from_environment()
    operations.start(ws.instance("alice"), on_event=print)
"""
from . import doctor, operations, settings
from .bots import Character, Instance
from .events import Cancel, Event, Fail
from .workspace import Server, Workspace

__all__ = ["Cancel", "Character", "Event", "Fail", "Instance", "Server", "Workspace",
           "doctor", "operations", "settings"]

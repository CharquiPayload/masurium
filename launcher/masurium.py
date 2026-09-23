#!/usr/bin/env python3
"""The launcher's command: create, start, stop and watch bots.

    masurium.py servers
    masurium.py create <name> <server> [--account online|offline]
    masurium.py login <name>
    masurium.py start <name> [server]
    masurium.py connect <name>
    masurium.py bridge <name>
    masurium.py stop <name> [--keep-guards]
    masurium.py restart <name> [server]
    masurium.py status [name]
    masurium.py set <name> [setting [value | --default]]
    masurium.py rules <instance> [change] | --bot <bot> | --server <slug> | --group <group> | --global
    masurium.py groups
    masurium.py gui                 the window (needs PySide6)
    masurium.py group create|add|remove|delete|clone|start|stop <group> ...
    masurium.py phrases <instance>
    masurium.py account [add | remove <account>]
    masurium.py deploy-mod [jar]
    masurium.py doctor
    masurium.py setup [--host H --token T ...]   the first time: downloads, the server

The same as `python3 -m launcher` from the repository. The code is the rest of
this folder, the `launcher` package (see its __init__); this file is only the
door everybody already knows, the bridge included.
"""
import pathlib
import sys

if __name__ == "__main__":
    # The repository, not this folder, is where `launcher` is imported from:
    # with this folder first on the path, its modules would be importable by
    # their bare names and could shadow others. An embedded Python (the one
    # the Windows setup brings) takes its path from its ._pth file and does
    # not put this folder on it at all: there the repository goes in front,
    # and the standard library's entry stays where it is.
    here = pathlib.Path(__file__).resolve().parent
    if sys.path and pathlib.Path(sys.path[0] or ".").resolve() == here:
        sys.path[0] = str(here.parent)
    else:
        sys.path.insert(0, str(here.parent))
    from launcher.cli import main
    sys.exit(main())

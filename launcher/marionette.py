#!/usr/bin/env python3
"""The launcher's command: create, start, stop and watch bots.

    marionette.py servers
    marionette.py create <name> <server> [--account online|offline]
    marionette.py login <name>
    marionette.py start <name> [server]
    marionette.py connect <name>
    marionette.py bridge <name>
    marionette.py stop <name> [--keep-guards]
    marionette.py restart <name> [server]
    marionette.py status [name]
    marionette.py set <name> [setting [value | --default]]
    marionette.py deploy-mod [jar]
    marionette.py doctor

The same as `python3 -m launcher` from the repository. The code is the rest of
this folder, the `launcher` package (see its __init__); this file is only the
door everybody already knows, the bridge included.
"""
import pathlib
import sys

if __name__ == "__main__":
    # The repository, not this folder, is where `launcher` is imported from:
    # with this folder first on the path, its modules would be importable by
    # their bare names and could shadow others.
    sys.path[0] = str(pathlib.Path(__file__).resolve().parent.parent)
    from launcher.cli import main
    sys.exit(main())

"""The window: the launcher's second face, on the same operations as the
command line.

It needs PySide6 (Qt for Python), which the core does not: the command line
runs on the standard library alone, and nothing outside this package imports
Qt. Started with  marionette.py gui  or  python -m launcher.gui.

    theme.py     how it looks: Fusion, dark, lavender
    tasks.py     operations on threads, their events back as signals
    state.py     what the window shows, read on a thread every few seconds
    widgets.py   a tile per instance, a section per group
    window.py    the main window
    dialogs.py   rules, settings, new instance, groups, logs, doctor, accounts...
"""


def main(argv=None, ws=None):
    import sys

    from PySide6.QtWidgets import QApplication

    from ..workspace import Workspace
    from . import theme
    from .window import MainWindow

    app = QApplication.instance() or QApplication(list(argv or sys.argv))
    app.setApplicationName("Marionette")
    theme.apply(app)
    win = MainWindow(ws or Workspace.from_environment())
    win.show()
    return app.exec()

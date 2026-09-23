"""The window: the launcher's second face, on the same operations as the
command line.

It needs PySide6 (Qt for Python), which the core does not: the command line
runs on the standard library alone, and nothing outside this package imports
Qt. Started with  marionette.py gui  or  python -m launcher.gui.

    theme.py     how it looks: Fusion, with colour presets (lavender, classic; dark, light)
    icons.py     its icons, drawn in code, and the gesture each makes when hovered
    anim.py      motion: fades and slides, off in the settings
    tasks.py     operations on threads, their events back as signals
    state.py     what the window shows, read on a thread every few seconds
    widgets.py   a tile per instance, a section per group, the switch
    window.py    the main window, laid out as Prism Launcher's
    common.py    what every dialog shares: its frame, its bottom row, a console
    pages.py     the pages of the paged windows: settings, rules, personality, mods, logs...
    dialogs.py   Edit Instance, Settings, new instance or group, change group, copy, doctor, bots
"""


def main(argv=None, ws=None):
    import sys

    from PySide6.QtGui import QIcon
    from PySide6.QtWidgets import QApplication

    from ..workspace import Workspace
    from . import theme
    from .window import MainWindow

    app = QApplication.instance() or QApplication(list(argv or sys.argv))
    app.setApplicationName("Marionette")
    theme.apply(app)
    app.setWindowIcon(QIcon(theme.avatar("Marionette", 64, colour=theme.ACCENT)))   # until it has a logo
    win = MainWindow(ws or Workspace.from_environment())
    win.show()
    return app.exec()

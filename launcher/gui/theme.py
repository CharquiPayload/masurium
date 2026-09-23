"""How the window looks: Qt's Fusion style, with a colour preset on top.

Fusion draws the same on every platform, which is the point: the window a
person sees on Windows is the one that was looked at on Linux. A preset is a
handful of colours; the stylesheet and the palette are made from it, and
switching presets restyles the open window at once (the launcher's settings).
"""
import atexit
import os
import shutil
import tempfile
import zlib

from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import QColor, QFont, QPainter, QPainterPath, QPalette, QPen, QPixmap, QPolygonF

PRESETS = {
    "Lavender dark": dict(
        base="#1e1b26", panel="#262233", card="#2a2638", dialog="#26222f", field="#231f2e",
        border="#3a3450", strong_border="#3f3858", hover="#5a4d80", text="#e6e1f0", muted="#a79fbd",
        faint="#6b6480", accent="#7c62d6", accent_light="#a58cf0", accent_hover="#8b72e0",
        header="#b9a6e8", selected="#4a3f73", tile_selected="#352e4d", button="#2f2a40",
        pressed="#3b3356", section="#221e2c", drop="#2d2742", separator="#332d45", switch_off="#4a4460"),
    "Lavender light": dict(
        base="#f4f1fa", panel="#ebe6f5", card="#ffffff", dialog="#f8f6fc", field="#ffffff",
        border="#d8d0ea", strong_border="#cbc1e0", hover="#9f8bd6", text="#221d2e", muted="#6d6480",
        faint="#a39bb5", accent="#7c62d6", accent_light="#6a50c8", accent_hover="#8b72e0",
        header="#5b44b0", selected="#ddd3f5", tile_selected="#efe9fb", button="#f0ecf8",
        pressed="#e2daf5", section="#faf8fd", drop="#e8e0fa", separator="#e6e0f0", switch_off="#c9c1dc"),
    "Classic dark": dict(
        base="#1f1f1f", panel="#292929", card="#2e2e2e", dialog="#262626", field="#242424",
        border="#3d3d3d", strong_border="#474747", hover="#5a7ab5", text="#e6e6e6", muted="#a0a0a0",
        faint="#6e6e6e", accent="#3d7eff", accent_light="#6aa0ff", accent_hover="#5590ff",
        header="#8fb4ff", selected="#2f4f80", tile_selected="#2b3a52", button="#333333",
        pressed="#3d3d3d", section="#242424", drop="#2a3448", separator="#363636", switch_off="#555555"),
    "Classic light": dict(
        base="#f3f3f3", panel="#e9e9e9", card="#ffffff", dialog="#f7f7f7", field="#ffffff",
        border="#d0d0d0", strong_border="#c4c4c4", hover="#7aa5ec", text="#202020", muted="#6b6b6b",
        faint="#a8a8a8", accent="#2f6fdb", accent_light="#2f6fdb", accent_hover="#4a84e6",
        header="#2a5bb5", selected="#cfe0ff", tile_selected="#e8f0ff", button="#f5f5f5",
        pressed="#e1e1e1", section="#fafafa", drop="#dde8fb", separator="#e4e4e4", switch_off="#c8c8c8"),
}
DEFAULT = "Lavender dark"

# What a tile's dot says: the same in every preset.
IN_SERVER = "#46b86a"
BUSY = "#d9a82b"
STOPPED = "#8a8499"
WRONG = "#d9624f"

# An instance's colour comes from its bot's name, so a bot is the same colour
# in every list and every instance of it.
AVATARS = ("#8d6ad8", "#4f7fbf", "#3f9a93", "#b0668c", "#8a7f55", "#5f8f4f", "#c07a4a", "#6a6fc9")

# The colours of the preset in use, for what the code paints itself.
current = dict(PRESETS[DEFAULT], name=DEFAULT)


def __getattr__(name):
    """theme.ACCENT, theme.MUTED...: the preset in use, whichever it is now."""
    key = name.lower()
    if key in current:
        return current[key]
    raise AttributeError(name)


_ARROWS = None


def arrows(colour):
    """Small arrows for combo boxes, spin boxes and menu buttons, drawn in the
    preset's colour: a stylesheet that restyles those parts only takes its
    arrows as image files. Kept in a folder of this process's own, removed
    when it ends."""
    global _ARROWS
    if _ARROWS is None:
        _ARROWS = tempfile.mkdtemp(prefix="masurium-arrows-")
        atexit.register(shutil.rmtree, _ARROWS, True)
    paths = {}
    for name, points in (("down", ((1, 3), (9, 3), (5, 8))), ("up", ((1, 7), (9, 7), (5, 2)))):
        path = os.path.join(_ARROWS, f"{name}-{colour.lstrip('#')}.png")
        if not os.path.exists(path):
            pm = QPixmap(10, 10)
            pm.fill(Qt.transparent)
            p = QPainter(pm)
            p.setRenderHint(QPainter.Antialiasing)
            p.setPen(Qt.NoPen)
            p.setBrush(QColor(colour))
            p.drawPolygon(QPolygonF([QPointF(x, y) for x, y in points]))
            p.end()
            pm.save(path, "PNG")
        paths[name] = path.replace(os.sep, "/")
    return paths


def stylesheet(c):
    a = arrows(c["muted"])
    return f"""
QWidget {{ color: {c['text']}; }}
QMainWindow, QWidget#page {{ background: {c['base']}; }}
QDialog, QMessageBox {{ background: {c['dialog']}; }}
QToolBar {{ background: {c['panel']}; border: none; border-bottom: 1px solid {c['border']}; padding: 4px;
    spacing: 4px; }}
QToolBar QToolButton {{ background: transparent; border: 1px solid transparent; border-radius: 6px;
    padding: 6px 10px; }}
QToolBar QToolButton:hover {{ border-color: {c['hover']}; background: {c['button']}; }}
QScrollArea {{ border: none; background: {c['base']}; }}
QToolButton#groupHeader {{ color: {c['header']}; font-weight: bold; border: none; background: transparent;
    padding: 6px 4px 2px 2px; text-align: left; }}
QToolButton#groupHeader[selected="true"] {{ color: {c['accent_light']}; text-decoration: underline; }}
QFrame#tile {{ background: transparent; border: 1px solid transparent; border-radius: 8px; }}
QFrame#tile:hover {{ background: {c['card']}; }}
QFrame#tile[selected="true"] {{ background: {c['tile_selected']}; border: 1px solid {c['accent_light']}; }}
QLabel#tileName {{ padding: 1px 4px; border-radius: 3px; }}
QLabel#tileName[selected="true"] {{ background: {c['accent']}; color: white; }}
QLabel#tileWhere {{ color: {c['muted']}; font-size: 8pt; }}
QFrame#headerLine {{ color: {c['border']}; background: {c['border']}; max-height: 1px; border: none; }}
QFrame#side QPushButton {{ background: transparent; border: none; text-align: left; padding: 6px 10px;
    border-radius: 5px; }}
QFrame#side QPushButton:hover {{ background: {c['card']}; }}
QFrame#side QPushButton:disabled {{ color: {c['faint']}; }}
QFrame#side QPushButton#primary {{ font-weight: bold; color: {c['accent_light']}; background: transparent; }}
QFrame#side QPushButton#primary:hover {{ background: {c['card']}; }}
QFrame#side QToolButton#more {{ background: transparent; border: none; border-radius: 5px; padding: 0 6px; }}
QFrame#side QToolButton#more:hover {{ background: {c['card']}; }}
QFrame#side QToolButton#more::menu-indicator {{ image: none; }}
QFrame#side QFrame#sideLine {{ color: {c['border']}; background: {c['border']}; max-height: 1px; border: none; }}
QListWidget#pages {{ background: {c['panel']}; outline: 0; }}
QListWidget#pages::item {{ padding: 8px 10px; border: none; }}
QWidget#section {{ background: {c['section']}; border: 1px solid {c['strong_border']}; border-radius: 10px; }}
QWidget#dependency {{ background: transparent; border: none; }}
QWidget#section[drop="true"], QWidget#loose[drop="true"], QWidget#dependency[drop="true"] {{
    background: {c['drop']}; border: 1px dashed {c['accent_light']}; border-radius: 10px; }}
QToolButton#face {{ border: none; background: transparent; padding: 0; }}
QLabel#muted {{ color: {c['muted']}; }}
QLabel#title {{ font-weight: bold; font-size: 13pt; }}
QFrame#side {{ background: {c['panel']}; border-left: 1px solid {c['border']}; }}
QPushButton {{ background: {c['button']}; border: 1px solid {c['strong_border']}; border-radius: 6px;
    padding: 6px 12px; }}
QPushButton:hover {{ border-color: {c['hover']}; }}
QPushButton:pressed {{ background: {c['pressed']}; }}
QPushButton:disabled {{ color: {c['faint']}; border-color: {c['separator']}; }}
QPushButton#primary {{ background: {c['accent']}; border: none; color: white; font-weight: bold; padding: 8px; }}
QPushButton#primary:hover {{ background: {c['accent_hover']}; }}
QPushButton#primary:disabled {{ background: {c['switch_off']}; color: {c['muted']}; }}
QStatusBar {{ background: {c['panel']}; color: {c['muted']}; border-top: 1px solid {c['border']}; }}
QTabWidget::pane {{ border: 1px solid {c['border']}; border-radius: 6px; background: {c['field']}; }}
QTabBar::tab {{ background: {c['card']}; padding: 7px 16px; border-top-left-radius: 6px;
    border-top-right-radius: 6px; margin-right: 2px; }}
QTabBar::tab:selected {{ background: {c['selected']}; }}
QTreeWidget::item {{ padding: 5px 2px; border-bottom: 1px solid {c['separator']}; }}
QTreeWidget::item:selected {{ background: {c['selected']}; color: {c['text']}; }}
QTableWidget, QListWidget, QPlainTextEdit, QTreeWidget {{ background: {c['field']}; border: 1px solid {c['border']};
    border-radius: 6px; gridline-color: {c['border']}; selection-background-color: {c['selected']}; }}
QListWidget::item {{ padding: 6px 4px; border-bottom: 1px solid {c['separator']}; }}
QListWidget::item:selected {{ background: {c['selected']}; color: {c['text']}; }}
QListWidget#recent::item {{ padding: 1px 2px; border: none; }}
QHeaderView::section {{ background: {c['card']}; border: none; padding: 5px; color: {c['header']};
    font-weight: bold; }}
QLineEdit, QComboBox, QSpinBox {{ background: {c['card']}; border: 1px solid {c['strong_border']};
    border-radius: 5px; padding: 5px; }}
QComboBox {{ padding-right: 24px; }}
QComboBox::drop-down {{ subcontrol-origin: padding; subcontrol-position: center right; width: 22px; border: none; }}
QComboBox::down-arrow {{ image: url({a['down']}); width: 10px; height: 10px; }}
QSpinBox {{ padding-right: 22px; }}
QSpinBox::up-button, QSpinBox::down-button {{ subcontrol-origin: border; width: 20px; border: none;
    background: transparent; }}
QSpinBox::up-button {{ subcontrol-position: top right; }}
QSpinBox::down-button {{ subcontrol-position: bottom right; }}
QSpinBox::up-arrow {{ image: url({a['up']}); width: 10px; height: 10px; }}
QSpinBox::down-arrow {{ image: url({a['down']}); width: 10px; height: 10px; }}
QToolButton::menu-indicator {{ image: url({a['down']}); subcontrol-origin: padding;
    subcontrol-position: center right; width: 10px; height: 10px; right: 4px; }}
QToolBar QToolButton[popupMode="2"] {{ padding-right: 22px; }}
QToolButton::menu-button {{ border: none; border-left: 1px solid {c['border']}; width: 18px; }}
QToolButton::menu-arrow {{ image: url({a['down']}); width: 10px; height: 10px; }}
QLineEdit:focus, QComboBox:focus, QSpinBox:focus {{ border-color: {c['accent_light']}; }}
QComboBox QAbstractItemView {{ background: {c['card']}; selection-background-color: {c['selected']}; }}
QMenu {{ background: {c['card']}; border: 1px solid {c['border']}; padding: 4px; }}
QMenu::item {{ padding: 5px 18px; border-radius: 4px; }}
QMenu::item:selected {{ background: {c['selected']}; }}
QToolTip {{ background: {c['card']}; color: {c['text']}; border: 1px solid {c['border']}; padding: 4px; }}
"""


def apply(app, name=None):
    """Fusion, a palette under it (for what the stylesheet does not reach:
    scroll bars, check marks) and the stylesheet on top, all from one preset.
    An unknown name is the default one. Returns the name applied."""
    name = name if name in PRESETS else DEFAULT
    current.clear()
    current.update(PRESETS[name], name=name)
    c = current
    app.setStyle("Fusion")
    p = QPalette()
    for role, colour in ((QPalette.Window, c["base"]), (QPalette.WindowText, c["text"]),
                         (QPalette.Base, c["field"]), (QPalette.AlternateBase, c["card"]),
                         (QPalette.Text, c["text"]), (QPalette.Button, c["button"]),
                         (QPalette.ButtonText, c["text"]), (QPalette.Highlight, c["accent"]),
                         (QPalette.HighlightedText, "#ffffff"), (QPalette.ToolTipBase, c["card"]),
                         (QPalette.ToolTipText, c["text"]), (QPalette.PlaceholderText, c["faint"]),
                         (QPalette.Link, c["accent_light"])):
        p.setColor(role, QColor(colour))
    for role in (QPalette.Text, QPalette.WindowText, QPalette.ButtonText):
        p.setColor(QPalette.Disabled, role, QColor(c["faint"]))
    app.setPalette(p)
    app.setStyleSheet(stylesheet(c))
    return name


def frame(widget, colour=None, width=2):
    """An edge drawn around a window, inside it: dialogs open over the main
    window with the same colours behind them, and without one it is hard to
    tell where one ends and the other begins (a tiling compositor draws none)."""
    p = QPainter(widget)
    pen = QPen(QColor(colour or current["accent_light"]))
    pen.setWidth(width)
    p.setPen(pen)
    half = width // 2
    p.drawRect(widget.rect().adjusted(half, half, -half - 1 + width % 2, -half - 1 + width % 2))
    p.end()


def colour_of(name):
    return AVATARS[zlib.crc32((name or "?").lower().encode()) % len(AVATARS)]


def avatar(name, size=44, colour=None, image=None):
    """A bot's face: its picture when it has one (bots/<bot>/icon.png),
    cut to a rounded square; until then, a rounded square with its name's
    first letter."""
    pm = QPixmap(size, size)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    p.setRenderHint(QPainter.SmoothPixmapTransform)
    picture = QPixmap(str(image)) if image and os.path.isfile(image) else QPixmap()
    if not picture.isNull():
        path = QPainterPath()
        path.addRoundedRect(QRectF(0, 0, size, size), size * 0.22, size * 0.22)
        p.setClipPath(path)
        scaled = picture.scaled(size, size, Qt.KeepAspectRatioByExpanding, Qt.SmoothTransformation)
        p.drawPixmap((size - scaled.width()) // 2, (size - scaled.height()) // 2, scaled)
        p.end()
        return pm
    p.setBrush(QColor(colour or colour_of(name)))
    p.setPen(Qt.NoPen)
    p.drawRoundedRect(0, 0, size, size, size * 0.22, size * 0.22)
    p.setPen(QColor("white"))
    font = QFont()
    font.setBold(True)
    font.setPixelSize(int(size * 0.45))
    p.setFont(font)
    p.drawText(pm.rect(), Qt.AlignCenter, (name or "?")[:1].upper())
    p.end()
    return pm

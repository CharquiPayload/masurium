"""How the window looks: Qt's Fusion style, dark, with a lavender accent.

Fusion draws the same on every platform, which is the point: the window a
person sees on Windows is the one that was looked at on Linux. Everything
else is one stylesheet here and a few colours the code paints with.
"""
import zlib

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QFont, QPainter, QPalette, QPixmap

ACCENT = "#7c62d6"
ACCENT_LIGHT = "#a58cf0"
TEXT = "#e6e1f0"
MUTED = "#a79fbd"
BASE = "#1e1b26"
PANEL = "#262233"
CARD = "#2a2638"
BORDER = "#3a3450"

# What a tile's dot says.
IN_SERVER = "#46b86a"
BUSY = "#d9a82b"
STOPPED = "#6b6480"
WRONG = "#d9624f"

# An instance's colour comes from its bot's name, so a bot is the same colour
# in every list and every instance of it.
AVATARS = ("#8d6ad8", "#4f7fbf", "#3f9a93", "#b0668c", "#8a7f55", "#5f8f4f", "#c07a4a", "#6a6fc9")

STYLESHEET = f"""
QWidget {{ color: {TEXT}; }}
QMainWindow, QDialog, QWidget#page {{ background: {BASE}; }}
QToolBar {{ background: {PANEL}; border: none; border-bottom: 1px solid {BORDER}; padding: 4px; spacing: 4px; }}
QToolBar QToolButton {{ background: transparent; border: 1px solid transparent; border-radius: 6px;
    padding: 6px 10px; }}
QToolBar QToolButton:hover {{ border-color: #5a4d80; background: #2f2a40; }}
QScrollArea {{ border: none; background: {BASE}; }}
QToolButton#groupHeader {{ color: #b9a6e8; font-weight: bold; border: none; background: transparent;
    padding: 6px 4px 2px 2px; text-align: left; }}
QToolButton#groupHeader[selected="true"] {{ color: white; }}
QFrame#tile {{ background: {CARD}; border: 1px solid {BORDER}; border-radius: 10px; }}
QFrame#tile:hover {{ border-color: #5a4d80; }}
QFrame#tile[selected="true"] {{ background: #352e4d; border: 2px solid {ACCENT_LIGHT}; }}
QLabel#tileName {{ font-weight: bold; }}
QLabel#muted {{ color: {MUTED}; }}
QLabel#title {{ font-weight: bold; font-size: 13pt; }}
QFrame#side {{ background: {PANEL}; border-left: 1px solid {BORDER}; }}
QPushButton {{ background: #2f2a40; border: 1px solid #3f3858; border-radius: 6px; padding: 6px 12px; }}
QPushButton:hover {{ border-color: #6a5b96; }}
QPushButton:pressed {{ background: #3b3356; }}
QPushButton:disabled {{ color: #6b6480; border-color: #332d45; }}
QFrame#side QPushButton {{ text-align: left; padding: 7px 10px; }}
QPushButton#primary {{ background: {ACCENT}; border: none; color: white; font-weight: bold; padding: 8px; }}
QPushButton#primary:hover {{ background: #8b72e0; }}
QPushButton#primary:disabled {{ background: #4a4163; color: #a79fbd; }}
QPushButton#danger {{ background: #6e3340; border: none; color: white; }}
QStatusBar {{ background: {PANEL}; color: {MUTED}; border-top: 1px solid {BORDER}; }}
QTabWidget::pane {{ border: 1px solid {BORDER}; border-radius: 6px; background: #231f2e; }}
QTabBar::tab {{ background: {CARD}; padding: 7px 16px; border-top-left-radius: 6px;
    border-top-right-radius: 6px; margin-right: 2px; }}
QTabBar::tab:selected {{ background: #3b3356; color: white; }}
QTableWidget, QListWidget, QPlainTextEdit, QTreeWidget {{ background: #231f2e; border: 1px solid {BORDER};
    border-radius: 6px; gridline-color: {BORDER}; selection-background-color: #4a3f73; }}
QHeaderView::section {{ background: {CARD}; border: none; padding: 5px; color: #b9a6e8; font-weight: bold; }}
QLineEdit, QComboBox, QSpinBox {{ background: {CARD}; border: 1px solid #3f3858; border-radius: 5px;
    padding: 5px; }}
QLineEdit:focus, QComboBox:focus {{ border-color: {ACCENT_LIGHT}; }}
QComboBox QAbstractItemView {{ background: {CARD}; selection-background-color: #4a3f73; }}
QMenu {{ background: {CARD}; border: 1px solid {BORDER}; padding: 4px; }}
QMenu::item {{ padding: 5px 18px; border-radius: 4px; }}
QMenu::item:selected {{ background: #4a3f73; }}
QToolTip {{ background: {CARD}; color: {TEXT}; border: 1px solid {BORDER}; padding: 4px; }}
QCheckBox::indicator {{ width: 15px; height: 15px; }}
"""


def apply(app):
    """Fusion, the dark palette under it (for what the stylesheet does not
    reach: scroll bars, check marks) and the stylesheet on top."""
    app.setStyle("Fusion")
    p = QPalette()
    for role, colour in ((QPalette.Window, BASE), (QPalette.WindowText, TEXT), (QPalette.Base, "#231f2e"),
                         (QPalette.AlternateBase, CARD), (QPalette.Text, TEXT), (QPalette.Button, "#2f2a40"),
                         (QPalette.ButtonText, TEXT), (QPalette.Highlight, ACCENT),
                         (QPalette.HighlightedText, "#ffffff"), (QPalette.ToolTipBase, CARD),
                         (QPalette.ToolTipText, TEXT), (QPalette.PlaceholderText, "#7d7494"),
                         (QPalette.Link, ACCENT_LIGHT)):
        p.setColor(role, QColor(colour))
    p.setColor(QPalette.Disabled, QPalette.Text, QColor("#6b6480"))
    p.setColor(QPalette.Disabled, QPalette.WindowText, QColor("#6b6480"))
    p.setColor(QPalette.Disabled, QPalette.ButtonText, QColor("#6b6480"))
    app.setPalette(p)
    app.setStyleSheet(STYLESHEET)


def colour_of(name):
    return AVATARS[zlib.crc32((name or "?").lower().encode()) % len(AVATARS)]


def avatar(name, size=44, colour=None):
    """A rounded square with the name's first letter: what stands for a bot
    until it has a face of its own."""
    pm = QPixmap(size, size)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
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

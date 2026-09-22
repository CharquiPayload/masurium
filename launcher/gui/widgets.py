"""The pieces of the main window: a tile per instance, a collapsible section
per group, and the layout that lays tiles out in rows that wrap."""
from PySide6.QtCore import QPoint, QRect, QSize, Qt, Signal
from PySide6.QtWidgets import (QFrame, QHBoxLayout, QLabel, QLayout, QSizePolicy, QToolButton, QVBoxLayout,
                               QWidget)

from . import theme

STATE_TEXT = {"in": "in the server", "loading": "loading…", "stopped": "stopped",
              "mute": "in the server, no bridge"}
STATE_COLOUR = {"in": theme.IN_SERVER, "loading": theme.BUSY, "stopped": theme.STOPPED, "mute": theme.WRONG}


class FlowLayout(QLayout):
    """Tiles side by side, wrapping to the next row when the window is
    narrower: Qt's own flow layout example, as Qt has none built in."""

    def __init__(self, parent=None, spacing=10):
        super().__init__(parent)
        self._items = []
        self.setSpacing(spacing)
        self.setContentsMargins(0, 0, 0, 0)

    def addItem(self, item):
        self._items.append(item)

    def count(self):
        return len(self._items)

    def itemAt(self, i):
        return self._items[i] if 0 <= i < len(self._items) else None

    def takeAt(self, i):
        return self._items.pop(i) if 0 <= i < len(self._items) else None

    def expandingDirections(self):
        return Qt.Orientation(0)

    def hasHeightForWidth(self):
        return True

    def heightForWidth(self, width):
        return self._lay(QRect(0, 0, width, 0), True)

    def setGeometry(self, rect):
        super().setGeometry(rect)
        self._lay(rect, False)

    def sizeHint(self):
        return self.minimumSize()

    def minimumSize(self):
        size = QSize()
        for item in self._items:
            size = size.expandedTo(item.minimumSize())
        m = self.contentsMargins()
        return size + QSize(m.left() + m.right(), m.top() + m.bottom())

    def _lay(self, rect, only_measure):
        m = self.contentsMargins()
        area = rect.adjusted(m.left(), m.top(), -m.right(), -m.bottom())
        x, y, row = area.x(), area.y(), 0
        gap = self.spacing()
        for item in self._items:
            hint = item.sizeHint()
            if x + hint.width() > area.right() + 1 and row > 0:
                x, y, row = area.x(), y + row + gap, 0
            if not only_measure:
                item.setGeometry(QRect(QPoint(x, y), hint))
            x += hint.width() + gap
            row = max(row, hint.height())
        return y + row - rect.y() + m.bottom()


class InstanceTile(QFrame):
    """One instance: its bot's letter, its name, where it plays and how it is
    doing. Clicked, it is selected; right-clicked, its actions."""

    clicked = Signal(str)
    menu = Signal(str, QPoint)
    opened = Signal(str)

    def __init__(self, view, parent=None):
        super().__init__(parent)
        self.key = view.key
        self.setObjectName("tile")
        self.setFixedSize(QSize(224, 82))
        self.setCursor(Qt.PointingHandCursor)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(10, 8, 10, 8)
        self.pic = QLabel()
        self.pic.setPixmap(theme.avatar(view.bot or view.name))
        lay.addWidget(self.pic)
        col = QVBoxLayout()
        col.setSpacing(1)
        top = QHBoxLayout()
        self.title = QLabel(view.key)
        self.title.setObjectName("tileName")
        top.addWidget(self.title)
        top.addStretch()
        self.dot = QLabel("●")
        top.addWidget(self.dot)
        col.addLayout(top)
        self.where = QLabel()
        self.where.setObjectName("muted")
        col.addWidget(self.where)
        self.note = QLabel()
        self.note.setObjectName("muted")
        col.addWidget(self.note)
        lay.addLayout(col, 1)
        self.update_view(view, None)

    def update_view(self, view, busy):
        self.where.setText(f"{view.name} · {view.server}")
        if busy:
            note, colour = busy + "…", theme.BUSY
        else:
            state = view.state
            note, colour = STATE_TEXT[state], STATE_COLOUR[state]
            if view.leader:
                note = f"guard of {view.leader} · {note}" if state != "stopped" else f"guard of {view.leader}"
        self.note.setText(self.note.fontMetrics().elidedText(note, Qt.ElideRight, 150))
        self.note.setToolTip(note)
        self.dot.setStyleSheet(f"color: {colour};")
        self.setToolTip(f"{view.key}: {view.name} on {view.server}, port {view.port}\nmodel {view.model}, "
                        f"role {view.role}")

    def set_selected(self, on):
        self.setProperty("selected", on)
        self.style().unpolish(self)
        self.style().polish(self)

    def mousePressEvent(self, e):
        if e.button() == Qt.LeftButton:
            self.clicked.emit(self.key)
        elif e.button() == Qt.RightButton:
            self.clicked.emit(self.key)
            self.menu.emit(self.key, e.globalPosition().toPoint())

    def mouseDoubleClickEvent(self, e):
        self.opened.emit(self.key)


class GroupSection(QWidget):
    """A group: a header that folds it, and inside, its instances' tiles and
    the groups inside it, indented."""

    toggled = Signal(str, bool)
    clicked = Signal(str)
    menu = Signal(str, QPoint)

    def __init__(self, key, title, folded, depth, parent=None):
        super().__init__(parent)
        self.key = key
        v = QVBoxLayout(self)
        v.setContentsMargins(depth * 18, 0, 0, 0)
        v.setSpacing(4)
        row = QHBoxLayout()
        row.setSpacing(0)
        # The arrow folds it; the name selects it, for its actions on the side.
        self.arrow = QToolButton()
        self.arrow.setObjectName("groupHeader")
        self.arrow.setArrowType(Qt.RightArrow if folded else Qt.DownArrow)
        self.arrow.setCursor(Qt.PointingHandCursor)
        self.arrow.clicked.connect(self._fold)
        row.addWidget(self.arrow)
        self.header = QToolButton()
        self.header.setObjectName("groupHeader")
        self.header.setText(title)
        self.header.setCursor(Qt.PointingHandCursor)
        self.header.setSizePolicy(QSizePolicy.Maximum, QSizePolicy.Fixed)
        self.header.clicked.connect(lambda: self.clicked.emit(self.key))
        self.header.setContextMenuPolicy(Qt.CustomContextMenu)
        self.header.customContextMenuRequested.connect(
            lambda pos: self.menu.emit(self.key, self.header.mapToGlobal(pos)))
        row.addWidget(self.header)
        row.addStretch()
        v.addLayout(row)
        self.body = QWidget()
        self.inner = QVBoxLayout(self.body)
        self.inner.setContentsMargins(4, 0, 0, 6)
        self.inner.setSpacing(6)
        self.tiles = QWidget()
        self.flow = FlowLayout(self.tiles)
        self.inner.addWidget(self.tiles)
        v.addWidget(self.body)
        self.body.setVisible(not folded)

    def _fold(self):
        folded = self.body.isVisible()
        self.body.setVisible(not folded)
        self.arrow.setArrowType(Qt.RightArrow if folded else Qt.DownArrow)
        self.toggled.emit(self.key, folded)

    def set_selected(self, on):
        self.header.setProperty("selected", on)
        self.header.style().unpolish(self.header)
        self.header.style().polish(self.header)

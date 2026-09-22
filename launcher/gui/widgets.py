"""The pieces of the main window: a tile per instance, a collapsible section
per group, and the layout that lays tiles out in rows that wrap."""
from PySide6.QtCore import QMimeData, QPoint, QPointF, QRect, QSize, Qt, Signal
from PySide6.QtGui import QColor, QDrag, QPainter, QPen, QPolygonF
from PySide6.QtWidgets import (QAbstractButton, QApplication, QFrame, QHBoxLayout, QLabel, QLayout, QSizePolicy,
                               QToolButton, QVBoxLayout, QWidget)

from . import theme

STATE_TEXT = {"in": "in the server", "loading": "loading…", "stopped": "stopped",
              "mute": "in the server, no bridge"}
STATE_COLOUR = {"in": theme.IN_SERVER, "loading": theme.BUSY, "stopped": theme.STOPPED, "mute": theme.WRONG}
# What a tile or a group header carries when it is dragged: "instance:<key>" or "group:<key>".
MIME = "application/x-marionette-node"


def icon_of(view):
    """The picture file of a view's icon field ("<path>:<mtime>"), or None."""
    return view.icon.rsplit(":", 1)[0] if view.icon else None


class Dragger:
    """Pressed and moved a little, a widget starts dragging what it stands for;
    pressed and released, it is a click as always."""

    def _press(self, e):
        self._from = e.position().toPoint() if e.button() == Qt.LeftButton else None

    def _move(self, e, ref, picture):
        if self._from is None or not (e.buttons() & Qt.LeftButton):
            return False
        if (e.position().toPoint() - self._from).manhattanLength() < QApplication.startDragDistance():
            return False
        self._from = None
        mime = QMimeData()
        mime.setData(MIME, ref.encode())
        drag = QDrag(self)
        drag.setMimeData(mime)
        drag.setPixmap(picture.scaledToWidth(min(picture.width(), 200), Qt.SmoothTransformation))
        drag.exec(Qt.MoveAction)
        return True


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


class Switch(QAbstractButton):
    """On or off, for what is only that: a pill with a knob, lavender when on."""

    def __init__(self, checked=False, parent=None):
        super().__init__(parent)
        self.setCheckable(True)
        self.setChecked(checked)
        self.setCursor(Qt.PointingHandCursor)
        self.setFixedSize(QSize(40, 22))

    def sizeHint(self):
        return QSize(40, 22)

    def paintEvent(self, event):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        on, enabled = self.isChecked(), self.isEnabled()
        track = QColor(theme.ACCENT if on else theme.SWITCH_OFF)
        if not enabled:
            track.setAlpha(90)
        p.setPen(Qt.NoPen)
        p.setBrush(track)
        p.drawRoundedRect(self.rect().adjusted(1, 1, -1, -1), 10, 10)
        d = self.height() - 8
        x = self.width() - d - 4 if on else 4
        p.setBrush(QColor("#ffffff" if enabled else "#8a8499"))
        p.drawEllipse(x, 4, d, d)
        p.end()


def switch_row(text, checked=False):
    """A switch with its label on the right: (the row, the switch)."""
    row = QWidget()
    lay = QHBoxLayout(row)
    lay.setContentsMargins(6, 0, 6, 0)
    sw = Switch(checked)
    lay.addWidget(sw)
    label = QLabel(text)
    label.setBuddy(sw)
    lay.addWidget(label, 1)
    return row, sw


class InstanceTile(Dragger, QFrame):
    """One instance: its bot's face, its name, where it plays and how it is
    doing. Clicked, it is selected; right-clicked, its actions; dragged onto
    a group, it moves there."""

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
        self.face = None
        lay.addWidget(self.pic)
        self._from = None
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
        if self.face != view.icon:
            self.face = view.icon
            self.pic.setPixmap(theme.avatar(view.bot or view.name, image=icon_of(view)))
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
        self._press(e)
        if e.button() == Qt.LeftButton:
            self.clicked.emit(self.key)
        elif e.button() == Qt.RightButton:
            self.clicked.emit(self.key)
            self.menu.emit(self.key, e.globalPosition().toPoint())

    def mouseMoveEvent(self, e):
        self._move(e, f"instance:{self.key}", self.grab())

    def mouseDoubleClickEvent(self, e):
        self.opened.emit(self.key)


class DragHeader(Dragger, QToolButton):
    """A group's name: clicked, the group is selected; dragged onto another
    group, it moves there."""

    def __init__(self, ref, parent=None):
        super().__init__(parent)
        self.ref = ref
        self._from = None

    def mousePressEvent(self, e):
        self._press(e)
        super().mousePressEvent(e)

    def mouseMoveEvent(self, e):
        if self.ref and self._move(e, self.ref, self.grab()):
            self.setDown(False)
            return
        super().mouseMoveEvent(e)


class GroupSection(QWidget):
    """A group: a header that folds it, and inside, its instances' tiles and
    the groups inside it, indented. What is dropped on it moves into it (on
    the section of the instances in no group: out of their group)."""

    toggled = Signal(str, bool)
    clicked = Signal(str)
    menu = Signal(str, QPoint)
    dropped = Signal(str, str)           # what ("instance:alice"), into which group ("" for none)

    def __init__(self, key, title, folded, parent=None):
        super().__init__(parent)
        self.key = key
        # A group has an outline, so where one ends and the next (or the one
        # inside it) begins is seen; the instances in no group have none.
        self.setObjectName("section" if key else "loose")
        self.setAttribute(Qt.WA_StyledBackground, True)
        self.setAcceptDrops(True)
        v = QVBoxLayout(self)
        if key:
            v.setContentsMargins(8, 4, 8, 8)
        else:
            v.setContentsMargins(0, 0, 0, 0)
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
        self.header = DragHeader(f"group:{key}" if key else "")
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

    def _lit(self, on):
        self.setProperty("drop", on)
        self.style().unpolish(self)
        self.style().polish(self)

    def dragEnterEvent(self, e):
        if e.mimeData().hasFormat(MIME) and bytes(e.mimeData().data(MIME)).decode() != f"group:{self.key}":
            e.acceptProposedAction()
            self._lit(True)

    def dragLeaveEvent(self, e):
        self._lit(False)

    def dropEvent(self, e):
        self._lit(False)
        what = bytes(e.mimeData().data(MIME)).decode()
        e.acceptProposedAction()
        self.dropped.emit(what, self.key)


class DependencySection(GroupSection):
    """A leader and its guards, drawn as a small map: the leader on top,
    always there, and its guards below, each joined to it by a line with an
    arrow at the leader's end (they depend on it). Folding hides the guards,
    never the leader. Nothing boxes them in: the leader is not "inside" its
    guards' group, it is what they hang from."""

    def __init__(self, key, title, folded, parent=None):
        super().__init__(key, title, folded, parent)
        self.setObjectName("dependency")
        self.leader = None
        lay = self.layout()
        lay.setContentsMargins(4, 4, 4, 8)
        # The header row becomes: [leader tile] [arrow, name] ; the guards go under it.
        head = lay.itemAt(0).layout()
        lay.removeItem(head)
        self.top = QHBoxLayout()
        self.top.setSpacing(10)
        self.slot = QHBoxLayout()
        self.top.addLayout(self.slot)
        self.top.addLayout(head)
        lay.insertLayout(0, self.top)
        lay.setSpacing(34)
        self.inner.setContentsMargins(42, 0, 0, 0)

    def set_leader(self, tile):
        self.leader = tile
        self.slot.addWidget(tile)

    def _fold(self):
        super()._fold()
        self.update()

    def paintEvent(self, event):
        super().paintEvent(event)
        if self.leader is None or not self.body.isVisible():
            return
        guards = [self.flow.itemAt(i).widget() for i in range(self.flow.count())]
        guards = [g for g in guards if g is not None and g.isVisible()]
        if not guards:
            return
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        pen = QPen(QColor(theme.ACCENT_LIGHT))
        pen.setWidth(2)
        p.setPen(pen)
        top_left = self.leader.mapTo(self, QPoint(0, 0))
        lx = top_left.x() + 36                      # under the leader's face
        ly = top_left.y() + self.leader.height()
        tops = [(g.mapTo(self, QPoint(0, 0)).x() + 36, g.mapTo(self, QPoint(0, 0)).y()) for g in guards]
        bus = (ly + min(y for _, y in tops)) // 2
        p.drawLine(lx, ly + 1, lx, bus)
        for gx, gy in tops:
            p.drawLine(lx, bus, gx, bus)
            p.drawLine(gx, bus, gx, gy - 1)
        # The arrow points at the leader: the guards depend on it.
        p.setBrush(QColor(theme.ACCENT_LIGHT))
        p.drawPolygon(QPolygonF([QPointF(lx, ly + 1), QPointF(lx - 5, ly + 9), QPointF(lx + 5, ly + 9)]))
        p.end()

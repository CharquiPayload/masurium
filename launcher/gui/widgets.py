"""The pieces of the main window: a tile per instance, a collapsible section
per group, and the layout that lays tiles out in rows that wrap."""
from PySide6.QtCore import Property, QMimeData, QPoint, QPointF, QRect, QSize, Qt, QVariantAnimation, Signal
from PySide6.QtGui import QColor, QDrag, QPainter, QPen, QPixmap, QPolygonF
from PySide6.QtWidgets import (QAbstractButton, QApplication, QFrame, QHBoxLayout, QLabel, QLayout, QSizePolicy,
                               QToolButton, QVBoxLayout, QWidget)

from . import anim, icons, theme

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
    """On or off, for what is only that: a pill with a knob in the accent
    colour when on. The knob slides, unless motion is off."""

    def __init__(self, checked=False, parent=None):
        super().__init__(parent)
        self.setCheckable(True)
        self.setChecked(checked)
        self._knob = 1.0 if checked else 0.0
        self.setCursor(Qt.PointingHandCursor)
        self.setFixedSize(QSize(40, 22))
        self.toggled.connect(lambda on: anim.slide(self, b"knob", 1.0 if on else 0.0, 140))

    def _get_knob(self):
        return self._knob

    def _set_knob(self, value):
        self._knob = float(value)
        self.update()

    knob = Property(float, _get_knob, _set_knob)

    def sizeHint(self):
        return QSize(40, 22)

    def paintEvent(self, event):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        enabled = self.isEnabled()
        off, on = QColor(theme.SWITCH_OFF), QColor(theme.ACCENT)
        k = max(0.0, min(1.0, self._knob))
        track = QColor(int(off.red() + (on.red() - off.red()) * k), int(off.green() + (on.green() - off.green()) * k),
                       int(off.blue() + (on.blue() - off.blue()) * k))
        if not enabled:
            track.setAlpha(90)
        p.setPen(Qt.NoPen)
        p.setBrush(track)
        p.drawRoundedRect(self.rect().adjusted(1, 1, -1, -1), 10, 10)
        d = self.height() - 8
        x = 4 + (self.width() - d - 8) * k
        p.setBrush(QColor("#ffffff" if enabled else "#8a8499"))
        p.drawEllipse(QPointF(x + d / 2, 4 + d / 2), d / 2, d / 2)
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


def face_with_state(face, colour, pulse=None):
    """A bot's face with a dot in its corner saying how it is doing. `pulse`
    (0 to 1), while it is busy: a ring that grows out of the dot and fades."""
    size = face.width()
    pm = QPixmap(face)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    d = max(10, size // 4)
    centre = QPointF(size - 1 - d / 2, size - 1 - d / 2)
    if pulse is not None:
        ring = QColor(colour)
        ring.setAlphaF(0.85 * (1 - pulse))
        pen = QPen(ring)
        pen.setWidthF(2)
        p.setPen(pen)
        p.setBrush(Qt.NoBrush)
        r = d / 2 + 1 + 5 * pulse
        p.drawEllipse(centre, r, r)
    p.setPen(QPen(QColor(theme.BASE), 2))
    p.setBrush(QColor(colour))
    p.drawEllipse(centre, d / 2, d / 2)
    p.end()
    return pm


class InstanceTile(Dragger, QFrame):
    """One instance, Prism's way: its face, a dot on it saying how it is
    doing, and its name under it (and, small, where it plays). Clicked, it is
    selected; right-clicked, its actions; double-clicked, edited; dragged onto
    a group, it moves there."""

    clicked = Signal(str)
    menu = Signal(str, QPoint)
    opened = Signal(str)

    def __init__(self, view, parent=None):
        super().__init__(parent)
        self.key = view.key
        self.setObjectName("tile")
        self.setFixedSize(QSize(128, 116))
        self.setCursor(Qt.PointingHandCursor)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(4, 6, 4, 4)
        lay.setSpacing(3)
        self.pic = QLabel()
        self.pic.setAlignment(Qt.AlignCenter)
        self.face = None
        lay.addWidget(self.pic)
        self._from = None
        self.title = QLabel(view.key)
        self.title.setObjectName("tileName")
        self.title.setAlignment(Qt.AlignCenter)
        lay.addWidget(self.title)
        self.where = QLabel()
        self.where.setObjectName("tileWhere")
        self.where.setAlignment(Qt.AlignCenter)
        lay.addWidget(self.where)
        lay.addStretch()
        self.note = QLabel()          # what it is doing, for the tooltip and the tests; not shown
        self.base = None              # its face without the dot
        self.colour = None
        # While it loads or works, its dot pulses (animated icons, in the settings).
        self.pulse = QVariantAnimation(self)
        self.pulse.setStartValue(0.0)
        self.pulse.setEndValue(1.0)
        self.pulse.setDuration(1300)
        self.pulse.setLoopCount(-1)
        self.pulse.valueChanged.connect(lambda v: self.pic.setPixmap(face_with_state(self.base, self.colour, v)))
        self.update_view(view, None)

    def update_view(self, view, busy):
        if busy:
            note, colour = busy + "…", theme.BUSY
        else:
            state = view.state
            note, colour = STATE_TEXT[state], STATE_COLOUR[state]
            if view.leader:
                note = f"guard of {view.leader} · {note}" if state != "stopped" else f"guard of {view.leader}"
        signature = (view.icon, colour, theme.current["name"])
        if self.face != signature:
            self.face = signature
            self.base = theme.avatar(view.bot or view.name, 52, image=icon_of(view))
            self.colour = colour
            self.pic.setPixmap(face_with_state(self.base, colour))
        self.set_pulsing(bool(busy) or view.state == "loading")
        metrics = self.title.fontMetrics()
        self.title.setText(metrics.elidedText(view.key, Qt.ElideRight, 118))
        self.where.setText(metrics.elidedText(view.server, Qt.ElideRight, 118))
        self.note.setText(note)
        self.note.setToolTip(note)
        self.setToolTip(f"{view.key}: {view.name} on {view.server}, port {view.port}\n{note}\n"
                        f"model {view.model}, role {view.role}")

    def set_pulsing(self, on):
        on = on and icons.animated
        if on and self.pulse.state() != QVariantAnimation.Running:
            self.pulse.start()
        elif not on and self.pulse.state() == QVariantAnimation.Running:
            self.pulse.stop()
            self.pic.setPixmap(face_with_state(self.base, self.colour))

    def set_selected(self, on):
        for w in (self, self.title):
            w.setProperty("selected", on)
            w.style().unpolish(w)
            w.style().polish(w)

    def mousePressEvent(self, e):
        self._press(e)
        if e.button() in (Qt.LeftButton, Qt.RightButton):
            self.clicked.emit(self.key)

    def contextMenuEvent(self, e):
        # Taken here, so the group around it does not offer its own menu too.
        e.accept()
        self.menu.emit(self.key, e.globalPos())

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
    menu = Signal(str, QPoint)           # on its name: the group's actions
    space_menu = Signal(str, QPoint)     # anywhere else in it: what can be added there
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
        # Prism's header: the name, and a thin line running to the right.
        line = QFrame()
        line.setObjectName("headerLine")
        line.setFrameShape(QFrame.HLine)
        line.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        row.addSpacing(8)
        row.addWidget(line, 1)
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
        if not folded:
            anim.fade_in(self.body)
        self.toggled.emit(self.key, folded)

    def contextMenuEvent(self, e):
        e.accept()
        self.space_menu.emit(self.key, e.globalPos())

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
        lx = top_left.x() + self.leader.width() // 2   # under the leader's face
        ly = top_left.y() + self.leader.height()
        tops = [(g.mapTo(self, QPoint(0, 0)).x() + g.width() // 2, g.mapTo(self, QPoint(0, 0)).y()) for g in guards]
        bus = (ly + min(y for _, y in tops)) // 2
        p.drawLine(lx, ly + 1, lx, bus)
        for gx, gy in tops:
            p.drawLine(lx, bus, gx, bus)
            p.drawLine(gx, bus, gx, gy - 1)
        # The arrow points at the leader: the guards depend on it.
        p.setBrush(QColor(theme.ACCENT_LIGHT))
        p.drawPolygon(QPolygonF([QPointF(lx, ly + 1), QPointF(lx - 5, ly + 9), QPointF(lx + 5, ly + 9)]))
        p.end()

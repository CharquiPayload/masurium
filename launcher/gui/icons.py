"""The launcher's icons, drawn here as lines on a 24-unit grid: no image
files, nothing borrowed from another launcher's icon set, and in the colour
of the preset in use, so a light or dark style never leaves one unreadable.

Being drawn, they can move: with `animated` on (the launcher's settings),
an icon makes its gesture when the pointer comes over its button. The gear
turns, Restart goes round, Launch steps forward, the bin lifts its lid, the
folder opens; the rest give a small hop. `t` runs from 0 to 1 through the
gesture, and 0 is the icon at rest."""
import math

from PySide6.QtCore import QEasingCurve, QEvent, QObject, QPointF, QRectF, Qt, QVariantAnimation
from PySide6.QtGui import QColor, QFont, QIcon, QPainter, QPainterPath, QPen, QPixmap, QPolygonF

from . import theme

animated = True


def _play(p):
    p.setBrush(p.pen().color())
    p.drawPolygon(QPolygonF([QPointF(7, 4.5), QPointF(19, 12), QPointF(7, 19.5)]))


def _stop(p):
    p.setBrush(p.pen().color())
    p.drawRoundedRect(QRectF(6, 6, 12, 12), 2, 2)


def _cancel(p):
    p.drawLine(QPointF(6, 6), QPointF(18, 18))
    p.drawLine(QPointF(18, 6), QPointF(6, 18))


def _edit(p):
    body = QPainterPath()
    body.moveTo(15.5, 4.5)
    body.lineTo(19.5, 8.5)
    body.lineTo(9, 19)
    body.lineTo(4.5, 19.5)
    body.lineTo(5, 15)
    body.closeSubpath()
    p.drawPath(body)
    p.drawLine(QPointF(13, 7), QPointF(17, 11))


def _move(p):
    p.drawRoundedRect(QRectF(3.5, 5, 10, 14), 2, 2)
    p.drawLine(QPointF(10, 12), QPointF(20.5, 12))
    p.drawLine(QPointF(17, 8.5), QPointF(20.5, 12))
    p.drawLine(QPointF(17, 15.5), QPointF(20.5, 12))


def _folder(p, t=0.0):
    path = QPainterPath()
    path.moveTo(3.5, 7)
    path.lineTo(3.5, 18.5)
    path.lineTo(20.5, 18.5)
    path.lineTo(20.5, 8.5)
    path.lineTo(12, 8.5)
    path.lineTo(10, 5.5)
    path.lineTo(3.5, 5.5)
    path.closeSubpath()
    p.drawPath(path)
    if t:
        # Its front swings open: a flap leaning out from the bottom edge.
        k = math.sin(math.pi * t)
        flap = QPolygonF([QPointF(3.5, 18.5), QPointF(20.5, 18.5), QPointF(22 + 1.5 * k, 11 + 1.5 * k),
                          QPointF(5 + 3 * k, 11 + 1.5 * k)])
        p.save()
        p.setBrush(QColor(theme.PANEL))
        p.drawPolygon(flap)
        p.restore()


def _copy(p):
    p.drawRoundedRect(QRectF(8.5, 8.5, 11, 11), 2, 2)
    path = QPainterPath()
    path.moveTo(15.5, 8.5)
    path.lineTo(15.5, 4.5)
    path.lineTo(4.5, 4.5)
    path.lineTo(4.5, 15.5)
    path.lineTo(8.5, 15.5)
    p.drawPath(path)


def _delete(p, t=0.0):
    # The lid (and its handle) lifts and tips back as the gesture goes.
    k = math.sin(math.pi * t)
    p.save()
    p.translate(QPointF(4, 6.5))
    p.rotate(-14 * k)
    p.translate(QPointF(-4, -6.5 - 2.5 * k))
    p.drawLine(QPointF(4, 6.5), QPointF(20, 6.5))
    p.drawLine(QPointF(9.5, 6.5), QPointF(10, 4))
    p.drawLine(QPointF(10, 4), QPointF(14, 4))
    p.drawLine(QPointF(14, 4), QPointF(14.5, 6.5))
    p.restore()
    path = QPainterPath()
    path.moveTo(6, 6.5)
    path.lineTo(7, 20)
    path.lineTo(17, 20)
    path.lineTo(18, 6.5)
    p.drawPath(path)
    p.drawLine(QPointF(10, 10), QPointF(10, 16.5))
    p.drawLine(QPointF(14, 10), QPointF(14, 16.5))


def _plus(p):
    p.drawLine(QPointF(12, 4.5), QPointF(12, 19.5))
    p.drawLine(QPointF(4.5, 12), QPointF(19.5, 12))


def _gear(p):
    for i in range(8):
        a = i * math.pi / 4
        p.drawLine(QPointF(12 + 6.5 * math.cos(a), 12 + 6.5 * math.sin(a)),
                   QPointF(12 + 9 * math.cos(a), 12 + 9 * math.sin(a)))
    p.drawEllipse(QPointF(12, 12), 6, 6)
    p.drawEllipse(QPointF(12, 12), 2.2, 2.2)


def _help(p):
    p.drawEllipse(QPointF(12, 12), 8.5, 8.5)
    path = QPainterPath()
    path.moveTo(9.3, 9.8)
    path.cubicTo(9.3, 6.4, 14.8, 6.4, 14.8, 9.6)
    path.cubicTo(14.8, 11.6, 12, 11.8, 12, 14)
    p.drawPath(path)
    p.setBrush(p.pen().color())
    p.drawEllipse(QPointF(12, 17), 0.6, 0.6)


def _account(p):
    p.drawEllipse(QPointF(12, 8.5), 3.8, 3.8)
    path = QPainterPath()
    path.moveTo(4.5, 20)
    path.cubicTo(5, 14.5, 19, 14.5, 19.5, 20)
    p.drawPath(path)


def _bot(p):
    p.drawRoundedRect(QRectF(4.5, 7, 15, 12), 3, 3)
    p.drawLine(QPointF(12, 7), QPointF(12, 4))
    p.setBrush(p.pen().color())
    p.drawEllipse(QPointF(12, 3.5), 0.8, 0.8)
    p.drawEllipse(QPointF(9, 12.5), 1.1, 1.1)
    p.drawEllipse(QPointF(15, 12.5), 1.1, 1.1)
    p.drawLine(QPointF(9.5, 16), QPointF(14.5, 16))


def _restart(p):
    p.drawArc(QRectF(5, 5, 14, 14), 60 * 16, 290 * 16)
    p.setBrush(p.pen().color())
    tip = QPointF(12 + 7 * math.cos(math.radians(60)), 12 - 7 * math.sin(math.radians(60)))
    p.drawPolygon(QPolygonF([tip + QPointF(-3.5, -3.2), tip + QPointF(1.8, -0.8), tip + QPointF(-2.4, 3)]))


def _connect(p):
    p.drawLine(QPointF(4, 9), QPointF(19, 9))
    p.drawLine(QPointF(15.5, 5.5), QPointF(19, 9))
    p.drawLine(QPointF(20, 15), QPointF(5, 15))
    p.drawLine(QPointF(8.5, 18.5), QPointF(5, 15))


def _bridge(p):
    path = QPainterPath()
    path.moveTo(3.5, 16)
    path.cubicTo(7, 8, 17, 8, 20.5, 16)
    p.drawPath(path)
    p.drawLine(QPointF(3.5, 16), QPointF(20.5, 16))
    for x in (8, 12, 16):
        p.drawLine(QPointF(x, 11 if x == 12 else 12.2), QPointF(x, 16))


def _key(p):
    p.drawEllipse(QPointF(8, 12), 4, 4)
    p.drawLine(QPointF(12, 12), QPointF(20.5, 12))
    p.drawLine(QPointF(17.5, 12), QPointF(17.5, 15.5))
    p.drawLine(QPointF(20.5, 12), QPointF(20.5, 15))


def _logs(p):
    p.drawRoundedRect(QRectF(5, 3.5, 14, 17), 2, 2)
    for y in (8.5, 12, 15.5):
        p.drawLine(QPointF(8.5, y), QPointF(15.5, y))


def _doctor(p):
    path = QPainterPath()
    path.moveTo(3, 12.5)
    for x, y in ((7.5, 12.5), (9.5, 7), (12.5, 18), (15, 10), (16.5, 12.5), (21, 12.5)):
        path.lineTo(x, y)
    p.drawPath(path)


def _quit(p):
    p.drawArc(QRectF(5, 5.5, 14, 14), 125 * 16, 290 * 16)
    p.drawLine(QPointF(12, 3.5), QPointF(12, 11.5))


def _group(p):
    p.drawRoundedRect(QRectF(3.5, 5, 17, 14), 3, 3)
    p.drawLine(QPointF(3.5, 9), QPointF(20.5, 9))


def _info(p):
    p.drawEllipse(QPointF(12, 12), 8.5, 8.5)
    p.drawLine(QPointF(12, 11), QPointF(12, 16.5))
    p.setBrush(p.pen().color())
    p.drawEllipse(QPointF(12, 7.8), 0.6, 0.6)


def _rules(p):
    for y in (6.5, 12, 17.5):
        p.drawRoundedRect(QRectF(3.5, y - 2, 4, 4), 1, 1)
        p.drawLine(QPointF(10.5, y), QPointF(20.5, y))


def _cube(p):
    top = QPolygonF([QPointF(12, 3.5), QPointF(20, 7.5), QPointF(12, 11.5), QPointF(4, 7.5)])
    p.drawPolygon(top)
    p.drawLine(QPointF(4, 7.5), QPointF(4, 16.5))
    p.drawLine(QPointF(20, 7.5), QPointF(20, 16.5))
    p.drawLine(QPointF(12, 11.5), QPointF(12, 20.5))
    p.drawLine(QPointF(4, 16.5), QPointF(12, 20.5))
    p.drawLine(QPointF(20, 16.5), QPointF(12, 20.5))


def _cup(p):
    path = QPainterPath()
    path.moveTo(4.5, 10)
    path.lineTo(16.5, 10)
    path.lineTo(15.5, 18)
    path.cubicTo(15, 20, 6, 20, 5.5, 18)
    path.closeSubpath()
    p.drawPath(path)
    p.drawArc(QRectF(14, 11, 6, 5), -90 * 16, 180 * 16)
    for x in (8, 11.5):
        p.drawLine(QPointF(x, 3.5), QPointF(x, 6.5))


def _server(p):
    for y in (4, 13):
        p.drawRoundedRect(QRectF(4, y, 16, 7), 2, 2)
        p.setBrush(p.pen().color())
        p.drawEllipse(QPointF(7.5, y + 3.5), 0.7, 0.7)
        p.setBrush(Qt.NoBrush)
        p.drawLine(QPointF(11, y + 3.5), QPointF(17, y + 3.5))


def _window(p):
    p.drawRoundedRect(QRectF(3.5, 4.5, 17, 15), 2, 2)
    p.drawLine(QPointF(3.5, 8.5), QPointF(20.5, 8.5))
    p.drawLine(QPointF(9, 8.5), QPointF(9, 19.5))


def _globe(p):
    p.drawEllipse(QPointF(12, 12), 8.5, 8.5)
    p.drawEllipse(QPointF(12, 12), 3.8, 8.5)
    p.drawLine(QPointF(3.5, 12), QPointF(20.5, 12))


def _chip(p):
    """Memory: a chip with its pins."""
    p.drawRoundedRect(QRectF(6.5, 6.5, 11, 11), 2, 2)
    for k in (9, 12, 15):
        p.drawLine(QPointF(3.5, k), QPointF(6.5, k))
        p.drawLine(QPointF(17.5, k), QPointF(20.5, k))
        p.drawLine(QPointF(k, 3.5), QPointF(k, 6.5))
        p.drawLine(QPointF(k, 17.5), QPointF(k, 20.5))


def _spark(p):
    """The model: a four-pointed spark."""
    path = QPainterPath()
    path.moveTo(12, 3.5)
    path.cubicTo(12.8, 8.8, 15.2, 11.2, 20.5, 12)
    path.cubicTo(15.2, 12.8, 12.8, 15.2, 12, 20.5)
    path.cubicTo(11.2, 15.2, 8.8, 12.8, 3.5, 12)
    path.cubicTo(8.8, 11.2, 11.2, 8.8, 12, 3.5)
    p.drawPath(path)


def _bolt(p):
    p.drawPolygon(QPolygonF([QPointF(13.5, 3), QPointF(6, 13.5), QPointF(11.5, 13.5), QPointF(10.5, 21),
                             QPointF(18, 10.5), QPointF(12.5, 10.5)]))


def _crown(p):
    p.drawPolygon(QPolygonF([QPointF(4.5, 17), QPointF(3.5, 7.5), QPointF(8.5, 11.5), QPointF(12, 5),
                             QPointF(15.5, 11.5), QPointF(20.5, 7.5), QPointF(19.5, 17)]))
    p.drawLine(QPointF(4.5, 20), QPointF(19.5, 20))


def _shield(p):
    path = QPainterPath()
    path.moveTo(12, 3.5)
    path.lineTo(19, 6)
    path.lineTo(19, 11.5)
    path.cubicTo(19, 16, 15.5, 19.2, 12, 20.5)
    path.cubicTo(8.5, 19.2, 5, 16, 5, 11.5)
    path.lineTo(5, 6)
    path.closeSubpath()
    p.drawPath(path)


def _lock(p):
    p.drawRoundedRect(QRectF(5.5, 10.5, 13, 10), 2, 2)
    path = QPainterPath()
    path.moveTo(8.5, 10.5)
    path.lineTo(8.5, 8)
    path.arcTo(QRectF(8.5, 4.5, 7, 7), 180, -180)
    path.lineTo(15.5, 10.5)
    p.drawPath(path)
    p.setBrush(p.pen().color())
    p.drawEllipse(QPointF(12, 15.5), 1.1, 1.1)


DRAW = dict(rules=_rules, cube=_cube, cup=_cup, server=_server, window=_window, globe=_globe, play=_play, stop=_stop, cancel=_cancel, edit=_edit, move=_move, folder=_folder, copy=_copy,
            delete=_delete, plus=_plus, gear=_gear, help=_help, account=_account, bot=_bot, restart=_restart,
            connect=_connect, bridge=_bridge, key=_key, logs=_logs, doctor=_doctor, quit=_quit, group=_group,
            info=_info, chip=_chip, spark=_spark, bolt=_bolt, crown=_crown, shield=_shield, lock=_lock)


# Each icon's gesture: (kind, amount, how long in ms). The icons that draw
# their own gesture (the folder, the bin) are "own".
GESTURE = dict(gear=("turn", 45, 520), restart=("turn", 360, 620), cancel=("turn", 90, 360),
               play=("step", 2.5, 420), move=("step", 2.5, 420), connect=("step", 2.5, 420),
               doctor=("step", 2.0, 420), copy=("step", 1.5, 380),
               edit=("wiggle", 14, 480), key=("wiggle", 18, 480), help=("wiggle", 12, 480),
               folder=("own", 0, 460), delete=("own", 0, 460))
HOP = ("hop", 0.14, 380)
OWN = {"folder", "delete"}


def pixmap(name, size=18, colour=None, t=0.0):
    pm = QPixmap(size, size)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    p.scale(size / 24, size / 24)
    pen = QPen(QColor(colour or theme.ACCENT_LIGHT))
    pen.setWidthF(2.0)
    pen.setCapStyle(Qt.RoundCap)
    pen.setJoinStyle(Qt.RoundJoin)
    p.setPen(pen)
    p.setBrush(Qt.NoBrush)
    p.setFont(QFont())
    kind, amount, _ = GESTURE.get(name, HOP)
    if t and kind != "own":
        there_and_back = math.sin(math.pi * t)
        p.translate(12, 12)
        if kind == "turn":
            p.rotate(amount * t)
        elif kind == "wiggle":
            p.rotate(amount * math.sin(2 * math.pi * t))
        elif kind == "step":
            p.translate(amount * there_and_back, 0)
        else:
            p.scale(1 + amount * there_and_back, 1 + amount * there_and_back)
        p.translate(-12, -12)
    if name in OWN:
        DRAW[name](p, t)
    else:
        DRAW[name](p)
    p.end()
    return pm


def icon(name, size=18, colour=None, t=0.0):
    """One of DRAW's icons, with a greyed copy for when its button is off."""
    ic = QIcon(pixmap(name, size, colour, t))
    ic.addPixmap(pixmap(name, size, theme.FAINT), QIcon.Disabled)
    return ic


class _Gesture(QObject):
    """Plays an icon's gesture on a button when the pointer comes over it."""

    def __init__(self, button, name, size, colour=None):
        super().__init__(button)
        self.button, self.name, self.size, self.colour = button, name, size, colour
        kind, _, ms = GESTURE.get(name, HOP)
        self.motion = QVariantAnimation(self)
        self.motion.setStartValue(0.0)
        self.motion.setEndValue(1.0)
        self.motion.setDuration(ms)
        self.motion.setEasingCurve(QEasingCurve.InOutSine if kind == "turn" else QEasingCurve.Linear)
        self.motion.valueChanged.connect(self._frame)
        self.motion.finished.connect(lambda: self._frame(0.0))
        button.installEventFilter(self)

    def _frame(self, t):
        self.button.setIcon(icon(self.name, self.size, self.colour, t=float(t)))

    def eventFilter(self, obj, event):
        if event.type() == QEvent.Enter and animated and obj.isEnabled() \
                and self.motion.state() != QVariantAnimation.Running:
            self.motion.start()
        return False


def set_on(button, name, size=18, colour=None):
    """An icon on a button, making its gesture when hovered (if icons are
    animated). Called again, it only changes the icon."""
    button.setIcon(icon(name, size, colour))
    gesture = getattr(button, "_gesture", None)
    if gesture is None:
        button._gesture = _Gesture(button, name, size, colour)
    else:
        gesture.name, gesture.size, gesture.colour = name, size, colour
    return button

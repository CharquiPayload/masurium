"""Motion: a switch that slides, windows and panels that fade in, a group
that unfolds softly, a tile that appears where it was dropped. On by
default, off in the launcher's settings; off, everything simply is where
it goes."""
from PySide6.QtCore import QAbstractAnimation, QEasingCurve, QPropertyAnimation
from PySide6.QtWidgets import QGraphicsOpacityEffect

enabled = True
DURATION = 170


def fade_in(widget, ms=DURATION):
    """The widget comes in from transparent. Its effect is taken off at the
    end: an opacity effect left on costs drawing, and would fight any other."""
    if not enabled or widget is None or widget.graphicsEffect() is not None:
        return
    effect = QGraphicsOpacityEffect(widget)
    effect.setOpacity(0.0)
    widget.setGraphicsEffect(effect)
    motion = QPropertyAnimation(effect, b"opacity", widget)
    motion.setDuration(ms)
    motion.setStartValue(0.0)
    motion.setEndValue(1.0)
    motion.setEasingCurve(QEasingCurve.OutCubic)
    motion.finished.connect(lambda: widget.setGraphicsEffect(None))
    motion.start(QAbstractAnimation.DeleteWhenStopped)


def slide(target, prop, to, ms=DURATION, parent=None):
    """A numeric property of `target` moved to `to`, or set at once when
    motion is off. Returns the animation, or None."""
    if not enabled:
        target.setProperty(prop.decode() if isinstance(prop, bytes) else prop, to)
        return None
    motion = QPropertyAnimation(target, prop if isinstance(prop, bytes) else prop.encode(), parent or target)
    motion.setDuration(ms)
    motion.setEndValue(to)
    motion.setEasingCurve(QEasingCurve.InOutCubic)
    motion.start(QAbstractAnimation.DeleteWhenStopped)
    return motion

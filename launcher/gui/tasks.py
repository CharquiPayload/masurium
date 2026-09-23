"""Work that takes time, off the window's thread.

A start waits minutes for a game to load; asking a server that is down waits
seconds for a timeout. Done on the window's thread, either would freeze it,
and a frozen window looks like a crash. So operations run on threads of their
own, exactly as the command line runs them (the same functions, the same
Cancel), and what they report comes back as signals, which Qt delivers on the
window's thread.
"""
import threading
import traceback

from PySide6.QtCore import QObject, Signal

from ..events import Cancel, Cancelled, Fail


class Tasks(QObject):
    """The operations running now, at most one per target (an instance's
    key, "group team"...): the core refuses a second one anyway, and a button
    that looks pressable while its instance is busy would be a lie."""

    event = Signal(str, object)          # target, Event
    done = Signal(str, str, object)      # target, title, what it returned
    failed = Signal(str, str, object)    # target, title, the Fail (or Cancelled, or a bug)
    changed = Signal()                   # one started or ended
    _ended = Signal(str, bool)

    def __init__(self, parent=None):
        super().__init__(parent)
        self._running = {}               # target -> (title, Cancel)
        self._then = {}                  # target -> what to do when it ends, on the window's thread
        self._ended.connect(self._forget)

    def busy(self, target):
        """What is running on it, or None."""
        entry = self._running.get(target)
        return entry[0] if entry else None

    def running(self):
        return {t: title for t, (title, _) in self._running.items()}

    def run(self, target, title, fn, then=None):
        """fn(on_event, cancel) on a thread. False if the target is busy.
        `then(ok)`, if given, is called on the window's thread when it ends."""
        if target in self._running:
            return False
        cancel = Cancel()
        self._running[target] = (title, cancel)
        if then:
            self._then[target] = then

        def work():
            ok = False
            try:
                result = fn(lambda e: self.event.emit(target, e), cancel)
            except (Fail, Cancelled) as e:
                self.failed.emit(target, title, e)
            except Exception as e:           # a bug: said with its trace, not swallowed
                e.trace = traceback.format_exc()
                self.failed.emit(target, title, e)
            else:
                ok = True
                self.done.emit(target, title, result)
            finally:
                self._ended.emit(target, ok)

        threading.Thread(target=work, name=f"task {target}", daemon=True).start()
        self.changed.emit()
        return True

    def cancel(self, target):
        entry = self._running.get(target)
        if entry:
            entry[1].set()

    def _forget(self, target, ok):
        self._running.pop(target, None)
        then = self._then.pop(target, None)
        if then:
            try:
                then(ok)
            except RuntimeError:
                pass                     # what asked is gone (a window closed meanwhile)
        self.changed.emit()


def _answer(callback, value):
    """An answer to what asked, unless what asked is gone: a page closed
    while its server was still being asked leaves its labels deleted, and
    writing on them would be an error for nothing."""
    try:
        callback(value)
    except RuntimeError as e:
        if "already deleted" not in str(e):
            raise


class Background(QObject):
    """One question on a thread, its answer back on the window's: for what a
    dialog needs from a server before it can show anything."""

    answered = Signal(object, object)    # callback, result
    broke = Signal(object, object)       # callback, exception

    def __init__(self, parent=None):
        super().__init__(parent)
        self.answered.connect(_answer)
        self.broke.connect(_answer)

    def ask(self, fn, then, otherwise):
        def work():
            try:
                result = fn()
            except Exception as e:
                self.broke.emit(otherwise, e)
            else:
                self.answered.emit(then, result)
        threading.Thread(target=work, name="background", daemon=True).start()

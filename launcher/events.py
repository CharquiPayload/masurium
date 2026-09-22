"""What the core says while it works, how it fails, and how it is told to stop.

The core never prints. It hands Events to whoever called it: the command line
prints them, a window will draw them, a test collects them. It fails by
raising Fail, whose message is the whole story and whose lines are the
evidence (the cause in a crash report, the last lines of a log), so that the
one who shows it decides how. And every long wait can be cut short with a
Cancel.
"""
import threading
import time
from dataclasses import dataclass


class Fail(Exception):
    """Something the user has to fix. `code` names the failure for a program
    that has to tell one from another (a window choosing what to offer next);
    a person reads the message."""

    def __init__(self, message, lines=(), code=None):
        super().__init__(message)
        self.lines = tuple(lines)
        self.code = code


@dataclass(frozen=True)
class Event:
    """One thing said during an operation.

    kind:   "step" (a stage begins), "detail" (about the current stage) or
            "warning" (something that may go wrong later: said, not refused).
    stage:  for a step, a fixed name a program can follow ("loading",
            "joining"...) while the text is free to change.
    lines:  what goes under the text, one per line.
    """
    kind: str
    text: str
    lines: tuple = ()
    stage: str = None


class Report:
    """The callback the caller gave, with a name per kind. Without one,
    nothing is said."""

    def __init__(self, sink=None):
        self.sink = sink

    def _emit(self, kind, text, lines, stage=None):
        if self.sink:
            self.sink(Event(kind, text, tuple(lines), stage))

    def step(self, text, lines=(), stage=None):
        self._emit("step", text, lines, stage)

    def detail(self, text, lines=()):
        self._emit("detail", text, lines)

    def warning(self, text, lines=()):
        self._emit("warning", text, lines)


def report_to(on_event):
    """A Report from whatever was given: a Report, a callable, or None."""
    return on_event if isinstance(on_event, Report) else Report(on_event)


class Cancelled(Fail):
    """The operation was asked to stop, and did, undoing what it had started."""

    def __init__(self, message="cancelled", lines=()):
        super().__init__(message, lines, code="cancelled")


class Cancel:
    """How to stop an operation that is waiting: a window's Cancel button, a
    Ctrl+C. The operation looks at it in every wait, so it notices at once and
    not when the wait ends; set from any thread."""

    def __init__(self):
        self._flag = threading.Event()

    def set(self):
        self._flag.set()

    def is_set(self):
        return self._flag.is_set()

    def check(self):
        if self._flag.is_set():
            raise Cancelled()

    def sleep(self, seconds):
        if self._flag.wait(seconds):
            raise Cancelled()


def pause(seconds, cancel=None):
    if cancel:
        cancel.sleep(seconds)
    else:
        time.sleep(seconds)


def wait_for(predicate, seconds, every=1.0, cancel=None):
    """True as soon as predicate() is, False when `seconds` pass first;
    Cancelled the moment `cancel` is set."""
    deadline = time.monotonic() + seconds
    while True:
        if cancel:
            cancel.check()
        if predicate():
            return True
        if time.monotonic() >= deadline:
            return False
        pause(every, cancel)

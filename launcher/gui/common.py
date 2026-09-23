"""What every window of the launcher shares: dialogs that stand out from
the main window, a yes-or-no question, a few labels, and a console for a
program that asks things (HeadlessMC logging an account in)."""
from PySide6.QtCore import QProcess, QProcessEnvironment, Qt, QUrl
from PySide6.QtGui import QDesktopServices, QFontDatabase
from PySide6.QtWidgets import (QDialog, QDialogButtonBox, QHBoxLayout, QLabel, QLineEdit, QMessageBox,
                               QPlainTextEdit, QPushButton, QSizePolicy, QVBoxLayout, QWidget)

from . import anim, icons, theme

# Where Help leads: the setup guide, at the part about what is on screen.
DOCS = "https://github.com/CharquiPayload/masurium/blob/main/docs/setup.md"


class Dialog(QDialog):
    """Every dialog: a shade lighter than the main window, with an edge in the
    accent colour, so it stands out from what it opened over; it fades in."""

    def paintEvent(self, event):
        super().paintEvent(event)
        theme.frame(self)

    def showEvent(self, event):
        super().showEvent(event)
        for child in self.findChildren(QWidget, options=Qt.FindDirectChildrenOnly):
            anim.fade_in(child)


class MessageBox(QMessageBox):
    def paintEvent(self, event):
        super().paintEvent(event)
        theme.frame(self)


def open_help(anchor=""):
    QDesktopServices.openUrl(QUrl(DOCS + (f"#{anchor}" if anchor else "")))


class ButtonRow(QWidget):
    """Prism's bottom row: Help on the left, when there is a page about it,
    and the dialog's buttons on the right."""

    def __init__(self, buttons, help=None):
        super().__init__()
        self.row = QHBoxLayout(self)
        self.row.setContentsMargins(0, 6, 0, 0)
        if help is not None:
            b = QPushButton("Help")
            icons.set_on(b, "help")
            b.clicked.connect(lambda: open_help(help))
            self.row.addWidget(b)
        self.row.addStretch()
        self.buttons = buttons
        buttons.setSizePolicy(QSizePolicy.Maximum, QSizePolicy.Fixed)
        self.row.addWidget(buttons)

    def add(self, widget):
        """A button of the dialog's own, just left of the standard ones."""
        self.row.insertWidget(self.row.indexOf(self.buttons), widget)


def close_row(dialog, help=None):
    """Close on the right (some window managers draw no title bar to close
    from), Help on the left."""
    buttons = QDialogButtonBox(QDialogButtonBox.Close)
    buttons.rejected.connect(dialog.reject)
    return ButtonRow(buttons, help)


def ok_row(dialog, ok_text, on_ok, help=None):
    """The dialog's own OK, named for what it does (Create, Move…), and
    Cancel on the right; Help on the left."""
    buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
    ok = buttons.button(QDialogButtonBox.Ok)
    ok.setText(ok_text)
    ok.setObjectName("primary")
    buttons.accepted.connect(on_ok)
    buttons.rejected.connect(dialog.reject)
    return ButtonRow(buttons, help)


def ask(parent, heading, text):
    """A yes or no, framed like the rest."""
    box = MessageBox(QMessageBox.Question, heading, text, QMessageBox.Yes | QMessageBox.No, parent)
    return box.exec() == QMessageBox.Yes


def muted(text):
    lab = QLabel(text)
    lab.setObjectName("muted")
    lab.setWordWrap(True)
    return lab


def title(text):
    lab = QLabel(text)
    lab.setObjectName("title")
    return lab


def monospace():
    return QFontDatabase.systemFont(QFontDatabase.FixedFont)


class ConsoleDialog(Dialog):
    """A program that asks things, HeadlessMC logging an account in: what it
    says, and a line to answer it. `finished(code)` is called when it ends."""

    def __init__(self, win, heading, hint, argv, cwd, env, finished):
        super().__init__(win)
        self.finished_cb = finished
        self.setWindowTitle(heading)
        self.resize(860, 520)
        v = QVBoxLayout(self)
        v.addWidget(title(heading))
        v.addWidget(muted(hint))
        self.out = QPlainTextEdit()
        self.out.setReadOnly(True)
        self.out.setFont(monospace())
        v.addWidget(self.out, 1)
        row = QHBoxLayout()
        self.line = QLineEdit()
        self.line.setPlaceholderText("type here and press Enter")
        self.line.returnPressed.connect(self._send)
        row.addWidget(self.line, 1)
        for word in ("login", "quit"):
            b = QPushButton(word)
            b.clicked.connect(lambda _=False, w=word: self._send(w))
            row.addWidget(b)
        v.addLayout(row)
        self.proc = QProcess(self)
        environment = QProcessEnvironment()
        for k, val in env.items():
            environment.insert(k, val)
        self.proc.setProcessEnvironment(environment)
        self.proc.setWorkingDirectory(str(cwd))
        self.proc.setProcessChannelMode(QProcess.MergedChannels)
        self.proc.readyReadStandardOutput.connect(self._read)
        self.proc.finished.connect(self._ended)
        self.proc.start(argv[0], [str(a) for a in argv[1:]])

    def _read(self):
        self.out.appendPlainText(bytes(self.proc.readAllStandardOutput()).decode("utf-8", "replace").rstrip())

    def _send(self, text=None):
        text = text if isinstance(text, str) else self.line.text()
        self.line.clear()
        self.out.appendPlainText(f"> {text}")
        self.proc.write((text + "\n").encode())

    def _ended(self, code, _status):
        self.out.appendPlainText(f"(it ended, code {code})")
        self.line.setEnabled(False)
        self.finished_cb(code)

    def reject(self):
        if self.proc.state() != QProcess.NotRunning:
            self.proc.kill()
            self.proc.waitForFinished(3000)
        super().reject()

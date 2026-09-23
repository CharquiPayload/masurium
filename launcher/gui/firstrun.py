"""Set up this machine: the window's face on launcher/firstrun.py. Shown when
the window opens and a first bot would still lack something the launcher can
get itself (the downloads, the mod, the way to the server, a server), and from
Help at any time."""
from PySide6.QtCore import Qt
from PySide6.QtGui import QColor
from PySide6.QtWidgets import (QFormLayout, QHBoxLayout, QLineEdit, QListWidget, QListWidgetItem, QPushButton,
                               QVBoxLayout)

from .. import firstrun
from . import icons, theme
from .common import Dialog, close_row, muted, title

TASK = "setup"


class SetupDialog(Dialog):
    def __init__(self, win):
        super().__init__(win)
        self.win, self.ws = win, win.ws
        self.setWindowTitle("Set up this machine")
        self.resize(860, 820)
        v = QVBoxLayout(self)
        v.addWidget(title("Set up this machine"))
        v.addWidget(muted("What a first bot needs. What the launcher can get, it gets: the buttons below. "
                          "The rest says how to get it."))
        self.list = QListWidget()
        self.list.setWordWrap(True)
        self.list.setMinimumHeight(230)
        v.addWidget(self.list, 1)

        self.get = QPushButton("Get HeadlessMC, hmc-specifics and the Masurium mod")
        icons.set_on(self.get, "plus")
        self.get.clicked.connect(self._get)
        v.addWidget(self.get, 0, Qt.AlignLeft)

        v.addWidget(title("The server's Masurium mod"))
        v.addWidget(muted("Where it listens, and its token: `host`, `port` and `token` in the masurium.properties "
                          "next to the server's jar. The launcher asks it which players are in and which mods it "
                          "runs."))
        env = self.ws.env_values() if self.ws.env_file.is_file() else {}
        form = QFormLayout()
        self.host = QLineEdit(env.get("MASURIUM_HOST", ""))
        self.host.setPlaceholderText("192.168.1.10")
        self.port = QLineEdit(env.get("MASURIUM_PORT", "") or "8477")
        self.token = QLineEdit(env.get("MASURIUM_TOKEN", ""))
        self.token.setEchoMode(QLineEdit.Password)
        show = self.token.addAction(icons.icon("key"), QLineEdit.TrailingPosition)
        show.setToolTip("Show or hide the token")
        show.triggered.connect(lambda: self.token.setEchoMode(
            QLineEdit.Normal if self.token.echoMode() == QLineEdit.Password else QLineEdit.Password))
        self.owner = QLineEdit(env.get("MASURIUM_OWNER", ""))
        self.owner.setPlaceholderText("your player name: the bots' owner (optional)")
        form.addRow("Address", self.host)
        form.addRow("Port", self.port)
        form.addRow("Token", self.token)
        form.addRow("Owner", self.owner)
        v.addLayout(form)
        self.connect_button = QPushButton("Connect")
        icons.set_on(self.connect_button, "connect")
        self.connect_button.clicked.connect(self._connect)
        v.addWidget(self.connect_button, 0, Qt.AlignLeft)

        v.addWidget(title("The first server"))
        v.addWidget(muted("The server the bots join: a name for it here, and its game address. More can be "
                          "added later as folders under servers/."))
        form = QFormLayout()
        self.slug = QLineEdit("my-server")
        self.address = QLineEdit()
        self.address.setPlaceholderText("the same as above, usually")
        self.game_port = QLineEdit("25565")
        form.addRow("Name", self.slug)
        form.addRow("Game address", self.address)
        form.addRow("Game port", self.game_port)
        v.addLayout(form)
        self.add_button = QPushButton("Add server")
        icons.set_on(self.add_button, "server")
        self.add_button.clicked.connect(self._add_server)
        v.addWidget(self.add_button, 0, Qt.AlignLeft)

        row = QHBoxLayout()
        again = QPushButton("Check again")
        again.clicked.connect(self.refresh)
        row.addWidget(again)
        self.first = QPushButton("Add the first instance…")
        icons.set_on(self.first, "plus")
        self.first.setObjectName("primary")
        self.first.clicked.connect(self._first_instance)
        row.addWidget(self.first)
        row.addStretch()
        row.addWidget(close_row(self, help="quick-start"))
        v.addLayout(row)
        self.needs = []
        self.refresh()

    # --- what there is ---------------------------------------------------------------

    def refresh(self):
        self.list.clear()
        self.list.addItem("checking…")
        self.win.background.ask(lambda: firstrun.needs(self.ws), self._show,
                                lambda e: self.list.addItem(f"it could not check: {e}"))

    def _show(self, needs):
        self.needs = needs
        self.list.clear()
        for n in needs:
            mark = "✓" if n.ok else ("→" if n.can else "✗")
            it = QListWidgetItem(f"{mark}  {n.label}:  {n.detail}")
            it.setForeground(QColor(theme.IN_SERVER if n.ok else (theme.MUTED if n.can else theme.WRONG)))
            it.setToolTip(n.detail)
            self.list.addItem(it)
        lacking = {n.key for n in needs if not n.ok}
        busy = bool(self.win.tasks.busy(TASK))
        self.get.setEnabled(not busy and any(n.can for n in needs if n.key.startswith("download:")
                                             or n.key in ("masurium", "newer_jars")))
        self.connect_button.setText("Connect" if "server_env" in lacking else "Connect again")
        self.add_button.setEnabled("server" in lacking)
        for w in (self.slug, self.address, self.game_port):
            w.setEnabled("server" in lacking)
        self.first.setVisible(not [k for k in lacking if k not in ("java", "claude", "newer_jars")])

    # --- doing it ----------------------------------------------------------------------

    def _run(self, heading, work):
        def then(ok):
            if self.isVisible():
                self.refresh()
        if not self.win.tasks.run(TASK, heading, work, then=then):
            self.win.alert("Busy", "The setup is already doing something: wait for it to finish.")
            return
        self.get.setEnabled(False)

    def _get(self):
        ws = self.ws

        def work(on_event, cancel):
            got = firstrun.fetch(ws, on_event, cancel)
            if not firstrun.masurium_in_shared(ws) or firstrun.older_jars(ws):
                got += firstrun.put_mods(ws, on_event)
            return got
        self._run("getting what is missing", work)

    def _connect(self):
        ws = self.ws
        host, port, token, owner = (w.text().strip() for w in (self.host, self.port, self.token, self.owner))
        if not self.address.text().strip():
            self.address.setText(host)
        self._run("connecting to the server",
                  lambda on_event, cancel: firstrun.connect(ws, host, port, token, owner, on_event=on_event))

    def _add_server(self):
        ws = self.ws
        slug, address, port = (w.text().strip() for w in (self.slug, self.address, self.game_port))
        address = address or self.host.text().strip()
        self._run("adding the server",
                  lambda on_event, cancel: firstrun.add_server(ws, slug, address, port, on_event=on_event))

    def _first_instance(self):
        self.accept()
        self.win.new_instance()

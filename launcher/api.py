"""The server mod's HTTP API: the one to ask who is connected and which mods
the server loaded. Asked, never trusted to answer: every question has an
answer for "the server is down"."""
import json
import urllib.error
import urllib.request

UNREACHABLE = (urllib.error.URLError, OSError, ValueError)


class ServerApi:
    def __init__(self, host, port, token, owner=""):
        self.host = host or "127.0.0.1"
        self.port = str(port or "8477")
        self.token = token or ""
        self.owner = owner or ""

    def __repr__(self):
        return f"ServerApi({self.address})"

    @property
    def address(self):
        return f"{self.host}:{self.port}"

    def get(self, route, timeout=5):
        """The body of one answer. Raises when there is none (UNREACHABLE),
        HTTPError included, whose code says a wrong token (401/403)."""
        req = urllib.request.Request(f"http://{self.address}{route}",
                                     headers={"X-Masurium-Token": self.token})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read().decode("utf-8", "replace")

    def players_text(self):
        try:
            return self.get("/players")
        except UNREACHABLE:
            return ""

    def is_inside(self, name):
        """The server is the one to ask whether a bot is in, not the client log:
        the log says what the client believes, /players says what there is."""
        return f'"{name}"' in self.players_text()

    def mods(self):
        """The mods the server loaded, {id: version}, from /mods. None when the
        server mod is older than that route, or does not answer."""
        try:
            data = json.loads(self.get("/mods"))
        except UNREACHABLE:
            return None
        if not isinstance(data, dict) or not isinstance(data.get("mods"), list):
            return None
        return {m["id"]: str(m.get("version", "")) for m in data["mods"]
                if isinstance(m, dict) and "id" in m}

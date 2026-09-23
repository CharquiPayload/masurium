"""Claude Code, what the brain runs on: the version this machine has, and
whether a newer one is out (the releases of github.com/anthropics/claude-code).

The launcher says so, at a bridge's start and in doctor, and never updates it
by itself: a bot's brain changing under it is a decision, not a side effect.
Which model `opus` means depends on this version, too: an old Claude Code
kept a bot on the Opus before the newest one without anyone noticing.
"""
import json
import re
import shutil
import time
import urllib.request

from .processes import run_quiet

RELEASES = "https://api.github.com/repos/anthropics/claude-code/releases/latest"
UPDATE = "claude update"
# GitHub is asked at most this often; in between, its last answer is kept in
# the state folder. A start must not wait on the network every time.
EVERY = 6 * 3600
CACHE = "claude-latest.json"


def version_of(text):
    """(2, 1, 280) from "2.1.280 (Claude Code)" or "v2.1.280"; None if none."""
    m = re.search(r"(\d+)\.(\d+)\.(\d+)", text or "")
    return tuple(int(x) for x in m.groups()) if m else None


def dotted(version):
    return ".".join(str(x) for x in version)


def installed(ws):
    """(its path, its version) of the Claude Code the bridge would run, or
    (None, None) when there is none."""
    exe = shutil.which("claude", path=ws.child_env()["PATH"])
    if not exe:
        return None, None
    code, text = run_quiet([exe, "--version"])
    return exe, (version_of(text) if code == 0 else None)


def fetch_latest():
    """The tag of the latest release, asked of GitHub."""
    request = urllib.request.Request(RELEASES, headers={"Accept": "application/vnd.github+json",
                                                        "User-Agent": "masurium-launcher"})
    with urllib.request.urlopen(request, timeout=5) as r:
        return json.load(r).get("tag_name")


def latest(ws, now=None):
    """The newest Claude Code, as (2, 1, 280), or None when it cannot be known
    (no network, GitHub refusing): then nothing is said, and it is asked
    again after EVERY."""
    now = time.time() if now is None else now
    cache = ws.state_dir / CACHE
    try:
        kept = json.loads(cache.read_text(encoding="utf-8"))
        if now - kept["at"] < EVERY:
            return tuple(kept["version"]) if kept.get("version") else None
    except (OSError, ValueError, KeyError, TypeError):
        pass
    try:
        version = version_of(fetch_latest())
    except Exception:
        version = None
    try:
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_text(json.dumps({"at": now, "version": list(version) if version else None}),
                         encoding="utf-8")
    except OSError:
        pass
    return version


def newer(ws, have=None, now=None):
    """(this machine's, the latest) as text when a newer Claude Code is out;
    None when it is up to date or either is unknown."""
    if have is None:
        have = installed(ws)[1]
    want = latest(ws, now)
    if have and want and want > have:
        return dotted(have), dotted(want)
    return None

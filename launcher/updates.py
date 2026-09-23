"""Masurium itself: whether a newer release of it is out (the releases of
github.com/CharquiPayload/masurium), and how this copy is updated.

The launcher says so, when the window opens and in doctor, and never updates
itself: it is updated the way it was installed (its package, install.sh
again, git pull), and the bots' jars change only when asked (firstrun.py's
older_jars), because the server needs the same one.
"""
import json
import pathlib
import time
import urllib.request

from . import __version__, operations
from .brain import dotted, version_of

# The repository, named once: its releases' page, and GitHub's API over them.
HOME = "https://github.com/CharquiPayload/masurium"
PAGE = HOME + "/releases/latest"
RELEASES = HOME.replace("https://github.com/", "https://api.github.com/repos/") + "/releases/latest"
# GitHub is asked at most once a day; in between, its last answer is kept in
# the state folder. Opening the window must not wait on the network.
EVERY = 24 * 3600
CACHE = "masurium-latest.json"


def fetch_latest():
    """(the tag, the page) of the latest release, asked of GitHub. Drafts and
    pre-releases are not the latest."""
    request = urllib.request.Request(RELEASES, headers={"Accept": "application/vnd.github+json",
                                                        "User-Agent": "masurium-launcher"})
    with urllib.request.urlopen(request, timeout=5) as r:
        data = json.load(r)
    return data.get("tag_name"), data.get("html_url")


def latest(ws, now=None):
    """(the newest release, as (1, 1, 0), its page), or None when it cannot
    be known (no network, GitHub refusing): then nothing is said, and it is
    asked again after EVERY."""
    now = time.time() if now is None else now
    cache = ws.state_dir / CACHE
    try:
        kept = json.loads(cache.read_text(encoding="utf-8"))
        if now - kept["at"] < EVERY:
            return (tuple(kept["version"]), kept.get("page") or PAGE) if kept.get("version") else None
    except (OSError, ValueError, KeyError, TypeError):
        pass
    try:
        tag, page = fetch_latest()
        version = version_of(tag)
    except Exception:
        version, page = None, None
    try:
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_text(json.dumps({"at": now, "version": list(version) if version else None,
                                     "page": page}), encoding="utf-8")
    except OSError:
        pass
    return (version, page or PAGE) if version else None


def newer(ws, have=None, now=None):
    """(this launcher's version, the latest's, its page) as text when a newer
    Masurium is out; None when this one is the latest or either is unknown."""
    have = version_of(have or __version__)
    got = latest(ws, now)
    if have and got and got[0] > have:
        return dotted(have), dotted(got[0]), got[1]
    return None


def how_to_update(root=None):
    """How this copy of the launcher is updated, from where it lives."""
    root = pathlib.Path(root or operations.REPO)
    if (root / ".git").exists():
        return "git pull in its folder, and build the mod again"
    if root.parts[:2] in (("/", "opt"), ("/", "usr")):
        return "install the new package from the release page, the way this one was (the .deb, or the PKGBUILD)"
    return "unpack the new release and run its ./install.sh again"

"""Masurium itself: whether a newer release of it is out (the releases of
github.com/CharquiPayload/masurium), how this copy is updated, and, for a copy
install.sh made, updating it.

The launcher says so, when the window opens and in doctor, and never updates
itself unasked: a package is updated by its package manager, a clone by git,
and a copy install.sh made when its user presses Update now (install_latest,
the same as running the one-line installer again). The bots' jars change only
when asked too (firstrun.py's older_jars), because the server needs the same one.
"""
import hashlib
import json
import os
import pathlib
import re
import subprocess
import tarfile
import tempfile
import time
import urllib.request

from . import __version__, operations
from .api import UNREACHABLE
from .brain import dotted, version_of
from .events import Cancelled, Fail, report_to

# The repository, named once: its releases' page, and GitHub's API over them.
HOME = "https://github.com/CharquiPayload/masurium"
PAGE = HOME + "/releases/latest"
RELEASES = HOME.replace("https://github.com/", "https://api.github.com/repos/") + "/releases/latest"
# The latest release's files: install.sh among them, the one-line installer.
DOWNLOAD = HOME + "/releases/latest/download"
ONE_LINE = f"curl -fsSL {DOWNLOAD}/install.sh | sh"
# GitHub is asked at most once a day; in between, its last answer is kept in
# the state folder. Opening the window must not wait on the network.
EVERY = 24 * 3600
CACHE = "masurium-latest.json"
# What install.sh leaves in a copy it made, saying so.
INSTALLER = "INSTALLER"
# The launcher's tarball in a release's SHA256SUMS, as sha256sum writes it.
TARBALL = re.compile(r"^([0-9a-f]{64}) [ *](masurium-launcher-[0-9][A-Za-z0-9.+~-]*\.tar\.gz)$", re.M)


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


def made_by_install_sh(root=None):
    """Whether install.sh made this copy: then it is its user's, and it can
    update itself without sudo."""
    try:
        return (pathlib.Path(root or operations.REPO) / INSTALLER).read_text(encoding="utf-8").strip() == "install.sh"
    except OSError:
        return False


def how_to_update(root=None):
    """How this copy of the launcher is updated, from where it lives."""
    root = pathlib.Path(root or operations.REPO)
    if (root / ".git").exists():
        return "git pull in its folder, and build the mod again"
    if made_by_install_sh(root):
        return f"the window's Update now, or  {ONE_LINE}  again"
    if root.parts[:2] in (("/", "opt"), ("/", "usr")):
        return "install the new package from the release page, the way this one was (the .deb, or the PKGBUILD)"
    return f"install the new release:  {ONE_LINE}"


def _unpack(tar, where):
    """A tarball's files under `where`, and nothing outside it."""
    if hasattr(tarfile, "data_filter"):
        tar.extractall(where, filter="data")
        return
    inside = str(pathlib.Path(where).resolve()) + os.sep
    for member in tar.getmembers():
        if not (member.isfile() or member.isdir()) \
                or not str((pathlib.Path(where) / member.name).resolve()).startswith(inside):
            raise Fail(f"{member.name}: not a file of the release's own", code="bad_download")
    tar.extractall(where)


def install_latest(on_event=None, cancel=None, base=None, opener=None, root=None):
    """The latest release over this copy, the way install.sh installed it:
    the release's SHA256SUMS names the launcher's tarball and its checksum,
    the tarball is checked before anything in it runs, and its own install.sh
    installs it, as the one-line installer does. Only for a copy install.sh
    made. The bots keep running; this launcher is the old one until it
    restarts. Returns the version installed."""
    report = report_to(on_event)
    root = pathlib.Path(root or operations.REPO)
    if not made_by_install_sh(root):
        raise Fail(f"this copy of the launcher is updated another way: {how_to_update(root)}",
                   code="not_install_sh")
    base = (base or os.environ.get("MASURIUM_RELEASE_URL") or DOWNLOAD).rstrip("/")
    report.step("downloading the latest Masurium Launcher", stage="download")

    def get(name):
        request = urllib.request.Request(f"{base}/{name}", headers={"User-Agent": "masurium-launcher"})
        chunks = []
        try:
            with (opener or urllib.request.urlopen)(request, timeout=60) as r:
                while True:
                    if cancel is not None and cancel.is_set():
                        raise Cancelled("the update was cancelled: nothing was installed")
                    chunk = r.read(1 << 16)
                    if not chunk:
                        break
                    chunks.append(chunk)
        except UNREACHABLE as e:
            raise Fail(f"{base}/{name} could not be downloaded: {getattr(e, 'reason', e)}", code="download")
        return b"".join(chunks)

    m = TARBALL.search(get("SHA256SUMS").decode("utf-8", "replace"))
    if not m:
        raise Fail("the release's SHA256SUMS names no masurium-launcher-<version>.tar.gz", code="bad_download")
    name = m.group(2)
    report.detail(name)
    data = get(name)
    if hashlib.sha256(data).hexdigest() != m.group(1):
        raise Fail(f"{name} did not arrive as the release has it (its checksum differs): nothing was installed",
                   code="bad_download")
    with tempfile.TemporaryDirectory(prefix="masurium-update-") as tmp:
        tarball = pathlib.Path(tmp) / name
        tarball.write_bytes(data)
        try:
            with tarfile.open(tarball) as tar:
                _unpack(tar, tmp)
        except tarfile.TarError as e:
            raise Fail(f"{name} could not be unpacked: {e}", code="bad_download")
        installer = pathlib.Path(tmp) / name.removesuffix(".tar.gz") / "install.sh"
        if not installer.is_file():
            raise Fail(f"{name} has no install.sh", code="bad_download")
        # From here on it is not cancelled: install.sh swaps the program whole.
        report.step(f"installing {name}", stage="install")
        done = subprocess.run(["sh", str(installer)], stdin=subprocess.DEVNULL, capture_output=True, text=True,
                              timeout=1800)
        if done.returncode != 0:
            raise Fail(f"{name}: its install.sh failed", lines=(done.stdout + done.stderr).splitlines()[-15:],
                       code="install")
    version = version_of(name)
    report.detail(f"Masurium Launcher {dotted(version) if version else name} is installed")
    return dotted(version) if version else name

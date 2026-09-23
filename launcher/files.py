"""Files the launcher reads and writes, and the few ways it reads them."""
import os
import pathlib
import re
import shutil

try:
    import fcntl
except ImportError:          # Windows
    fcntl = None
    import msvcrt

# Windows locks bytes, not files, and a locked byte cannot be read by anyone
# else: a lock taken on the pid itself hid who held it. The lock goes on a
# byte far past what is written, where nothing is ever read.
LOCK_BYTE = 1 << 30


def read_env_file(path):
    """KEY=VALUE lines, as a shell would read them but without running one:
    comments and blanks skipped, an `export ` prefix ignored, matching quotes
    around the value removed."""
    values = {}
    try:
        text = pathlib.Path(path).read_text(encoding="utf-8")
    except OSError:
        return values
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        if line.startswith("export "):
            line = line[len("export "):].lstrip()
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key] = value
    return values


def read_java_properties(path):
    values = {}
    try:
        for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                values[k.strip()] = v.strip()
    except OSError:
        pass
    return values


def read_pid(path):
    try:
        return int(pathlib.Path(path).read_text().split()[0])
    except (OSError, ValueError, IndexError):
        return None


def unlink_quietly(*paths):
    for p in paths:
        try:
            pathlib.Path(p).unlink()
        except OSError:
            pass


def fresh_logs(*paths):
    """Logs that start empty: each one deleted, or, when that cannot be done,
    emptied. Windows will not delete a file some process still has open (a
    keeper on its way out, still holding its log), but lets another empty it;
    a log left whole would show the next start the last one's lines."""
    for p in paths:
        try:
            pathlib.Path(p).unlink(missing_ok=True)
        except OSError:
            try:
                open(p, "w").close()
            except OSError:
                pass


def write_private(path, text):
    """A file only this user can read, written whole or not at all: whoever
    reads it never sees half of it."""
    path = pathlib.Path(path)
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)


def link_dir(target, link):
    """`link` becomes a link to the folder `target`, replacing what was there.
    A symlink where the system allows one; on Windows, where a symlink needs
    rights a user may not have, a junction, which does not (untested there
    until the Windows port)."""
    link = pathlib.Path(link)
    link.parent.mkdir(parents=True, exist_ok=True)
    if link.is_symlink() or link.is_file():
        link.unlink()
    elif link.is_dir():
        # A junction reads as a folder; a real folder here is not ours to delete.
        try:
            os.rmdir(link)
        except OSError:
            raise OSError(f"{link} is a real folder, not a link: it will not be replaced")
    try:
        os.symlink(target, link, target_is_directory=True)
    except OSError:
        if os.name != "nt":
            raise
        import subprocess
        subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(target)],
                       check=True, capture_output=True)


def link_target(link):
    """Where a folder link points, or None. Windows may hand it back behind
    the prefix it gives long paths and junctions, which is no part of where it
    points and is dropped."""
    try:
        target = os.readlink(link)
    except OSError:
        return None
    for prefix in ("\\\\?\\", "\\??\\"):
        if target.startswith(prefix):
            target = target[len(prefix):]
    return pathlib.Path(target)


def same_path(a, b):
    """Whether two paths name the same file or folder. Windows spells one
    folder several ways, long, short (RUNNER~1) or in another case, so it is
    asked of the system, and only when that cannot be done compared as text."""
    if a is None or b is None:
        return False
    try:
        return os.path.samefile(a, b)
    except OSError:
        return os.path.normcase(os.path.abspath(a)) == os.path.normcase(os.path.abspath(b))


def link_or_copy(src, dst):
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def try_lock(path):
    """An exclusive lock on a file, with this pid written in it; None when
    somebody else holds it. The kernel lets go of the lock however its holder
    ends, so unlike a pid file it can never be stale (mcp/bridge.py,
    only_one_bridge, is the same device)."""
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    handle = open(path, "a+")
    try:
        if fcntl:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        else:
            os.lseek(handle.fileno(), LOCK_BYTE, os.SEEK_SET)
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
    except OSError:
        handle.close()
        return None
    handle.seek(0)
    handle.truncate()
    handle.write(f"{os.getpid()}\n")
    handle.flush()
    return handle


def tail_lines(path, n, pattern=None, width=160):
    try:
        lines = pathlib.Path(path).read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []
    if pattern:
        rx = re.compile(pattern, re.I)
        lines = [l for l in lines if rx.search(l)]
    return [l[:width] for l in lines[-n:]]


def log_has(path, needle):
    try:
        return needle in pathlib.Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False


class LogWatch:
    """A growing log, read from where the last look left off, for the lines
    being waited for. Reading it whole on every look was the old way, and a
    client log with a big pack is megabytes, looked at every 2 s for as long
    as the game runs. A log that shrank was started over: it is read again
    from the top, and what was seen in the old one is forgotten."""

    def __init__(self, path, *needles):
        self.path = pathlib.Path(path)
        self.needles = needles
        self.pos = 0
        self.carry = ""          # the end of the last read, for a needle cut in two
        self.found = set()

    def saw(self, needle):
        try:
            size = self.path.stat().st_size
        except OSError:
            return needle in self.found
        if size < self.pos:
            self.pos, self.carry, self.found = 0, "", set()
        if size > self.pos:
            with open(self.path, "rb") as f:
                f.seek(self.pos)
                data = f.read(size - self.pos)
            self.pos += len(data)
            text = self.carry + data.decode("utf-8", "replace")
            self.found.update(n for n in self.needles if n in text)
            keep = max(len(n) for n in self.needles) - 1
            self.carry = text[-keep:] if keep > 0 else ""
        return needle in self.found

"""Processes and ports, with primitives that exist on every platform Python
runs on: sockets instead of `ss`, pid files instead of `pgrep`, a process of
its own instead of `setsid`/`nohup`."""
import os
import pathlib
import signal
import socket
import subprocess
import sys
import time

from .events import wait_for

WINDOWS = os.name == "nt"
# The command everybody types, and the one the launcher runs itself with for
# its internal jobs (the keeper, the middleman of spawn_free).
ENTRY = pathlib.Path(__file__).resolve().parent / "masurium.py"


def run_quiet(args, timeout=20):
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout + r.stderr).strip()
    except (OSError, subprocess.TimeoutExpired) as e:
        return None, str(e)


def port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", int(port))) == 0


def pid_alive(pid):
    if not pid:
        return False
    if WINDOWS:
        # os.kill(pid, 0) is NOT a probe on Windows: it terminates. Ask the
        # kernel for the exit code instead; 259 is STILL_ACTIVE.
        import ctypes
        from ctypes import wintypes
        k32 = ctypes.windll.kernel32
        handle = k32.OpenProcess(0x1000, False, pid)   # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            return False
        code = wintypes.DWORD()
        ok = k32.GetExitCodeProcess(handle, ctypes.byref(code))
        k32.CloseHandle(handle)
        return bool(ok) and code.value == 259
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def argv_of(pid):
    """The command line of a live process, as a list; None when there is no
    such process or it cannot be read. Linux reads /proc; elsewhere `ps`, and
    on Windows PowerShell (wmic is gone from recent Windows)."""
    if not pid:
        return None
    proc = pathlib.Path("/proc") / str(int(pid)) / "cmdline"
    if proc.parent.parent.is_dir():
        try:
            raw = proc.read_bytes()
        except OSError:
            return None
        # A zombie has an empty command line: it is not a process any more.
        return [a.decode("utf-8", "replace") for a in raw.split(b"\0") if a] or None
    if WINDOWS:
        code, out = run_quiet(["powershell", "-NoProfile", "-Command",
                               f"(Get-CimInstance Win32_Process -Filter 'ProcessId={int(pid)}').CommandLine"])
    else:
        code, out = run_quiet(["ps", "-o", "args=", "-p", str(int(pid))])
    return out.split() if code == 0 and out else None


def is_ours(pid, *marks):
    """pid is alive AND is the process we take it for: its command line
    carries every mark, as a whole argument or as the end of a path.

    A pid file outlives a process killed without warning (the OOM killer, a
    reboot) and the kernel hands its number to somebody else. Alive is not
    enough, then: trusting a stale pid file means saying "already running"
    about a stranger, and sending that stranger the SIGTERM meant for a bot."""
    argv = argv_of(pid)
    if not argv:
        return False
    args = [a.lower().replace("\\", "/") for a in argv]
    return all(any(a == k or a.endswith("/" + k) for a in args)
               for k in (m.lower() for m in marks))


def terminate(pid, grace=10):
    """Ask nicely, wait, then insist. Returns once the process is gone."""
    if not pid_alive(pid):
        return
    try:
        os.kill(pid, signal.SIGTERM)
    except OSError:
        return
    for _ in range(int(grace * 10)):
        if not pid_alive(pid):
            return
        time.sleep(0.1)
    try:
        os.kill(pid, getattr(signal, "SIGKILL", signal.SIGTERM))
    except OSError:
        pass
    time.sleep(0.5)


def own_group():
    """Popen keywords that put the child in a process group of its own, so
    the whole tree under it can be signalled at once (see kill_tree)."""
    if WINDOWS:
        return {"creationflags": subprocess.DETACHED_PROCESS
                | subprocess.CREATE_NEW_PROCESS_GROUP}
    return {"start_new_session": True}


def detached(args, log_path, cwd=None, env=None):
    """A process that outlives this one: its own session, its stdio on a log
    file and not on ours. Whoever started it from an SSH session that then
    dropped will be glad of it."""
    log = open(log_path, "ab")
    kw = dict(stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
              cwd=str(cwd) if cwd else None, env=env, **own_group())
    try:
        return subprocess.Popen(args, **kw)
    finally:
        log.close()


def spawn_free(args, log_path, cwd, env=None):
    """detached(), through a middleman that exits at once. The process then
    belongs to nobody: a `start` still polling the server never holds a dead
    keeper as a zombie, so `stop`, run from elsewhere, sees it gone the moment
    it is. (Windows has no zombies; the middleman costs nothing there.)"""
    middle = detached([sys.executable, str(ENTRY), "spawn",
                       "--log", str(log_path), "--cwd", str(cwd), "--"] + list(args),
                      log_path, cwd=cwd, env=env)
    middle.wait(timeout=15)


def kill_tree(pid, hard=False):
    """The process AND its children. HeadlessMC is a launcher: `launch` starts
    the game as a child java, and terminating the launcher alone leaves that
    child alive, holding the port, with nobody at its stdin. It happened.
    The launcher is started in its own group (own_group) precisely so the
    group is the tree."""
    if not pid:
        return
    if WINDOWS:
        cmd = ["taskkill", "/T", "/PID", str(pid)]
        if hard:
            cmd.insert(1, "/F")
        subprocess.run(cmd, capture_output=True)
        return
    sig = signal.SIGKILL if hard else signal.SIGTERM
    # Its group id is its own pid (own_group), and a group outlives its
    # leader: with the launcher already gone and the game still there, this
    # still reaches the game.
    try:
        os.killpg(pid, sig)
    except OSError:
        try:
            os.kill(pid, sig)
        except OSError:
            pass


def game_pids(port):
    """Every java carrying -Dmasurium.bot.port=<port> on its command line:
    the game itself, whoever started it. The last resort of `stop` and the
    truth for `status` when the keeper is gone. Linux reads /proc; elsewhere
    `ps` (POSIX) or PowerShell (Windows) say the same, more slowly."""
    needle = f"-Dmasurium.bot.port={int(port)}"
    found = []
    proc = pathlib.Path("/proc")
    if proc.is_dir():
        for d in proc.iterdir():
            if not d.name.isdigit():
                continue
            try:
                cmd = (d / "cmdline").read_bytes().replace(b"\0", b" ").decode("utf-8", "replace")
            except OSError:
                continue
            if needle in cmd and "java" in cmd:
                found.append(int(d.name))
        return found
    if WINDOWS:
        code, out = run_quiet(["powershell", "-NoProfile", "-Command",
                               "Get-CimInstance Win32_Process -Filter \"Name like 'java%'\" | "
                               "ForEach-Object { \"$($_.ProcessId) $($_.CommandLine)\" }"])
    else:
        code, out = run_quiet(["ps", "-eo", "pid=,args="])
    for line in (out or "").splitlines():
        parts = line.strip().split(None, 1)
        if len(parts) == 2 and parts[0].isdigit() and needle in parts[1] and "java" in parts[1]:
            found.append(int(parts[0]))
    return found


def signal_game(port, hard=False):
    for pid in game_pids(port):
        try:
            os.kill(pid, getattr(signal, "SIGKILL", signal.SIGTERM) if hard else signal.SIGTERM)
        except OSError:
            pass


def stop_game(port, launcher_pid=None):
    """Everything that is the client on this port: the launcher's group if we
    know it, and whatever java carries the port, found by name. Waits for the
    port to close, which is the one sign that the game is gone.

    On Windows the asking is only a formality: taskkill without /F asks a
    window to close, and a headless game has none, so it would stay, and a
    game still loading has no port open to say so. There the launcher's own
    process must be gone too, and the insisting comes after a few seconds,
    not twenty-five. (On Linux a stopped child is a zombie until its parent
    reaps it, alive to a probe: the port is the sign there.)"""
    def gone():
        return not port_in_use(port) and not (WINDOWS and launcher_pid and pid_alive(launcher_pid))
    kill_tree(launcher_pid)
    signal_game(port)
    if wait_for(gone, 5 if WINDOWS else 25, every=0.5):
        return True
    kill_tree(launcher_pid, hard=True)
    signal_game(port, hard=True)
    return wait_for(gone, 10, every=0.5)

"""The keeper: the process that holds a game's console.

HeadlessMC reads commands (`launch`, `connect`, `msg`) from its stdin, and
somebody has to hold that stdin open for as long as the game runs. That
somebody is the keeper: a small process per bot, started by `start`, that owns
the java process and listens on a localhost socket. The launcher and the
bridge send it lines; it writes them to the game.

Protocol, one line per request, one line back. run/keeper.port holds
"<port> <token>", readable only by this user, and every request starts with
the token: a localhost port is open to every user of the machine, and what
reaches HeadlessMC's console can launch a JVM with any arguments. A request
with the wrong token gets "denied". After the token, anything is written to
the game's stdin as it came ("connect 1.2.3.4", "msg hello"), except lines
starting with "@", which are for the keeper itself:

  @ping   ->  "ok <client pid>"
  @stop   ->  "stopping"      (the game is terminated and the keeper exits)
"""
import hmac
import os
import re
import secrets
import signal
import socket
import subprocess
import time

from . import settings
from .files import LogWatch, read_pid, unlink_quietly, write_private
from .processes import WINDOWS, game_pids, is_ours, own_group, port_in_use, stop_game

HMC_READY = "HMC-Specifics initialized"
KEEPER_FAILED = "keeper failed"
GAME_OVER = "Minecraft exited with code"
KEEPER_ENDED = "game exited"


def launch_line(inst, server):
    """What HeadlessMC is told. Two things that cost a night when nobody knew
    them: NO -commands (that flag puts HeadlessMC's runtime inside the game,
    and then TWO consoles read the same stdin, stealing each other's lines),
    and -lwjgl, which removes rendering: no screen, no GPU.

    -Dmarionette.name is what makes this client a bot at all.
    -Dmarionette.bot.port: without it every bot would fight over 8478 and
    the second one would have no hands. What the body says on its own (a
    creeper next to whoever it escorts) does not go through the brain, so the
    mod gets the same language and gender the bridge uses; only clean values
    reach the JVM."""
    ws = inst.ws
    offline = " -offline" if settings.get(inst, "account") == "offline" else ""
    # A heap edited by hand is not trusted onto the JVM's command line:
    # anything but a size falls back to the default (doctor says why).
    heap = settings.get(inst, "heap")
    if settings.problem(inst, "heap", heap):
        heap = ws.heap()
    jvm = [f"-Xmx{heap}", f"-Dmarionette.name={inst.name}",
           "-Dmarionette.headless=true", f"-Dmarionette.bot.port={inst.port}"]
    language = re.sub(r"[^A-Za-z]", "", settings.get(inst, "language"))[:8]
    gender = re.sub(r"[^A-Za-z]", "", settings.get(inst, "gender"))[:1]
    if language:
        jvm.append(f"-Dmarionette.language={language}")
    if gender:
        jvm.append(f"-Dmarionette.gender={gender}")
    return f"launch {ws.version_for(server)} -lwjgl{offline} -paulscode --jvm \"{' '.join(jvm)}\""


def clear_run_files(inst):
    unlink_quietly(inst.keeper_port_f, inst.client_pid_f, inst.keeper_pid_f)


def keeper_main(inst, server):
    """The keeper's whole life. Runs in a process of its own (marionette.py
    keeper), with its stdout on run/keeper.log."""
    inst.run.mkdir(parents=True, exist_ok=True)

    def note(m):
        print(time.strftime("%H:%M:%S"), m, flush=True)

    # Its pid FIRST: from here on `start` can tell a keeper that is loading
    # the game from one that never got going.
    inst.keeper_pid_f.write_text(f"{os.getpid()}\n")
    if not WINDOWS:
        # A plain SIGTERM would end Python on the spot, skipping the finally
        # below, and leave the game running with nobody at its stdin. As an
        # exception it goes through the finally, which stops the game.
        def on_term(signum, frame):
            raise SystemExit(0)
        signal.signal(signal.SIGTERM, on_term)

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.bind(("127.0.0.1", 0))
    listener.listen(8)
    listener.settimeout(0.5)
    control_port = listener.getsockname()[1]
    token = secrets.token_hex(16)

    client_log = open(inst.client_log, "ab")
    # In a group of its own: HeadlessMC starts the game as a child java, and
    # the group is how both are stopped together (kill_tree).
    try:
        game = subprocess.Popen(
            inst.ws.java_command() + ["-jar", "headlessmc-launcher.jar"],
            cwd=str(inst.hmc), stdin=subprocess.PIPE, stdout=client_log,
            stderr=subprocess.STDOUT, env=inst.ws.child_env(), **own_group())
    except OSError as e:
        # No java, or not that one. Said in one line `start` is waiting for,
        # instead of a traceback it is not.
        note(f"{KEEPER_FAILED}: could not run {' '.join(inst.ws.java_command())}: {e}")
        client_log.close()
        listener.close()
        clear_run_files(inst)
        return 1
    inst.client_pid_f.write_text(f"{game.pid}\n")
    # The port file goes LAST: whoever sees it may talk to a keeper that is whole.
    write_private(inst.keeper_port_f, f"{control_port} {token}\n")
    note(f"keeper of {inst.key} ({inst.name}): game pid {game.pid}, control port {control_port}")

    def to_game(line):
        game.stdin.write((line + "\n").encode("utf-8"))
        game.stdin.flush()

    line = launch_line(inst, server)
    note(line)
    to_game(line)

    # HeadlessMC is a console around the game: when the game crashes at
    # startup it can stay at its prompt, alive, with nothing behind it. The
    # line it prints then is the sign that the game is gone, not the process.
    watch = LogWatch(inst.client_log, GAME_OVER)
    stopping = False
    last_look = 0.0
    try:
        while game.poll() is None and not stopping:
            if time.monotonic() - last_look > 2:
                last_look = time.monotonic()
                if watch.saw(GAME_OVER):
                    note("the game exited under the launcher; leaving too")
                    stopping = True
                    break
            try:
                conn, _ = listener.accept()
            except socket.timeout:
                continue
            with conn:
                conn.settimeout(5)
                try:
                    data = b""
                    while not data.endswith(b"\n"):
                        chunk = conn.recv(4096)
                        if not chunk:
                            break
                        data += chunk
                except OSError:
                    continue
                given, _, request = data.decode("utf-8", "replace").strip().partition(" ")
                if not hmac.compare_digest(given.encode(), token.encode()):
                    reply = "denied"
                elif not request:
                    continue
                elif request == "@ping":
                    reply = f"ok {game.pid}"
                elif request == "@stop":
                    reply = "stopping"
                    stopping = True
                elif request.startswith("@"):
                    reply = "unknown"
                else:
                    try:
                        to_game(request)
                        reply = "sent"
                    except OSError as e:
                        reply = f"error {e}"
                try:
                    conn.sendall((reply + "\n").encode("utf-8"))
                except OSError:
                    pass
    finally:
        if not WINDOWS:
            # A second SIGTERM must not cut this cleanup short.
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
        # The launcher AND the game under it. A JVM asked to stop runs the
        # game's shutdown hooks first (it saves), which takes a while with a
        # world loaded; stop_game has the patience for that.
        if stopping or game.poll() is None or port_in_use(inst.port):
            note("terminating the game")
            if not stop_game(inst.port, game.pid):
                note(f"something still holds port {inst.port}: {game_pids(inst.port)}")
        try:
            game.wait(timeout=5)      # reap the launcher; a pid probe would see a zombie
        except subprocess.TimeoutExpired:
            pass
        note(f"{KEEPER_ENDED} with {game.poll()}")
        client_log.close()
        listener.close()
        clear_run_files(inst)
    return 0


def keeper_ask(inst, line, timeout=5):
    """One line to this instance's keeper; its one-line answer. None when there is
    no keeper to talk to."""
    try:
        fields = inst.keeper_port_f.read_text().split()
        port = int(fields[0])
    except (OSError, ValueError, IndexError):
        return None
    # A keeper older than the token writes the port alone, and takes the
    # line bare: it goes on working until its instance is restarted.
    if len(fields) > 1:
        line = f"{fields[1]} {line}"
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout) as s:
            s.sendall((line + "\n").encode("utf-8"))
            s.settimeout(timeout)
            data = b""
            while not data.endswith(b"\n"):
                chunk = s.recv(256)
                if not chunk:
                    break
                data += chunk
            return data.decode("utf-8", "replace").strip()
    except OSError:
        return None


def keeper_alive(inst):
    """The keeper answers, and the game it holds is the pid it claims."""
    answer = keeper_ask(inst, "@ping")
    return bool(answer and answer.startswith("ok "))


def keeper_pid(inst):
    """The pid in keeper.pid, only while it still is this instance's keeper."""
    pid = read_pid(inst.keeper_pid_f)
    return pid if is_ours(pid, "marionette.py", "keeper", inst.key) else None


def launcher_pid(inst):
    """The pid in client.pid, only while it still is a HeadlessMC launcher."""
    pid = read_pid(inst.client_pid_f)
    return pid if is_ours(pid, "headlessmc-launcher.jar") else None

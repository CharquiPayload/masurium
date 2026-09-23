#!/usr/bin/env python3
"""The Python test suites the way GitHub's machines run them (.github/workflows),
on Linux and on Windows.

Each suite runs on its own, its whole output printed, and a suite that fails
also leaves an annotation with what went wrong: its FAIL lines and its last
lines, where a traceback ends. GitHub shows annotations to anyone; the logs it
shows only to whoever is logged in, and a failure nobody can read is not
tested. An annotation holds 4096 characters, so a long one goes in several: the
FAIL lines as errors, the last lines as warnings (each kind has a quota of its
own per step).

Run:  python tools/ci.py [mcp] [launcher] [window]    (all three by default)
"""
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SUITES = {
    "mcp": [sys.executable, "mcp/tests.py"],
    "launcher": [sys.executable, "launcher/tests.py"],
    # Qt drawn offscreen: a CI machine has no screen.
    "window": [sys.executable, "-m", "launcher.gui.tests"],
}
# Long enough for the slowest suite on a slow machine, short enough that a
# suite waiting forever on a process that never came fails instead of eating
# the job's hour.
TIMEOUT = 20 * 60
# The last lines of a failed suite, where a traceback ends.
TAIL = 60
# What GitHub keeps of one annotation's message.
ROOM = 4000
# How many annotations of each kind a suite may leave: a step keeps ten of each.
MOST = 3


def chunks(lines):
    """The lines in pieces that fit an annotation each, cut between lines."""
    piece = []
    for line in lines:
        line = line[:ROOM]
        if piece and len("\n".join(piece + [line])) > ROOM:
            yield piece
            piece = []
        piece.append(line)
    if piece:
        yield piece


def annotate(kind, title, lines):
    """Annotations of one kind (error, warning). A message is one line to the
    runner, so its line breaks, and the % that escapes them, are escaped."""
    pieces = list(chunks(lines))[:MOST]
    for n, piece in enumerate(pieces, 1):
        text = "\n".join(piece).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        part = f" ({n}/{len(pieces)})" if len(pieces) > 1 else ""
        print(f"::{kind} title={title}{part}::{text}", flush=True)


def run(name):
    env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1")
    if name == "window":
        env["QT_QPA_PLATFORM"] = "offscreen"
    print(f"=== {name} ===", flush=True)
    try:
        done = subprocess.run(SUITES[name], cwd=ROOT, env=env, stdin=subprocess.DEVNULL,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=TIMEOUT)
        out, code = done.stdout.decode("utf-8", "replace"), done.returncode
    except subprocess.TimeoutExpired as e:
        out = (e.stdout or b"").decode("utf-8", "replace") + f"\n(still running after {TIMEOUT} s: stopped)"
        code = None
    print(out, flush=True)
    if code == 0:
        return True
    lines = out.splitlines()
    failed = [l for i, l in enumerate(lines) if l.startswith("  FAIL ")
              or (i and lines[i - 1].startswith("  FAIL ") and l.startswith("         "))]
    where = f"{name} suite on {sys.platform}"
    annotate("error", f"{where}: exit {code}", failed or ["no FAIL line: see the last lines"])
    annotate("warning", f"{where}: its last lines", lines[-TAIL:])
    return False


def main(names):
    bad = [n for n in (names or list(SUITES)) if not run(n)]
    print(f"\n{'red: ' + ', '.join(bad) if bad else 'all green'}", flush=True)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

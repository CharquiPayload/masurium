#!/usr/bin/env python3
"""The Python test suites the way GitHub's machines run them (.github/workflows),
on Linux and on Windows.

Each suite runs on its own, its whole output printed, and a suite that fails
also leaves an annotation with what went wrong: its FAIL lines and its last
lines, where a traceback ends. GitHub shows annotations to anyone; the logs it
shows only to whoever is logged in, and a failure nobody can read is not
tested.

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
# An annotation holds 64 KB; the tail of a failure fits in far less.
TAIL = 80


def annotate(title, lines):
    """One ::error:: annotation. Its message is one line to the runner, so the
    line breaks, and the % that escapes them, are escaped."""
    text = "\n".join(lines)[-60000:]
    text = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::error title={title}::{text}", flush=True)


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
    annotate(f"{name} suite on {sys.platform}: exit {code}",
             failed[:200] + ["", "--- last lines ---"] + lines[-TAIL:])
    return False


def main(names):
    bad = [n for n in (names or list(SUITES)) if not run(n)]
    print(f"\n{'red: ' + ', '.join(bad) if bad else 'all green'}", flush=True)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

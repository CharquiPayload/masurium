#!/bin/bash
# Every test of the project, in one command.
#
#   ./test.sh
#
# None of them needs Minecraft running, nor the server, nor the network. That is
# the condition: if testing meant starting a world, it would never be done.
#
# Two traps that already bit here, both of the same family: **calling green
# something that was not checked**.
#
#   1. `cmd | grep ...` returns GREP's exit code. A grep without matches gives 1
#      and paints a green suite red. The real exit code is saved BEFORE.
#   2. Gradle serves `:test` FROM-CACHE and runs and prints nothing. It said
#      "BUILD SUCCESSFUL" without a single test run. Hence `--rerun` and the
#      count below: **if not a single result is seen, it is red**.
set -uo pipefail
cd "$(dirname "$(readlink -f "$0")")"

failures=0

echo "═══ MCP layer (python) ═══"
python3 mcp/tests.py || failures=$((failures + 1))

echo
echo "═══ the launcher (python): folders, names, ports, mods, the keeper ═══"
python3 launcher/tests.py || failures=$((failures + 1))

echo
echo "═══ the window (python, Qt drawn offscreen) ═══"
# It needs PySide6, which the rest does not: a python that has it is used, the
# one MASURIUM_GUI_PYTHON names, and without one the suite is skipped, said.
gui_python="${MASURIUM_GUI_PYTHON:-python3}"
if "$gui_python" -c "import PySide6" 2>/dev/null; then
  QT_QPA_PLATFORM=offscreen "$gui_python" -m launcher.gui.tests 2>&1 | grep -vE 'propagateSizeHints'
  [ "${PIPESTATUS[0]}" -eq 0 ] || failures=$((failures + 1))
else
  echo "  skipped: $gui_python has no PySide6 (MASURIUM_GUI_PYTHON can name one that has it)"
fi

echo
echo "═══ the mod (java): one jar, server and bot ═══"
# `build` and not just `test`: the jar has to come out, and BotSideTest reads the
# COMPILED classes to check that nothing the server loads names a client class.
output=$(cd mod && ./gradlew build --rerun-tasks --console=plain 2>&1)
code=$?
echo "$output" | grep -E 'PASSED|FAILED|SKIPPED' | sed 's/^/  /'
seen=$(echo "$output" | grep -cE 'PASSED|FAILED')

if [ "$code" -ne 0 ]; then
  failures=$((failures + 1))
  echo "$output" | grep -B2 -A6 -E 'FAILED|error:' | head -25
elif [ "$seen" -eq 0 ]; then
  # Green without evidence is not green.
  failures=$((failures + 1))
  echo "  NOT A SINGLE TEST RAN: Gradle said yes, but there are no results."
  echo "$output" | tail -8 | sed 's/^/  /'
else
  echo "  $seen java tests"
  jar=$(ls mod/build/libs/masurium-*.jar 2>/dev/null | grep -v sources | head -1)
  if [ -z "$jar" ]; then
    failures=$((failures + 1))
    echo "  NO JAR CAME OUT of mod/build/libs"
  else
    echo "  $(basename "$jar")  ($(stat -c%s "$jar") bytes)"
  fi
fi

echo
if [ "$failures" -eq 0 ]; then
  echo "ALL GREEN"
else
  echo "$failures SUITE(S) RED"
  exit 1
fi

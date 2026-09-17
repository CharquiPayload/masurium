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
echo "═══ server mod (java) ═══"
output=$(cd mod-server && ./gradlew test --rerun --console=plain 2>&1)
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
fi

echo
echo "═══ bot mod (java, compile only) ═══"
output=$(cd mod-bot && ./gradlew build --console=plain 2>&1)
if [ $? -ne 0 ]; then
  failures=$((failures + 1))
  echo "$output" | grep -A4 'error:' | head -25
else
  echo "  builds"
fi

echo
if [ "$failures" -eq 0 ]; then
  echo "ALL GREEN"
else
  echo "$failures SUITE(S) RED"
  exit 1
fi

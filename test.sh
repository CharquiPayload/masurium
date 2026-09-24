#!/bin/bash
# Every test of the Masurium mod, in one command: its JUnit tests and the jar.
# Masurium Launcher's tests are in its own repository.
#
#   ./test.sh
#
# None of them needs Minecraft running, nor the server, nor the network. That is
# the condition: if testing meant starting a world, it would never be done.
#
# A trap that already bit here: **calling green something that was not
# checked**. Gradle serves `:test` FROM-CACHE and runs and prints nothing: it
# said "BUILD SUCCESSFUL" without a single test run. Hence `--rerun-tasks` and
# the count below: **if not a single result is seen, it is red**.
set -uo pipefail
cd "$(dirname "$(readlink -f "$0")")"

failures=0

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

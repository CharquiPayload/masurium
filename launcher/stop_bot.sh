#!/bin/bash
# Stops a bot: the client and its bridge.
#
#   ./stop_bot.sh <name>
#
# Processes are killed by what identifies THAT bot and no other: the client by
# its `-Dmarionette.bot.port`, the bridge by the name on its command line. A
# bare `pkill -f bridge.py` would take down the bridge of every bot, which is
# exactly what must not happen when there are two.
#
# Careful with killing by pattern: if the pattern appears in the command that
# runs this script, the script kills itself. So the pids are collected first
# and our own are skipped before touching anything.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$HERE/common.sh"

require_name "${1:-}"
NAME="$1"
UNDER="$(lowercase "$NAME")"
BASE="$BOTS_DIR/$UNDER"
PORT="$(cat "$BASE/port" 2>/dev/null || echo 8478)"

kill_matching() {
  local pattern="$1" what="$2" pid found=0
  for pid in $(pgrep -fi "$pattern" 2>/dev/null); do
    [ "$pid" = "$$" ] && continue
    [ "$pid" = "$PPID" ] && continue
    kill "$pid" 2>/dev/null && found=$((found + 1))
  done
  if [ "$found" -gt 0 ]; then
    echo "==> $what: $found process(es)"
  else
    echo "==> $what: nothing was running"
  fi
}

echo "==> stopping $NAME (port $PORT)"
kill_matching "marionette.bot.port=$PORT" "client"
kill_matching "bridge.py $NAME" "bridge"
# The `tail -f` feeding the pipe outlives the java and keeps the FIFO open.
kill_matching "tail -f /tmp/${UNDER}_in" "pipe"
sleep 3

remaining="$(pgrep -f "marionette.bot.port=$PORT" 2>/dev/null | grep -vc "^$$\$" || true)"
if [ "${remaining:-0}" -gt 0 ]; then
  echo "==> the client did not leave nicely; insisting"
  pkill -9 -f "marionette.bot.port=$PORT" 2>/dev/null
  sleep 2
fi
rm -f "/tmp/${UNDER}_in"
echo "==> $NAME stopped"

# Its guards leave with it: a guard without a boss has nothing to do. The real
# name (with its capitals) comes from the hmc config, which is what its bridge
# runs with. A RESTART is not a disconnection: restart_bot.sh passes
# NO_GUARDS=1 and the guards stay.
for f in "$BOTS_DIR"/*/escort; do
  [ -n "${NO_GUARDS:-}" ] && break
  [ -f "$f" ] || continue
  boss="$(tr -d '[:space:]' < "$f" | tr 'A-Z' 'a-z')"
  [ "$boss" = "$UNDER" ] || continue
  g="$(basename "$(dirname "$f")")"
  real="$(grep -oP '^hmc.offline.username=\K.*' "$BOTS_DIR/$g/hmc/HeadlessMC/config.properties" 2>/dev/null || echo "$g")"
  echo "==> $real is a guard of $NAME: stopping it too"
  "$HERE/stop_bot.sh" "$real"
done

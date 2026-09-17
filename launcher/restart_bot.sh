#!/bin/bash
# Restarts a WHOLE bot: client and bridge.
#
#   ./restart_bot.sh <name> [server]
#
# It exists because of an easy mistake: after deploying a new mod both processes
# get killed, but only the client gets started again. The bot stays in the
# game, visible in /players, and mute, which from the chat looks exactly like a
# hang. Starting both together is not a convenience, it prevents that failure.
#
# The bridge goes AFTER, and only if the client joined: without a body in the
# game it has nobody to write to, and starting it earlier only fills its log
# with errors.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$HERE/common.sh"

require_name "${1:-}" "[server]"
NAME="$1"
SRV="${2:-}"
UNDER="$(lowercase "$NAME")"
LOG="/tmp/bridge_${UNDER}_out"

NO_GUARDS=1 "$HERE/stop_bot.sh" "$NAME"
echo

if ! "$HERE/start_bot.sh" "$NAME" ${SRV:+"$SRV"}; then
  echo "==> the client did not join; NOT starting the bridge."
  exit 1
fi

echo
echo "==> starting the bridge of $NAME"
cd "$HOME" || exit 1
BOT_NAME="$NAME" setsid nohup python3 "$HERE/../mcp/bridge.py" "$NAME" \
    > "$LOG" 2>&1 < /dev/null &

# The sign that it started is its own "listening" line, not that a process
# exists: the bridge can exist and be dying, and `pgrep` on the command line
# has lied before. The log does not.
for _ in $(seq 1 10); do
  grep -q "listening" "$LOG" 2>/dev/null && break
  sleep 1
done
if grep -q "listening" "$LOG" 2>/dev/null; then
  echo "==> bridge alive: $(head -1 "$LOG")"
  echo "==> log at $LOG"
else
  echo "==> the bridge did NOT start. $NAME is in the game but MUTE:"
  cat "$LOG" 2>/dev/null
  exit 1
fi

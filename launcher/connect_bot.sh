#!/bin/bash
# Puts back on the server a bot whose client is ALIVE at the title screen
# (after log_off, or a failed connect). It starts nothing: if the client is not
# running, this is not what you want; use start_bot.sh.
#
#   ./connect_bot.sh <name>
#
# On purpose it does NOT switch servers: switching servers means switching
# packs, and the pack is only rebuilt by start_bot.sh. This rejoins the same one.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$HERE/common.sh"

require_name "${1:-}"
NAME="$1"
UNDER="$(lowercase "$NAME")"
BASE="$BOTS_DIR/$UNDER"
PIPE="/tmp/${UNDER}_in"
PORT="${PORT:-$(cat "$BASE/port" 2>/dev/null || echo 8478)}"

SRV="$(cat "$BASE/server" 2>/dev/null || echo "")"
[ -n "$SRV" ] || { echo "$NAME has no server noted."; exit 1; }
load_server "$SRV" || exit 1
load_env || exit 1

if ! port_in_use "$PORT"; then
  echo "no live client on port $PORT: use start_bot.sh $NAME"
  exit 1
fi

if is_inside "$NAME"; then
  echo "$NAME is already in. Nothing to do."
  exit 0
fi

ADDRESS="$HOST"
[ "$MC_PORT" = "25565" ] || ADDRESS="$HOST:$MC_PORT"

echo "==> connecting to $SRV ($ADDRESS)"
for attempt in 1 2 3; do
  echo "connect $ADDRESS" > "$PIPE"
  for i in $(seq 1 12); do
    if is_inside "$NAME"; then
      echo "==> $NAME is IN (attempt $attempt, ${i}0s)"
      exit 0
    fi
    sleep 10
  done
  echo "    attempt $attempt failed"
done

echo "==> it did NOT join. If the client is hung: restart_bot.sh $NAME"
exit 1

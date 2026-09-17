#!/bin/bash
# Logs a bot's Minecraft account in, once, through HeadlessMC.
#
#   ./login_bot.sh <name>
#
# Online bots use a real, purchased Minecraft Java account (a Microsoft
# account), like any player. HeadlessMC keeps the login in the bot's own hmc
# folder, so each bot has its own account. This opens HeadlessMC interactively:
# type `login`, follow its instructions, and type `quit` when it is done.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$HERE/common.sh"

require_name "${1:-}"
NAME="$1"
BASE="$BOTS_DIR/$(lowercase "$NAME")"

if [ ! -d "$BASE/hmc" ]; then
  echo "$BASE does not exist. Create it first:  $HERE/create_bot.sh $NAME <server>"
  exit 1
fi
if [ "$(tr -d '[:space:]' < "$BASE/account" 2>/dev/null)" = "offline" ]; then
  echo "$NAME uses an offline account; there is nothing to log in."
  exit 0
fi

echo "==> HeadlessMC for $NAME. Type:  login   (then follow the instructions)"
echo "    and when the account is saved:  quit"
cd "$BASE/hmc" || exit 1
exec java -jar headlessmc-launcher.jar

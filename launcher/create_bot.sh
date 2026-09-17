#!/bin/bash
# Creates a new bot, ready to start.
#
#   ./create_bot.sh <name> <server>
#   ./create_bot.sh                     (lists the servers and exits)
#
# The account defaults to online: a purchased Minecraft Java account, logged in
# once with login_bot.sh. MARIONETTE_ACCOUNT=offline creates an offline account,
# only for private servers you control with online-mode=false.
#
# It copies almost nothing, on purpose. The heavy parts are the assets, the
# libraries and the game versions (about 1 GB), which live once in ~/.minecraft
# and HeadlessMC shares between every bot. Mods are hard links to the server's
# pack. A new bot costs about 2 MB and takes a second.
#
# The only thing that CANNOT be shared is the hmc folder: the name used to join
# the game is fixed in its config.properties, so there is one per bot.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$HERE/common.sh"

NAME="${1:-}"
SRV="${2:-}"

if [ -z "$NAME" ] || [ -z "$SRV" ]; then
  echo "usage: create_bot.sh <name> <server>"
  echo
  echo "servers:"
  list_servers
  exit 1
fi

# Minecraft's rules, not a whim: up to 16 characters, letters, digits and
# underscore. An invalid name does not fail when the bot is created, it fails
# when it JOINS, minutes later, when the error is hard to connect to the cause.
case "$NAME" in
  *[!A-Za-z0-9_]*)
    echo "'$NAME' is not a valid name: only letters, digits and underscore."
    exit 1 ;;
esac
if [ ${#NAME} -gt 16 ]; then
  echo "'$NAME' has ${#NAME} characters; Minecraft allows 16."
  exit 1
fi

UNDER="$(lowercase "$NAME")"
BASE="$BOTS_DIR/$UNDER"

ACCOUNT="${MARIONETTE_ACCOUNT:-online}"
case "$ACCOUNT" in
  online) HMC_OFFLINE=false ;;
  offline) HMC_OFFLINE=true ;;
  *) echo "MARIONETTE_ACCOUNT must be online or offline, not '$ACCOUNT'."; exit 1 ;;
esac

if [ -e "$BASE" ]; then
  echo "$BASE already exists. To make it again, delete it yourself first."
  exit 1
fi

if clash="$(name_clashes "$UNDER")"; then
  echo "'$NAME' clashes with the bot '$clash': one name contains the other."
  echo "the bridge would mix them up in the chat. Choose another name."
  exit 1
fi

load_server "$SRV" || exit 1

if [ ! -d "$COMMON_DIR/mods" ] || [ ! -f "$COMMON_DIR/headlessmc-launcher.jar" ]; then
  echo "missing $COMMON_DIR: it holds the launcher and the Marionette mods."
  exit 1
fi

PORT="$(free_port "$UNDER")" || {
  echo "no free ports from $FIRST_PORT on. Something odd is going on."
  exit 1
}

echo "==> creating $NAME  (server $SRV, port $PORT)"
mkdir -p "$BASE/gamedir/mods" "$BASE/hmc/HeadlessMC" || exit 1

ln -f "$COMMON_DIR/headlessmc-launcher.jar" "$BASE/hmc/headlessmc-launcher.jar" \
  2>/dev/null || cp "$COMMON_DIR/headlessmc-launcher.jar" "$BASE/hmc/" || exit 1

cat > "$BASE/hmc/HeadlessMC/config.properties" <<CONF
hmc.jline.enabled=false
hmc.offline=$HMC_OFFLINE
hmc.offline.username=$NAME
hmc.invert.command.modifiers=false
hmc.gamedir=$BASE/gamedir
CONF

echo "$PORT" > "$BASE/port"
echo "$SRV" > "$BASE/server"
echo "$ACCOUNT" > "$BASE/account"

# The language the bot speaks in the chat: en or es.
echo "en" > "$BASE/language"

# Its character, in its own file from minute one. A template instead of an
# empty file, because a bot without a written character sounds like a manual.
# Editing this is all it takes for this bot not to sound like the others.
cat > "$BASE/personality.txt" <<TEXT
You are $NAME. Write here who you are: how you talk, what you care about, who
you trust, what makes you laugh. In second person and in a few lines: this
goes at the start of the prompt, before the body's instructions.

For now: you talk plainly, correct and direct, without flourishes.
TEXT

# The owner: whose delicate orders the bot accepts, and who can shut it down,
# restart it and manage its lists with /marionette bot on any server. It is
# born with the server owner, if the environment names one; to give the bot to
# someone else, write their EXACT player name here and restart the bridge.
OWNER=""
if [ -f "${MARIONETTE_ENV:-$HOME/.marionette/server.env}" ]; then
  OWNER="$( . "${MARIONETTE_ENV:-$HOME/.marionette/server.env}"; printf '%s' "${MARIONETTE_OWNER:-}")"
fi
[ -n "$OWNER" ] && echo "$OWNER" > "$BASE/owner"

if ! n="$(sync_mods "$BASE/gamedir" "$PACK")"; then
  echo "could not link the mods of the $SRV pack."
  exit 1
fi

# Careful measuring this with a bare `du -sh`: it would count the jars as if
# they belonged to this bot, when they are hard links that take no new space.
echo "==> $n mods linked from $PACK/mods and $COMMON_DIR/mods"
printf '==> this bot alone: %s (the jars are hard links, not copies)\n' \
       "$(du -sh --exclude='*.jar' "$BASE" | cut -f1)"
echo
echo "files you may want to edit in $BASE: personality.txt, language (en/es),"
echo "owner (a player name), model, escort (makes it a guard of that bot)."
echo
if [ "$ACCOUNT" = "online" ]; then
  echo "log in its Minecraft account once:  $HERE/login_bot.sh $NAME"
  echo "then start it with:                 $HERE/start_bot.sh $NAME"
else
  echo "offline account: only for private servers with online-mode=false."
  echo "start it with:  $HERE/start_bot.sh $NAME"
fi

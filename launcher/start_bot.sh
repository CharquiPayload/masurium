#!/bin/bash
# Starts a headless bot and puts it on the server.
#
#   ./start_bot.sh <name> [server]
#
# The server is a NAME from the registry (`servers/`), not an IP: the address,
# the game version and above all the mod pack come from there. Without that
# argument the bot uses the last server it joined, stored in bots/<name>/server.
# If another one is asked for, the mods are rebuilt before starting: joining
# with the wrong pack ends in a disconnection that does not say why.
#
# Two things that cost a night when nobody knows them:
#
#   NO -commands, on purpose. That flag puts HeadlessMC's runtime inside the
#               game, and then TWO consoles read the same stdin, stealing each
#               other's lines: the runtime (which does not know `connect`) and
#               hmc-specifics (which does). With a single reader, `connect`
#               always reaches the one that understands it.
#   -lwjgl      removes rendering. No screen, no GPU.
#
# And the house rule: to know whether the bot joined, do NOT read the log, ask
# the server. The log says what the client believes; /players says what there is.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$HERE/common.sh"

require_name "${1:-}" "[server]"
NAME="$1"
UNDER="$(lowercase "$NAME")"
REQUESTED_VERSION="${VERSION:-}"   # the environment wins over the server's
HEAP="${HEAP:-3g}"

BASE="$BOTS_DIR/$UNDER"
PIPE="/tmp/${UNDER}_in"
OUTPUT="/tmp/${UNDER}_out"

if [ ! -d "$BASE" ]; then
  echo "$BASE does not exist. Create it first:  $HERE/create_bot.sh $NAME <server>"
  exit 1
fi

# online: a logged-in Minecraft account (login_bot.sh). offline: an offline
# account, for private servers with online-mode=false.
ACCOUNT="$(tr -d '[:space:]' < "$BASE/account" 2>/dev/null || true)"
ACCOUNT="${ACCOUNT:-online}"
OFFLINE_FLAG=""
[ "$ACCOUNT" = "offline" ] && OFFLINE_FLAG=" -offline"

# The port belongs to each bot, not to the project. It lives in the bot's own
# folder so the launcher and the bridge read it from the same place.
PORT="${PORT:-$(cat "$BASE/port" 2>/dev/null || echo 8478)}"

SRV="${2:-$(cat "$BASE/server" 2>/dev/null || echo "")}"
if [ -z "$SRV" ]; then
  echo "$NAME has no server noted. Tell me which one it joins:"
  list_servers
  exit 1
fi
load_server "$SRV" || exit 1
VERSION="${REQUESTED_VERSION:-$VERSION}"
load_env || exit 1

if clash="$(name_clashes "$UNDER")"; then
  echo "the name '$NAME' clashes with the bot '$clash': one contains the other."
  echo "the bridge would mix them up in the chat. Choose another name."
  exit 1
fi

# The mod has no way of knowing WHICH server it joined (they may all share an
# address and port): the launcher tells it. Per-server memories (places, chests,
# orders...) are keyed on this. ALWAYS written, not only when switching packs.
mkdir -p "$BASE/gamedir/config"
printf '%s\n' "$SRV" > "$BASE/gamedir/config/marionette-server.txt"
# Who the bot escorts by default (a guard of another bot): bots/<bot>/escort.
# Without the file it is nobody's guard and the mod looks for no one.
if [ -s "$BASE/escort" ]; then
  tr -d '[:space:]' < "$BASE/escort" > "$BASE/gamedir/config/marionette-escort.txt"
else
  rm -f "$BASE/gamedir/config/marionette-escort.txt"
fi

# ALWAYS, not only when switching servers. Switching packs is not the only way
# for the pack and the gamedir to drift apart: add a mod to a server's pack and
# the bots keep joining with the old one. The server rejects them with
# "Incompatible client! Please use NeoForge ...", which looks nothing like the
# real cause. Rebuilding costs a second; not doing it costs a mysterious
# disconnection.
echo "==> preparing the mods of $SRV"
if ! n="$(sync_mods "$BASE/gamedir" "$PACK")"; then
  echo "could not link the mods of the $SRV pack."
  exit 1
fi
echo "$SRV" > "$BASE/server"
echo "    $n mods"

if is_inside "$NAME"; then
  echo "$NAME is already in. Nothing to do."
  exit 0
fi

# If the port is taken there is a live client: either this same bot loaded but
# not connected, or another bot that took it. Starting anyway would launch a
# second 3 GB java, which is how the OOM killer gets invited.
if port_in_use "$PORT"; then
  echo "port $PORT is already taken. There is a live client."
  pgrep -af "marionette.bot.port=$PORT" | cut -c1-120
  echo "kill it before retrying, or give this bot another port."
  exit 1
fi

# What the body says on its own (a creeper next to whoever it escorts, being
# cornered) does not go through the brain, so the mod needs the same language
# and gender the bridge uses. Only clean values reach the JVM.
LANGUAGE="$(tr -dc '[:alpha:]' < "$BASE/language" 2>/dev/null | cut -c1-8)"
GENDER="$(tr -dc '[:alpha:]' < "$BASE/gender" 2>/dev/null | cut -c1-1)"
SPEECH_FLAGS=""
[ -n "$LANGUAGE" ] && SPEECH_FLAGS=" -Dmarionette.language=$LANGUAGE"
[ -n "$GENDER" ] && SPEECH_FLAGS="$SPEECH_FLAGS -Dmarionette.gender=$GENDER"

echo "==> starting $NAME (port $PORT)"
rm -f "$PIPE" "$OUTPUT"
mkfifo "$PIPE"
cd "$BASE/hmc" || exit 1
nohup sh -c "tail -f $PIPE | java -jar headlessmc-launcher.jar" > "$OUTPUT" 2>&1 &
sleep 4
# -Dmarionette.bot.port: the mod reads it when it opens its HTTP server. Without
# it every bot would fight over 8478 and the second one would have no hands.
echo "launch $VERSION -lwjgl$OFFLINE_FLAG -paulscode" \
     "--jvm \"-Xmx$HEAP -Dmarionette.bot.port=$PORT$SPEECH_FLAGS\"" > "$PIPE"

echo "==> loading the game"
# The right signal is NOT that the process exists: it is that the mod has
# registered its commands. Sending `connect` before that talks to nobody.
for _ in $(seq 1 60); do
  grep -q "HMC-Specifics initialized" "$OUTPUT" 2>/dev/null && break
  sleep 5
done
if ! grep -q "HMC-Specifics initialized" "$OUTPUT" 2>/dev/null; then
  echo "==> the hmc-specifics mod did not initialize. Without it there is no connect."
  [ "$ACCOUNT" = "online" ] && echo "    (online account: if HeadlessMC asked for a login, run login_bot.sh $NAME)"
  tail -5 "$OUTPUT" | cut -c1-160
  exit 1
fi

# The bot can join the server without hands: if the mod could not open its
# port, `connect` works anyway and the failure only shows much later, when an
# order does nothing. Better to know here.
for _ in $(seq 1 10); do
  port_in_use "$PORT" && break
  sleep 2
done
if ! port_in_use "$PORT"; then
  echo "==> the bot mod did not open port $PORT. It would join without hands."
  grep -iE "marionette_bot|address already in use|BindException" "$OUTPUT" \
    | tail -5 | cut -c1-160
  exit 1
fi

# 25565 goes unsaid because it is the default; any other one is spelled out.
ADDRESS="$HOST"
[ "$MC_PORT" = "25565" ] || ADDRESS="$HOST:$MC_PORT"

echo "==> connecting to $SRV ($ADDRESS)"
for attempt in 1 2 3 4 5; do
  echo "connect $ADDRESS" > "$PIPE"
  for i in $(seq 1 18); do
    if is_inside "$NAME"; then
      echo "==> $NAME is IN (attempt $attempt, ${i}0s)"
      curl -s -H "X-Marionette-Token: $MARIONETTE_TOKEN" \
           "http://$MARIONETTE_HOST:$MARIONETTE_PORT/players"; echo
      exit 0
    fi
    sleep 10
  done
  echo "    attempt $attempt failed"
done

# Fail out loud, with the reason at hand. Since the pack comes from the
# registry, suspect number one is that it does not match the server's: that
# shows up as a mod rejection, not as "could not connect".
echo "==> it did NOT join. Last complaints of the client:"
grep -iE "disconnect|kick|refused|timed out|Unknown host|failed|\
mod rejections|incompatible|missing mods|negotiation" "$OUTPUT" \
  | tail -8 | cut -c1-160
echo "==> it joined with the '$SRV' pack ($(find "$BASE/gamedir/mods" -name '*.jar' \
  | wc -l) mods). If the running server is not that one, there is the reason."
exit 1

#!/bin/bash
# What create_bot.sh, start_bot.sh and friends share. Not meant to run alone.
#
# Three folders, outside the repo because they are heavy and are not code:
#
#   bots/<name>/      what belongs to each bot: port, server, gamedir, hmc
#   servers/<slug>/   one server and ITS pack of client mods
#   shared/           what every bot uses: the launcher and the Marionette mods
#
# And one rule: **the pack owns gamedir/mods**. A bot's mods are hard links to
# the pack, so switching servers means deleting the links and making them
# again. Deleting a hard link does NOT delete the pack's jar, which is why this
# can be done lightly and without copying hundreds of MB per bot.

BOTS_DIR="${MARIONETTE_BOTS_DIR:-$HOME/bots}"
SERVERS_DIR="${MARIONETTE_SERVERS_DIR:-$HOME/servers}"
COMMON_DIR="${MARIONETTE_COMMON_DIR:-$HOME/shared}"
# A native Claude Code install (~/.local/bin, updates itself) goes first.
export PATH="$HOME/.local/bin:$PATH"
FIRST_PORT=8478   # 8477 belongs to the SERVER mod

lowercase() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

port_in_use() {
  ss -lntH 2>/dev/null \
    | awk -v p="$1" '{n=split($4,a,":"); if (a[n]==p) f=1} END{exit !f}'
}

# Busy is not enough: a stopped bot does not listen, but its port is still its own.
port_reserved() {
  local p="$1" me="${2:-}" f owner
  for f in "$BOTS_DIR"/*/port; do
    [ -f "$f" ] || continue
    owner="$(basename "$(dirname "$f")")"
    [ "$owner" = "$me" ] && continue
    [ "$(cat "$f" 2>/dev/null)" = "$p" ] && return 0
  done
  return 1
}

free_port() {
  local me="${1:-}" p=$FIRST_PORT
  while [ "$p" -lt $((FIRST_PORT + 50)) ]; do
    if ! port_reserved "$p" "$me" && ! port_in_use "$p"; then
      printf '%s' "$p"
      return 0
    fi
    p=$((p + 1))
  done
  return 1
}

list_servers() {
  local d slug n
  for d in "$SERVERS_DIR"/*/; do
    [ -f "$d/server.conf" ] || continue
    slug="$(basename "$d")"
    n=$(find "$d/mods" -maxdepth 1 -name '*.jar' 2>/dev/null | wc -l)
    (
      # shellcheck source=/dev/null
      . "$d/server.conf"
      printf '  %-14s %-20s %3s mods  %s\n' \
             "$slug" "$HOST:${MC_PORT:-25565}" "$n" "${DESCRIPTION:-}"
    )
  done
}

# Sets HOST, MC_PORT, VERSION, DESCRIPTION and PACK. Careful: server.conf must
# NOT use the variable PORT, which in the launchers is the bot's own port.
load_server() {
  local slug="$1"
  PACK="$SERVERS_DIR/$slug"
  if [ ! -f "$PACK/server.conf" ]; then
    echo "unknown server '$slug'. These are the ones there are:"
    list_servers
    return 1
  fi
  # shellcheck source=/dev/null
  . "$PACK/server.conf"
  if [ -z "${HOST:-}" ]; then
    echo "$slug/server.conf does not set HOST."
    return 1
  fi
  MC_PORT="${MC_PORT:-25565}"
  VERSION="${VERSION:-neoforge-21.1.248}"
  return 0
}

# The connection settings of the server mod (MARIONETTE_HOST, MARIONETTE_PORT,
# MARIONETTE_TOKEN, optionally MARIONETTE_OWNER). Without them nothing can ask
# the server who is connected, so the launchers refuse to guess.
load_env() {
  MOD_ENV="${MARIONETTE_ENV:-$HOME/.marionette/server.env}"
  if [ ! -f "$MOD_ENV" ]; then
    echo "missing $MOD_ENV (MARIONETTE_HOST, MARIONETTE_PORT, MARIONETTE_TOKEN)."
    return 1
  fi
  # shellcheck source=/dev/null
  . "$MOD_ENV"
  MARIONETTE_HOST="${MARIONETTE_HOST:-127.0.0.1}"
  MARIONETTE_PORT="${MARIONETTE_PORT:-8477}"
  MARIONETTE_TOKEN="${MARIONETTE_TOKEN:-}"
  return 0
}

# The server is the one to ask whether a bot is in, not the client log: the log
# says what the client believes, /players says what there is.
is_inside() {
  curl -s -m5 -H "X-Marionette-Token: $MARIONETTE_TOKEN" \
       "http://$MARIONETTE_HOST:$MARIONETTE_PORT/players" 2>/dev/null \
    | grep -q "\"$1\""
}

# gamedir/mods is a MANAGED folder, not a drawer: it is rebuilt whole. A jar
# someone drops there is gone on the next sync.
# The body runs in a subshell (parentheses, not braces) so the cd does not leak
# to the caller. The cd is there for find, which complains when it cannot go
# back to a starting directory it is not allowed to read (such as /root).
sync_mods() (
  gamedir="$1"; pack="$2"; n=0
  mkdir -p "$gamedir/mods" || return 1
  cd "$gamedir/mods" || return 1
  find . -maxdepth 1 -name '*.jar' -delete
  for jar in "$COMMON_DIR"/mods/*.jar "$pack"/mods/*.jar; do
    [ -f "$jar" ] || continue
    ln -f "$jar" "./$(basename "$jar")" 2>/dev/null \
      || cp "$jar" . || return 1
    n=$((n + 1))
  done
  printf '%s' "$n"
)

# A name that is a substring of another does not break the launcher but the
# bridge, which reacts when `NAME in text`: with "Ada" and "Adam" in the same
# chat, calling one answers with both. Better to refuse now than to find out
# in the chat.
name_clashes() {
  local under="$1" other
  for other in "$BOTS_DIR"/*/; do
    [ -d "$other" ] || continue
    other="$(basename "$other")"
    [ "$other" = "$under" ] && continue
    case "$other" in *"$under"*) printf '%s' "$other"; return 0 ;; esac
    case "$under" in *"$other"*) printf '%s' "$other"; return 0 ;; esac
  done
  return 1
}

# Every launcher needs a bot name; there is no default bot.
require_name() {
  if [ -z "${1:-}" ]; then
    echo "usage: $(basename "$0") <bot name>${2:+ $2}"
    exit 1
  fi
}

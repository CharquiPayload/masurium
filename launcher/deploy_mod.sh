#!/bin/bash
# Puts the freshly built bot mod in shared/mods WITHOUT overwriting the one in use.
#
#   ./deploy_mod.sh            (after ./gradlew build in mod-bot)
#
# Each bot's mods are HARD LINKS to the jar in shared/mods, and `cp` over that
# jar writes into the same inode: a running client sees the zip it has open
# change and, as soon as it needs a class it had not loaded yet (an inner class,
# say), dies with NoClassDefFoundError. That is how one bot crashed in the very
# second the jar was copied to restart another.
#
# `mv` instead creates a new inode: the link in the gamedir of the running bot
# still points at the old jar (which lives on while it has a link) and the bot
# does not notice. On its next start, sync_mods links it to the new one. It is
# the only safe way to deploy with a bot in the game.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$HERE/common.sh"

SOURCE="$(ls "$HERE"/../mod-bot/build/libs/marionette-bot-*.jar 2>/dev/null \
          | grep -v -- '-sources' | head -1 || true)"
[ -n "$SOURCE" ] || { echo "no marionette-bot jar in mod-bot/build/libs: build it first"; exit 1; }
mkdir -p "$COMMON_DIR/mods"
TARGET="$COMMON_DIR/mods/$(basename "$SOURCE")"

cp "$SOURCE" "$TARGET.new"
mv -f "$TARGET.new" "$TARGET"
# A different version left behind would be loaded twice. Unlinking it is as
# safe as the mv above: running bots keep their own link.
for old in "$COMMON_DIR"/mods/marionette-bot-*.jar; do
  [ "$old" = "$TARGET" ] || rm -f "$old"
done
echo "==> deployed $(stat -c %s "$TARGET") bytes to $TARGET (new inode)"
echo "    bots already in the game keep the old jar until they restart"

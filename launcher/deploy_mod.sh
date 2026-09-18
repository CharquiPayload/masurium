#!/bin/bash
# Puts the freshly built mod in shared/mods WITHOUT overwriting the one in use.
#
#   ./deploy_mod.sh            (after ./gradlew build in mod/)
#
# ONE jar holds both halves, so this same file is also what goes in the Minecraft
# SERVER's mods folder. Deploying only one side is what the version handshake in the
# server console complains about.
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

SOURCE="$(ls "$HERE"/../mod/build/libs/marionette-*.jar 2>/dev/null \
          | grep -v -- '-sources' | head -1 || true)"
[ -n "$SOURCE" ] || { echo "no marionette jar in mod/build/libs: build it first"; exit 1; }
mkdir -p "$COMMON_DIR/mods"
TARGET="$COMMON_DIR/mods/$(basename "$SOURCE")"

cp "$SOURCE" "$TARGET.new"
mv -f "$TARGET.new" "$TARGET"
# A jar left behind would declare the same mod twice and the game would refuse to
# start. That covers the OLD TWO-FILE NAMES as well: whoever updates from those has
# marionette-bot-*.jar and marionette-server-*.jar sitting there. Unlinking is as
# safe as the mv above: running bots keep their own link.
for old in "$COMMON_DIR"/mods/marionette-*.jar; do
  [ "$old" = "$TARGET" ] || rm -f "$old"
done
echo "==> deployed $(stat -c %s "$TARGET") bytes to $TARGET (new inode)"
echo "    bots already in the game keep the old jar until they restart"
echo
echo "    The SAME file goes in the Minecraft server's mods folder, and the old"
echo "    marionette-bot-*.jar / marionette-server-*.jar have to come out of it:"
echo "    two jars declaring marionette_bot means the game does not start."

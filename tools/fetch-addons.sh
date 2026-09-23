#!/bin/sh
# The add-on jars a release carries (packaging/addons.txt), downloaded from
# their repositories' releases into a folder, each checked against its
# checksum: one that does not match is refused and nothing is left of it.
# A jar already there with the right checksum is not downloaded again.
#
#   tools/fetch-addons.sh <folder>
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEST=${1:?usage: tools/fetch-addons.sh <folder>}
die() { printf 'fetch-addons: %s\n' "$*" >&2; exit 1; }
mkdir -p "$DEST"

grep -v '^[[:space:]]*\(#\|$\)' "$ROOT/packaging/addons.txt" | while read -r name version sum; do
    jar="$name-$version.jar"
    if [ -f "$DEST/$jar" ] && [ "$(sha256sum "$DEST/$jar" | cut -d' ' -f1)" = "$sum" ]; then
        echo "    $jar (there already)"
        continue
    fi
    # Every add-on is masurium-<something>, in a repository of that name.
    url="https://github.com/CharquiPayload/masurium-${name#masurium-}/releases/download/v$version/$jar"
    curl -fsSL -o "$DEST/$jar.part" "$url" || { rm -f "$DEST/$jar.part"; die "$url could not be downloaded"; }
    got=$(sha256sum "$DEST/$jar.part" | cut -d' ' -f1)
    if [ "$got" != "$sum" ]; then
        rm -f "$DEST/$jar.part"
        die "$jar did not arrive as released: its checksum is $got, not $sum"
    fi
    mv "$DEST/$jar.part" "$DEST/$jar"
    echo "    $jar"
done

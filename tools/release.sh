#!/bin/sh
# Everything a release carries, into dist/: the Masurium jars (the mod and its
# add-ons), the launcher as a folder for any Linux (install.sh inside) and as a
# .deb, and their checksums. Run on Debian or Ubuntu, from a clean checkout.
#
#   tools/release.sh
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
VERSION=$(sed -n 's/^__version__ = "\(.*\)"$/\1/p' "$ROOT/launcher/__init__.py")
OUT="$ROOT/dist"
die() { printf 'release: %s\n' "$*" >&2; exit 1; }
[ -n "$VERSION" ] || die "no __version__ in launcher/__init__.py"

echo "==> building the mod and its add-ons"
(cd "$ROOT/mod" && ./gradlew build --console=plain -q)
for a in "$ROOT"/addons/*/; do (cd "$a" && ./gradlew build --console=plain -q); done

rm -rf "$OUT"
mkdir -p "$OUT"
for j in "$ROOT"/mod/build/libs/masurium-*.jar "$ROOT"/addons/*/build/libs/masurium-*.jar; do
    case "$j" in *-sources.jar) continue ;; esac
    cp "$j" "$OUT/"
done

echo "==> the launcher, for any Linux"
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
dir="$STAGE/masurium-launcher-$VERSION"
mkdir -p "$dir/jars"
cp -R "$ROOT/launcher" "$ROOT/mcp" "$ROOT/docs" "$ROOT/install.sh" "$ROOT/README.md" "$ROOT/LICENSE" \
      "$ROOT/CHANGELOG.md" "$dir/"
cp "$OUT"/masurium-*.jar "$dir/jars/"
find "$dir" -name __pycache__ -type d -prune -exec rm -rf {} +
tar -C "$STAGE" -czf "$OUT/masurium-launcher-$VERSION.tar.gz" "masurium-launcher-$VERSION"

echo "==> the launcher, as a .deb"
"$ROOT/tools/build-deb.sh"

(cd "$OUT" && sha256sum -- * > SHA256SUMS)
echo "==> dist/:"
ls -la "$OUT"

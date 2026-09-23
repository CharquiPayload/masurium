#!/bin/sh
# Everything a release carries, into dist/: the Masurium jars (the mod, and the
# add-ons from their own repositories' releases), the launcher as a folder for
# any Linux and for Windows (install.sh and install.ps1 inside), as a .deb and
# as the PKGBUILD that makes its Arch package, install.sh and
# install.ps1 on their own (the one-line installers, for Linux and for
# Windows), and their checksums.
# Run on Debian or Ubuntu, from a clean checkout.
#
#   tools/release.sh
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
VERSION=$(sed -n 's/^__version__ = "\(.*\)"$/\1/p' "$ROOT/launcher/__init__.py")
OUT="$ROOT/dist"
die() { printf 'release: %s\n' "$*" >&2; exit 1; }
[ -n "$VERSION" ] || die "no __version__ in launcher/__init__.py"

echo "==> building the mod"
(cd "$ROOT/mod" && ./gradlew build --console=plain -q)

rm -rf "$OUT"
mkdir -p "$OUT"
for j in "$ROOT"/mod/build/libs/masurium-*.jar; do
    case "$j" in *-sources.jar) continue ;; esac
    cp "$j" "$OUT/"
done
echo "==> the add-ons, from their releases (packaging/addons.txt)"
"$ROOT/tools/fetch-addons.sh" "$OUT"

echo "==> the launcher, for any Linux and for Windows"
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
dir="$STAGE/masurium-launcher-$VERSION"
mkdir -p "$dir/jars"
cp -R "$ROOT/launcher" "$ROOT/mcp" "$ROOT/docs" "$ROOT/packaging" "$ROOT/install.sh" "$ROOT/install.ps1" "$ROOT/README.md" \
      "$ROOT/LICENSE" "$ROOT/CHANGELOG.md" "$dir/"
cp "$OUT"/masurium-*.jar "$dir/jars/"
find "$dir" -name __pycache__ -type d -prune -exec rm -rf {} +
tar -C "$STAGE" -czf "$OUT/masurium-launcher-$VERSION.tar.gz" "masurium-launcher-$VERSION"
# And the installers on their own: piped from .../releases/latest/download/
# (install.sh into sh, install.ps1 into PowerShell), each finds the tarball
# above in SHA256SUMS, checks it and installs it with the one inside.
cp "$ROOT/install.sh" "$ROOT/install.ps1" "$OUT/"

echo "==> the launcher, as a .deb"
"$ROOT/tools/build-deb.sh"

echo "==> the PKGBUILD of its Arch package, for this tarball"
maintainer=$(git -C "$ROOT" log -1 --format='%an <%ae>')
sum=$(sha256sum "$OUT/masurium-launcher-$VERSION.tar.gz" | cut -d' ' -f1)
sed -e "s|@VERSION@|$VERSION|" -e "s|@SHA256@|$sum|" -e "s|@MAINTAINER@|$maintainer|" \
    "$ROOT/packaging/arch/PKGBUILD" > "$OUT/PKGBUILD"

(cd "$OUT" && sha256sum -- * > SHA256SUMS)
echo "==> dist/:"
ls -la "$OUT"

#!/bin/sh
# The .deb of Masurium Launcher, for Ubuntu, Debian and their family, from this
# repository: the program, the Masurium jars it was built with, and Qt for the
# window as the wheels it was tested with, so installing needs no internet.
# Run it on a Debian or Ubuntu machine, with the jars built (mod/ and each
# addons/*: ./gradlew build).
#
#   tools/build-deb.sh     ->  dist/masurium-launcher_<version>_amd64.deb
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
NAME=masurium-launcher
VERSION=$(sed -n 's/^__version__ = "\(.*\)"$/\1/p' "$ROOT/launcher/__init__.py")
ARCH=amd64
# The Qt for Python the window was tested with. Its wheels are abi3: one of them
# serves every Python from 3.10 on, whichever the machine has.
PYSIDE_VERSION=6.11.2
WHEEL_PLATFORM=manylinux_2_34_x86_64
OUT="$ROOT/dist"

die() { printf 'build-deb: %s\n' "$*" >&2; exit 1; }
[ -n "$VERSION" ] || die "no __version__ in launcher/__init__.py"
command -v dpkg-deb >/dev/null || die "dpkg-deb is needed: run it on Debian or Ubuntu"
core=$(ls "$ROOT"/mod/build/libs/masurium-[0-9]*.jar 2>/dev/null | grep -v -- -sources | head -1 || true)
[ -n "$core" ] || die "no masurium jar in mod/build/libs: build the mod first (cd mod && ./gradlew build)"

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
PKG="$STAGE/pkg"
OPT="$PKG/opt/$NAME"
mkdir -p "$OPT/app/jars" "$OPT/wheels" "$PKG/usr/bin" "$PKG/usr/share/applications" \
         "$PKG/usr/share/doc/$NAME" "$PKG/DEBIAN"

echo "==> the program"
cp -R "$ROOT/launcher" "$ROOT/mcp" "$ROOT/docs" "$ROOT/README.md" "$ROOT/LICENSE" "$ROOT/CHANGELOG.md" "$OPT/app/"
find "$OPT/app" -name __pycache__ -type d -prune -exec rm -rf {} +
cp "$core" "$OPT/app/jars/"
for j in "$ROOT"/addons/*/build/libs/masurium-*.jar; do
    case "$j" in *-sources.jar) continue ;; esac
    if [ -f "$j" ]; then cp "$j" "$OPT/app/jars/"; fi
done
ls "$OPT/app/jars"

echo "==> Qt for Python $PYSIDE_VERSION, as wheels"
python3 -m venv "$STAGE/pip"
"$STAGE/pip/bin/pip" download --quiet --disable-pip-version-check --no-deps --only-binary=:all: \
    --platform "$WHEEL_PLATFORM" --implementation cp --python-version 3.10 --abi abi3 \
    -d "$OPT/wheels" "PySide6-Essentials==$PYSIDE_VERSION" "shiboken6==$PYSIDE_VERSION"
ls "$OPT/wheels"

echo "==> the command, the menu entry, the icon"
cp "$ROOT/packaging/deb/masurium" "$PKG/usr/bin/masurium"
cp "$ROOT/packaging/deb/$NAME.desktop" "$PKG/usr/share/applications/"
for s in 32 48 64 128 256 512; do
    mkdir -p "$PKG/usr/share/icons/hicolor/${s}x${s}/apps"
    cp "$ROOT/launcher/gui/appicon/$NAME-$s.png" "$PKG/usr/share/icons/hicolor/${s}x${s}/apps/$NAME.png"
done
holder=$(sed -n 's/^Copyright (c) //p' "$ROOT/LICENSE" | head -1)
sed "s|@COPYRIGHT@|$holder|" "$ROOT/packaging/deb/copyright" > "$PKG/usr/share/doc/$NAME/copyright"

echo "==> the package"
maintainer=$(git -C "$ROOT" log -1 --format='%an <%ae>' 2>/dev/null || true)
[ -n "$maintainer" ] || die "no git author to name as the maintainer"
size=$(du -sk "$PKG" | cut -f1)
sed -e "s|@VERSION@|$VERSION|" -e "s|@ARCH@|$ARCH|" -e "s|@MAINTAINER@|$maintainer|" -e "s|@SIZE@|$size|" \
    "$ROOT/packaging/deb/control" > "$PKG/DEBIAN/control"
cp "$ROOT/packaging/deb/postinst" "$ROOT/packaging/deb/prerm" "$PKG/DEBIAN/"
chmod 755 "$PKG/DEBIAN/postinst" "$PKG/DEBIAN/prerm" "$PKG/usr/bin/masurium"
find "$PKG" -type d -exec chmod 755 {} +
find "$PKG/opt" "$PKG/usr/share" -type f -exec chmod 644 {} +
chmod 755 "$OPT/app/launcher/masurium.py"
mkdir -p "$OUT"
deb="$OUT/${NAME}_${VERSION}_${ARCH}.deb"
dpkg-deb --root-owner-group -Zxz --build "$PKG" "$deb" >/dev/null
echo "==> $deb ($(du -h "$deb" | cut -f1))"

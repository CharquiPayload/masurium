#!/bin/sh
# Masurium Launcher for Linux, for this user and without sudo: the program, its
# own Python environment with Qt for its window, a `masurium` command and an
# entry in the applications menu. Run it again to update. On Ubuntu and Debian
# the .deb does the same for the whole machine.
#
#   ./install.sh               install, or update
#   ./install.sh --no-gui      the command line alone (a machine without a screen)
#   ./install.sh --uninstall   take it away; your instances and settings stay
set -eu

NAME=masurium-launcher
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
PREFIX="$DATA/$NAME"
BIN="$HOME/.local/bin"
APPS="$DATA/applications"
ICONS="$DATA/icons/hicolor"
SIZES="32 48 64 128 256 512"
PYSIDE="PySide6-Essentials>=6.7,<7"
MARK="# installed by Masurium Launcher's install.sh"
HERE=$(cd "$(dirname "$0")" && pwd)

say() { printf '%s\n' "$*"; }
die() { printf 'install.sh: %s\n' "$*" >&2; exit 1; }

gui=1
action=install
for arg in "$@"; do
    case "$arg" in
        --no-gui) gui=0 ;;
        --uninstall) action=uninstall ;;
        -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "unknown option: $arg (see --help)" ;;
    esac
done

refresh_menus() {
    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database -q "$APPS" 2>/dev/null || true
    fi
    if command -v gtk-update-icon-cache >/dev/null 2>&1; then
        gtk-update-icon-cache -q -t "$ICONS" 2>/dev/null || true
    fi
}

if [ "$action" = uninstall ]; then
    rm -rf "$PREFIX"
    # The command only if it is ours: someone else's `masurium` is left alone.
    if [ -f "$BIN/masurium" ] && grep -q "$MARK" "$BIN/masurium"; then
        rm -f "$BIN/masurium"
    fi
    rm -f "$APPS/$NAME.desktop"
    for s in $SIZES; do rm -f "$ICONS/${s}x${s}/apps/$NAME.png"; done
    refresh_menus
    say "Masurium Launcher is uninstalled."
    say "Your instances, servers and settings stay (~/.local/share/masurium and ~/.masurium):"
    say "delete those folders yourself if you want them gone too."
    exit 0
fi

[ "$(uname -s)" = Linux ] || die "this installer is for Linux"
if [ ! -f "$HERE/launcher/masurium.py" ] || [ ! -f "$HERE/mcp/bridge.py" ]; then
    die "run it from Masurium's folder: a release, or the repository"
fi
PY=$(command -v python3) || die "Python 3.9 or newer is needed: install python3"
"$PY" -c 'import sys; sys.exit(sys.version_info < (3, 9))' \
    || die "Python 3.9 or newer is needed; this one is $("$PY" --version 2>&1)"

say "==> installing Masurium Launcher in $PREFIX"
mkdir -p "$PREFIX"
# The program, replaced whole: an update leaves nothing of the old one behind.
rm -rf "$PREFIX/app.new"
mkdir -p "$PREFIX/app.new/jars"
cp -R "$HERE/launcher" "$HERE/mcp" "$PREFIX/app.new/"
for f in README.md LICENSE CHANGELOG.md; do
    if [ -f "$HERE/$f" ]; then cp "$HERE/$f" "$PREFIX/app.new/"; fi
done
if [ -d "$HERE/docs" ]; then cp -R "$HERE/docs" "$PREFIX/app.new/"; fi
# The Masurium jars it came with: a release's jars/, or what a clone of the
# repository built. `masurium setup` puts them in shared/mods.
if [ -d "$HERE/jars" ]; then
    for j in "$HERE"/jars/*.jar; do
        if [ -f "$j" ]; then cp "$j" "$PREFIX/app.new/jars/"; fi
    done
else
    for j in "$HERE"/mod/build/libs/masurium-*.jar "$HERE"/addons/*/build/libs/masurium-*.jar; do
        case "$j" in *-sources.jar) continue ;; esac
        if [ -f "$j" ]; then cp "$j" "$PREFIX/app.new/jars/"; fi
    done
fi
find "$PREFIX/app.new" -name __pycache__ -type d -prune -exec rm -rf {} +
rm -rf "$PREFIX/app"
mv "$PREFIX/app.new" "$PREFIX/app"

# Its own Python environment: Qt for the window lives there, not in the
# system's Python. Made again when the system's Python changed under it.
if ! "$PREFIX/venv/bin/python" -c 'import sys' >/dev/null 2>&1; then
    rm -rf "$PREFIX/venv"
    "$PY" -m venv "$PREFIX/venv" >/dev/null 2>&1 \
        || die "Python could not make an environment: install python3-venv (on Ubuntu or Debian: sudo apt install python3-venv) and run this again"
fi
if [ "$gui" = 1 ]; then
    say "==> getting Qt for the window (PySide6): about 100 MB, only the first time"
    "$PREFIX/venv/bin/python" -m pip install --quiet --disable-pip-version-check "$PYSIDE" \
        || die "PySide6 could not be installed (no internet?). ./install.sh --no-gui installs the command line alone"
fi

# The command.
mkdir -p "$BIN"
cat > "$BIN/masurium" <<EOF
#!/bin/sh
$MARK
exec "$PREFIX/venv/bin/python" "$PREFIX/app/launcher/masurium.py" "\$@"
EOF
chmod 755 "$BIN/masurium"

# The menu entry and its icon.
if [ "$gui" = 1 ]; then
    for s in $SIZES; do
        mkdir -p "$ICONS/${s}x${s}/apps"
        cp "$PREFIX/app/launcher/gui/appicon/$NAME-$s.png" "$ICONS/${s}x${s}/apps/$NAME.png"
    done
    mkdir -p "$APPS"
    cat > "$APPS/$NAME.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Masurium Launcher
GenericName=Minecraft bot launcher
Comment=Create, start and watch Minecraft bots with a brain
Exec="$BIN/masurium" gui
Icon=$NAME
Terminal=false
Categories=Game;
Keywords=minecraft;bot;ai;claude;
StartupWMClass=Masurium Launcher
EOF
    refresh_menus
fi

say ""
say "Masurium Launcher is installed."
if [ "$gui" = 1 ]; then
    say "  Open it from the applications menu, or run:  masurium gui"
    say "  The first time, it walks you through the setup."
    # Qt 6 on X11 needs libxcb-cursor, which some desktops do not install.
    if command -v ldconfig >/dev/null 2>&1 && ! ldconfig -p 2>/dev/null | grep -q 'libxcb-cursor\.so\.0'; then
        say "  If the window does not open, install libxcb-cursor0 (Ubuntu/Debian: sudo apt install libxcb-cursor0)."
    fi
else
    say "  Run:  masurium setup   and then  masurium --help"
fi
case ":$PATH:" in
    *":$BIN:"*) ;;
    *) say "  Note: $BIN is not on your PATH: add it, or run $BIN/masurium" ;;
esac

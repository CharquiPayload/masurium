#!/bin/sh
# Masurium Launcher for Linux, for this user and without sudo: the program, its
# own Python environment with Qt for its window, a `masurium` command and an
# entry in the applications menu. On Ubuntu and Debian the .deb does the same
# for the whole machine.
#
#   curl -fsSL https://github.com/CharquiPayload/masurium/releases/latest/download/install.sh | sh
#       the latest release, downloaded and checked; run it again to update
#   ./install.sh               from a release's folder, or the repository: that one
#   ./install.sh --no-gui      the command line alone (a machine without a screen;
#                              piped:  ... | sh -s -- --no-gui)
#   ./install.sh --uninstall   take it away; your instances and settings stay
#
# Everything runs from main, on the last line: a download cut short runs nothing.
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
RELEASE="${MASURIUM_RELEASE_URL:-https://github.com/CharquiPayload/masurium/releases/latest/download}"

say() { printf '%s\n' "$*"; }
die() { printf 'install.sh: %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'EOF'
Masurium Launcher for Linux, for this user and without sudo.

  curl -fsSL https://github.com/CharquiPayload/masurium/releases/latest/download/install.sh | sh
      the latest release, downloaded and checked; run it again to update
  ./install.sh               from a release's folder, or the repository: that one
  ./install.sh --no-gui      the command line alone (a machine without a screen;
                             piped:  ... | sh -s -- --no-gui)
  ./install.sh --uninstall   take it away; your instances and settings stay
EOF
}

refresh_menus() {
    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database -q "$APPS" 2>/dev/null || true
    fi
    if command -v gtk-update-icon-cache >/dev/null 2>&1; then
        gtk-update-icon-cache -q -t "$ICONS" 2>/dev/null || true
    fi
}

uninstall() {
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
}

# Piped from curl, or run anywhere but Masurium's folder: the latest release.
# Its SHA256SUMS names the launcher's tarball and its checksum, the tarball is
# checked before anything in it runs, and its own install.sh installs it.
from_release() {
    say "==> getting the latest Masurium Launcher from $RELEASE"
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT
    "$PY" - "$RELEASE" "$tmp" <<'EOF' || die "nothing was installed"
import hashlib, pathlib, re, sys, urllib.request

base, tmp = sys.argv[1].rstrip("/"), pathlib.Path(sys.argv[2])


def get(name):
    request = urllib.request.Request(base + "/" + name, headers={"User-Agent": "masurium-install"})
    try:
        with urllib.request.urlopen(request, timeout=60) as r:
            return r.read()
    except Exception as e:
        sys.exit(f"install.sh: {base}/{name} could not be downloaded: {getattr(e, 'reason', e)}")


sums = get("SHA256SUMS").decode("utf-8", "replace")
m = re.search(r"^([0-9a-f]{64}) [ *](masurium-launcher-[0-9][A-Za-z0-9.+~-]*\.tar\.gz)$", sums, re.M)
if not m:
    sys.exit("install.sh: the release's SHA256SUMS names no masurium-launcher-<version>.tar.gz")
data = get(m.group(2))
if hashlib.sha256(data).hexdigest() != m.group(1):
    sys.exit(f"install.sh: {m.group(2)} did not arrive as the release has it (its checksum differs)")
(tmp / m.group(2)).write_bytes(data)
(tmp / "name").write_text(m.group(2))
EOF
    name=$(cat "$tmp/name")
    tar -xzf "$tmp/$name" -C "$tmp" || die "$name could not be unpacked"
    dir="$tmp/${name%.tar.gz}"
    [ -f "$dir/install.sh" ] || die "$name has no install.sh"
    say "==> $name: checked, and unpacked"
    sh "$dir/install.sh" "$@" </dev/null
}

install_here() {
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
    # The Masurium jars it came with: a release's jars/ (the mod and its add-ons),
    # or the mod a clone of the repository built. `masurium setup` puts them in
    # shared/mods.
    if [ -d "$HERE/jars" ]; then
        for j in "$HERE"/jars/*.jar; do
            if [ -f "$j" ]; then cp "$j" "$PREFIX/app.new/jars/"; fi
        done
    else
        for j in "$HERE"/mod/build/libs/masurium-*.jar; do
            case "$j" in *-sources.jar) continue ;; esac
            if [ -f "$j" ]; then cp "$j" "$PREFIX/app.new/jars/"; fi
        done
    fi
    # Which installer made this copy: one of this script's can update itself
    # from the window (launcher/updates.py), for its user and without sudo.
    say "install.sh" > "$PREFIX/app.new/INSTALLER"
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
}

main() {
    gui=1
    action=install
    for arg in "$@"; do
        case "$arg" in
            --no-gui) gui=0 ;;
            --uninstall) action=uninstall ;;
            -h|--help) usage; exit 0 ;;
            *) die "unknown option: $arg (see --help)" ;;
        esac
    done
    if [ "$action" = uninstall ]; then
        uninstall
        exit 0
    fi

    [ "$(uname -s)" = Linux ] || die "this installer is for Linux"
    PY=$(command -v python3) || die "Python 3.9 or newer is needed: install python3"
    "$PY" -c 'import sys; sys.exit(sys.version_info < (3, 9))' \
        || die "Python 3.9 or newer is needed; this one is $("$PY" --version 2>&1)"
    # Piped, $0 is the shell, and this folder is wherever it was run from.
    HERE=$(cd "$(dirname "$0")" && pwd)
    if [ -f "$HERE/launcher/masurium.py" ] && [ -f "$HERE/mcp/bridge.py" ]; then
        install_here
    else
        from_release "$@"
    fi
}

main "$@"

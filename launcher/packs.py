"""What a pack is made of, read from the jars themselves, and the rule that
the pack owns each bot's gamedir/mods.

A bot's mods are hard links to the pack, so switching servers means deleting
the links and making them again. Deleting a hard link does NOT delete the
pack's jar, which is why this can be done lightly and without copying
hundreds of MB per bot.
"""
import io
import json
import pathlib
import re
import zipfile

from .files import link_or_copy

MODS_TOML = "META-INF/neoforge.mods.toml"
# The core is marionette-<version>.jar; an add-on is marionette-<name>-<version>.jar.
CORE_JAR = re.compile(r"^marionette-\d")
# Third-party mods a headless bot cannot run without an add-on, and the add-on's
# jar family. The same table lives in the bot mod (Bot.ADDON_FOR), which refuses
# to start without it; here it is caught before a 3 GB java is launched.
ADDON_FOR = {"veil": "marionette-veil"}


def toml_mods(text, manifest_version=""):
    """modId -> version from a mods.toml, without a TOML parser: the two keys
    every [[mods]] block has, and the one placeholder that is common."""
    mods = {}
    current = None

    def flush():
        if current and "modId" in current:
            mods[current["modId"]] = current.get("version", "")

    for raw in text.splitlines():
        line = raw.strip()
        if line == "[[mods]]":
            flush()
            current = {}
            continue
        if line.startswith("["):
            flush()
            current = None
            continue
        if current is None or line.startswith("#") or "=" not in line:
            continue
        key, value = (s.strip() for s in line.split("=", 1))
        value = value.split("#", 1)[0].strip().strip("\"'")
        if key in ("modId", "version"):
            current[key] = value
    flush()
    for k, v in list(mods.items()):
        if v == "${file.jarVersion}":
            mods[k] = manifest_version
    return mods


def jar_mods(jar_path):
    """modId -> version for one jar, and for the jars inside it: Veil, for
    one, is never a jar of its own in a pack, it ships inside Sable and others,
    listed in their META-INF/jarjar/metadata.json."""
    found = {}

    def read(z):
        names = set(z.namelist())
        manifest_version = ""
        if "META-INF/MANIFEST.MF" in names:
            for l in z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace").splitlines():
                if l.startswith("Implementation-Version:"):
                    manifest_version = l.split(":", 1)[1].strip()
        if MODS_TOML in names:
            found.update(toml_mods(z.read(MODS_TOML).decode("utf-8", "replace"), manifest_version))
        if "META-INF/jarjar/metadata.json" in names:
            try:
                meta = json.loads(z.read("META-INF/jarjar/metadata.json").decode("utf-8", "replace"))
            except ValueError:
                meta = {}
            for entry in meta.get("jars", []) if isinstance(meta, dict) else []:
                path = str(entry.get("path", ""))
                if path in names:
                    try:
                        with zipfile.ZipFile(io.BytesIO(z.read(path))) as inner:
                            read(inner)
                    except zipfile.BadZipFile:
                        pass

    try:
        with zipfile.ZipFile(jar_path) as z:
            read(z)
    except (OSError, zipfile.BadZipFile):
        pass
    return found


def pack_sources(ws, pack, extra=None):
    """Where an instance's jars come from: shared/mods, the pack's own, and
    the instance's own extra mods (its mods/ folder), in that order: a jar
    of the same name later on wins."""
    sources = [ws.shared_dir / "mods", pathlib.Path(pack) / "mods"]
    if extra:
        sources.append(pathlib.Path(extra))
    return sources


def pack_mods(ws, pack, extra=None):
    """Every mod an instance joins with: shared/mods, the pack's own, its extras."""
    mods = {}
    for source in pack_sources(ws, pack, extra):
        for jar in sorted(source.glob("*.jar")):
            mods.update(jar_mods(jar))
    return mods


def compare_packs(client, server):
    """What differs between a bot's pack and the server's mods. The version
    mismatches are the strong signal: the server rejects those, and its
    message names NeoForge instead. The other two lists are for reading: a
    mod only on the server may be server-side, and a mod only on the client
    (a renderer, a minimap) is the normal case."""
    return {
        "mismatch": sorted((i, client[i], server[i]) for i in client
                           if i in server and client[i] != server[i]),
        "server_only": sorted(i for i in server if i not in client),
        "client_only": sorted(i for i in client if i not in server),
    }


def sync_mods(ws, gamedir, pack, extra=None):
    """gamedir/mods is a MANAGED folder, not a drawer: it is rebuilt whole. A
    jar someone drops there is gone on the next sync; an extra mod for one
    instance goes in the instance's own mods/ folder."""
    mods = pathlib.Path(gamedir) / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    for old in mods.glob("*.jar"):
        old.unlink()
    linked = {}
    for source in pack_sources(ws, pack, extra):
        for jar in sorted(source.glob("*.jar")):
            target = mods / jar.name
            if target.exists():
                target.unlink()
            link_or_copy(jar, target)
            linked[jar.name] = True
    return len(linked)


def jar_family(name):
    """What is left of a jar's name before its version: `marionette` for the
    core, `marionette-veil` for that add-on. Two jars of one family declare
    the same mod, and only one may stay."""
    m = re.match(r"^(.*?)-\d", name)
    return m.group(1) if m else name.removesuffix(".jar")


def pack_carries(pack, mod_id):
    """The jars of a pack that are, or carry inside them, the mod with this id."""
    return [jar.name for jar in sorted((pathlib.Path(pack) / "mods").glob("*.jar"))
            if mod_id in jar_mods(jar)]

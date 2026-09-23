"""What a bot remembers: the places it knows, with their coordinates, and the
texts of its diary. Both live in its game folder, one file per server (the
same bot joins different worlds, and a bed in one is not in another), and a
place also carries its dimension (in the Nether the same x and z are another
spot).

    gamedir/config/masurium-places-<server>.txt   type,dimension,x,y,z[,name]
    gamedir/config/masurium-diary-<server>.txt    <date> | <text>, one a line

The bot holds them while it plays, and writes them as they change: a file
edited under it would be overwritten by its next change. So a change goes
through the bot while its hands answer, and into the file only when it is
not playing. Reading is always the file, which the bot keeps up to date.
"""
import re
import time
import urllib.parse
from dataclasses import dataclass

from .events import Fail
from .processes import port_in_use

# What the bot remembers places as (the mod's Places.TYPES). A point is any
# spot someone named ("the factory"), and needs its name.
TYPES = ("point", "table", "furnace", "bed", "chest", "portal", "death", "wolf", "farm")
DIMENSIONS = ("overworld", "the_nether", "the_end")
DIMENSION_RULE = re.compile(r"^[a-z0-9_./-]{1,64}$")
PLACES_HEAD = "# Useful places of THIS server: type,dimension,x,y,z[,label]"
DIARY_HEAD = "# Diary of this server. The memorable, not the routine."
SEEN = "#seen "


@dataclass(frozen=True)
class Place:
    type: str
    dimension: str
    x: int
    y: int
    z: int
    name: str = ""

    @property
    def line(self):
        return f"{self.type},{self.dimension},{self.x},{self.y},{self.z}" + (f",{self.name}" if self.name else "")

    def same_spot(self, other):
        return (self.dimension, self.x, self.y, self.z) == (other.dimension, other.x, other.y, other.z)


def _file(inst, what, server):
    return inst.gamedir / "config" / f"masurium-{what}-{server}.txt"


def servers(inst):
    """The servers it remembers something of: its own first, then any other
    whose memory is in its game folder (a copy, a server it played on)."""
    found = set()
    config = inst.gamedir / "config"
    if config.is_dir():
        for f in config.iterdir():
            m = re.match(r"^masurium-(places|diary)-(.+)\.txt$", f.name)
            if m:
                found.add(m.group(2))
    return [inst.slug] + sorted(found - {inst.slug})


def _read_place(line):
    """A line of the places file, or None: `type,dimension,x,y,z[,name]`, or
    the old `type,x,y,z[,name]`, which was always the overworld."""
    line = line.strip()
    if not line or line.startswith("#"):
        return None
    t = line.split(",", 5)
    if len(t) < 4 or t[0].strip() not in TYPES:
        return None
    try:
        if re.match(r"^-?\d+$", t[1].strip()):
            return Place(t[0].strip(), "overworld", int(t[1]), int(t[2]), int(t[3]),
                         ",".join(t[4:]).strip() if len(t) > 4 else "")
        if len(t) < 5:
            return None
        return Place(t[0].strip(), t[1].strip(), int(t[2]), int(t[3]), int(t[4]),
                     t[5].strip() if len(t) == 6 else "")
    except ValueError:
        return None


def places(inst, server=None):
    """What it remembers of a server's places, in the order it noted them."""
    try:
        text = _file(inst, "places", server or inst.slug).read_text(encoding="utf-8")
    except OSError:
        return []
    return [p for p in (_read_place(line) for line in text.splitlines()) if p]


def _read_diary(inst, server):
    try:
        text = _file(inst, "diary", server or inst.slug).read_text(encoding="utf-8")
    except OSError:
        return [], []
    entries, seen = [], []
    for line in text.splitlines():
        line = line.strip()
        if line.startswith(SEEN):
            seen.append(line)
        elif line and not line.startswith("#"):
            entries.append(line)
    return entries, seen


def diary(inst, server=None):
    """The texts it wrote down on a server, oldest first: `<date> | <text>`."""
    return _read_diary(inst, server)[0]


# --- changing it -------------------------------------------------------------

def _playing(inst, server):
    """Whether this memory is the bot's to change right now: its hands answer
    and it is on that server."""
    return (server or inst.slug) == inst.slug and port_in_use(inst.port)


def _ask(inst, route, **params):
    query = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
    answer = inst.ask(f"{route}?{query}")
    if not answer.get("ok"):
        raise Fail(f"{inst.key}: {answer.get('error', 'it did not do it')}", code="memory")
    return answer


def check_place(place):
    if place.type not in TYPES:
        raise Fail(f"'{place.type}' is not a kind of place: " + ", ".join(TYPES), code="bad_place")
    if place.type == "point" and not place.name.strip():
        raise Fail("a point is a spot someone named: it needs its name, or it cannot be asked for "
                   "later", code="bad_place")
    if not DIMENSION_RULE.match(place.dimension):
        raise Fail(f"'{place.dimension}' is not a dimension (overworld, the_nether, the_end...)",
                   code="bad_place")
    if "\n" in place.name:
        raise Fail("a place's name is one line", code="bad_place")


def _write_places(inst, server, items):
    f = _file(inst, "places", server)
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text("\n".join([PLACES_HEAD] + [p.line for p in items]) + "\n", encoding="utf-8")


def remember_place(inst, place, server=None):
    """Notes a place, or renames the one at that spot."""
    check_place(place)
    server = server or inst.slug
    if _playing(inst, server):
        _ask(inst, "/places", remember=place.type, x=place.x, y=place.y, z=place.z,
             label=place.name, dimension=place.dimension)
        return "told"
    items = [p for p in places(inst, server) if not p.same_spot(place)] + [place]
    _write_places(inst, server, items)
    return "written"


def forget_place(inst, place, server=None):
    server = server or inst.slug
    if _playing(inst, server):
        _ask(inst, "/places", forget=1, x=place.x, y=place.y, z=place.z, dimension=place.dimension)
        return "told"
    items = places(inst, server)
    if not any(p.same_spot(place) for p in items):
        raise Fail(f"{inst.key} does not remember a place at {place.x} {place.y} {place.z} "
                   f"in {place.dimension}", code="memory")
    _write_places(inst, server, [p for p in items if not p.same_spot(place)])
    return "written"


def change_place(inst, old, new, server=None):
    """A place moved or renamed: the old one goes, the new one is noted."""
    check_place(new)
    forget_place(inst, old, server)
    return remember_place(inst, new, server)


def _write_diary(inst, server, entries, seen):
    f = _file(inst, "diary", server)
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text("\n".join([DIARY_HEAD] + entries + seen) + "\n", encoding="utf-8")


def write(inst, text, server=None):
    """A text for it to remember, dated now."""
    text = " ".join(text.split())
    if not text:
        raise Fail("there is nothing to remember in an empty text", code="memory")
    server = server or inst.slug
    if _playing(inst, server):
        _ask(inst, "/diary", annotate=text)
        return "told"
    entries, seen = _read_diary(inst, server)
    _write_diary(inst, server, entries + [time.strftime("%Y-%m-%d %H:%M") + " | " + text], seen)
    return "written"


def rewrite(inst, entry, text, server=None):
    """An entry (given whole) rewritten, keeping its date; with no text,
    forgotten."""
    text = " ".join((text or "").split())
    server = server or inst.slug
    if _playing(inst, server):
        _ask(inst, "/diary", entry=entry, text=text)
        return "told"
    entries, seen = _read_diary(inst, server)
    if entry not in entries:
        raise Fail(f"{inst.key} has no such entry in its diary", code="memory")
    i = entries.index(entry)
    if text:
        date = entry.split(" | ", 1)[0] + " | " if " | " in entry else ""
        entries[i] = date + text
    else:
        del entries[i]
    _write_diary(inst, server, entries, seen)
    return "written"


def forget_text(inst, entry, server=None):
    return rewrite(inst, entry, "", server)

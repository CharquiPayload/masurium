"""Groups of instances, and the global config over all of them.

A group is groups/<name>/group.json. Two kinds:

    normal       instances and other groups, started and stopped together.
                 {"kind": "normal", "instances": ["carol"], "groups": ["alice-guards"]}
    dependency   a LEADER and its GUARDS, on one server: a guard is useless
                 without its leader. Starting a guard starts its leader first;
                 stopping the leader stops its guards; restarting the leader
                 leaves them running.
                 {"kind": "dependency", "leader": "alice", "guards": ["bob"]}

Either kind may also carry settings (model, heap, owner, role) and rules,
which IMPOSE on everything inside it: outer over inner, groups over the
instance, the global config (launcher.json) over all of it. Two switches stop
that: `lock` on an instance or a group keeps the groups OUTSIDE it from
imposing on it, and `ignore_global` keeps the global config out.

Each instance and each group is inside one group at most, so what imposes on
an instance is one chain, from its group outwards, and never two groups that
could disagree. A guard has one leader for the same reason: two would give it
orders at once.
"""
import re

from .instances import read_json, write_json
from .events import Fail

NORMAL, DEPENDENCY = "normal", "dependency"


class Group:
    """groups/<key>/group.json."""

    def __init__(self, ws, key):
        self.ws = ws
        self.key = key.lower()
        self.dir = ws.groups_dir / self.key
        self.json = self.dir / "group.json"

    def __repr__(self):
        return f"Group({self.key!r})"

    def __eq__(self, other):
        return isinstance(other, Group) and other.dir == self.dir

    def __hash__(self):
        return hash(self.dir)

    @property
    def id(self):
        return f"group {self.key}"

    def exists(self):
        return self.json.is_file()

    def require(self):
        if not self.exists():
            raise Fail(f"there is no group {self.key}. These are: "
                       + (", ".join(self.ws.group_keys()) or "(none yet)"), code="no_group")
        return self

    @property
    def data(self):
        return read_json(self.json)

    def save(self, data):
        write_json(self.json, data)

    @property
    def kind(self):
        return self.data.get("kind", NORMAL)

    @property
    def leader(self):
        """The leader's instance key, in a dependency group."""
        return self.data.get("leader") or None

    @property
    def guards(self):
        return list(self.data.get("guards") or [])

    @property
    def locked(self):
        """Whether the groups around it are kept from imposing on it."""
        values = self.data.get("settings")
        return isinstance(values, dict) and str(values.get("lock", "")).lower() == "yes"

    def instance_keys(self):
        """The instances directly in it: the leader and guards of a
        dependency group, the listed ones of a normal one."""
        if self.kind == DEPENDENCY:
            return ([self.leader] if self.leader else []) + self.guards
        return list(self.data.get("instances") or [])

    def group_keys(self):
        return [] if self.kind == DEPENDENCY else list(self.data.get("groups") or [])


class Global:
    """The global config, launcher.json: settings and rules over every
    instance that does not ignore it."""

    key = "global"
    id = "the global config"

    def __init__(self, ws):
        self.ws = ws

    def exists(self):
        return True

    def require(self):
        return self

    @property
    def data(self):
        return self.ws.config()

    def save(self, data):
        if data:
            self.ws.save_config(data)
        else:
            self.ws.config_file.unlink(missing_ok=True)


# --- the tree -------------------------------------------------------------------

def parent_of(ws, node):
    """The group an instance or a group is in, or None."""
    from .instances import Instance
    for g in ws.groups():
        if isinstance(node, Instance) and node.key in g.instance_keys():
            return g
        if isinstance(node, Group) and node.key in g.group_keys():
            return g
    return None


def chain(inst):
    """The groups that impose on an instance, innermost first: its group and
    the ones around it, up to one with a lock (included: a lock keeps out
    what is around it, not the group itself). None at all if the instance
    itself is locked."""
    out = []
    if str(inst.data.get("lock", "")).lower() == "yes":
        return out
    node, seen = inst, set()
    while True:
        g = parent_of(inst.ws, node)
        if g is None or g.key in seen:
            return out
        seen.add(g.key)
        out.append(g)
        if g.locked:
            return out
        node = g


def dependency_of(inst):
    """(the dependency group an instance is in, "leader" or "guard"), or
    (None, None)."""
    for g in inst.ws.groups():
        if g.kind != DEPENDENCY:
            continue
        if g.leader == inst.key:
            return g, "leader"
        if inst.key in g.guards:
            return g, "guard"
    return None, None


def leader_of(inst):
    """The instance a guard guards, or None."""
    g, role = dependency_of(inst)
    if role != "guard" or not g.leader:
        return None
    from .instances import Instance
    leader = Instance(inst.ws, g.leader)
    return leader if leader.exists() else None


def guards_of(inst):
    """The instances that guard this one."""
    g, role = dependency_of(inst)
    if role != "leader":
        return []
    from .instances import Instance
    return [i for i in (Instance(inst.ws, k) for k in g.guards) if i.exists()]


def flatten(group, seen=None):
    """Every instance inside a group, at any depth, each once, leaders
    before their guards."""
    from .instances import Instance
    seen = set() if seen is None else seen
    if group.key in seen:
        return []
    seen.add(group.key)
    out = []
    for key in group.instance_keys():
        inst = Instance(group.ws, key)
        if inst.exists() and inst not in out:
            out.append(inst)
    for key in group.group_keys():
        sub = Group(group.ws, key)
        if sub.exists():
            out += [i for i in flatten(sub, seen) if i not in out]
    return out


def subgroups(group, seen=None):
    """The groups inside a group, at any depth."""
    seen = set() if seen is None else seen
    out = []
    for key in group.group_keys():
        if key in seen:
            continue
        seen.add(key)
        sub = Group(group.ws, key)
        if sub.exists():
            out.append(sub)
            out += subgroups(sub, seen)
    return out


def problems(ws):
    """(where, what is wrong) for every group: what `doctor` reports and what
    the commands that change groups refuse to leave behind."""
    from .instances import Instance
    from . import settings
    out = []
    parents = {}
    for g in ws.groups():
        data = g.data
        if g.kind not in (NORMAL, DEPENDENCY):
            out.append((g.id, f"kind '{g.kind}': it is normal or dependency"))
            continue
        for key in g.instance_keys():
            if not Instance(ws, key).exists():
                out.append((g.id, f"there is no instance {key}"))
            parents.setdefault(("instance", key), []).append(g.key)
        for key in g.group_keys():
            if not Group(ws, key).exists():
                out.append((g.id, f"there is no group {key}"))
            parents.setdefault(("group", key), []).append(g.key)
        if g.kind == DEPENDENCY:
            if not g.leader:
                out.append((g.id, "no leader"))
            elif g.leader in g.guards:
                out.append((g.id, f"{g.leader} is its leader and one of its guards"))
            leader = Instance(ws, g.leader) if g.leader else None
            for key in g.guards:
                guard = Instance(ws, key)
                if not guard.exists():
                    continue
                if leader is not None and leader.exists() and guard.slug != leader.slug:
                    out.append((g.id, f"{key} is on {guard.slug} and its leader {g.leader} on "
                                      f"{leader.slug}: a guard plays where its leader does"))
                if settings.get(guard, "role") != "guard":
                    out.append((g.id, f"{key} is one of its guards, and its role is "
                                      f"{settings.get(guard, 'role')}: masurium.py set {key} role guard"))
        for key in ("settings", "rules"):
            if key in data and not isinstance(data[key], dict):
                out.append((g.id, f"{key} is a JSON object"))
    for (what, key), groups in sorted(parents.items()):
        if len(groups) > 1:
            out.append((f"{what} {key}", f"in more than one group ({', '.join(groups)}): one at most, "
                                         "so what imposes on it is never two groups that disagree"))
    for g in ws.groups():
        if g in subgroups(g):
            out.append((g.id, "it is inside itself"))
    return out


GROUP_KEY = re.compile(r"^[a-z0-9_][a-z0-9_ -]{0,39}$")


def check_group_key(key):
    """A group's name, which is its folder: lowercase letters, digits, spaces,
    underscore and dash. Spaces are fine here: a group is no player, and the
    game never sees its name."""
    if not GROUP_KEY.match(key or "") or key != key.strip() or "  " in key:
        raise Fail(f"'{key}' is not a valid group name: lowercase letters, digits, spaces, underscore and dash, "
                   "up to 40, not starting or ending with a space.", code="bad_name")

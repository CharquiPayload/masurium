"""What the window shows, read in one go: every instance with how it is doing,
and the groups as a tree.

It is read on a thread (asking each server whether its bots are in can wait
for a timeout) every few seconds, and the window draws the latest one. A
snapshot is plain data: the window never reads the folders itself.
"""
import time
from dataclasses import dataclass, field

from .. import groups, operations, settings
from ..events import Fail


@dataclass(frozen=True)
class InstanceView:
    key: str
    name: str          # the player
    bot: str
    server: str
    port: int
    client: bool       # a game is running for it
    hands: bool        # the bot mod answers on its port
    inside: object     # True, False, or None when its server was not asked
    bridge: object     # the bridge's pid, or None
    role: str
    leader: str        # the player it guards, when a guard
    model: str
    group: object      # the key of the group it is in, or None

    @property
    def state(self):
        """in, loading, stopped, or mute (in the game with no bridge)."""
        if self.client and self.inside:
            return "in" if self.bridge else "mute"
        if self.client:
            return "loading"
        return "stopped"


@dataclass(frozen=True)
class GroupView:
    key: str
    kind: str
    locked: bool
    parent: object
    instances: tuple
    groups: tuple
    leader: object


@dataclass(frozen=True)
class Snapshot:
    instances: dict = field(default_factory=dict)
    groups: dict = field(default_factory=dict)
    top: tuple = ()            # groups in no group
    loose: tuple = ()          # instances in no group
    problems: tuple = ()       # servers that did not answer, and why
    taken: float = 0.0

    def shape(self):
        """What the grid is built from: when only the states change, the
        tiles are updated in place instead of being built again."""
        return (tuple(sorted((g.key, g.kind, g.instances, g.groups, g.locked) for g in self.groups.values())),
                self.top, self.loose, tuple(sorted(self.instances)))


def read(ws):
    problems, statuses = operations.survey(ws)
    views = {}
    for s in statuses:
        inst = ws.instance(s.key)
        parent = groups.parent_of(ws, inst)
        try:
            model = settings.get(inst, "model")
            role = settings.get(inst, "role")
        except Fail:
            model, role = "?", "?"
        views[s.key] = InstanceView(key=s.key, name=s.name, bot=inst.data.get("bot", ""), server=s.server,
                                    port=s.port, client=s.client, hands=s.hands, inside=s.inside,
                                    bridge=s.bridge, role=role, leader=s.guard_of, model=model,
                                    group=parent.key if parent else None)
    gviews = {}
    for g in ws.groups():
        parent = groups.parent_of(ws, g)
        gviews[g.key] = GroupView(key=g.key, kind=g.kind, locked=g.locked, parent=parent.key if parent else None,
                                  instances=tuple(g.instance_keys()), groups=tuple(g.group_keys()),
                                  leader=g.leader)
    top = tuple(k for k, g in gviews.items() if g.parent is None)
    loose = tuple(k for k, v in views.items() if v.group is None)
    return Snapshot(instances=views, groups=gviews, top=top, loose=loose, problems=tuple(problems),
                    taken=time.time())

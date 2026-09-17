# Lessons

What was learned breaking things while building this and the earlier versions
of the project. **These are not opinions: each one is a bug that happened and
cost hours.** Read it before changing the mods.

---

## Hard facts about the Minecraft API

**Break blocks at a saved `BlockPos`, never at `mc.hitResult`.** Without a
screen there is no render, and "what is in front of me" never updates. A
headless client does not look: it aims at coordinates.

**Choose the tool by asking the game** with `getDestroySpeed`, not with your own
table of what digs what. That way modded blocks work too, which is exactly where
a hand-written table fails. Prefer the tools with which the block actually drops
something, and only switch items for something strictly faster.

**Keep looking at the block every tick while digging.** The server validates
where the head points; stop looking and it rejects the hit **silently**.

**Ask the registry for an item's id** (`BuiltInRegistries.ITEM.getKey(...)`),
never parse `toString()`: depending on the item it returns `minecraft:oak_planks`
or `block{minecraft:oak_log}`, and any trimming that works for one fails for the
other.

**Enchantments live in a data component since 1.20.5**, and the getter returns
the **raw** data, not the normalized list of older versions. Iterating it as a
list throws. Worse: a `try/catch` that swallowed the error meant Feather Falling
and Protection were silently ignored in fall damage estimates for hours.

**Fall damage is `(blocks − 3)` half hearts**, and normal armor does **not**
reduce it (only Feather Falling and Protection). Water cancels it completely at
any height; one block is enough.

**Ballistics**: arrows and tridents share gravity −0.05 and drag 0.99. At full
charge a bow arrow leaves at 3 blocks per tick, so the drop grows with the square
of the distance. **Simulate the line of fire, do not trace a straight ray**: a
straight ray lies both ways (a low wall the arrow would fly over looks blocked,
and the floor it would hit when aiming at a mob in a cave looks clear).

**The chat font only draws Unicode plane 0.** The client ships `unifont` for
plane 0; emoji live above U+FFFF, have no glyph and are drawn as white boxes.
Kaomoji made of plane-0 characters render fine. The fix has two layers on
purpose: the prompt asks for kaomoji (a wish), and the bridge's `drawable()`
removes everything above U+FFFF, variation selectors and joiners (the
guarantee). Limits of the game are filtered in code, not requested politely.

---

## Suspect the timing before the logic

**When something "does not work" but the data looks right, suspect the MOMENT
before the logic.** It happened three times in one night: counting the inventory
after crafting, recording picked-up items, opening a crafting table. Always the
same cause: **clicks are packets to the server, and the client inventory does not
reflect the change in the same tick.**

**Switching hotbar slots is not instant for the server.**
`inventory.selected` belongs to the client; the server learns about it from a
packet the client sends **in its own tick**. Setting the slot and acting in the
same tick (dropping, placing, using) reaches the server in the opposite order:
the action first, with the old slot. The bot was asked for rotten flesh and
dropped cooked fish, which was what it held before. Where waiting a tick is not
possible, send the packet yourself:

```java
p.getInventory().selected = slot;
mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
```

That is `MarionetteBot.wieldNow`. Where a tick can be spent, spend it: it is the
cheap version of the same thing.

**The client cannot vouch for anyone's health.** Right after hitting a zombie the
client said 19.0; the server said 12.1. The client's copy has not received the
server's answer yet. The bot mod never returns a mob's health: whoever wants the
truth asks the server (`/entities`). The same goes for the respawn position and
for the inventory right after server-side crafting (hence the server's own
`/inventory`).

---

## State that nobody resets

**When you add any state with phases, ask who resets it when something is
interrupted.** If the answer is "nobody", you already have the bug. Dying while
drawing a bow left the use key pressed and the charge half done, and **that
state survived the respawn**: the bot came back standing, bow raised, motionless
forever.

The same family, again and again: a faked key must be released on **every**
exit of a behaviour, not only on the ones that remembered to. The use key is
shared by eating and the bow; the jump key by swimming and towers.

## A predicate never mutates state

`hasBow()` moved the bow to the hotbar. It was called from a combat condition,
**20 times per second**, and every inventory move cancelled the item use: draw,
cancel, draw. The symptom was "it stares at the bow and eventually shoots".

**A function named `hasX` or `isY` may only read.** And "it eventually works"
almost always means a race, not a broken function.

## A cube is not a sphere

`AABB.inflate(24)` is a **cube** of side 48, whose corner is 41 blocks from the
center. The bot said "there is no hostile within 24 blocks" while aiming at one
36 blocks away. When a radius is promised, **cut by distance** as well as using
the box, or the number given to the model means something other than what it
says.

## An action and its effect are two different things

**The weapon is checked before EVERY hit**, not once at the start of a fight:
building and digging change what is in hand, and the bot kept hitting with the
cobblestone from its last bridge. Damage is asked of the game through item
attributes, not a hand-written "sword beats axe" list (in 1.21 a diamond axe hits
9 and a sword 7).

**The attack bar.** With the right weapon the zombie lost 0.9 health per hit:
hitting before the bar recharges does a fraction of the damage, and **switching
items resets it**, so the fix "wield the right weapon" guaranteed weak hits. Now
the charge is checked and a weak hit is not spent: "the hit is at 75% of charge;
wait ~5 ticks". Whenever an action has a bar, a cooldown or an animation, "I did
it" and "it had an effect" must be checked separately.

---

## Walking and building

**Movement set from outside does not survive a tick.** Setting
`player.input.forwardImpulse = 1` is overwritten by `Input.tick()`, which
rewrites the impulses from the keyboard inside the player's own tick. The worst
symptom: the bot says it walks, the code looks right, and it does not move.
**Replace `player.input` with your own implementation** whose `tick()` does
nothing, and set the impulses from the walker's step in `ClientTickEvent.Post`,
the only place where the game is in a consistent state to touch the player.
Sprinting also needs the faked key: the client only *starts* sprinting when it
sees the sprint key pressed.

**Start simple, behind an interface.** The first walker went in a straight line
and jumped when blocked, behind three methods (go, stop, state). It was replaced
by a real A* later without the mod or the agent noticing. It was honest from day
one: when stuck it stopped and said where and how far it still was.

**Starting does not block; waiting belongs to the agent.** `/go` starts walking
and returns at once. A long block inside the mod would be an action impossible
to stop from outside.

**Building to reach somewhere failed three times the same way: treating a
transient state as final.**
1. A courtesy that moved destinations in the air down to the ground turned
   "climb up there" into "stay where you are". It only applies when the bot may
   not build.
2. A point counted as reached when the jump passed through its height **in the
   air**; the bot fell back and the tower branch never activated. **Arriving
   requires feet on the ground.**
3. The tower condition ("the next point is one block above my current height")
   stopped holding mid-jump, so the tower aborted itself. **A started tower is a
   STATE, not a condition re-evaluated every tick**, and it must store its column.

The symptom of all three: the bot jumping in place, no error, no complaint.

**The tower block is not placed at take-off.** While rising, the body occupies
the spot and the server silently rejects the placement. Wait for the top of the
jump (about 1.1 blocks above where the block goes).

**The standing tile is not `floor(position)`.** A player is held up by its box:
at the edge of a pillar the center hangs over an empty column, and on a partial
block (a dirt path is 15/16) the feet are inside the block's own cell. Both made
every trip fail with "where I am is not a spot where one can stand".

---

## The path finder

**A* only returns the optimal route if the estimate never exceeds the real
cost.** The first heuristic used 3D distance, but going down is almost free (a
three-block fall costs one step), so the estimate was inflated, A* stopped early,
and the cliff test passed **by accident**. Setting the fall cost to zero (the
exact old bug) still passed. The heuristic now counts horizontal distance (plus a
cheap per-block climb term), and the mutation fails with the exact message.
**A test that never fails hides the bug it claims to watch.**

**When drawing a test world, remember the player is two blocks tall.** Every
walkable tile needs air above, and outside the drawing counts as rock, so leave
an extra layer of air on top or nothing is walkable.

**Build and dig only when there is no way on foot.** Bridges, towers and tunnels
are expensive in the cost table: in a test with a narrow ditch, the path finder
**chose to walk around it**, which is exactly what it should do.

---

## Debugging

**Check the state of the world before suspecting the code.** Five builds chased
a "crafts fine but says it could not". An honest diagnostic showed it at once:
empty inventory, and `was blown up by Creeper` in the log. The bot was dying
between tests, and "I could not craft" was the truth.

**A silent failure is worse than a loud one.** A well-meant `try/catch`
swallowed a `NoClassDefFoundError` and the bot stood still without a trace in any
log.

**Instrument before the third fix attempt, not after.** A counter inside the bow
draw showed `using=0, aborts=0`: the item use **never started**, it was not being
cancelled. Telling those apart was the whole bug.

**"It stands still" is not always a hang.** Once it was doing exactly what it
was asked: aiming at a target still set from before it died. Ask the state before
touching code.

**Tests with HTTP 200 and correct data do not catch gameplay bugs.** Most bugs
were found by someone watching the game. Automated tests keep the logic honest;
they do not replace watching.

**"Gets nothing when mining" was three different bugs** with one symptom: asking
for `coal` when the block is `coal_ore`, not carrying a pickaxe, and "get N"
meaning "have N" instead of "N more". Look at what the layer below answers before
touching the planner.

**Merging everything said in a time window reads the previous step's late
messages as failures of the current one.** Chat and notices carry incremental
ids so nothing is read twice or lost.

**Spawn protection can trap the bot**, unable to gather anything. **A new server
mod can break the bot without breaking the game**: if the bot suddenly cannot
get a block right in front of it, look for `ExceptionInInitializerError` in the
**client** log.

## The logbook: the death nobody saw

A zombie killed the bot while the brain was thinking an answer; the bot carried
a diamond sword. There was no fight because nothing was watching. And afterwards
there was no way to know what had happened: the mod had written a single line in
the whole session.

That produced three rules:
- **The body keeps a logbook**: in memory, a ring of 400 notes, repeated notes
  counted instead of stacked, and dropped notes reported as such. A log that
  loses entries silently lies like a bot that says "done" without doing it.
- **Defense does not go through the model.** It reacts only after taking damage,
  only against what is on top of it, and does not chase.
- **Anything that cannot wait for the next sentence lives in the code** and
  leaves a written trace: defending, getting out of a hole, not crafting items
  that would not fit (checked on a copy of the inventory after spending the
  materials, since using the last log frees the slot the planks go into).

---

## Tests that tell the truth

**`cmd | grep ...` returns grep's exit code.** A grep without matches gives 1 and
paints a green suite red. Save the real exit code before filtering.

**Gradle serves `:test` from the cache and runs nothing** (`FROM-CACHE`,
`BUILD SUCCESSFUL`, zero tests). Use `--rerun`, and count the results: **if not a
single result is seen, it is red.**

**See a new test fail before trusting it.** A suite that passes on the first run
is suspicious: break the code on purpose and check that exactly the right checks
fail.

**Extract what does not need Minecraft.** Request parsing and the chat log left
the server mod's main class so they can be tested in milliseconds. Right away
`?x=hello` started answering "x is not a number" instead of silently meaning 0
and sending the bot to the other side of the world.

---

## Operating the server

**Files you drop into a managed server folder must belong to the user the server
runs as.** A config file copied as root with mode 600 could not be read by the
server (`AccessDeniedException`), while the jar loaded fine. It was caught at once
because the mod **logs the error** instead of silently starting with defaults
(localhost, no token), which would have looked fine while the agent talked to a
door that did not exist.

**Configuration goes in a file, not in JVM arguments.** Server panels manage
those arguments; changing them by hand desyncs the panel from what really runs.

**Wait for NEW data when something restarts.** Three times in one night:
waiting for `Done (` in `latest.log` (it was there from the previous start),
waiting for the mod's "listening" line (same), and waiting for the health
endpoint to answer (the old server was still alive). All three checked a
condition that was already true before starting. Wait for a datum the old
version could not produce, or wait for it to go down and then come back up.

**The HTTP API listens where you tell it, and `127.0.0.1` is rarely what you
want**: that localhost belongs to the game server's machine, not to the agent's.
Outside localhost, a token is mandatory.

## HeadlessMC

**`connect` does not come from the launcher: the `hmc-specifics` mod adds it.**
If the mod did not load, the command does not exist and orders fall into the
void. Before sending anything, wait for its log line, `HMC-Specifics
initialized`, which is also a wait for new data.

**Never launch with `-commands`.** That flag injects HeadlessMC's runtime into
the game, and then **two consoles read the same stdin, stealing each other's
lines**: the runtime (which does not know `connect`) and hmc-specifics (which
does). `help` lists `connect`, and `connect` answers "Couldn't find command"...
sometimes. With a single reader it joins on the first attempt, every time.

**`hmc.gamedir` in the config is respected.** `~/.minecraft` holds versions,
libraries and assets; each bot's game lives in its own gamedir, with its mods in
`<gamedir>/mods/`.

**Do not `cp` over a jar a running client has open.** Each bot's mods are hard
links to the shared jar, so copying over it writes into the same inode. Java
loads classes on demand: everything already loaded keeps working, and the first
class needed after the copy is read from a zip whose index no longer matches,
failing with an `Error` (not an `Exception`, so not even the HTTP server's
`try` caught it; the connection just closed). Deploy with
`launcher/deploy_mod.sh`: copy to a temporary name and `mv`. A new inode: running
bots keep the old jar until their next start, when the sync links the new one.

---

## Dependencies you cannot inspect are walls

Dropping Baritone as a dependency is what made iterating fast. Its jar is
obfuscated and its API does not exist at runtime; it was driven by chat commands,
and whether it had finished was guessed from its chat lines; its mining could
break for a whole session with certain mods, unfixable from outside. Every bug
found afterwards (the A* heuristic, the tower cancelling itself, file permissions,
Gradle's cache) **could be found and fixed** because the code is ours and tests
touch it.

The lesson is not "no dependencies". It is: **a dependency you cannot inspect,
query from code or patch is not a dependency, it is a wall.**

## Being honest about results

**An item can have several recipes.** A stick comes from planks **and** bamboo.
Picking the first recipe is tossing a coin: pick the one the player can actually
make, and list all of them when asked.

**"Done" while the items fall on the ground is a lie.** Crafting with a full
inventory drops the result, which is correct, but answering "made 4" without
saying so makes the bot believe it has items it does not have. Say how many
dropped.

**"9 skipped" is half a report.** Skipping a block has causes fixed in completely
different ways: nothing to rest it on, could not reach it, it did not give way in
time, no permission, no tool that makes it drop. Count them separately and say
them.

**Doing 78% and saying exactly what was missing is better than giving up**, and
much better than claiming everything was done.

## The one that sums it up

**The mod is the expensive part to change**: rebuilding is seconds, but
restarting a client takes minutes. **Put in the mod only what must live inside
the game.** Everything else goes outside, where it changes fast and is tested
without opening Minecraft.

A sustainable project is one where **being wrong is cheap**.

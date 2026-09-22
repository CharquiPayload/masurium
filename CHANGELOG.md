# Changelog

## 1.0.0 (unreleased)

First public release of Marionette.

**One jar for both sides.** The same `marionette-*.jar` goes in the Minecraft
server's `mods/` folder and in each bot client's. The bot half is
`@Mod(dist = Dist.CLIENT)`, so a dedicated server never constructs it, and a test
over the compiled classes fails the build if anything the server loads ever names a
client class. It was two jars during development, which meant a bot and the server
it joined could run different versions and the half that did not understand a
setting ignored it without a word.

### Server half
- HTTP API (token-protected) that answers what a client can only guess: online
  players, entities and dropped items near a point, blocks, chunks, the
  server-side inventory, where an entity is by uuid, the chat with incremental ids.
- Server-side crafting with every loaded recipe, including modded ones, with the
  table-distance rule and honest reports of items dropped on the ground.
- State icons next to bot names in the TAB list.
- `/marionette bot <bot>` commands to shut down, restart or log off a bot, to
  manage its admins and hear list, and to change its behaviour settings, its
  food ban and the blocks it may break by itself. Allowed to its owner, its
  admins, the server console, or players granted permission nodes (LuckPerms
  and the like). The brain has no tool that writes any of it: what it can still
  change by itself is what it learns about the world (places, chests, its diary,
  its own trash list). A setting decided while a bot is off is applied when its
  bridge comes back.
- `/marionette` commands: bot owners, status, an optional sidebar with every bot,
  and a hotbar notice for owners.
- The server console says so, once, when a bot joins running a different version of
  the bot mod than the server's, and `/marionette bot <bot>` shows both.
- `/mods`: every mod the server loaded, with its version, for a launcher to compare
  a bot's pack against before joining. A mismatch used to show up three minutes
  after the connect as "Incompatible client! Please use NeoForge ...".
- Bots do not pick up again what they tossed themselves.

### Launcher
- One command, `launcher/marionette.py`, in Python with no dependencies and no
  shell: `servers`, `create`, `login`, `start`, `connect`, `bridge`, `stop`,
  `restart`, `status`, `deploy-mod`, `doctor`. It replaced eight bash scripts.
- Behind the command, a core that never prints: it reports what it does as
  events and fails with the reason and its evidence, so a window can sit on
  the same functions the command line uses (`launcher/`, a package).
- A start, restart or connect can be cancelled while it waits, and a
  cancelled start stops the client it had launched. Ctrl+C does exactly that.
- `set` sees and changes a bot's settings (language, gender, owner, model,
  escort, heap, account, server, port) through one table that says what each
  accepts and when a change counts; a setting read at start is refused while
  the client runs. `doctor` names hand-edited values `set` would refuse.
  `MARIONETTE_HEAP` and `MARIONETTE_VERSION` replace the old `HEAP` and
  `VERSION`, names generic enough to be set by something else.
- Each server can have its own `servers/<slug>/server.env` (address and token
  of its server mod, readable by its owner only). The launcher, the bridge and
  the MCP server then talk to the server each bot is on, not to the one the
  global `server.env` names; `doctor` checks each server's mod and its pack
  against that server's mods.
- A keeper per bot holds the game's console open and takes lines for it on a
  localhost socket, only from whoever can read its token. It stops the game's
  whole process tree, because HeadlessMC starts the game as a child java, and
  it does so on a SIGTERM too.
- One launcher command at a time per bot, under a lock the kernel frees however
  its holder ends: two `start`s side by side no longer launch two games.
- A pid file is trusted only while its pid is still the process it was written
  for, so a pid left by a killed keeper or bridge and handed to another process
  is neither reported as running nor stopped.
- `start` checks whether the bot runs before it touches its mods or its server,
  and says at once when the keeper could not start the game (no java, say)
  instead of waiting five minutes.
- `doctor` checks the machine, the folders, the jars and the server mod, in the
  order in which things break; `status` says per bot whether the client is there,
  its port is open, the server lists it and its bridge is alive.
- A crashed start quotes the crash report's cause instead of the mod list.
- `start` compares the pack with the server's `/mods` and names the version
  mismatches before connecting; `doctor` reports them per pack. Packs are read
  from the jars themselves, including the mods that ship inside other mods.
- `start` waits for the title screen before the first `connect`: sent while the
  game was still loading, the command talked to nobody, and an attempt was lost
  on every start.
- The game's first-run accessibility prompt is turned off in each bot's `options.txt`:
  it stood in front of the title screen at every start, waiting for a click.

### Add-ons
- `addons/veil`: a jar of its own that keeps Veil (bundled in Sable and others)
  from touching a GPU that a headless bot does not have. Two mixins, applied only
  on a bot with no screen; nothing of Veil's own jar is touched. The core no
  longer carries a mixin against any third-party mod.
- An add-on is for the **exact version** of the mod it is for (`veil [4.3.2]`):
  its mixins reach into that version's internals, and with another one NeoForge
  refuses to load the add-on instead of letting it half-apply. Without the mod
  in the pack, the add-on loads and does nothing.
- The core **refuses to start a headless bot** whose pack carries a mod that
  needs an add-on when the add-on is missing, naming the add-on in the crash
  report instead of letting the game die somewhere in a vertex attribute.
  `doctor` says the same before a java is launched, reading the packs'
  jar-in-jar metadata.

### Bot half
- `/version` also says which screen the client is on, whether it is still loading
  and whether it is in a world, so a launcher knows when `connect` will be heard.
- A* path finder with tick-based costs, partial routes, goal conditions, anti-
  dithering, doors, swimming, bridges, towers and optional tunnelling.
- Long trips in segments, open-water swimming and travel between dimensions
  through noted portals.
- Digging with automatic tool choice, strip mining with vein following and zig-zag
  staircases that are saved and reused.
- Gathering, farming with irrigation, fishing, hunting, shearing, taming,
  breeding and pets, animals on leads, riding horses.
- Chests, furnaces, armor, eating, sleeping and setting the spawn.
- Filling areas and building layered blueprints with per-layer review.
- Escorting players; guards that follow and protect a main bot.
- Arrows are not given away: three that do not lower a target's health and that
  target is left alone, by everything that shoots (escort, lookout and the
  archer's errand), until it hurts the bot.
- Reflexes that never wait for the model: defense, the creeper/phantom/archer
  lookout, retreating when badly hurt, surfacing to breathe, getting out of holes,
  respawning and recovering items after death.
- Per-server memory: places, a diary, people, placed blocks, staircases,
  standing orders, preferences, break whitelist, food blacklist and trash list.
- What the body says by itself without waiting for the brain (a creeper next to
  whoever it escorts, being cornered, what it found exploring) comes out in the
  bot's own language and grammatical gender.
- Body notices that wake the brain, with per-key cooldowns, and an in-memory
  logbook for diagnosis.

### Brain and bridge
- MCP server with the bot's tools; Claude Code sessions per bot with no shell or
  file access.
- Chat bridge with instant stop words, a hear list that keeps strangers away
  from the brain, orders from `/marionette bot` carried out without the model,
  per-bot owner, personality, model and language (English or Spanish).
- Nothing that takes a bot out of the game can be asked through the chat, and
  the one lock the brain still touches (vetoed food) checks the name the bridge
  read in the chat, never one written by the brain.
- One bridge per bot: a second one on the same bot refuses to start instead of
  answering everything twice.
- Internal channel between a bot and its guards.

### Launchers and tests
- Online (a logged-in Minecraft account, `marionette.py login`) or offline bot accounts.
- Launchers to create, start, connect, restart and stop bots, with shared game
  files, per-server mod packs through hard links and safe mod deployment.
- Tests that need no Minecraft: path finder, logbook, request parsing, bridge and
  MCP logic.

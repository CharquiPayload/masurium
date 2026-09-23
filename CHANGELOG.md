# Changelog

## 1.0.0 (unreleased)

First public release of Masurium. During development it was called Marionette;
the name changed before any release because a server plugin with fake players
already used it. Everything that carried the old name carries the new one:
the mod ids (`masurium_server`, `masurium_bot`), the Java packages, the
`/masurium` command, the `MASURIUM_*` variables, `~/.masurium` and the jars.
The launcher is Masurium Launcher. The logo is element 43's tile, the one
masurium would have had.

**One jar for both sides.** The same `masurium-*.jar` goes in the Minecraft
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
- `/masurium bot <bot>` commands to shut down, restart or log off a bot, to
  manage its admins and hear list, and to change its behaviour settings, its
  food ban and the blocks it may break by itself. Allowed to its owner, its
  admins, the server console, the server's operators, or players granted
  permission nodes (LuckPerms and the like, which can also take a node from an
  operator). The brain has no tool that writes any of it: what it can still
  change by itself is what it learns about the world (places, chests, its diary,
  its own trash list). A setting decided while a bot is off is applied when its
  bridge comes back.
- `/masurium` commands: bot owners, status, an optional sidebar with every bot,
  and a hotbar notice for owners.
- The server console says so, once, when a bot joins running a different version of
  the bot mod than the server's, and `/masurium bot <bot>` shows both.
- A bot's rules (its behaviour toggles, the food it does not eat on its own, the
  blocks it may break on its own) in three layers, kept in
  `masurium_bots.json`: its config and what is imposed come from the
  launcher, and its own layer is what `/masurium bot` edits. What is imposed
  cannot be changed from the game, and the command says who imposes it; `pref`,
  `food` and `break` take `default` to go back to what is under, and show who
  decides each thing. The body is handed what the rules come to, whole, and
  holds exactly that. A `masurium_bots.properties` from before is read once
  into the bots' own layer.
- `/rules`: a bot's three layers and what they come to, for the launcher.
- `/mods`: every mod the server loaded, with its version, for a launcher to compare
  a bot's pack against before joining. A mismatch used to show up three minutes
  after the connect as "Incompatible client! Please use NeoForge ...".
- Bots do not pick up again what they tossed themselves.

### Launcher
- **Installers**: a `.deb` for Ubuntu, Debian and their family (Qt for the
  window inside as wheels, so it installs without internet; Python and Java 21
  asked of apt), and `install.sh` for any Linux, for one user and without sudo
  (`--no-gui` for a machine without a screen, `--uninstall`). Both put
  **Masurium Launcher** in the applications menu, with its own icon (element
  43's tile with only its symbol, Ma), and a `masurium` command.
  `tools/release.sh` builds them with the jars.
- **Setting a new machine up**: the window offers it the first time it opens
  (and from Help), `masurium setup` in a terminal. It downloads HeadlessMC and
  hmc-specifics, checked against the checksums of the versions Masurium was
  tested with; puts in the Masurium jars the launcher came with; asks the
  server's Masurium mod before writing `server.env` (readable by its user
  alone); registers the first server with the NeoForge version the server
  runs; and says how to get Java 21 and Claude Code when they are missing.
- The launcher's folders (instances, servers, shared, accounts, groups) live
  in `~/.local/share/masurium` by default instead of straight in the home; a
  machine that already has them in the home keeps using them there.
- One command, `launcher/masurium.py`, in Python with no dependencies and no
  shell: `servers`, `create`, `start`, `connect`, `bridge`, `stop`,
  `restart`, `status`, `deploy-mod`, `doctor`. It replaced eight bash scripts.
- Behind the command, a core that never prints: it reports what it does as
  events and fails with the reason and its evidence, so a window can sit on
  the same functions the command line uses (`launcher/`, a package).
- A start, restart or connect can be cancelled while it waits, and a
  cancelled start stops the client it had launched. Ctrl+C does exactly that.
- `set` sees and changes an instance's settings (name, account, owner, model,
  role, heap, port) through one table that says what each
  accepts and when a change counts; a setting read at start is refused while
  the client runs. `doctor` names hand-edited values `set` would refuse.
  `MASURIUM_HEAP` and `MASURIUM_VERSION` replace the old `HEAP` and
  `VERSION`, names generic enough to be set by something else.
- Each bot is an instance: a player on a server, and everything it is
  (`instances/<instance>/`: its player name, account, personality, picture,
  settings, game, HeadlessMC, logs, port and extra mods). There are no bots
  apart from instances, which was one place too many to set one thing: the
  same character on two servers is a copy of the instance, on its own from
  then on. Instances are copied without questions (`clone`); `start` refuses
  to run one player twice (the same player on one server, or a Microsoft
  account already playing anywhere) and says which instance is in the way.
  Each server's bridges keep their state apart. `migrate` folds the bots of
  before into their instances, with a backup first.
- No Masurium on the server, no bot there. `start` asks the server's mod
  before it loads a game and says what is wrong (not there or not up, or a
  wrong token); and the bot mod itself, a few seconds after joining a server
  whose command tree has no `/masurium`, leaves and closes its game, for a
  bot started without the launcher. A start that sees its client close while
  joining stops waiting at once.
- Accounts, logged in once. `account add` opens HeadlessMC to log a Minecraft
  account in and keeps it in `accounts/<player>/`; an instance set to that
  account plays as its player, and each of its instances links to that one login
  instead of holding a copy that would go stale when HeadlessMC renews it.
  One account plays in one game at a time and its instances start one after
  another. `account` lists them (logged in or not, who uses them), `account
  remove` takes an unused one out, and doctor says when a login is gone.
- `rules`: a bot's rules from the launcher. `rules <instance>` shows every
  toggle and both lists, each with who decides it; with a change it edits the
  instance's own, on its server (waiting for its next start if the server is
  away). `--group` edits what a group imposes on everything in it, `--global`
  what is imposed on every instance (`launcher.json`; `ignore_global` exempts
  one).
  They are sent on every start and to running instances when they change; a
  rule written wrong stops the start and `doctor` names it.
- Groups. A normal group holds instances and other groups, started and
  stopped together (`group start`, `group stop`, with a warning when their
  heaps do not fit). A dependency group holds a leader and its guards, on one
  server: starting a guard starts its leader first, stopping the leader stops
  its guards, and a guard no group names does not start. A leader in a normal
  group stays there, its dependency group taking its place. Groups nest, each
  instance and group in one at most; their settings (`set --group`) and rules
  (`rules --group`) impose on what is inside them, the outer over the inner,
  and a global config (`set --global`, `rules --global`) over all of them.
  `lock` keeps the groups around an instance or a group out, `ignore_global`
  the global config. `groups` shows the tree; `group clone` copies a group
  with copies of every instance in it. The `escort` setting became the
  dependency group, and `migrate` turns one into the other.
- A window, `masurium.py gui` (PySide6, which the command line does not
  need), on the same operations: every instance by group with how it is
  doing, and the actions for the one selected or for a group; the rules
  editor (each toggle and each list with who decides it, imposed ones
  locked, changes collected and sent with Apply), settings per instance,
  bot, group or globally, new instances and groups, moving and cloning,
  the personality, logs that follow, doctor, and accounts logged in through
  HeadlessMC's own questions. Operations run in the background and can be
  cancelled; it is tested drawn offscreen. Instances and groups move by
  dragging them onto a group; a dependency group is drawn as its leader with
  its guards hanging from it; a bot can have a picture; colour styles (dark
  and light, lavender and classic) in the launcher's own settings. It is laid
  out as Prism Launcher's (a bar on top, instances as faces by group, the
  selected one's actions on the right, Edit Instance and Settings windows with
  their pages down the left), with icons of its own, drawn in code; motion
  (animations and animated icons) can be turned off. Settings come in
  sections (the game, the brain, groups), each with an icon and a name a
  person reads, the command line's key under it.
- Mods' ids in a bot's rules: `food ban farmersdelight:tomato`, `break allow
  create:cog`. A name alone is that name in whatever mod has it, and named on
  the server it is looked up: `cog` is kept as `create:cog` when only Create
  has one; a name two mods share, or none has, is refused, saying why.
- A WATUT add-on (`addons/watut`): WATUT marks a player away after minutes
  without a key or a mouse button, which a bot never presses, so a bot looked
  AFK while it worked. Now a bot that moves or acts is not away, and while its
  brain thinks an answer it is shown typing (the bridge tells the body when a
  turn starts and ends, `/thinking`): its chat counts as open and typed in,
  since WATUT draws no bubble over a closed chat. Required, like Veil's, where
  the pack carries WATUT.
- A Memory page for each instance: the places it knows, with their
  coordinates and dimension, and the texts it wrote down, per server, to add,
  change and forget. While it plays a change goes to the bot, which holds its
  memory (its `/places` takes a dimension now, and its `/diary` an entry to
  rewrite or forget); otherwise into its files.
- Offline or a Microsoft account, one switch: an offline instance plays as the
  name it was given (`name`, for private servers), and may play in several
  games at once; one set to a Microsoft account plays as its player. In the
  window, Add Instance and an instance's settings have that switch.
- `java` and `java_args`: the Java an instance's game runs on and extra JVM
  flags, per instance, per group or globally.
- `delete <instance> --yes` (and Delete in the window): an instance out of the
  launcher, its folder and all; refused while it runs or leads
  a dependency group.
- `fast_responses`: whether a bot's brain writes its fast responses (what it
  says without thinking) ahead of time; they are written again when its
  personality changes. Off, they are said in plain English.
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
  whoever it escorts, being cornered, what it found exploring) is said in the
  bot's own voice: its brain writes its version of each sentence once
  (`masurium.py phrases` writes them again), and plain English stands in for
  any it has not written well.
- Body notices that wake the brain, with per-key cooldowns, and an in-memory
  logbook for diagnosis.

### Brain and bridge
- MCP server with the bot's tools; Claude Code sessions per bot with no shell or
  file access.
- Chat bridge with instant stop words, a hear list that keeps strangers away
  from the brain, orders from `/masurium bot` carried out without the model,
  per-bot owner, personality (which says the language it speaks) and model.
- Nothing that takes a bot out of the game can be asked through the chat, and
  the one lock the brain still touches (vetoed food) checks the name the bridge
  read in the chat, never one written by the brain.
- One bridge per bot: a second one on the same bot refuses to start instead of
  answering everything twice.
- Internal channel between a bot and its guards.
- Waiting to be given something counts what came while the brain was still
  thinking: the body notes when each thing reached its backpack (`/received`),
  and the bridge says when it heard the message. A gift handed over faster
  than the answer no longer leaves the bot waiting for another.
- The final answer to a notice from the bot's own body is never said: nobody
  asked, so it is a thought. One went out to the chat as it was, in English and
  mid-fight. What is meant for someone goes with `say`.
- A chat cooldown per bot (`chat_cooldown`, 10 s by default, per instance,
  group or global, and in the window's settings): the least time between two
  things a bot says in the chat on its own. Its answer to someone who has just
  spoken to it always goes out; what its brain tries to say too soon is refused
  and it is told why; what its body would say alone is dropped. The body keeps
  it (`/cooldown`), and the bridge passes the setting on before every turn.

### Launchers and tests
- Offline bots with a name of their own, or a logged-in Microsoft account.
- Launchers to create, start, connect, restart and stop bots, with shared game
  files, per-server mod packs through hard links and safe mod deployment.
- Tests that need no Minecraft: path finder, logbook, request parsing, bridge and
  MCP logic.

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
- Bots do not pick up again what they tossed themselves.

### Bot half
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

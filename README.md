<p align="center"><img src="launcher/gui/logo.png" width="160" alt="Masurium: element 43, Ma"></p>

# Masurium

Minecraft bots with a brain. A Masurium bot joins your server as a regular
player, understands what people ask it in the chat, and does it: it mines,
crafts, farms, fishes, builds from a blueprint, escorts players and fights off
what attacks them. Direct orders are handled instantly; anything ambiguous is
interpreted by an AI model running through [Claude Code](https://claude.com/claude-code).

It is made for people who want to **create their own bots**: give each one a
name, a personality (which says the language it speaks) and a job, link guards to a main bot, and let
them play alongside you.

> **The rule behind everything:** the bot must never be unsure whether it did
> something. It checks the state of the world instead of trusting a message,
> tools answer when they *know*, not when they launched an order, and every
> failure says *what happened*, with numbers.

**To install it**, see [docs/setup.md](docs/setup.md): what you need, how to
build it, and how to get the first bot into the game.

## How it works

| layer | what it does | where |
|---|---|---|
| **Server half** | the source of truth: what is where, who is online, did it happen; server-side crafting | `mod/src/main/java/masurium/server/` |
| **Bot half** | the hands, inside a headless client: walk, dig, place, fight, eat, use chests and furnaces | `mod/src/main/java/masurium/bot/` |
| **Shared half** | what both need and neither owns: the path finder, the logbook, the settings and the phrases | `mod/src/main/java/masurium/common/` |
| **MCP server** | the catalog of tools the brain can call | `mcp/server.py` |
| **Bridge** | reads the chat, wakes the brain when the bot is named, relays body notices | `mcp/bridge.py` |
| **Claude Code** | thinking, only when needed | — |
| **Masurium Launcher** | creates, starts, stops and watches bots; a keeper per bot holds the game's console | `launcher/` |
| **Add-ons** | jars of their own, one per third-party mod the bot has to live with; the first keeps Veil off a GPU that a headless bot does not have | `addons/veil/` |

The split is strict: **a question goes to the server, an action goes to the
bot.** A dedicated server loads only the server and shared halves; the bot half
is `@Mod(dist = Dist.CLIENT)` and is never constructed there. Each bot is a real
Minecraft client without a screen
([HeadlessMC](https://github.com/headlesshq/headlessmc)), so what it does goes
through the normal game rules.

What never goes through the model: stopping (`stop` always works instantly),
self-defense, fleeing creepers, surfacing to breathe, eating when starving,
respawning and recovering items after death. Those live in the bot mod,
because fighting is measured in ticks and a model round trip takes seconds.

## Features

- **Navigation**: its own A* path finder with a tick-based cost table, partial
  routes for long trips, bridges, towers and (optionally) tunnels, doors,
  swimming, portals between dimensions, and honest reports when stuck.
- **Work**: digging, strip mining with vein following, zig-zag staircases,
  gathering, farming with irrigation, fishing, hunting, shearing, taming and
  breeding, chests and furnaces, crafting with every loaded recipe (mods
  included), filling areas and building layered blueprints.
- **Protection**: escorting players, guards that follow their main bot, a
  lookout for creepers, phantoms, archers and low health.
- **Memory** (per server): useful places, a diary of memorable events, what
  each person has done with the bot, standing orders by category, behaviour
  preferences.
- **In-game integration**: state icons in the TAB list, an optional sidebar
  with every bot, a hotbar notice for owners, and `/masurium bot` commands to
  shut down, restart or log off a bot and choose who it listens to.
- **Safety by construction**: the brain has no shell or file access, only the
  bot's tools; shutting down, restarting and logging off are server commands,
  never chat orders, so nobody can talk the brain into them; a hear list keeps
  strangers from reaching the brain at all; breaking blocks to move is limited
  to a whitelist; building and digging are expensive in the path finder so they
  only happen when there is no way on foot.
- **Languages**: a bot speaks the language its personality says, and so do the
  few sentences it says without its brain, which the brain writes once in its
  own voice; the mod, the tools and the brain's instructions are English.

## Tests

```bash
./test.sh              # the MCP layer and the launcher in Python, then the mod: JUnit tests and the jar
mcp/test_brain.sh      # the thinking layer against a fake bot (spends model calls)
```

None of the tests need Minecraft running. The path finder, the logbook, the
request parsing and most of the bridge and MCP logic are tested in milliseconds;
the launcher's keeper is run for real against a fake game that echoes what it
is told.

One of them is worth knowing about before changing anything: `BotSideTest` fails
the build if anything in `masurium.server` or `masurium.common` so much as
names a client class or the bot's half. Those two are all a dedicated server
loads, and a client class touched there does not fail politely — it kills the
startup, on the server of whoever downloaded the mod. It reads the **compiled**
classes rather than the source, so it also catches a fully qualified name written
inline, a lambda, or a return type that no `import` would reveal.

## Documentation

- [docs/setup.md](docs/setup.md): requirements, installation, configuring a bot
  and the in-game commands.
- [docs/architecture.md](docs/architecture.md): every design decision and why,
  including the ones that were discarded.
- [docs/lessons.md](docs/lessons.md): what took nights to learn about the
  Minecraft API, headless clients and testing. Read it before changing the mods.
- [CHANGELOG.md](CHANGELOG.md)

## Roadmap

- A per-bot configuration file generated on first start, with the behaviour
  toggles as `true`/`false`.
- Release builds of the jar.
- **Masurium Launcher**: the command line is done (`launcher/masurium.py`: bots, which
  are characters, and instances, a bot on a server, created, cloned, started,
  stopped and watched; settings in layers, bot < instance; each server with its
  own server mod; it compares the pack with the server's `/mods` before
  joining, and refuses to run one player twice; accounts logged in once and
  shared by a bot's instances; a bot's rules — toggles, food, blocks — in three
  layers kept on the server and editable from the launcher and the game, live;
  groups, plain ones that start together and a leader with its guards, nested,
  with settings and rules that impose on what is inside them, and a global
  config over all). A window on the same code (`masurium.py gui`, PySide6):
  the instances by group, their actions, rules, settings and logs. To do: a run
  on Windows.
- **Add-ons**: separate jars that teach the bots one mod each. The first one is
  there, `addons/veil` (compatibility: Veil without a GPU); the next ones teach
  *abilities* (`masurium-create`, `masurium-watut`...). To do for those: the
  core offers them a place to register their own `/masurium bot <bot> <add-on>
  ...` subcommands and their own per-bot settings, kept where the rest of the
  per-bot settings live and handed to the bridge in the same poll, so an add-on
  needs no server of its own.
- A documented way to **try a bot without HeadlessMC**, launching the client from
  a normal launcher, so the first bot does not require the whole setup.
- Consecutive notices from the body sent in **one turn** instead of one per turn,
  which reads as the bot answering twice and spends turns.
- Emptying into a **chest** instead of dropping items on the ground.
- **Pluggable brain** (to do): besides Claude Code, support other chat-completions
  backends, such as the Anthropic API and OpenAI-compatible APIs (OpenRouter,
  Ollama, LM Studio). Smaller or local models will likely need a reduced tool
  catalog.

## Where to use it

**Only on private servers, or on servers whose owners explicitly allowed your
bots.** Never take a Masurium bot to a public server such as Hypixel:

- **You break their rules.** Public networks forbid bots and automated clients;
  the account gets banned, and you are the one breaking the rules.
- **Unbounded consumption.** Every message that names the bot can wake its
  brain. Strangers can spam it on purpose and burn your AI quota or your API
  bill.
- **Prompt injection.** The chat is untrusted input. The brain has no shell or
  file access, but other players can still try to talk the bot into tossing
  your items, breaking blocks it is allowed to break, or following them away.
- **Privacy.** What players say near the bot is sent to the AI provider.

Use it with people you trust, on a server you run or have permission for,
ideally with a whitelist.

## Acknowledgements

- **[Baritone](https://github.com/cabaletta/baritone)** (LGPL-3.0). Masurium's
  navigation was designed after studying Baritone: partial routes ("best so
  far"), goals as conditions, costs measured in ticks, time-bounded searches and
  favoring recent paths. The ideas were reimplemented from scratch; no Baritone
  code is included.
- **[HeadlessMC](https://github.com/headlesshq/headlessmc)** and
  **[hmc-specifics](https://github.com/headlesshq/hmc-specifics)** by 3arthqu4ke and
  HeadlessHQ (MIT), which make it possible to run the bots as real clients without a
  screen. They are used as external tools and are not bundled.
- **[Prism Launcher](https://prismlauncher.org/)** (GPL-3.0). Masurium began as a
  client mod run from Prism, one instance per bot, and its launcher follows Prism's
  lead: instances in groups, a panel of actions for the one selected, settings per
  instance over shared defaults. Nothing of Prism's code is used.
- [NeoForge](https://neoforged.net/) and the Minecraft modding community.
- [Claude Code](https://claude.com/claude-code) by Anthropic, the brain.

Masurium is an independent project. It is not affiliated with, endorsed by or
sponsored by the Prism Launcher project, HeadlessMC or HeadlessHQ, or the Baritone
project, and it is **not an official Minecraft product: not approved by or
associated with Mojang or Microsoft**. Their names are used only to say where an
idea came from or what Masurium works with; their logos and assets are not used.

## The name

Masurium is the name three chemists gave element 43 in 1925, with the symbol
**Ma**. Their discovery could not be confirmed, and the element is known today as
technetium, so the name was free for a project to borrow, symbol included. The
logo is its tile in the periodic table.

## License

[MIT](LICENSE) © 2026 CharquiPayload

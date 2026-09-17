# Marionette

Minecraft bots with a brain. A Marionette bot joins your server as a regular
player, understands what people ask it in the chat, and does it: it mines,
crafts, farms, fishes, builds from a blueprint, escorts players and fights off
what attacks them. Direct orders are handled instantly; anything ambiguous is
interpreted by an AI model running through [Claude Code](https://claude.com/claude-code).

It is made for people who want to **create their own bots**: give each one a
name, a personality, a language and a job, link guards to a main bot, and let
them play alongside you.

> **The rule behind everything:** the bot must never be unsure whether it did
> something. It checks the state of the world instead of trusting a message,
> tools answer when they *know*, not when they launched an order, and every
> failure says *what happened*, with numbers.

## Where to use it

**Only on private servers, or on servers whose owners explicitly allowed your
bots.** Never take a Marionette bot to a public server such as Hypixel:

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

## How it works

| layer | what it does | where |
|---|---|---|
| **Server mod** | the source of truth: what is where, who is online, did it happen; server-side crafting | `mod-server/` |
| **Bot mod** | the hands, inside a headless client: walk, dig, place, fight, eat, use chests and furnaces | `mod-bot/` |
| **MCP server** | the catalog of tools the brain can call | `mcp/server.py` |
| **Bridge** | reads the chat, wakes the brain when the bot is named, relays body notices | `mcp/bridge.py` |
| **Claude Code** | thinking, only when needed | — |

The split is strict: **a question goes to the server, an action goes to the
bot.** Each bot is a real Minecraft client without a screen
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
  with every bot, a hotbar notice for owners, `/marionette` admin commands.
- **Safety by construction**: the brain has no shell or file access, only the
  bot's tools; shutting a bot down requires its admin list; breaking blocks to
  move is limited to a whitelist; building and digging are expensive in the
  path finder so they only happen when there is no way on foot.
- **Languages**: bots talk in English or Spanish (`language` file per bot);
  the brain and tools always work in English.

## Requirements

**Minecraft server**
- Minecraft **1.21.1** with **NeoForge 21.1.x**.
- The server mod: `marionette-server-<version>.jar`.

**Accounts: bots work online or offline**
- **Online (recommended):** each bot uses its own purchased Minecraft Java
  account (a Microsoft account), logged in once through HeadlessMC, and the
  server keeps `online-mode=true`. One license per bot.
- **Offline:** only on a private server you control with `online-mode=false`,
  for example for testing. Anyone could join such a server with any name, so
  keep it on a private network or behind a whitelist. HeadlessMC itself states
  that offline accounts are meant for running the game headlessly in CI/CD
  pipelines, not for playing without having bought Minecraft.

**Bot machine** (can be the same machine)
- Linux with Java **21**, Python **3.9+** (standard library only), `curl` and
  `ss` (iproute2).
- [Claude Code](https://claude.com/claude-code), installed and logged in for the
  user that runs the bots.
- [HeadlessMC](https://github.com/headlesshq/headlessmc) launcher jar and the
  [hmc-specifics](https://github.com/headlesshq/hmc-specifics) mod for 1.21.1
  NeoForge.
- About **3 GB of RAM per bot** (each bot is a full Java client; `HEAP` sets it).
- Network access from the bot machine to the server's game port and to the
  server mod's HTTP port (8477 by default).

## Quick start

### 1. Build the mods

```bash
(cd mod-server && ./gradlew build)   # mod-server/build/libs/marionette-server-*.jar
(cd mod-bot && ./gradlew build)      # mod-bot/build/libs/marionette-bot-*.jar
```

### 2. Install the server mod

Put `marionette-server-*.jar` in the server's `mods/` folder and start the
server once. It writes `marionette.properties` next to the server jar:

```properties
host=127.0.0.1
port=8477
token=
bots=
```

`host` is the address the HTTP API listens on: set it to one the bot machine
can reach. As soon as it is not `127.0.0.1`, choose a long random `token`:
anyone who reaches that port with the token can command the bots. `bots` lists
the player names that are bots, comma separated. Restart the server afterwards.
Java properties files do not support comments at the end of a line, so keep
comments on their own lines.

### 3. Prepare the bot machine

```text
~/.marionette/server.env        connection to the server mod
~/shared/headlessmc-launcher.jar
~/shared/mods/                  marionette-bot-*.jar and hmc-specifics-*.jar
~/servers/<slug>/server.conf    one folder per server you connect to
~/servers/<slug>/mods/          client-side mods that server requires (may be empty)
~/bots/                         created by the launcher, one folder per bot
```

`~/.marionette/server.env`:

```bash
# where the server mod listens, and its token
MARIONETTE_HOST=192.168.1.10
MARIONETTE_PORT=8477
MARIONETTE_TOKEN=the-same-token
# optional: the player who administers the bots
MARIONETTE_OWNER=YourPlayerName
```

`~/servers/<slug>/server.conf`:

```bash
# game server address
HOST=192.168.1.10
MC_PORT=25565
# a NeoForge version available in HeadlessMC
VERSION=neoforge-21.1.248
DESCRIPTION="My survival server"
```

The folders can be moved with `MARIONETTE_BOTS_DIR`, `MARIONETTE_SERVERS_DIR`,
`MARIONETTE_COMMON_DIR` and `MARIONETTE_ENV`. After building, `launcher/deploy_mod.sh`
copies the bot mod into `shared/mods` safely, even with bots running.

### 4. Create and start a bot

```bash
launcher/create_bot.sh Alice <slug>
launcher/login_bot.sh Alice            # once: type `login`, follow the steps, then `quit`
launcher/restart_bot.sh Alice          # starts the client and its bridge
```

For an offline bot on a private server with `online-mode=false`, create it with
`MARIONETTE_ACCOUNT=offline launcher/create_bot.sh Alice <slug>` and skip the
login.

Then say its name in the chat: `Alice, come here`.

Other launchers: `start_bot.sh` (client only), `connect_bot.sh` (rejoin after a
log off), `stop_bot.sh` (client, bridge and its guards). Bridge logs go to
`/tmp/bridge_<bot>_out`.

## Configuring a bot

Each bot is a folder in `~/bots/<name>/`. Every file is optional except `port`
and `server`, which the launcher writes:

| file | meaning |
|---|---|
| `personality.txt` | who the bot is, in second person; goes at the start of its prompt |
| `language` | `en` or `es`: the language it speaks in the chat |
| `account` | `online` (a logged-in Minecraft account) or `offline` (private servers only) |
| `favorite` | the player whose delicate orders it accepts; also seeds its admin list |
| `model` | the model and effort of its brain, e.g. `sonnet` or `haiku low` (default `opus medium`) |
| `escort` | the name of another bot: this bot becomes that bot's **guard** |
| `gender` | `m` or `f`, for languages with grammatical gender |

**Main bots and guards.** A main bot takes orders and does jobs. A guard is a
bot whose `escort` file names a main bot: it follows and protects it, sleeps
when it sleeps, shares its break whitelist, steps aside when it is in the way,
and is stopped together with it. Guards mostly react, so a smaller model
(`haiku low`) works well for them.

Most behaviour is changed by talking to the bot: preferences such as
`build_while_following`, `hunt_players` or `defend_from_players`, which blocks it
may break to get through, which food it must not eat, and standing orders
("if you run out of fuel, take it from the wooden chest"). These are stored per
server in the client's `config/` folder.

## Server commands

| command | who | what |
|---|---|---|
| `/marionette owner <bot>` | anyone | who owns a bot |
| `/marionette owner <bot> <player\|none>` | operators | assign or remove the owner |
| `/marionette owners` | anyone | the whole list |
| `/marionette status` | anyone | what each bot is doing, with health and position |
| `/marionette hud on\|off` | players | your bots' state icons above your hotbar |
| `/marionette scoreboard on\|off` | operators | a sidebar with every bot's state |

## Tests

```bash
./test.sh              # Python checks, server-mod JUnit tests, bot mod build
mcp/test_brain.sh      # the thinking layer against a fake bot (spends model calls)
```

None of the tests need Minecraft running. The path finder, the logbook, the
request parsing and most of the bridge and MCP logic are tested in milliseconds.

## Documentation

- [docs/architecture.md](docs/architecture.md): every design decision and why,
  including the ones that were discarded.
- [docs/lessons.md](docs/lessons.md): what took nights to learn about the
  Minecraft API, headless clients and testing. Read it before changing the mods.
- [CHANGELOG.md](CHANGELOG.md)

## Roadmap

- `create_bot.sh --role main|guard` instead of editing `escort` by hand.
- A per-bot configuration file generated on first start, with the behaviour
  toggles as `true`/`false`.
- Release builds of both jars.
- **Pluggable brain** (to do): besides Claude Code, support other chat-completions
  backends, such as the Anthropic API and OpenAI-compatible APIs (OpenRouter,
  Ollama, LM Studio). Smaller or local models will likely need a reduced tool
  catalog.

## Acknowledgements

- **[Baritone](https://github.com/cabaletta/baritone)** (LGPL-3.0). Marionette's
  navigation was designed after studying Baritone: partial routes ("best so
  far"), goals as conditions, costs measured in ticks, time-bounded searches and
  favoring recent paths. The ideas were reimplemented from scratch; no Baritone
  code is included.
- **[HeadlessMC](https://github.com/headlesshq/headlessmc)** and
  **[hmc-specifics](https://github.com/headlesshq/hmc-specifics)** by 3arthqu4ke and
  HeadlessHQ (MIT), which make it possible to run the bots as real clients without a
  screen. They are used as external tools and are not bundled.
- [NeoForge](https://neoforged.net/) and the Minecraft modding community.
- [Claude Code](https://claude.com/claude-code) by Anthropic, the brain.

## License

[MIT](LICENSE) © 2026 CharquiPayload

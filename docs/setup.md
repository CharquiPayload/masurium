# Setting up Marionette

Everything you have to do to get a bot playing. The [README](../README.md) says
what Marionette is and how it is put together; this page is the part with the
commands in it.

If you only want to see it work, read **Requirements** and **Quick start**, in
that order. The rest can wait until you have a bot in the game.

## Requirements

**Minecraft server**
- Minecraft **1.21.1** with **NeoForge 21.1.x**.
- The mod: `marionette-<version>.jar`, the same file the bots use.

**If the server's pack carries Veil** (it ships inside Sable, among others), the
bots also need `marionette-veil-<version>.jar` in `shared/mods/`: a headless
client refuses to start without it, saying so, and `doctor` says so before that.
The add-on is for one exact Veil version; with another, NeoForge refuses to load
it and a new add-on version is due. See `addons/veil/README.md`. The server does
not need it.

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
- Java **21** and Python **3.9+** (standard library only). The launcher is
  written for every platform Python runs on, but so far it has only been run
  on Linux.
- [Claude Code](https://claude.com/claude-code), installed for the user that runs
  the bots, with a way to pay for it — see **The brain** below. A Claude
  subscription is **not** required.
- [HeadlessMC](https://github.com/headlesshq/headlessmc) launcher jar and the
  [hmc-specifics](https://github.com/headlesshq/hmc-specifics) mod for 1.21.1
  NeoForge.
- About **3 GB of RAM per bot** (each bot is a full Java client; a bot's `heap`
  file, or `MARIONETTE_HEAP` for all of them, sets it).
- Network access from the bot machine to the server's game port and to the
  server mod's HTTP port (8477 by default).

## The brain

Every bot thinks through [Claude Code](https://claude.com/claude-code), which the
bridge runs once per turn. It has to be installed on the **same machine as the
bot client**, because the bot mod listens only on `127.0.0.1` and the bridge
talks to it there. The brain lives where the hands live.

**A subscription is not required.** Claude Code checks that it has credentials,
not that you pay monthly. There are four ways to give it some, and any one is
enough:

| How | What it needs |
|---|---|
| Claude subscription | a Pro or Max plan, logged in with `/login` |
| Anthropic API key | pay-as-you-go, no plan |
| Amazon Bedrock | `CLAUDE_CODE_USE_BEDROCK=1` and AWS credentials |
| Google Vertex | `CLAUDE_CODE_USE_VERTEX=1` and GCP credentials |

**Do not put the key in a settings file.** Claude Code takes `apiKeyHelper`, a
command that prints the key when it is needed, so the key itself stays in one
file you control:

```json
{
  "apiKeyHelper": "cat ~/.marionette/api.key"
}
```

```bash
printf '%s' 'sk-ant-...' > ~/.marionette/api.key
chmod 600 ~/.marionette/api.key
```

Rotating a key is then overwriting that one file, with nothing else to change.

**If it says "Not logged in", that message is about credentials in general, not
about the subscription.** A dead API key and an expired session produce the same
sentence, and it sends you to `/login` when what is missing may be the key.

### Other providers

`ANTHROPIC_BASE_URL` points Claude Code at something other than Anthropic, and
gateways exist that speak the Anthropic protocol and route onward to other
models. It works — the agentic loop and the tool calls survive the trip.

Two things to weigh before relying on it. **Check the terms of the tools you are
using**: this project does not tell you whether running Claude Code against
somebody else's model is allowed, because it does not know. And a model that
merely supports tool calls is not the same as a model that can run a bot: there
are over a hundred tools here, and using them well means chaining several in a
row — look, decide, move, check. Smaller models are likely to need a reduced
catalog, which does not exist yet.

The documented path is the table above. Anything else is yours to verify.

## Quick start

### 1. Build the mod

```bash
(cd mod && ./gradlew build)   # mod/build/libs/marionette-*.jar
```

**One jar, both halves.** The same file goes in the Minecraft server's `mods/`
folder and in each bot client's. On a dedicated server the bot half is never
constructed (it is `@Mod(dist = Dist.CLIENT)`), and on a client the server half
stays asleep unless you open a single-player or LAN world.

If you are updating from a version that shipped two jars, take
`marionette-server-*.jar` and `marionette-bot-*.jar` **out** of the folder: two
jars declaring the same mod and the game will not start.

### 2. Install it on the server

Put `marionette-*.jar` in the server's `mods/` folder and start the
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
~/shared/mods/                  marionette-*.jar and hmc-specifics-*.jar
~/servers/<slug>/server.conf    one folder per server you connect to
~/servers/<slug>/mods/          client-side mods that server requires (may be empty)
~/bots/                         created by the launcher: one folder per bot (a character)
~/instances/                    created by the launcher: one folder per instance (a bot on a server)
```

`~/.marionette/server.env`:

```bash
# where the server mod listens, and its token
MARIONETTE_HOST=192.168.1.10
MARIONETTE_PORT=8477
MARIONETTE_TOKEN=the-same-token
# optional: the player who runs the bots; also the owner of any bot
# without an owner file
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

**More than one server.** `server.env` points at one server mod. When the bots
play on several servers, give each server its own
`~/servers/<slug>/server.env`, with the same three lines for *its* server mod,
and `chmod 600` it: it holds that server's token. It is kept apart from
`server.conf` on purpose, so the address, version and pack can be shown and
passed around without the token. The launcher then looks for each bot in its
own server's `/players` and compares its pack with that server's `/mods`, and
the bridge and the MCP server talk to that server (the launcher tells them
the file's path, never the token). A server without a file of its own uses
`server.env`, which also keeps what only it says, such as `MARIONETTE_OWNER`.
`doctor` checks each server's mod, and that each such file is readable by you
alone.

The folders can be moved with `MARIONETTE_BOTS_DIR`, `MARIONETTE_INSTANCES_DIR`,
`MARIONETTE_SERVERS_DIR`, `MARIONETTE_COMMON_DIR` and `MARIONETTE_STATE_DIR`
(where the bridges keep their sessions and channels, `~/.marionette` by
default), either in the environment or as more lines of `server.env`
(`MARIONETTE_ENV` says where that file is). After building,
`launcher/marionette.py deploy-mod` copies the mod into `shared/mods` safely,
even with bots running, and clears out any older jar that would declare the
same mod twice. `MARIONETTE_JAVA` points the bots at a particular `java` when
the one on the PATH is not 21.

### 4. Create and start a bot

Two things to tell apart. A **bot** is a character: its name in the game, its
personality, its settings (`bots/<bot>/`). An **instance** is a bot on a
server, and it is what starts and stops: its game folder, its HeadlessMC, its
logs, its port (`instances/<instance>/`). One bot can have several instances,
on several servers.

Everything goes through one command, `launcher/marionette.py`:

```bash
launcher/marionette.py doctor            # the machine, the folders, the server: what is missing
launcher/marionette.py create Alice <slug>   # the bot Alice and the instance alice on that server
launcher/marionette.py login alice       # once: type `login`, follow the steps, then `quit`
launcher/marionette.py restart alice     # starts the client and, once it is in, its bridge
```

For an offline bot on a private server with `online-mode=false`, create it with
`--account offline` and skip the login.

Then say its name in the chat: `Alice, come here`.

The rest of the subcommands: `status` (every instance: client, hands, in the
server, bridge), `bots`, `servers`, `start` (the client only), `connect`
(rejoin after a log off), `bridge` (the bridge only), `stop` (client, bridge and
its running guards; `--keep-guards` leaves the guards), `set` and `deploy-mod`.

**Cloning.** `clone alice` makes `alice-1`, the same bot on the same server;
`clone alice --server other` puts it on another. `clone-bot alice` makes a new
bot from Alice (`alice-1`, playing as `Alice_1`) with her settings and
personality; `create Alice_1 <slug> --bot alice-1` then gives it an instance.
Clones are made without questions: two instances may even be the same player
on the same server. What cannot happen is both **running**, and `start` is
where that is refused, saying which instance is in the way:

- the same player on the same server;
- an **online** account already playing anywhere: one Microsoft account plays
  in one game at a time (an offline player may be on two servers at once);
- on one server, two players whose names contain one another: the bridge
  reacts when its name is said, and calling one would wake both.

Ctrl+C during `start`, `restart` or `connect` cancels: the launcher stops the
client it had started instead of leaving a game loading in the background, and
exits with 130. A second Ctrl+C leaves at once.

A running instance leaves its tracks in `instances/<instance>/run/`:
`client.log` (the game), `bridge.log` (the brain's side), `keeper.log`, and the
pid files. The **keeper** is a small process per instance, started by `start`,
that holds the game's console open and takes lines for it on a localhost
socket; it is how the launcher knows an instance is already running instead of
starting it twice. While a command works on an instance it holds
`run/launcher.lock`, and a second command on it is refused until the first one
ends.

**From the layout before instances.** Bots created before instances existed
kept their game in their own folder. `marionette.py migrate --dry-run` says
what it would do, and `marionette.py migrate` turns each one into a bot and an
instance of the same name: the game folders are moved, not copied; what its
bridge kept goes to its server's state folder; and a backup of every small file
goes first to `<state>/backups/`. A bot that is running is left alone until it
is stopped.

## Configuring a bot

Settings live in two layers: the **bot's** (`bots/<bot>/bot.json`), for every
instance of it, and the **instance's** (`instances/<instance>/instance.json`),
which wins over the bot's. `marionette.py set` changes them with a check first
and says when the change counts; `doctor` names any value, edited by hand, that
`set` would refuse.

```bash
launcher/marionette.py set alice                      # every setting, its value, and where it comes from
launcher/marionette.py set --bot alice model sonnet   # for every instance of the bot
launcher/marionette.py set alice model haiku low      # for this instance only
launcher/marionette.py set alice heap --default       # out of the instance: the bot's, or the default
```

The personality is a text file of its own, `bots/<bot>/personality.txt`: who
the bot is, in second person, **including the language it speaks** with
players and how; it goes at the start of its prompt.

| setting | layers | meaning |
|---|---|---|
| `account` | bot, instance | `online` (a logged-in Minecraft account) or `offline` (private servers only) |
| `owner` | bot, instance | the player the bot belongs to: it accepts their delicate orders, and they control it with `/marionette bot` on any server. It cannot be changed from inside the game |
| `model` | bot, instance | the model and effort of its brain, e.g. `sonnet` or `haiku low` (default `opus medium`) |
| `heap` | bot, instance | the game's memory, e.g. `3g` (default `MARIONETTE_HEAP`, else `3g`) |
| `escort` | instance | the player name of another bot on its server: this one becomes that bot's **guard** |
| `port` | instance | the local port of the bot mod, chosen by the launcher |

The launcher writes what the bridge reads into the instance's folder on every
start and every change, one small file per setting (`model`, `owner`...):
those are an output, not a place to edit.

**What it says without its brain.** A few sentences are said without asking
the brain: the urgent ones of the body (a creeper next to the player it
escorts, being cornered) and a few of the bridge's (shutting down, busy). They
are plain English in the code. The first time its bridge starts, the brain
writes its own version of each, in its voice and its language, into
`gamedir/config/marionette-phrases.properties`, and those are said from then
on. They are written again only on request, after changing the personality
for instance:

```bash
launcher/marionette.py phrases alice
```

A version that lost a placeholder (the distance of the creeper, say) is not
used: that sentence is said in English.

**Main bots and guards.** A main bot takes orders and does jobs. A guard is an
instance whose `escort` names a main bot on its server: it follows and protects it, sleeps
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
| `/marionette bot <bot>` | anyone | owner, admins, who it hears, whether its bridge answers, and which version of the bot mod it runs |
| `/marionette bot <bot> shutdown` | owner, admins | stop the client and its bridge |
| `/marionette bot <bot> restart` | owner, admins | restart the client and its bridge |
| `/marionette bot <bot> logoff` | owner, admins | leave the server, keeping the client running |
| `/marionette bot <bot> hear on\|off` | owner, admins | hear only its list, or everyone |
| `/marionette bot <bot> hear add\|remove <player>` | owner, admins | edit the hear list |
| `/marionette bot <bot> hear list` | anyone | the hear list and whether it is on |
| `/marionette bot <bot> admins add\|remove <player>` | owner | edit the admins |
| `/marionette bot <bot> pref` | anyone | every behaviour setting with its value |
| `/marionette bot <bot> pref <key>` | anyone | what one setting does, and how it stands |
| `/marionette bot <bot> pref <key> on\|off` | owner, admins | switch a setting |
| `/marionette bot <bot> food` | anyone | what it will not eat on its own |
| `/marionette bot <bot> food ban\|allow <item>` | owner, admins | edit the food ban |
| `/marionette bot <bot> break` | anyone | what it may break by itself to make its way |
| `/marionette bot <bot> break allow\|forbid <block>` | owner, admins | edit that whitelist |
| `/marionette owners` | anyone | every bot with its owner |
| `/marionette status` | anyone | what each bot is doing, with health and position |
| `/marionette hud on\|off` | players | your bots' state icons above your hotbar |
| `/marionette scoreboard on\|off` | operators | a sidebar with every bot's state |

**Who controls a bot.** Its **owner** comes from the bot's own `owner` file,
so it follows the bot to any server. The owner names **admins** on each server.
Being a server operator gives no control over someone else's bot. The server
console can always run these commands, and command blocks never can.

Shutting down, restarting and logging off are **only** server commands. Asking
a bot in the chat gets you the command, not the action, even from its owner:
the server knows for sure who runs a command, while a name in the chat reaches
the brain through words a player can fake.

**Settings, food and breaking, too.** The same holds for the behaviour
settings, the food ban and the break whitelist. They used to be changed by
asking the bot, and the brain's tools for it said "only on the owner's order" —
which was a sentence in a prompt with nothing enforcing it. The brain now has
tools that only *read* them, so it still knows its own rules and can tell you
what they are; changing them is `pref`, `food` and `break` above. A setting
decided while the bot is off is kept and applied when it comes back.

What the bot writes about the **world** stays its own: the places it
remembers, the chests it annotates, its diary, what it learns about people, and
its trash list, which only governs what it drops from its own backpack. It may
write down what it discovers; it may not change what it is allowed to do.

**Hear list.** It works like the vanilla `/whitelist`. By default it is off
and a bot hears everyone who names it. With `hear on` it only hears its owner,
its admins, other bots and the players on its list. Anyone else is ignored before the brain, so they cost no tokens and
cannot inject anything.

**Versions.** A bot is a separate installation from the server: it can join one
built from another version, and the half that does not understand a setting
ignores it without a word. Its bridge reports the bot mod's version in every
poll; when it differs from the server's, the server console says so once and
`/marionette bot <bot>` shows both. A bridge older than this check reports no
version, and nothing is claimed about it.

**Permission nodes.** Every action has a node for permission mods such as
LuckPerms: `marionette.bot.shutdown`, `marionette.bot.restart`,
`marionette.bot.logoff`, `marionette.bot.hear`, `marionette.bot.admins`,
`marionette.bot.pref`, `marionette.bot.food` and `marionette.bot.break`.
Nobody has them by default. Granting one lets that player use it on every bot.

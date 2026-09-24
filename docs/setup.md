# Setting up Masurium

The server's side: the Masurium mod in your Minecraft server, its
configuration, and the in-game commands. The bots themselves run from
[Masurium Launcher](https://github.com/CharquiPayload/masurium-launcher): [its setup](https://github.com/CharquiPayload/masurium-launcher/blob/main/docs/setup.md) takes it from there, creating and
starting them. The [README](../README.md) says what Masurium is and how it is
put together.

## Requirements

**Minecraft server**
- Minecraft **1.21.1** with **NeoForge 21.1.x**.
- The mod: `masurium-<version>.jar`, the same file the bots use.

**If the server's pack carries Veil** (it ships inside Sable, among others), the
bots also need `masurium-veil-<version>.jar` beside the mod: a headless client
refuses to start without it, saying so, and the launcher's `doctor` says so
before that.
The add-on is for one exact Veil version; with another, NeoForge refuses to load
it and a new add-on version is due. See
[masurium-veil](https://github.com/CharquiPayload/masurium-veil), where its
releases are. Masurium Launcher's releases carry the add-ons, and its
`masurium setup` puts them in `shared/mods/` with the mod. The server does not
need them.

**Accounts: bots work online or offline**
- **Online (recommended):** each bot uses its own purchased Minecraft Java
  account (a Microsoft account), logged in once through HeadlessMC, and the
  server keeps `online-mode=true`. One license per bot.
- **Offline:** only on a private server you control with `online-mode=false`,
  for example for testing. Anyone could join such a server with any name, so
  keep it on a private network or behind a whitelist. HeadlessMC itself states
  that offline accounts are meant for running the game headlessly in CI/CD
  pipelines, not for playing without having bought Minecraft.

**The bots** run on a machine of their own (it can be the same one), from
[Masurium Launcher](https://github.com/CharquiPayload/masurium-launcher), with Java 21 and [Claude Code](https://claude.com/claude-code):
[its setup](https://github.com/CharquiPayload/masurium-launcher/blob/main/docs/setup.md) lists what that machine needs.

## Quick start

### 1. Build the mod

```bash
(cd mod && ./gradlew build)   # mod/build/libs/masurium-*.jar
```

**One jar, both halves.** The same file goes in the Minecraft server's `mods/`
folder and in each bot client's. On a dedicated server the bot half is never
constructed (it is `@Mod(dist = Dist.CLIENT)`), and on a client the server half
is never constructed either (`@Mod(dist = Dist.DEDICATED_SERVER)`). So a player
who has the jar sees nothing of it, and a single-player world, opened to LAN or
not, cannot host bots: they join dedicated servers.

If you are updating from a version that shipped two jars, take
`masurium-server-*.jar` and `masurium-bot-*.jar` **out** of the folder: two
jars declaring the same mod and the game will not start.

### 2. Install it on the server

Put `masurium-*.jar` in the server's `mods/` folder and start the
server once. It writes `masurium.properties` next to the server jar:

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

### 3. The bots

With the mod on the server and its `masurium.properties` set, the bots come
from [Masurium Launcher](https://github.com/CharquiPayload/masurium-launcher) on the machine that runs them: [its setup](https://github.com/CharquiPayload/masurium-launcher/blob/main/docs/setup.md)
installs it, connects it to this server (its `host`, `port` and `token`) and
creates and starts the first bot. How a bot is configured, its groups and its
rules are there too.

## Server commands

| command | who | what |
|---|---|---|
| `/masurium bot <bot>` | anyone | owner, admins, who it hears, whether its bridge answers, and which version of the bot mod it runs |
| `/masurium bot <bot> shutdown` | owner, admins | stop the client and its bridge |
| `/masurium bot <bot> restart` | owner, admins | restart the client and its bridge |
| `/masurium bot <bot> logoff` | owner, admins | leave the server, keeping the client running |
| `/masurium bot <bot> hear on\|off` | owner, admins | hear only its list, or everyone |
| `/masurium bot <bot> hear add\|remove <player>` | owner, admins | edit the hear list |
| `/masurium bot <bot> hear list` | anyone | the hear list and whether it is on |
| `/masurium bot <bot> admins add\|remove <player>` | owner | edit the admins |
| `/masurium bot <bot> pref` | anyone | every behaviour setting with its value, and who decides it |
| `/masurium bot <bot> pref <key>` | anyone | what one setting does, and how it stands |
| `/masurium bot <bot> pref <key> on\|off\|default` | owner, admins | switch a setting, or take it back to what applies without it |
| `/masurium bot <bot> food` | anyone | what it will not eat on its own |
| `/masurium bot <bot> food ban\|allow\|default <item>` | owner, admins | edit the food ban |
| `/masurium bot <bot> break` | anyone | what it may break by itself to make its way |
| `/masurium bot <bot> break allow\|forbid\|default <block>` | owner, admins | edit that whitelist |
| `/masurium owners` | anyone | every bot with its owner |
| `/masurium status` | anyone | what each bot is doing, with health and position |
| `/masurium hud on\|off` | players | your bots' state icons above your hotbar |
| `/masurium scoreboard on\|off` | operators | a sidebar with every bot's state |

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
what they are; changing them is `pref`, `food` and `break` above. They edit
the bot's **own** rules (see [Rules](#rules)); what the launcher imposes is
refused, saying who imposes it. A change made while the bot is off is kept and
applied when it comes back.

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
`/masurium bot <bot>` shows both. A bridge older than this check reports no
version, and nothing is claimed about it.

**Permission nodes.** Every action has a node for permission mods such as
LuckPerms: `masurium.bot.shutdown`, `masurium.bot.restart`,
`masurium.bot.logoff`, `masurium.bot.hear`, `masurium.bot.admins`,
`masurium.bot.pref`, `masurium.bot.food` and `masurium.bot.break`.
By default the server's operators (`/op`) have them all and nobody else does.
A permissions mod decides otherwise: granting a node lets that player use it on
every bot, and setting it to false takes it from an operator.

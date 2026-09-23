# Setting up Masurium

Everything you have to do to get a bot playing. The [README](../README.md) says
what Masurium is and how it is put together; this page is the part with the
commands in it.

If you only want to see it work, read **Requirements** and **Quick start**, in
that order. The rest can wait until you have a bot in the game.

## Requirements

**Minecraft server**
- Minecraft **1.21.1** with **NeoForge 21.1.x**.
- The mod: `masurium-<version>.jar`, the same file the bots use.

**If the server's pack carries Veil** (it ships inside Sable, among others), the
bots also need `masurium-veil-<version>.jar` in `shared/mods/`: a headless
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
- Java **21** and Python **3.9+** (standard library only). The launcher
  (Masurium Launcher) is written for every platform Python runs on, but so far
  it has only been run on Linux.
- [Claude Code](https://claude.com/claude-code), installed for the user that runs
  the bots, with a way to pay for it — see **The brain** below. A Claude
  subscription is **not** required.
- [HeadlessMC](https://github.com/headlesshq/headlessmc) launcher jar and the
  [hmc-specifics](https://github.com/headlesshq/hmc-specifics) mod for 1.21.1
  NeoForge.
- About **3 GB of RAM per bot** (each bot is a full Java client; a bot's `heap`
  file, or `MASURIUM_HEAP` for all of them, sets it).
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
  "apiKeyHelper": "cat ~/.masurium/api.key"
}
```

```bash
printf '%s' 'sk-ant-...' > ~/.masurium/api.key
chmod 600 ~/.masurium/api.key
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
(cd mod && ./gradlew build)   # mod/build/libs/masurium-*.jar
```

**One jar, both halves.** The same file goes in the Minecraft server's `mods/`
folder and in each bot client's. On a dedicated server the bot half is never
constructed (it is `@Mod(dist = Dist.CLIENT)`), and on a client the server half
stays asleep unless you open a single-player or LAN world.

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

### 3. Prepare the bot machine

```text
~/.masurium/server.env          connection to the server mod
~/shared/headlessmc-launcher.jar
~/shared/mods/                  masurium-*.jar and hmc-specifics-*.jar
~/servers/<slug>/server.conf    one folder per server you connect to
~/servers/<slug>/mods/          client-side mods that server requires (may be empty)
~/bots/                         created by the launcher: one folder per bot (a character)
~/instances/                    created by the launcher: one folder per instance (a bot on a server)
```

`~/.masurium/server.env`:

```bash
# where the server mod listens, and its token
MASURIUM_HOST=192.168.1.10
MASURIUM_PORT=8477
MASURIUM_TOKEN=the-same-token
# optional: the player who runs the bots; also the owner of any bot
# without an owner file
MASURIUM_OWNER=YourPlayerName
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
`server.env`, which also keeps what only it says, such as `MASURIUM_OWNER`.
`doctor` checks each server's mod, and that each such file is readable by you
alone.

The folders can be moved with `MASURIUM_BOTS_DIR`, `MASURIUM_INSTANCES_DIR`,
`MASURIUM_SERVERS_DIR`, `MASURIUM_COMMON_DIR` and `MASURIUM_STATE_DIR`
(where the bridges keep their sessions and channels, `~/.masurium` by
default), either in the environment or as more lines of `server.env`
(`MASURIUM_ENV` says where that file is). After building,
`launcher/masurium.py deploy-mod` copies the mod into `shared/mods` safely,
even with bots running, and clears out any older jar that would declare the
same mod twice. `MASURIUM_JAVA` points the bots at a particular `java` when
the one on the PATH is not 21.

### 4. Create and start a bot

Two things to tell apart. A **bot** is a character: its name in the game, its
personality, its settings (`bots/<bot>/`). An **instance** is a bot on a
server, and it is what starts and stops: its game folder, its HeadlessMC, its
logs, its port (`instances/<instance>/`). One bot can have several instances,
on several servers.

Everything goes through one command, `launcher/masurium.py`, the command line of
Masurium Launcher:

```bash
launcher/masurium.py doctor            # the machine, the folders, the server: what is missing
launcher/masurium.py account add      # once per Minecraft account: type `login`, follow the steps, then `quit`
launcher/masurium.py create Alice <slug>   # the bot Alice and the instance alice on that server
launcher/masurium.py set --bot alice account <account>   # it plays with that account
launcher/masurium.py restart alice     # starts the client and, once it is in, its bridge
```

For an offline bot on a private server with `online-mode=false`, create it with
`--account offline` and skip the account. Or add an **offline account**,
`account add --offline Name`: only a player name, listed and chosen like the
others, which gives every bot set to it that name. Unlike a Microsoft
account, it may play in several games at once.

**Accounts.** An online bot plays with a real, purchased Minecraft Java
account. `account add` opens HeadlessMC in a folder of its own: type `login`,
follow its steps in a browser, and `quit` once it says the account is saved;
it becomes `accounts/<player>/`. A bot set to that account plays as its player,
and every instance of it uses that one login, through a link: HeadlessMC
renews a login each time it launches the game and Microsoft replaces the key
each time, so copies would go stale. For the same reason one account plays in
one game at a time (`start` refuses a second one, wherever it would play) and
its instances start one after another. `account` lists the accounts, whether
each is still logged in and who uses it; `account remove` takes one out, once
nobody uses it. (`account online` still means a login kept in the instance's
own HeadlessMC, made with `masurium.py login <instance>`.)

Then say its name in the chat: `Alice, come here`.

**The window.** Masurium Launcher also has a window, on the same code:

```bash
pip install PySide6-Essentials          # Qt for Python; the command line does not need it
launcher/masurium.py gui
```

It is laid out as [Prism Launcher](https://prismlauncher.org/)'s, so it feels
familiar to anyone who has used it (Masurium is not affiliated with Prism;
see the README). Across the top: **Add Instance** (with Add Group under its
arrow), **Folders**, **Settings**, **Help** (doctor, this guide), and **Bots**
and **Accounts** on the right. In the middle, every instance by group: its
bot's face with a dot saying how it is doing (in the server, loading, stopped,
in the game with no bridge), its name under it. On the right, for the one
selected: **Launch** (client, then bridge; Restart, Connect again and Start its
bridge under its arrow), **Kill**, **Edit**, **Change Group**, **Folder**,
**Copy** and **Delete**; for a group, Launch all, Kill all, Edit, Add to it,
Add instance here, and the same. Right-click on a group's empty space adds an
instance or a group right there; on the background, in no group.

**Edit** opens an instance's own window, with its pages down the left:
Settings, Rules, Personality, Mods (jars of its own) and Logs, and Launch and
Kill at the bottom; a double click on an instance opens it at its logs.
**Settings** has the launcher's own page (its colour style, animations, how
often it looks at the instances), Java, the global settings and rules,
accounts (Microsoft ones, logged in from the window through HeadlessMC's own
questions, and offline ones) and the servers. Long operations run in the
background and can be cancelled; what they report is listed under the
instance. Motion is on by default and off in Settings, Launcher: switches that
slide, windows and panels that fade in, icons that make a small gesture when
the pointer is over them, and a dot that pulses while its instance works.

Instances and groups are dragged onto a group to move them there, or onto
"In no group" to take them out; a leader or a guard takes its dependency group
along. A dependency group is drawn as a small map: its leader on top, always
shown, and its guards below, each joined to it by an arrow. A bot's picture is
set by clicking its face in the side panel: from a file, or pasted from a
copied image (handy when the window is shown from another machine, whose files
the launcher cannot see). It lives in `bots/<bot>/icon.png`.

**Showing it from another machine.** The window runs where the bots run. On a
machine without a screen, it can be shown on a Linux desktop with
[waypipe](https://gitlab.freedesktop.org/mstoeckl/waypipe) (Wayland) or
`ssh -X` (X11), with waypipe installed on both ends:

```bash
waypipe -n ssh user@bots-machine /path/to/masurium/launcher/masurium.py gui
```

The rest of the subcommands: `status` (every instance: client, hands, in the
server, bridge), `bots`, `servers`, `start` (the client only), `connect`
(rejoin after a log off), `bridge` (the bridge only), `stop` (client, bridge and
its running guards; `--keep-guards` leaves the guards), `set`, `deploy-mod` and
`delete <instance> --yes` (its folder and all; its bot stays).

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
kept their game in their own folder. `masurium.py migrate --dry-run` says
what it would do, and `masurium.py migrate` turns each one into a bot and an
instance of the same name: the game folders are moved, not copied; what its
bridge kept goes to its server's state folder; and a backup of every small file
goes first to `<state>/backups/`. A bot that is running is left alone until it
is stopped.

## Configuring a bot

Settings come in layers, weakest first: the **bot's** (`bots/<bot>/bot.json`),
for every instance of it; the **instance's** (`instances/<instance>/instance.json`);
its **groups'**, from its own outwards (see [Groups](#groups)); and the
**global** config (`launcher.json`, next to `server.env`). A stronger layer
wins where it says something. `masurium.py set` changes them with a check
first and says when the change counts; `doctor` names any value, edited by
hand, that `set` would refuse.

```bash
launcher/masurium.py set alice                        # every setting, its value, and where it comes from
launcher/masurium.py set --bot alice model sonnet     # for every instance of the bot
launcher/masurium.py set alice model haiku low        # for this instance only
launcher/masurium.py set alice heap --default         # out of the instance: what is under, or the default
launcher/masurium.py set --group team model sonnet    # imposed on everything in the group
launcher/masurium.py set --global heap 4g             # imposed on every instance
```

The personality is a text file of its own, `bots/<bot>/personality.txt`: who
the bot is, in second person, **including the language it speaks** with
players and how; it goes at the start of its prompt.

| setting | layers | meaning |
|---|---|---|
| `account` | bot, instance | one of the launcher's accounts (`account add`), `offline` (private servers only), or `online` (a login kept in the instance) |
| `owner` | all | the player the bot belongs to: it accepts their delicate orders, and they control it with `/masurium bot` on any server. It cannot be changed from inside the game |
| `model` | all | the model and effort of its brain, e.g. `sonnet` or `haiku low` (default `opus medium`). The aliases `opus`, `sonnet`, `haiku` and `fable` always mean the newest of their family; `opus[1m]` and the like, a million tokens of context |
| `heap` | all | the game's memory, e.g. `3g` (default `MASURIUM_HEAP`, else `3g`) |
| `java` | instance, group, global | the Java its game runs on: a path to a `java`, or a name on the PATH (default `MASURIUM_JAVA`, else `java`); NeoForge 21.1 wants Java 21 |
| `java_args` | instance, group, global | extra JVM flags, e.g. `-XX:+UseZGC` (the heap is `heap`, not a flag here) |
| `role` | bot, instance, group | `main` (takes orders, does jobs) or `guard` (see [Groups](#groups)) |
| `fast_responses` | all | `yes` (default): its brain writes ahead of time, in its voice and language, the few things it says without thinking; `no`: plain English |
| `port` | instance | the local port of the bot mod, chosen by the launcher |
| `lock` | instance, group | `yes`: the groups around it do not impose on it (the global config still does) |
| `ignore_global` | instance, group | `yes`: the global config (settings and rules) leaves it alone |

The launcher writes what the bridge reads into the instance's folder on every
start and every change, one small file per setting (`model`, `owner`...):
those are an output, not a place to edit.

**What it says without its brain: its fast responses.** A few sentences are
said without asking the brain: the urgent ones of the body (a creeper next to
the player it escorts, being cornered) and a few of the bridge's (shutting
down, busy). They are plain English in the code. With `fast_responses` on
(the default), the first time its bridge starts the brain writes its own
version of each, in its voice and its language, into
`gamedir/config/masurium-phrases.properties`, and those are said from then
on; they are written again when its personality changes, and on request:

```bash
launcher/masurium.py phrases alice
```

A version that lost a placeholder (the distance of the creeper, say) is not
used: that sentence is said in English.

**Main bots and guards.** A main bot takes orders and does jobs. A guard
follows and protects its leader, sleeps when it sleeps, shares its break
whitelist and steps aside when it is in the way. Which bot it guards is said
by a dependency group (below). Guards mostly react, so a smaller model
(`haiku low`) works well for them.

### Groups

A group is `groups/<group>/group.json`, and comes in two kinds:

- **normal**: instances and other groups, started and stopped together;
- **dependency**: a **leader** and its **guards**, on the leader's server. A guard
  is useless without its leader, so starting a guard starts its leader first
  (client and bridge), stopping the leader stops its guards, and restarting the
  leader leaves them running. A guard has one leader. An instance with the
  role `guard` that no dependency group names does not start, and says so.
  A leader that was in a normal group stays there: its new dependency group
  takes its place. In the window, an instance's right-click has **New
  Dependency Group…** (led by it), and a leader's has **Add Guards…**.

```bash
launcher/masurium.py group create alice-guards --leader alice     # a dependency group
launcher/masurium.py group add alice-guards bob                   # bob guards alice (and takes the role)
launcher/masurium.py group create team
launcher/masurium.py group add team carol group:alice-guards      # instances and groups
launcher/masurium.py groups                                       # the tree, and what is in no group
launcher/masurium.py group start team                             # everything in it, leaders first
launcher/masurium.py group stop team
launcher/masurium.py group clone team                             # team-1, with copies of every instance
```

Groups nest, and each instance and each group is in **one** group at most, so
what imposes on an instance is a single chain, from its own group outwards. A
group's settings (`set --group`) and rules (`rules --group`) **impose** on
everything inside it, the outer groups over the inner ones, and the global
config over them all. `lock yes` on an instance or a group keeps the groups
around it from imposing on it; `ignore_global yes` keeps the global config out.

Starting a group starts what is not running, one after another, and warns
first if their heaps do not fit in the memory there is. One that does not
start does not stop the rest, except its own guards. A clone of a group is a
copy of the whole tree with copies of its instances: the same players as the
originals, so `start` is what refuses to run both. An `escort` from before
groups is turned into a dependency group by `masurium.py migrate`.

Standing orders ("if you run out of fuel, take it from the wooden chest") are
still given by talking to the bot, and kept per server in the client's
`config/` folder. What it is *allowed* to do is not: that is its rules.

### Rules

A bot's **rules** are its behaviour toggles (`hunt_players`,
`build_while_following`, `defend_from_players`...), the food it does not eat
on its own, and the blocks it may break on its own to make its way. The
**server** enforces them: its mod keeps them for each bot, and the bridge makes
the body hold what they come to, at start and within a second of any change.
They come in three layers, the stronger one winning where two name the same
thing:

| layer | where it is kept | who changes it |
|---|---|---|
| **base** | the bot's `bot.json` (`"rules"`), with its server's `servers/<slug>/rules.json` on top | the launcher: `rules --bot`, `rules --server` |
| **own** | on the server, per player | the launcher (`rules <instance> ...`) **and** `/masurium bot` in the game: one copy, the server's |
| **imposed** | the launcher's `launcher.json` (`"rules"`), next to `server.env` | the launcher only: `rules --global`. The game refuses to change it, and says who imposes it |

Each layer names only what it decides; otherwise lists **add up** (the bot's
config banning beef and the instance banning salmon ban both). A layer can
instead **replace** a list (`food replace`): its entries are then the whole
list, and what is under it, the golden apples every bot starts with included,
no longer counts.

```bash
launcher/masurium.py rules alice                            # every toggle and both lists, and who decides each
launcher/masurium.py rules alice food ban rotten_flesh      # its own, like /masurium bot alice food ban ...
launcher/masurium.py rules alice pref hunt_players default   # back to what is under
launcher/masurium.py rules --bot alice break allow oak_log    # the bot's config, for all its instances
launcher/masurium.py rules --server create food ban beef         # every bot on that server
launcher/masurium.py rules --global pref hunt_players off        # imposed on every instance
launcher/masurium.py rules --global food replace                 # ...the whole food list, not just additions
```

Ids go in English, as the game names things, and a mod's are ids too:
`create:cog` is exactly Create's cog, and a name alone is that name in
whatever mod has it, the game's own first. Named on the server (in the game,
or with `rules <instance>`), a name alone is looked up in its mods: `cog` is
kept as `create:cog` when only Create has one, and a name two mods share, or
one no mod has, is refused, saying which to choose or that it is not there.

In a file, a layer reads:

```json
{"prefs": {"hunt_players": false},
 "food":  {"ban": ["rotten_flesh"], "allow": ["golden_apple"]},
 "break": {"allow": ["oak_log"], "forbid": ["dirt"], "replace": false}}
```

The bot's config, its server's and the global rules are sent on every start,
and to the running instances as soon as they change. A change to an
instance's own rules while its server is away waits in the instance's folder
(`rules-pending.json`) and goes on its next start; `doctor` counts what waits.
A clone on another server takes a copy of its own rules; one that is the same
player on the same server shares them, since the server keeps them per player.

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
| `/masurium bot <bot> pref <key> on\|off\|default` | owner, admins | switch a setting, or take it back to what its config says |
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
Nobody has them by default. Granting one lets that player use it on every bot.

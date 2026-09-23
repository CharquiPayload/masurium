# masurium-watut

An add-on for [WATUT](https://www.curseforge.com/minecraft/mc-mods/watut) (What
Are They Up To), which shows what each player is doing: typing, in a menu, and
**away**. Away is minutes without a key or a mouse button, and a bot has
neither: it walks, digs and fights through code. So WATUT marked a bot AFK while
it worked, with the "zzz" over its head and the tired way of standing that goes
with it.

On a bot's client, and nowhere else, the add-on tells WATUT that the bot is
active whenever it moves, swings or uses something: WATUT's own `onAction()`,
the call its key and mouse hooks make, once a second at most. A bot that stands
still for WATUT's minutes goes away like anyone.

And while its brain thinks an answer to someone, the bot is shown **typing**, the
bubble WATUT draws over a player with text in their chat box: the bridge tells
the body when a turn starts and ends (`/thinking`), and a mixin answers WATUT's
"is the local player typing?" with yes meanwhile. Nothing in WATUT's jar is
touched, and players see WATUT as always.

```bash
(cd ../../mod && ./gradlew build)   # the core first: the add-on compiles against it
./gradlew build                     # build/libs/masurium-watut-<version>.jar
../../launcher/masurium.py deploy-mod build/libs/masurium-watut-*.jar
```

The jar goes in `shared/mods/`, with the core. It is client side only.

**One exact version.** This add-on is for WATUT **1.21.0-1.2.7**, declared in
its `mods.toml`. WATUT is reached by reflection and a mixin (so building needs
no copy of it), and a WATUT that moved `WatutMod.getPlayerStatusManagerClient()`,
its `onAction()` or its `checkIfTyping` would leave the bot away again, or never
typing: with any other version NeoForge
refuses to load the add-on, and a new version of it is due. Without WATUT in
the pack it loads and does nothing.

The core knows about this add-on, as about Veil's: a headless bot whose pack
carries WATUT refuses to start without it (`Bot.ADDON_FOR`), and the launcher's
`doctor` says so before a java is launched.

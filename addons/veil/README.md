# masurium-veil

An add-on: a jar of its own, next to the core, that teaches a bot to live with
one third-party mod. This one is for [Veil](https://github.com/FoundryMC/Veil),
a rendering library that ships *inside* other mods (Sable, among others) and
assumes there is a GPU. A bot with no screen has none, and Veil 4.3 crashes the
client at startup, before the mod handshake, with

    Invalid vertex attribute index. Must be between 0 and -1: 0

Nothing here touches Veil's jar or the mod that carries it. The add-on is two
mixins, applied when Veil's classes load, that put to sleep the parts of Veil
that only a GPU can serve, on a headless bot and nowhere else: the render
styles of its particle engine, and the unbinding of samplers. Veil itself
initializes whole, because some hundred of its own hooks into the game expect
it to. A player, or a bot with a window, gets Veil untouched. In a pack without
Veil the jar does nothing.

```bash
(cd ../../mod && ./gradlew build)   # the core first: the add-on compiles against it
./gradlew build                     # build/libs/masurium-veil-<version>.jar
../../launcher/masurium.py deploy-mod build/libs/masurium-veil-*.jar
```

The jar goes in `shared/mods/`, with the core: every bot links it. It is client
side only; the server does not need it and does not mind it.

**One exact version.** This add-on is for Veil **4.3.2** (the one inside Sable
2.0.5), declared as `veil [4.3.2]` in its `mods.toml`. Its mixins reach into
that version's internals, and a Veil that moved them would not crash the mixins,
it would leave them silently unapplied and the crash would be back. So with any
other Veil, NeoForge refuses to load the add-on, on purpose, and a new version of
the add-on is due, tested against that Veil. Without Veil in the pack it loads
and does nothing.

The core knows about this add-on: a headless bot whose pack carries Veil refuses
to start without it (`Bot.ADDON_FOR`), and the launcher's `doctor` reports it
before a java is launched.

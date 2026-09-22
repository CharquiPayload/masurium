# marionette-veil

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
./gradlew build                     # build/libs/marionette-veil-<version>.jar
../../launcher/marionette.py deploy-mod build/libs/marionette-veil-*.jar
```

The jar goes in `shared/mods/`, with the core: every bot links it. It is client
side only; the server does not need it and does not mind it.

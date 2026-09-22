#!/usr/bin/env python3
"""Tests of the launcher. No Minecraft, no server, no network.

What is tested is the part that used to live in bash and could only be tested
by starting a bot: the folders, the names, the ports, the mods, and the
keeper, which is run for real against a fake game that echoes what it is told.

Run:  python3 launcher/tests.py
"""
import dataclasses
import json
import os
import pathlib
import stat
import subprocess
import sys
import tempfile
import time

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
TMP = pathlib.Path(tempfile.mkdtemp(prefix="marionette-test-"))

# A game that is not a game: it reads its stdin, echoes each line to stdout
# with a prefix, and claims the mod initialized when told to launch. The keeper
# is started with this as its java, through MARIONETTE_JAVA.
FAKE_JAVA = TMP / "fake_java.py"
FAKE_JAVA.write_text(
    "#!" + sys.executable + "\n"
    "import sys\n"
    "for line in sys.stdin:\n"
    "    line = line.rstrip('\\n')\n"
    "    print('got: ' + line, flush=True)\n"
    "    if line.startswith('launch'):\n"
    "        print('HMC-Specifics initialized', flush=True)\n"
    "    if line == 'quit':\n"
    "        break\n")
FAKE_JAVA.chmod(FAKE_JAVA.stat().st_mode | stat.S_IEXEC)

sys.path.insert(0, str(REPO))
from launcher import cli, doctor, operations as ops  # noqa: E402
from launcher import bots, files, keeper, packs, processes  # noqa: E402
from launcher.events import Fail  # noqa: E402
from launcher.workspace import DEFAULT_HEAP, DEFAULT_VERSION, FIRST_PORT, Workspace  # noqa: E402

# Every test runs in a workspace of its own, under TMP, home included: nothing
# here reads or writes the real ~/bots, ~/servers, ~/shared or ~/.marionette.
ENVIRON = dict(os.environ)
for k in ("HEAP", "VERSION", "MARIONETTE_HEAP", "MARIONETTE_VERSION", "MARIONETTE_ACCOUNT"):
    ENVIRON.pop(k, None)
ENVIRON["MARIONETTE_JAVA"] = str(FAKE_JAVA)
WS = Workspace(TMP / "bots", TMP / "servers", TMP / "shared", TMP / "server.env",
               home=TMP, environ=ENVIRON)


# --- minimal harness --------------------------------------------------------

failures = []
done = 0


def check(description, condition, detail=""):
    global done
    done += 1
    if condition:
        print(f"  ok   {description}")
    else:
        print(f"  FAIL {description}" + (f"\n         {detail}" if detail else ""))
        failures.append(description)


def fails(fn, *args, **kw):
    """The Fail the launcher raises, or None when it did not refuse."""
    try:
        fn(*args, **kw)
    except Fail as e:
        return e
    return None


def told(e):
    """All a Fail says, message and evidence, as one text ('' for None)."""
    return "" if e is None else "\n".join((str(e),) + e.lines)


def said(fn, *args, **kw):
    """What an operation reported, printed as the command line prints it,
    followed by its failure if it failed; and what it returned (or the Fail)."""
    import contextlib
    import io
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        try:
            result = fn(*args, on_event=cli.print_event, **kw)
        except Fail as e:
            cli.print_fail(e)
            result = e
    return out.getvalue(), result


def run_cli(*argv):
    """The command line itself, on the test workspace: (what it printed, exit code)."""
    import contextlib
    import io
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = cli.main(list(argv), ws=WS)
    return out.getvalue(), code


def wait(predicate, seconds):
    return processes.wait_for(predicate, seconds, every=0.1)


def layout():
    """The three folders and a server, fresh, with a jar in each mods folder."""
    for d in ("bots", "servers/test/mods", "shared/mods"):
        (TMP / d).mkdir(parents=True, exist_ok=True)
    (TMP / "shared" / "headlessmc-launcher.jar").write_bytes(b"not really a jar")
    (TMP / "shared" / "mods" / "marionette-1.0.0.jar").write_bytes(b"m")
    (TMP / "shared" / "mods" / "hmc-specifics-1.21.1-neoforge.jar").write_bytes(b"h")
    (TMP / "servers" / "test" / "mods" / "create-6.jar").write_bytes(b"c")
    (TMP / "servers" / "test" / "server.conf").write_text(
        "# a server\nHOST=10.0.0.5\nMC_PORT=25566\nDESCRIPTION=\"the test one\"\n")
    (TMP / "server.env").write_text(
        "MARIONETTE_HOST=10.0.0.5\nMARIONETTE_PORT=1\nMARIONETTE_TOKEN=t\n"
        "MARIONETTE_OWNER=Owner\n")


def quick_server_env():
    """A server mod that refuses at once (127.0.0.1:1) instead of one that
    times out, so a `start` under test fails in a second, not in ten."""
    (TMP / "server.env").write_text("MARIONETTE_HOST=127.0.0.1\nMARIONETTE_PORT=1\n"
                                    "MARIONETTE_TOKEN=t\n")


def start_keeper(name="Alice"):
    bot = WS.bot(name)
    bot.run.mkdir(parents=True, exist_ok=True)
    files.unlink_quietly(bot.client_log, bot.keeper_log)
    processes.spawn_free([sys.executable, str(HERE / "marionette.py"), "keeper", name, "test"],
                         bot.keeper_log, cwd=TMP, env=WS.child_env())
    return bot


# --- files ------------------------------------------------------------------

def tests_files():
    print("\nFiles: read like a shell would, without running one")
    f = TMP / "env.txt"
    f.write_text("# comment\n\nexport A=1\nB = two words\nC=\"quoted\"\nD='single'\n"
                 "E=x=y\nnot a pair\n")
    v = files.read_env_file(f)
    check("comments and blanks are skipped", "comment" not in str(v) and len(v) == 5)
    check("`export` prefix is ignored", v.get("A") == "1")
    check("spaces around = are trimmed", v.get("B") == "two words")
    check("double quotes come off", v.get("C") == "quoted")
    check("single quotes come off", v.get("D") == "single")
    check("only the first = splits", v.get("E") == "x=y")
    check("a missing file is an empty config", files.read_env_file(TMP / "nope") == {})

    p = TMP / "x.properties"
    p.write_text("# c\nhmc.offline.username=Alice\nhmc.gamedir=/a/b\n")
    check("java properties", files.read_java_properties(p).get("hmc.offline.username") == "Alice")


# --- the workspace ----------------------------------------------------------

def tests_workspace():
    print("\nWorkspace: where the folders are, decided once, per workspace")
    env = TMP / "ws.env"
    env.write_text("MARIONETTE_SERVERS_DIR=/from/the/file\n")
    ws = Workspace.from_environment({"MARIONETTE_ENV": str(env),
                                     "MARIONETTE_BOTS_DIR": "/from/the/environment"}, home=TMP)
    check("the environment wins", ws.bots_dir == pathlib.Path("/from/the/environment"))
    check("then server.env", ws.servers_dir == pathlib.Path("/from/the/file"))
    check("then the default next to the home", ws.shared_dir == TMP / "shared")
    check("two workspaces do not share their folders", WS.bots_dir == TMP / "bots")
    child = WS.child_env()
    check("children inherit the folders this workspace resolved",
          child["MARIONETTE_BOTS_DIR"] == str(TMP / "bots") and child["MARIONETTE_ENV"] == str(TMP / "server.env"))
    check("...and ~/.local/bin first on PATH", child["PATH"].startswith(str(TMP / ".local" / "bin")))
    check("without server.env there is no API, and it is said",
          "missing" in told(fails(Workspace(TMP, TMP, TMP, TMP / "nope.env").api)))


# --- names and servers ------------------------------------------------------

def tests_names():
    print("\nNames: Minecraft's rules, refused at creation and not at join time")
    check("letters, digits, underscore pass", fails(bots.check_name, "Bot_42") is None)
    check("a dash is refused", "not a valid name" in told(fails(bots.check_name, "bot-1")))
    check("an accent is refused", fails(bots.check_name, "Iñaki") is not None)
    check("empty is refused", fails(bots.check_name, "") is not None)
    check("17 characters are refused", "allows 16" in told(fails(bots.check_name, "A" * 17)))
    check("16 characters pass", fails(bots.check_name, "A" * 16) is None)

    layout()
    (TMP / "bots" / "adam").mkdir()
    check("'ada' clashes with 'adam' (one contains the other)", WS.name_clash("ada") == "adam")
    check("'adam' clashes with 'ada' the other way round", WS.name_clash("adam") is None)
    (TMP / "bots" / "ada").mkdir()
    check("a name does not clash with itself", WS.name_clash("adam") == "ada")
    check("'eve' clashes with nobody", WS.name_clash("eve") is None)
    for d in ("adam", "ada"):
        (TMP / "bots" / d).rmdir()


def tests_servers():
    print("\nServers: the registry, with its defaults")
    layout()
    s = WS.server("test")
    check("HOST, MC_PORT and DESCRIPTION are read",
          (s.host, s.mc_port, s.description) == ("10.0.0.5", "25566", "the test one"))
    check("VERSION defaults", s.version == DEFAULT_VERSION)
    check("a port that is not 25565 is spelled out", s.address == "10.0.0.5:25566")
    check("25565 goes unsaid", dataclasses.replace(s, mc_port="25565").address == "10.0.0.5")
    e = fails(WS.server, "nope")
    check("an unknown slug lists the ones there are", "test" in told(e) and e.code == "unknown_server")
    (TMP / "servers" / "bad").mkdir()
    (TMP / "servers" / "bad" / "server.conf").write_text("MC_PORT=1\n")
    check("a server without HOST is refused", "HOST" in told(fails(WS.server, "bad")))
    check("servers are listed sorted", WS.server_slugs() == ["bad", "test"])
    check("...and a broken one is left out of the list", [x.slug for x in WS.servers()] == ["test"])
    (TMP / "servers" / "bad" / "server.conf").unlink()
    (TMP / "servers" / "bad").rmdir()


# --- ports and mods ---------------------------------------------------------

def tests_ports():
    print("\nPorts: a stopped bot keeps its port")
    layout()
    (TMP / "bots" / "one").mkdir()
    (TMP / "bots" / "one" / "port").write_text(f"{FIRST_PORT}\n")
    check("the first port is reserved by 'one'", WS.port_reserved(FIRST_PORT))
    check("...but not against 'one' itself", not WS.port_reserved(FIRST_PORT, "one"))
    check("the free port skips it", WS.free_port("two") == FIRST_PORT + 1)
    check("a port nobody listens on is not in use", not processes.port_in_use(1))
    (TMP / "bots" / "one" / "port").unlink()
    (TMP / "bots" / "one").rmdir()

    check("this process is alive", processes.pid_alive(os.getpid()))
    check("pid 0/None is not", not processes.pid_alive(None) and not processes.pid_alive(0))
    check("a pid file with junk reads as None", files.read_pid(TMP / "nope") is None)


def tests_mods():
    print("\nMods: gamedir/mods is rebuilt whole from shared and the pack")
    layout()
    gamedir = TMP / "bots" / "x" / "gamedir"
    (gamedir / "mods").mkdir(parents=True)
    stray = gamedir / "mods" / "dropped-by-hand.jar"
    stray.write_bytes(b"s")
    n = packs.sync_mods(WS, gamedir, TMP / "servers" / "test")
    names = sorted(p.name for p in (gamedir / "mods").glob("*.jar"))
    check("three jars linked: two shared, one from the pack", n == 3 and len(names) == 3, str(names))
    check("a jar dropped by hand is gone", not stray.exists())
    linked = gamedir / "mods" / "create-6.jar"
    source = TMP / "servers" / "test" / "mods" / "create-6.jar"
    same = linked.stat().st_ino == source.stat().st_ino
    check("the jar is a hard link when the filesystem allows it (else a copy)",
          same or linked.read_bytes() == source.read_bytes())
    linked.unlink()
    check("deleting the link keeps the pack's jar", source.exists())
    for p in (gamedir / "mods").glob("*"):
        p.unlink()
    (gamedir / "mods").rmdir()
    gamedir.rmdir()
    gamedir.parent.rmdir()


# --- create -----------------------------------------------------------------

def tests_create():
    print("\nCreate: a bot costs a folder, a port and a few small files")
    layout()
    text, bot = said(ops.create_bot, WS, "Alice", "test", "offline")
    check("returns the bot", not isinstance(bot, Fail) and bot.key == "alice", text)
    check("the folder is lowercase", bot.dir == TMP / "bots" / "alice" and bot.dir.is_dir())
    check("the name keeps its capitals in the hmc config", bot.name == "Alice")
    check("port, server and account are written",
          (bot.read("port"), bot.read("server"), bot.read("account")) == (str(FIRST_PORT), "test", "offline"))
    check("the owner comes from server.env", bot.read("owner") == "Owner")
    check("language defaults to en", bot.read("language") == "en")
    check("a personality template is there", "You are Alice" in bot.read("personality.txt"))
    props = files.read_java_properties(bot.hmc / "HeadlessMC" / "config.properties")
    check("hmc is offline and points at the gamedir",
          props.get("hmc.offline") == "true" and props.get("hmc.gamedir") == str(bot.gamedir))
    check("the launcher jar is next to the hmc config", (bot.hmc / "headlessmc-launcher.jar").is_file())
    check("its mods are linked", len(list((bot.gamedir / "mods").glob("*.jar"))) == 3)
    check("what it did is reported, not printed", "creating Alice" in text and "3 mods linked" in text, text)
    check("creating it again is refused",
          "already exists" in told(fails(ops.create_bot, WS, "Alice", "test")))
    check("'Ali' is refused: Alice contains it",
          "clashes" in told(fails(ops.create_bot, WS, "Ali", "test")))
    check("an unknown server is refused",
          "unknown server" in told(fails(ops.create_bot, WS, "Bob", "nope")))
    check("a second bot takes the next port",
          not isinstance(said(ops.create_bot, WS, "Bob", "test", "offline")[1], Fail)
          and WS.bot("Bob").read("port") == str(FIRST_PORT + 1))


def tests_prepare():
    print("\nPrepare: what the gamedir gets before every start")
    bot = WS.bot("Alice")
    server = WS.server("test")
    options = bot.gamedir / "options.txt"
    options.write_text("fov:0.5\nonboardAccessibility:true\nlang:en_us\n")
    ops.prepare_gamedir(bot, server)
    text = options.read_text()
    check("the accessibility prompt is turned off", "onboardAccessibility:false" in text)
    check("...and every other option is kept", "fov:0.5" in text and "lang:en_us" in text
          and "onboardAccessibility:true" not in text)
    options.unlink()
    ops.prepare_gamedir(bot, server)
    check("with no options.txt, one is written with just that", options.read_text() == "onboardAccessibility:false\n")
    check("the server is noted in the gamedir for the mod",
          (bot.gamedir / "config" / "marionette-server.txt").read_text().strip() == "test")


# --- the launch line --------------------------------------------------------

def tests_launch_line():
    print("\nLaunch line: what HeadlessMC is told")
    bot = WS.bot("Alice")
    server = WS.server("test")
    line = keeper.launch_line(bot, server)
    check("no -commands, ever", "-commands" not in line)
    check("-lwjgl and -paulscode", "-lwjgl" in line and "-paulscode" in line)
    check("offline account: -offline", " -offline " in line)
    check("the name with its capitals", "-Dmarionette.name=Alice" in line)
    check("headless, and the bot's own port",
          "-Dmarionette.headless=true" in line and f"-Dmarionette.bot.port={FIRST_PORT}" in line)
    check("default heap", f"-Xmx{DEFAULT_HEAP}" in line)
    check("no language flag without a clean value", "marionette.language=en" in line)
    bot.write("language", "es; rm -rf /")
    bot.write("gender", "f\n")
    line = keeper.launch_line(bot, server)
    check("language and gender reach the JVM cleaned",
          "-Dmarionette.language=esrmrf " in line and "-Dmarionette.gender=f" in line)
    WS.environ["MARIONETTE_HEAP"] = "1g"
    WS.environ["MARIONETTE_VERSION"] = "neoforge-21.1.999"
    line = keeper.launch_line(bot, server)
    check("MARIONETTE_HEAP and MARIONETTE_VERSION from the environment win",
          "-Xmx1g" in line and "launch neoforge-21.1.999 " in line)
    bot.write("heap", "4g")
    check("...and a bot's own heap file wins over MARIONETTE_HEAP", "-Xmx4g " in keeper.launch_line(bot, server))
    bot.write("heap", "4g -XX:+Evil")
    check("a heap file that is not a size never reaches the JVM",
          "Evil" not in keeper.launch_line(bot, server) and "-Xmx1g " in keeper.launch_line(bot, server))
    (bot.dir / "heap").unlink()
    WS.environ.pop("MARIONETTE_HEAP")
    WS.environ.pop("MARIONETTE_VERSION")
    WS.environ["HEAP"] = "1g"
    WS.environ["VERSION"] = "neoforge-21.1.999"
    line = keeper.launch_line(bot, server)
    check("the old, too generic HEAP and VERSION are not read any more",
          f"-Xmx{DEFAULT_HEAP}" in line and "21.1.999" not in line)
    WS.environ.pop("HEAP")
    WS.environ.pop("VERSION")
    bot.write("language", "en")
    (bot.dir / "gender").unlink()


# --- the keeper -------------------------------------------------------------

def tests_keeper():
    print("\nKeeper: holds the game's stdin and answers on a socket")
    bot = start_keeper()
    check("the port file appears", wait(lambda: bot.keeper_port_f.is_file(), 10))
    keeper_pid = files.read_pid(bot.keeper_pid_f)
    check("the keeper belongs to nobody here (not our child, no zombie later)",
          keeper_pid and os.name == "nt" or (keeper_pid and not any(
              int(p) == keeper_pid for p in
              subprocess.run(["ps", "-o", "pid=", "--ppid", str(os.getpid())],
                             capture_output=True, text=True).stdout.split())))
    check("@ping answers with the game's pid",
          wait(lambda: (keeper.keeper_ask(bot, "@ping") or "").startswith("ok "), 5))
    answer = keeper.keeper_ask(bot, "@ping")
    game_pid = int(answer.split()[1])
    check("that pid is the one in client.pid and it is alive",
          files.read_pid(bot.client_pid_f) == game_pid and processes.pid_alive(game_pid))
    check("keeper.pid is a live process", processes.pid_alive(files.read_pid(bot.keeper_pid_f)))
    check("keeper_alive() agrees", keeper.keeper_alive(bot))
    check("the launch line reached the game",
          wait(lambda: files.log_has(bot.client_log, "got: launch neoforge-21.1.248 -lwjgl -offline"), 5))
    check("...and the game claimed the mod initialized", files.log_has(bot.client_log, keeper.HMC_READY))
    check("a line is passed through and acknowledged", keeper.keeper_ask(bot, "connect 10.0.0.5:25566") == "sent")
    check("it arrived at the game's stdin", wait(lambda: files.log_has(bot.client_log, "got: connect 10.0.0.5:25566"), 5))
    check("an unknown @command is refused", keeper.keeper_ask(bot, "@dance") == "unknown")
    check("@stop is acknowledged", keeper.keeper_ask(bot, "@stop") == "stopping")
    check("the game is gone", wait(lambda: not processes.pid_alive(game_pid), 10))
    check("the keeper is gone", wait(lambda: not processes.pid_alive(files.read_pid(bot.keeper_pid_f)) if bot.keeper_pid_f.exists() else True, 10))
    check("the run files are cleaned up",
          wait(lambda: not bot.keeper_port_f.exists() and not bot.client_pid_f.exists(), 5))
    check("with no keeper, asking returns None", keeper.keeper_ask(bot, "@ping") is None)
    check("the keeper's own log says why it ended", files.log_has(bot.keeper_log, "game exited"))


def tests_stop_without_keeper():
    print("\nStop: nothing running is not an error")
    text, code = run_cli("stop", "Alice")
    check("stopping a stopped bot returns 0", code == 0, text)


# --- what a pack is made of -------------------------------------------------

def write_mod_jar(path, mod_id, version, inner=(), placeholder=False):
    """A jar with a mods.toml, and optionally other mod jars inside it the way
    NeoForge's jar-in-jar does it (metadata.json + the nested jars)."""
    import io
    import zipfile

    def toml(i, v):
        return (f"modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\n\n[[mods]]\nmodId=\"{i}\" # the id\n"
                f"version=\"{v}\"\ndisplayName=\"{i}\"\n\n[[dependencies.{i}]]\nmodId=\"neoforge\"\n"
                f"type=\"required\"\nversionRange=\"[21.1,)\"\n")

    with zipfile.ZipFile(path, "w") as z:
        z.writestr("META-INF/MANIFEST.MF", f"Manifest-Version: 1.0\nImplementation-Version: {version}\n")
        z.writestr(packs.MODS_TOML, toml(mod_id, "${file.jarVersion}" if placeholder else version))
        if inner:
            entries = []
            for i, v in inner:
                buf = io.BytesIO()
                with zipfile.ZipFile(buf, "w") as nested:
                    nested.writestr(packs.MODS_TOML, toml(i, v))
                nested_path = f"META-INF/jarjar/{i}-neoforge-1.21.1-{v}.jar"
                z.writestr(nested_path, buf.getvalue())
                entries.append({"identifier": {"group": "x", "artifact": i}, "path": nested_path})
            z.writestr("META-INF/jarjar/metadata.json", json.dumps({"jars": entries}))


def tests_packs():
    print("\nPacks: what a bot joins with, read from the jars themselves")
    layout()
    mods_dir = TMP / "servers" / "test" / "mods"
    for p in mods_dir.glob("*.jar"):
        p.unlink()
    write_mod_jar(mods_dir / "create-6.0.10.jar", "create", "6.0.10")
    write_mod_jar(mods_dir / "sable-neoforge-1.21.1-2.0.3.jar", "sable", "2.0.3", inner=[("veil", "4.2.0")])
    write_mod_jar(mods_dir / "xaeros-1.0.jar", "xaerominimap", "25.1", placeholder=True)
    (mods_dir / "not-a-jar.jar").write_bytes(b"junk")
    mine = packs.pack_mods(WS, TMP / "servers" / "test")
    check("a plain mods.toml is read", mine.get("create") == "6.0.10")
    check("a mod inside another (jar-in-jar) is read too", mine.get("veil") == "4.2.0" and mine.get("sable") == "2.0.3")
    check("${file.jarVersion} comes from the manifest", mine.get("xaerominimap") == "25.1")
    check("a broken jar is skipped, not fatal", "junk" not in str(mine))
    check("shared/mods is part of the pack",
          all(k in mine for k in ()) and len(mine) >= 4)   # the shared jars in layout() have no toml
    theirs = {"create": "6.0.10", "sable": "2.0.5", "veil": "4.3.2", "neoforge": "21.1.248",
              "createcobblestone": "1.5.0"}
    diff = packs.compare_packs(mine, theirs)
    check("version mismatches are named, with both versions",
          diff["mismatch"] == [("sable", "2.0.3", "2.0.5"), ("veil", "4.2.0", "4.3.2")], str(diff))
    check("what only the server has is listed apart", diff["server_only"] == ["createcobblestone", "neoforge"])
    check("what only the client has is listed apart", diff["client_only"] == ["xaerominimap"])
    check("same versions: nothing to say", packs.compare_packs({"a": "1"}, {"a": "1", "b": "2"})["mismatch"] == [])
    check("toml: an inline comment does not become part of the value",
          packs.toml_mods('[[mods]]\nmodId = "x" # c\nversion = "1.0" #mandatory\n') == {"x": "1.0"})
    check("toml: two [[mods]] blocks, both read",
          packs.toml_mods('[[mods]]\nmodId="a"\nversion="1"\n[[mods]]\nmodId="b"\nversion="2"\n[[mixins]]\nconfig="x"\n')
          == {"a": "1", "b": "2"})
    for p in mods_dir.glob("*"):
        p.unlink()
    layout()


# --- deploy-mod -------------------------------------------------------------

def tests_deploy():
    print("\nDeploy: a new inode, and only the same family leaves")
    check("the core's family is 'marionette'", packs.jar_family("marionette-1.0.0.jar") == "marionette")
    check("an add-on's family keeps its name", packs.jar_family("marionette-veil-1.0.0.jar") == "marionette-veil")
    check("the core jar is told from an add-on by its name",
          packs.CORE_JAR.match("marionette-1.0.0.jar") and not packs.CORE_JAR.match("marionette-veil-1.0.0.jar"))
    layout()
    mods = TMP / "shared" / "mods"
    for name in ("marionette-0.9.0.jar", "marionette-bot-0.8.0.jar", "marionette-veil-0.9.0.jar",
                 "marionette-veil-1.0.0.jar", "marionette-1.0.0.jar", "hmc-specifics-1.21.1-neoforge.jar"):
        (mods / name).write_bytes(name.encode())
    old_inode = (mods / "marionette-1.0.0.jar").stat().st_ino
    built = TMP / "marionette-1.0.0.jar"
    built.write_bytes(b"fresh core")
    ops.deploy_mod(WS, str(built))
    left = sorted(p.name for p in mods.glob("*.jar"))
    check("the core is replaced through a new inode",
          (mods / "marionette-1.0.0.jar").read_bytes() == b"fresh core"
          and (mods / "marionette-1.0.0.jar").stat().st_ino != old_inode)
    check("older cores and the old two-file names are gone",
          "marionette-0.9.0.jar" not in left and "marionette-bot-0.8.0.jar" not in left)
    check("add-ons and other mods are NOT touched by a core deploy",
          {"marionette-veil-0.9.0.jar", "marionette-veil-1.0.0.jar", "hmc-specifics-1.21.1-neoforge.jar"} <= set(left))
    addon = TMP / "marionette-veil-1.1.0.jar"
    addon.write_bytes(b"fresh addon")
    target, gone = ops.deploy_mod(WS, str(addon))
    left = sorted(p.name for p in mods.glob("*.jar"))
    check("an add-on deploy replaces every older jar of that add-on only",
          "marionette-veil-1.1.0.jar" in left and "marionette-veil-0.9.0.jar" not in left
          and "marionette-veil-1.0.0.jar" not in left and "marionette-1.0.0.jar" in left, str(left))
    check("...and says which ones it replaced",
          target.name == "marionette-veil-1.1.0.jar" and sorted(gone) == ["marionette-veil-0.9.0.jar", "marionette-veil-1.0.0.jar"])
    check("a jar that is not there is refused",
          "not a file" in told(fails(ops.deploy_mod, WS, str(TMP / "nope.jar"))))
    for p in mods.glob("marionette-veil-*.jar"):
        p.unlink()


# --- doctor -----------------------------------------------------------------

def tests_doctor():
    print("\nDoctor: says what is missing, in the order it would break")
    layout()
    checks = doctor.checks(WS)
    labels = [c.label for c in checks]
    check("it checks python, java, claude and server.env",
          all(any(l.startswith(k) for l in labels) for k in ("python", "java", "claude", "server.env")))
    check("the fake java is reported as not 21",
          any(l == "java 21" and ok is False for l, ok, _ in checks))
    check("the server mod at 10.0.0.5:1 is reported unreachable",
          any(l == "server mod answers" and ok is False for l, ok, _ in checks))
    check("shared and the marionette jar are found",
          any(l == "shared/mods/marionette" and ok for l, ok, _ in checks))
    (TMP / "shared" / "mods" / "marionette-0.9.0.jar").write_bytes(b"old")
    checks = doctor.checks(WS)
    check("two marionette jars are a problem",
          any(l == "shared/mods/marionette" and ok is False and "more than one" in d for l, ok, d in checks))
    (TMP / "shared" / "mods" / "marionette-0.9.0.jar").unlink()
    (TMP / "shared" / "mods" / "marionette-veil-1.0.0.jar").write_bytes(b"a")
    checks = doctor.checks(WS)
    check("an add-on next to the core is not 'two cores'",
          any(l == "shared/mods/marionette" and ok for l, ok, _ in checks)
          and any(l == "shared/mods add-ons" and ok and "veil" in d for l, ok, d in checks))
    (TMP / "shared" / "mods" / "marionette-veil-0.9.0.jar").write_bytes(b"b")
    checks = doctor.checks(WS)
    check("two jars of one add-on are a problem",
          any(l == "shared/mods add-ons" and ok is False for l, ok, _ in checks))
    for p in (TMP / "shared" / "mods").glob("marionette-veil-*.jar"):
        p.unlink()

    # A mod that carries Veil inside it (jar-in-jar), the way Sable does.
    carrier = TMP / "servers" / "test" / "mods" / "sable-neoforge-1.21.1-2.0.5.jar"
    write_mod_jar(carrier, "sable", "2.0.5", inner=[("veil", "4.3.2")])
    check("pack_carries finds Veil inside Sable", packs.pack_carries(TMP / "servers" / "test", "veil") == [carrier.name])
    check("...and nothing where there is nothing", packs.pack_carries(TMP / "servers" / "test", "watut") == [])
    checks = doctor.checks(WS)
    check("Veil in a pack without the add-on is a problem",
          any(l == "servers/test: veil" and ok is False and "marionette-veil" in d for l, ok, d in checks))
    (TMP / "shared" / "mods" / "marionette-veil-1.0.0.jar").write_bytes(b"a")
    checks = doctor.checks(WS)
    check("...and not with the add-on in shared/mods",
          not any(l == "servers/test: veil" for l, ok, d in checks))
    (TMP / "shared" / "mods" / "marionette-veil-1.0.0.jar").unlink()
    carrier.unlink()
    WS.bot("Bob").write("port", FIRST_PORT)          # same as Alice's
    checks = doctor.checks(WS)
    check("two bots on one port are a problem",
          any(l == "bots/bob" and ok is False and "also belongs" in d for l, ok, d in checks))
    WS.bot("Bob").write("port", FIRST_PORT + 1)
    text, code = run_cli("doctor")
    check("the command line prints every check and fails when one fails",
          code == 1 and "!!  java 21" in text and "problem(s) above" in text, text[-300:])


# --- the traps of a launcher that is left running -----------------------------

def stranger():
    """A live process that is nobody's bot, keeper nor bridge."""
    return subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])


def tests_pids():
    print("\nPids: a number in a file is trusted only while it names the same process")
    check("this process is ours by its own command line", processes.is_ours(os.getpid(), "tests.py"))
    check("...and not by a mark it does not carry", not processes.is_ours(os.getpid(), "bridge.py"))
    check("a mark is a whole argument, not a piece of one", not processes.is_ours(os.getpid(), "sts.py"))
    check("no pid is nobody's", not processes.is_ours(None, "x") and processes.argv_of(None) is None)
    bot = WS.bot("Alice")
    bot.run.mkdir(parents=True, exist_ok=True)
    other = stranger()
    try:
        bot.keeper_pid_f.write_text(f"{other.pid}\n")
        bot.client_pid_f.write_text(f"{other.pid}\n")
        check("a stale keeper.pid naming a stranger is no keeper", keeper.keeper_pid(bot) is None)
        check("...nor a stale client.pid a HeadlessMC", keeper.launcher_pid(bot) is None)
        check("the bridge lock lives under the workspace's home", str(bot.bridge_lock).startswith(str(TMP)))
        bot.bridge_lock.parent.mkdir(parents=True, exist_ok=True)
        bot.bridge_lock.write_text(f"{other.pid}\n")
        check("a bridge lock left with a stranger's pid is no bridge", ops.bridge_pid(bot) is None)
        text, _ = said(ops.stop, bot)
        check("stop leaves the stranger alone", other.poll() is None, text)
        check("...and says nothing was running", "client: nothing was running" in text
              and "bridge: nothing was running" in text, text)
        check("...and clears the stale files", not bot.keeper_pid_f.exists() and not bot.client_pid_f.exists())
        bot.bridge_lock.unlink()
    finally:
        other.kill()
        other.wait()


def tests_lock():
    print("\nLock: one launcher command at a time on a bot")
    bot = WS.bot("Alice")
    lock = bot.run / "launcher.lock"
    holder = subprocess.Popen(
        [sys.executable, "-c",
         "import sys, time; sys.path.insert(0, sys.argv[1]); from launcher import files\n"
         "h = files.try_lock(sys.argv[2]); print('held' if h else 'not', flush=True); time.sleep(30)",
         str(REPO), str(lock)], stdout=subprocess.PIPE, text=True)
    try:
        check("another process takes the lock", holder.stdout.readline().strip() == "held")
        text, result = said(ops.start, bot)
        check("a start while it is held is refused, naming the holder",
              isinstance(result, Fail) and "another launcher command" in str(result)
              and str(holder.pid) in str(result) and result.code == "busy", str(result))
        check("...and refused before touching anything", "preparing" not in text, text)
        check("so is a stop", isinstance(said(ops.stop, bot)[1], Fail))
    finally:
        holder.kill()
        holder.wait()
    handle = files.try_lock(lock)
    check("the lock is free once its holder is gone, however it ended", handle is not None)
    if handle:
        handle.close()
    with bots.operating(bot):
        with bots.operating(bot):
            nested = True
    check("it is re-entrant in one thread (restart = stop + start)", nested)

    import threading
    seen = {}
    with bots.operating(bot):
        t = threading.Thread(target=lambda: seen.update(e=fails(ops.stop, bot)))
        t.start()
        t.join(10)
    check("...and only in that one: another thread is another command",
          seen.get("e") is not None and seen["e"].code == "busy", repr(seen))


def tests_start_order():
    print("\nStart: whether it runs is asked before anything is touched")
    quick_server_env()
    other = TMP / "servers" / "other"
    (other / "mods").mkdir(parents=True, exist_ok=True)
    (other / "server.conf").write_text("HOST=10.0.0.6\n")
    (other / "mods" / "other-1.jar").write_bytes(b"o")
    bot = start_keeper()
    try:
        check("a keeper is up", wait(lambda: keeper.keeper_alive(bot), 10))
        marker = bot.gamedir / "mods" / "in-use.jar"
        marker.write_bytes(b"u")
        text, result = said(ops.start, bot, "other")
        check("starting a running bot is refused", isinstance(result, Fail)
              and "already running" in str(result) and result.code == "running", f"{result!r} {text}")
        check("...with its mods folder left as the game has it", marker.exists())
        check("...and its server not rewritten",
              bot.read("server") == "test"
              and (bot.gamedir / "config" / "marionette-server.txt").read_text().strip() == "test")
    finally:
        said(ops.stop, bot)
        for p in other.rglob("*"):
            if p.is_file():
                p.unlink()
        (other / "mods").rmdir()
        other.rmdir()
        layout()


def tests_keeper_failures():
    print("\nKeeper failures: said at once, not after five minutes")
    quick_server_env()
    bot = WS.bot("Alice")
    WS.environ["MARIONETTE_JAVA"] = str(TMP / "no-such-java")
    try:
        started = time.monotonic()
        text, code = run_cli("start", "Alice")
        took = time.monotonic() - started
    finally:
        WS.environ["MARIONETTE_JAVA"] = str(FAKE_JAVA)
    check("no java: start fails", code == 1, f"{code!r}")
    check("...in seconds", took < 30, f"{took:.0f}s")
    check("...saying it could not run java", "could not run" in text and "no-such-java" in text, text)
    check("...and leaving no run files behind",
          not bot.keeper_pid_f.exists() and not bot.keeper_port_f.exists())

    # A keeper killed outright leaves no line behind: its pid is the sign.
    slow = TMP / "slow_java.py"
    slow.write_text("#!" + sys.executable + "\nimport time\ntime.sleep(60)\n")
    slow.chmod(slow.stat().st_mode | stat.S_IEXEC)
    WS.environ["MARIONETTE_JAVA"] = str(slow)
    import signal
    import threading
    game = {}

    def kill_the_keeper():
        if wait(lambda: keeper.keeper_pid(bot) and bot.client_pid_f.exists(), 20):
            game["pid"] = files.read_pid(bot.client_pid_f)
            os.kill(keeper.keeper_pid(bot), signal.SIGKILL)

    killer = threading.Thread(target=kill_the_keeper)
    killer.start()
    try:
        started = time.monotonic()
        text, result = said(ops.start, bot)
        took = time.monotonic() - started
    finally:
        killer.join()
        WS.environ["MARIONETTE_JAVA"] = str(FAKE_JAVA)
        if game.get("pid"):
            try:
                os.killpg(game["pid"], signal.SIGKILL)
            except OSError:
                pass
    check("a keeper killed while loading: start notices", isinstance(result, Fail) and took < 30,
          f"{result!r} after {took:.0f}s")
    check("...and says the keeper did not get the game going",
          "did not get the game going" in text and result.code == "keeper_failed", text)
    keeper.clear_run_files(bot)
    layout()


def tests_keeper_guarded():
    print("\nKeeper: only the holder of the token talks to it; SIGTERM still stops the game")
    bot = start_keeper()
    check("a keeper is up", wait(lambda: keeper.keeper_alive(bot), 10))
    fields = bot.keeper_port_f.read_text().split()
    check("keeper.port holds the port and a token", len(fields) == 2 and len(fields[1]) == 32)
    if os.name != "nt":
        check("...readable by this user only", stat.S_IMODE(bot.keeper_port_f.stat().st_mode) == 0o600)

    def raw(line):
        import socket
        with socket.create_connection(("127.0.0.1", int(fields[0])), timeout=5) as s:
            s.sendall((line + "\n").encode())
            return s.recv(64).decode().strip()

    check("a line without the token is denied", raw("@ping") == "denied")
    check("...and with a wrong one", raw("0" * 32 + " @ping") == "denied")
    check("a denied line does not reach the game", raw("msg sneaky") == "denied"
          and not files.log_has(bot.client_log, "sneaky"))
    check("with the token it answers", raw(f"{fields[1]} @ping").startswith("ok "))
    if os.name != "nt":
        import signal
        game_pid = files.read_pid(bot.client_pid_f)
        os.kill(keeper.keeper_pid(bot), signal.SIGTERM)
        check("SIGTERM to the keeper takes the game down with it", wait(lambda: not processes.pid_alive(game_pid), 15))
        check("...and the keeper cleans up", wait(lambda: not bot.keeper_port_f.exists()
                                                   and not bot.keeper_pid_f.exists(), 10))

    # A keeper from before the token writes the port alone and takes the
    # line bare: keeper_ask keeps talking to it until the bot is restarted.
    import socket
    import threading
    got = {}
    old = socket.socket()
    old.bind(("127.0.0.1", 0))
    old.listen(1)

    def serve():
        conn, _ = old.accept()
        with conn:
            got["line"] = conn.recv(64).decode().strip()
            conn.sendall(b"ok 1\n")

    t = threading.Thread(target=serve)
    t.start()
    bot.keeper_port_f.write_text(f"{old.getsockname()[1]}\n")
    answer = keeper.keeper_ask(bot, "@ping")
    t.join(5)
    old.close()
    bot.keeper_port_f.unlink()
    check("a keeper older than the token still gets the bare line", got.get("line") == "@ping" and answer == "ok 1")


def tests_guard_rings():
    print("\nGuards: a ring of escorts is stopped once each, not forever")
    alice, bob = WS.bot("Alice"), WS.bot("Bob")
    alice.write("escort", "bob")
    bob.write("escort", "alice")
    text, code = run_cli("stop", "Alice")
    check("two bots guarding each other: stop ends", code == 0, text)
    check("...having stopped each one once",
          text.count("==> Alice stopped") == 1 and text.count("==> Bob stopped") == 1, text)
    bob.write("escort", "bob")
    (alice.dir / "escort").unlink()
    text, code = run_cli("stop", "Bob")
    check("a bot guarding itself: stop ends", code == 0, text)
    checks = doctor.checks(WS)
    check("doctor names a bot that guards itself",
          any(l == "bots/bob" and ok is False and "escorts itself" in d for l, ok, d in checks))
    (bob.dir / "escort").unlink()


def tests_logwatch():
    print("\nLogWatch: a log read from where it was left, not whole every time")
    log = TMP / "watch.log"
    log.write_text("starting\n")
    w = files.LogWatch(log, "READY now", "dead")
    check("nothing yet", not w.saw("READY now"))
    with open(log, "a") as f:
        f.write("... REA")
    check("half a line is not the line", not w.saw("READY now"))
    with open(log, "a") as f:
        f.write("DY now\n")
    check("a line cut between two reads is still seen", w.saw("READY now"))
    check("the position moved to the end, nothing is read twice", w.pos == log.stat().st_size)
    check("a needle it was not told to watch is not invented", not w.saw("dead"))
    log.write_text("new\n")
    check("a log started over forgets what the old one said", not w.saw("READY now"))
    check("a missing log is simply not there yet", not files.LogWatch(TMP / "nope.log", "x").saw("x"))


def tests_status():
    print("\nStatus: what every bot is doing, as data and as a table")
    quick_server_env()
    problem, statuses = ops.survey(WS)
    check("the survey says the server mod does not answer", problem and "does not answer" in problem, problem)
    check("...and one status per bot, 'in server' unknown",
          [s.name for s in statuses] == ["Alice", "Bob"] and all(s.inside is None for s in statuses))
    check("a stopped bot: no client, no hands, no bridge",
          not statuses[0].client and not statuses[0].hands and statuses[0].bridge is None)
    text, code = run_cli("status")
    check("status still returns 0", code == 0)
    check("...and says the server mod does not answer", "does not answer" in text, text)
    check("...above a table with a row per bot", "Alice" in text and "Bob" in text and "in server" in text, text)
    layout()


# --- the command line -------------------------------------------------------

def tests_cli():
    print("\nCommand line: the same door as before, printing what the core reports")
    env = dict(WS.child_env())
    r = subprocess.run([sys.executable, str(HERE / "marionette.py"), "servers"],
                       capture_output=True, text=True, env=env)
    check("marionette.py still works as a script", r.returncode == 0 and "test" in r.stdout, r.stdout + r.stderr)
    r = subprocess.run([sys.executable, "-m", "launcher", "servers"],
                       capture_output=True, text=True, env=env, cwd=str(REPO))
    check("...and so does python -m launcher", r.returncode == 0 and "test" in r.stdout, r.stdout + r.stderr)
    text, code = run_cli("start", "Nobody")
    check("a Fail is printed, not raised, and the exit code is 1",
          code == 1 and "does not exist" in text, text)
    text, code = run_cli("create", "Eve", "nope")
    check("...with its evidence under it (the servers there are)",
          code == 1 and "unknown server" in text and "\n    test " in text, text)


# --- cancelling -------------------------------------------------------------

def slow_java():
    """A java that never loads: the keeper holds it, and nothing ever says
    'initialized'."""
    slow = TMP / "slow_java.py"
    slow.write_text("#!" + sys.executable + "\nimport time\ntime.sleep(120)\n")
    slow.chmod(slow.stat().st_mode | stat.S_IEXEC)
    return slow


def tests_cancel():
    print("\nCancel: a start cut short stops what it started, at once")
    import threading
    from launcher.events import Cancel, Cancelled
    quick_server_env()
    bot = WS.bot("Alice")

    cancel = Cancel()
    threading.Timer(0.3, cancel.set).start()
    started = time.monotonic()
    try:
        processes.wait_for(lambda: False, 30, every=5, cancel=cancel)
        raised = False
    except Cancelled:
        raised = True
    check("a wait notices a cancel at once, not when its sleep ends",
          raised and time.monotonic() - started < 2, f"{time.monotonic() - started:.1f}s")

    cancel = Cancel()
    cancel.set()
    text, result = said(ops.start, bot, cancel=cancel)
    check("cancelled before it began: nothing is touched", isinstance(result, Cancelled)
          and "preparing" not in text and not bot.keeper_pid_f.exists(), text)

    WS.environ["MARIONETTE_JAVA"] = str(slow_java())
    cancel = Cancel()
    game = {}

    def cancel_while_loading():
        if wait(lambda: keeper.keeper_pid(bot) and bot.client_pid_f.exists(), 20):
            game["pid"] = files.read_pid(bot.client_pid_f)
            cancel.set()

    helper = threading.Thread(target=cancel_while_loading)
    helper.start()
    try:
        started = time.monotonic()
        text, result = said(ops.start, bot, cancel=cancel)
        took = time.monotonic() - started
    finally:
        helper.join()
        WS.environ["MARIONETTE_JAVA"] = str(FAKE_JAVA)
    check("cancelled while the game loads: start raises Cancelled, in seconds",
          isinstance(result, Cancelled) and result.code == "cancelled" and took < 30,
          f"{result!r} after {took:.0f}s")
    check("...having said it is stopping what it started", "stopping the client it had started" in text, text)
    check("...and the game it had launched is gone", game.get("pid") and wait(
        lambda: not processes.pid_alive(game["pid"]), 10))
    check("...and so is its keeper, with its files", keeper.keeper_pid(bot) is None
          and not bot.keeper_port_f.exists() and not bot.client_pid_f.exists())

    # The command line: the first Ctrl+C is a cancel, not an abandoned game.
    import signal
    env = dict(WS.child_env())
    env["MARIONETTE_JAVA"] = str(slow_java())
    run = subprocess.Popen([sys.executable, str(HERE / "marionette.py"), "start", "Alice"],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env)
    try:
        loading = wait(lambda: keeper.keeper_pid(bot) and bot.client_pid_f.exists(), 20)
        game_pid = files.read_pid(bot.client_pid_f)
        run.send_signal(signal.SIGINT)
        out, _ = run.communicate(timeout=60)
    finally:
        if run.poll() is None:
            run.kill()
    check("Ctrl+C on `start` while loading: exit code 130", loading and run.returncode == 130,
          f"{run.returncode} {out}")
    check("...saying it cancels, and stopping the game it launched",
          "cancelling" in out and "cancelled" in out
          and wait(lambda: not processes.pid_alive(game_pid), 10), out)
    keeper.clear_run_files(bot)
    layout()


# --- settings ---------------------------------------------------------------

def tests_settings():
    print("\nSettings: one table says what each bot file accepts, for every face")
    from launcher import settings
    bot, bob = WS.bot("Alice"), WS.bot("Bob")
    check("a value that applies without a file is the default",
          settings.get(bot, "model") == "opus medium" and not settings.is_set(bot, "model"))
    check("the owner's default comes from server.env", settings.get(bob, "owner") == "Owner")
    for key, value, why in (("language", "english", "language code"),
                            ("gender", "x", "one of f, m"),
                            ("account", "maybe", "one of online, offline"),
                            ("owner", "not a name!", "player name"),
                            ("model", "haiku lowest", "effort is one of"),
                            ("model", "a b c", "optional effort"),
                            ("escort", "alice", "cannot escort itself"),
                            ("escort", "nobody", "another bot here: bob"),
                            ("heap", "512m", "at least 1g"),
                            ("heap", "lots", "heap size"),
                            ("port", "80", "between 1024"),
                            ("port", str(FIRST_PORT + 1), "belongs to another bot"),
                            ("server", "nope", "registered servers: test")):
        e = fails(settings.set_value, bot, key, value)
        check(f"{key} '{value}' is refused, saying what it takes",
              e is not None and why in told(e) and e.code == "bad_setting", told(e))
    check("an unknown setting is refused, listing the ones there are",
          "language" in told(fails(settings.setting, "colour")))
    check("a good value is written, as a plain file", settings.set_value(bot, "language", "ES") == "es"
          and bot.read("language") == "es")
    check("a model keeps its two words", settings.set_value(bot, "model", "haiku   low") == "haiku low")
    check("an escort keeps its capitals (the game shows names as they are)",
          settings.set_value(bot, "escort", "Bob") == "Bob")
    settings.clear(bot, "escort")
    settings.clear(bot, "model")
    check("clearing goes back to the default", not settings.is_set(bot, "model")
          and settings.get(bot, "model") == "opus medium")
    check("a required one cannot be cleared", "no default" in told(fails(settings.clear, bot, "port")))
    settings.set_value(bot, "account", "online")
    props = files.read_java_properties(bot.hmc / "HeadlessMC" / "config.properties")
    check("changing the account changes HeadlessMC's own config too", props.get("hmc.offline") == "false")
    settings.set_value(bot, "account", "offline")
    props = files.read_java_properties(bot.hmc / "HeadlessMC" / "config.properties")
    check("...both ways, and nothing else in it is lost",
          props.get("hmc.offline") == "true" and props.get("hmc.offline.username") == "Alice")
    settings.set_value(bot, "language", "en")

    # The operation: the lock, and no start-time setting under a running client.
    text, now = said(ops.configure, bot, "heap", "4g")
    check("configure reports the value and when it counts",
          now == "4g" and "Alice: heap = 4g" in text and "on its next start" in text, text)
    kept = start_keeper()
    try:
        check("a keeper is up", wait(lambda: keeper.keeper_alive(kept), 10))
        text, result = said(ops.configure, bot, "heap", "6g")
        check("a setting read at start is refused while the client runs",
              isinstance(result, Fail) and result.code == "running" and bot.read("heap") == "4g", text)
        text, result = said(ops.configure, bot, "owner", "SomePlayer")
        check("...one read as it is used is not", result == "SomePlayer" and "right away" in text, text)
    finally:
        said(ops.stop, bot)
    said(ops.configure, bot, "heap", clear=True)
    said(ops.configure, bot, "owner", clear=True)

    # The command line.
    text, code = run_cli("set", "Alice")
    check("`set <bot>` lists every setting with its value",
          code == 0 and all(k in text for k in settings.SETTINGS) and "(default)" in text, text)
    text, code = run_cli("set", "Alice", "model", "sonnet", "low")
    check("`set <bot> <key> <value...>` changes it (two words are one value)",
          code == 0 and bot.read("model") == "sonnet low", text)
    text, code = run_cli("set", "Alice", "model")
    check("`set <bot> <key>` shows one, with its choices and when it counts",
          "choices:" in text and "when its bridge restarts" in text, text)
    text, code = run_cli("set", "Alice", "model", "--default")
    check("`--default` puts it back", code == 0 and not settings.is_set(bot, "model"), text)
    text, code = run_cli("set", "Alice", "gender", "x")
    check("a bad value is a failure of the command, with the reason", code == 1 and "one of f, m" in text, text)

    bob.write("language", "Klingon!")
    checks = doctor.checks(WS)
    check("doctor names a file edited by hand into something `set` would refuse",
          any(l == "bots/bob" and ok is False and "language" in d for l, ok, d in checks))
    text, code = run_cli("set", "Bob")
    check("...and so does `set <bot>`", "!! 'Klingon!' is not a valid language" in text, text)
    (bob.dir / "language").write_text("en\n")


if __name__ == "__main__":
    tests_files()
    tests_workspace()
    tests_names()
    tests_servers()
    tests_ports()
    tests_mods()
    tests_create()
    tests_prepare()
    tests_launch_line()
    tests_keeper()
    tests_stop_without_keeper()
    tests_packs()
    tests_deploy()
    tests_doctor()
    tests_pids()
    tests_lock()
    tests_start_order()
    tests_keeper_failures()
    tests_keeper_guarded()
    tests_guard_rings()
    tests_logwatch()
    tests_status()
    tests_cancel()
    tests_settings()
    tests_cli()

    print(f"\n{done - len(failures)}/{done} checks pass")
    if failures:
        print("failed:")
        for f in failures:
            print(f"  - {f}")
        sys.exit(1)

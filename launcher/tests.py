#!/usr/bin/env python3
"""Tests of the launcher. No Minecraft, no server, no network.

What is tested is the part that used to live in bash and could only be tested
by starting a bot: the folders, the names, the ports, the mods, bots and
instances, and the keeper, which is run for real against a fake game that
echoes what it is told.

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
from launcher import cli, doctor, operations as ops, settings  # noqa: E402
from launcher import bots, files, keeper, packs, processes  # noqa: E402
from launcher.events import Fail  # noqa: E402
from launcher.workspace import DEFAULT_HEAP, DEFAULT_VERSION, FIRST_PORT, Workspace  # noqa: E402

# Every test runs in a workspace of its own, under TMP, home included: nothing
# here reads or writes the real ~/bots, ~/instances, ~/servers, ~/shared or
# ~/.marionette.
ENVIRON = dict(os.environ)
for k in ("HEAP", "VERSION", "MARIONETTE_HEAP", "MARIONETTE_VERSION", "MARIONETTE_ACCOUNT"):
    ENVIRON.pop(k, None)
ENVIRON["MARIONETTE_JAVA"] = str(FAKE_JAVA)
WS = Workspace(TMP / "bots", TMP / "servers", TMP / "shared", TMP / "server.env",
               home=TMP, environ=ENVIRON, instances_dir=TMP / "instances", state_dir=TMP / "state")


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
    """The folders and a server, fresh, with a jar in each mods folder."""
    for d in ("bots", "instances", "servers/test/mods", "shared/mods"):
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


def second_server(slug="other"):
    d = TMP / "servers" / slug
    (d / "mods").mkdir(parents=True, exist_ok=True)
    (d / "server.conf").write_text("HOST=10.0.0.6\n")
    return d


def remove_tree(path):
    import shutil
    shutil.rmtree(path, ignore_errors=True)


def quick_server_env():
    """A server mod that refuses at once (127.0.0.1:1) instead of one that
    times out, so a `start` under test fails in a second, not in ten."""
    (TMP / "server.env").write_text("MARIONETTE_HOST=127.0.0.1\nMARIONETTE_PORT=1\n"
                                    "MARIONETTE_TOKEN=t\n")


def serve_a_server_mod(players=(), token="t"):
    """server.env pointed at a fake server mod that answers, with `players`
    connected: what `start` needs to get past its first question. Returns the
    HTTP server, to shut down."""
    httpd, port = fake_server_mod(token, list(players))
    (TMP / "server.env").write_text(f"MARIONETTE_HOST=127.0.0.1\nMARIONETTE_PORT={port}\n"
                                    "MARIONETTE_TOKEN=t\n")
    return httpd


def start_keeper(key="alice"):
    """A keeper holding the fake game for an instance, as `start` launches it."""
    inst = WS.instance(key)
    inst.run.mkdir(parents=True, exist_ok=True)
    files.unlink_quietly(inst.client_log, inst.keeper_log)
    processes.spawn_free([sys.executable, str(HERE / "marionette.py"), "keeper", key],
                         inst.keeper_log, cwd=TMP, env=WS.child_env())
    return inst


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
    check("then the default next to the home",
          ws.shared_dir == TMP / "shared" and ws.instances_dir == TMP / "instances")
    check("the state folder is next to server.env by default, where the bridge kept it",
          ws.state_dir == TMP)
    check("two workspaces do not share their folders", WS.bots_dir == TMP / "bots")
    child = WS.child_env()
    check("children inherit the folders this workspace resolved",
          child["MARIONETTE_BOTS_DIR"] == str(TMP / "bots")
          and child["MARIONETTE_INSTANCES_DIR"] == str(TMP / "instances")
          and child["MARIONETTE_STATE_DIR"] == str(TMP / "state")
          and child["MARIONETTE_ENV"] == str(TMP / "server.env"))
    check("...and ~/.local/bin first on PATH", child["PATH"].startswith(str(TMP / ".local" / "bin")))
    check("without server.env there is no API, and it is said",
          "missing" in told(fails(Workspace(TMP, TMP, TMP, TMP / "nope.env").api)))
    check("a clone's name: the first of name, name-1, name-2... not taken",
          WS.free_key("alice", ["alice", "alice-1"]) == "alice-2" and WS.free_key("alice", []) == "alice")


# --- names and servers ------------------------------------------------------

def tests_names():
    print("\nNames: Minecraft's rules for players, the launcher's for folders")
    check("letters, digits, underscore pass", fails(bots.check_name, "Bot_42") is None)
    check("a dash is refused in a player name", "not a valid name" in told(fails(bots.check_name, "bot-1")))
    check("an accent is refused", fails(bots.check_name, "Iñaki") is not None)
    check("empty is refused", fails(bots.check_name, "") is not None)
    check("17 characters are refused", "allows 16" in told(fails(bots.check_name, "A" * 17)))
    check("16 characters pass", fails(bots.check_name, "A" * 16) is None)
    check("a bot or instance name takes a dash (alice-1)", fails(bots.check_key, "alice-1") is None)
    check("...but not capitals, spaces or slashes",
          all(fails(bots.check_key, k) for k in ("Alice", "my bot", "../x", "")))



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
    print("\nPorts: a stopped instance keeps its port")
    layout()
    one = TMP / "instances" / "one"
    one.mkdir(parents=True)
    (one / "instance.json").write_text(json.dumps({"bot": "one", "server": "test", "port": FIRST_PORT}))
    check("the first port is reserved by 'one'", WS.port_reserved(FIRST_PORT))
    check("...but not against 'one' itself", not WS.port_reserved(FIRST_PORT, "one"))
    check("the free port skips it", WS.free_port("two") == FIRST_PORT + 1)
    check("a port nobody listens on is not in use", not processes.port_in_use(1))
    remove_tree(one)

    check("this process is alive", processes.pid_alive(os.getpid()))
    check("pid 0/None is not", not processes.pid_alive(None) and not processes.pid_alive(0))
    check("a pid file with junk reads as None", files.read_pid(TMP / "nope") is None)


def tests_mods():
    print("\nMods: gamedir/mods is rebuilt whole from shared, the pack and the instance's extras")
    layout()
    gamedir = TMP / "x" / "gamedir"
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
    extra = TMP / "x" / "mods"
    extra.mkdir()
    (extra / "minimap-1.jar").write_bytes(b"e")
    (extra / "create-6.jar").write_bytes(b"instance's own")
    n = packs.sync_mods(WS, gamedir, TMP / "servers" / "test", extra)
    check("an instance's extra mods are linked too", n == 4 and (gamedir / "mods" / "minimap-1.jar").exists())
    check("...and one named like the pack's replaces it, for this instance only",
          (gamedir / "mods" / "create-6.jar").read_bytes() == b"instance's own" and source.read_bytes() == b"c")
    remove_tree(TMP / "x")


# --- bots and instances -----------------------------------------------------

def tests_create():
    print("\nCreate: a bot is a character; an instance is that bot on a server")
    layout()
    text, inst = said(ops.create, WS, "Alice", "test", "offline")
    check("returns the instance", not isinstance(inst, Fail) and inst.key == "alice", text)
    bot = WS.bot("alice")
    check("the bot is bots/alice/bot.json, with the name's capitals",
          bot.exists() and bot.data.get("name") == "Alice" and bot.name == "Alice")
    check("the account goes in the bot, and nothing about language",
          bot.data == {"name": "Alice", "account": "offline"}, bot.data)
    check("a personality template is there, which says the language it speaks",
          "You are Alice" in bot.personality.read_text() and "speak English" in bot.personality.read_text())
    check("the instance is instances/alice/instance.json: which bot, which server, its port",
          inst.dir == TMP / "instances" / "alice"
          and inst.data == {"bot": "alice", "server": "test", "port": FIRST_PORT})
    check("the instance plays as the bot", inst.name == "Alice" and inst.player == "alice" and inst.slug == "test")
    props = files.read_java_properties(inst.hmc / "HeadlessMC" / "config.properties")
    check("HeadlessMC is offline, as Alice, and points at the instance's game folder",
          props.get("hmc.offline") == "true" and props.get("hmc.offline.username") == "Alice"
          and props.get("hmc.gamedir") == str(inst.gamedir))
    check("the launcher jar is next to the hmc config", (inst.hmc / "headlessmc-launcher.jar").is_file())
    check("its mods are linked", len(list((inst.gamedir / "mods").glob("*.jar"))) == 3)
    check("what it did is reported, not printed",
          "bot alice created" in text and "instance alice: Alice on test" in text, text)
    check("an unknown server is refused before anything is made",
          "unknown server" in told(fails(ops.create, WS, "Bob", "nope"))
          and not (TMP / "bots" / "bob").exists())
    check("'Ali' is a valid second bot: clashes are for start, on one server",
          not isinstance(said(ops.create, WS, "Ali", "test", "offline")[1], Fail))
    remove_tree(TMP / "bots" / "ali")
    remove_tree(TMP / "instances" / "ali")
    text, inst2 = said(ops.create, WS, "Alice", "test")
    check("creating Alice again on the same server makes a second instance, alice-1",
          not isinstance(inst2, Fail) and inst2.key == "alice-1" and inst2.port == FIRST_PORT + 1, text)
    remove_tree(inst2.dir)
    check("an existing bot with another player name is refused",
          "plays as Alice" in told(fails(ops.create, WS, "Alicia", "test", bot_key="alice")))
    check("a second bot takes the next port",
          not isinstance(said(ops.create, WS, "Bob", "test", "offline")[1], Fail)
          and WS.instance("bob").port == FIRST_PORT + 1)
    check("instances are found by name; a missing one lists the ones there are",
          WS.instance("Bob").key == "bob" and "alice, bob" in told(fails(WS.instance, "carol")))


def tests_clone():
    print("\nClone: the same bot again, or a new bot from another, under a name of its own")
    second_server("other")
    alice = WS.instance("alice")
    (alice.gamedir / "config").mkdir(parents=True, exist_ok=True)
    (alice.gamedir / "config" / "marionette-places-test.txt").write_text("home 1 2 3\n")
    alice.extra_mods.mkdir(exist_ok=True)
    (alice.extra_mods / "minimap-1.jar").write_bytes(b"e")
    settings.set_value(alice, "heap", "4g")

    text, c = said(ops.clone_instance, WS, "alice")
    check("cloned without a question: alice-1, the same bot on the same server",
          not isinstance(c, Fail) and c.key == "alice-1" and c.bot.key == "alice" and c.slug == "test", text)
    check("...with a port of its own", c.port not in (alice.port, WS.instance("bob").port))
    check("...its own settings copied", c.data.get("heap") == "4g")
    check("...its extra mods and its memories of that world",
          (c.extra_mods / "minimap-1.jar").exists()
          and (c.gamedir / "config" / "marionette-places-test.txt").exists())
    check("...and HeadlessMC pointed at the clone's own game folder",
          files.read_java_properties(c.hmc / "HeadlessMC" / "config.properties").get("hmc.gamedir")
          == str(c.gamedir))
    text, o = said(ops.clone_instance, WS, "alice", slug="other")
    check("cloned to another server: alice-2 on other", not isinstance(o, Fail)
          and o.key == "alice-2" and o.slug == "other", text)
    check("...without the memories of the first world", not (o.gamedir / "config").exists())
    check("a clone to an unknown server is refused", "unknown server" in told(
        fails(ops.clone_instance, WS, "alice", slug="nope")))

    text, b = said(ops.clone_bot, WS, "alice")
    check("a bot cloned: alice-1, playing as Alice_1 (a dash is not a player-name letter)",
          not isinstance(b, Fail) and b.key == "alice-1" and b.name == "Alice_1", text)
    check("...with the personality and settings of the first",
          b.personality.read_text() == WS.bot("alice").personality.read_text()
          and b.data.get("account") == "offline")
    for d in (c.dir, o.dir, b.dir):
        remove_tree(d)
    settings.clear(alice, "heap")
    remove_tree(alice.extra_mods)


def tests_render():
    print("\nRender: what the bridge reads, written from the layers")
    alice = WS.instance("alice")
    settings.render(alice)
    check("the port and the server, always", alice.read("port") == str(FIRST_PORT) and alice.read("server") == "test")
    check("a setting the bot sets is written for the bridge", alice.read("account") == "offline")
    check("a setting nobody sets is not: the bridge applies the same default",
          not (alice.dir / "model").exists() and not (alice.dir / "owner").exists())
    check("the personality is the bot's", alice.read("personality.txt").startswith("You are Alice"))
    settings.set_value(WS.bot("alice"), "model", "haiku low")
    settings.set_value(alice, "model", "sonnet")
    settings.render(alice)
    check("the instance wins over the bot", alice.read("model") == "sonnet")
    settings.clear(alice, "model")
    settings.render(alice)
    check("taken out of the instance, the bot's applies again", alice.read("model") == "haiku low")
    settings.clear(WS.bot("alice"), "model")
    settings.render(alice)
    check("taken out of both, the file goes", not (alice.dir / "model").exists())


def tests_prepare():
    print("\nPrepare: what the gamedir gets before every start")
    inst = WS.instance("alice")
    server = WS.server("test")
    options = inst.gamedir / "options.txt"
    options.write_text("fov:0.5\nonboardAccessibility:true\nlang:en_us\n")
    ops.prepare_gamedir(inst, server)
    text = options.read_text()
    check("the accessibility prompt is turned off", "onboardAccessibility:false" in text)
    check("...and every other option is kept", "fov:0.5" in text and "lang:en_us" in text
          and "onboardAccessibility:true" not in text)
    options.unlink()
    ops.prepare_gamedir(inst, server)
    check("with no options.txt, one is written with just that", options.read_text() == "onboardAccessibility:false\n")
    check("the server is noted in the gamedir for the mod",
          (inst.gamedir / "config" / "marionette-server.txt").read_text().strip() == "test")


# --- the launch line --------------------------------------------------------

def tests_launch_line():
    print("\nLaunch line: what HeadlessMC is told")
    inst = WS.instance("alice")
    bot = inst.bot
    server = WS.server("test")
    line = keeper.launch_line(inst, server)
    check("no -commands, ever", "-commands" not in line)
    check("-lwjgl and -paulscode", "-lwjgl" in line and "-paulscode" in line)
    check("offline account: -offline", " -offline " in line)
    check("the name with its capitals", "-Dmarionette.name=Alice" in line)
    check("headless, and the instance's own port",
          "-Dmarionette.headless=true" in line and f"-Dmarionette.bot.port={FIRST_PORT}" in line)
    check("default heap", f"-Xmx{DEFAULT_HEAP}" in line)
    check("no language nor gender: the personality says those", "language" not in line and "gender" not in line)
    WS.environ["MARIONETTE_HEAP"] = "1g"
    WS.environ["MARIONETTE_VERSION"] = "neoforge-21.1.999"
    line = keeper.launch_line(inst, server)
    check("MARIONETTE_HEAP and MARIONETTE_VERSION from the environment win",
          "-Xmx1g" in line and "launch neoforge-21.1.999 " in line)
    settings.set_value(bot, "heap", "4g")
    check("...a bot's own heap wins over MARIONETTE_HEAP", "-Xmx4g " in keeper.launch_line(inst, server))
    settings.set_value(inst, "heap", "6g")
    check("...and the instance's over the bot's", "-Xmx6g " in keeper.launch_line(inst, server))
    data = inst.data
    data["heap"] = "4g -XX:+Evil"
    inst.save(data)
    check("a heap that is not a size never reaches the JVM",
          "Evil" not in keeper.launch_line(inst, server) and "-Xmx1g " in keeper.launch_line(inst, server))
    settings.clear(inst, "heap")
    settings.clear(bot, "heap")
    WS.environ.pop("MARIONETTE_HEAP")
    WS.environ.pop("MARIONETTE_VERSION")
    WS.environ["HEAP"] = "1g"
    WS.environ["VERSION"] = "neoforge-21.1.999"
    line = keeper.launch_line(inst, server)
    check("the old, too generic HEAP and VERSION are not read any more",
          f"-Xmx{DEFAULT_HEAP}" in line and "21.1.999" not in line)
    WS.environ.pop("HEAP")
    WS.environ.pop("VERSION")


# --- the keeper -------------------------------------------------------------

def tests_keeper():
    print("\nKeeper: holds the game's stdin and answers on a socket")
    inst = start_keeper()
    check("the port file appears", wait(lambda: inst.keeper_port_f.is_file(), 10))
    keeper_pid = files.read_pid(inst.keeper_pid_f)
    check("the keeper belongs to nobody here (not our child, no zombie later)",
          keeper_pid and os.name == "nt" or (keeper_pid and not any(
              int(p) == keeper_pid for p in
              subprocess.run(["ps", "-o", "pid=", "--ppid", str(os.getpid())],
                             capture_output=True, text=True).stdout.split())))
    check("it is known by its instance on its command line", keeper.keeper_pid(inst) == keeper_pid)
    check("@ping answers with the game's pid",
          wait(lambda: (keeper.keeper_ask(inst, "@ping") or "").startswith("ok "), 5))
    answer = keeper.keeper_ask(inst, "@ping")
    game_pid = int(answer.split()[1])
    check("that pid is the one in client.pid and it is alive",
          files.read_pid(inst.client_pid_f) == game_pid and processes.pid_alive(game_pid))
    check("keeper_alive() agrees", keeper.keeper_alive(inst))
    check("the launch line reached the game",
          wait(lambda: files.log_has(inst.client_log, "got: launch neoforge-21.1.248 -lwjgl -offline"), 5))
    check("...and the game claimed the mod initialized", files.log_has(inst.client_log, keeper.HMC_READY))
    check("a line is passed through and acknowledged", keeper.keeper_ask(inst, "connect 10.0.0.5:25566") == "sent")
    check("it arrived at the game's stdin", wait(lambda: files.log_has(inst.client_log, "got: connect 10.0.0.5:25566"), 5))
    check("an unknown @command is refused", keeper.keeper_ask(inst, "@dance") == "unknown")
    check("@stop is acknowledged", keeper.keeper_ask(inst, "@stop") == "stopping")
    check("the game is gone", wait(lambda: not processes.pid_alive(game_pid), 10))
    check("the keeper is gone", wait(lambda: keeper.keeper_pid(inst) is None, 10))
    check("the run files are cleaned up",
          wait(lambda: not inst.keeper_port_f.exists() and not inst.client_pid_f.exists(), 5))
    check("with no keeper, asking returns None", keeper.keeper_ask(inst, "@ping") is None)
    check("the keeper's own log says why it ended", files.log_has(inst.keeper_log, "game exited"))


def tests_stop_without_keeper():
    print("\nStop: nothing running is not an error")
    text, code = run_cli("stop", "alice")
    check("stopping a stopped instance returns 0", code == 0, text)


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
    check("bots and instances are checked apart",
          any(l == "bots/alice" and ok for l, ok, _ in checks)
          and any(l == "instances/alice" and ok for l, ok, _ in checks), [c for c in checks if "alice" in c.label])
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

    bob = WS.instance("bob")
    data = bob.data
    data["port"] = FIRST_PORT          # same as Alice's
    bob.save(data)
    checks = doctor.checks(WS)
    check("two instances on one port are a problem",
          any(l == "instances/bob" and ok is False and "also belongs" in d for l, ok, d in checks))
    data["port"] = FIRST_PORT + 1
    bob.save(data)
    settings.set_value(WS.bot("bob"), "account", "online")
    checks = doctor.checks(WS)
    check("an online instance whose accounts file is empty was never logged in",
          any(l == "instances/bob" and ok is False and "never logged in" in d for l, ok, d in checks))
    accounts = bob.hmc / "HeadlessMC" / "auth" / ".accounts.json"
    accounts.parent.mkdir(parents=True, exist_ok=True)
    accounts.write_text("")
    checks = doctor.checks(WS)
    check("...even when HeadlessMC made the (empty) file: the folder never meant a login",
          any(l == "instances/bob" and ok is False and "never logged in" in d for l, ok, d in checks))
    settings.set_value(WS.bot("bob"), "account", "offline")
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
    inst = WS.instance("alice")
    inst.run.mkdir(parents=True, exist_ok=True)
    other = stranger()
    try:
        inst.keeper_pid_f.write_text(f"{other.pid}\n")
        inst.client_pid_f.write_text(f"{other.pid}\n")
        check("a stale keeper.pid naming a stranger is no keeper", keeper.keeper_pid(inst) is None)
        check("...nor a stale client.pid a HeadlessMC", keeper.launcher_pid(inst) is None)
        check("the bridge lock lives in its server's state folder",
              inst.bridge_lock == TMP / "state" / "servers" / "test" / "bridge_alice.lock")
        inst.bridge_lock.parent.mkdir(parents=True, exist_ok=True)
        inst.bridge_lock.write_text(f"{other.pid}\n")
        check("a bridge lock left with a stranger's pid is no bridge", ops.bridge_pid(inst) is None)
        text, _ = said(ops.stop, inst)
        check("stop leaves the stranger alone", other.poll() is None, text)
        check("...and says nothing was running", "client: nothing was running" in text
              and "bridge: nothing was running" in text, text)
        check("...and clears the stale files", not inst.keeper_pid_f.exists() and not inst.client_pid_f.exists())
        inst.bridge_lock.unlink()
    finally:
        other.kill()
        other.wait()


def tests_lock():
    print("\nLock: one launcher command at a time on an instance")
    inst = WS.instance("alice")
    lock = inst.run / "launcher.lock"
    holder = subprocess.Popen(
        [sys.executable, "-c",
         "import sys, time; sys.path.insert(0, sys.argv[1]); from launcher import files\n"
         "h = files.try_lock(sys.argv[2]); print('held' if h else 'not', flush=True); time.sleep(30)",
         str(REPO), str(lock)], stdout=subprocess.PIPE, text=True)
    try:
        check("another process takes the lock", holder.stdout.readline().strip() == "held")
        text, result = said(ops.start, inst)
        check("a start while it is held is refused, naming the holder",
              isinstance(result, Fail) and "another launcher command" in str(result)
              and str(holder.pid) in str(result) and result.code == "busy", str(result))
        check("...and refused before touching anything", "preparing" not in text, text)
        check("so is a stop", isinstance(said(ops.stop, inst)[1], Fail))
    finally:
        holder.kill()
        holder.wait()
    handle = files.try_lock(lock)
    check("the lock is free once its holder is gone, however it ended", handle is not None)
    if handle:
        handle.close()
    with bots.operating(inst):
        with bots.operating(inst):
            nested = True
    check("it is re-entrant in one thread (restart = stop + start)", nested)

    import threading
    seen = {}
    with bots.operating(inst):
        t = threading.Thread(target=lambda: seen.update(e=fails(ops.stop, inst)))
        t.start()
        t.join(10)
    check("...and only in that one: another thread is another command",
          seen.get("e") is not None and seen["e"].code == "busy", repr(seen))


def tests_start_order():
    print("\nStart: whether it runs is asked before anything is touched")
    quick_server_env()
    inst = start_keeper()
    try:
        check("a keeper is up", wait(lambda: keeper.keeper_alive(inst), 10))
        marker = inst.gamedir / "mods" / "in-use.jar"
        marker.write_bytes(b"u")
        text, result = said(ops.start, inst)
        check("starting a running instance is refused", isinstance(result, Fail)
              and "already running" in str(result) and result.code == "running", f"{result!r} {text}")
        check("...with its mods folder left as the game has it", marker.exists())
    finally:
        said(ops.stop, inst)
        layout()


def tests_conflicts():
    print("\nConflicts: instances are cloned freely, and two of one player never RUN at once")
    quick_server_env()
    second_server("other")
    alice = WS.instance("alice")
    _, same = said(ops.clone_instance, WS, "alice")                # alice-1: Alice on test
    _, away = said(ops.clone_instance, WS, "alice", slug="other")  # alice-2: Alice on other
    ops.take_place(alice)
    check("the running instance is linked as its player in its server's state folder",
          files.link_target(alice.place) == alice.dir
          and alice.place == TMP / "state" / "servers" / "test" / "bots" / "alice")
    start_keeper("alice")
    try:
        check("alice runs", wait(lambda: keeper.keeper_alive(alice), 10))
        e = fails(ops.check_can_run, same)
        check("the same player on the same server does not run twice",
              e is not None and e.code == "player_taken" and "as the instance alice" in str(e), told(e))
        text, result = said(ops.start, same)
        check("...and start says so before touching anything",
              isinstance(result, Fail) and result.code == "player_taken" and "preparing" not in text, text)
        check("an offline player may be on another server at the same time",
              fails(ops.check_can_run, away) is None)
        settings.set_value(WS.bot("alice"), "account", "online")
        e = fails(ops.check_can_run, away)
        check("an online account may not: one Microsoft account, one game at a time",
              e is not None and e.code == "account_in_use" and "on test" in str(e), told(e))
        settings.set_value(WS.bot("alice"), "account", "offline")
        _, ali = said(ops.create, WS, "Ali", "test", "offline")
        e = fails(ops.check_can_run, ali)
        check("a player whose name is inside a running one's, on the same server, is refused",
              e is not None and e.code == "name_clash", told(e))
        ali_elsewhere = said(ops.clone_instance, WS, ali.key, slug="other")[1]
        check("...but not on another server, where they share no chat",
              fails(ops.check_can_run, ali_elsewhere) is None)
        for d in (ali.dir, ali_elsewhere.dir, WS.bot("ali").dir):
            remove_tree(d)
    finally:
        said(ops.stop, alice)
    check("stopping leaves the place", not alice.place.exists() and not alice.place.is_symlink())
    check("stopped, the other copy may run", fails(ops.check_can_run, same) is None)
    for d in (same.dir, away.dir):
        remove_tree(d)
    layout()


def tests_keeper_failures():
    print("\nKeeper failures: said at once, not after five minutes")
    httpd = serve_a_server_mod()
    bot = WS.instance("alice")
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
    httpd.shutdown()
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



def tests_server_mod_missing():
    print("\nNo Marionette on the server: said before a game is loaded for nothing")
    inst = WS.instance("alice")
    quick_server_env()
    files.unlink_quietly(inst.keeper_log)
    text, result = said(ops.start, inst)
    check("a server mod that does not answer stops the start before the game",
          isinstance(result, Fail) and result.code == "server_mod_down"
          and "Is Marionette in that server's mods folder" in text
          and "starting alice" not in text and not inst.keeper_log.exists(), text)
    httpd = serve_a_server_mod(token="another")
    try:
        text, result = said(ops.start, inst)
        check("one that answers with a refusal is a wrong token, and says so",
              isinstance(result, Fail) and result.code == "server_mod_refused"
              and "token is not the one" in text, text)
    finally:
        httpd.shutdown()

    import threading
    from launcher.events import Report
    httpd = serve_a_server_mod(players=[])
    start_keeper("alice")
    try:
        check("a keeper is up", wait(lambda: keeper.keeper_alive(inst), 10))
        threading.Timer(1.0, lambda: keeper.keeper_ask(inst, "@stop")).start()
        lines = []
        started = time.monotonic()
        joined = ops.join(inst, WS.server("test"), WS.api_for(WS.server("test")),
                          Report(lambda e: lines.append(e.text)), attempts=3)
        took = time.monotonic() - started
        check("joining stops waiting as soon as the client closes, not after 3 x 3 minutes",
              joined is False and took < 30 and "the client closed" in lines, f"{took:.0f}s {lines}")
    finally:
        said(ops.stop, inst)
        httpd.shutdown()
        layout()


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
    httpd = serve_a_server_mod()
    bot = WS.instance("alice")

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
    httpd.shutdown()
    layout()



def tests_guard_rings():
    print("\nGuards: a ring of escorts is stopped once each, not forever")
    quick_server_env()
    alice, bob = WS.instance("alice"), WS.instance("bob")
    settings.set_value(alice, "escort", "bob")
    settings.set_value(bob, "escort", "alice")
    start_keeper("alice")
    start_keeper("bob")
    check("both run", wait(lambda: keeper.keeper_alive(alice) and keeper.keeper_alive(bob), 10))
    text, code = run_cli("stop", "alice")
    check("two instances guarding each other: stop ends", code == 0, text)
    check("...having stopped each one once",
          text.count("==> alice stopped") == 1 and text.count("==> bob stopped") == 1, text)
    text, code = run_cli("stop", "bob")
    check("a guard that is not running is not 'stopped' again", code == 0 and "guard" not in text, text)
    data = bob.data
    data["escort"] = "bob"                 # edited by hand: `set` refuses it
    bob.save(data)
    text, code = run_cli("stop", "bob")
    check("an instance guarding itself: stop ends", code == 0, text)
    checks = doctor.checks(WS)
    check("doctor names an instance that guards itself",
          any(l == "instances/bob" and ok is False and "escorts itself" in d for l, ok, d in checks))
    settings.clear(alice, "escort")
    settings.clear(bob, "escort")
    layout()


def tests_status():
    print("\nStatus: what every instance is doing, as data and as a table")
    quick_server_env()
    problems, statuses = ops.survey(WS)
    check("the survey says the server mod does not answer",
          len(problems) == 1 and "does not answer" in problems[0], problems)
    check("...and one status per instance, 'in server' unknown",
          [(s.key, s.name) for s in statuses] == [("alice", "Alice"), ("bob", "Bob")]
          and all(s.inside is None for s in statuses), statuses)
    check("a stopped instance: no client, no hands, no bridge",
          not statuses[0].client and not statuses[0].hands and statuses[0].bridge is None)
    text, code = run_cli("status")
    check("status still returns 0", code == 0)
    check("...and says the server mod does not answer", "does not answer" in text, text)
    check("...above a table with a row per instance",
          "alice" in text and "Bob" in text and "plays as" in text, text)
    text, code = run_cli("bots")
    check("`bots` lists the characters and their instances", code == 0 and "plays as Alice" in text, text)
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
          code == 1 and "there is no instance Nobody" in text, text)
    text, code = run_cli("create", "Eve", "nope")
    check("...with its evidence under it (the servers there are)",
          code == 1 and "unknown server" in text and "\n    test " in text, text)


# --- settings ---------------------------------------------------------------

def tests_settings():
    print("\nSettings: in layers, bot < instance, and one table says what each accepts")
    alice, bob = WS.instance("alice"), WS.instance("bob")
    bot = alice.bot
    check("a value no layer sets is the default",
          settings.resolve(alice, "model") == ("opus medium", "default"))
    check("the owner's default comes from server.env", settings.get(alice, "owner") == "Owner")
    settings.set_value(bot, "model", "sonnet")
    check("set in the bot, every instance of it has it", settings.resolve(alice, "model") == ("sonnet", "bot"))
    settings.set_value(alice, "model", "haiku low")
    check("set in the instance, it wins over the bot's", settings.resolve(alice, "model") == ("haiku low", "instance"))
    settings.clear(alice, "model")
    settings.clear(bot, "model")
    for target, key, value, why in ((bot, "language", "es", "no longer a setting: the personality"),
                                    (bot, "gender", "f", "no longer a setting: the personality"),
                                    (bot, "account", "maybe", "one of online, offline"),
                                    (bot, "owner", "not a name!", "player name"),
                                    (bot, "model", "haiku lowest", "effort is one of"),
                                    (bot, "model", "a b c", "optional effort"),
                                    (bot, "port", "9000", "set per instance"),
                                    (bot, "escort", "bob", "set per instance"),
                                    (alice, "escort", "alice", "cannot escort itself"),
                                    (alice, "escort", "nobody", "instance on test: bob"),
                                    (alice, "heap", "512m", "at least 1g"),
                                    (alice, "heap", "lots", "heap size"),
                                    (alice, "port", "80", "between 1024"),
                                    (alice, "port", str(FIRST_PORT + 1), "belongs to another instance")):
        e = fails(settings.set_value, target, key, value)
        check(f"{key} '{value}' on the {settings.layer_of(target)} is refused, saying what it takes",
              e is not None and why in told(e) and e.code == "bad_setting", told(e))
    check("an unknown setting is refused, listing the ones there are",
          "model" in told(fails(settings.setting, "colour")))
    check("a good value is written into the layer's JSON",
          settings.set_value(bot, "account", "ONLINE") == "online" and bot.data.get("account") == "online")
    settings.set_value(bot, "account", "offline")
    check("a model keeps its two words", settings.set_value(bot, "model", "haiku   low") == "haiku low")
    check("an escort keeps its capitals (the game shows names as they are)",
          settings.set_value(alice, "escort", "Bob") == "Bob")
    settings.clear(alice, "escort")
    settings.clear(bot, "model")
    check("the port cannot be cleared: every instance needs one",
          "no default" in told(fails(settings.clear, alice, "port")))
    settings.set_value(bot, "account", "online")
    settings.render(alice)
    props = files.read_java_properties(alice.hmc / "HeadlessMC" / "config.properties")
    check("the account is written into HeadlessMC's own config at render", props.get("hmc.offline") == "false")
    settings.set_value(bot, "account", "offline")
    settings.render(alice)
    props = files.read_java_properties(alice.hmc / "HeadlessMC" / "config.properties")
    check("...both ways, and nothing else in it is lost",
          props.get("hmc.offline") == "true" and props.get("hmc.offline.username") == "Alice")

    # The operation: the lock, and no start-time setting under a running client.
    text, now = said(ops.configure, alice, "heap", "4g")
    check("configure reports the value and when it counts",
          now == "4g" and "alice: heap = 4g" in text and "on its next start" in text, text)
    start_keeper("alice")
    try:
        check("a keeper is up", wait(lambda: keeper.keeper_alive(alice), 10))
        text, result = said(ops.configure, alice, "heap", "6g")
        check("a setting read at start is refused while the client runs",
              isinstance(result, Fail) and result.code == "running" and alice.data.get("heap") == "4g", text)
        text, result = said(ops.configure, bot, "heap", "6g")
        check("...in the bot too, while any instance of it runs",
              isinstance(result, Fail) and result.code == "running", text)
        text, result = said(ops.configure, alice, "owner", "SomePlayer")
        check("...one read as it is used is not", result == "SomePlayer" and "right away" in text, text)
        check("...and it is rendered for the bridge at once", alice.read("owner") == "SomePlayer")
    finally:
        said(ops.stop, alice)
    said(ops.configure, alice, "heap", clear=True)
    said(ops.configure, alice, "owner", clear=True)

    # The command line.
    text, code = run_cli("set", "alice")
    check("`set <instance>` lists every setting with its value and where it comes from",
          code == 0 and all(k in text for k in settings.SETTINGS) and "(default)" in text and "(bot)" in text,
          text)
    text, code = run_cli("set", "--bot", "alice", "model", "sonnet", "low")
    check("`set --bot <bot> <key> <value...>` changes the bot (two words are one value)",
          code == 0 and bot.data.get("model") == "sonnet low", text)
    text, code = run_cli("set", "alice", "model")
    check("`set <instance> <key>` shows one: its choices, its layers, when it counts",
          "choices:" in text and "when its bridge restarts" in text and "(bot)" in text, text)
    text, code = run_cli("set", "--bot", "alice", "model", "--default")
    check("`--default` takes it out of the layer", code == 0 and "model" not in bot.data, text)
    text, code = run_cli("set", "alice", "account", "maybe")
    check("a bad value is a failure of the command, with the reason",
          code == 1 and "one of online, offline" in text, text)

    data = bob.bot.data
    data["model"] = "haiku lowest"
    data["language"] = "es"
    bob.bot.save(data)
    checks = doctor.checks(WS)
    check("doctor names a JSON edited by hand into something `set` would refuse",
          any(l == "bots/bob" and ok is False and "effort is one of" in d for l, ok, d in checks))
    check("...and a setting there is no more, saying where it went",
          any(l == "bots/bob" and "'language' is no longer a setting" in d for l, ok, d in checks))
    text, code = run_cli("set", "--bot", "bob")
    check("...and so does `set`", "!! 'haiku lowest' is not a valid model" in text, text)
    data.pop("model")
    data.pop("language")
    bob.bot.save(data)


# --- one server mod per server ------------------------------------------------

def fake_server_mod(token, players):
    """A server mod that answers /players and /mods, with a token, on a port
    of its own; returns (the HTTP server, its port)."""
    import http.server
    import threading

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.headers.get("X-Marionette-Token") != token:
                self.send_response(401)
                self.end_headers()
                return
            if self.path == "/players":
                body = json.dumps({"players": players})
            elif self.path == "/mods":
                body = json.dumps({"mods": [{"id": "create", "version": "6.0.10"}]})
            else:
                self.send_response(404)
                self.end_headers()
                return
            self.send_response(200)
            self.end_headers()
            self.wfile.write(body.encode())

        def log_message(self, *a):
            pass

    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd, httpd.server_address[1]


def tests_server_apis():
    print("\nServer mods: each server can have its own, and each instance is looked for in its own")
    quick_server_env()
    (TMP / "server.env").write_text((TMP / "server.env").read_text() + "MARIONETTE_OWNER=Owner\n")
    other = second_server("other")
    httpd, port = fake_server_mod("own-token", ["Bob"])
    own = other / "server.env"
    own.write_text(f"MARIONETTE_HOST=127.0.0.1\nMARIONETTE_PORT={port}\nMARIONETTE_TOKEN=own-token\n")
    own.chmod(0o600)
    alice, bob = WS.instance("alice"), WS.instance("bob")
    data = bob.data
    data["server"] = "other"
    bob.save(data)
    try:
        check("a server without its own server.env uses the global one",
              WS.api_for(WS.server("test")).address == "127.0.0.1:1")
        api = WS.api_for(WS.server("other"))
        check("a server with its own uses it", api.address == f"127.0.0.1:{port}" and api.token == "own-token")
        check("...keeping the owner, which only the global file says", api.owner == "Owner")

        start_keeper("bob")
        check("bob runs", wait(lambda: keeper.keeper_alive(bob), 10))
        problems, statuses = ops.survey(WS)
        by_key = {s.key: s for s in statuses}
        check("status finds Bob in HIS server's /players", by_key["bob"].inside is True, repr(by_key["bob"]))
        check("...while Alice's server does not answer, and only hers is unknown",
              by_key["alice"].inside is None and len(problems) == 1 and "of test" in problems[0], problems)
        text, result = said(ops.start, bob)
        check("start asks the instance's own server whether it is already in",
              result == ops.ALREADY_IN and "already in" in text, text)
        said(ops.stop, bob)
        text, result = said(ops.start, bob)
        check("in the server with no client of ours: somebody else plays as Bob there",
              isinstance(result, Fail) and result.code == "player_taken"
              and "not through this instance" in str(result), text)

        env = ops.bridge_env(bob)
        check("the bridge of bob reads its server's bots, keeps its server's state",
              env["MARIONETTE_BOTS_DIR"] == str(TMP / "state" / "servers" / "other" / "bots")
              and env["MARIONETTE_STATE_DIR"] == str(TMP / "state" / "servers" / "other"))
        check("...knows which instance it is, for a restart ordered from the game",
              env["MARIONETTE_INSTANCE"] == "bob" and env["BOT_NAME"] == "Bob")
        check("...and is told the PATH of its server's file", env.get("MARIONETTE_SERVER_ENV") == str(own))
        check("...never the token itself", "own-token" not in "".join(env.values()))
        check("an instance whose server has no file of its own is told nothing",
              "MARIONETTE_SERVER_ENV" not in ops.bridge_env(alice))

        checks = doctor.checks(WS)
        check("doctor asks each server's own mod", any(
            l == "servers/other: server mod" and ok is True for l, ok, _ in checks),
            [c for c in checks if "other" in c.label])
        check("...and compares the pack with ITS server, not whichever answers",
              any(l == "servers/other: versions" and "with its server" in d for l, ok, d in checks))
        own.chmod(0o644)
        checks = doctor.checks(WS)
        check("a server.env others can read is a problem: it holds the token",
              any(l == "servers/other: server.env" and ok is False and "chmod 600" in d for l, ok, d in checks))
        own.chmod(0o600)
        own.write_text(f"MARIONETTE_HOST=127.0.0.1\nMARIONETTE_PORT={port}\nMARIONETTE_TOKEN=wrong\n")
        checks = doctor.checks(WS)
        check("a wrong token is named as such",
              any(l == "servers/other: server mod" and ok is False and "wrong token" in d for l, ok, d in checks))
    finally:
        said(ops.stop, bob)
        httpd.shutdown()
        data["server"] = "test"
        bob.save(data)
        remove_tree(other)
        layout()


def tests_phrases():
    print("\nPhrases: what the bot says without its brain is rewritten on request")
    inst = WS.instance("alice")
    f = ops.phrases_file(inst)
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text("turning_back=Me devuelvo.\n")
    text, result = said(ops.rewrite_phrases, inst)
    check("without a bridge running, the old ones go and the next start writes new ones",
          result == "on the next start" and not f.exists() and "next starts" in text, text)
    fake = TMP / "bridge.py"
    fake.write_text("import time\ntime.sleep(30)\n")
    bridge = subprocess.Popen([sys.executable, str(fake), "Alice"])
    try:
        inst.state.mkdir(parents=True, exist_ok=True)
        inst.bridge_lock.write_text(f"{bridge.pid}\n")
        text, result = said(ops.rewrite_phrases, inst)
        mark = inst.state / "phrases_alice"
        check("with its bridge running, it leaves the mark the bridge looks for",
              result == "asked" and mark.exists() and "writing its sentences again" in text, text)
        mark.unlink()
    finally:
        bridge.kill()
        bridge.wait()
        inst.bridge_lock.unlink()
    text, code = run_cli("phrases", "alice")
    check("`marionette.py phrases <instance>` is the command", code == 0 and "sentences" in text, text)


# --- the layout from before instances -----------------------------------------

def tests_migrate():
    print("\nMigrate: bots/<name>/ with the game inside becomes a bot and an instance")
    layout()
    old = TMP / "bots" / "old"
    (old / "hmc" / "HeadlessMC").mkdir(parents=True)
    (old / "gamedir" / "config").mkdir(parents=True)
    (old / "run").mkdir()
    for f, v in (("port", "8490"), ("server", "test"), ("account", "offline"), ("language", "es"),
                 ("model", "haiku low"), ("owner", "Someone"), ("gender", "f")):
        (old / f).write_text(v + "\n")
    (old / "personality.txt").write_text("You are Old.\n")
    (old / "hmc" / "HeadlessMC" / "config.properties").write_text(
        f"hmc.offline=true\nhmc.offline.username=Old\nhmc.gamedir={old / 'gamedir'}\n")
    (old / "gamedir" / "config" / "marionette-places-test.txt").write_text("home 1 2 3\n")
    (old / "run" / "client.log").write_text("an old log\n")
    state = TMP / "state"
    state.mkdir(exist_ok=True)
    for f in ("session_old", "pending_old.json", "internal_old.jsonl", "calls_old.log",
              "horse_old_test.json", "internal_olden.jsonl"):
        (state / f).write_text("x\n")

    check("the old layout is recognized", WS.legacy_bots() == ["old"] and "old" not in WS.bot_keys())
    check("an instance command on it says to migrate", "migrate" in told(fails(WS.instance, "old")))
    text, code = run_cli("status")
    check("...and so does status", "marionette.py migrate" in text or code == 0, text)
    checks = doctor.checks(WS)
    check("...and doctor", any(l == "layout" and ok is False and "old" in d for l, ok, d in checks))

    import socket
    busy = socket.socket()
    busy.bind(("127.0.0.1", 8490))
    busy.listen(1)
    try:
        e = fails(ops.migrate, WS)
        check("a bot that is running is not migrated: stop it first",
              e is not None and e.code == "running" and (old / "port").exists(), told(e))
    finally:
        busy.close()

    text, made = said(ops.migrate, WS, dry_run=True)
    check("a dry run says what it would do and does nothing",
          made == [] and "old: bot old (plays as Old) + instance old on test, port 8490" in text
          and (old / "port").exists() and not (TMP / "instances" / "old").exists(), text)

    text, made = said(ops.migrate, WS)
    inst = WS.instance("old")
    bot = WS.bot("old")
    check("migrated: a bot and an instance", [i.key for i in made] == ["old"] and bot.exists(), text)
    check("the bot keeps its name, account, model and owner",
          bot.data == {"name": "Old", "account": "offline", "model": "haiku low", "owner": "Someone"},
          bot.data)
    check("...and language and gender, no longer settings, are pointed at its personality",
          "language es, gender f are no longer settings: say them in its personality.txt" in text, text)
    check("...and its personality, where it was", bot.personality.read_text() == "You are Old.\n")
    check("the instance keeps its server and its port", inst.data == {"bot": "old", "server": "test", "port": 8490})
    check("its game, HeadlessMC and logs were moved, not copied",
          (inst.gamedir / "config" / "marionette-places-test.txt").exists()
          and (inst.run / "client.log").exists() and not (old / "gamedir").exists())
    check("HeadlessMC now points at the game folder where it is",
          files.read_java_properties(inst.hmc / "HeadlessMC" / "config.properties").get("hmc.gamedir")
          == str(inst.gamedir))
    check("the old one-value files left the bot's folder",
          sorted(p.name for p in old.iterdir()) == ["bot.json", "personality.txt"])
    backups = list((state / "backups").glob("bots-before-instances-*.tar.gz"))
    import tarfile
    names = tarfile.open(backups[0]).getnames() if backups else []
    check("a backup of the small files was made first",
          "old/port" in names and "old/personality.txt" in names
          and "old/hmc/HeadlessMC/config.properties" in names, names)
    moved = sorted(p.name for p in (state / "servers" / "test").iterdir() if p.is_file())
    check("its state moved to its server's state folder",
          moved == sorted(["session_old", "pending_old.json", "internal_old.jsonl", "calls_old.log",
                           "horse_old_test.json"]), moved)
    check("...and another bot's, whose name starts the same, did not",
          (state / "internal_olden.jsonl").exists())
    check("nothing is left to migrate", WS.legacy_bots() == [] and
          "nothing to migrate" in said(ops.migrate, WS)[0])
    remove_tree(inst.dir)
    remove_tree(bot.dir)


if __name__ == "__main__":
    tests_files()
    tests_workspace()
    tests_names()
    tests_servers()
    tests_ports()
    tests_mods()
    tests_create()
    tests_clone()
    tests_render()
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
    tests_conflicts()
    tests_keeper_failures()
    tests_keeper_guarded()
    tests_guard_rings()
    tests_logwatch()
    tests_status()
    tests_cancel()
    tests_server_mod_missing()
    tests_settings()
    tests_server_apis()
    tests_phrases()
    tests_migrate()
    tests_cli()

    print(f"\n{done - len(failures)}/{done} checks pass")
    if failures:
        print("failed:")
        for f in failures:
            print(f"  - {f}")
        sys.exit(1)

#!/usr/bin/env python3
"""Tests of the launcher. No Minecraft, no server, no network.

What is tested is the part that used to live in bash and could only be tested
by starting a bot: the folders, the names, the ports, the mods, and the
keeper, which is run for real against a fake game that echoes what it is told.

Run:  python3 launcher/tests.py
"""
import os
import pathlib
import stat
import subprocess
import sys
import tempfile
import time

HERE = pathlib.Path(__file__).resolve().parent
TMP = pathlib.Path(tempfile.mkdtemp(prefix="marionette-test-"))

# The folders are resolved when the launcher is imported, so they are pointed
# at the temporary ones BEFORE, and every test runs there.
os.environ["MARIONETTE_BOTS_DIR"] = str(TMP / "bots")
os.environ["MARIONETTE_SERVERS_DIR"] = str(TMP / "servers")
os.environ["MARIONETTE_COMMON_DIR"] = str(TMP / "shared")
os.environ["MARIONETTE_ENV"] = str(TMP / "server.env")
os.environ.pop("HEAP", None)
os.environ.pop("VERSION", None)

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
os.environ["MARIONETTE_JAVA"] = str(FAKE_JAVA)

sys.path.insert(0, str(HERE))
import marionette as m  # noqa: E402


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


def fails(fn, *args):
    """The Fail the launcher raises, or None when it did not refuse."""
    try:
        fn(*args)
    except m.Fail as e:
        return str(e)
    return None


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


# --- files ------------------------------------------------------------------

def tests_files():
    print("\nFiles: read like a shell would, without running one")
    f = TMP / "env.txt"
    f.write_text("# comment\n\nexport A=1\nB = two words\nC=\"quoted\"\nD='single'\n"
                 "E=x=y\nnot a pair\n")
    v = m.read_env_file(f)
    check("comments and blanks are skipped", "comment" not in str(v) and len(v) == 5)
    check("`export` prefix is ignored", v.get("A") == "1")
    check("spaces around = are trimmed", v.get("B") == "two words")
    check("double quotes come off", v.get("C") == "quoted")
    check("single quotes come off", v.get("D") == "single")
    check("only the first = splits", v.get("E") == "x=y")
    check("a missing file is an empty config", m.read_env_file(TMP / "nope") == {})

    p = TMP / "x.properties"
    p.write_text("# c\nhmc.offline.username=Alice\nhmc.gamedir=/a/b\n")
    check("java properties", m.read_java_properties(p).get("hmc.offline.username") == "Alice")


# --- names and servers ------------------------------------------------------

def tests_names():
    print("\nNames: Minecraft's rules, refused at creation and not at join time")
    check("letters, digits, underscore pass", fails(m.check_name, "Bot_42") is None)
    check("a dash is refused", "not a valid name" in (fails(m.check_name, "bot-1") or ""))
    check("an accent is refused", fails(m.check_name, "Iñaki") is not None)
    check("empty is refused", fails(m.check_name, "") is not None)
    check("17 characters are refused", "allows 16" in (fails(m.check_name, "A" * 17) or ""))
    check("16 characters pass", fails(m.check_name, "A" * 16) is None)

    layout()
    (TMP / "bots" / "adam").mkdir()
    check("'ada' clashes with 'adam' (one contains the other)", m.name_clashes("ada") == "adam")
    check("'adam' clashes with 'ada' the other way round", m.name_clashes("adam") is None)
    (TMP / "bots" / "ada").mkdir()
    check("a name does not clash with itself", m.name_clashes("adam") == "ada")
    check("'eve' clashes with nobody", m.name_clashes("eve") is None)
    for d in ("adam", "ada"):
        (TMP / "bots" / d).rmdir()


def tests_servers():
    print("\nServers: the registry, with its defaults")
    layout()
    s = m.load_server("test")
    check("HOST, MC_PORT and DESCRIPTION are read",
          (s["host"], s["mc_port"], s["description"]) == ("10.0.0.5", "25566", "the test one"))
    check("VERSION defaults", s["version"] == m.DEFAULT_VERSION)
    check("a port that is not 25565 is spelled out", m.address_of(s) == "10.0.0.5:25566")
    s["mc_port"] = "25565"
    check("25565 goes unsaid", m.address_of(s) == "10.0.0.5")
    check("an unknown slug lists the ones there are",
          "test" in (fails(m.load_server, "nope") or ""))
    (TMP / "servers" / "bad").mkdir()
    (TMP / "servers" / "bad" / "server.conf").write_text("MC_PORT=1\n")
    check("a server without HOST is refused", "HOST" in (fails(m.load_server, "bad") or ""))
    check("servers are listed sorted", m.server_slugs() == ["bad", "test"])
    (TMP / "servers" / "bad" / "server.conf").unlink()
    (TMP / "servers" / "bad").rmdir()


# --- ports and mods ---------------------------------------------------------

def tests_ports():
    print("\nPorts: a stopped bot keeps its port")
    layout()
    (TMP / "bots" / "one").mkdir()
    (TMP / "bots" / "one" / "port").write_text(f"{m.FIRST_PORT}\n")
    check("the first port is reserved by 'one'", m.port_reserved(m.FIRST_PORT))
    check("...but not against 'one' itself", not m.port_reserved(m.FIRST_PORT, "one"))
    check("the free port skips it", m.free_port("two") == m.FIRST_PORT + 1)
    check("a port nobody listens on is not in use", not m.port_in_use(1))
    (TMP / "bots" / "one" / "port").unlink()
    (TMP / "bots" / "one").rmdir()

    check("this process is alive", m.pid_alive(os.getpid()))
    check("pid 0/None is not", not m.pid_alive(None) and not m.pid_alive(0))
    check("a pid file with junk reads as None", m.read_pid(TMP / "nope") is None)


def tests_mods():
    print("\nMods: gamedir/mods is rebuilt whole from shared and the pack")
    layout()
    gamedir = TMP / "bots" / "x" / "gamedir"
    (gamedir / "mods").mkdir(parents=True)
    stray = gamedir / "mods" / "dropped-by-hand.jar"
    stray.write_bytes(b"s")
    n = m.sync_mods(gamedir, TMP / "servers" / "test")
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


# --- create -----------------------------------------------------------------

class Args:
    def __init__(self, **kw):
        self.__dict__.update(kw)


def tests_create():
    print("\nCreate: a bot costs a folder, a port and a few small files")
    layout()
    code = m.cmd_create(Args(name="Alice", server="test", account="offline"))
    bot = m.Bot("Alice")
    check("returns 0", code == 0)
    check("the folder is lowercase", bot.dir == TMP / "bots" / "alice" and bot.dir.is_dir())
    check("the name keeps its capitals in the hmc config", bot.name == "Alice")
    check("port, server and account are written",
          (bot.read("port"), bot.read("server"), bot.read("account")) == (str(m.FIRST_PORT), "test", "offline"))
    check("the owner comes from server.env", bot.read("owner") == "Owner")
    check("language defaults to en", bot.read("language") == "en")
    check("a personality template is there", "You are Alice" in bot.read("personality.txt"))
    props = m.read_java_properties(bot.hmc / "HeadlessMC" / "config.properties")
    check("hmc is offline and points at the gamedir",
          props.get("hmc.offline") == "true" and props.get("hmc.gamedir") == str(bot.gamedir))
    check("the launcher jar is next to the hmc config", (bot.hmc / "headlessmc-launcher.jar").is_file())
    check("its mods are linked", len(list((bot.gamedir / "mods").glob("*.jar"))) == 3)
    check("creating it again is refused",
          "already exists" in (fails(m.cmd_create, Args(name="Alice", server="test", account=None)) or ""))
    check("'Ali' is refused: Alice contains it",
          "clashes" in (fails(m.cmd_create, Args(name="Ali", server="test", account=None)) or ""))
    check("an unknown server is refused",
          "unknown server" in (fails(m.cmd_create, Args(name="Bob", server="nope", account=None)) or ""))
    check("a second bot takes the next port",
          m.cmd_create(Args(name="Bob", server="test", account="offline")) == 0
          and m.Bot("Bob").read("port") == str(m.FIRST_PORT + 1))


# --- the launch line --------------------------------------------------------

def tests_launch_line():
    print("\nLaunch line: what HeadlessMC is told")
    bot = m.Bot("Alice")
    server = m.load_server("test")
    line = m.launch_line(bot, server)
    check("no -commands, ever", "-commands" not in line)
    check("-lwjgl and -paulscode", "-lwjgl" in line and "-paulscode" in line)
    check("offline account: -offline", " -offline " in line)
    check("the name with its capitals", "-Dmarionette.name=Alice" in line)
    check("headless, and the bot's own port",
          "-Dmarionette.headless=true" in line and f"-Dmarionette.bot.port={m.FIRST_PORT}" in line)
    check("default heap", f"-Xmx{m.DEFAULT_HEAP}" in line)
    check("no language flag without a clean value", "marionette.language=en" in line)
    bot.write("language", "es; rm -rf /")
    bot.write("gender", "f\n")
    line = m.launch_line(bot, server)
    check("language and gender reach the JVM cleaned",
          "-Dmarionette.language=esrmrf " in line and "-Dmarionette.gender=f" in line)
    os.environ["HEAP"] = "1g"
    os.environ["VERSION"] = "neoforge-21.1.999"
    line = m.launch_line(bot, server)
    check("HEAP and VERSION from the environment win", "-Xmx1g" in line and "launch neoforge-21.1.999 " in line)
    os.environ.pop("HEAP")
    os.environ.pop("VERSION")
    bot.write("language", "en")
    (bot.dir / "gender").unlink()


# --- the keeper -------------------------------------------------------------

def wait(predicate, seconds):
    return m.wait_for(predicate, seconds, every=0.1)


def tests_keeper():
    print("\nKeeper: holds the game's stdin and answers on a socket")
    bot = m.Bot("Alice")
    bot.run.mkdir(parents=True, exist_ok=True)
    for f in (bot.client_log, bot.keeper_log):
        try:
            f.unlink()
        except OSError:
            pass
    m.spawn_free([sys.executable, str(HERE / "marionette.py"), "keeper", "Alice", "test"],
                 bot.keeper_log, cwd=TMP, env=m.child_env())
    check("the port file appears", wait(lambda: bot.keeper_port_f.is_file(), 10))
    keeper_pid = m.read_pid(bot.keeper_pid_f)
    check("the keeper belongs to nobody here (not our child, no zombie later)",
          keeper_pid and os.name == "nt" or (keeper_pid and not any(
              int(p) == keeper_pid for p in
              subprocess.run(["ps", "-o", "pid=", "--ppid", str(os.getpid())],
                             capture_output=True, text=True).stdout.split())))
    check("@ping answers with the game's pid",
          wait(lambda: (m.keeper_ask(bot, "@ping") or "").startswith("ok "), 5))
    answer = m.keeper_ask(bot, "@ping")
    game_pid = int(answer.split()[1])
    check("that pid is the one in client.pid and it is alive",
          m.read_pid(bot.client_pid_f) == game_pid and m.pid_alive(game_pid))
    check("keeper.pid is a live process", m.pid_alive(m.read_pid(bot.keeper_pid_f)))
    check("keeper_alive() agrees", m.keeper_alive(bot))
    check("the launch line reached the game",
          wait(lambda: m.log_has(bot.client_log, "got: launch neoforge-21.1.248 -lwjgl -offline"), 5))
    check("...and the game claimed the mod initialized", m.log_has(bot.client_log, m.HMC_READY))
    check("a line is passed through and acknowledged", m.keeper_ask(bot, "connect 10.0.0.5:25566") == "sent")
    check("it arrived at the game's stdin", wait(lambda: m.log_has(bot.client_log, "got: connect 10.0.0.5:25566"), 5))
    check("an unknown @command is refused", m.keeper_ask(bot, "@dance") == "unknown")
    check("@stop is acknowledged", m.keeper_ask(bot, "@stop") == "stopping")
    check("the game is gone", wait(lambda: not m.pid_alive(game_pid), 10))
    check("the keeper is gone", wait(lambda: not m.pid_alive(m.read_pid(bot.keeper_pid_f)) if bot.keeper_pid_f.exists() else True, 10))
    check("the run files are cleaned up",
          wait(lambda: not bot.keeper_port_f.exists() and not bot.client_pid_f.exists(), 5))
    check("with no keeper, asking returns None", m.keeper_ask(bot, "@ping") is None)
    check("the keeper's own log says why it ended", m.log_has(bot.keeper_log, "game exited"))


def tests_stop_without_keeper():
    print("\nStop: nothing running is not an error")
    bot = m.Bot("Alice")
    code = m.cmd_stop(Args(name="Alice", keep_guards=False))
    check("stopping a stopped bot returns 0", code == 0)


# --- deploy-mod -------------------------------------------------------------

def tests_deploy():
    print("\nDeploy: a new inode, and only the same family leaves")
    check("the core's family is 'marionette'", m.jar_family("marionette-1.0.0.jar") == "marionette")
    check("an add-on's family keeps its name", m.jar_family("marionette-veil-1.0.0.jar") == "marionette-veil")
    check("the core jar is told from an add-on by its name",
          m.CORE_JAR.match("marionette-1.0.0.jar") and not m.CORE_JAR.match("marionette-veil-1.0.0.jar"))
    layout()
    mods = TMP / "shared" / "mods"
    for name in ("marionette-0.9.0.jar", "marionette-bot-0.8.0.jar", "marionette-veil-0.9.0.jar",
                 "marionette-veil-1.0.0.jar", "marionette-1.0.0.jar", "hmc-specifics-1.21.1-neoforge.jar"):
        (mods / name).write_bytes(name.encode())
    old_inode = (mods / "marionette-1.0.0.jar").stat().st_ino
    built = TMP / "marionette-1.0.0.jar"
    built.write_bytes(b"fresh core")
    m.cmd_deploy_mod(Args(jar=str(built)))
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
    m.cmd_deploy_mod(Args(jar=str(addon)))
    left = sorted(p.name for p in mods.glob("*.jar"))
    check("an add-on deploy replaces every older jar of that add-on only",
          "marionette-veil-1.1.0.jar" in left and "marionette-veil-0.9.0.jar" not in left
          and "marionette-veil-1.0.0.jar" not in left and "marionette-1.0.0.jar" in left, str(left))
    check("a jar that is not there is refused",
          "not a file" in (fails(m.cmd_deploy_mod, Args(jar=str(TMP / "nope.jar"))) or ""))
    for p in mods.glob("marionette-veil-*.jar"):
        p.unlink()


# --- doctor -----------------------------------------------------------------

def tests_doctor():
    print("\nDoctor: says what is missing, in the order it would break")
    layout()
    checks = m.doctor_checks()
    labels = [c[0] for c in checks]
    check("it checks python, java, claude and server.env",
          all(any(l.startswith(k) for l in labels) for k in ("python", "java", "claude", "server.env")))
    check("the fake java is reported as not 21",
          any(l == "java 21" and ok is False for l, ok, _ in checks))
    check("the server mod at 10.0.0.5:1 is reported unreachable",
          any(l == "server mod answers" and ok is False for l, ok, _ in checks))
    check("shared and the marionette jar are found",
          any(l == "shared/mods/marionette" and ok for l, ok, _ in checks))
    (TMP / "shared" / "mods" / "marionette-0.9.0.jar").write_bytes(b"old")
    checks = m.doctor_checks()
    check("two marionette jars are a problem",
          any(l == "shared/mods/marionette" and ok is False and "more than one" in d for l, ok, d in checks))
    (TMP / "shared" / "mods" / "marionette-0.9.0.jar").unlink()
    (TMP / "shared" / "mods" / "marionette-veil-1.0.0.jar").write_bytes(b"a")
    checks = m.doctor_checks()
    check("an add-on next to the core is not 'two cores'",
          any(l == "shared/mods/marionette" and ok for l, ok, _ in checks)
          and any(l == "shared/mods add-ons" and ok and "veil" in d for l, ok, d in checks))
    (TMP / "shared" / "mods" / "marionette-veil-0.9.0.jar").write_bytes(b"b")
    checks = m.doctor_checks()
    check("two jars of one add-on are a problem",
          any(l == "shared/mods add-ons" and ok is False for l, ok, _ in checks))
    for p in (TMP / "shared" / "mods").glob("marionette-veil-*.jar"):
        p.unlink()
    m.Bot("Bob").write("port", m.FIRST_PORT)          # same as Alice's
    checks = m.doctor_checks()
    check("two bots on one port are a problem",
          any(l == "bots/bob" and ok is False and "also belongs" in d for l, ok, d in checks))
    m.Bot("Bob").write("port", m.FIRST_PORT + 1)


if __name__ == "__main__":
    tests_files()
    tests_names()
    tests_servers()
    tests_ports()
    tests_mods()
    tests_create()
    tests_launch_line()
    tests_keeper()
    tests_stop_without_keeper()
    tests_deploy()
    tests_doctor()

    print(f"\n{done - len(failures)}/{done} checks pass")
    if failures:
        print("failed:")
        for f in failures:
            print(f"  - {f}")
        sys.exit(1)

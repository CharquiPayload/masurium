"""Microsoft accounts, logged in once and shared by the instances that use them.

An instance plays offline, as the player name it was given (private servers
with online-mode=false, no login at all), or with one of these: a real,
purchased Minecraft Java account.

An instance with an account plays as the account's player. HeadlessMC
keeps the login of an account in its own folder (HeadlessMC/auth/.accounts.json,
a path it does not let be changed), and renews it every time it launches the
game: Microsoft hands back a new key each time and the old one stops working.
So a login cannot be COPIED to each instance, or the copies would go stale one
after another. It lives once, in accounts/<account>/, and each instance's
HeadlessMC auth folder is a LINK to it: one login, renewed in one place.

The other half of that rule is in operations: one Microsoft account plays in
one game at a time, and two instances of it never start at the same moment.
"""
import json
import os
import shutil

from .events import Fail
from .files import link_or_copy, link_target

OFFLINE = "offline"


class Account:
    """accounts/<key>/: account.json (its player name) and hmc/, the
    HeadlessMC folder its login was made and is kept in."""

    def __init__(self, ws, key):
        self.ws = ws
        self.key = key.lower()
        self.dir = ws.accounts_dir / self.key
        self.json = self.dir / "account.json"
        self.hmc = self.dir / "hmc"
        self.auth = self.hmc / "HeadlessMC" / "auth"
        self.logins = self.auth / ".accounts.json"
        self.lock = self.dir / "start.lock"

    def __repr__(self):
        return f"Account({self.key!r})"

    def exists(self):
        return self.json.is_file()

    def require(self):
        if not self.exists():
            raise Fail(f"there is no account {self.key}. These are: "
                       + (", ".join(self.ws.account_keys()) or "(none yet: masurium.py account add)"),
                       code="no_account")
        return self

    @property
    def data(self):
        try:
            data = json.loads(self.json.read_text(encoding="utf-8"))
            return data if isinstance(data, dict) else {}
        except (OSError, ValueError):
            return {}

    @property
    def name(self):
        """The player name it plays as."""
        return self.data.get("name") or self.key

    @property
    def offline(self):
        """An offline account, from before an offline instance simply had a
        name: a player name and nothing to log in (`migrate` turns the
        instances that used one into offline instances with that name)."""
        return bool(self.data.get("offline"))

    def logged_in(self):
        return bool(players_in(self.logins))

    def users(self):
        """The instances that play with it."""
        return [i.key for i in self.ws.instances() if i.data.get("account") == self.key]


def players_in(path):
    """The player names of the logins in a HeadlessMC accounts file:
    {"accounts": [<a MinecraftAuth Java session>, ...]}, each with its
    profile ("mcProfile": {"name": ...}). An empty or unreadable file has
    none: HeadlessMC makes an empty one on its first run."""
    try:
        data = json.loads(open(path, encoding="utf-8").read() or "{}")
    except (OSError, ValueError):
        return []
    names = []

    def walk(node):
        if isinstance(node, dict):
            profile = node.get("mcProfile")
            if isinstance(profile, dict) and isinstance(profile.get("name"), str):
                names.append(profile["name"])
                return
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)

    walk(data.get("accounts", []) if isinstance(data, dict) else data)
    return names


def plays_offline(value):
    """Whether an `account` setting means playing offline."""
    return value == OFFLINE


def prepare_login(ws):
    """A HeadlessMC folder to log a new account in: (argv, cwd, env, folder).
    It is made apart and only becomes an account once a login is in it."""
    launcher_jar = ws.shared_dir / "headlessmc-launcher.jar"
    if not launcher_jar.is_file():
        raise Fail(f"missing {launcher_jar}: HeadlessMC is what logs accounts in.", code="no_shared")
    folder = ws.accounts_dir / f".adding-{os.getpid()}"
    if folder.exists():
        shutil.rmtree(folder)
    hmc = folder / "hmc"
    (hmc / "HeadlessMC").mkdir(parents=True)
    link_or_copy(launcher_jar, hmc / "headlessmc-launcher.jar")
    (hmc / "HeadlessMC" / "config.properties").write_text(
        "hmc.jline.enabled=false\nhmc.offline=false\nhmc.invert.command.modifiers=false\n",
        encoding="utf-8")
    return ws.java_command() + ["-jar", "headlessmc-launcher.jar"], hmc, ws.child_env(), folder


def finish_login(ws, folder, key=None):
    """What `prepare_login` made becomes accounts/<key>/, named after the
    player it logged in as (lowercase), once there is a login in it. Without
    one, the folder goes and it is said."""
    names = players_in(folder / "hmc" / "HeadlessMC" / "auth" / ".accounts.json")
    if not names:
        shutil.rmtree(folder, ignore_errors=True)
        raise Fail("no account was logged in: in HeadlessMC, type `login`, follow its steps "
                   "in a browser, and `quit` once it says the account is saved.", code="no_login")
    if len(names) > 1:
        shutil.rmtree(folder, ignore_errors=True)
        raise Fail(f"more than one account was logged in there ({', '.join(names)}): add them "
                   "one at a time.", code="several_logins")
    name = names[0]
    key = (key or name).lower()
    account = Account(ws, key)
    if account.dir.exists():
        shutil.rmtree(folder, ignore_errors=True)
        raise Fail(f"there is already an account {key}: to log it in again, remove it first "
                   f"(masurium.py account remove {key}).", code="exists")
    folder.rename(account.dir)
    account.json.write_text(json.dumps({"name": name}, indent=2) + "\n", encoding="utf-8")
    return account


def link_login(inst, account):
    """The instance's HeadlessMC auth folder becomes the account's. A login
    of its own that was already there is kept aside, not destroyed."""
    from .files import link_dir
    auth = inst.hmc / "HeadlessMC" / "auth"
    if link_target(auth) == account.auth:
        return
    account.auth.mkdir(parents=True, exist_ok=True)
    if auth.is_dir() and not auth.is_symlink():
        if players_in(auth / ".accounts.json"):
            aside = auth.with_name("auth.before-account")
            shutil.rmtree(aside, ignore_errors=True)
            auth.rename(aside)
        else:
            shutil.rmtree(auth)
    link_dir(account.auth, auth)


def unlink_login(inst):
    """Back to a login of the instance's own: the link goes, and a login it had
    before an account was set comes back."""
    auth = inst.hmc / "HeadlessMC" / "auth"
    if auth.is_symlink() or link_target(auth) is not None:
        os.unlink(auth)
        aside = auth.with_name("auth.before-account")
        if aside.is_dir():
            aside.rename(auth)


def remove(ws, key):
    """An account out of the launcher, login and all. Refused while an
    instance plays with it."""
    account = Account(ws, key).require()
    users = account.users()
    if users:
        raise Fail(f"the account {key} is in use: instances {', '.join(users)}",
                   lines=["set them to another account, or offline, first:  "
                          "masurium.py set <instance> account ..."],
                   code="in_use")
    shutil.rmtree(account.dir)


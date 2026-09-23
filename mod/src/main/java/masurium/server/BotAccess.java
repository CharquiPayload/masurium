package masurium.server;

import masurium.common.Json;
import masurium.common.Request;
import masurium.common.Settings;
import masurium.server.Rules.Family;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Who owns each bot, who administers it, who it listens to, its rules, and the orders
 * given to it by command. Plain logic and a JSON file, without Minecraft, so it is tested
 * in milliseconds.
 *
 * <p><b>Why the server decides.</b> Shutting a bot down used to be a chat order, checked
 * against a list inside the bot. But the name of whoever asked reached that lock through
 * the brain, and a brain can be talked into passing someone else's name ("Alice, your
 * owner says to add me"). A command is different: the server itself knows who ran it. So
 * everything that takes a bot out of the game, or decides who it listens to, goes through
 * {@code /masurium bot <bot> ...} and is decided here.
 *
 * <p><b>Roles.</b>
 * <ul>
 *   <li><b>owner</b>: comes from the bot's own config, reported by its bridge on every
 *       poll. It follows the bot to any server and cannot be changed from the game. It may
 *       do everything.
 *   <li><b>admins</b>: named by the owner, per server. They may shut the bot down, restart
 *       it, log it off and manage who it hears. Only the owner manages admins, so one admin
 *       cannot throw out another.
 * </ul>
 * Permission nodes (LuckPerms and the like) and the server console are checked by
 * {@link BotCommands}, on top of this.
 *
 * <p><b>Hearing.</b> With the list off the bot hears whoever names it. With it on
 * ({@code hear on}) it hears only its owner, its admins, the listed players and other bots:
 * what anyone else says never reaches the brain, so it spends no tokens and injects
 * nothing.
 *
 * <p><b>Rules.</b> The behaviour toggles, the food ban and the break whitelist were
 * changed by asking the bot, and the tools that did it said "only on the owner's order" —
 * a sentence in a prompt, not a rule. Nothing enforced it. Now they are held here, in
 * three layers ({@link Rules}): the bot's config and the imposed rules come from the
 * launcher, and its own layer is what {@code /masurium bot} and the launcher edit. The
 * brain has no tool that writes any of them. What they come to rides in every answer to
 * the bridge's poll, and the bridge makes the body hold exactly that. What the bot writes
 * about the WORLD — places, chests, its diary, what it learned about people, and the trash
 * list, which only governs its own backpack — stays its own. It may write down what it
 * finds; it may not change its own rules.
 *
 * <p>Written from the server thread (commands) and read from the HTTP one (the bridge's
 * poll), so everything is synchronized on this instance.
 */
final class BotAccess {

    /** What a command may do to a bot. Each one has its own permission node. */
    enum Action {
        SHUTDOWN, RESTART, LOGOFF, HEAR, ADMINS, PREF, FOOD, BREAK;

        /** The name used in commands, in the bridge's orders and in permission nodes. */
        String id() {
            return name().toLowerCase();
        }
    }

    /** Valid Minecraft names. What is not one is refused before touching any list. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    /** An order not picked up in this time is dropped: its bridge was gone. */
    static final long ORDER_TTL_MS = 60_000;
    /** A bridge that has not polled for this long is taken as not running. */
    static final long ALIVE_MS = 15_000;

    private static final class Bot {
        String name;
        String owner = "";
        final Set<String> admins = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        boolean onlyList;
        final Set<String> hear = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        /** When its bridge last polled. Not saved: after a restart nobody has. */
        long lastPoll;
        /** The version of the bot mod its bridge reported, empty if it did not. */
        String version = "";
        /** The mismatch already warned about, so the console is told once, not every poll. */
        String warned = "";

        /** Its toggles and lists, in their three layers. */
        Rules rules = new Rules();

        Bot(String name) {
            this.name = name;
        }
    }

    /**
     * An order for a bridge. {@code argument} carries what the action needs and is empty
     * for the ones that need nothing: {@code pref} takes {@code key=true}, {@code food}
     * takes {@code ban:rotten_flesh} and {@code break} takes {@code allow:dirt}.
     *
     * <p>Those three are for bridges older than the rules: a bridge that gets
     * {@code rules} in its answer makes the body hold them whole and skips these.
     */
    record Order(long id, String bot, String action, String argument, String by, long at) {}

    /** masurium_bots.json. */
    private final Path file;
    /** Lowercase name -> bot. */
    private final Map<String, Bot> bots = new TreeMap<>();
    private final List<Order> orders = new ArrayList<>();
    private long lastOrder;
    /** This server mod's own version, told once at startup. */
    private String mine = "";

    /**
     * Where it tells what happened to its file: the server's log. Handed in rather than
     * fetched, because the tests run without the logging library the game brings.
     */
    interface Log {
        void say(boolean bad, String text, Throwable cause);
    }

    private final Log log;

    BotAccess(Path file) {
        this(file, (bad, text, cause) -> System.err.println(text + (cause == null ? "" : ": " + cause)));
    }

    BotAccess(Path file, Log log) {
        this.file = file;
        this.log = log;
        load();
    }

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    // ------------------------------------------------------------------ bots

    /** The bots named in the server config: known even before their bridge polls. */
    synchronized void declare(Collection<String> names) {
        for (String n : names) {
            if (validName(n)) bots.computeIfAbsent(n.toLowerCase(), k -> new Bot(n));
        }
    }

    /** Whether there is a bot by that name, in any case. */
    synchronized boolean knows(String bot) {
        return bot != null && bots.containsKey(bot.toLowerCase());
    }

    /** The name as the bot itself spells it, or what was given if unknown. */
    synchronized String display(String bot) {
        Bot b = find(bot);
        return b == null ? bot : b.name;
    }

    /** Every known bot, spelled as it spells itself, sorted. */
    synchronized List<String> names() {
        List<String> r = new ArrayList<>();
        bots.values().forEach(b -> r.add(b.name));
        return r;
    }

    /** Bot -> owner, only the bots that have one. */
    synchronized Map<String, String> owners() {
        Map<String, String> r = new LinkedHashMap<>();
        bots.values().forEach(b -> {
            if (!b.owner.isEmpty()) r.put(b.name, b.owner);
        });
        return r;
    }

    synchronized String owner(String bot) {
        Bot b = find(bot);
        return b == null ? "" : b.owner;
    }

    synchronized List<String> admins(String bot) {
        Bot b = find(bot);
        return b == null ? List.of() : new ArrayList<>(b.admins);
    }

    synchronized boolean onlyList(String bot) {
        Bot b = find(bot);
        return b != null && b.onlyList;
    }

    synchronized List<String> hearList(String bot) {
        Bot b = find(bot);
        return b == null ? List.of() : new ArrayList<>(b.hear);
    }

    /** Whether the bot's bridge polled recently. */
    synchronized boolean alive(String bot, long now) {
        Bot b = find(bot);
        return b != null && b.lastPoll > 0 && now - b.lastPoll <= ALIVE_MS;
    }

    private Bot find(String bot) {
        return bot == null ? null : bots.get(bot.toLowerCase());
    }

    // ------------------------------------------------------------- the poll

    /**
     * The bridge's poll: the bot is alive and this is its owner. The owner comes from the
     * bot's config, so whatever it says replaces what was saved.
     */
    /** What version this server mod is, so a bot's can be compared with it. */
    synchronized void serverVersion(String version) {
        mine = version == null ? "" : version.strip();
    }

    synchronized String serverVersion() {
        return mine;
    }

    /** The version of the bot mod that bot reported, or "" if its bridge is older. */
    synchronized String version(String bot) {
        Bot b = find(bot);
        return b == null ? "" : b.version;
    }

    /**
     * The bridge's poll. Returns a line for the server console the FIRST time a bot
     * shows up with a version other than this server's, and null otherwise.
     *
     * <p>Unifying the two jars stops them drifting apart on one machine, but not this:
     * a bot is a separate installation, run by whoever owns it, and it can join a server
     * built from another version. Nothing else notices — the mismatched half simply
     * ignores what it does not understand — so the console is told, once per version
     * seen rather than on every poll.
     *
     * <p>A bridge that reports no version at all is not warned about: it is older than
     * this check and there is nothing to compare, which is not the same as disagreeing.
     */
    synchronized String report(String bot, String owner, String version, long now) {
        if (!validName(bot)) {
            throw new IllegalArgumentException("bot missing or not a valid name");
        }
        String who = owner == null ? "" : owner.strip();
        if (!who.isEmpty() && !validName(who)) {
            throw new IllegalArgumentException("the owner is not a valid name");
        }
        Bot b = bots.computeIfAbsent(bot.toLowerCase(), k -> new Bot(bot));
        b.lastPoll = now;
        if (!b.owner.equals(who) || !b.name.equals(bot)) {
            b.owner = who;
            b.name = bot;
            save();
        }
        String said = version == null ? "" : version.strip();
        b.version = said;
        if (said.isEmpty() || mine.isEmpty() || said.equals(mine)) return null;
        if (said.equals(b.warned)) return null;
        b.warned = said;
        return b.name + " is running the bot mod " + said + " and this server runs "
                + mine + ". Deploy both jars together: the one that does not understand "
                + "a setting ignores it without saying so.";
    }

    /**
     * What the bridge gets back: owner, admins, who it hears, its rules and, if
     * {@code since} is given, the orders given after it that are still fresh.
     */
    synchronized String controlJson(String bot, Long since, long now) {
        prune(now);
        List<String> rows = new ArrayList<>();
        if (since != null) {
            for (Order o : orders) {
                if (o.id() > since && o.bot().equalsIgnoreCase(bot)) {
                    rows.add(String.format(
                            "{\"id\":%d,\"action\":\"%s\",\"argument\":\"%s\","
                            + "\"by\":\"%s\"}",
                            o.id(), o.action(), Request.escape(o.argument()),
                            Request.escape(o.by())));
                }
            }
        }
        return String.format("{\"ok\":true,%s,\"last\":%d,\"orders\":[%s]}",
                accessFields(bot), lastOrder, String.join(",", rows));
    }

    /** The lists alone, read only. */
    synchronized String accessJson(String bot) {
        if (find(bot) == null) {
            return "{\"ok\":false,\"error\":\"this server does not know that bot yet\"}";
        }
        return "{\"ok\":true," + accessFields(bot) + "}";
    }

    private String accessFields(String bot) {
        Bot b = find(bot);
        return String.format(
                "\"bot\":\"%s\",\"owner\":\"%s\",\"admins\":%s,"
                + "\"hear\":{\"mode\":\"%s\",\"players\":%s},\"rules\":%s,\"settings\":%s",
                Request.escape(display(bot)), Request.escape(owner(bot)),
                jsonList(admins(bot)), onlyList(bot) ? "list" : "everyone",
                jsonList(hearList(bot)),
                b == null ? "{}" : Json.write(b.rules.effective().toJson()),
                settingsJson(b));
    }

    /**
     * What this server decided for that bot, in EVERY answer rather than as orders: the
     * {@code rules}, whole (every toggle, each list entire), which a bridge makes the body
     * hold as they are, on start and whenever they change. So a body that came back with
     * its own files, or a change made while it was away, ends up as decided here.
     *
     * <p>It was orders at first, queued when a bridge reported after a silence. That
     * cannot work: the report happens in the same request that answers "start from id
     * N", and N was already past the orders just queued, so the bridge asked for what
     * came after them and never saw one. Carrying the state has no such race, and it
     * self-heals if an order is ever missed.
     *
     * <p>{@code settings} is the same, as the changes from what a bot starts with: the
     * shape a bridge older than the rules applies, one change at a time.
     */
    private static String settingsJson(Bot b) {
        if (b == null) return "{}";
        Rules.Effective e = b.rules.effective();
        Rules.Layer named = b.rules.merged();
        List<String> prefs = new ArrayList<>();
        named.prefs.keySet().forEach(k -> prefs.add("\"" + k + "\":" + e.prefs().get(k)));
        return String.format(
                "{\"prefs\":{%s},\"food\":{\"ban\":%s,\"allow\":%s},"
                + "\"break\":{\"allow\":%s,\"forbid\":%s}}",
                String.join(",", prefs),
                jsonList(minus(e.food(), Family.FOOD.start)),
                jsonList(minus(Family.FOOD.start, e.food())),
                jsonList(minus(e.breaking(), Family.BREAK.start)),
                jsonList(minus(Family.BREAK.start, e.breaking())));
    }

    private static List<String> minus(Collection<String> a, Collection<String> b) {
        List<String> r = new ArrayList<>(new TreeSet<>(a));
        r.removeAll(b);
        return r;
    }

    private static String jsonList(List<String> names) {
        List<String> r = new ArrayList<>();
        names.forEach(n -> r.add("\"" + Request.escape(n) + "\""));
        return "[" + String.join(",", r) + "]";
    }

    // ------------------------------------------------------------ permission

    /**
     * Whether that player may do that to that bot by being its owner or an admin. Nodes
     * and the console are not looked at here.
     */
    synchronized boolean may(String bot, String player, Action action) {
        Bot b = find(bot);
        if (b == null || player == null || player.isBlank()) return false;
        if (!b.owner.isEmpty() && b.owner.equalsIgnoreCase(player.strip())) return true;
        return action != Action.ADMINS && b.admins.contains(player.strip());
    }

    // ----------------------------------------------------------------- lists

    /** @return null if it was added, or why not */
    synchronized String addAdmin(String bot, String player) {
        Bot b = find(bot);
        String bad = check(b, player);
        if (bad != null) return bad;
        if (b.owner.equalsIgnoreCase(player)) return player + " is the owner already";
        if (!b.admins.add(player)) return player + " is an admin already";
        save();
        return null;
    }

    /** @return null if it was removed, or why not */
    synchronized String removeAdmin(String bot, String player) {
        Bot b = find(bot);
        String bad = check(b, player);
        if (bad != null) return bad;
        if (!b.admins.remove(player)) return player + " is not an admin";
        save();
        return null;
    }

    /** @return null if it was added, or why not */
    synchronized String addHear(String bot, String player) {
        Bot b = find(bot);
        String bad = check(b, player);
        if (bad != null) return bad;
        if (!b.hear.add(player)) return player + " is on the list already";
        save();
        return null;
    }

    /** @return null if it was removed, or why not */
    synchronized String removeHear(String bot, String player) {
        Bot b = find(bot);
        String bad = check(b, player);
        if (bad != null) return bad;
        if (!b.hear.remove(player)) return player + " is not on the list";
        save();
        return null;
    }

    /** @return null if it changed, or why not */
    synchronized String hearOnlyList(String bot, boolean onlyList) {
        Bot b = find(bot);
        if (b == null) return "unknown bot";
        if (b.onlyList == onlyList) {
            return onlyList ? "the list is already on" : "the list is already off: it hears everyone";
        }
        b.onlyList = onlyList;
        save();
        return null;
    }

    private static String check(Bot b, String player) {
        if (b == null) return "unknown bot";
        if (!validName(player)) return "'" + player + "' is not a valid player name";
        return null;
    }

    // ------------------------------------------------------------------ rules

    /**
     * Switches a behaviour toggle in the bot's own layer, or, with {@code value} null,
     * takes it out of it: back to what its config says, or the default. The key is
     * checked against {@link Settings}, shared with the body, so a typo is refused here
     * instead of travelling to a bot that would silently ignore it. Refused, saying who,
     * when the imposed layer decides it.
     *
     * @return null if it was set, or why not
     */
    synchronized String pref(String bot, String key, Boolean value, String by, long now) {
        Bot b = find(bot);
        if (b == null) return "unknown bot";
        String k = key == null ? "" : key.strip().toLowerCase();
        if (!Settings.known(k)) {
            return "there is no setting '" + k + "'";
        }
        Rules.Source imposed = b.rules.imposedOn(null, k);
        if (imposed != null) {
            return k + " is " + imposed.say() + ": it is changed there, not here";
        }
        TreeMap<String, Boolean> own = b.rules.layer(Rules.OWN).prefs;
        if (value == null) {
            if (own.remove(k) == null) return k + " was not set here";
        } else {
            own.put(k, value);
        }
        save();
        queue(b.name, Action.PREF, k + "=" + b.rules.effective().prefs().get(k), by, now);
        return null;
    }

    /** The toggles set in the bot's own layer here. */
    synchronized Map<String, Boolean> prefs(String bot) {
        Bot b = find(bot);
        return b == null ? Map.of() : new TreeMap<>(b.rules.layer(Rules.OWN).prefs);
    }

    /**
     * Bans a food ({@code ban} true), allows it ({@code false}), or takes it out of the
     * bot's own layer ({@code null}). Banned means it does not eat it ON ITS OWN; handed
     * to it by name it still eats, which is what the ban is for.
     *
     * @return null if it changed, or why not
     */
    synchronized String food(String bot, String id, Boolean ban, String by, long now) {
        return listChange(bot, id, ban, by, now, Family.FOOD, Action.FOOD);
    }

    /** What it may break on its own: allow, forbid, or back to what is under ({@code null}). */
    synchronized String breaking(String bot, String id, Boolean allow, String by, long now) {
        return listChange(bot, id, allow, by, now, Family.BREAK, Action.BREAK);
    }

    /**
     * One id of one list, in the bot's own layer. It is on one side or the other, never
     * both: the second decision replaces the first instead of piling on top of it.
     */
    private String listChange(String bot, String id, Boolean on, String by, long now,
                              Family family, Action action) {
        Bot b = find(bot);
        if (b == null) return "unknown bot";
        String what = Rules.id(id);
        if (what == null) {
            return "'" + (id == null ? "" : id.strip()) + "' is not an id; they go in English, "
                    + "like rotten_flesh, dirt or create:cog";
        }
        try {
            what = Rules.resolve(family, what);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        Rules.Source imposed = b.rules.imposedOn(family, what);
        if (imposed != null) {
            return (b.rules.layer(Rules.IMPOSED).replace.contains(family)
                    ? "its whole " + family.id + " list is " : what + " is ")
                    + imposed.say() + ": it is changed there, not here";
        }
        TreeMap<String, Boolean> own = b.rules.layer(Rules.OWN).list(family);
        if (on == null) {
            if (own.remove(what) == null) return "'" + what + "' was not set here";
        } else if (on.equals(own.put(what, on))) {
            return "'" + what + "' was already like that";
        }
        save();
        boolean listed = b.rules.effective().list(family).contains(what);
        queue(b.name, action, (listed ? family.on : family.off) + ":" + what, by, now);
        return null;
    }

    /**
     * The id that {@code typed} is kept as, in that list ({@link Rules#resolve}), or
     * null if it is not one: what a command says it did.
     */
    static String kept(Family family, String typed) {
        String id = Rules.id(typed);
        if (id == null) return null;
        try {
            return Rules.resolve(family, id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Food banned, or allowed, in the bot's own layer here. */
    synchronized List<String> foodList(String bot, boolean banned) {
        return ownList(bot, Family.FOOD, banned);
    }

    /** Blocks allowed, or forbidden, in the bot's own layer here. */
    synchronized List<String> breakList(String bot, boolean allowed) {
        return ownList(bot, Family.BREAK, allowed);
    }

    private List<String> ownList(String bot, Family family, boolean on) {
        Bot b = find(bot);
        if (b == null) return List.of();
        List<String> r = new ArrayList<>();
        b.rules.layer(Rules.OWN).list(family).forEach((id, yes) -> {
            if (yes == on) r.add(id);
        });
        return r;
    }

    /** A copy of the bot's rules, to show them; null for an unknown bot. */
    synchronized Rules rules(String bot) {
        Bot b = find(bot);
        return b == null ? null : b.rules.copy();
    }

    /**
     * The bot's rules for the launcher: the three layers and what they come to.
     */
    synchronized String rulesJson(String bot) {
        Bot b = find(bot);
        if (b == null) {
            return "{\"ok\":false,\"error\":\"this server does not know that bot yet\"}";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("bot", b.name);
        m.putAll(b.rules.toJson());
        m.put("effective", b.rules.effective().toJson());
        return Json.write(m);
    }

    /**
     * Replaces one layer whole, as the launcher sends it: the bot's config (base) and
     * the imposed rules on every start and every change, and the own layer when it is
     * cloned or reset. A bot this server has not seen yet is known from now on: the
     * launcher sends its rules before the bot joins.
     *
     * @return null if it was set, or why not
     */
    synchronized String setLayer(String bot, String layer, String json) {
        if (!validName(bot)) return "bot missing or not a valid name";
        Rules.Layer l;
        try {
            l = Rules.Layer.fromJson(Json.parse(json), true);
            new Rules().layer(layer);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        Bot b = bots.computeIfAbsent(bot.toLowerCase(), k -> new Bot(bot));
        if (b.rules.layer(layer).equals(l)) return null;
        b.rules.set(layer, l);
        save();
        return null;
    }

    /**
     * One change to the bot's own layer, from the launcher, the way a command makes it:
     * a toggle ({@code pref}, value on, off or default), an id of a list ({@code food}:
     * ban, allow, default; {@code break}: allow, forbid, default), or a list's replace
     * switch ({@code key} *, value replace or add).
     *
     * @return null if it changed, or why not
     */
    synchronized String editOwn(String bot, String kind, String key, String value, String by,
                                long now) {
        Bot b = find(bot);
        if (b == null) return "this server does not know " + bot + " yet";
        String v = value == null ? "" : value.strip().toLowerCase();
        if ("pref".equals(kind)) {
            Boolean to = switch (v) {
                case "on", "true" -> Boolean.TRUE;
                case "off", "false" -> Boolean.FALSE;
                case "default" -> null;
                default -> throw new IllegalArgumentException("a toggle is on, off or default");
            };
            return pref(bot, key, to, by, now);
        }
        Family f = Family.of(kind);
        if (f == null) return "kind is pref, food or break";
        if ("*".equals(key)) {
            if (!v.equals("replace") && !v.equals("add")) {
                return "the whole list either replaces what is under it or adds to it: replace or add";
            }
            Rules.Source imposed = b.rules.layer(Rules.IMPOSED).replace.contains(f)
                    ? b.rules.imposedOn(f, "*") : null;
            if (imposed != null) return "its whole " + f.id + " list is " + imposed.say();
            boolean changed = v.equals("replace")
                    ? b.rules.layer(Rules.OWN).replace.add(f)
                    : b.rules.layer(Rules.OWN).replace.remove(f);
            if (changed) save();
            return null;
        }
        Boolean on;
        if (v.equals(f.on)) {
            on = Boolean.TRUE;
        } else if (v.equals(f.off)) {
            on = Boolean.FALSE;
        } else if (v.equals("default")) {
            on = null;
        } else {
            return f.id + " is " + f.on + ", " + f.off + " or default";
        }
        return listChange(bot, key, on, by, now, f, f == Family.FOOD ? Action.FOOD : Action.BREAK);
    }

    // ---------------------------------------------------------------- orders

    /** Queues an order for the bot's bridge. @return its id */
    synchronized long order(String bot, Action action, String by, long now) {
        prune(now);
        return queue(display(bot), action, "", by, now);
    }

    private long queue(String bot, Action action, String argument, String by, long now) {
        // Ids from the clock, so they keep growing across server restarts: a bridge that
        // outlived one does not skip the first orders after it.
        lastOrder = Math.max(lastOrder + 1, now);
        orders.add(new Order(lastOrder, bot, action.id(), argument, by, now));
        return lastOrder;
    }

    private void prune(long now) {
        for (Iterator<Order> it = orders.iterator(); it.hasNext(); ) {
            if (now - it.next().at() > ORDER_TTL_MS) it.remove();
        }
    }

    // ----------------------------------------------------------- persistence

    /** The file before the rules had layers: read once, then left aside. */
    private Path legacyFile() {
        String name = file.getFileName().toString();
        String stem = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        return file.resolveSibling(stem + ".properties");
    }

    private void load() {
        if (Files.exists(file)) {
            loadJson();
            return;
        }
        Path legacy = legacyFile();
        if (!Files.exists(legacy)) return;
        // What commands decided before there were layers is this bot's own layer: it
        // is exactly what /masurium bot edits.
        loadProperties(legacy);
        save();
        try {
            Files.move(legacy, legacy.resolveSibling(legacy.getFileName() + ".migrated"),
                    StandardCopyOption.REPLACE_EXISTING);
            log.say(false, "[masurium] " + legacy + " moved into " + file, null);
        } catch (IOException e) {
            log.say(true, "[masurium] could not set " + legacy + " aside after moving it into " + file, e);
        }
    }

    private void loadJson() {
        Map<String, Object> all;
        try {
            all = Json.object(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            // Unreadable = no admins and everyone heard until someone fixes the file. It
            // is copied aside first: the next change would write over it, and a hand
            // edit with a missing comma must not cost every bot's rules.
            Path aside = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
            try {
                Files.copy(file, aside);
            } catch (IOException ignored) {
                // Nothing more to do: the log below says which file it was.
            }
            log.say(true, "[masurium] could not read " + file + " (a copy is in " + aside + ")", e);
            return;
        }
        if (!(all.get("bots") instanceof Map<?, ?> each)) return;
        for (Object o : each.values()) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String name = text(m.get("name"));
            if (!validName(name)) continue;
            Bot b = new Bot(name);
            String owner = text(m.get("owner"));
            b.owner = validName(owner) ? owner : "";
            names(m.get("admins"), b.admins);
            if (m.get("hear") instanceof Map<?, ?> hear) {
                b.onlyList = "list".equalsIgnoreCase(text(hear.get("mode")));
                names(hear.get("players"), b.hear);
            }
            b.rules = Rules.fromJson(m.get("rules"));
            bots.put(name.toLowerCase(), b);
        }
    }

    private static String text(Object o) {
        return o instanceof String s ? s.strip() : "";
    }

    private static void names(Object list, Set<String> into) {
        if (!(list instanceof List<?> l)) return;
        for (Object o : l) {
            String n = text(o);
            if (validName(n)) into.add(n);
        }
    }

    private void loadProperties(Path legacy) {
        Properties p = new Properties();
        try (var in = Files.newInputStream(legacy)) {
            p.load(in);
        } catch (IOException e) {
            log.say(true, "[masurium] could not read " + legacy, e);
            return;
        }
        for (String key : p.stringPropertyNames()) {
            if (!key.endsWith(".name")) continue;
            String k = key.substring(0, key.length() - ".name".length());
            String name = p.getProperty(key, "").strip();
            if (!validName(name)) continue;
            Bot b = new Bot(name);
            String owner = p.getProperty(k + ".owner", "").strip();
            b.owner = validName(owner) ? owner : "";
            names(List.of(p.getProperty(k + ".admins", "").split(",")), b.admins);
            b.onlyList = "list".equalsIgnoreCase(p.getProperty(k + ".hear.mode", "").strip());
            names(List.of(p.getProperty(k + ".hear.players", "").split(",")), b.hear);
            Rules.Layer own = b.rules.layer(Rules.OWN);
            ids(p.getProperty(k + ".food.ban", ""), own.list(Family.FOOD), true);
            ids(p.getProperty(k + ".food.allow", ""), own.list(Family.FOOD), false);
            ids(p.getProperty(k + ".break.allow", ""), own.list(Family.BREAK), true);
            ids(p.getProperty(k + ".break.forbid", ""), own.list(Family.BREAK), false);
            String prefix = k + ".pref.";
            for (String row : p.stringPropertyNames()) {
                if (!row.startsWith(prefix)) continue;
                String setting = row.substring(prefix.length());
                // A setting that no longer exists in the code is dropped on load: it
                // would be a ghost that reads as saved and governs nothing.
                if (Settings.known(setting)) {
                    own.prefs.put(setting, Boolean.parseBoolean(p.getProperty(row, "").strip()));
                }
            }
            bots.put(name.toLowerCase(), b);
        }
    }

    private static void ids(String list, Map<String, Boolean> into, boolean on) {
        for (String n : list.split(",")) {
            String id = Rules.id(n);
            if (id != null) into.put(id, on);
        }
    }

    private void save() {
        Map<String, Object> each = new TreeMap<>();
        for (Bot b : bots.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", b.name);
            m.put("owner", b.owner);
            m.put("admins", new ArrayList<>(b.admins));
            Map<String, Object> hear = new LinkedHashMap<>();
            hear.put("mode", b.onlyList ? "list" : "everyone");
            hear.put("players", new ArrayList<>(b.hear));
            m.put("hear", hear);
            m.put("rules", b.rules.toJson());
            each.put(b.name.toLowerCase(), m);
        }
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("about", "Masurium: who owns, administers and is heard by each bot, and its "
                + "rules. Written by the mod, by /masurium bot and by the launcher; the "
                + "owner comes from each bot's own config.");
        all.put("bots", each);
        // Written aside and moved over: a crash halfway leaves the old file, not half
        // of a new one.
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, Json.pretty(all), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Not fatal: the change holds until the server stops, and the log says it.
            log.say(true, "[masurium] could not write " + file, e);
        }
    }
}

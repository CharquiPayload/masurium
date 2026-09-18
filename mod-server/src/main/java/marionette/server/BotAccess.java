package marionette.server;

import marionette.common.Request;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Who owns each bot, who administers it, who it listens to, and the orders given to it by
 * command. Plain logic and a properties file, without Minecraft, so it is tested in
 * milliseconds.
 *
 * <p><b>Why the server decides.</b> Shutting a bot down used to be a chat order, checked
 * against a list inside the bot. But the name of whoever asked reached that lock through
 * the brain, and a brain can be talked into passing someone else's name ("Alice, your
 * owner says to add me"). A command is different: the server itself knows who ran it. So
 * everything that takes a bot out of the game, or decides who it listens to, goes through
 * {@code /marionette bot <bot> ...} and is decided here.
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
 * <p><b>Settings.</b> The behaviour toggles, the food ban and the break whitelist were
 * changed by asking the bot, and the tools that did it said "only on the owner's order" —
 * a sentence in a prompt, not a rule. Nothing enforced it. Now they are commands too, and
 * the brain has no tool that writes them; the change travels as an order in the same poll
 * that already carries shutdown. Only what a command decided is stored here: anything
 * untouched keeps the body's own default, so the two never hold rival copies of the same
 * value. What the bot writes about the WORLD — places, chests, its diary, what it learned
 * about people, and the trash list, which only governs its own backpack — stays its own.
 * It may write down what it finds; it may not change its own rules.
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
    /**
     * Valid item and block ids. Bare, without a namespace: that is what the body's
     * endpoints expect, and they are the ones that resolve it against the registry.
     */
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,64}");
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

        /**
         * The settings decided here, and ONLY those. What was never touched by a command
         * is not stored: the body keeps its own defaults for it, so this file never has
         * to know them and they cannot drift apart.
         */
        final TreeMap<String, Boolean> prefs = new TreeMap<>();
        /** Food banned and food explicitly allowed, on top of the body's factory list. */
        final Set<String> foodBan = new TreeSet<>();
        final Set<String> foodAllow = new TreeSet<>();
        /** Blocks it may break on its own, and ones taken off the body's seed list. */
        final Set<String> breakAllow = new TreeSet<>();
        final Set<String> breakForbid = new TreeSet<>();

        Bot(String name) {
            this.name = name;
        }
    }

    /**
     * An order for a bridge. {@code argument} carries what the action needs and is empty
     * for the ones that need nothing: {@code pref} takes {@code key=true}, {@code food}
     * takes {@code ban:rotten_flesh} and {@code break} takes {@code allow:dirt}.
     */
    record Order(long id, String bot, String action, String argument, String by, long at) {}

    private final Path file;
    /** Lowercase name -> bot. */
    private final Map<String, Bot> bots = new TreeMap<>();
    private final List<Order> orders = new ArrayList<>();
    private long lastOrder;

    BotAccess(Path file) {
        this.file = file;
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
    synchronized void report(String bot, String owner, long now) {
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
    }

    /**
     * What the bridge gets back: owner, admins, who it hears and, if {@code since} is
     * given, the orders given after it that are still fresh.
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
        return String.format(
                "\"bot\":\"%s\",\"owner\":\"%s\",\"admins\":%s,"
                + "\"hear\":{\"mode\":\"%s\",\"players\":%s},\"settings\":%s",
                Request.escape(display(bot)), Request.escape(owner(bot)),
                jsonList(admins(bot)), onlyList(bot) ? "list" : "everyone",
                jsonList(hearList(bot)), settingsJson(bot));
    }

    /**
     * Everything this server decided for that bot, in EVERY answer rather than as
     * orders. A bridge applies it when it starts, so a body that came back with its own
     * files ends up as the commands left it.
     *
     * <p>It was orders at first, queued when a bridge reported after a silence. That
     * cannot work: the report happens in the same request that answers "start from id
     * N", and N was already past the orders just queued, so the bridge asked for what
     * came after them and never saw one. Carrying the state has no such race, and it
     * self-heals if an order is ever missed.
     */
    private String settingsJson(String bot) {
        Bot b = find(bot);
        if (b == null) return "{}";
        List<String> prefs = new ArrayList<>();
        b.prefs.forEach((k, v) -> prefs.add("\"" + k + "\":" + v));
        return String.format(
                "{\"prefs\":{%s},\"food\":{\"ban\":%s,\"allow\":%s},"
                + "\"break\":{\"allow\":%s,\"forbid\":%s}}",
                String.join(",", prefs),
                jsonList(new ArrayList<>(b.foodBan)),
                jsonList(new ArrayList<>(b.foodAllow)),
                jsonList(new ArrayList<>(b.breakAllow)),
                jsonList(new ArrayList<>(b.breakForbid)));
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

    // --------------------------------------------------------------- settings

    /**
     * Switches a behaviour toggle. The key is checked against {@link
     * marionette.common.Settings}, shared with the body, so a typo is refused here
     * instead of travelling to a bot that will silently ignore it.
     *
     * @return null if it was set, or why not
     */
    synchronized String pref(String bot, String key, boolean value, String by, long now) {
        Bot b = find(bot);
        if (b == null) return "unknown bot";
        String k = key == null ? "" : key.strip().toLowerCase();
        if (!marionette.common.Settings.known(k)) {
            return "there is no setting '" + k + "'";
        }
        b.prefs.put(k, value);
        save();
        queue(b.name, Action.PREF, k + "=" + value, by, now);
        return null;
    }

    /** What this server has set for that bot, without the untouched ones. */
    synchronized Map<String, Boolean> prefs(String bot) {
        Bot b = find(bot);
        return b == null ? Map.of() : new TreeMap<>(b.prefs);
    }

    /**
     * Bans a food, or allows it again. Banned means it does not eat it ON ITS OWN;
     * handed to it by name it still eats, which is what the ban is for.
     *
     * @return null if it changed, or why not
     */
    synchronized String food(String bot, String id, boolean ban, String by, long now) {
        return listChange(bot, id, ban, by, now, Action.FOOD,
                b -> b.foodBan, b -> b.foodAllow);
    }

    /** What it may break on its own, and what was taken off its seed list. */
    synchronized String breaking(String bot, String id, boolean allow, String by,
                                 long now) {
        return listChange(bot, id, allow, by, now, Action.BREAK,
                b -> b.breakAllow, b -> b.breakForbid);
    }

    /**
     * The two sides of one list. An id lives in one set or the other, never in both: the
     * second decision replaces the first instead of piling on top of it.
     */
    private String listChange(String bot, String id, boolean first, String by, long now,
                              Action action,
                              java.util.function.Function<Bot, Set<String>> yes,
                              java.util.function.Function<Bot, Set<String>> no) {
        Bot b = find(bot);
        if (b == null) return "unknown bot";
        String what = id == null ? "" : id.strip().toLowerCase();
        if (!ID.matcher(what).matches()) {
            return "'" + what + "' is not an id; they go in English and without a "
                    + "namespace, like rotten_flesh or dirt";
        }
        Set<String> into = first ? yes.apply(b) : no.apply(b);
        Set<String> outOf = first ? no.apply(b) : yes.apply(b);
        outOf.remove(what);
        if (!into.add(what)) {
            return "'" + what + "' was already like that";
        }
        save();
        String verb = action == Action.FOOD ? (first ? "ban" : "allow")
                                            : (first ? "allow" : "forbid");
        queue(b.name, action, verb + ":" + what, by, now);
        return null;
    }

    /** Food this server banned, and food it allowed back. */
    synchronized List<String> foodList(String bot, boolean banned) {
        Bot b = find(bot);
        if (b == null) return List.of();
        return new ArrayList<>(banned ? b.foodBan : b.foodAllow);
    }

    /** Blocks this server allowed breaking, and ones it forbade. */
    synchronized List<String> breakList(String bot, boolean allowed) {
        Bot b = find(bot);
        if (b == null) return List.of();
        return new ArrayList<>(allowed ? b.breakAllow : b.breakForbid);
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

    private void load() {
        if (!Files.exists(file)) return;
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            // Unreadable = no admins and everyone heard until someone fixes the file. The
            // owners come back on their own with the next poll of each bridge.
            org.slf4j.LoggerFactory.getLogger(BotAccess.class)
                    .error("[marionette] could not read {}", file, e);
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
            names(p.getProperty(k + ".admins", ""), b.admins);
            b.onlyList = "list".equalsIgnoreCase(p.getProperty(k + ".hear.mode", "").strip());
            names(p.getProperty(k + ".hear.players", ""), b.hear);
            ids(p.getProperty(k + ".food.ban", ""), b.foodBan);
            ids(p.getProperty(k + ".food.allow", ""), b.foodAllow);
            ids(p.getProperty(k + ".break.allow", ""), b.breakAllow);
            ids(p.getProperty(k + ".break.forbid", ""), b.breakForbid);
            // Settings are one property each, so the file stays readable and a hand
            // edit of one cannot take the others with it.
            String prefix = k + ".pref.";
            for (String row : p.stringPropertyNames()) {
                if (!row.startsWith(prefix)) continue;
                String setting = row.substring(prefix.length());
                // A setting that no longer exists in the code is dropped on load: it
                // would be a ghost that reads as saved and governs nothing.
                if (marionette.common.Settings.known(setting)) {
                    b.prefs.put(setting,
                            Boolean.parseBoolean(p.getProperty(row, "").strip()));
                }
            }
            bots.put(name.toLowerCase(), b);
        }
    }

    private static void names(String list, Set<String> into) {
        for (String n : list.split(",")) {
            n = n.strip();
            if (validName(n)) into.add(n);
        }
    }

    private static void ids(String list, Set<String> into) {
        for (String n : list.split(",")) {
            n = n.strip().toLowerCase();
            if (ID.matcher(n).matches()) into.add(n);
        }
    }

    private void save() {
        Properties p = new Properties();
        for (Bot b : bots.values()) {
            String k = b.name.toLowerCase();
            p.setProperty(k + ".name", b.name);
            p.setProperty(k + ".owner", b.owner);
            p.setProperty(k + ".admins", String.join(",", b.admins));
            p.setProperty(k + ".hear.mode", b.onlyList ? "list" : "everyone");
            p.setProperty(k + ".hear.players", String.join(",", b.hear));
            p.setProperty(k + ".food.ban", String.join(",", b.foodBan));
            p.setProperty(k + ".food.allow", String.join(",", b.foodAllow));
            p.setProperty(k + ".break.allow", String.join(",", b.breakAllow));
            p.setProperty(k + ".break.forbid", String.join(",", b.breakForbid));
            b.prefs.forEach((key, v) -> p.setProperty(k + ".pref." + key, String.valueOf(v)));
        }
        try (var out = Files.newOutputStream(file)) {
            p.store(out, "Marionette: who owns, administers and is heard by each bot. "
                    + "Written by the mod and by /marionette bot; the owner comes from "
                    + "each bot's own config.");
        } catch (IOException e) {
            // Not fatal: the change holds until the server stops, and the log says it.
            org.slf4j.LoggerFactory.getLogger(BotAccess.class)
                    .error("[marionette] could not write {}", file, e);
        }
    }
}

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
 * <p>Written from the server thread (commands) and read from the HTTP one (the bridge's
 * poll), so everything is synchronized on this instance.
 */
final class BotAccess {

    /** What a command may do to a bot. Each one has its own permission node. */
    enum Action {
        SHUTDOWN, RESTART, LOGOFF, HEAR, ADMINS;

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

        Bot(String name) {
            this.name = name;
        }
    }

    record Order(long id, String bot, String action, String by, long at) {}

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
                    rows.add(String.format("{\"id\":%d,\"action\":\"%s\",\"by\":\"%s\"}",
                            o.id(), o.action(), Request.escape(o.by())));
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
                + "\"hear\":{\"mode\":\"%s\",\"players\":%s}",
                Request.escape(display(bot)), Request.escape(owner(bot)),
                jsonList(admins(bot)), onlyList(bot) ? "list" : "everyone",
                jsonList(hearList(bot)));
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

    // ---------------------------------------------------------------- orders

    /** Queues an order for the bot's bridge. @return its id */
    synchronized long order(String bot, Action action, String by, long now) {
        prune(now);
        // Ids from the clock, so they keep growing across server restarts: a bridge that
        // outlived one does not skip the first orders after it.
        lastOrder = Math.max(lastOrder + 1, now);
        orders.add(new Order(lastOrder, display(bot), action.id(), by, now));
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
            bots.put(name.toLowerCase(), b);
        }
    }

    private static void names(String list, Set<String> into) {
        for (String n : list.split(",")) {
            n = n.strip();
            if (validName(n)) into.add(n);
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

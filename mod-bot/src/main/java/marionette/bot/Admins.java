package marionette.bot;

import marionette.common.Request;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Who may shut the bot down, restart it or take it off the server.
 *
 * <p>A rule in the prompt ("log off ONLY if the owner asks") is a warning, not a lock: a
 * prompt can be talked around with the right sentence, and whoever wants to annoy will
 * try exactly with the orders that leave the bot out. This is the lock, and it lives
 * where locks live in this project: in the code, next to {@link BreakPermissions}.
 *
 * <p>The list is per BOT, not per server, unlike places or orders: whoever commands it
 * commands it in every world. Being able to shut it down in one world and not in another
 * means nothing.
 *
 * <p>And it is managed from inside the list: only someone already on it can add another.
 * If the list became empty nobody could fix it, so the last name cannot be removed.
 */
final class Admins {

    private Admins() {}

    /**
     * Who the list is born with when the file does not exist: the owner handed to the
     * launcher (-Dmarionette.owner or MARIONETTE_OWNER), if any.
     */
    private static final String OWNER = System.getProperty("marionette.owner",
            System.getenv().getOrDefault("MARIONETTE_OWNER", "")).strip();

    private static Path file;
    private static Set<String> whoAll;

    private static synchronized Set<String> load() {
        if (whoAll != null) return whoAll;
        whoAll = new LinkedHashSet<>();
        file = Path.of("config", "marionette-admins.txt");
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    line = line.strip();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        whoAll.add(line);
                    }
                }
            }
        } catch (IOException ignored) {
            // Unreadable list = factory list. It is never emptied on purpose: a bot that
            // obeys nobody cannot even be shut down from the chat.
        }
        if (whoAll.isEmpty() && !OWNER.isEmpty()) {
            whoAll.add(OWNER);
            save();
        }
        return whoAll;
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Who may shut me down, restart me or take me out of the "
                    + "server. One name per line.");
            lines.addAll(whoAll);
            Files.write(file, lines);
        } catch (IOException e) {
            marionette.common.Logbook.note("admins",
                    "could not save the admin list: " + e.getMessage());
        }
    }

    /**
     * Whether that name is in command. Without a name nobody is: an anonymous order to
     * shut the bot down is exactly the one not to obey.
     */
    static synchronized boolean can(String name) {
        if (name == null || name.isBlank()) return false;
        for (String q : load()) {
            if (q.equalsIgnoreCase(name.strip())) return true;
        }
        return false;
    }

    /** @return null if it was added, or the reason */
    static synchronized String add(String whoAsks, String name) {
        if (!can(whoAsks)) {
            return "that list is only changed by someone already on it";
        }
        if (name == null || name.isBlank()) return "tell me who";
        load().add(name.strip());
        save();
        return null;
    }

    /** @return null if it was removed, or the reason */
    static synchronized String remove(String whoAsks, String name) {
        if (!can(whoAsks)) {
            return "that list is only changed by someone already on it";
        }
        if (load().size() <= 1) {
            return "it is the last name on the list; if I remove it nobody is left "
                   + "who can shut me down or fix it";
        }
        boolean was = load().removeIf(q -> q.equalsIgnoreCase(
                name == null ? "" : name.strip()));
        if (!was) return "that one was not on the list";
        save();
        return null;
    }

    /** The list, to be able to say by name who can. */
    static synchronized List<String> list() {
        return new ArrayList<>(load());
    }

    static synchronized String asJson(String who) {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"commanders\":[");
        boolean firstItem = true;
        for (String q : load()) {
            if (!firstItem) sb.append(',');
            firstItem = false;
            sb.append('"').append(Request.escape(q)).append('"');
        }
        sb.append(']');
        if (who != null && !who.isBlank()) {
            sb.append(",\"can\":").append(can(who));
        }
        return sb.append('}').toString();
    }
}

package masurium.bot;

import masurium.common.Request;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What each person has done with the bot.
 *
 * <p>It gives the bot <b>character through facts, not adjectives</b>. A prompt saying
 * "you are friendly" does not make it treat whoever gives it food differently from
 * whoever uses it for target practice; this does, because when asked "what do you think
 * of so-and-so" there is something to look at.
 *
 * <p>Two kinds of memory, on purpose:
 * <ul>
 *   <li><b>tallies</b> are kept by the body, about what it can check by itself: hits
 *       taken, deaths at someone's hands, escorts;</li>
 *   <li><b>notes</b> are written by the brain when something worth remembering happens
 *       ("gave me a bow", "got me out of a cave"). The mod does not interpret them, just
 *       as with orders: it only stores them.</li>
 * </ul>
 *
 * <p>Per server, like everything else: someone in one world is not the same someone in
 * another, and what they did there is none of this world's business.
 */
final class People {

    private People() {}

    /**
     * Free notes per person. Past this the oldest is forgotten: an endless history is
     * never read, and old things weigh less than yesterday's.
     */
    private static final int NOTES_MAX = 12;

    /** What it knows about someone: tallies and notes, in order of arrival. */
    record Card(TreeMap<String, Integer> tallies, List<String> memos) {}

    private static Path file;
    private static Map<String, Card> people;

    private static synchronized Map<String, Card> load() {
        if (people != null) return people;
        people = new LinkedHashMap<>();
        file = Path.of("config",
                "masurium-people-" + ServerIdentity.key() + ".txt");
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    line = line.strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    // name|key|value  (value: a number for a tally, text when the key is
                    // "note")
                    String[] t = line.split("\\|", 3);
                    if (t.length < 3) continue;
                    Card f = card(t[0].strip());
                    if (t[1].strip().equals("note")) {
                        f.memos().add(t[2].strip());
                    } else {
                        try {
                            f.tallies().put(t[1].strip(),
                                    Integer.parseInt(t[2].strip()));
                        } catch (NumberFormatException brokenOne) { /* ignored */ }
                    }
                }
            }
        } catch (IOException ignored) {
            // Broken file = no memories; it is rewritten clean on the first change.
        }
        return people;
    }

    private static Card card(String name) {
        return people.computeIfAbsent(name,
                n -> new Card(new TreeMap<>(), new ArrayList<>()));
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# What each person has done with me, in this world.");
            for (var e : people.entrySet()) {
                for (var c : e.getValue().tallies().entrySet()) {
                    lines.add(e.getKey() + "|" + c.getKey() + "|" + c.getValue());
                }
                for (String n : e.getValue().memos()) {
                    lines.add(e.getKey() + "|note|" + n);
                }
            }
            Files.write(file, lines);
        } catch (IOException e) {
            masurium.common.Logbook.note("people",
                    "could not save what I know about people: " + e.getMessage());
        }
    }

    /** Add one to someone's tally ("hits", "escorts", "killed_me"). */
    static synchronized void tally(String name, String what) {
        if (name == null || name.isBlank()) return;
        load();
        Card f = card(name.strip());
        f.tallies().merge(what, 1, Integer::sum);
        save();
    }

    /** Keep something about a person that is worth remembering. */
    static synchronized void note(String name, String memo) {
        if (name == null || name.isBlank() || memo == null
                || memo.isBlank()) {
            return;
        }
        load();
        Card f = card(name.strip());
        f.memos().add(memo.strip());
        while (f.memos().size() > NOTES_MAX) f.memos().remove(0);
        save();
    }

    /** What it knows about someone, or about everyone if the name is empty. */
    static synchronized String asJson(String name) {
        load();
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"people\":[");
        boolean firstItem = true;
        for (var e : people.entrySet()) {
            if (!name.isBlank()
                    && !e.getKey().equalsIgnoreCase(name.strip())) {
                continue;
            }
            if (!firstItem) sb.append(',');
            firstItem = false;
            sb.append(String.format("{\"who\":\"%s\",\"tallies\":{",
                    Request.escape(e.getKey())));
            boolean pc = true;
            for (var c : e.getValue().tallies().entrySet()) {
                if (!pc) sb.append(',');
                pc = false;
                sb.append('"').append(Request.escape(c.getKey()))
                  .append("\":").append(c.getValue());
            }
            sb.append("},\"notes\":[");
            boolean pn = true;
            for (String n : e.getValue().memos()) {
                if (!pn) sb.append(',');
                pn = false;
                sb.append('"').append(Request.escape(n)).append('"');
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }
}

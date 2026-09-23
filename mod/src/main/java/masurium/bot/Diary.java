package masurium.bot;

import masurium.common.Request;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The diary: what is worth remembering a week from now.
 *
 * <p>It must be persistent, and that is exactly the point: the {@link
 * masurium.common.Logbook} lives in memory and dies with the client, so the bot could
 * not tell anything from the day before yesterday. A whole life that starts from zero on
 * every restart is not a life.
 *
 * <p><b>It is not the logbook on disk.</b> The logbook notes everything (every step,
 * every arrow, every time it got stuck) and serves to diagnose ten minutes; the diary
 * notes THE MEMORABLE and serves to remember. If something would not be told out loud, it
 * does not go here. That is why only someone with a reason writes: death, crossing
 * worlds, the first time something is seen, a big errand, or the brain itself when it
 * sees fit.
 *
 * <p>One file per server, like places and orders: what happened on one server is not the
 * history of another.
 */
final class Diary {

    private Diary() {}

    /**
     * What is kept; older than this is trimmed when writing. A hundred entries are months
     * of memorable things, not of noise.
     */
    private static final int MEMORY = 300;
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * The things it has already seen at some point, to know when something is the FIRST
     * time. They are kept on their own marked line of the file.
     */
    private static final String SEEN = "#seen ";

    private static Path file;
    private static List<String> entries;
    private static Set<String> seenOnes;

    private static synchronized void load() {
        if (entries != null) return;
        entries = new ArrayList<>();
        seenOnes = new LinkedHashSet<>();
        file = Path.of("config",
                "masurium-diary-" + ServerIdentity.key() + ".txt");
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    line = line.strip();
                    if (line.isEmpty()) continue;
                    if (line.startsWith(SEEN)) {
                        seenOnes.add(line.substring(SEEN.length()).strip());
                    } else if (!line.startsWith("#")) {
                        entries.add(line);
                    }
                }
            }
        } catch (IOException ignored) {
            // An unreadable diary is an empty diary; it is rewritten clean.
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Diary of this server. The memorable, not the routine.");
            while (entries.size() > MEMORY) entries.remove(0);
            lines.addAll(entries);
            for (String v : seenOnes) lines.add(SEEN + v);
            Files.write(file, lines);
        } catch (IOException e) {
            masurium.common.Logbook.note("diary",
                    "could not save the diary: " + e.getMessage());
        }
    }

    /**
     * Notes something memorable. It also goes to the logbook, which is where the
     * right-now is looked at.
     */
    static synchronized void note(String text) {
        String cleanOne = text.strip();
        if (cleanOne.isEmpty()) return;
        load();
        entries.add(LocalDateTime.now().format(CLOCK) + " | " + cleanOne);
        save();
        masurium.common.Logbook.note("diary", cleanOne);
    }

    /**
     * Is this the first time it sees this?
     *
     * <p>Asked and marked at once: callers do it right when it is in front of them, and
     * asking twice about the same thing would give two first times.
     *
     * @param what something stable: "entity:ender_dragon", "dimension:the_nether"
     */
    static synchronized boolean firstTime(String what) {
        load();
        if (!seenOnes.add(what.strip())) return false;
        save();
        return true;
    }

    /** The latest {@code howMany} entries, from oldest to newest. */
    static synchronized String asJson(int howMany) {
        load();
        int from = Math.max(0, entries.size() - Math.max(1, howMany));
        StringBuilder sb = new StringBuilder(String.format(
                "{\"ok\":true,\"how_many\":%d,\"entries\":[", entries.size()));
        for (int i = from; i < entries.size(); i++) {
            if (i > from) sb.append(',');
            sb.append('"').append(Request.escape(entries.get(i))).append('"');
        }
        return sb.append("]}").toString();
    }
}

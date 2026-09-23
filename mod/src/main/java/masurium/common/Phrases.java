package masurium.common;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The few sentences a bot says on its own, without its brain.
 *
 * <p>Everything else it says comes from the brain, in its own voice and in the language
 * its personality speaks. These do not go through the brain when they are said: they are
 * the urgent ones, said by the body in the same tick (a creeper next to the person being
 * escorted, being cornered), where ten seconds of thinking would be ten seconds late.
 *
 * <p>So the brain writes them BEFOREHAND. When its bridge starts, it asks the brain for its
 * own version of each of these sentences and writes them to
 * {@code config/masurium-phrases.properties}; the body says those, at once, in the bot's
 * voice. What is not written there yet, or was written wrong (a placeholder lost on the
 * way), is said as below: plain, neutral English. The mod itself speaks nothing else.
 *
 * <p>Placeholders have names, {@code {player}}, because another language may want them in
 * another order. The arguments of {@link #of} fill them in the order they appear in the
 * English sentence.
 */
public final class Phrases {

    private Phrases() {}

    /** The English sentences, and the catalog the brain writes its own versions of. */
    private static final Map<String, String> EN = ordered(
            "shelter", "Night fell and I am out in the open; moving to {place}.",
            "known_spot", "a known spot",
            "hurt_cornered", "Badly hurt and with no way out.",
            "creeper_cornered", "Creeper on top of me and no way out.",
            "explored", "I reached {x} {y} {z}, {what}. {next}",
            "turning_back", "Turning back.",
            "nothing_here", "No {what} here: on to segment {segment} of {segments}.",
            "next_segment", "On to segment {segment} of {segments}.",
            "escort_creeper", "{player}, creeper {blocks} blocks from you!",
            "recovered", "I respawned. Going for my things at {place}.");

    /** Where the brain's versions are, relative to the game folder (like the rest of
     *  config/). */
    public static final Path FILE = Path.of("config", "masurium-phrases.properties");
    /** What a sentence may be at most: the chat cuts a little past this. */
    static final int LIMIT = 240;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z]+)\\}");

    private static Map<String, String> own = Map.of();
    private static long ownStamp = -1;

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }

    /** The English catalog: key -> sentence, placeholders included. */
    public static Map<String, String> catalog() {
        return EN;
    }

    /** The sentence for that key, filled in: the bot's own if it wrote a good one. */
    public static String of(String key, Object... arguments) {
        return fill(key, ownVersions().get(key), arguments);
    }

    /** The same, with its own versions given: this is what the tests use. */
    public static String of(Map<String, String> versions, String key, Object... arguments) {
        return fill(key, versions.get(key), arguments);
    }

    private static String fill(String key, String version, Object... arguments) {
        String english = EN.get(key);
        if (english == null) {
            throw new IllegalArgumentException("there is no sentence called " + key);
        }
        String pattern = version != null && acceptable(key, version) ? version : english;
        List<String> names = placeholders(english);
        String said = pattern;
        for (int i = 0; i < names.size() && i < arguments.length; i++) {
            said = said.replace("{" + names.get(i) + "}", String.valueOf(arguments[i]));
        }
        return said;
    }

    /** The placeholders of a sentence, in order, each once. */
    static List<String> placeholders(String sentence) {
        List<String> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(sentence);
        while (m.find()) {
            if (!out.contains(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** Whether the brain's version of a sentence can be said: it keeps exactly the
     *  placeholders of the English one (a lost {blocks} would say a warning without the
     *  distance; an invented one would reach the chat as it is), fits in the chat, is one
     *  line, and is not a command. */
    public static boolean acceptable(String key, String version) {
        String english = EN.get(key);
        if (english == null || version == null) {
            return false;
        }
        String v = version.strip();
        if (v.isEmpty() || v.length() > LIMIT || v.contains("\n") || v.startsWith("/")) {
            return false;
        }
        Set<String> want = new TreeSet<>(placeholders(english));
        Set<String> got = new TreeSet<>(placeholders(v));
        return want.equals(got) && !v.replaceAll("\\{[a-z]+\\}", "").contains("{");
    }

    /** The versions in the file, read again only when the file changes. */
    private static synchronized Map<String, String> ownVersions() {
        long stamp;
        try {
            stamp = Files.exists(FILE) ? Files.getLastModifiedTime(FILE).toMillis() : 0;
        } catch (IOException e) {
            stamp = 0;
        }
        if (stamp != ownStamp) {
            own = read(FILE);
            ownStamp = stamp;
        }
        return own;
    }

    /** A versions file, as key -> sentence; empty when there is none. */
    public static Map<String, String> read(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return out;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            return out;
        }
        for (String key : EN.keySet()) {
            String v = p.getProperty(key);
            if (v != null) {
                out.put(key, v.strip());
            }
        }
        return out;
    }
}

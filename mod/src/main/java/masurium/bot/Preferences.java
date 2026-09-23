package masurium.bot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * The bot's behaviour preferences, as this body holds them.
 *
 * <p><b>It no longer changes them by itself.</b> They used to be toggled by asking the AI,
 * and the tool that did it said "only on the owner's order" — a sentence in a prompt, with
 * nothing enforcing it. Now they are the server's rules for this bot (from the launcher,
 * and {@code /masurium bot <bot> pref <key> on|off}) and arrive here whole through the
 * bridge ({@link #hold}); the brain keeps a tool that only READS them. This file is what
 * the body reads every tick, and what it holds while no bridge has told it anything.
 *
 * <p>It is a file SEPARATE from the permissions on purpose: the whitelist is SAFETY (what
 * it may touch) and this is TASTE (how it behaves). Mixing them would invite a "change
 * how you follow me" to end up touching what it may break.
 *
 * <p>The keys, their defaults and what each one does live in {@link
 * masurium.common.Settings}, which the server reads too. Only KNOWN keys: a typo does
 * not create ghost preferences that look saved and do nothing.
 */
final class Preferences {

    private Preferences() {}

    /** Per server, like every toggle. */
    private static Path file() {
        return ServerIdentity.file("preferences");
    }

    /**
     * The keys that exist, with their default value. They come from {@link
     * masurium.common.Settings}, shared with the server: the command that switches
     * them has to offer the same list, and two copies drift.
     */
    private static final TreeMap<String, Boolean> DEFAULTS =
            new TreeMap<>(masurium.common.Settings.defaults());

    private static TreeMap<String, Boolean> valueList;

    private static synchronized TreeMap<String, Boolean> load() {
        if (valueList != null) return valueList;
        valueList = new TreeMap<>(DEFAULTS);
        try {
            if (Files.exists(file())) {
                for (String line : Files.readAllLines(file())) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int i = line.indexOf('=');
                    if (i < 0) continue;
                    String k = line.substring(0, i).trim();
                    // Unknown keys are ignored without removing them from the map in
                    // memory: if an old version wrote something, it is not lost on
                    // saving... but it does not govern anything either.
                    if (DEFAULTS.containsKey(k)) {
                        valueList.put(k, Boolean.parseBoolean(
                                line.substring(i + 1).trim()));
                    }
                }
            } else {
                save();
            }
        } catch (IOException e) {
            // With an unreadable file the defaults rule: always the conservative choice.
        }
        return valueList;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Behaviour preferences, written from the server's rules for this "
                    + "bot: /masurium bot <bot> pref, or the launcher.");
            for (var e : valueList.entrySet()) {
                lines.add(e.getKey() + "=" + e.getValue());
            }
            Files.write(file(), lines);
        } catch (IOException e) {
            masurium.common.Logbook.note("preferences",
                    "could not save the file: " + e.getMessage());
        }
    }

    static synchronized boolean is(String key) {
        return load().getOrDefault(key, false);
    }

    /** @return null if it worked, or the reason (unknown key) */
    static synchronized String place(String key, boolean value) {
        if (!DEFAULTS.containsKey(key)) {
            return "there is no preference '" + key + "'; the ones there are: "
                    + String.join(", ", DEFAULTS.keySet());
        }
        load().put(key, value);
        save();
        return null;
    }

    /**
     * Holds exactly these values: what the server's rules come to. A key this body does
     * not know is left out (a newer server); one not given goes back to its default.
     *
     * @return what changed, as {@code key=value}
     */
    static synchronized List<String> hold(java.util.Map<String, Boolean> values) {
        TreeMap<String, Boolean> want = new TreeMap<>(DEFAULTS);
        values.forEach((k, v) -> {
            if (DEFAULTS.containsKey(k) && v != null) want.put(k, v);
        });
        TreeMap<String, Boolean> have = load();
        List<String> changed = new ArrayList<>();
        want.forEach((k, v) -> {
            if (!v.equals(have.get(k))) changed.add(k + "=" + v);
        });
        if (!want.equals(have)) {
            valueList = want;
            save();
        }
        return changed;
    }

    static synchronized TreeMap<String, Boolean> allItems() {
        return new TreeMap<>(load());
    }
}

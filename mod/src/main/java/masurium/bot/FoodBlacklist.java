package masurium.bot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The food the bot does NOT touch on its own.
 *
 * <p>Eating on its own checks whether something is food, not whether that something is
 * the errand: a fishing afternoon can end up eaten without the bot noticing it was
 * working.
 *
 * <p>The ban only covers what the bot does ON ITS OWN. Asking it directly still works
 * ("eat a salmon" is an order, not an oversight), just as with rotten flesh: never on its
 * own, by hand yes.
 *
 * <p>It is born with the two golden apples inside, which is what nobody wants to see
 * disappear in a hunger dip. The rest is decided on the server — the bot's rules, from the
 * launcher and from {@code /masurium bot <bot> food ban|allow <item>} — and arrives
 * through the bridge, whole ({@link #hold}): the brain has no tool that writes this list.
 * Asking the bot to ban something used to be enough, and "only the owner may" was a
 * sentence in its prompt.
 *
 * <p>Per server, like permissions and preferences: fish is an errand in one world and a
 * snack in another.
 */
final class FoodBlacklist {

    private FoodBlacklist() {}

    /** What the list is born with (the server starts from the same one). */
    private static final List<String> FACTORY = masurium.common.Settings.FOOD_FACTORY;

    private static Path file() {
        return ServerIdentity.file("vetoed-food");
    }

    private static Set<String> bannedOnes;

    private static synchronized Set<String> load() {
        if (bannedOnes != null) return bannedOnes;
        bannedOnes = new LinkedHashSet<>();
        try {
            if (Files.exists(file())) {
                for (String line : Files.readAllLines(file())) {
                    line = line.strip().toLowerCase();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        bannedOnes.add(line);
                    }
                }
                return bannedOnes;
            }
        } catch (IOException ignored) {
            // Unreadable list: start with the factory one and rewrite it.
        }
        bannedOnes.addAll(FACTORY);
        save();
        return bannedOnes;
    }

    private static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Food I do NOT eat on my own. By hand yes, if asked "
                    + "for it by that name.");
            lines.addAll(bannedOnes);
            Files.write(f, lines);
        } catch (IOException e) {
            masurium.common.Logbook.note("food",
                    "could not save the vetoed food list: "
                    + e.getMessage());
        }
    }

    /** Whether it must NOT eat this on its own. */
    static synchronized boolean banned(String id) {
        return id != null && load().contains(id.strip().toLowerCase());
    }

    /** @return true if it was not already there */
    static synchronized boolean ban(String id) {
        boolean fresh = load().add(id.strip().toLowerCase());
        if (fresh) save();
        return fresh;
    }

    /** @return true if it was there */
    static synchronized boolean allow(String id) {
        boolean was = load().remove(id.strip().toLowerCase());
        if (was) save();
        return was;
    }

    /**
     * Holds exactly this list: what the server's rules come to.
     *
     * @return what changed, as {@code +id} (banned now) and {@code -id} (not any more)
     */
    static synchronized List<String> hold(java.util.Collection<String> ids) {
        Set<String> want = new LinkedHashSet<>();
        for (String id : ids) want.add(id.strip().toLowerCase());
        Set<String> have = load();
        List<String> changed = new ArrayList<>();
        for (String id : want) {
            if (!have.contains(id)) changed.add("+" + id);
        }
        for (String id : have) {
            if (!want.contains(id)) changed.add("-" + id);
        }
        if (!changed.isEmpty()) {
            bannedOnes = want;
            save();
        }
        return changed;
    }

    static synchronized List<String> list() {
        return new ArrayList<>(load());
    }
}

package marionette.bot;

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
 * <p>The veto only covers what the bot does ON ITS OWN. Asking it directly still works
 * ("eat a salmon" is an order, not an oversight), just as with rotten flesh: never on its
 * own, by hand yes.
 *
 * <p>It is born with the two golden apples inside, which is what nobody wants to see
 * disappear in a hunger dip. The rest is said in the chat and stored, like the break
 * permissions.
 *
 * <p>Per server, like permissions and preferences: fish is an errand in one world and a
 * snack in another.
 */
final class FoodBlacklist {

    private FoodBlacklist() {}

    /**
     * What the list is born with. What is expensive to replace and almost always meant
     * for something else.
     */
    private static final List<String> FACTORY =
            List.of("golden_apple", "enchanted_golden_apple");

    private static Path file() {
        return ServerIdentity.file("vetoed-food");
    }

    private static Set<String> vetoedOnes;

    private static synchronized Set<String> load() {
        if (vetoedOnes != null) return vetoedOnes;
        vetoedOnes = new LinkedHashSet<>();
        try {
            if (Files.exists(file())) {
                for (String line : Files.readAllLines(file())) {
                    line = line.strip().toLowerCase();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        vetoedOnes.add(line);
                    }
                }
                return vetoedOnes;
            }
        } catch (IOException ignored) {
            // Unreadable list: start with the factory one and rewrite it.
        }
        vetoedOnes.addAll(FACTORY);
        save();
        return vetoedOnes;
    }

    private static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Food I do NOT eat on my own. By hand yes, if asked "
                    + "for it by that name.");
            lines.addAll(vetoedOnes);
            Files.write(f, lines);
        } catch (IOException e) {
            marionette.common.Logbook.note("food",
                    "could not save the vetoed food list: "
                    + e.getMessage());
        }
    }

    /** Whether it must NOT eat this on its own. */
    static synchronized boolean vetoed(String id) {
        return id != null && load().contains(id.strip().toLowerCase());
    }

    /** @return true if it was not already there */
    static synchronized boolean veto(String id) {
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

    static synchronized List<String> list() {
        return new ArrayList<>(load());
    }
}

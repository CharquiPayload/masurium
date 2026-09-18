package marionette.bot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Standing orders: rules the players dictate once and that apply forever, by category.
 * "If you are out of fuel, take it from the wooden chest", and the next time the bot
 * cooks it does so without anyone repeating it.
 *
 * <p><b>The mod does NOT interpret them.</b> They are free text for the BRAIN: here they
 * are only stored, numbered and by category, and the brain reads them before each task of
 * that category. That is why they go by category: so the hunting rules do not fill its
 * head while it is flattening a hole. Writing, changing and deleting them is also the
 * brain's job, on the players' orders: "prefer the factory furnace" deletes the one about
 * the lake furnace and notes the new one.
 *
 * <p>Per server, like places: a rule about the wooden chest of one world means nothing in
 * another.
 */
final class StandingOrders {

    private StandingOrders() {}

    /**
     * Closed, like the place types: a free category per rule would end with each order in
     * its own box that nobody looks at again.
     */
    /**
     * 'idle' is what the bot does when it has had nothing to do for a while: a text a
     * player dictates ('look for beef') that the brain runs as an errand when the idle
     * notice arrives. Only one fits: noting another replaces it.
     */
    static final List<String> CATEGORIES = List.of(
            "cooking", "hunting", "gear", "building", "travel", "general",
            "idle");
    static final String INACTIVE = "idle";

    record Order(String category, String text) {}

    private static Path file;
    private static List<Order> orders;

    private static synchronized List<Order> load() {
        if (orders != null) return orders;
        orders = new ArrayList<>();
        file = Path.of("config",
                "marionette-orders-" + ServerIdentity.key() + ".txt");
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int i = line.indexOf('|');
                    if (i < 0) continue;
                    String cat = line.substring(0, i).trim();
                    if (CATEGORIES.contains(cat)) {
                        orders.add(new Order(cat, line.substring(i + 1).trim()));
                    }
                }
            }
        } catch (IOException ignored) { }
        return orders;
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Standing orders of THIS server: category|text");
            for (Order o : orders) {
                lines.add(o.category() + "|" + o.text());
            }
            Files.write(file, lines);
        } catch (IOException e) {
            marionette.common.Logbook.note("orders",
                    "could not save the orders: " + e.getMessage());
        }
    }

    /** @return null if it was noted, or the reason */
    static synchronized String note(String category, String text) {
        if (!CATEGORIES.contains(category)) {
            return "there is no category '" + category + "'; the ones there are: "
                    + String.join(", ", CATEGORIES);
        }
        if (text.isBlank()) return "an empty order orders nothing";
        if (INACTIVE.equals(category)) {
            load().removeIf(o -> INACTIVE.equals(o.category()));
        }
        load().add(new Order(category, text.trim()));
        save();
        return null;
    }

    /**
     * Deletes order N (1-based) WITHIN its category: the same number it was listed with,
     * so the brain deletes what it just read.
     *
     * @return the deleted text, or null if it did not exist
     */
    static synchronized String delete(String category, int number) {
        int view = 0;
        for (int i = 0; i < load().size(); i++) {
            if (orders.get(i).category().equals(category)) {
                if (++view == number) {
                    String text = orders.remove(i).text();
                    save();
                    return text;
                }
            }
        }
        return null;
    }

    /** The orders of a category ("" = all), in order of arrival. */
    static synchronized List<Order> of(String category) {
        List<Order> list = new ArrayList<>();
        for (Order o : load()) {
            if (category.isEmpty() || o.category().equals(category)) {
                list.add(o);
            }
        }
        return list;
    }
}

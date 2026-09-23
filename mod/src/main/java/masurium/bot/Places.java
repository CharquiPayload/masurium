package masurium.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The memory of useful places: tables, furnaces, beds, chests, portals and the points
 * players name. Per server and per dimension.
 *
 * <p>The bot remembers where things are, automatically when it uses or places them, or
 * because a player tells it, so that when it needs to sleep, smelt or craft it can choose
 * between going to the nearest one it remembers and making its own. The DECISION belongs
 * to the brain; only the memory lives here.
 *
 * <p><b>One file per server</b>, because the same bot joins different worlds and a bed in
 * one does not exist in another. The key is written by the launcher into {@code
 * config/masurium-server.txt}: the mod has no way of knowing which server it joined
 * (several may share an IP and port, running one at a time), but the launcher does.
 *
 * <p><b>And the DIMENSION inside each memory</b>: in the Nether the same x and z are
 * ANOTHER place (at one eighth of the scale), so without it a Nether portal and an
 * overworld bed could take the same slot of the memory and overwrite each other. Old
 * files do not carry it and are read as overworld, which is where they always were.
 *
 * <p>Memories also lie: a chest gets removed, a bed gets broken. The rule is literal:
 * when arriving and the block is gone, delete it from memory. Whoever uses the place does
 * that (FurnaceHandler on finding something else, the brain with forget_place).
 */
final class Places {

    private Places() {}

    /**
     * The types that are remembered. Closed on purpose, like the preferences: a free type
     * would pile up junk ("nice spot") that nobody looks at.
     *
     * <p>{@code point} is the exception: any spot a player names ("the factory", "the
     * portal") so they can later just say "go to the factory". It does not contradict the
     * above because the name is MANDATORY ({@link #requiresName}) and automatic capture
     * never creates points: a point without a name would be exactly that junk.
     *
     * <p>{@code portal} has its own type, and is not just another point, because it is
     * asked for by what it IS ("take me to the portal") and because it is the only thing
     * that changes dimension.
     *
     * <p>The bot sets {@code death} by itself on dying and there is only one: where it
     * was killed last, so it can go back for what stayed there.
     */
    static final List<String> TYPES = List.of(
            "table", "furnace", "bed", "chest", "point", "portal", "death",
            "wolf",    // a wild wolf seen without bones, to come back for it
            "farm"); // a plot sown by the bot (corner; label = crop)

    /**
     * Whether this type cannot exist without a name. Only the point: the others are
     * recognized by what they are, a point only by what it was called.
     */
    static boolean requiresName(String type) {
        return type.equals("point");
    }

    /**
     * A memory: what it is, where, in which dimension, and what whoever noted it called
     * it ("" = no name). The label is what makes "chest of blocks at x y z" useful: later
     * "take blocks from the chest of blocks" is resolved by the brain through the name.
     */
    record Place(String type, BlockPos where, String dimension,
                 String label) {}

    /** Where things have always been, and what an old file is assumed to be. */
    private static final String DEFAULT = "overworld";

    private static Path file;
    private static Map<String, Place> places;

    /**
     * Which dimension it is in right now, with the short name the game uses: overworld,
     * the_nether, the_end.
     */
    static String currentDimension() {
        var p = Minecraft.getInstance().player;
        return p == null ? DEFAULT
                : p.level().dimension().location().getPath();
    }

    private static String key(String dimension, BlockPos where) {
        return dimension + "|" + where.asLong();
    }

    private static synchronized Map<String, Place> load() {
        if (places != null) return places;
        places = new LinkedHashMap<>();
        file = Path.of("config",
                "masurium-places-" + ServerIdentity.key() + ".txt");
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    Place l = read(line.trim());
                    if (l != null) {
                        places.put(key(l.dimension(), l.where()), l);
                    }
                }
            }
        } catch (IOException ignored) {
            // Broken file = empty memory; it is rewritten clean on the first change.
        }
        return places;
    }

    /**
     * A line of the file, or null if it is not valid.
     *
     * <p>Two formats, told apart without a version number: the new one is {@code
     * type,dimension,x,y,z[,label]} and the old one {@code type,x,y,z[,label]}. Since a
     * dimension is never a number and an x always is, looking at the second field is
     * enough and they cannot be confused.
     */
    private static Place read(String line) {
        if (line.isEmpty() || line.startsWith("#")) return null;
        String[] t = line.split(",", 6);
        if (t.length < 4 || !TYPES.contains(t[0].trim())) return null;
        try {
            boolean old = isNumber(t[1]);
            if (old) {
                return new Place(t[0].trim(),
                        new BlockPos(Integer.parseInt(t[1].trim()),
                                Integer.parseInt(t[2].trim()),
                                Integer.parseInt(t[3].trim())),
                        DEFAULT,
                        t.length >= 5 ? t[4].trim() : "");
            }
            if (t.length < 5) return null;
            return new Place(t[0].trim(),
                    new BlockPos(Integer.parseInt(t[2].trim()),
                            Integer.parseInt(t[3].trim()),
                            Integer.parseInt(t[4].trim())),
                    t[1].trim(),
                    t.length == 6 ? t[5].trim() : "");
        } catch (NumberFormatException brokenOne) {
            return null;
        }
    }

    private static boolean isNumber(String s) {
        try {
            Integer.parseInt(s.trim());
            return true;
        } catch (NumberFormatException negative) {
            return false;
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Useful places of THIS server: "
                    + "type,dimension,x,y,z[,label]");
            for (Place l : places.values()) {
                BlockPos p = l.where();
                lines.add(String.format("%s,%s,%d,%d,%d%s",
                        l.type(), l.dimension(), p.getX(), p.getY(), p.getZ(),
                        l.label().isEmpty() ? "" : "," + l.label()));
            }
            Files.write(file, lines);
        } catch (IOException e) {
            masurium.common.Logbook.note("places",
                    "could not save the memory: " + e.getMessage());
        }
    }

    /** @return true if it was new (to note only first sightings) */
    static synchronized boolean remember(String type, BlockPos where) {
        return remember(type, where, "");
    }

    /**
     * With a label. Remembering without a label does NOT erase an existing one: automatic
     * capture comes through here with "" and must not overwrite the name a player gave.
     */
    static synchronized boolean remember(String type, BlockPos where,
                                         String label) {
        if (!TYPES.contains(type)) return false;
        String dim = currentDimension();
        Place before = load().get(key(dim, where));
        String et = label.isEmpty() && before != null
                ? before.label() : label;
        // The point's name is required here too, not only by the caller: the memory owns
        // its own rule.
        if (requiresName(type) && et.isEmpty()) return false;
        Place latest = new Place(type, where.immutable(), dim, et);
        if (!latest.equals(before)) {
            load().put(key(dim, where), latest);
            save();
            return true;
        }
        return false;
    }

    /**
     * Deletes every place of a type, in every dimension. Used by death: only the last one
     * matters, older ones are noise.
     */
    static synchronized void forgetType(String type) {
        if (load().values().removeIf(l -> l.type().equals(type))) save();
    }

    /**
     * @return true if it was there. The one in THIS dimension is forgotten: forgetting is
     *         something done while standing in front of the place.
     */
    static synchronized boolean forget(BlockPos where) {
        boolean was = load().remove(
                key(currentDimension(), where)) != null;
        if (was) save();
        return was;
    }

    /**
     * Every place of a type ("" = all), from every dimension: the caller decides what to
     * do with the ones elsewhere.
     */
    static synchronized List<Place> of(String type) {
        List<Place> list = new ArrayList<>();
        for (Place l : load().values()) {
            if (type.isEmpty() || l.type().equals(type)) list.add(l);
        }
        return list;
    }

    /**
     * The places whose name contains this text. Without accents or capitals ("factory"
     * must find "the Factory") and without requiring the whole name, because whoever asks
     * "go to the factory" does not remember noting it as "the iron factory".
     *
     * <p>Empty if nothing is called that: that is an "I do not have it noted", not an
     * error. Guessing the nearest place would be worse than not knowing.
     */
    static synchronized List<Place> called(String name) {
        List<Place> list = new ArrayList<>();
        String lookup = blueprint(name);
        if (lookup.isEmpty()) return list;
        for (Place l : load().values()) {
            if (blueprint(l.label()).contains(lookup)) list.add(l);
        }
        return list;
    }

    /** Lowercase and without accents, to compare hand-written names. */
    private static String blueprint(String s) {
        return Normalizer.normalize(s.trim().toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }
}

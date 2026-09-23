package masurium.bot;

import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the bot PLACED itself, so it can break it without asking for permission.
 *
 * <p>A bot may break the crafting tables, chests, furnaces and so on that it placed
 * itself. The {@link BreakPermissions} whitelist is about natural blocks and protects
 * what belongs to others; this is the other half: what is the bot's own is its own. A
 * block counts as its own if it was placed through {@code /place} and is still of the
 * same type (if someone swapped it for something else, it no longer is).
 *
 * <p>One file per server, like {@link Places}: {@code
 * config/masurium-placed-<server>.txt}, one line per block ({@code
 * dimension,x,y,z,id}). Breaking the block removes it from here.
 */
final class PlacedBlocks {

    private PlacedBlocks() {}

    private static Path file;
    private static Map<String, String> placedOnes;

    private static String key(String dimension, BlockPos where) {
        return dimension + "|" + where.asLong();
    }

    private static synchronized Map<String, String> load() {
        if (placedOnes != null) return placedOnes;
        placedOnes = new LinkedHashMap<>();
        file = Path.of("config", "masurium-placed-" + ServerIdentity.key() + ".txt");
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    String l = line.trim();
                    if (l.isEmpty() || l.startsWith("#")) continue;
                    String[] t = l.split(",");
                    if (t.length != 5) continue;
                    try {
                        BlockPos p = new BlockPos(Integer.parseInt(t[1].trim()),
                                Integer.parseInt(t[2].trim()),
                                Integer.parseInt(t[3].trim()));
                        placedOnes.put(key(t[0].trim(), p), t[4].trim());
                    } catch (NumberFormatException ignored) {
                        // broken line: skipped
                    }
                }
            }
        } catch (IOException ignored) {
            // broken file = empty memory; it is rewritten clean on the first change
        }
        return placedOnes;
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Blocks I placed MYSELF on this server (I may break them "
                    + "without permission): dimension,x,y,z,id");
            for (var e : placedOnes.entrySet()) {
                String[] k = e.getKey().split("\\|");
                BlockPos p = BlockPos.of(Long.parseLong(k[1]));
                lines.add(String.format("%s,%d,%d,%d,%s", k[0], p.getX(),
                        p.getY(), p.getZ(), e.getValue()));
            }
            Files.write(file, lines);
        } catch (IOException e) {
            masurium.common.Logbook.note("placed",
                    "could not save what I placed: " + e.getMessage());
        }
    }

    /** I just placed {@code id} at {@code where}. */
    static synchronized void note(BlockPos where, String id) {
        Map<String, String> m = load();
        m.put(key(Places.currentDimension(), where.immutable()), id);
        save();
    }

    /** Did I place that block, of that type? */
    static synchronized boolean isMine(BlockPos where, String id) {
        String ownedByMe = load().get(key(Places.currentDimension(), where));
        return ownedByMe != null && ownedByMe.equals(id);
    }

    /** It is gone (I broke it): off the list. @return whether it was there. */
    static synchronized boolean forget(BlockPos where) {
        Map<String, String> m = load();
        if (m.remove(key(Places.currentDimension(), where)) == null) return false;
        save();
        return true;
    }
}

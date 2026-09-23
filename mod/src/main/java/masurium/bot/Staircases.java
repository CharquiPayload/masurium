package masurium.bot;

import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The staircases the bot already dug, with their STEPS, to walk down (or up) them again
 * without the path finder.
 *
 * <p>Without this, a bot that climbed back up its own staircase could not find the way
 * down again: the path finder does not find 50 blocks of zig-zag in a one-block tunnel
 * that starts far away (its budget runs out), so the bot dug ANOTHER staircase next to
 * it. The head and the foot are noted in {@link Places}, but there was nothing in
 * between: here goes the whole path, tile by tile, which is what the {@link Walker} walks
 * without searching.
 *
 * <p>One file per server, like {@link Places}: {@code
 * config/masurium-staircases-<server>.txt}, one line per staircase: {@code
 * dimension|elevation|x y z;x y z;...} from head to foot. Only staircases that reached
 * their elevation without jumps are kept (a relocation halfway leaves a gap in the path
 * and is not saved).
 */
final class Staircases {

    private Staircases() {}

    /** A saved staircase: from the head (first step) to the foot (last). */
    record Saved(String dimension, int elevation, List<BlockPos> steps) {
        BlockPos head() { return steps.get(0); }
        BlockPos foot() { return steps.get(steps.size() - 1); }
    }

    private static Path file;
    private static List<Saved> list;

    private static synchronized List<Saved> load() {
        if (list != null) return list;
        list = new ArrayList<>();
        file = Path.of("config", "masurium-staircases-" + ServerIdentity.key() + ".txt");
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    Saved g = read(line.trim());
                    if (g != null) list.add(g);
                }
            }
        } catch (IOException ignored) {
            // broken file = no staircases; it is rewritten clean on the first change
        }
        return list;
    }

    private static Saved read(String line) {
        if (line.isEmpty() || line.startsWith("#")) return null;
        String[] t = line.split("\\|");
        if (t.length != 3) return null;
        try {
            int elevation = Integer.parseInt(t[1].trim());
            List<BlockPos> steps = new ArrayList<>();
            for (String p : t[2].split(";")) {
                String[] c = p.trim().split(" ");
                if (c.length != 3) continue;
                steps.add(new BlockPos(Integer.parseInt(c[0]),
                        Integer.parseInt(c[1]), Integer.parseInt(c[2])));
            }
            return steps.size() >= 2 ? new Saved(t[0].trim(), elevation, steps) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Staircases I dug on this server, with their steps "
                    + "from head to foot: dimension|elevation|x y z;x y z;...");
            for (Saved g : list) {
                StringBuilder sb = new StringBuilder();
                for (BlockPos p : g.steps()) {
                    if (sb.length() > 0) sb.append(';');
                    sb.append(p.getX()).append(' ').append(p.getY()).append(' ')
                            .append(p.getZ());
                }
                lines.add(g.dimension() + "|" + g.elevation() + "|" + sb);
            }
            Files.write(file, lines);
        } catch (IOException e) {
            masurium.common.Logbook.note("staircases",
                    "could not save the staircase: " + e.getMessage());
        }
    }

    /**
     * I just reached the elevation by these steps: into memory. If there was one with the
     * same head already, it is replaced.
     */
    static synchronized void note(int elevation, List<BlockPos> steps) {
        if (steps == null || steps.size() < 2) return;
        List<BlockPos> copy = new ArrayList<>();
        for (BlockPos p : steps) copy.add(p.immutable());
        String dim = Places.currentDimension();
        load().removeIf(g -> g.dimension().equals(dim)
                && g.head().equals(copy.get(0)));
        list.add(new Saved(dim, elevation, copy));
        save();
    }

    /**
     * The nearest saved staircase by its head (to go down) or by its foot (to go up),
     * within {@code radius} blocks of {@code from} and in this dimension; null if there
     * is none.
     */
    static synchronized Saved nearby(BlockPos from, boolean perHead,
                                         double radius) {
        String dim = Places.currentDimension();
        Saved best = null;
        double bestD = radius * radius;
        for (Saved g : load()) {
            if (!g.dimension().equals(dim)) continue;
            BlockPos ref = perHead ? g.head() : g.foot();
            double d = ref.distSqr(from);
            if (d <= bestD) {
                bestD = d;
                best = g;
            }
        }
        return best;
    }

    static synchronized int howMany() {
        return load().size();
    }
}

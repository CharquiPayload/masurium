package marionette.common;

import java.util.HashMap;
import java.util.Map;

/**
 * A world drawn with text, for the tests.
 * Layers are written bottom to top. Each layer is a list of rows (growing Z) and each
 * character a column (growing X):
 *
 * <pre>
 *   #  stone                          .  air
 *   r  breakable rock (whitelisted)   ~  water
 *   S  start (air)                    G  goal (air)
 *   L  lava                           P  closed door (opened and walked through)
 * </pre>
 *
 * It exists to check behaviour without opening Minecraft: that the path goes around a
 * wall, does NOT jump off a cliff, climbs a step. A three-line map says more about a cost
 * rule than half an hour watching the bot.
 */
public final class TextWorld implements World {

    private final Map<Long, Character> cells = new HashMap<>();
    private Route.Point exitPoint;
    private Route.Point goal;

    /** @param yBase the Y of the first layer; the next ones go on top */
    public static TextWorld of(int yBase, String[]... layers) {
        TextWorld m = new TextWorld();
        for (int i = 0; i < layers.length; i++) {
            String[] rows = layers[i];
            for (int z = 0; z < rows.length; z++) {
                String row = rows[z];
                for (int x = 0; x < row.length(); x++) {
                    char c = row.charAt(x);
                    int y = yBase + i;
                    if (c == 'S') m.exitPoint = new Route.Point(x, y, z);
                    if (c == 'G') m.goal = new Route.Point(x, y, z);
                    m.cells.put(key(x, y, z), c);
                }
            }
        }
        return m;
    }

    public Route.Point exitPoint() { return exitPoint; }
    public Route.Point goal() { return goal; }

    @Override
    public boolean solid(int x, int y, int z) {
        Character c = cells.get(key(x, y, z));
        // Outside the drawing counts as solid: that way the tests do not depend on what
        // lies "beyond the paper", and the path finder cannot escape.
        if (c == null) return true;
        return c == '#' || c == 'r';
    }

    /** 'r' = BREAKABLE rock: solid, but whitelisted. */
    @Override
    public boolean breakable(int x, int y, int z) {
        Character c = cells.get(key(x, y, z));
        return c != null && c == 'r';
    }

    @Override
    public boolean water(int x, int y, int z) {
        Character c = cells.get(key(x, y, z));
        return c != null && c == '~';
    }

    /** 'P' = closed door: not solid (it opens), but it costs. */
    @Override
    public boolean door(int x, int y, int z) {
        Character c = cells.get(key(x, y, z));
        return c != null && c == 'P';
    }

    /** 'L' = lava: not solid, but it is neither walked on nor fallen into. */
    @Override
    public boolean lava(int x, int y, int z) {
        Character c = cells.get(key(x, y, z));
        return c != null && c == 'L';
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0xFFFF) << 32) | ((long) (y & 0xFFFF) << 16) | (z & 0xFFFF);
    }
}

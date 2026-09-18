package marionette.bot;

import marionette.common.Route;

import java.util.HashMap;
import java.util.Map;

/**
 * The tiles where the {@link Walker} got stuck recently.
 *
 * <p>Replanning from the same spot used to return the SAME route: for the path finder
 * that tile was still walkable, even though the body had jumped six times without getting
 * in. Here the tile is noted and {@link ClientWorld} treats it as not walkable for a
 * while, so EVERY path finder (go to, travel, follow, the fill job, the strip mine, the
 * staircase) goes around what just failed on its own, without touching any of them.
 *
 * <p>It lasts {@value #LASTS_S} seconds and not forever: the world changes, and a stuck
 * spot may have been a pig in the way.
 */
final class StuckSpots {

    private static final int LASTS_S = 90;
    private static final Map<Route.Point, Long> cells = new HashMap<>();

    private StuckSpots() {}

    static synchronized void markPlace(Route.Point c) {
        long now = System.currentTimeMillis();
        cells.values().removeIf(t -> t < now);
        cells.put(c, now + LASTS_S * 1000L);
    }

    /**
     * Called A LOT (once per tile the A* looks at): with an empty map it costs nothing,
     * and with something inside it is a lookup in a small map.
     */
    static synchronized boolean avoided(int x, int y, int z) {
        if (cells.isEmpty()) return false;
        Long until = cells.get(new Route.Point(x, y, z));
        return until != null && until >= System.currentTimeMillis();
    }

    static synchronized int howMany() {
        long now = System.currentTimeMillis();
        cells.values().removeIf(t -> t < now);
        return cells.size();
    }
}

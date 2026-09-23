package masurium.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What reached the backpack, and when: every rise in the count of an item, kept for a
 * few minutes.
 *
 * <p>It exists for waiting to be given something. The brain takes seconds to think an
 * answer, and whoever gives is often faster: told "she will give you a cactus", the bot
 * already had the cactus when it started waiting, waited 45 s for another that never
 * came, and looked stuck. Counting from when it starts waiting misses what came while it
 * thought; with this it asks what came since it was told.
 *
 * <p>A new player (joining, respawning) arrives with its backpack still to be synced
 * from the server: for a moment it looks empty and then full, and that is not a gift. So
 * after a new player the counts are only watched, not written down, for {@value
 * #SETTLE_MS} ms.
 */
public final class Received {

    static final long KEPT_MS = 5 * 60_000;
    static final long SETTLE_MS = 2_000;

    /** {@code count} of {@code what} came in at {@code atMs}. */
    public record Gain(long atMs, String what, int count) {
    }

    private final Map<String, Integer> last = new HashMap<>();
    private final Deque<Gain> gains = new ArrayDeque<>();
    private Object player;
    private long settledAt;

    /** The counts of this player's backpack now, item by item. */
    synchronized void observe(Object who, Map<String, Integer> counts, long nowMs) {
        if (who != player) {
            player = who;
            settledAt = nowMs + SETTLE_MS;
        }
        if (nowMs >= settledAt) {
            for (var e : counts.entrySet()) {
                int more = e.getValue() - last.getOrDefault(e.getKey(), 0);
                if (more > 0) gains.addLast(new Gain(nowMs, e.getKey(), more));
            }
        }
        last.clear();
        last.putAll(counts);
        while (!gains.isEmpty() && gains.peekFirst().atMs() < nowMs - KEPT_MS) {
            gains.removeFirst();
        }
    }

    /** What came in since that moment, oldest first. */
    synchronized List<Gain> since(long ms) {
        List<Gain> out = new ArrayList<>();
        for (Gain g : gains) {
            if (g.atMs() >= ms) out.add(g);
        }
        return out;
    }
}

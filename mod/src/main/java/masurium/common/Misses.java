package masurium.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which targets are not worth another arrow.
 *
 * <p>A bot escorting someone emptied about twenty arrows into a creeper it never touched:
 * a block in the way ate every shot and nothing was counting the failures. Three arrows
 * that do not lower a target's health and that target is ignored, until it hurts the bot,
 * which proves it can reach and be reached.
 *
 * <p>What counts is HEALTH, not the gesture: mob health is synced to the client, so an
 * arrow that lands is never confused with one that flies past. The health of the target is
 * recorded when each arrow leaves and compared with the next one.
 *
 * <p>It is shared by everything that shoots on its own account (the escort, the lookout
 * and the archer's errand): the count belongs to the target, not to whoever is aiming, and
 * a creeper that cannot be hit cannot be hit by any of them.
 *
 * <p>Only the last {@value #REMEMBERED} targets are remembered, oldest first out: a world
 * has no shortage of mobs and this must not grow without end.
 */
public final class Misses {

    private Misses() {}

    /** Arrows at one target without lowering its health before giving up on it. */
    public static final int PATIENCE = 3;
    /** How many targets are remembered at a time. */
    private static final int REMEMBERED = 64;

    /** Arrows at that target and the lowest health seen when one was released. */
    private record Count(int arrows, float hp) {}

    private static final Map<UUID, Count> BY_TARGET =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Count> eldest) {
                    return size() > REMEMBERED;
                }
            };

    /** Whether another arrow at that target is worth it. */
    public static synchronized boolean worthIt(UUID target) {
        Count c = BY_TARGET.get(target);
        return c == null || c.arrows() < PATIENCE;
    }

    /**
     * An arrow just left at that target, whose health is this. If it is lower than when
     * the previous arrow left, the arrows are landing and the count starts over.
     */
    public static synchronized void arrow(UUID target, float hp) {
        Count c = BY_TARGET.get(target);
        if (c == null || hp < c.hp() - 0.01f) {
            BY_TARGET.put(target, new Count(1, hp));
            return;
        }
        BY_TARGET.put(target, new Count(c.arrows() + 1, Math.min(hp, c.hp())));
    }

    /**
     * Give up on that target now, whatever the count says. The archer measures its own
     * arrows one by one, watching each in flight, and when it decides it says so here so
     * the escort and the lookout do not start over on the same target.
     */
    public static synchronized void giveUp(UUID target, float hp) {
        BY_TARGET.put(target, new Count(PATIENCE, hp));
    }

    /**
     * That target hurt the bot, so it deserves arrows again: whatever was in the way, it
     * is not in the way any more.
     */
    public static synchronized void hurtMe(UUID target) {
        BY_TARGET.remove(target);
    }

    /** How many arrows went at that target without lowering its health. */
    public static synchronized int arrowsAt(UUID target) {
        Count c = BY_TARGET.get(target);
        return c == null ? 0 : c.arrows();
    }

    /** Forgets everything. For a new world, and for the tests. */
    public static synchronized void forget() {
        BY_TARGET.clear();
    }

    /** How many targets are being remembered right now. */
    public static synchronized int remembered() {
        return BY_TARGET.size();
    }
}

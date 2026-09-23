package masurium.bot;

import masurium.common.Phrases;
import masurium.common.Logbook;
import masurium.common.Request;
import masurium.common.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Going back for its things after dying.
 *
 * <p>On a server without keepInventory or a graves mod, what the bot carried stays on the
 * ground where it died and <b>vanishes after five minutes</b>. Otherwise the body only
 * notified, and the brain had to decide to go, call {@code go_to}, then {@code
 * objects_nearby}, then {@code pick_up}... turns that only happen if someone names the
 * bot. Five minutes are not enough for that: by the time the brain found out, the gear no
 * longer existed.
 *
 * <p>One of the few things it does without being asked (self-rescue, respawning, eating,
 * and this), for the same reason as the others: between turns the bot does not exist, and
 * this has a clock.
 *
 * <p>How it works: the {@link Guard} notes the grave as a place of type {@code death} on
 * dying. Once alive again, after a two-second breather (the server confirms the respawn a
 * few ticks later), this starts a {@link Traveler} trip there (building if needed, and
 * crossing dimensions if it died in another one) and, at the grave, steps on whatever it
 * sees lying within {@value #RADIUS} blocks until nothing is left. Picking up is not an
 * action: getting just close enough makes the game take it in.
 *
 * <p>The brakes, which matter more than the feature:
 * <ul>
 *   <li><b>Two attempts per grave.</b> Dying in lava or next to whatever killed you and
 *       going back is dying again; on the third it stays where it respawned and says so.
 *       (The cap of five deaths in five minutes in {@code respawnAlone} still sits above
 *       this.)</li>
 *   <li><b>It gives up six minutes after dying</b>: there is nothing left to pick up, and
 *       walking there is a waste of time.</li>
 *   <li><b>The {@link Lookout} rules.</b> This runs inside the block the lookout skips in
 *       an emergency, and any new order from the brain ({@code go_to}, strip mine,
 *       staircase, stop) cancels it.</li>
 *   <li>Preference {@code recover_on_death}, true by default.</li>
 * </ul>
 */
final class ItemRecovery {

    /**
     * Dropped items last 5 min; plus a minute of margin in case it arrives just in time.
     */
    private static final long DEADLINE_MS = 6 * 60 * 1000L;
    /**
     * Ticks after respawning before moving: the server takes a moment to place the bot.
     */
    private static final int BREATHER = 40;
    /** How close to the grave counts as arrived (in XZ). */
    private static final double ARRIVED = 6.0;
    /** How far around the grave it looks: what drops scatters. */
    private static final double RADIUS = 12.0;
    /** How often, in ticks, it looks at the ground again. */
    private static final int EVERY = 10;
    /** Three seconds without seeing anything lying around = nothing left. */
    private static final int NOTHING = 60;
    private static final int ATTEMPTS_MAX = 2;
    /**
     * A waiting guard (after dying) does not go to its grave alone: its boss decides
     * that. MasuriumBot sets and clears it.
     */
    private volatile boolean noInitiative;

    synchronized void noInitiative(boolean yes) {
        noInitiative = yes;
        if (yes && recovering) stop("I am a guard: I wait for my boss's instructions");
    }

    private final Traveler traveler;
    private final Walker walker;

    private boolean wasDead;
    private boolean pending;
    private int ticksSinceRespawn;
    private long diedAt;

    private BlockPos grave;
    private BlockPos previousGrave;
    private int attempts;
    /**
     * Whether the last death was caused by THE TERRAIN (magma, lava, fire, drowning) and
     * why. The {@link Guard} notes it on dying, since it is the one reading the damage
     * source, and it is checked here on respawning.
     *
     * <p>Going back to that grave is going back to the spot that kills: the whirlpool
     * still pulls down, the magma still burns. A bot died at the same spot twice in a row
     * (the second time on its way to recover the first) and was heading for a third when
     * it was stopped from outside. And what falls into lava or magma burns with it, so
     * there is nothing to pick up anyway.
     */
    private static String terrainDeath;

    /** For the Guard: "this death was the terrain's". */
    static void trapGrave(String because) {
        terrainDeath = because;
    }

    private boolean recovering;
    private boolean atGrave;
    private int ticksWithNothing;
    private int ticksUntilLook;
    private int trips;
    private final Set<Integer> unreachable = new HashSet<>();
    private String outcome = "I have not had to recover anything";

    ItemRecovery(Traveler traveler, Walker walker) {
        this.traveler = traveler;
        this.walker = walker;
    }

    synchronized void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;

        if (!p.isAlive()) {
            if (!wasDead) {
                wasDead = true;
                diedAt = System.currentTimeMillis();
                if (recovering) stop("I was killed again on the way");
            }
            return;
        }
        if (wasDead) {
            // Just respawned: the Guard already noted the grave.
            wasDead = false;
            pending = !noInitiative;
            ticksSinceRespawn = 0;
            if (noInitiative) {
                Logbook.note("grave", "I do not go to my grave alone: I am a guard "
                        + "and I wait for what my boss says");
            }
        }
        if (pending) {
            if (++ticksSinceRespawn < BREATHER) return;
            pending = false;
            begin(mc, p);
            return;
        }
        if (!recovering) return;

        if (System.currentTimeMillis() - diedAt > DEADLINE_MS) {
            traveler.stop("the grave's time ran out");
            walker.stop("the grave's time ran out");
            stop("more than five minutes passed since I died; what "
                    + "was left on the ground has vanished");
            return;
        }

        if (!atGrave) {
            if (traveler.traveling()) return;
            if (!near(p, grave)) {
                String because = traveler.outcome();
                stop("I could not reach the grave"
                        + (because == null || because.isBlank() ? "" : ": " + because));
                return;
            }
            atGrave = true;
            ticksWithNothing = 0;
            ticksUntilLook = 0;
            Logbook.note("recovery", "I reached the grave; looking at what is left");
        }

        // At the grave: step on whatever is there, one at a time.
        if (walker.walking()) return;
        if (++ticksUntilLook < EVERY) return;
        ticksUntilLook = 0;

        ItemEntity target = null;
        double nearer = Double.MAX_VALUE;
        int seenOnes = 0;
        for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class,
                new AABB(grave).inflate(RADIUS))) {
            seenOnes++;
            if (unreachable.contains(e.getId())) continue;
            double d = p.distanceTo(e);
            if (d < nearer) { nearer = d; target = e; }
        }
        if (target == null) {
            ticksWithNothing += EVERY;
            if (ticksWithNothing < NOTHING) return;
            if (seenOnes > 0) {
                stop(String.format("I see %d thing(s) lying at the grave but "
                        + "I cannot reach them", seenOnes));
            } else {
                stop(trips > 0
                        ? "I picked up what was left at the grave; check the inventory"
                        : "nothing was left lying at the grave");
            }
            return;
        }
        ticksWithNothing = 0;

        // Like /pick_up: the client sees the item with exact coordinates, and the bot has
        // to get close until the boxes touch (0.4).
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MasuriumBot.whereAmI(world, p);
        Route.Point goal = new Route.Point(
                (int) Math.floor(target.getX()),
                (int) Math.floor(target.getY()),
                (int) Math.floor(target.getZ()));
        Route.Result r = Route.search(world, here, Route.Meta.near(goal, 1.0),
                new Route.Options(MasuriumBot.safeFall(p.getHealth()), 8_000,
                        true).withDeadline(40)
                        .breaking(Preferences.is("break_to_advance")));
        if (!r.hasRoute() || walker.follow(r.steps(), d -> null, 0.4) != null) {
            unreachable.add(target.getId());
            return;
        }
        trips++;
    }

    private void begin(Minecraft mc, LocalPlayer p) {
        if (!Preferences.is("recover_on_death")) return;
        List<Places.Place> graves = Places.of("death");
        if (graves.isEmpty()) return;
        Places.Place l = graves.get(0);
        grave = l.where();

        if (terrainDeath != null) {
            String because = terrainDeath;
            terrainDeath = null;
            outcome = String.format("I am not going back to %d %d %d: the terrain killed me "
                    + "(%s) and it is still there", grave.getX(), grave.getY(), grave.getZ(),
                    because);
            Logbook.note("recovery", outcome);
            Needs.warn("recover:" + grave.asLong() + ":trap",
                    outcome + ". What I carried is lost; do not insist on "
                    + "that way without changing something (gear, route or daylight)");
            return;
        }

        if (grave.equals(previousGrave)) {
            attempts++;
        } else {
            previousGrave = grave;
            attempts = 1;
        }
        unreachable.clear();
        trips = 0;
        if (attempts > ATTEMPTS_MAX) {
            outcome = String.format("I already tried %d times to go back to %d %d %d "
                    + "and I was killed again; I do not insist",
                    ATTEMPTS_MAX, grave.getX(), grave.getY(), grave.getZ());
            Logbook.note("recovery", outcome);
            Needs.warn("recover:" + grave.asLong() + ":gave_up",
                    outcome + ". If you want me to try again, tell me");
            return;
        }

        recovering = true;
        atGrave = false;
        outcome = null;
        Route.Point destination = new Route.Point(grave.getX(), grave.getY(), grave.getZ());
        String dim = l.dimension();
        if (dim == null || dim.isBlank() || dim.equals(Places.currentDimension())) {
            traveler.begin(destination, true);
        } else {
            traveler.begin(destination, dim, true);
        }
        String where = String.format("%d %d %d", grave.getX(), grave.getY(), grave.getZ());
        Logbook.note("recovery", "I respawned; going for my things to " + where
                + " (attempt " + attempts + ")");
        Diary.note("I respawned and went out for my things to " + where);
        Voice.say("recover", 60_000, Phrases.of("recovered", where));
    }

    /**
     * Stops and tells how it ended: to the brain, through a body notice, since the brain
     * decides whether to say something or send it again.
     */
    synchronized void stop(String because) {
        if (!recovering) return;
        recovering = false;
        atGrave = false;
        outcome = because;
        Logbook.note("recovery", because);
        Diary.note("back to the grave: " + because);
        if (grave != null) {
            Needs.warn("recover:" + grave.asLong() + ":" + because.hashCode(),
                    String.format("I went alone to my grave at %d %d %d: %s",
                            grave.getX(), grave.getY(), grave.getZ(), because));
        }
    }

    private static boolean near(LocalPlayer p, BlockPos q) {
        double dx = q.getX() + 0.5 - p.getX();
        double dz = q.getZ() + 0.5 - p.getZ();
        return dx * dx + dz * dz <= ARRIVED * ARRIVED;
    }

    synchronized String state() {
        if (!recovering) {
            return String.format("{\"recovering\":false,\"outcome\":\"%s\"}",
                    Request.escape(outcome));
        }
        return String.format("{\"recovering\":true,\"at_grave\":%b,"
                + "\"grave\":{\"x\":%d,\"y\":%d,\"z\":%d},\"attempt\":%d,\"trips\":%d}",
                atGrave, grave.getX(), grave.getY(), grave.getZ(), attempts, trips);
    }
}

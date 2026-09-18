package marionette.bot;

import marionette.common.Phrases;
import marionette.common.Logbook;
import marionette.common.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;

/**
 * What the bot does when nobody gives it anything to do: not stand in the middle of a
 * field at night.
 *
 * <p>Staying close to light or home without an errand, instead of standing in the open,
 * is its own behaviour, not an order. It is also the sensible thing: at night, outdoors
 * and in the dark is exactly where the mobs that eat it spawn.
 *
 * <p>Three conditions to move, and all three matter:
 * <ul>
 *   <li><b>really idle</b>: nobody has asked it for anything in a while and it is not
 *       walking. Interrupting a task on its own initiative would be exactly the opposite
 *       of what is wanted;</li>
 *   <li><b>alone</b>: if someone is nearby, it stays with that person. Going home and
 *       leaving someone standing there is bad company;</li>
 *   <li><b>in the dark with sky above</b>: under a roof or with light there is no problem
 *       to solve.</li>
 * </ul>
 *
 * <p>And if it has no noted place nearby, it stays where it is and says so once.
 * Wandering in the dark looking for home would be worse than waiting.
 */
final class Routine {

    /** How often, in ticks, it thinks about life. Five seconds. */
    private static final int EVERY = 100;
    /** Without orders or steps for this long, it counts as idle. */
    private static final long IDLE_MS = 30_000;
    /** If someone is closer than this, it stays with that person. */
    private static final double ACCOMPANIED = 16.0;
    /** How far it looks for a known place to move to. */
    private static final double SEARCH = 64.0;

    private final Walker walker;
    private int ticks;
    /** So the "nowhere to go" notice is not repeated every five seconds. */
    private boolean alreadySaid;

    Routine(Walker walker) {
        this.walker = walker;
    }

    /** @return true if it started walking on its own */
    boolean tick() {
        if (!Preferences.is("night_routine")) return false;
        if (++ticks < EVERY) return false;
        ticks = 0;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isAlive()) return false;
        if (walker.walking()) return false;
        if (Activity.idleMs() < IDLE_MS) return false;
        if (mc.level.isDay()) { alreadySaid = false; return false; }

        // Anyone nearby? Then it is not the moment to go anywhere.
        for (Player other : mc.level.players()) {
            if (other != p && other.distanceTo(p) <= ACCOMPANIED) return false;
        }

        var where = p.blockPosition();
        if (mc.level.getBrightness(LightLayer.BLOCK, where) > 0) return false;
        if (!mc.level.canSeeSky(where)) return false;      // under a roof, fine

        Places.Place shelter = nearest(p);
        if (shelter == null) {
            if (!alreadySaid) {
                alreadySaid = true;
                Logbook.note("routine", "it is night and I am out in the "
                        + "open, but I have no spot noted "
                        + "nearby to move to");
            }
            return false;
        }

        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MarionetteBot.whereAmI(world, p);
        Route.Point goal = new Route.Point(shelter.where().getX(),
                shelter.where().getY(), shelter.where().getZ());
        Route.Result r = Route.search(world, here, Route.Meta.near(goal, 2.0),
                new Route.Options(MarionetteBot.safeFall(p.getHealth()),
                        8_000, true, true).withDeadline(30)
                        .breaking(Preferences.is("break_to_advance")));
        if (!r.hasRoute() || r.steps().size() <= 1) return false;
        if (walker.follow(r.steps(), x -> null) != null) return false;

        alreadySaid = false;
        Logbook.note("routine", String.format(
                "it is night and I am out in the open: moving to%s %s at "
                + "%d %d %d", shelter.label().isEmpty() ? "l" : "",
                shelter.label().isEmpty() ? shelter.type()
                        : "'" + shelter.label() + "'",
                goal.x(), goal.y(), goal.z()));
        Voice.say("routine", 300_000, Phrases.of("shelter",
                shelter.label().isEmpty() ? Phrases.of("known_spot")
                        : shelter.label()));
        return true;
    }

    /**
     * The nearest known place worth moving to: a bed above all, and otherwise anything it
     * remembers.
     */
    private static Places.Place nearest(LocalPlayer p) {
        String here = Places.currentDimension();
        Places.Place best = null;
        double mejorD = SEARCH * SEARCH;
        for (Places.Place l : Places.of("")) {
            if (!l.dimension().equals(here)) continue;
            if (l.type().equals("death")) continue;    // no shelter there
            double d = l.where().distToCenterSqr(p.position());
            boolean bestType = best != null
                    && !best.type().equals("bed") && l.type().equals("bed");
            if (d < mejorD || bestType) {
                if (d > SEARCH * SEARCH) continue;
                mejorD = Math.min(mejorD, d);
                best = l;
            }
        }
        return best;
    }
}

package masurium.bot;

import masurium.common.Logbook;
import masurium.common.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Following a player. On demand, until told to stop.
 *
 * <p>It is a continuous behaviour, like the {@link Guard}: it lives in the tick because
 * following is <b>reacting to the other one's movement</b>, and a round trip through the
 * brain takes seconds; by the time it answered, the player would be somewhere else. The
 * brain decides WHEN to start and when to stop; the feet go by themselves.
 *
 * <p>Three decisions that keep it from being a clingy puppy:
 * <ul>
 *   <li><b>Hysteresis</b>: it closes in to ~3 blocks and does not move again until the
 *       other one is more than 5 away. Without the gap between the two numbers it would
 *       dance on top of the player at every step.</li>
 *   <li><b>It replans with restraint</b>: at most one route per second, and only if the
 *       target really moved since the last plan.</li>
 *   <li><b>It does not build</b> unless the preference allows it: following does not
 *       grant permission to place blocks. If the player crosses where the bot cannot, it
 *       notes it and keeps trying; the other one may come back.</li>
 * </ul>
 *
 * <p>If it loses sight of the player (logged off, or left the loaded world) it waits ~10
 * seconds and gives up, saying so.
 */
final class Follower {

    /** At this distance it is fine; closer would be stepping on their heels. */
    private static final double NEAR = 3.0;
    /** And it does not move again until the other one goes past this. */
    private static final double FAR = 5.0;
    /** How much the target must move to justify another route. */
    private static final double MOVED = 2.0;
    /** Ticks between replans, at most one per second. */
    private static final int EVERY = 20;
    /** Ticks without seeing the player before giving up (~10 s). */
    private static final int PATIENCE = 200;

    private final Walker walker;

    private String target;
    private boolean following;
    private String outcome = "I am not following anyone";
    private int ticksWithoutSeeingIt;
    private int ticksSincePlan;
    private double blueprintX, blueprintZ;          // where the other one was when planning
    private int routeFailures;
    /** Where I last saw them: I tell the brain if I lose them. */
    private double seenX, seenY, seenZ;
    private boolean seenAny;
    /**
     * Whether losing sight of them notifies the brain. The escort sets false: it has its
     * own patience and its own notice (escort-default).
     */
    private boolean warnLoss = true;

    /**
     * A make-way request lasts this long (ticks); the boss renews it if the guard is
     * still in the way.
     */
    private static final int OBSTRUCTION_TICKS = 200;
    // Make-way request (see Guards): step away from the segment a-b to r blocks WITHOUT
    // dropping the escort, for a guard standing where its boss places a block or in the
    // line of its fishing rod.
    private net.minecraft.world.phys.Vec3 obstructionA, obstructionB;
    private double obstructionR;
    private int obstructionTicks, obstructionPlan;
    private boolean obstructionWarned;

    Follower(Walker walker) {
        this.walker = walker;
    }

    synchronized String begin(String player) {
        return begin(player, true);
    }

    /**
     * @param warnLoss whether losing sight of them notifies the brain (the escort passes
     *                 false: it notifies itself, with more patience)
     */
    synchronized String begin(String player, boolean warnLoss) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return "I am in no world";
        Player p = search(mc, player);
        if (p == null) {
            return String.format("I see nobody called '%s' around here", player);
        }
        target = p.getGameProfile().getName();   // the exact name, not the typed one
        following = true;
        outcome = null;
        ticksWithoutSeeingIt = 0;
        ticksSincePlan = EVERY;                     // plan right away, no waiting
        blueprintX = Double.NaN;
        routeFailures = 0;
        seenAny = false;
        this.warnLoss = warnLoss;
        Logbook.note("follow", "starting to follow " + target);
        return null;
    }

    synchronized void stop(String because) {
        if (following) {
            Logbook.note("follow", "I stop following: " + because);
        }
        following = false;
        outcome = because;
    }

    synchronized void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer me = mc.player;
        // Asleep, or on the way to bed, the feet belong to sleep: the escort stays on and
        // resumes on getting up.
        if (me != null && (me.isSleeping() || MasuriumBot.goingToSleep())) return;
        // The make-way request is handled WHETHER OR NOT it is following anyone (a
        // waiting guard is also in the way), and before the escort.
        if (me != null && mc.level != null && me.isAlive() && handleObstruction(mc, me)) return;
        if (!following) return;
        if (me == null || mc.level == null) { stop("I left the world"); return; }
        if (!me.isAlive()) { stop("I was killed while following you"); return; }

        Player him = search(mc, target);
        if (him == null) {
            if (++ticksWithoutSeeingIt > PATIENCE) {
                stop("I lost sight of you and you did not come back");
                // And the brain must know: giving up silently left a bot standing a
                // hundred blocks from the player it followed (who left by train) without
                // knowing it had lost them. The body only sees LOADED players (~140
                // blocks with view-distance 10); where they are NOW is known by the
                // server, and the brain asks that with `players`.
                if (warnLoss) warnLoss();
            }
            return;
        }
        ticksWithoutSeeingIt = 0;
        seenX = him.getX();
        seenY = him.getY();
        seenZ = him.getZ();
        seenAny = true;

        // HORIZONTAL distance: the vertical one is not closed by walking, and with the
        // player flying above, the 3D distance never dropped below "far": it kept
        // planning one-step routes to its own tile every second.
        double dx = me.getX() - him.getX(), dz = me.getZ() - him.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= NEAR) {
            if (walker.walking()) walker.stop("I am already with you");
            blueprintX = Double.NaN;                    // the next plan, from scratch
            // And look at you, since that is why it is with you. Standing still at your
            // side staring at the floor looked like a horror movie. The Guard runs later
            // in the tick, so in a fight its look at the mob wins over this one, as it
            // should. With the bow being drawn (or any item in use) the look belongs to
            // the shot, not to whoever is followed: looking at them mid-draw sent the
            // arrow into them.
            if (!me.isUsingItem()) me.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument
                    .Anchor.EYES, him.getEyePosition());
            return;
        }
        // Between NEAR and FAR, still if it already arrived: the hysteresis. But if it is
        // walking, let it finish the trip; cutting it here would leave it short.
        if (d <= FAR && !walker.walking()) return;

        if (++ticksSincePlan < EVERY) return;
        boolean moved = Double.isNaN(blueprintX)
                || Math.abs(him.getX() - blueprintX) + Math.abs(him.getZ() - blueprintZ) > MOVED;
        if (walker.walking() && !moved) return;
        ticksSincePlan = 0;

        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MasuriumBot.whereAmI(world, me);
        // Building to reach you is a PREFERENCE, not a right of the follower:
        // build_while_following governs it, toggled through the chat. And when building
        // UPWARDS the budget rises as in fill jobs: the heuristic does not see height and
        // a 17-block tower floods A* (see FillWorker); with 8,000 the "I can build" would
        // be a lie. And only if it CARRIES something to build with: planning bridges
        // without blocks sends the walker into a gap it cannot close, and it never gets
        // out ("I could not bridge" every five seconds for minutes).
        boolean build = Preferences.is("build_while_following")
                && Builder.hasScaffold(me);
        // The guard follows its boss DIGGING if needed: underground, in a mine, down a
        // dug staircase. A guard that stays wandering on the surface guards nothing. With
        // permissions, as always: the whitelist decides which blocks. And with more nodes
        // when there is a height difference, since the heuristic does not see height.
        boolean boss = MasuriumBot.isMyBoss(target);
        int heightDiff = Math.abs((int) Math.floor(him.getY()) - here.y());
        int nodes = (build && (int) Math.floor(him.getY()) > here.y() + 3)
                || (boss && heightDiff > 3) ? 40_000 : 8_000;
        // With partial routes: a distant player is followed in segments instead of giving
        // up. This replans by itself every second, so the next segment comes for free
        // from the loop that already exists.
        Route.Options op = new Route.Options(
                MasuriumBot.safeFall(me.getHealth()), nodes, build, true)
                .breaking(Preferences.is("break_to_advance"));

        // Where to aim: their walkable tile, or the ground BELOW them if they fly
        // (whereStanding looks 24 down). The goal is no longer an exact tile but "within
        // 2 of you": any tile of the ring will do and the path finder picks it. A 40 ms
        // deadline: this runs on the game thread.
        Route.Point ground = whereStanding(world, him.getX(), him.getY(), him.getZ());
        Route.Point aimTarget = ground != null ? ground : new Route.Point(
                (int) Math.floor(him.getX()), (int) Math.floor(him.getY()),
                (int) Math.floor(him.getZ()));
        Route.Result r = Route.search(world, here,
                Route.Meta.near(aimTarget, 2.0), op.withDeadline(40));
        // A PARTIAL route that does not get closer is no good: with the boss 46 blocks
        // below, the path finder returned "the closest it got" (the same column, above)
        // and the follower accepted it again and again, silently. If the end of the route
        // is neither within reach nor closer, it is a failure, and on the third one the
        // height difference is reported.
        boolean approaches = approaches(r, here, aimTarget);
        // Behind the boss, FIRST the way it went (its tunnel, its staircase) and dig only
        // if that gives no path: with cheap digging from the start the path finder went
        // straight through the rock and the guard opened a second tunnel parallel to its
        // boss's. The guard does NOT dig to follow: it opened huge tunnels. If it cannot
        // get there, on the third failure it notifies and its brain asks the boss for
        // directions.
        walker.breakingTheNext(false);
        if (r.hasRoute() && approaches
                && walker.follow(r.steps(), x -> null) == null) {
            routeFailures = 0;
            blueprintX = him.getX();
            blueprintZ = him.getZ();
            return;
        }
        // No drama and no spam: the first failure of each streak is noted and it keeps
        // trying; the other one may come back the way it can go.
        if (routeFailures++ == 0) {
            Logbook.note("follow", "I find no path towards "
                    + target + "; I keep trying");
        }
        // On the third, the brain must know and tell whoever it follows: staying silent
        // and trying is what looks like "stuck" from outside. Once per streak (Needs
        // rests by key).
        if (routeFailures == 3) {
            String because = r.hasRoute() ? "my route gets cut off" : r.reason();
            // The height difference, spelled out: a boss 46 blocks below (in the mine) is
            // not reached by clearing the way, it is reached by digging a staircase down
            // to its height, and the brain decides that.
            int dy = (int) Math.floor(him.getY()) - here.y();
            String whereIs = dy < -4
                    ? String.format(" and they are %d blocks BELOW me; I do not "
                            + "dig: ask them for directions through the internal channel (where to "
                            + "enter, their staircase, or where to wait for them)", -dy)
                    : dy > 4 ? String.format(" and they are %d blocks above me", dy)
                    : "";
            Needs.warn("follow:no_path", "I cannot get to "
                    + target + " from where I am (" + because + ")" + whereIs
                    + (Builder.hasScaffold(me) ? ""
                       : "; besides I carry no blocks to bridge or climb")
                    + ". Tell them which way to go or to come closer");
        }
    }

    /**
     * The loss notice. Its key carries the 32-block cell where I saw them: losing them
     * twice in the same place within ten minutes rests; losing them farther away (they
     * really left) counts as something else.
     */
    private void warnLoss() {
        String where = "", cell = "?";
        if (seenAny) {
            int x = (int) Math.floor(seenX), y = (int) Math.floor(seenY),
                    z = (int) Math.floor(seenZ);
            where = String.format(", I last saw them at %d %d %d", x, y, z);
            cell = (x >> 5) + "," + (z >> 5);
        }
        Needs.warn("follow:lost:" + target + ":" + cell,
                "I lost sight of " + target + " while following them" + where
                + " and stopped following: I only see loaded players (about 140 "
                + "blocks). Check with `players` where they are NOW: if within "
                + "reach, go with `go_to` and `follow_player` again; if very far "
                + "or still moving away, tell them and wait; if they do not show up, they "
                + "logged off");
    }

    /**
     * The walkable tile where the player is, or the ground BELOW them, up to 24 further
     * down. The 24 are no whim: a player flying in creative left the follower planted
     * silently every time they took off with a 3-block look. If you fly, I walk in your
     * shadow.
     */
    /**
     * I am asked to make way: step away from the segment a-b (a point if a = b) to r
     * blocks.
     */
    synchronized void stepAside(net.minecraft.world.phys.Vec3 a, net.minecraft.world.phys.Vec3 b, double r) {
        obstructionA = a;
        obstructionB = b;
        obstructionR = r;
        obstructionTicks = 0;
        obstructionPlan = EVERY;                        // plan right away
    }

    private static net.minecraft.world.phys.Vec3 nearestInSegment(
            net.minecraft.world.phys.Vec3 a, net.minecraft.world.phys.Vec3 b,
            net.minecraft.world.phys.Vec3 p) {
        net.minecraft.world.phys.Vec3 ab = b.subtract(a);
        double l2 = ab.lengthSqr();
        if (l2 < 1e-9) return a;
        double t = Math.max(0, Math.min(1, p.subtract(a).dot(ab) / l2));
        return a.add(ab.scale(t));
    }

    /** Distance from the segment a-b to a box (0 if it crosses it). */
    private static double distanceToBox(net.minecraft.world.phys.Vec3 a,
                                         net.minecraft.world.phys.Vec3 b,
                                         net.minecraft.world.phys.AABB box) {
        net.minecraft.world.phys.Vec3 q = nearestInSegment(a, b, box.getCenter());
        double dx = Math.max(0, Math.max(box.minX - q.x, q.x - box.maxX));
        double dy = Math.max(0, Math.max(box.minY - q.y, q.y - box.maxY));
        double dz = Math.max(0, Math.max(box.minZ - q.z, q.z - box.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Handles the make-way request. Outside the path, nothing (and the escort goes on:
     * 2-3 blocks from the boss, the hysteresis does not bring it back to the same spot);
     * inside, a destination perpendicular to the path, on the side it is already on,
     * trying angles and distances until finding a walkable tile with a route.
     *
     * @return true if the tick went to stepping aside
     */
    private boolean handleObstruction(Minecraft mc, LocalPlayer me) {
        if (obstructionA == null) return false;
        if (++obstructionTicks > OBSTRUCTION_TICKS) {
            obstructionA = null;
            obstructionWarned = false;
            return false;
        }
        net.minecraft.world.phys.AABB box = me.getBoundingBox();
        if (distanceToBox(obstructionA, obstructionB, box) >= obstructionR) return false;
        if (!obstructionWarned) {
            Logbook.note("follow", "I am in the way; I step aside without dropping the escort");
            obstructionWarned = true;
        }
        if (++obstructionPlan < EVERY) return true;     // already on my way
        obstructionPlan = 0;
        net.minecraft.world.phys.Vec3 center = box.getCenter();
        net.minecraft.world.phys.Vec3 near = nearestInSegment(obstructionA, obstructionB, center);
        net.minecraft.world.phys.Vec3 outside = new net.minecraft.world.phys.Vec3(
                center.x - near.x, 0, center.z - near.z);
        if (outside.lengthSqr() < 1e-4) {
            net.minecraft.world.phys.Vec3 ab = obstructionB.subtract(obstructionA);
            outside = Math.abs(ab.x) + Math.abs(ab.z) < 1e-4
                    ? new net.minecraft.world.phys.Vec3(1, 0, 0)
                    : new net.minecraft.world.phys.Vec3(-ab.z, 0, ab.x);
        }
        outside = outside.normalize();
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MasuriumBot.whereAmI(world, me);
        java.util.List<Route.Point> goals = new java.util.ArrayList<>();
        double[] angles = {0, 45, -45, 90, -90, 135, -135, 180};
        for (double k = obstructionR + 1; k <= obstructionR + 3; k += 1) {
            for (double angleDeg : angles) {
                double rad = Math.toRadians(angleDeg);
                double fx = outside.x * Math.cos(rad) - outside.z * Math.sin(rad);
                double fz = outside.x * Math.sin(rad) + outside.z * Math.cos(rad);
                Route.Point m = whereStanding(world, near.x + fx * k, me.getY() + 1, near.z + fz * k);
                if (m == null || goals.contains(m)) continue;
                // Make sure it really ends up out of the path, not farther along the same
                // axis.
                net.minecraft.world.phys.AABB there = new net.minecraft.world.phys.AABB(
                        m.x() + 0.2, m.y(), m.z() + 0.2, m.x() + 0.8, m.y() + 1.8, m.z() + 0.8);
                if (distanceToBox(obstructionA, obstructionB, there) < obstructionR + 0.5) continue;
                goals.add(m);
            }
        }
        for (Route.Point m : goals) {
            Route.Result res = Route.search(world, here, m, new Route.Options(
                    MasuriumBot.safeFall(me.getHealth()), 800, false));
            if (!res.hasRoute()) continue;
            walker.breakingTheNext(false);
            if (walker.follow(res.steps(), x -> null) == null) return true;
        }
        Logbook.note("follow", "I am asked to make way and find nowhere to step aside");
        return true;
    }

    /**
     * Whether the route arrives (within 4) or at least gets 2 blocks closer to the
     * target.
     */
    private static boolean approaches(Route.Result r, Route.Point here, Route.Point aimTarget) {
        if (!r.hasRoute() || r.steps().isEmpty()) return false;
        Route.Point end = r.steps().get(r.steps().size() - 1);
        double dEnd = Math.sqrt(Math.pow(end.x() - aimTarget.x(), 2)
                + Math.pow(end.y() - aimTarget.y(), 2) + Math.pow(end.z() - aimTarget.z(), 2));
        double dHere = Math.sqrt(Math.pow(here.x() - aimTarget.x(), 2)
                + Math.pow(here.y() - aimTarget.y(), 2) + Math.pow(here.z() - aimTarget.z(), 2));
        return dEnd <= 4.0 || dEnd < dHere - 2.0;
    }

    private static Route.Point whereStanding(ClientWorld m,
                                        double ex, double ey, double ez) {
        int x = (int) Math.floor(ex);
        int z = (int) Math.floor(ez);
        int y0 = (int) Math.floor(ey);
        for (int dy = 1; dy >= -24; dy--) {
            if (m.canStand(x, y0 + dy, z)) {
                return new Route.Point(x, y0 + dy, z);
            }
        }
        return null;
    }

    private static Player search(Minecraft mc, String name) {
        for (Player p : mc.level.players()) {
            if (p != mc.player
                    && p.getGameProfile().getName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    synchronized String state() {
        if (!following) {
            return String.format("{\"following\":false,\"outcome\":\"%s\"}",
                    masurium.common.Request.escape(outcome));
        }
        return String.format("{\"following\":true,\"to\":\"%s\"}",
                masurium.common.Request.escape(target));
    }

    synchronized boolean following() {
        return following;
    }
}

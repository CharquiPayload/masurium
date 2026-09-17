package marionette.bot;

import marionette.common.Logbook;
import marionette.common.Request;
import marionette.common.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Traveling far: reaching a point that a single search cannot.
 *
 * <p>It was born from a "come here" 84 blocks away and 13 up: /go_to searched for ONE
 * whole route, 60,000 tiles were not enough, and the answer was "I find no route", a
 * half-truth, because walking half the way and searching again did find a path. That is
 * exactly what this does: /go_to plans the first segment (with {@link Route}'s partial
 * routes) and leaves the destination here; every time the walker finishes a segment
 * without arriving, the next one is searched from where it stopped. Arriving, getting
 * really stuck or another behaviour claiming the feet are the three exits, and all three
 * speak.
 *
 * <p>It does not plan the first segment on purpose: /go_to's courtesies (landing the
 * destination, trying neighbours, the rescue from a hole) already validate the trip
 * before handing it over. Here it only continues.
 */
final class Traveler {

    /**
     * Budget of each segment. It used to be 40 ms and 20,000 tiles: with a mountain in
     * between they were burned exploring and the segment died on the ridge. Baritone
     * gives 500 ms and up to 2 s, but it searches on a separate thread; we search ON the
     * game thread, and 120 ms is two and a half ticks of freeze per segment, every
     * several seconds, barely noticeable. More than this requires moving the search off
     * the thread. The tiles go up accordingly so the clock is what cuts.
     */
    private static final long SEGMENT_DEADLINE_MS = 120;
    private static final int SEGMENT_NODES = 40_000;

    /** Ticks between checks: the walker does the fine work. */
    private static final int EVERY = 10;
    /**
     * At this horizontal distance from the destination, I arrived. A hair more than the
     * walker's arrival (1.4), so as not to argue with it.
     */
    private static final double ARRIVED = 1.6;
    /** Accumulated ticks without getting a segment before giving up with a reason. */
    private static final int STUCK_PATIENCE = 100;
    /**
     * Crossing a portal takes about 80 ticks standing inside. With 400 (twenty seconds)
     * there is plenty of margin; if nothing happened, something is wrong: the portal is
     * off, or the bot is next to it and not inside.
     */
    private static final int PORTAL_PATIENCE = 400;
    /**
     * The portal is reached EXACTLY, at the center of the tile. The usual arrival (1.4)
     * left the bot on the frame, "next to the portal but unable to get in": 1.4 from a
     * block's center is the neighbouring block.
     */
    private static final double EXACT = 0.3;
    /**
     * Closer than this to the portal, if no route comes out (the start and end tiles
     * almost overlap) it takes a straight step without searching.
     */
    private static final double LAST_STEP = 3.0;
    /** Attempts to get in while already next to it, before giving up and saying so. */
    private static final int NEAR_ATTEMPTS = 6;
    /**
     * On coming out the other side, ticks spent looking for the portal back: the new
     * world's chunks arrive from the server a while after appearing, and looking just
     * once (as it used to) found nothing.
     */
    private static final int RETURN_PATIENCE = 200;
    /**
     * With no portal noted in this dimension, one in sight is looked for before giving
     * up: crossing leaves you right next to a portal.
     */
    private static final int VIEW_RADIUS = 32;
    /** Length of a straight swimming segment, and how often a point goes in. */
    private static final int SWIM_SEGMENT = 48;
    private static final int SWIM_STEP = 4;
    /**
     * From the shore, how far towards the destination it looks to get back into the water
     * when the search finds no path over land.
     */
    private static final int TO_WATER = 12;
    private static final int VIEW_HEIGHT = 12;

    private final Walker walker;

    private Route.Point destination;
    /** The dimension where the destination lives. Empty = this one. */
    private String destinationDimension = "";
    /** The portal it is heading for while it has to cross, or null. */
    private Route.Point portal;
    /** Ticks standing still inside the portal, waiting for the trip. */
    private int portalTicks;
    private int nearAttempts;
    /** Ticks left looking for the portal back after crossing. */
    private int searchingWayBack;
    private boolean build;
    private boolean traveling;
    private String outcome = "I am not travelling";
    private int ticksSincePlan;
    private int stuckTicks;
    /**
     * The smallest distance to the destination a segment ended with, and how many
     * segments in a row have ended without improving it.
     */
    private double bestDist;
    private int segmentsNoProgress;

    Traveler(Walker walker) {
        this.walker = walker;
    }

    /**
     * /go_to already validated it and started the first segment; this carries on to the
     * end.
     */
    synchronized void begin(Route.Point destination, boolean build) {
        begin(destination, "", build);
    }

    /**
     * The same, but with the destination in ANOTHER dimension.
     *
     * <p>It can go to Nether coordinates from the overworld through the nearest Nether
     * portal (and vice versa). The trip is split in three: go to the nearest noted portal
     * in THIS dimension, stay inside until the world changes, and continue on foot to the
     * coordinates on the other side.
     *
     * <p>The destination is not validated here, and it cannot be: that piece of world
     * does not exist in the world it is in now. It is validated on arrival, like any
     * other segment.
     */
    synchronized void begin(Route.Point destination, String dimension,
                              boolean build) {
        this.destinationDimension = dimension == null ? "" : dimension.trim();
        this.portal = null;
        this.portalTicks = 0;
        this.nearAttempts = 0;
        this.searchingWayBack = 0;
        this.destination = destination;
        this.build = build;
        this.traveling = true;
        this.outcome = null;
        this.ticksSincePlan = 0;
        this.stuckTicks = 0;
        this.bestDist = Double.MAX_VALUE;
        this.segmentsNoProgress = 0;
    }

    synchronized void stop(String because) {
        if (traveling) {
            Logbook.note("travel", "I drop the trip: " + because);
        }
        traveling = false;
        outcome = because;
        // And the LEGS. Without this, "stop" only stopped ordering new segments: the
        // current segment was finished anyway, because this same tick waits for the
        // Walker. A bot saw the cow it was looking for 29 blocks away, notified,
        // considered the search over and kept walking eighty blocks to the end of the
        // segment. Whoever says "stop" wants it to stop where it is.
        if (walker.walking()) walker.stop(because);
    }

    synchronized void tick() {
        if (!traveling) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { stop("I left the world"); return; }
        if (!p.isAlive()) { stop("I was killed while travelling"); return; }
        if (++ticksSincePlan < EVERY) return;
        ticksSincePlan = 0;

        // Is the destination in another world? Then the destination NOW is the portal,
        // and the rest will be seen on the other side.
        if (!destinationDimension.isEmpty()) {
            if (!Places.currentDimension().equals(destinationDimension)) {
                cross(mc, p);
                return;
            }
            // The world changed: it is on the other side. The route it carried belongs to
            // ANOTHER world and is no good; and the portal it came out through (the way
            // back) is noted as soon as the new world can be seen.
            if (walker.walking()) walker.stop("I crossed the portal");
            destinationDimension = "";
            portal = null;
            searchingWayBack = RETURN_PATIENCE;
            Logbook.note("travel", "I crossed; carrying on to "
                    + destination.x() + " " + destination.y() + " " + destination.z());
        }
        if (searchingWayBack > 0) {
            if (noteThePortalHere(mc, p)) searchingWayBack = 0;
            else searchingWayBack -= EVERY;
        }

        if (walker.walking()) return;       // the current segment goes on

        double dx = destination.x() + 0.5 - p.getX();
        double dz = destination.z() + 0.5 - p.getZ();
        if (dx * dx + dz * dz <= ARRIVED * ARRIVED
                && Math.abs(destination.y() - p.getY()) <= 2) {
            Logbook.note("travel", String.format("I arrived at %d %d %d",
                    destination.x(), destination.y(), destination.z()));
            stop("arrived");
            return;
        }

        // Segment finished without arriving. If segments keep ending without getting
        // closer (water with no blocks to bridge with, eight "I could not bridge" per
        // second forever), it stops SAYING why with the walker's reason instead of
        // retrying blindly. Three segments without gaining a block is being stuck, not
        // bad luck. In 3D: it used to be measured only in XZ, but arriving requires |dy|
        // <= 2 (above). Climbing 20 blocks towards the destination was "no progress", and
        // after three segments it gave up: "I got stuck" with high destinations.
        double dyv = destination.y() - p.getY();
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        double dist = Math.sqrt(dx * dx + dyv * dyv + dz * dz);
        if (dist < bestDist - 1.0) {
            bestDist = dist;
            segmentsNoProgress = 0;
        } else if (++segmentsNoProgress >= 3) {
            stop(String.format("I got stuck %d blocks from %d %d %d: %s",
                    (int) dist, destination.x(), destination.y(), destination.z(),
                    walker.outcome()));
            return;
        }
        // On the open sea and far away, swim in a straight line first: the path search
        // does not reach (see swimSegment). Close to the coast the search rules, and
        // swimming is plan B.
        if (p.isInWater() && distXZ > SWIM_SEGMENT && swimSegment(mc, p)) {
            stuckTicks = 0;
            return;
        }
        // The next segment, from where it stopped.
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MarionetteBot.whereAmI(world, p);
        // Without scaffolding no towers or bridges are planned: promising them ends in "I
        // could not build a tower: I carry no blocks" in front of the first slope, and
        // there it stays with the stuck icon. In exchange it is allowed to dig, which is
        // how a mound is passed without placing a single block, like Baritone: dig, or
        // find a route where building is not needed.
        boolean withWhat = Builder.hasScaffold(p);
        Route.Options op = new Route.Options(
                MarionetteBot.safeFall(p.getHealth()), SEGMENT_NODES,
                build && withWhat, true)
                .withDeadline(SEGMENT_DEADLINE_MS)
                .breaking(Preferences.is("break_to_advance") || !withWhat);
        // First the COLUMN (height does not matter) and only if there is no way to get
        // there like that, the exact tile, which is the one that forces building upwards:
        // first X,Z, and only without another route, the Y.
        // It comes from a bot building a bridge in the middle of a peak: the player it
        // was going to gave a position with y=116 because they stood on top of a hill,
        // and reaching THAT tile required building the path 37 blocks up. With the column
        // as the goal it stands below and walks up if there is a way, which was what was
        // wanted.
        Route.Result r = Route.search(world, here,
                Route.Meta.onlyXZ(destination.x(), destination.z()), op);
        if (!r.hasRoute()) {
            r = Route.search(world, here, destination, op);
        }
        if (r.hasRoute() && walker.follow(r.steps(),
                x -> null) == null) {
            stuckTicks = 0;
            if (r.isPartial()) {
                Logbook.note("travel", "another segment: " + r.reason());
            }
            return;
        }
        // Plan B from land too: if the search does not get off this shore (an island, a
        // point) and there is water towards the destination, it gets back into the water
        // and continues swimming. The direction is always the destination's; what can
        // happen is running into land in between, and this is what crosses it.
        if (swimSegment(mc, p)) {
            stuckTicks = 0;
            return;
        }
        // No usable segment. As in chases: a few seconds of grace (the world changes,
        // chunks load) and then give up SAYING so, never stand still silently.
        stuckTicks += EVERY;
        if (stuckTicks >= STUCK_PATIENCE) {
            stop("I found no way to continue to "
                    + destination.x() + " " + destination.y() + " " + destination.z()
                    + ": " + r.reason());
        }
    }

    /**
     * Go to the nearest portal and stay INSIDE until the world changes. Waiting is
     * literally all there is to do: the game takes about four seconds to take away
     * someone standing inside.
     *
     * <p>"Inside" is decided by the block it occupies, not the distance. The first
     * version counted as inside being 1.6 from the noted portal, and at 1.6 one is on the
     * frame: twenty seconds still on the obsidian and "it took me nowhere".
     */
    private void cross(Minecraft mc, LocalPlayer p) {
        if (portal == null) {
            Route.Point noted = nearestPortal(p);
            String origin = "noted";
            if (noted == null) {
                noted = portalInSight(mc, p);
                origin = "that I see";
            }
            if (noted == null) {
                stop("I have no portal noted in "
                        + Places.currentDimension() + " and I see none within "
                        + VIEW_RADIUS + " blocks; take me to one and I note it, "
                        + "or tell me at which coordinates it is");
                return;
            }
            portal = portalTile(mc, p, noted);
            Logbook.note("travel", String.format(
                    "going to the %s portal at %d %d %d to cross to %s", origin,
                    portal.x(), portal.y(), portal.z(), destinationDimension));
        }
        if (insidePortal(mc, p)) {
            if (walker.walking()) walker.stop("I am already inside the portal");
            if (++portalTicks > PORTAL_PATIENCE) {
                stop(String.format("I spent %d seconds inside the portal at "
                        + "%d %d %d and it took me nowhere; it may not "
                        + "be lit or may no longer exist",
                        PORTAL_PATIENCE / 20,
                        portal.x(), portal.y(), portal.z()));
            }
            return;
        }
        portalTicks = 0;
        if (walker.walking()) return;

        double dx = portal.x() + 0.5 - p.getX();
        double dz = portal.z() + 0.5 - p.getZ();
        boolean beside = dx * dx + dz * dz <= LAST_STEP * LAST_STEP
                && Math.abs(portal.y() - p.getY()) <= 2;
        if (beside && ++nearAttempts > NEAR_ATTEMPTS) {
            stop(String.format("I am next to the portal at %d %d %d and "
                    + "after %d attempts I still cannot get in; is it blocked?",
                    portal.x(), portal.y(), portal.z(), NEAR_ATTEMPTS));
            return;
        }
        boolean iGo = segmentToward(mc, p, portal, EXACT)
                || (beside && straightStep(mc, p, portal));
        if (iGo) {
            stuckTicks = 0;
            return;
        }
        stuckTicks += EVERY;
        if (stuckTicks >= STUCK_PATIENCE) {
            stop(String.format("I found no way to reach the portal at "
                    + "%d %d %d", portal.x(), portal.y(), portal.z()));
        }
    }

    // --- portals: where they are and how they are walked into ---------------

    static boolean isPortal(BlockState s) {
        return s.is(Blocks.NETHER_PORTAL) || s.is(Blocks.END_PORTAL)
                || s.is(Blocks.END_GATEWAY);
    }

    /** Whether it occupies a portal block with its feet or head. */
    private static boolean insidePortal(Minecraft mc, LocalPlayer p) {
        BlockPos feet = p.blockPosition();
        return isPortal(mc.level.getBlockState(feet))
                || isPortal(mc.level.getBlockState(feet.above()));
    }

    /**
     * The portal block of the BOTTOM ROW nearest to a point, or null if none is loaded
     * within that radius. The top row is also nether_portal, but there is no route to
     * that tile (it has no floor): noting the portal by any of its blocks left the bot on
     * the frame, "stuck on the edge".
     */
    static BlockPos portalNear(Minecraft mc, BlockPos center, int radius) {
        if (mc.level == null || !mc.level.hasChunkAt(center)) return null;
        BlockPos best = null;
        double mejorD = Double.MAX_VALUE;
        for (BlockPos b : BlockPos.betweenClosed(
                center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            if (!isPortal(mc.level.getBlockState(b))) continue;
            if (isPortal(mc.level.getBlockState(b.below()))) continue;
            double d = b.distSqr(center);
            if (d < mejorD) { mejorD = d; best = b.immutable(); }
        }
        return best;
    }

    /**
     * What gets noted as a portal: its walkable tile if visible, otherwise what was said.
     * /places also uses it when remembering one.
     */
    static BlockPos adjustPortal(Minecraft mc, BlockPos where) {
        BlockPos b = portalNear(mc, where, 3);
        return b == null ? where : b;
    }

    /**
     * The tile of the noted portal where one can STAND, the nearest to the bot. If the
     * chunk is not loaded, the noted position as is.
     */
    private static Route.Point portalTile(Minecraft mc, LocalPlayer p,
                                               Route.Point noted) {
        BlockPos base = new BlockPos(noted.x(), noted.y(), noted.z());
        if (!mc.level.hasChunkAt(base)) return noted;
        ClientWorld world = new ClientWorld(mc.level);
        BlockPos best = null;
        double mejorD = Double.MAX_VALUE;
        for (BlockPos b : BlockPos.betweenClosed(base.offset(-3, -3, -3),
                                                 base.offset(3, 3, 3))) {
            if (!isPortal(mc.level.getBlockState(b))) continue;
            if (!world.canStand(b.getX(), b.getY(), b.getZ())) continue;
            double d = b.distToCenterSqr(p.position());
            if (d < mejorD) { mejorD = d; best = b.immutable(); }
        }
        return best == null ? noted
                : new Route.Point(best.getX(), best.getY(), best.getZ());
    }

    /** The nearest noted portal in THIS dimension, or null. */
    private static Route.Point nearestPortal(LocalPlayer p) {
        String here = Places.currentDimension();
        Route.Point best = null;
        double mejorD = Double.MAX_VALUE;
        for (Places.Place l : Places.of("portal")) {
            if (!l.dimension().equals(here)) continue;
            double d = l.where().distToCenterSqr(p.position());
            if (d < mejorD) {
                mejorD = d;
                best = new Route.Point(l.where().getX(), l.where().getY(),
                        l.where().getZ());
            }
        }
        return best;
    }

    /**
     * Nothing noted: the nearest portal in SIGHT, through the chunks the client has
     * loaded. A sweep, not something per tick: done once per crossing. If one shows up,
     * it gets noted, since that is what it was searched for.
     */
    private static Route.Point portalInSight(Minecraft mc, LocalPlayer p) {
        BlockPos me = p.blockPosition();
        BlockPos best = null;
        double mejorD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos b = new BlockPos.MutableBlockPos();
        for (int dx = -VIEW_RADIUS; dx <= VIEW_RADIUS; dx++) {
            for (int dz = -VIEW_RADIUS; dz <= VIEW_RADIUS; dz++) {
                if (!mc.level.hasChunkAt(me.offset(dx, 0, dz))) continue;
                for (int dy = -VIEW_HEIGHT; dy <= VIEW_HEIGHT; dy++) {
                    b.set(me.getX() + dx, me.getY() + dy, me.getZ() + dz);
                    if (!isPortal(mc.level.getBlockState(b))) continue;
                    if (isPortal(mc.level.getBlockState(b.below()))) continue;
                    double d = b.distSqr(me);
                    if (d < mejorD) { mejorD = d; best = b.immutable(); }
                }
            }
        }
        if (best == null) return null;
        if (Places.remember("portal", best)) {
            Logbook.note("places", String.format(
                    "I remember the portal at %d %d %d (I saw it searching)",
                    best.getX(), best.getY(), best.getZ()));
        }
        return new Route.Point(best.getX(), best.getY(), best.getZ());
    }

    /**
     * Notes the portal it just came out of: it is the way back, and nobody knew it yet;
     * crossing may make the game BUILD a new one on the other side. Without this, the way
     * back would be on foot.
     *
     * @return true if it saw it (noted or already known); false if it cannot be seen yet
     */
    private static boolean noteThePortalHere(Minecraft mc, LocalPlayer p) {
        BlockPos b = portalNear(mc, p.blockPosition(), 4);
        if (b == null) return false;
        if (Places.remember("portal", b)) {
            Logbook.note("places", String.format(
                    "I remember the portal at %d %d %d (where I came out)",
                    b.getX(), b.getY(), b.getZ()));
        }
        return true;
    }

    /**
     * A segment towards wherever, with the usual rules. @param near how close to the
     * destination counts as arrived @return true if it got a path and is already walking
     */
    private boolean segmentToward(Minecraft mc, LocalPlayer p, Route.Point goal,
                               double near) {
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MarionetteBot.whereAmI(world, p);
        boolean withWhat = Builder.hasScaffold(p);   // see the segment above
        Route.Result r = Route.search(world, here, goal, new Route.Options(
                MarionetteBot.safeFall(p.getHealth()), SEGMENT_NODES,
                build && withWhat, true)
                .withDeadline(SEGMENT_DEADLINE_MS)
                .breaking(Preferences.is("break_to_advance") || !withWhat));
        return r.hasRoute() && walker.follow(r.steps(), x -> null, near) == null;
    }

    /**
     * Crosses open water swimming, in a straight line along the surface, WITHOUT
     * searching for a route.
     *
     * <p>The path finder has 120 ms per segment. On land that is plenty; at sea, where
     * every tile costs the same, it spreads like a stain: at 40 ms it looked at about
     * 7,000 tiles, some 70 blocks of reach, and with three times the time it gets a
     * little past a hundred. A bot stayed 400 blocks from its target dying in every
     * segment with "I gave up after looking at 7169 tiles". It could swim; it could not
     * plan the crossing. The idea: think of water as a continuous flat block and just
     * move forward keeping room to float. That is what this does: here water is a plane,
     * and nothing is searched.
     *
     * <p>Points go every {@link #SWIM_STEP} blocks towards the destination, at the height
     * of each column's surface (the Walker holds the jump key in water, so it floats). As
     * soon as a column stops being water (coast, ice, a boat) the segment is cut there:
     * from the shore on, the path search rules again. Water is never dug or bridged from
     * here.
     *
     * @return true if it started a swimming segment
     */
    private boolean swimSegment(Minecraft mc, LocalPlayer p) {
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MarionetteBot.whereAmI(world, p);
        double dx = destination.x() + 0.5 - p.getX();
        double dz = destination.z() + 0.5 - p.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < SWIM_STEP) return false;
        dx /= dist;
        dz /= dist;
        List<Route.Point> points = new java.util.ArrayList<>();
        points.add(here);
        int from = SWIM_STEP;
        if (!world.water(here.x(), here.y(), here.z())) {
            // On land: only if the water is right ahead, towards the destination. It
            // walks to the shore (falling into water is free) and from there the segment
            // continues swimming. With no water in sight this is not its job: let the
            // path search say so.
            int shore = -1;
            for (int d = 1; d <= TO_WATER; d++) {
                int x = (int) Math.floor(p.getX() + dx * d);
                int z = (int) Math.floor(p.getZ() + dz * d);
                if (surface(world, x, here.y(), z) != Integer.MIN_VALUE) {
                    shore = d;
                    break;
                }
            }
            if (shore < 0) return false;
            from = shore;
        }
        int length = (int) Math.min(SWIM_SEGMENT, dist);
        for (int d = from; d <= length; d += SWIM_STEP) {
            int x = (int) Math.floor(p.getX() + dx * d);
            int z = (int) Math.floor(p.getZ() + dz * d);
            int y = surface(world, x, here.y(), z);
            if (y == Integer.MIN_VALUE) break;   // the water ended: coast
            points.add(new Route.Point(x, y, z));
        }
        if (points.size() < 2) return false;
        if (walker.follow(points, x -> null) != null) return false;
        Route.Point end = points.get(points.size() - 1);
        Logbook.note("travel", String.format(
                "swimming in a straight line to %d %d %d (%d points, %d to go)",
                end.x(), end.y(), end.z(), points.size() - 1, (int) dist));
        return true;
    }

    /**
     * The highest water tile of the column, starting to look at the bot's height;
     * MIN_VALUE if that column has no water at that height (nor a couple of blocks up or
     * down: there are no waves, but there are coasts).
     */
    private static int surface(ClientWorld world, int x, int y0, int z) {
        for (int dy = 0; dy <= 2; dy++) {
            for (int y : new int[]{y0 + dy, y0 - dy}) {
                if (world.water(x, y, z)) {
                    while (world.water(x, y + 1, z)) y++;
                    return y;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Starts the trip already swimming, when /go_to found no walking route while in
     * water. False if swimming is not possible either (no water towards the destination),
     * and then /go_to says there is no route.
     */
    synchronized boolean beginSwimming(Route.Point destination, boolean build) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isInWater()) return false;
        begin(destination, "", build);
        if (swimSegment(mc, p)) return true;
        traveling = false;
        return false;
    }

    /**
     * From the frame to the portal's center without searching: two points, exact. Only
     * from right next to it, when the search finds no path because the start and end
     * tiles almost overlap.
     */
    private boolean straightStep(Minecraft mc, LocalPlayer p, Route.Point goal) {
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MarionetteBot.whereAmI(world, p);
        return walker.follow(List.of(here, goal), x -> null, EXACT) == null;
    }

    /** Whether it is still on its way. The Explorer checks it to know when it arrived. */
    synchronized boolean traveling() {
        return traveling;
    }

    /**
     * HOW the last trip ended: "arrived", or the reason it did not. Stopping is not
     * arriving, and whoever waits for the end needs to tell them apart: without this, the
     * Explorer counted as explored a trip that never even started.
     */
    synchronized String outcome() {
        return outcome;
    }

    synchronized String state() {
        if (!traveling) {
            return String.format("{\"traveling\":false,\"outcome\":\"%s\"}",
                    Request.escape(outcome));
        }
        LocalPlayer p = Minecraft.getInstance().player;
        int missingCount = p == null ? -1 : (int) Math.hypot(
                destination.x() + 0.5 - p.getX(), destination.z() + 0.5 - p.getZ());
        return String.format(
                "{\"traveling\":true,\"destination\":{\"x\":%d,\"y\":%d,\"z\":%d},"
                + "\"missing\":%d%s%s}",
                destination.x(), destination.y(), destination.z(), missingCount,
                destinationDimension.isEmpty() ? ""
                        : ",\"crossing_to\":\"" + destinationDimension + "\"",
                portal == null ? "" : String.format(
                        ",\"portal\":{\"x\":%d,\"y\":%d,\"z\":%d}",
                        portal.x(), portal.y(), portal.z()));
    }
}

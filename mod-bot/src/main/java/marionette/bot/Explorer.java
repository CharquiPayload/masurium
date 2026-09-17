package marionette.bot;

import marionette.common.Fan;
import marionette.common.Request;
import marionette.common.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Going out to see what is there, and coming back.
 *
 * <p>A command of its own, useful by itself and as what the bot can offer to do when it
 * has had nothing to do for a while.
 *
 * <p>An expedition is there AND back. Leaving the bot planted three hundred blocks away
 * would be "exploring" on paper and a problem in the world, so the starting point is
 * saved on leaving and it returns there at the end. Whoever sends it does not have to
 * remember to bring it back.
 *
 * <p>The feet are the {@link Traveler}'s, which already knows how to make a long trip in
 * segments; here it only decides where to go, looks at what is there on arrival and
 * writes it in the {@link Diary}, which is what turns a walk into a memory.
 *
 * <p><b>Looking for a material along the way.</b> It used to be a blind walk: it went,
 * looked at the biome of the EXACT spot where it ended, and came back. Asked for cactus,
 * it went 250 blocks south, reached a steppe, noted it and came back without having
 * looked at a single block. It did not give up: the tool did not know how to search. With
 * {@code searching} it sweeps the surface around every {@value #SWEEP_STEP} blocks
 * walked, and as soon as it shows up it stops, notes it as a place and NOTIFIES the brain
 * with the coordinates: finding it is the errand, and staying quiet next to it would be
 * the usual failure.
 *
 * <p><b>And in several directions.</b> A single straight line is a bet: if the desert was
 * north and it went south, it comes back empty-handed and looks like it gave up. With
 * {@code branches} several are tried, turning around the starting point.
 *
 * <p>The route is a <b>spiral</b>, not a flower: when one direction is done it goes
 * STRAIGHT to the next one and a bit FARTHER, without passing through the center. The
 * first version went back to the origin between branches: "look in another direction, do
 * not give up", and "why does the bot come back, why does it not keep going?". Going back
 * was also the worst possible path: it repeats what was already seen and, with four
 * directions and radius 300, it is 600 blocks between branches against the ~450 from one
 * end to the next, which also cross new ground. Each segment goes {@value #GROWS} percent
 * farther than the previous one, so the search widens instead of circling the same ring;
 * the cap is {@value #FAR_MAX} blocks from home, which is already half the world. It
 * returns to the origin ONCE, at the end, because leaving it planted a thousand blocks
 * away is still not acceptable.
 *
 * <p>It also searches by BIOME, which for a scattered material is the only sensible
 * thing: a cactus cannot be seen thirty blocks away, but a desert spans hundreds and is
 * recognized as soon as the bot steps into it.
 */
final class Explorer {

    /** What it walks by default if nobody says how much. */
    private static final int DEFAULT_RADIUS = 150;
    /**
     * How close to the point counts as arrived. Generous on purpose: a long trip ends
     * where the terrain allows, not on the exact tile, and this only tells "I arrived"
     * from "I did not move".
     */
    private static final double ARRIVED = 6.0;

    /** And the cap of a single segment. */
    private static final int RADIUS_MAX = 2000;

    /** Radius of the sweep looking for the material, in blocks. */
    private static final int EYE = 32;
    /** How many blocks walked between sweeps. */
    private static final double SWEEP_STEP = 24.0;
    /**
     * Band around the surface where it looks: what is buried cannot be seen from outside,
     * and digging down for it is not exploring.
     */
    private static final int LOW = 3, TALL = 6;
    /**
     * Height of the box when looking for creatures: animals walk on the ground, and
     * looking higher and lower only brings cave bats.
     */
    private static final int CREATURE_HEIGHT = 8;

    /**
     * Cap on segments. With a growth of {@value #GROWS} percent, sixteen go beyond
     * {@value #FAR_MAX} from a small radius: that is what it takes for the spiral to
     * really reach the cap.
     */
    private static final int BRANCHES_MAX = 16;
    /** How many directions by default when looking for something and nobody says. */
    private static final int SEARCH_BRANCHES = 4;
    /** How much farther each segment goes than the previous one, in percent. */
    private static final int GROWS = 35;
    /**
     * And how far from the origin it may get, whatever happens.
     *
     * <p>Ten thousand, as practically "no limit". It is not free, and the number says so:
     * ten thousand blocks are hours of walking one way, the way back is as much, and if
     * it gets killed on the way it drops very far from home. There is still a cap because
     * a real "no limit" would be walking to the edge of the world with nobody stopping
     * it.
     */
    private static final int FAR_MAX = 10_000;

    private final Traveler traveler;

    private Route.Point origin;
    private Route.Point destination;
    private String toward;
    private boolean exploring;
    private boolean goingBack;
    private String outcome = "I have not gone out exploring";
    /** What it looks for along the way, or null if it is just a walk. */
    /**
     * Blocks looked for. A SET and not a single one: with a single id, "animals to eat"
     * forced the brain to bet on one and the bot walked past pigs looking for a cow.
     */
    private final Set<Block> searching = new LinkedHashSet<>();
    /**
     * Creatures looked for: without this, "go look for cows" was a walk without eyes and
     * it walked right past them.
     */
    private final Set<EntityType<?>> searchingCreature = new LinkedHashSet<>();
    private String soughtName;
    /** Where the last sweep was made, so it is not repeated every tick. */
    private double sweepX, sweepZ;
    private boolean hasSweep;
    /** The biome looked for (a piece of its name, in English), or null. */
    private String biomeSought;
    /** The biomes crossed on this outing, in order. */
    private final Set<String> biomesSeen = new LinkedHashSet<>();
    /** Petals: how many were asked for, which one is on, and where the first one went. */
    private int branches, currentBranch;
    private double baseX, baseZ;
    private int branchRadius;

    Explorer(Traveler traveler) {
        this.traveler = traveler;
    }

    /**
     * @param radius blocks to walk (bounded)
     * @param direction north, south, east, west; empty = the one it faces now
     * @param whatISeek id of the block to look for along the way, or empty
     * @param whichBiome piece of the name of the biome to look for, or empty
     * @param howManyBranches directions to try (1 to 8); 0 = the default
     * @return null if it left, or the reason
     */
    synchronized String begin(int radius, String direction, String whatISeek,
                                String whichBiome, int howManyBranches) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (!p.isAlive()) return "I am dead; I must respawn first";

        this.searching.clear();
        this.searchingCreature.clear();
        this.soughtName = null;
        this.hasSweep = false;
        if (whatISeek != null && !whatISeek.isBlank()) {
            String cleanOne = whatISeek.trim().toLowerCase().replace("minecraft:", "");
            // Several, separated by commas: it stops at the FIRST one of the list it
            // sees. With a single one, "animals to eat" forced the brain to bet on one
            // species.
            for (String fragment : cleanOne.split(",")) {
                String one = fragment.trim();
                if (one.isEmpty()) continue;
                ResourceLocation id = ResourceLocation.tryParse("minecraft:" + one);
                // Loud on purpose: the model tends to say "giant cactus" or "sand" and
                // accepting it silently would send the bot on a three-hundred-block walk
                // looking for nothing.
                boolean isBlock = id != null && BuiltInRegistries.BLOCK.containsKey(id);
                boolean isCreature = id != null && BuiltInRegistries.ENTITY_TYPE.containsKey(id);
                if (!isBlock && !isCreature) {
                    return String.format("I do not know '%s' either as a block or as a "
                            + "living creature; ids go in English, like cactus, "
                            + "sand, oak_log, cow or sheep, and several are separated "
                            + "by commas", one);
                }
                // Block first: the few ids in both registries (tnt) are almost always
                // meant as the block.
                if (isBlock) {
                    this.searching.add(BuiltInRegistries.BLOCK.get(id));
                } else {
                    this.searchingCreature.add(BuiltInRegistries.ENTITY_TYPE.get(id));
                }
            }
            if (searching.isEmpty() && searchingCreature.isEmpty()) {
                return "tell me what to search for; the list came empty";
            }
            this.soughtName = cleanOne;
        }
        this.biomeSought = whichBiome == null || whichBiome.isBlank() ? null
                : whichBiome.trim().toLowerCase().replace("minecraft:", "");
        this.biomesSeen.clear();

        int r = Math.max(20, Math.min(RADIUS_MAX,
                radius <= 0 ? DEFAULT_RADIUS : radius));
        double[] v = unitVector(p, direction);
        this.toward = directionName(v);
        this.origin = new Route.Point((int) Math.floor(p.getX()),
                (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));
        this.destination = new Route.Point(
                (int) Math.floor(p.getX() + v[0] * r),
                origin.y(),
                (int) Math.floor(p.getZ() + v[1] * r));
        // When looking for something, giving up after a single straight line is exactly
        // what looked like "giving up fast". Looking for nothing, one petal is a walk.
        int byDefault = (!searching.isEmpty() || !searchingCreature.isEmpty()
                || biomeSought != null) ? SEARCH_BRANCHES : 1;
        this.branches = Math.max(1, Math.min(BRANCHES_MAX,
                howManyBranches <= 0 ? byDefault : howManyBranches));
        this.currentBranch = 1;
        this.baseX = v[0];
        this.baseZ = v[1];
        this.branchRadius = r;
        this.exploring = true;
        this.goingBack = false;
        this.outcome = null;
        traveler.begin(destination, true);
        Diary.note(String.format("going out exploring %d blocks to the %s%s%s",
                r, toward, whatISeek(), branches > 1
                        ? String.format(" (%d spiral segments, up to %d "
                                + "blocks)", branches, reachOf(branches)) : ""));
        return null;
    }

    synchronized void stop(String because) {
        if (exploring) Diary.note("I drop the exploration: " + because);
        exploring = false;
        goingBack = false;
        outcome = because;
    }

    synchronized void tick() {
        if (!exploring) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { stop("I left the world"); return; }
        if (!p.isAlive()) { stop("I was killed while exploring"); return; }

        // The sweep goes BEFORE waiting for the Traveler: the point of searching is to
        // look WHILE walking, not at the end of the walk.
        if ((!searching.isEmpty() || !searchingCreature.isEmpty() || biomeSought != null)
                && sweepDue(p)) {
            String biome = biomeOf(mc, p.blockPosition());
            if (biomesSeen.add(biome)) {
                Diary.note("exploring I crossed " + biome);
            }
            if (biomeSought != null && biome.contains(biomeSought)) {
                foundBiome(p, biome);
                return;
            }
            if (!searching.isEmpty()) {
                BlockPos seen = sweep(mc, p);
                if (seen != null) {
                    found(p, seen);
                    return;
                }
            }
            if (!searchingCreature.isEmpty()) {
                Entity creature = sweepCreature(mc, p);
                if (creature != null) {
                    foundCreature(p, creature);
                    return;
                }
            }
        }

        // The Traveler handles the trip; here it only waits for the outcome.
        if (traveler.traveling()) return;

        if (!goingBack) {
            // Every finished direction is data, not a failure: what was seen is told and
            // it heads for the next one without going home. That the Traveler STOPPED
            // does not mean it ARRIVED, and that confusion ate the whole expedition: if
            // the outward trip did not start (no route, stuck, whatever), this read it as
            // "already explored", turned back towards the origin (where it still was, so
            // it "arrived" instantly) and ended announcing a return that was never a
            // trip: an adventure lasting one second.
            if (!near(p, destination)) {
                String because = traveler.outcome();
                if (near(p, origin)) {
                    // It did not even leave. If directions remain, that is exactly why
                    // there are several: a sea or a wall blocks ONE way out, not all of
                    // them.
                    if (currentBranch < branches) {
                        Diary.note("I could not set out to the " + toward
                                + (because == null || because.isBlank()
                                   ? "" : ": " + because) + "; trying another");
                        nextBranch(p);
                        return;
                    }
                    stop("I did not even manage to set out to the " + toward
                            + (because == null || because.isBlank()
                               ? "" : ": " + because));
                    return;
                }
                // It left, but only got halfway. What it saw still counts and the way
                // back is still mandatory: leaving it halfway is exactly what this class
                // exists to avoid.
                Diary.note("I could not get as far as I wanted to the "
                        + toward + "; I turn back from here");
            }
            tellWhatISaw(mc, p);
            // Circuit left: on to the next direction from HERE.
            if (currentBranch < branches) {
                nextBranch(p);
                return;
            }
            goingBack = true;
            traveler.begin(origin, true);
            return;
        }
        String where = near(p, origin)
                ? String.format("back at %d %d %d",
                        origin.x(), origin.y(), origin.z())
                : String.format("I could not get all the way back; I stayed at "
                        + "%d %d %d", (int) Math.floor(p.getX()),
                        (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));
        if (searching == null && biomeSought == null) {
            stop(where + whereIWalked());
            return;
        }
        // When looking for something, coming back empty-handed IS A RESULT and has to be
        // told: there is none that way, try another direction. Keeping quiet leaves the
        // bot standing and whoever asks knowing nothing.
        String whatWasSought = soughtName != null ? soughtName
                : "biome " + biomeSought;
        int tried = currentBranch;
        stop(String.format("%s; I did %d segment%s (up to %d blocks from home) and "
                + "I did not see %s%s", where, tried, tried == 1 ? "" : "s",
                reachOf(tried), whatWasSought, whereIWalked()));
        Needs.warn("explore_nothing_found:" + whatWasSought,
                String.format("I came back from exploring: I did %d spiral segment%s, "
                        + "the last one %d blocks from home, and I did NOT see %s. Where"
                        + " I walked there was: %s. If needed, I can go out "
                        + "again with a larger radius or more segments, or start towards another "
                        + "side", tried, tried == 1 ? "" : "s",
                        reachOf(tried), whatWasSought, biomeList()));
    }

    /**
     * Turn to the next direction: the destination is the point on the circle around the
     * ORIGIN, but it goes there from wherever it is, without going back to the center.
     * The spread starts with the requested direction, so "explore south with 4" tries
     * south, west, north and east, not almost the same thing four times.
     */
    private void nextBranch(LocalPlayer p) {
        currentBranch++;
        double[] v = Fan.unitVector(baseX, baseZ, currentBranch, branches);
        int far = reachOf(currentBranch);
        this.toward = directionName(v);
        this.destination = new Route.Point(
                (int) Math.floor(origin.x() + v[0] * far),
                origin.y(),
                (int) Math.floor(origin.z() + v[1] * far));
        this.goingBack = false;
        this.hasSweep = false;      // sweep now, without waiting to walk
        traveler.begin(destination, true);
        Diary.note(String.format("still exploring: segment %d of %d, now the "
                + "area to the %s %d blocks from home",
                currentBranch, branches, toward, far));
    }

    /**
     * How far from the origin segment n is placed: the requested radius, plus {@value
     * #GROWS} percent more for every segment already walked. Searching around the same
     * ring would mean looking at the same thing twice.
     */
    private int reachOf(int branch) {
        return Fan.reach(branchRadius, branch, GROWS, FAR_MAX);
    }

    /** The biome of a point, by its short name. */
    private static String biomeOf(Minecraft mc, BlockPos where) {
        return mc.level.getBiome(where).unwrapKey()
                .map(k -> k.location().getPath()).orElse("an odd spot");
    }

    /**
     * Found by biome: there is no specific tile to note, the finding is the whole place.
     * It stops and says where.
     */
    private void foundBiome(LocalPlayer p, String biome) {
        BlockPos where = p.blockPosition();
        traveler.stop("I reached the " + biome);
        Places.remember("biome", where, biome + " seen while exploring");
        Diary.note(String.format("exploring I reached the %s at %d %d %d",
                biome, where.getX(), where.getY(), where.getZ()));
        stop(String.format("I found the %s biome at %d %d %d",
                biome, where.getX(), where.getY(), where.getZ()));
        Needs.warn("explore_biome:" + biome,
                String.format("exploring I ENTERED %s, I am at %d %d %d and I "
                        + "noted it as a place. This is where to look for "
                        + "what comes out in this spot", biome,
                        where.getX(), where.getY(), where.getZ()));
    }

    private String whatISeek() {
        if (soughtName != null) return " looking for " + soughtName;
        if (biomeSought != null) return " seeking the biome " + biomeSought;
        return "";
    }

    private String whereIWalked() {
        return biomesSeen.isEmpty() ? "" : "; I crossed " + biomeList();
    }

    private String biomeList() {
        return biomesSeen.isEmpty() ? "nothing I recognised"
                : String.join(", ", biomesSeen);
    }

    /**
     * Time to sweep? At the start, and then every {@value #SWEEP_STEP} blocks walked:
     * sweeping every tick would mean looking at forty thousand blocks twenty times a
     * second to see the same thing.
     */
    private boolean sweepDue(LocalPlayer p) {
        if (!hasSweep) return true;
        double dx = p.getX() - sweepX, dz = p.getZ() - sweepZ;
        return dx * dx + dz * dz >= SWEEP_STEP * SWEEP_STEP;
    }

    /**
     * Sweeps the surface around looking for the material.
     *
     * <p>Only the surface (from {@value #LOW} below the ground to {@value #TALL} above)
     * and only chunks already loaded: asking about an unloaded chunk does not return
     * "none", it returns air, and that would be lying. A whole cube of radius {@value
     * #EYE} would be three hundred thousand tiles per sweep; this way it is forty
     * thousand, done every {@value #SWEEP_STEP} blocks.
     *
     * @return the nearest, or null
     */
    private BlockPos sweep(Minecraft mc, LocalPlayer p) {
        sweepX = p.getX();
        sweepZ = p.getZ();
        hasSweep = true;
        BlockPos me = p.blockPosition();
        BlockPos best = null;
        double smaller = Double.MAX_VALUE;
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int dx = -EYE; dx <= EYE; dx++) {
            for (int dz = -EYE; dz <= EYE; dz++) {
                int x = me.getX() + dx, z = me.getZ() + dz;
                if (!mc.level.hasChunkAt(x, z)) continue;
                int ground = mc.level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING, x, z);
                for (int y = ground - LOW; y <= ground + TALL; y++) {
                    c.set(x, y, z);
                    if (!searching.contains(mc.level.getBlockState(c).getBlock())) continue;
                    double d = c.distSqr(me);
                    if (d < smaller) { smaller = d; best = c.immutable(); }
                    break;
                }
            }
        }
        return best;
    }

    /**
     * Seen: stop where I am, note it and say it with coordinates. Going for it is the
     * brain's decision: there may be something more urgent right now, and in any case it
     * already knows where it is.
     */
    /**
     * Like {@link #sweep}, but for living things: animals do not show up in the height
     * map, so the world is asked for the entities in the box of {@value #EYE} around. The
     * nearest one is returned.
     */
    private Entity sweepCreature(Minecraft mc, LocalPlayer p) {
        sweepX = p.getX();
        sweepZ = p.getZ();
        hasSweep = true;
        AABB box = p.getBoundingBox().inflate(EYE, CREATURE_HEIGHT, EYE);
        Entity best = null;
        double smaller = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p, box,
                e -> e.isAlive() && searchingCreature.contains(e.getType()))) {
            double d = e.distanceToSqr(p);
            if (d < smaller) { smaller = d; best = e; }
        }
        return best;
    }

    /**
     * Unlike a block, an animal moves: it is NOT noted as a place (it would be a
     * coordinate expired within seconds); the brain is only notified of where it was so
     * it decides right away.
     */
    private void foundCreature(LocalPlayer p, Entity creature) {
        // With several sought, it has to say WHICH one showed up, not the whole list.
        String what = BuiltInRegistries.ENTITY_TYPE.getKey(creature.getType()).getPath();
        BlockPos where = creature.blockPosition();
        int d = (int) Math.sqrt(creature.distanceToSqr(p));
        traveler.stop("I found " + what);
        Diary.note(String.format("exploring I saw %s at %d %d %d",
                what, where.getX(), where.getY(), where.getZ()));
        stop(String.format("I found %s at %d %d %d, %d blocks away",
                what, where.getX(), where.getY(), where.getZ(), d));
        Needs.warn("explore_found:" + what,
                String.format("searching I FOUND %s at %d %d %d, %d blocks from "
                        + "where I am; I have stopped here. It moves, so if "
                        + "you want it we must go NOW: tie it, hunt it or whatever "
                        + "is needed", what, where.getX(), where.getY(), where.getZ(), d));
    }

    private void found(LocalPlayer p, BlockPos seen) {
        String what = BuiltInRegistries.BLOCK.getKey(Minecraft.getInstance()
                .level.getBlockState(seen).getBlock()).getPath();
        int d = (int) Math.sqrt(seen.distSqr(p.blockPosition()));
        traveler.stop("I found " + what);
        Places.remember("material", seen, what + " seen while exploring");
        Diary.note(String.format("exploring I saw %s at %d %d %d",
                what, seen.getX(), seen.getY(), seen.getZ()));
        stop(String.format("I found %s at %d %d %d, %d blocks away",
                what, seen.getX(), seen.getY(), seen.getZ(), d));
        Needs.warn("explore_found:" + what,
                String.format("exploring I FOUND %s at %d %d %d, %d blocks "
                        + "from where I am; I have stopped here and noted it "
                        + "as a place. If you want it, we must go and dig it",
                        what, seen.getX(), seen.getY(), seen.getZ(), d));
    }

    /**
     * Is it practically on top of that point? Checked in XZ: the height it ends up at is
     * decided by the terrain, not by whoever set the goal.
     */
    private static boolean near(LocalPlayer p, Route.Point q) {
        double dx = q.x() + 0.5 - p.getX();
        double dz = q.z() + 0.5 - p.getZ();
        return dx * dx + dz * dz <= ARRIVED * ARRIVED;
    }

    /**
     * What it takes away from a segment: where it arrived, what kind of place it is, and
     * what comes next.
     *
     * <p>The last part matters more than it seems. It used to say <i>"Turning back"</i>
     * at the end of EVERY segment, from when exploring was a single round trip; with the
     * spiral that is a lie in every segment but the last, and it is exactly the sentence
     * that made it look like it gave up halfway. A bot announcing the opposite of what it
     * is about to do is worse than a quiet one.
     */
    private void tellWhatISaw(Minecraft mc, LocalPlayer p) {
        BlockPos where = p.blockPosition();
        String biome = mc.level.getBiome(where).unwrapKey()
                .map(k -> k.location().getPath()).orElse("an odd spot");
        boolean remaining = currentBranch < branches;
        Diary.note(String.format(
                "exploring to the %s I reached %d %d %d: %s",
                toward, where.getX(), where.getY(), where.getZ(), biome));
        String whatsNext;
        if (!remaining) {
            whatsNext = "Turning back";
        } else if (soughtName != null || biomeSought != null) {
            whatsNext = String.format("No %s here: on to segment %d of %d",
                    soughtName != null ? soughtName : biomeSought,
                    currentBranch + 1, branches);
        } else {
            whatsNext = String.format("on to segment %d of %d",
                    currentBranch + 1, branches);
        }
        Voice.say("explore", 60_000, String.format("I reached %d %d %d, %s. %s",
                where.getX(), where.getY(), where.getZ(), biome, whatsNext));
    }

    /** Where to head: the requested direction, or the one it faces right now. */
    private static double[] unitVector(LocalPlayer p, String direction) {
        switch (direction == null ? "" : direction.trim().toLowerCase()) {
            case "north": return new double[]{0, -1};
            case "south":   return new double[]{0, 1};
            case "east":  return new double[]{1, 0};
            case "west": return new double[]{-1, 0};
            default:
                double rad = Math.toRadians(p.getYRot());
                // The same math the game uses for "where I am looking".
                return new double[]{-Math.sin(rad), Math.cos(rad)};
        }
    }

    private static String directionName(double[] v) {
        if (Math.abs(v[0]) > Math.abs(v[1])) return v[0] > 0 ? "east" : "west";
        return v[1] > 0 ? "south" : "north";
    }

    synchronized String state() {
        if (!exploring) {
            return String.format("{\"exploring\":false,\"outcome\":\"%s\"}",
                    Request.escape(outcome));
        }
        return String.format("{\"exploring\":true,\"toward\":\"%s\","
                + "\"returning\":%b,\"searching\":%s,\"biome\":%s,"
                + "\"direction\":%d,\"of\":%d,\"crossing_point\":\"%s\","
                + "\"destination\":{\"x\":%d,\"y\":%d,\"z\":%d}}",
                toward, goingBack,
                soughtName == null ? "null" : "\"" + soughtName + "\"",
                biomeSought == null ? "null" : "\"" + biomeSought + "\"",
                currentBranch, branches, Request.escape(biomeList()),
                destination.x(), destination.y(), destination.z());
    }
}

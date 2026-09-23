package masurium.bot;

import masurium.common.Logbook;
import masurium.common.Request;
import masurium.common.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Animals on a lead: tie them, lead them somewhere else, tether them to a post (a fence)
 * and release them.
 *
 * <p>A sibling of the {@link Rider}, with the same habits: get close with the {@link
 * Walker}'s feet, put in hand what is needed (the lead to tie; a CLEAN hand to tether to
 * the post and to release, because with food in hand the animal gets fed and with the
 * lead another one gets tied), use it, and WAIT for the server to answer before taking
 * anything as done: tied means the animal says its lead is held by me; tethered means a
 * fence knot holds it.
 *
 * <p><b>Leading them</b> is the delicate part: the lead breaks at ten blocks and an
 * animal walks slower than me. While leading I do not run, and if one falls more than
 * {@value #FAR} behind I stop and wait until all are within {@value #NEAR}; the path is
 * asked for without building (they do not climb towers).
 *
 * <p><b>Tethering</b> is vanilla: with animals tied to me, using a fence with an empty
 * hand moves them to a knot on the fence. Releasing is using the animal with an empty
 * hand: the lead drops to the ground next to it.
 */
final class Shepherd {

    private static final double VIEW = 32.0;
    /** From the eyes to the animal's box, as the server measures it. */
    private static final double REACH = 2.9;
    /** For the post: from the eyes to the center of the block. */
    private static final double POST_REACH = 3.8;
    private static final double FAR = 7.5;
    private static final double NEAR = 4.0;
    private static final int WAIT = 25;
    private static final int PLAN = 10;
    private static final int ATTEMPTS_MAX = 4;
    private static final int NO_APPROACH_MAX = 5;

    private enum Phase { NONE, APPROACHING, TYING, LEADING, GOING_TO_POST, TETHERING, RELEASING }
    private enum Mode { TIE, RELEASE }

    private final Walker walker;
    private Phase phase = Phase.NONE;
    private Mode mode = Mode.TIE;
    private Mob target;
    private String requested = "";
    private int quantity;
    private int deeds;
    private final Set<Integer> discardedOnes = new HashSet<>();
    private BlockPos post;
    private BlockPos destination;
    private boolean waiting;
    private List<Integer> toTether = List.of();
    private int attempts, wait, planTicks;
    private double bestDistance;
    private int withoutApproaching;
    private String outcome = "I have not tied any animal";

    Shepherd(Walker walker) {
        this.walker = walker;
    }

    // --- the errands --------------------------------------------------------

    /**
     * Ties up to {@code quantity} animals matching {@code what} (exact name tag, a type
     * such as "cow", or empty = any animal). @return null if it started, or the reason.
     */
    synchronized String tie(String what, int quantity) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (!p.isAlive()) return "I am dead; I must respawn first";
        if (slotOf(p, Items.LEAD) < 0) {
            return "I carry no lead: it is crafted with 4 string and 1 "
                    + "slime_ball (makes 2)";
        }
        this.requested = what == null ? "" : what.trim();
        this.quantity = Math.max(1, quantity);
        this.deeds = 0;
        this.discardedOnes.clear();
        this.mode = Mode.TIE;
        Mob firstOne = nextTarget(mc, p);
        if (firstOne == null) {
            return requested.isEmpty()
                    ? String.format("I see no loose animal within %d blocks", (int) VIEW)
                    : String.format("I see no loose '%s' within %d blocks (an exact "
                            + "name tag works, or the type in English: cow, sheep, "
                            + "chicken, pig, horse...)", requested, (int) VIEW);
        }
        beginWith(firstOne, Phase.APPROACHING);
        Logbook.note("lead", String.format("going to tie %s (%d blocks away)%s",
                nameOf(firstOne), (int) p.distanceTo(firstOne),
                this.quantity > 1 ? String.format(", and up to %d in total", this.quantity) : ""));
        return null;
    }

    /** Leads the tied animals to a point, at their pace. */
    synchronized String carry(int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (!p.isAlive()) return "I am dead; I must respawn first";
        List<Mob> ownedByMeList = tied(mc, p);
        if (ownedByMeList.isEmpty()) return "I have no animal tied; tie_animal first";
        this.destination = new BlockPos(x, y, z);
        this.waiting = false;
        this.planTicks = PLAN;
        this.bestDistance = Double.MAX_VALUE;
        this.withoutApproaching = 0;
        this.phase = Phase.LEADING;
        this.outcome = null;
        Logbook.note("lead", String.format("leading %d animal(s) to %d %d %d, at their pace",
                ownedByMeList.size(), x, y, z));
        return null;
    }

    /** Tethers the tied animals to a fence. */
    synchronized String tether(int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (!p.isAlive()) return "I am dead; I must respawn first";
        BlockPos b = new BlockPos(x, y, z);
        var state = mc.level.getBlockState(b);
        if (!state.is(BlockTags.FENCES)) {
            return String.format("at %d %d %d there is %s, and one only tethers to a FENCE "
                    + "(oak_fence and the like); place one if needed",
                    x, y, z, Miner.nameOf(state));
        }
        List<Mob> ownedByMeList = tied(mc, p);
        if (ownedByMeList.isEmpty()) return "I have no tied animal to tether";
        List<Integer> ids = new ArrayList<>();
        for (Mob m : ownedByMeList) ids.add(m.getId());
        this.toTether = ids;
        this.post = b;
        this.attempts = 0;
        this.wait = 0;
        this.planTicks = PLAN;
        this.bestDistance = Double.MAX_VALUE;
        this.withoutApproaching = 0;
        this.phase = Phase.GOING_TO_POST;
        this.outcome = null;
        Logbook.note("lead", String.format("going to tether %d animal(s) to the fence at %d %d %d",
                ownedByMeList.size(), x, y, z));
        return null;
    }

    /** Releases the ones tied to me: one by name/type, or all of them. */
    synchronized String release(String what) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (!p.isAlive()) return "I am dead; I must respawn first";
        this.requested = what == null || what.trim().equalsIgnoreCase("all") ? "" : what.trim();
        this.mode = Mode.RELEASE;
        this.quantity = requested.isEmpty() ? Integer.MAX_VALUE : 1;
        this.deeds = 0;
        this.discardedOnes.clear();
        Mob firstOne = nextTarget(mc, p);
        if (firstOne == null) {
            return requested.isEmpty() ? "I have no animal tied"
                    : String.format("I have no '%s' tied", requested);
        }
        beginWith(firstOne, Phase.APPROACHING);
        Logbook.note("lead", "going to release " + nameOf(firstOne));
        return null;
    }

    synchronized void stop(String because) {
        if (phase != Phase.NONE) {
            phase = Phase.NONE;
            target = null;
            outcome = because;
        }
    }

    // --- the tick -------------------------------------------------------------

    synchronized void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { stop("I left the world"); return; }
        // With animals tied the bot NEVER runs, whatever the rest of the body does: the
        // lead breaks at ten blocks.
        if (!tied(mc, p).isEmpty()) mc.options.keySprint.setDown(false);
        if (phase == Phase.NONE) return;
        if (!p.isAlive()) { stop("I was killed"); return; }
        switch (phase) {
            case APPROACHING -> approach(mc, p);
            case TYING -> tying(mc, p);
            case RELEASING -> releasing(mc, p);
            case LEADING -> carrying(mc, p);
            case GOING_TO_POST -> goingToPost(mc, p);
            case TETHERING -> tethering(mc, p);
            default -> { }
        }
    }

    private void approach(Minecraft mc, LocalPlayer p) {
        if (target == null || !target.isAlive() || target.isRemoved()) {
            nextOrFinish(mc, p, nameOf(target) + " is gone");
            return;
        }
        if (inReach(p, target)) {
            if (walker.walking()) walker.stop("I am already next to the animal");
            phase = mode == Mode.TIE ? Phase.TYING : Phase.RELEASING;
            wait = 0;
            attempts = 0;
            return;
        }
        if (walker.walking()) return;
        if (++planTicks < PLAN) return;
        planTicks = 0;
        double d = p.distanceTo(target);
        if (d < bestDistance - 0.5) {
            bestDistance = d;
            withoutApproaching = 0;
        } else if (++withoutApproaching >= NO_APPROACH_MAX) {
            discardedOnes.add(target.getId());
            nextOrFinish(mc, p, String.format("I cannot reach %s: I stay "
                    + "%.1f blocks away (is it behind a fence?)", nameOf(target), d));
            return;
        }
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MasuriumBot.whereAmI(world, p);
        if (here == null) { finish("I do not know which tile I am on"); return; }
        Route.Point where = new Route.Point((int) Math.floor(target.getX()),
                (int) Math.floor(target.getY()), (int) Math.floor(target.getZ()));
        Route.Result r = Route.search(world, here, Route.Meta.near(where, 2.0),
                new Route.Options(MasuriumBot.safeFall(p.getHealth()), 6_000,
                        false, true).withDeadline(30));
        if (r.hasRoute() && r.steps().size() > 1) {
            walker.follow(r.steps(), x -> null);
        } else {
            discardedOnes.add(target.getId());
            nextOrFinish(mc, p, String.format("I find no path to %s (%d blocks away)",
                    nameOf(target), (int) d));
        }
    }

    private void tying(Minecraft mc, LocalPlayer p) {
        if (target == null || !target.isAlive()) { nextOrFinish(mc, p, "the animal is gone"); return; }
        if (target.getLeashHolder() == p) {
            deeds++;
            Logbook.note("lead", "tied: " + nameOf(target));
            if (deeds >= quantity) {
                finish(String.format("I tied %s%s; I have %d tied. To move them, "
                        + "lead_animals; to leave them, tether_to_post",
                        nameOf(target), deeds > 1 ? String.format(" and %d more", deeds - 1) : "",
                        tied(mc, p).size()));
                return;
            }
            discardedOnes.add(target.getId());
            nextOrFinish(mc, p, null);
            return;
        }
        if (!inReach(p, target) && p.distanceTo(target) > REACH + 1.5) {
            phase = Phase.APPROACHING;
            planTicks = PLAN;
            return;
        }
        if (wait > 0) { wait--; return; }
        if (attempts >= ATTEMPTS_MAX) {
            discardedOnes.add(target.getId());
            nextOrFinish(mc, p, nameOf(target) + " will not let me tie it");
            return;
        }
        int lead = slotOf(p, Items.LEAD);
        if (lead < 0) { finish("I ran out of leads"); return; }
        p.getInventory().selected = lead;
        p.lookAt(EntityAnchorArgument.Anchor.EYES, target.position());
        mc.gameMode.interact(p, target, InteractionHand.MAIN_HAND);
        attempts++;
        wait = WAIT;
    }

    private void releasing(Minecraft mc, LocalPlayer p) {
        if (target == null || !target.isAlive()) { nextOrFinish(mc, p, "the animal is gone"); return; }
        if (target.getLeashHolder() != p) {
            deeds++;
            Logbook.note("lead", "released: " + nameOf(target));
            BlockPos where = target.blockPosition();
            if (deeds >= quantity || nextTarget(mc, p) == null) {
                finish(String.format("I released %s%s; the lead stays on the ground next "
                        + "to it (%d %d %d): pick_up lead so as not to lose it",
                        nameOf(target), deeds > 1 ? String.format(" and %d more", deeds - 1) : "",
                        where.getX(), where.getY(), where.getZ()));
                return;
            }
            discardedOnes.add(target.getId());
            nextOrFinish(mc, p, null);
            return;
        }
        if (!inReach(p, target) && p.distanceTo(target) > REACH + 1.5) {
            phase = Phase.APPROACHING;
            planTicks = PLAN;
            return;
        }
        if (wait > 0) { wait--; return; }
        if (attempts >= ATTEMPTS_MAX) {
            discardedOnes.add(target.getId());
            nextOrFinish(mc, p, nameOf(target) + " will not let me release it");
            return;
        }
        int clean = cleanHand(p);
        if (clean < 0) { finish("I have no clean slot in the hotbar"); return; }
        p.getInventory().selected = clean;
        p.lookAt(EntityAnchorArgument.Anchor.EYES, target.position());
        mc.gameMode.interact(p, target, InteractionHand.MAIN_HAND);
        attempts++;
        wait = WAIT;
    }

    private void carrying(Minecraft mc, LocalPlayer p) {
        List<Mob> ownedByMeList = tied(mc, p);
        if (ownedByMeList.isEmpty()) {
            if (walker.walking()) walker.stop("the animals got loose");
            finish("all the animals got loose on the way");
            return;
        }
        double maxD = 0;
        Mob laggingBehind = null;
        for (Mob m : ownedByMeList) {
            double d = p.distanceTo(m);
            if (d > maxD) { maxD = d; laggingBehind = m; }
        }
        if (waiting) {
            if (maxD <= NEAR) {
                waiting = false;
                planTicks = PLAN;   // plan right away
            }
            return;
        }
        if (walker.walking()) {
            if (maxD > FAR) {
                walker.stop("waiting for " + nameOf(laggingBehind));
                waiting = true;
                Logbook.note("lead", String.format("waiting for %s, which stayed %.0f behind",
                        nameOf(laggingBehind), maxD));
            }
            return;
        }
        double toDestination = Math.sqrt(p.distanceToSqr(Vec3.atCenterOf(destination)));
        if (toDestination <= 2.5) {
            finish(String.format("I arrived at %d %d %d with %d animal(s)", destination.getX(),
                    destination.getY(), destination.getZ(), ownedByMeList.size()));
            return;
        }
        if (++planTicks < PLAN) return;
        planTicks = 0;
        if (toDestination < bestDistance - 0.5) {
            bestDistance = toDestination;
            withoutApproaching = 0;
        } else if (++withoutApproaching >= NO_APPROACH_MAX) {
            finish(String.format("I cannot get the animals any closer: I stay %.0f "
                    + "from %d %d %d", toDestination, destination.getX(), destination.getY(), destination.getZ()));
            return;
        }
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MasuriumBot.whereAmI(world, p);
        if (here == null) { finish("I do not know which tile I am on"); return; }
        Route.Result r = Route.search(world, here, Route.Meta.near(StripMiner.point(destination), 1.5),
                new Route.Options(2, 8_000, false, true).withDeadline(40));
        if (r.hasRoute() && r.steps().size() > 1) {
            walker.follow(r.steps(), x -> null);
        } else {
            finish(String.format("I find no path with the animals to %d %d %d "
                    + "(%.0f away); without building and without big jumps, which they cannot do",
                    destination.getX(), destination.getY(), destination.getZ(), toDestination));
        }
    }

    private void goingToPost(Minecraft mc, LocalPlayer p) {
        if (!mc.level.getBlockState(post).is(BlockTags.FENCES)) {
            finish(String.format("the fence at %d %d %d is gone", post.getX(), post.getY(), post.getZ()));
            return;
        }
        double d = Math.sqrt(p.getEyePosition().distanceToSqr(Vec3.atCenterOf(post)));
        if (d <= POST_REACH) {
            if (walker.walking()) walker.stop("I am already next to the fence");
            phase = Phase.TETHERING;
            wait = 0;
            attempts = 0;
            return;
        }
        if (walker.walking()) return;
        if (++planTicks < PLAN) return;
        planTicks = 0;
        if (d < bestDistance - 0.5) {
            bestDistance = d;
            withoutApproaching = 0;
        } else if (++withoutApproaching >= NO_APPROACH_MAX) {
            finish(String.format("I cannot reach the fence at %d %d %d: I stay %.1f away",
                    post.getX(), post.getY(), post.getZ(), d));
            return;
        }
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MasuriumBot.whereAmI(world, p);
        if (here == null) { finish("I do not know which tile I am on"); return; }
        Route.Result r = Route.search(world, here, Route.Meta.near(StripMiner.point(post), 2.0),
                new Route.Options(2, 6_000, false, true).withDeadline(30));
        if (r.hasRoute() && r.steps().size() > 1) {
            walker.follow(r.steps(), x -> null);
        } else {
            finish(String.format("I find no path to the fence at %d %d %d",
                    post.getX(), post.getY(), post.getZ()));
        }
    }

    private void tethering(Minecraft mc, LocalPlayer p) {
        int knotted = 0, stillWithMe = 0;
        for (int id : toTether) {
            Entity e = mc.level.getEntity(id);
            if (!(e instanceof Mob m) || !m.isAlive()) continue;
            Entity h = m.getLeashHolder();
            if (h instanceof LeashFenceKnotEntity) knotted++;
            else if (h == p) stillWithMe++;
        }
        if (stillWithMe == 0) {
            finish(String.format("I tethered %d animal(s) to the fence at %d %d %d", knotted,
                    post.getX(), post.getY(), post.getZ()));
            return;
        }
        double d = Math.sqrt(p.getEyePosition().distanceToSqr(Vec3.atCenterOf(post)));
        if (d > POST_REACH + 1.0) {
            phase = Phase.GOING_TO_POST;
            planTicks = PLAN;
            return;
        }
        if (wait > 0) { wait--; return; }
        if (attempts >= ATTEMPTS_MAX) {
            finish(String.format("the fence at %d %d %d does not take my lead: %d "
                    + "tethered and %d still with me", post.getX(), post.getY(),
                    post.getZ(), knotted, stillWithMe));
            return;
        }
        int clean = cleanHand(p);
        if (clean < 0) { finish("I have no clean slot in the hotbar"); return; }
        p.getInventory().selected = clean;
        Vec3 center = Vec3.atCenterOf(post);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, center);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(center, Direction.UP, post, false));
        p.swing(InteractionHand.MAIN_HAND);
        attempts++;
        wait = WAIT;
    }

    // --- plumbing -------------------------------------------------------------

    private void beginWith(Mob m, Phase f) {
        this.target = m;
        this.attempts = 0;
        this.wait = 0;
        this.planTicks = PLAN;
        this.bestDistance = Double.MAX_VALUE;
        this.withoutApproaching = 0;
        this.phase = f;
        this.outcome = null;
    }

    /** Moves on to the next animal of the errand, or finishes with what was done. */
    private void nextOrFinish(Minecraft mc, LocalPlayer p, String memo) {
        if (memo != null) Logbook.note("lead", memo);
        Mob other = nextTarget(mc, p);
        if (other != null && deeds < quantity) {
            beginWith(other, Phase.APPROACHING);
            return;
        }
        String verb = mode == Mode.TIE ? "tied" : "released";
        finish(String.format("I %s %d animal(s)%s; I have %d tied", verb, deeds,
                memo != null ? " (" + memo + ")" : "", tied(mc, p).size()));
    }

    /**
     * The next one matching the request: loose (to tie) or tied to me (to release), the
     * nearest, without repeating discarded ones.
     */
    private Mob nextTarget(Minecraft mc, LocalPlayer p) {
        Mob best = null;
        double mejorD = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p, new AABB(p.blockPosition()).inflate(VIEW),
                x -> x.isAlive() && x instanceof Mob)) {
            Mob m = (Mob) e;
            if (discardedOnes.contains(m.getId())) continue;
            if (mode == Mode.TIE) {
                if (!m.canBeLeashed() || m.isLeashed()) continue;
                if (requested.isEmpty() && !(m instanceof Animal)) continue;
            } else if (m.getLeashHolder() != p) {
                continue;
            }
            if (!home(m)) continue;
            double d = p.distanceTo(m);
            if (d < mejorD) { mejorD = d; best = m; }
        }
        return best;
    }

    private boolean home(Mob m) {
        if (requested.isEmpty()) return true;
        if (m.hasCustomName() && m.getCustomName().getString().equalsIgnoreCase(requested)) return true;
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).getPath();
        String q = requested.toLowerCase();
        return type.equals(q) || type.endsWith("_" + q) || q.equals(type + "s");
    }

    /** The animals whose lead I hold in my hand. */
    static List<Mob> tied(Minecraft mc, LocalPlayer p) {
        List<Mob> list = new ArrayList<>();
        if (mc.level == null) return list;
        for (Entity e : mc.level.getEntities(p, new AABB(p.blockPosition()).inflate(16.0),
                x -> x instanceof Mob)) {
            Mob m = (Mob) e;
            if (m.isAlive() && m.getLeashHolder() == p) list.add(m);
        }
        return list;
    }

    private static boolean inReach(LocalPlayer p, Entity e) {
        return e != null && e.getBoundingBox().distanceToSqr(p.getEyePosition()) <= REACH * REACH;
    }

    private void finish(String how) {
        phase = Phase.NONE;
        target = null;
        outcome = how;
        Logbook.note("lead", how);
        Needs.warn("lead:" + how, "with the lead: " + how);
    }

    /**
     * A hotbar slot that does nothing odd when used: empty, or a plain block. Never food
     * nor a lead: each of those does ITS thing.
     */
    private static int cleanHand(LocalPlayer p) {
        var inv = p.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getItem(i).isEmpty()) return i;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof BlockItem && !s.is(Items.CARVED_PUMPKIN)) return i;
        }
        return -1;
    }

    private static int slotOf(LocalPlayer p, net.minecraft.world.item.Item what) {
        var inv = p.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getItem(i).is(what)) return i;
        return MasuriumBot.takeFromBackpack(p, BuiltInRegistries.ITEM.getKey(what).getPath());
    }

    static String nameOf(Entity e) {
        return Rider.nameOf(e);
    }

    synchronized String state() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        StringBuilder sb = new StringBuilder("[");
        if (p != null) {
            for (Mob m : tied(mc, p)) {
                if (sb.length() > 1) sb.append(',');
                sb.append(String.format("{\"what\":\"%s\",\"name\":\"%s\",\"to\":%.1f}",
                        Request.escape(BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).getPath()),
                        Request.escape(nameOf(m)), p.distanceTo(m)));
            }
        }
        sb.append(']');
        return String.format("{\"working\":%b,\"doing\":\"%s\",\"tied\":%s,"
                + "\"waiting\":%b,\"outcome\":%s}", phase != Phase.NONE,
                phase == Phase.NONE ? "none" : phase.name().toLowerCase(), sb, waiting,
                outcome == null ? "null" : "\"" + Request.escape(outcome) + "\"");
    }
}

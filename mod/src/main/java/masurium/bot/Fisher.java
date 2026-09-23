package masurium.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

import masurium.bot.mixin.FishingHookAccessor;
import masurium.common.Logbook;
import masurium.common.Request;

/**
 * Fishing.
 *
 * <p>The bite is NOT guessed from the bobber's movement: the server announces it
 * (DATA_BITING is synced to the client) and it is read through {@link
 * FishingHookAccessor}, since the field is private. No heuristics with the hook's speed:
 * the server has the truth, here too.
 *
 * <p>The cycle: cast (a {@code useItem}, like the bite of {@code eat}), wait for the bite
 * with the eyes on the bobber, reel in (another {@code useItem}) and cast again. The
 * catch flies towards the bot and goes in by itself when it lands at its feet. It stops
 * on its own if it runs out of a rod in hand, the same honest treatment as the miner when
 * its shovel breaks.
 *
 * <p><b>The bobber on dry land</b> (fifteen minutes with 0 fish and 30 rod uses spent).
 * The bobber does NOT land where the bot looks: it leaves at ~1.1 blocks per tick in the
 * direction of the look and gravity does the rest, so aiming at the nearest water from a
 * high shore (bot at 64, water at 62) left it 25 cm short, on the shore's step; the
 * server said so plainly: {@code OnGround: 1b} one block before the targeted water. Forty
 * centimeters closer the same aim had given 6 fish in two minutes: it was luck. Nothing
 * bites there and each dry reel-in costs 2 rod uses. Three remedies: only water without a
 * collision shape counts (a waterlogged block is land for the bobber), preferring water
 * with water on all four sides; and if the hook still lands dry, it is reeled in at once
 * and another water FARTHER away is targeted if it fell short (or closer if it overshot),
 * four times, and then it gives up NOTIFYING the brain, since without a notice there is
 * no turn.
 */
final class Fisher {

    /** How far around it looks for water, in blocks. */
    private static final int WATER_VIEW = 8;
    /**
     * Without a bite for this long (ticks) it reels in and casts again: the hook may have
     * snagged on land or a lily pad, where nothing will ever bite.
     */
    private static final int NO_BITE = 1200;
    /** After casting, how long to wait for the hook to exist on the client. */
    private static final int HOOK_WAIT = 40;
    /**
     * Breather before (re)casting: casting and reeling in within a breath while the
     * server lags leaves the rod half reeled in.
     */
    private static final int BREATHER = 20;
    /** With the hook in sight, ticks until where it landed counts as settled. */
    private static final int SETTLE = 40;
    /** Dry casts in a row before giving up from this shore. */
    private static final int DRY_MAX = 4;

    private boolean fishing;
    private Vec3 water;
    private int fishCaught;
    private int phaseTicks;
    private boolean launched;
    /** The water block being aimed at, and the ones that already failed dry. */
    private BlockPos waterBlock;
    private final Set<BlockPos> discarded = new HashSet<>();
    private int hookTicks;
    private int dry;
    /** Times in a row the hook snagged on a mob (not a guard). */
    private int snags;
    /**
     * After a dry cast, the next water is looked for farther (or closer) than the
     * targeted one, as a squared distance from the feet.
     */
    private double distMin, distMax;
    private String outcome = "I am not fishing";

    /** @return null if it started, or the reason */
    synchronized String begin() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        int slot = rodInHotbar(p);
        if (slot < 0) slot = MasuriumBot.takeFromBackpack(p, "fishing_rod");
        if (slot < 0) return "I carry no fishing rod";
        // From dry land. When the body asked for "another shore", the brain once walked
        // it into the lake and it ran out of air: swimming is not fishing, and under
        // water it drowns.
        if (p.isEyeInFluid(FluidTags.WATER) || (p.isInWater() && !p.onGround())) {
            return "I am in the water; one fishes from dry land, on "
                    + "the shore, not swimming";
        }
        p.getInventory().selected = slot;
        discarded.clear();
        dry = 0;
        snags = 0;
        hookTicks = 0;
        distMin = -1;
        distMax = Double.MAX_VALUE;
        BlockPos where = waterNear(p, discarded, distMin, distMax);
        if (where == null) {
            return String.format("I see no water within %d blocks; "
                    + "take me to the shore", WATER_VIEW);
        }
        waterBlock = where;
        water = Vec3.atCenterOf(where).add(0, 0.5, 0);
        fishing = true;
        launched = false;
        fishCaught = 0;
        phaseTicks = 0;
        outcome = "fishing";
        Logbook.note("fishing_job", String.format("fishing: water at %d,%d,%d",
                where.getX(), where.getY(), where.getZ()));
        return null;
    }

    synchronized void stop(String because) {
        if (!fishing) return;
        fishing = false;
        launched = false;
        outcome = String.format("%s (I caught %d)", because, fishCaught);
        Logbook.note("fishing_job", outcome);
    }

    synchronized void tick() {
        if (!fishing) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || !p.isAlive()) {
            stop("I ran out of world or of hp");
            return;
        }
        if (!p.getMainHandItem().is(Items.FISHING_ROD)) {
            // It broke (or something changed my hand). Tools break without drama and the
            // bot carries on with the next one: with a spare rod in the backpack it used
            // to stop and ask "shall I go on?". Another one is looked for (hotbar, or
            // backpack brought up to the hotbar) and it carries on. Only with none left
            // does it stop, and it NOTIFIES the brain, because without a notice there is
            // no turn: otherwise the bot stays silent by the lake until someone asks.
            int another = rodInHotbar(p);
            if (another < 0) another = MasuriumBot.takeFromBackpack(p, "fishing_rod");
            if (another >= 0) {
                p.getInventory().selected = another;
                launched = false;
                phaseTicks = 0;
                Logbook.note("fishing_job", "my rod broke; carrying on with another");
                return;
            }
            stop("my last rod broke");
            Needs.warn("no_rod", String.format(
                    "my last fishing rod broke and the fishing ended "
                    + "(I caught %d). I carry no other rod: check whether your guard carries "
                    + "one, check `search_chests fishing_rod`, and only if not, "
                    + "craft one (3 stick + 2 string)", fishCaught));
            return;
        }
        phaseTicks++;
        FishingHook hook = p.fishing;
        if (!launched) {
            p.lookAt(EntityAnchorArgument.Anchor.EYES, water);
            if (phaseTicks < BREATHER) return;
            // With the guard in the casting line the hook sticks into it: ask it to make
            // way and wait.
            var obstruction = Guards.guardBetween(mc, p.getEyePosition(), water, 0.5);
            if (obstruction != null) {
                Guards.moveAside(obstruction, p.getEyePosition(), water, 2.0, "going to cast the rod");
                return;
            }
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            launched = true;
            phaseTicks = 0;
            return;
        }
        if (hook == null) {
            hookTicks = 0;
            // Cast with no hook in sight: either the packet is still traveling or the
            // cast failed. After the wait it tries again.
            if (phaseTicks > HOOK_WAIT) {
                launched = false;
                phaseTicks = 0;
            }
            return;
        }
        // With the hook out, eyes on the bobber: you can see where it fishes.
        p.lookAt(EntityAnchorArgument.Anchor.EYES, hook.position());
        hookTicks++;
        if (((FishingHookAccessor) hook).masuriumBiting()) {
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            fishCaught++;
            dry = 0;
            launched = false;
            phaseTicks = 0;
            return;
        }
        // Hooked on SOMEONE (the guard, a cow, a player): reel in now, since nothing
        // bites there and each tug moves the guard. If it was the guard, it is asked to
        // step out of the line; if it is a mob that does not leave, on the third time it
        // switches water.
        Entity hooked = hook.getHookedIn();
        if (hooked != null) {
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            launched = false;
            phaseTicks = 0;
            String who = hooked.getName().getString();
            if (Guards.isGuard(hooked)) {
                Guards.moveAside((net.minecraft.world.entity.player.Player) hooked,
                        p.getEyePosition(), water, 2.0, "my rod got hooked on you");
                Logbook.note("fishing_job", "I hooked " + who + "; I ask them to make way");
                return;
            }
            Logbook.note("fishing_job", "the hook got caught on " + who + "; reeling in");
            if (++snags >= 3 && waterBlock != null) {
                discarded.add(waterBlock);
                snags = 0;
                BlockPos another = waterNear(p, discarded, distMin, distMax);
                if (another == null) {
                    stop("there is no water where the hook does not get caught");
                    Needs.warn("fishing_snagged", String.format(
                            "the hook gets caught on %s again and again and there is no "
                            + "other water nearby; the fishing ended (I caught %d). "
                            + "Take me to another shore", who, fishCaught));
                    return;
                }
                waterBlock = another;
                water = Vec3.atCenterOf(another).add(0, 0.5, 0);
            }
            return;
        }
        // On land (onGround) as soon as it settles, or out of the water after settling:
        // before, it was only checked in the exact SETTLE tick, and a bobber the current
        // dragged to the shore afterwards stayed there the whole minute.
        if (hookTicks >= 10 && !inWater(hook)
                && (hook.onGround() || hookTicks >= SETTLE)) {
            // Dry: reel in NOW (waiting the minute only wastes time, the rod loses its 2
            // uses anyway), cross out that block and aim at another. Giving up from here
            // is giving up with a notice.
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            launched = false;
            phaseTicks = 0;
            dry++;
            if (waterBlock != null) discarded.add(waterBlock);
            String fell = String.format("%d,%d,%d", hook.getBlockX(),
                    hook.getBlockY(), hook.getBlockZ());
            String how = "";
            if (waterBlock != null) {
                double dWater = waterBlock.distSqr(p.blockPosition());
                double dBobber = hook.blockPosition().distSqr(p.blockPosition());
                if (dBobber < dWater) { distMin = dWater; how = "it fell short; "; }
                else { distMax = dWater; how = "it overshot; "; }
            }
            BlockPos another = dry < DRY_MAX
                    ? waterNear(p, discarded, distMin, distMax) : null;
            if (another == null) {
                stop("the hook lands on dry ground from here");
                Needs.warn("fishing_dry", String.format(
                        "from here the hook lands on dry ground (%d casts, "
                        + "the last one at %s) and the fishing ended (I caught %d). "
                        + "Move along the shore to a spot flush with the water, WITHOUT getting into the water, and fish again",
                        dry, fell, fishCaught));
                return;
            }
            Logbook.note("fishing_job", String.format(
                    "the hook landed on dry ground at %s (I aimed at the water at %d,%d,%d); "
                    + how + "trying the one at %d,%d,%d", fell,
                    waterBlock.getX(), waterBlock.getY(), waterBlock.getZ(),
                    another.getX(), another.getY(), another.getZ()));
            waterBlock = another;
            water = Vec3.atCenterOf(another).add(0, 0.5, 0);
            return;
        }
        if (phaseTicks > NO_BITE) {
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            launched = false;
            phaseTicks = 0;
        }
    }

    /**
     * Whether the bobber is in water, by looking at the WORLD and not at the bobber: a
     * floating bobber sits right at the surface, where {@code isInWater()} (the bobber's
     * box against the fluid height) flickers between yes and no; with that and the
     * client's onGround, a well-placed bobber was taken as "dry" and reeled in a second
     * later, over and over ("it casts and reels in"). Water in its tile or the one below
     * is water.
     */
    private static boolean inWater(FishingHook hook) {
        var levelValue = hook.level();
        BlockPos b = hook.blockPosition();
        return hook.isInWater()
                || levelValue.getFluidState(b).is(FluidTags.WATER)
                || levelValue.getFluidState(b.below()).is(FluidTags.WATER);
    }

    private static int rodInHotbar(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).is(Items.FISHING_ROD)) return i;
        }
        return -1;
    }

    /**
     * The nearest water with air above where a bobber really sinks: water without a
     * collision shape (a waterlogged block such as leaves, slabs or stairs is water for
     * the game but land for the bobber), preferring water with water on all four sides,
     * because the bobber never lands exactly where aimed and at the edge of the shore
     * half the time it lands outside. With no open water, the nearest one not crossed
     * out.
     */
    private static BlockPos waterNear(LocalPlayer p, Set<BlockPos> discarded,
                                      double distMin, double distMax) {
        BlockPos feet = p.blockPosition();
        BlockPos best = null, bestOpen = null;
        double smaller = Double.MAX_VALUE, nearestOpen = Double.MAX_VALUE;
        for (BlockPos b : BlockPos.betweenClosed(
                feet.offset(-WATER_VIEW, -4, -WATER_VIEW),
                feet.offset(WATER_VIEW, 1, WATER_VIEW))) {
            if (!openWater(p, b)) continue;
            if (!p.level().getBlockState(b.above()).isAir()) continue;
            if (discarded.contains(b)) continue;
            double d = b.distSqr(feet);
            if (d <= distMin || d >= distMax) continue;
            if (d < smaller) {
                smaller = d;
                best = b.immutable();
            }
            if (d < nearestOpen && isOpen(p, b)) {
                nearestOpen = d;
                bestOpen = b.immutable();
            }
        }
        return bestOpen != null ? bestOpen : best;
    }

    /**
     * Water where a bobber sinks and STAYS: water fluid, a source block without current
     * and without a collision shape. In moving water (spilled, or a source with a drop
     * next to it) the current drags the bobber to the shore.
     */
    private static boolean openWater(LocalPlayer p, BlockPos b) {
        var fluid = p.level().getFluidState(b);
        return fluid.is(FluidTags.WATER) && fluid.isSource()
                && fluid.getFlow(p.level(), b).lengthSqr() < 1e-6
                && p.level().getBlockState(b).getCollisionShape(p.level(), b).isEmpty();
    }

    /** With free water on all four sides. */
    private static boolean isOpen(LocalPlayer p, BlockPos b) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (!openWater(p, b.relative(side))) return false;
        }
        return true;
    }

    synchronized String state() {
        return String.format(
                "{\"fishing\":%b,\"fish_caught\":%d,\"outcome\":\"%s\"}",
                fishing, fishCaught, Request.escape(outcome));
    }
}

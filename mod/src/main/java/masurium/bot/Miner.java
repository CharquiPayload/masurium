package masurium.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Digging blocks.
 *
 * <p><b>It digs at a SAVED position, never at "whatever is in front of me".</b> Without a
 * screen there is no render, and {@code mc.hitResult} (the block the crosshair points at)
 * never updates. A headless bot does not look: it aims at coordinates. That was one of
 * the first things that took a while to discover.
 *
 * <p>The tool is chosen by <b>asking the game</b> how long each one takes on that block,
 * not with our own "pickaxe is for stone" table. That way it also gets modded blocks
 * right, which is exactly where a hand-written table fails.
 *
 * <p><b>Two things are checked before the first hit</b>, both because of real failures:
 * that the block <b>can be seen</b> (without that it dug buried stone from the grass,
 * through the dirt, which a player cannot do) and that the tool <b>will make it drop
 * something</b>. Breaking stone by hand works and drops nothing: wasted work, and nobody
 * used to say so.
 */
final class Miner {

    /** Reach for breaking in 1.21. Farther, the server rejects it. */
    private static final double REACH = 4.5;

    /** A block that does not give way in this time cannot be dug like this. */
    private static final int TICKS_MAX = 20 * 30;

    private BlockPos target;
    private Direction face;
    private int ticks;
    private String outcome = "I have not dug anything yet";
    private boolean digging;

    /** Which slot it is digging with, to notice if the tool breaks. */
    private int usedSlot;
    private boolean slotHadSomething;
    private String toolName = "my hands";
    private boolean brokenTool;

    synchronized String begin(BlockPos where) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (!p.isAlive()) return "I am dead; I must respawn first";

        BlockState state = mc.level.getBlockState(where);
        if (state.isAir()) return "there is nothing to dig there";
        if (state.getDestroySpeed(mc.level, where) < 0) {
            return "that block cannot be broken";
        }
        // There is NO whitelist lock here: everything that goes through the Miner is an
        // explicit order (dig, gather, strip mine, staircase, fill job), and the order
        // already is the permission. The list still rules where the bot decides ON ITS
        // OWN to break its way through: the Walker and ClientWorld.breakable. Before
        // touching the block, the light: if it is dark here a torch goes in. Here and not
        // in the tick because switching slots mid-dig resets the progress, and only here,
        // which is the definition of "while mining" (no planting torches all over the
        // overworld).
        Torchbearer.lightIfNeeded();

        double d = Math.sqrt(p.distanceToSqr(Vec3.atCenterOf(where)));
        if (d > REACH) {
            return String.format("it is %.1f away and I only reach %.1f; "
                    + "I have to get closer", d, REACH);
        }
        BlockPos obstruction = whatIsInTheWay(p, where);
        if (obstruction != null) {
            return String.format("I cannot see it: there is %s in the way (%d %d %d). "
                    + "It has to be removed or gone around",
                    nameOf(mc.level.getBlockState(obstruction)),
                    obstruction.getX(), obstruction.getY(), obstruction.getZ());
        }

        // The order matters: first see WHAT it would dig with, and only then decide
        // whether it is worth it. The other way round we would have switched hands for
        // nothing.
        int slot = bestTool(p, state);
        if (state.requiresCorrectToolForDrops()
                && !p.getInventory().getItem(slot).isCorrectToolForDrops(state)) {
            // The BACKPACK before giving up. {@link #bestTool} only looks at the hotbar,
            // so with the pickaxe stored the fill job kept saying "it would be wasted
            // work" and skipping blocks: with its axe broken and an iron pickaxe in the
            // backpack, a bot skipped 195 of 400.
            String inBag = toolInBackpack(p, state);
            int ascent = inBag == null ? -1
                    : MasuriumBot.takeFromBackpack(p, inBag);
            if (ascent < 0) {
                return String.format("I may break %s, but without the right "
                        + "tool it drops nothing; it would be wasted work",
                        nameOf(state));
            }
            masurium.common.Logbook.note("dig", String.format(
                    "I brought %s up from the backpack for %s", inBag, nameOf(state)));
            slot = ascent;
        }
        // Working BY HAND when it has something better: no. Dirt drops the same without a
        // shovel, so the warning above did not fire and a bot dug 3,000 blocks by hand
        // without a word, even after being told to craft one.
        slot = withToolOrWarn(mc, where, p, state, slot);

        target = where;
        face = faceTowardMe(p, where);
        ticks = 0;
        outcome = null;
        digging = true;
        brokenTool = false;
        usedSlot = slot;
        var stack = p.getInventory().getItem(slot);
        slotHadSomething = !stack.isEmpty();
        toolName = stack.isEmpty() ? "my hands"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        p.getInventory().selected = slot;
        return null;
    }

    /**
     * The block hiding the target, or {@code null} if it can be seen.
     *
     * <p>A player cannot break what they cannot see, and the bot could: being within
     * {@link #REACH} was enough. That is how it dug buried stone from the grass, through
     * the dirt.
     *
     * <p>Traced with {@code COLLIDER} and not {@code OUTLINE} on purpose: what must be
     * prevented is digging through solid blocks, not tall grass or a torch in front
     * counting as a wall.
     */
    static BlockPos whatIsInTheWay(LocalPlayer p, BlockPos where) {
        return whatIsInTheWay(p, p.getEyePosition(), where);
    }

    /**
     * The same, but looking from any given eyes: it tells whether a block WOULD be
     * visible from a tile before walking to it.
     *
     * <p>First the block's center and, if something hides it, each exposed FACE (with air
     * on the other side): in the game you aim at any visible point of the block, not at
     * its center. A block at your feet has the one above between the eyes and its center,
     * and anyone still digs it by looking at its face; the same for a ceiling block,
     * looking at its bottom face. With the center alone, a bot circled an iron ore for
     * half an hour "that it could not see".
     */
    static BlockPos whatIsInTheWay(LocalPlayer p, Vec3 eyes, BlockPos where) {
        BlockPos obstruction = obstructionToward(p, eyes, Vec3.atCenterOf(where), where);
        if (obstruction == null) return null;
        var levelValue = p.level();
        for (Direction d : Direction.values()) {
            BlockPos neighbor = where.relative(d);
            if (!levelValue.getBlockState(neighbor).getCollisionShape(levelValue, neighbor).isEmpty()) {
                continue;
            }
            // The face's center, a hair outwards so the ray ends in the air and not
            // inside the block itself.
            Vec3 point = Vec3.atCenterOf(where).add(
                    d.getStepX() * 0.51, d.getStepY() * 0.51, d.getStepZ() * 0.51);
            if (obstructionToward(p, eyes, point, where) == null) return null;
        }
        return obstruction;
    }

    private static BlockPos obstructionToward(LocalPlayer p, Vec3 from, Vec3 until,
                                         BlockPos where) {
        BlockHitResult r = p.level().clip(new ClipContext(
                from, until, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (r.getType() == HitResult.Type.MISS) return null;
        return r.getBlockPos().equals(where) ? null : r.getBlockPos();
    }

    synchronized void stop(String because) {
        if (digging) {
            Minecraft.getInstance().gameMode.stopDestroyBlock();
        }
        digging = false;
        outcome = because;
    }

    /** One pickaxe hit. Called every tick, on the game thread. */
    synchronized void tick() {
        if (!digging) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { stop("I left the world"); return; }
        if (!p.isAlive()) { stop("I was killed while digging"); return; }

        // No digging while eating: the automatic bite puts the food in hand and, without
        // this, the hits went on with the pork chop. It waits, and when resuming the tool
        // is wielded again, since eating does not give it back.
        if (MasuriumBot.eating()) return;
        if (p.getInventory().selected != usedSlot) {
            p.getInventory().selected = usedSlot;
        }

        // Did the tool break mid-job? When a pickaxe or shovel wears out completely, the
        // item disappears from the slot without warning, and the bot used to keep digging
        // by hand as if nothing happened, slower and unaware. Stop and say so, do not
        // pretend.
        if (slotHadSomething
                && p.getInventory().getItem(usedSlot).isEmpty()) {
            // Tools break without drama. It broke: carry on with the next one that works
            // (hotbar, or backpack brought up to the hotbar), or by hand if the block
            // needs no tool. It only stops without a spare.
            String brokenOne = toolName;
            BlockState state = mc.level.getBlockState(target);
            int another = spare(p, state);
            if (another >= 0) {
                p.getInventory().selected = another;
                usedSlot = another;
                ItemStack fresh = p.getInventory().getItem(another);
                slotHadSomething = !fresh.isEmpty();
                toolName = fresh.isEmpty() ? "my hand"
                        : BuiltInRegistries.ITEM.getKey(fresh.getItem()).getPath();
                masurium.common.Logbook.note("dig", String.format(
                        "my %s broke; carrying on with %s", brokenOne, toolName));
                return;
            }
            brokenTool = true;
            stop(String.format("my tool (%s) broke halfway through "
                    + "the work and I carry no other that works", toolName));
            return;
        }
        if (mc.level.getBlockState(target).isAir()) {
            // If what I just broke was a noted place (my table, a furnace), it is gone:
            // out of memory. Many players place and break their crafting tables.
            if (Places.forget(target)) {
                masurium.common.Logbook.note("places", String.format(
                        "I forget the place at %d %d %d: I just broke it",
                        target.getX(), target.getY(), target.getZ()));
            }
            PlacedBlocks.forget(target);
            stop(String.format("dug (%d %d %d)",
                    target.getX(), target.getY(), target.getZ()));
            return;
        }
        if (++ticks > TICKS_MAX) {
            stop(String.format("%d seconds and it does not give way; I may be "
                    + "missing the tool", TICKS_MAX / 20));
            return;
        }
        // It has to keep looking at it: the server validates where the head points, and
        // without turning it the hit is rejected silently.
        p.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(target));
        if (ticks == 1) {
            mc.gameMode.startDestroyBlock(target, face);
        } else {
            mc.gameMode.continueDestroyBlock(target, face);
        }
        p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    synchronized String state() {
        if (!digging) {
            return String.format("{\"digging\":false,\"outcome\":\"%s\"}",
                    masurium.common.Request.escape(outcome));
        }
        return String.format(
                "{\"digging\":true,\"block\":{\"x\":%d,\"y\":%d,\"z\":%d},"
                + "\"ticks\":%d}",
                target.getX(), target.getY(), target.getZ(), ticks);
    }

    synchronized boolean digging() {
        return digging;
    }

    /** What I am digging now, or null. */
    synchronized BlockPos target() {
        return digging ? target : null;
    }

    /**
     * The tool that just broke, or null. Cleared when digging again and with {@link
     * #forgetBrokenTool()}.
     */
    synchronized String brokenTool() {
        return brokenTool ? toolName : null;
    }

    /**
     * Marks the broken tool as handled. Every job calls it on STARTING: the flag was only
     * cleared when digging again, and jobs check it BEFORE their first hit, so a
     * staircase relaunched with the new pickaxe in hand died on its first tick with the
     * old message ("my stone_pickaxe broke... ask me again"), without a logbook line and
     * with the bot standing still.
     */
    synchronized void forgetBrokenTool() {
        brokenTool = false;
    }

    /**
     * If nothing in the hotbar speeds up this block: bring up from the backpack whatever
     * works and, if there is nothing either, have the body notify (once every ten minutes
     * per tool class) so the brain crafts one and carries on.
     *
     * <p>Only for blocks that ASK for a tool (dirt: shovel; log: axe; stone: pickaxe;
     * leaves: nothing). A block that does not ask for one is broken by hand and there is
     * nothing to complain about.
     *
     * @return the slot it ends up digging with (the one brought up, or the previous one)
     */
    private static int withToolOrWarn(Minecraft mc, BlockPos where, LocalPlayer p,
                                      BlockState state, int slot) {
        var inv = p.getInventory();
        ItemStack chosenOne = inv.getItem(slot);
        float v = chosenOne.isEmpty() ? 1.0f : chosenOne.getDestroySpeed(state);
        if (v > 1.0f) return slot;            // something speeds it up: there is a tool
        // A fern or grass breaks at a touch: asking for an axe for that is what once made
        // the bot craft a wooden axe for a fern. Only what really costs to break by hand
        // counts (dirt: 0.5).
        if (state.getDestroySpeed(mc.level, where) < 0.5f) return slot;
        String cls = toolClass(state);
        if (cls == null) return slot;       // it needs no tool
        int inBag = -1;
        float bestV = 1.0f;
        for (int i = 9; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            float iSaw = stack.getDestroySpeed(state);
            if (iSaw > bestV
                    && (!state.requiresCorrectToolForDrops()
                        || stack.isCorrectToolForDrops(state))) {
                bestV = iSaw;
                inBag = i;
            }
        }
        if (inBag >= 0) {
            String id = BuiltInRegistries.ITEM.getKey(inv.getItem(inBag).getItem()).getPath();
            int ascent = MasuriumBot.takeFromBackpack(p, id);
            if (ascent >= 0) {
                masurium.common.Logbook.note("dig", String.format(
                        "I brought %s up from the backpack for %s", id, nameOf(state)));
                return ascent;
            }
        }
        // Nothing helps anywhere: it is broken BY HAND, not with the pickaxe. A tool
        // spends a use even when it speeds nothing up, so digging dirt with the iron
        // pickaxe throws its 250 uses into grass. An empty slot and, if there is none,
        // something that does not wear (blocks, food).
        int byHand = slot;
        if (inv.getItem(slot).isDamageableItem()) {
            int empty = -1, notDamageable = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) { empty = i; break; }
                if (notDamageable < 0 && !stack.isDamageableItem()) notDamageable = i;
            }
            byHand = empty >= 0 ? empty : (notDamageable >= 0 ? notDamageable : slot);
        }
        // Key with the minute/2: the usual ten-minute notice cooldown is too long here; a
        // wooden shovel broke and the bot went on by hand for nine minutes without anyone
        // telling it.
        Needs.warn("no_tool:" + cls + ":" + (System.currentTimeMillis() / 120_000),
                String.format(
                "I am breaking %s BY HAND because I carry no %s (neither in the hotbar "
                + "nor in the backpack) and it goes very slowly; if you can, craft "
                + "one and carry on with the same errand", nameOf(state), cls));
        return byHand;
    }

    /** Which tool the block asks for, by its tags (modded tags count). */
    private static String toolClass(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return "shovel";
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return "axe";
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return "pickaxe";
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) return "hoe";
        return null;
    }

    /**
     * The hotbar slot with the tool that breaks THAT block fastest. It returns, it does
     * not equip: the caller decides whether to use it.
     * The game is asked with {@code getDestroySpeed} instead of deciding with our own
     * list: that way it gets any block right, modded ones included, which is exactly
     * where a hand-written list falls short.
     */
    static int bestTool(LocalPlayer p, BlockState state) {
        // Two passes in one: first the tools with which the block DROPS something count,
        // and among those the fastest. Without the filter, plain "the fastest" could be
        // one that breaks quickly and drops nothing. If none works, the fastest without
        // the filter is returned, so the "wasted work" warning above talks about the best
        // real option and not some random slot.
        boolean requires = state.requiresCorrectToolForDrops();
        // It starts from WHAT IT ALREADY HOLDS, and only switches for something STRICTLY
        // faster. Starting at -1 with a tie at 1.0 (dirt without a shovel: everything is
        // the same) the first hotbar slot won, and the bot was seen digging dirt with a
        // poppy in hand. Just as fast, yes, but switching items for nothing is for
        // nothing.
        int sel = p.getInventory().selected;
        ItemStack heldInHand = p.getInventory().getItem(sel);
        float vHand = heldInHand.isEmpty() ? 1.0f : heldInHand.getDestroySpeed(state);
        boolean handSuitable = !requires || heldInHand.isCorrectToolForDrops(state);

        int best = sel;
        float bestV = vHand;
        int bestSuitable = handSuitable ? sel : -1;
        float bestVSuitable = handSuitable ? vHand : -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = p.getInventory().getItem(i);
            float v = stack.isEmpty() ? 1.0f : stack.getDestroySpeed(state);
            if (v > bestV) {
                bestV = v;
                best = i;
            }
            if ((!requires || stack.isCorrectToolForDrops(state)) && v > bestVSuitable) {
                bestVSuitable = v;
                bestSuitable = i;
            }
        }
        return bestSuitable >= 0 ? bestSuitable : best;
    }

    /**
     * Whether that block would drop something with what it carries IN THE HOTBAR.
     *
     * <p>Public so the FillWorker can check it <b>before</b> starting a whole area.
     * Without this, a job without the right pickaxe does not fail: it wanders. Each block
     * is rejected one by one, another spot is tried just in case, and the 392 refusals
     * are counted as <i>"I could not reach"</i>, which is a lie on top of it, since it
     * did reach: 0 of 392 while circling the cube with the diamond pickaxe in the
     * backpack.
     */
    /**
     * The hotbar slot to carry on with after the tool breaks: the best of the hotbar if
     * it works, otherwise one from the backpack brought up to the hotbar, and if the
     * block needs no tool, whatever (even the hand). -1 only if the block needs a tool
     * and none is left anywhere.
     */
    static int spare(LocalPlayer p, BlockState state) {
        var inv = p.getInventory();
        boolean requires = state.requiresCorrectToolForDrops();
        int best = bestTool(p, state);
        ItemStack cand = inv.getItem(best);
        boolean works = !cand.isEmpty() && (requires
                ? cand.isCorrectToolForDrops(state)
                : cand.getDestroySpeed(state) > 1.0f);
        if (works) return best;
        String inBag = toolInBackpack(p, state);
        if (inBag != null) {
            int ascent = MasuriumBot.takeFromBackpack(p, inBag);
            if (ascent >= 0) return ascent;
        }
        return requires ? -1 : best;
    }

    static boolean toolWorks(LocalPlayer p, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return true;
        if (p.getInventory().getItem(bestTool(p, state))
                .isCorrectToolForDrops(state)) {
            return true;
        }
        // Stored tools count too: the FillWorker asks this before starting a whole area,
        // and rejecting it for having the pickaxe in the backpack would be refusing a job
        // that can be done.
        return toolInBackpack(p, state) != null;
    }

    /** The id of something stored in the backpack that would work, or null. */
    static String toolInBackpack(LocalPlayer p, BlockState state) {
        var inv = p.getInventory();
        for (int i = 9; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) {
                return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            }
        }
        return null;
    }

    /** The block face facing the player. */
    private static Direction faceTowardMe(LocalPlayer p, BlockPos b) {
        Vec3 d = p.getEyePosition().subtract(Vec3.atCenterOf(b));
        return Direction.getNearest(d.x, d.y, d.z);
    }

    static String nameOf(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }
}

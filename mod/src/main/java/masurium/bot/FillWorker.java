package masurium.bot;

import masurium.common.Logbook;
import masurium.common.Request;
import masurium.common.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Filling and emptying an area, like {@code /fill}, but done by hand, block by block, as
 * a player would.
 * It is what Baritone offered with its {@code sel} command and was lost when dropping it.
 * Now it is ours, so when it fails we can see why.
 *
 * <p><b>Three brakes, and none is decorative.</b> This is the feature that can rebuild
 * someone's house by accident:
 * <ul>
 *   <li>the area has a <b>block cap</b>, and going over it is rejected with the exact
 *       number instead of starting and regretting it halfway;</li>
 *   <li>the material must be <b>placeable from the hand</b> (or air, to empty) and is
 *       counted against the inventory before starting;</li>
 *   <li>it can be <b>stopped at any moment</b>, and on stopping it says how many blocks
 *       were done.</li>
 * </ul>
 *
 * <p>If a block is out of reach, it <b>walks to it</b> reusing the path finder. If it
 * cannot get there, it counts it as skipped and moves on, and at the end it says how many
 * there were. Giving up entirely because of one unreachable block would be worse than
 * doing 95%.
 */
final class FillWorker {

    /** Area cap. More than this is almost never what someone wanted. */
    private static final int BLOCKS_MAX = 4096;
    /** Reach to touch a block. */
    private static final double REACH = 4.0;
    /**
     * How far it may drift when arriving at the chosen tile (the walker's fine arrival
     * and a bit more). The line of sight must also be clear from there: a floor block at
     * the feet is seen from two blocks away by a couple of centimeters over the edge, and
     * those centimeters were lost when arriving 20 or 40 cm off-center ("I arrived and
     * still could not reach", four planks out of seventeen).
     */
    private static final double VIEW_MARGIN = 0.45;
    /**
     * How much of the tick may go to planning, in nanoseconds.
     *
     * <p>A TIME budget and not a number of attempts, learned the hard way: the previous
     * cap was "3 blocks per tick", but an unreachable block is up to 8 searches that
     * exhaust their whole budget (~100 ms each): 2.4 seconds of tick with a distant area
     * of 270 blocks, and the game so choked that even /state returned TimeoutException.
     * Time does not lie: 35 ms is 35 ms, whatever each search costs.
     */
    private static final long PLAN_MAX_NS = 35_000_000L;
    /**
     * How precisely it must stand on the chosen tile.
     *
     * <p>The walker counts as arrived at 1.4, which is fine for going somewhere and not
     * for working: the tile was chosen <b>because the block can be seen from its
     * center</b>, and a meter and a half from that center any overhang hides it. Falling
     * short was not arriving somewhere similar, it was <b>not being able to touch
     * anything</b>: 0 of 392, circling the cube with the dirt in hand without digging
     * once.
     */
    private static final double FINE_ARRIVAL = 0.4;

    private enum Phase { CHOOSING, GOING, STEPPING_ASIDE, ACTING, REVIEWING }
    /**
     * Times it stepped aside from THIS cell's gap. Three and it is skipped: stepping
     * aside without getting out was a loop ("I step away from the gap" / "I already reach
     * the block" ten times a second).
     */
    private int timesSteppedAside;

    private final Walker walker;
    private final Miner miner;

    private BlockPos cornerA;
    private BlockPos cornerB;

    private List<BlockPos> pendingList = new ArrayList<>();
    /**
     * The ones rejected for lacking something to dig them with. Not a final no: they are
     * retried in the next pass.
     */
    private List<BlockPos> noTool = new ArrayList<>();

    /**
     * The ones this pass did not reach. The next one might: every block removed opens
     * room where there was none.
     */
    private List<BlockPos> unreachable = new ArrayList<>();
    /**
     * Cap on passes. The real stop condition is "a whole pass without progress"; this is
     * just the belt in case something oscillates.
     */
    private static final int PASSES_MAX = 12;
    private int pass;
    private int deedsAtPassStart;

    private Block withWhat;           // null = empty (air)
    /**
     * If set, the job is ONLY these cells and not the whole box. It is what allows
     * building a blueprint drawn in layers instead of a cube. Cleared on finishing.
     */
    private java.util.Set<BlockPos> onlyThese;
    /**
     * The blueprint: cell -> block, with null for "air". When set, the job is the
     * blueprint and not a single-material box: it is what is needed to raise a house
     * drawn in layers INSIDE the mod, without the brain waiting turn after turn. Cleared
     * on finishing.
     */
    private Map<BlockPos, Block> blueprint;
    /**
     * And of the layer in progress. The blueprint goes up layer by layer, bottom to top,
     * and each layer is REVIEWED before going up: once a layer is done it is checked, and
     * if something is wrong it is fixed and checked again.
     */
    private int layer;
    /** Reviews done on the layer in progress. */
    private int reviews;
    /**
     * Cap on reviews per layer. Past it, the job stops and says which cells are not
     * right: building on top of a broken layer is worse than stopping.
     */
    private static final int REVIEWS_MAX = 3;
    /**
     * Ticks of waiting before reviewing: the server confirms or reverts each block within
     * a few ticks, and reviewing earlier would read the client's prediction, which is
     * exactly what is not wanted.
     */
    private static final int REVIEW_WAIT = 30;
    /** Layers that already passed the review. */
    private int layersReady;
    /**
     * Collecting what is left over inside the blueprint before stopping because of a
     * layer that will not come out: mostly its own scaffolding. And the reason it will
     * stop with once done collecting.
     */
    private boolean cleaning;
    private String layerFailure;
    /**
     * Placing the FINISHING TOUCHES: doors, trapdoors and gates go at the end, when every
     * layer is done. A door is placed closed and the bot cannot open them: placed on the
     * first wall layer, it locked the bot out of its own house by the time it reached the
     * roof.
     */
    private boolean finishingOff;
    /**
     * Center of the layer in progress: within a layer the most peripheral goes first
     * (corners, edges) and the center last, which is what can be seen from inside with
     * nothing hiding it.
     */
    private double centerX, centerZ;
    /** What was placed, by material, for the blueprint's outcome. */
    private final Map<String, Integer> placedBy = new TreeMap<>();
    /**
     * Per cell of the layer in progress, how many neighbours OUTSIDE the blueprint are
     * solid (terrain). The most enclosed goes first: a corner against a hillside gets
     * covered as soon as its neighbours are placed and then nothing can see it.
     */
    private final Map<BlockPos, Integer> enclosure = new HashMap<>();
    /**
     * The blocks the blueprint uses. One of these in a cell that must be empty is its
     * own, placed crooked; it is claimed so it can be removed.
     */
    private final java.util.Set<Block> blueprintMaterials = new HashSet<>();
    /**
     * Only the shell of the box: walls, floor and ceiling. The inside is not touched (nor
     * emptied): for filling hollow structures.
     */
    private boolean gap;
    /**
     * Filling, whether it is still in the CLEAR phase. First clear, then place. Replacing
     * cell by cell (dig one, place one, dig the next) worked but was illegible from
     * outside and invited getting stuck: the bot walked between half-dug holes and
     * half-placed cobblestone, inside its own job. Two clean passes: empty everything
     * (top to bottom, like dismantling) and then place everything (bottom to top, like
     * building).
     */
    private boolean clearing;
    private String nameWithWhat = "air";
    private Phase phase = Phase.CHOOSING;
    private BlockPos current;
    /**
     * Whether another spot has already been tried for this block. See {@link #acting}.
     */
    private boolean soughtElsewhere;
    /**
     * Why the last {@link #goToward} failed, so it can be noted.
     *
     * <p>"366 I could not reach" says nothing useful: it does not tell a cube that cannot
     * be entered from a path finder failure, and without that distinction things get
     * fixed blindly.
     */
    private String whyIDidntGo = "";
    private int deeds, skipped, ticksInPhase;
    /** How many of the done ones were clearing, so they are not counted as placed. */
    private int clearedOnes;
    private int notArriving, unsupported, forSlowOnes, byTool, byPermission;
    private boolean working;
    private String outcome = "I have not worked yet";

    FillWorker(Walker walker, Miner miner) {
        this.walker = walker;
        this.miner = miner;
    }

    // --- the selection -------------------------------------------------------

    synchronized String markPlace(int which, BlockPos where) {
        if (which == 1) cornerA = where; else cornerB = where;
        return selection();
    }

    synchronized String selection() {
        if (cornerA == null || cornerB == null) {
            return String.format(
                    "{\"ok\":true,\"complete\":false,\"to\":%s,\"b\":%s}",
                    json(cornerA), json(cornerB));
        }
        return String.format(
                "{\"ok\":true,\"complete\":true,\"to\":%s,\"b\":%s,\"blocks\":%d}",
                json(cornerA), json(cornerB), volume());
    }

    private long volume() {
        return (long) (Math.abs(cornerA.getX() - cornerB.getX()) + 1)
                * (Math.abs(cornerA.getY() - cornerB.getY()) + 1)
                * (Math.abs(cornerA.getZ() - cornerB.getZ()) + 1);
    }

    private static String json(BlockPos p) {
        return p == null ? "null"
                : String.format("{\"x\":%d,\"y\":%d,\"z\":%d}",
                                p.getX(), p.getY(), p.getZ());
    }

    // --- the job -------------------------------------------------------------

    /** @param block block id, or "air"/null to empty */
    synchronized String begin(String block) {
        onlyThese = null;   // a box is the whole box, wherever it comes from
        return begin(block, false);
    }

    /** The block that goes in that cell: the blueprint's, or the job's single one. */
    private Block materialOf(BlockPos b) {
        return blueprint != null ? blueprint.get(b) : withWhat;
    }

    private String nameOf(BlockPos b) {
        Block m = materialOf(b);
        return m == null ? "air" : BuiltInRegistries.BLOCK.getKey(m).getPath();
    }

    /** Emptying: the whole job is removing (no blueprint and no material). */
    private boolean emptying() {
        return blueprint == null && withWhat == null;
    }

    /**
     * Whether a cell the blueprint wants EMPTY is fine as it is. What can be walked
     * through (tall grass, flowers) counts as empty, as in clearing. And so does the top
     * half of a door: a door takes two cells and the blueprint only draws the bottom one;
     * taking the top one as "leftover" broke the door.
     */
    private static boolean acceptableEmpty(BlockState state) {
        if (state.canBeReplaced()) return true;
        return state.getBlock() instanceof DoorBlock
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
    }

    /**
     * If a cell that must be empty holds one of the blueprint's blocks, it is its own
     * (placed wrongly) and is noted as such so it can be removed. If it is another
     * material (terrain, or someone else's) it is touched no more than permissions allow.
     */
    private boolean isMine(BlockPos b, BlockState state) {
        if (blueprint == null || blueprint.get(b) != null) return false;
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (PlacedBlocks.isMine(b, id)) return true;
        if (blueprintMaterials.contains(state.getBlock())) {
            PlacedBlocks.note(b, id);
            return true;
        }
        return false;
    }

    /** Whether the cell is as the blueprint wants it. */
    private boolean likeTheBlueprint(BlockPos b, BlockState state) {
        Block m = blueprint.get(b);
        return m == null ? acceptableEmpty(state) : state.getBlock() == m;
    }

    /**
     * A job with SEVERAL materials, cell by cell: a house blueprint.
     *
     * <p>Before taking a step it counts the material against the inventory and, if
     * something is missing, refuses with the exact list: the brain decides whether to go
     * get it or leave it. The brain looks for materials or refuses, because finishing the
     * job later would be complicated. Half a house is useless.
     *
     * @param cells cell -> block id ("air" to empty)
     * @return null if it started, or the reason
     */
    synchronized String beginBlueprint(Map<BlockPos, String> cells) {
        if (cells == null || cells.isEmpty()) return "you gave me no cells";
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return "I am in no world";
        if (cells.size() > BLOCKS_MAX) {
            return String.format("that blueprint is %d cells and my cap is %d",
                    cells.size(), BLOCKS_MAX);
        }
        Map<BlockPos, Block> latest = new LinkedHashMap<>();
        int x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE, z1 = Integer.MAX_VALUE;
        int x2 = Integer.MIN_VALUE, y2 = Integer.MIN_VALUE, z2 = Integer.MIN_VALUE;
        for (var e : cells.entrySet()) {
            BlockPos b = e.getKey().immutable();
            String id = e.getValue() == null ? "" : e.getValue().trim().toLowerCase();
            Block m = null;
            if (!(id.isEmpty() || id.equals("air") || id.equals("air"))) {
                var rl = net.minecraft.resources.ResourceLocation.tryParse("minecraft:" + id);
                if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
                    return String.format("I do not know the block '%s'; ids go in "
                            + "English, like cobblestone or oak_planks", id);
                }
                m = BuiltInRegistries.BLOCK.get(rl);
                if (!(m.asItem() instanceof net.minecraft.world.item.BlockItem)) {
                    return String.format("'%s' is not a block that can be placed "
                            + "from the hand", id);
                }
            }
            latest.put(b, m);
            x1 = Math.min(x1, b.getX()); x2 = Math.max(x2, b.getX());
            y1 = Math.min(y1, b.getY()); y2 = Math.max(y2, b.getY());
            z1 = Math.min(z1, b.getZ()); z2 = Math.max(z2, b.getZ());
        }

        // What really has to change, and with it the material count: only the cells NOT
        // already as the blueprint wants.
        List<BlockPos> toDo = new ArrayList<>();
        Map<String, Integer> iNeed = new TreeMap<>();
        for (var e : latest.entrySet()) {
            BlockState state = mc.level.getBlockState(e.getKey());
            Block m = e.getValue();
            boolean good = m == null ? acceptableEmpty(state) : state.getBlock() == m;
            if (good) continue;
            toDo.add(e.getKey());
            if (m != null) {
                iNeed.merge(BuiltInRegistries.BLOCK.getKey(m).getPath(), 1,
                        Integer::sum);
            }
        }
        if (toDo.isEmpty()) return "that blueprint is already done: there is nothing to change";
        List<String> missingCount = new ArrayList<>();
        for (var e : iNeed.entrySet()) {
            int iHave = MasuriumBot.quantityCarried(mc.player, e.getKey());
            if (iHave < e.getValue()) {
                missingCount.add(String.format("%d %s (I carry %d)",
                        e.getValue() - iHave, e.getKey(), iHave));
            }
        }
        if (!missingCount.isEmpty()) {
            return "I am missing material for that blueprint: " + String.join(", ", missingCount)
                   + ". Get it (or remove those parts from the blueprint) and ask "
                   + "me again";
        }

        this.blueprint = latest;
        this.onlyThese = new HashSet<>(latest.keySet());
        this.blueprintMaterials.clear();
        for (Block m : latest.values()) if (m != null) blueprintMaterials.add(m);
        this.cornerA = new BlockPos(x1, y1, z1);
        this.cornerB = new BlockPos(x2, y2, z2);
        this.gap = false;
        this.withWhat = null;
        this.nameWithWhat = "blueprint";
        this.placedBy.clear();
        this.layersReady = 0;
        this.cleaning = false;
        this.layerFailure = null;
        this.finishingOff = false;
        this.layer = y1;      // the bottom one; during clearing there is no layer yet
        this.reviews = 0;

        // First everything in the way is CLEARED (top to bottom), as in a box job; then
        // it builds layer by layer.
        List<BlockPos> covered = new ArrayList<>();
        for (BlockPos b : toDo) {
            BlockState rightThere = mc.level.getBlockState(b);
            if (rightThere.canBeReplaced()) continue;
            isMine(b, rightThere);   // its own crooked blocks are claimed BEFORE looking at permissions
            covered.add(b);
        }
        pendingList = new ArrayList<>(covered);
        clearing = !covered.isEmpty();
        if (clearing) {
            String missing = whatIsMissingToDig(mc);
            if (missing != null) {
                blueprint = null;
                onlyThese = null;
                return missing;
            }
        }
        startUp(mc.player);
        if (!clearing) nextLayer(mc.player);
        return null;
    }

    /**
     * A job on LOOSE cells, whichever they are, instead of a box. The corners are set to
     * the box containing them because the rest of the FillWorker uses them for its caps
     * and walks; the set filters the rest.
     *
     * @param cells where to place {@code block}. Empty = nothing to do.
     */
    synchronized String beginCells(String block, java.util.List<BlockPos> cells) {
        if (cells == null || cells.isEmpty()) return "you gave me no cells";
        int x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE, z1 = Integer.MAX_VALUE;
        int x2 = Integer.MIN_VALUE, y2 = Integer.MIN_VALUE, z2 = Integer.MIN_VALUE;
        for (BlockPos b : cells) {
            x1 = Math.min(x1, b.getX()); x2 = Math.max(x2, b.getX());
            y1 = Math.min(y1, b.getY()); y2 = Math.max(y2, b.getY());
            z1 = Math.min(z1, b.getZ()); z2 = Math.max(z2, b.getZ());
        }
        this.onlyThese = new java.util.HashSet<>(cells);
        this.cornerA = new BlockPos(x1, y1, z1);
        this.cornerB = new BlockPos(x2, y2, z2);
        String problem = begin(block, false);
        if (problem != null) this.onlyThese = null;
        return problem;
    }

    /**
     * @param block block id, or "air"/null to empty
     * @param gap only the shell; the inside of the box is left as it is
     */
    synchronized String begin(String block, boolean gap) {
        this.gap = gap;
        this.blueprint = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return "I am in no world";
        if (cornerA == null || cornerB == null) {
            return "corners missing: mark 1 and 2 before filling";
        }
        long v = volume();
        if (v > BLOCKS_MAX) {
            return String.format("that area is %d blocks and my cap is %d; "
                    + "make it smaller or raise my cap knowingly",
                    v, BLOCKS_MAX);
        }

        boolean emptyOut = block == null || block.isBlank()
                || block.equals("air") || block.equals("air");
        if (emptyOut) {
            withWhat = null;
            nameWithWhat = "air";
        } else {
            var id = net.minecraft.resources.ResourceLocation
                    .tryParse("minecraft:" + block);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                return String.format("I do not know the block '%s'; ids go in "
                        + "English, like cobblestone or dirt", block);
            }
            // Any block that can be placed from the hand. Filling used to accept only
            // "cheap" material; the restriction only matters while the bot travels.
            // Cheapness still rules bridges and towers (the Builder's scaffolding list),
            // which is where material gets spent without anyone asking; here someone
            // asked.
            Block candidate = BuiltInRegistries.BLOCK.get(id);
            if (!(candidate.asItem() instanceof net.minecraft.world.item.BlockItem)) {
                return String.format("'%s' is not a block that can be placed "
                        + "from the hand", block);
            }
            withWhat = candidate;
            nameWithWhat = block;
        }

        // Only what really has to change: reviewing what is already right would be
        // walking more for nothing.
        pendingList = new ArrayList<>();
        int x1 = Math.min(cornerA.getX(), cornerB.getX());
        int x2 = Math.max(cornerA.getX(), cornerB.getX());
        int y1 = Math.min(cornerA.getY(), cornerB.getY());
        int y2 = Math.max(cornerA.getY(), cornerB.getY());
        int z1 = Math.min(cornerA.getZ(), cornerB.getZ());
        int z2 = Math.max(cornerA.getZ(), cornerB.getZ());
        for (BlockPos b : BlockPos.betweenClosed(cornerA, cornerB)) {
            // With a blueprint, only the cells the blueprint asks for.
            if (onlyThese != null && !onlyThese.contains(b)) continue;
            // Hollow: the inside of the box is not touched. A cell is inside when it is
            // on none of the six faces.
            if (gap && b.getX() > x1 && b.getX() < x2
                    && b.getY() > y1 && b.getY() < y2
                    && b.getZ() > z1 && b.getZ() < z2) {
                continue;
            }
            var state = mc.level.getBlockState(b);
            boolean isAir = state.isAir();
            if (withWhat == null ? !isAir : state.getBlock() != withWhat) {
                pendingList.add(b.immutable());
            }
        }

        // Filling, first everything is CLEARED and then everything is placed (see the
        // clearing field). Phase 1 is only the cells occupied by another block; if there
        // are none, straight to placing.
        clearing = false;
        if (withWhat != null) {
            List<BlockPos> covered = new ArrayList<>();
            for (BlockPos b : pendingList) {
                if (!mc.level.getBlockState(b).canBeReplaced()) covered.add(b);
            }
            if (!covered.isEmpty()) {
                pendingList = covered;
                clearing = true;
            }
        }
        // Check the pickaxe BEFORE walking. If it does not work, it does not work for any
        // of the 392, and finding that out block by block is wandering all afternoon.
        if (withWhat == null) {
            String missing = whatIsMissingToDig(mc);
            if (missing != null) return missing;
        } else if (slotWith(mc.player, withWhat) < 0) {
            // The same principle for filling: the material is checked before walking, not
            // on arrival. And if it is stored, it says where.
            boolean inBag = false;
            var inv = mc.player.getInventory();
            for (int i = 9; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i).getItem() == withWhat.asItem()) {
                    inBag = true;
                    break;
                }
            }
            return inBag
                    ? String.format("I have %s but in the backpack — wield it "
                            + "first and ask me again", nameWithWhat)
                    : String.format("I do not carry %s on me", nameWithWhat);
        }

        startUp(mc.player);
        return null;
    }

    /** Does it fall inside the job's box (the two corners)? */
    private boolean insideTheBox(BlockPos b) {
        if (cornerA == null || cornerB == null || b == null) return false;
        return b.getX() >= Math.min(cornerA.getX(), cornerB.getX())
                && b.getX() <= Math.max(cornerA.getX(), cornerB.getX())
                && b.getY() >= Math.min(cornerA.getY(), cornerB.getY())
                && b.getY() <= Math.max(cornerA.getY(), cornerB.getY())
                && b.getZ() >= Math.min(cornerA.getZ(), cornerB.getZ())
                && b.getZ() <= Math.max(cornerA.getZ(), cornerB.getZ());
    }

    /** Counters to zero and the job under way. Shared by box and blueprint. */
    private void startUp(LocalPlayer p) {
        // The free zone: while the job lasts, everything inside its box may be broken.
        // stop() removes it, which is where every job ends, well or badly.
        BreakPermissions.freeZone(cornerA, cornerB);
        sort(p.position());
        deeds = 0;
        clearedOnes = 0;
        resetCounters();
        noTool = new ArrayList<>();
        unreachable = new ArrayList<>();
        pass = 1;
        deedsAtPassStart = 0;
        phase = Phase.CHOOSING;
        current = null;
        outcome = null;
        miner.forgetBrokenTool();
        working = true;
    }

    /**
     * Skip reasons to zero. For a blueprint it is done per layer and per review, so the
     * breakdown says why THE LAST attempt failed and not the sum of all.
     */
    private void resetCounters() {
        skipped = 0;
        notArriving = 0;
        unsupported = 0;
        forSlowOnes = 0;
        byTool = 0;
        byPermission = 0;
    }

    /** What gets placed at the end, with everything else already up. */
    private static boolean isFinisher(Block m) {
        return m instanceof DoorBlock || m instanceof TrapDoorBlock
                || m instanceof FenceGateBlock;
    }

    /**
     * Whether the cell belongs to THIS batch: normal layers without finishing touches;
     * the finishing batch only finishing touches.
     */
    private boolean ofTheBatch(BlockPos b) {
        return isFinisher(blueprint.get(b)) == finishingOff;
    }

    /**
     * Loads the lowest layer of the blueprint that still has something to do. If none is
     * left, it is the finishing batch's turn; and if not that either, the blueprint is
     * finished.
     */
    private void nextLayer(LocalPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        Integer y = null;
        for (var e : blueprint.entrySet()) {
            if (!ofTheBatch(e.getKey())) continue;
            if (likeTheBlueprint(e.getKey(), mc.level.getBlockState(e.getKey()))) continue;
            if (y == null || e.getKey().getY() < y) y = e.getKey().getY();
        }
        if (y == null && !finishingOff) {
            // All layers fine: now the doors and other finishing touches, all at once
            // (they are few and do not hide each other).
            finishingOff = true;
            nextLayer(p);
            return;
        }
        if (y == null) {
            finishBlueprint();
            return;
        }
        layer = y;
        reviews = 0;
        pendingList = new ArrayList<>();
        double sx = 0, sz = 0;
        int howMany = 0;
        for (var e : blueprint.entrySet()) {
            if (!ofTheBatch(e.getKey())) continue;
            if (!finishingOff && e.getKey().getY() != layer) continue;
            if (e.getValue() != null) {
                sx += e.getKey().getX() + 0.5;
                sz += e.getKey().getZ() + 0.5;
                howMany++;
            }
            BlockState rightThere = mc.level.getBlockState(e.getKey());
            if (!likeTheBlueprint(e.getKey(), rightThere)) {
                isMine(e.getKey(), rightThere);        // claim its own, if it is
                pendingList.add(e.getKey());
            }
        }
        centerX = howMany > 0 ? sx / howMany : 0;
        centerZ = howMany > 0 ? sz / howMany : 0;
        enclosure.clear();
        for (BlockPos b : pendingList) {
            int n = 0;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos neighbor = b.relative(d);
                if (blueprint.containsKey(neighbor)) continue;
                if (!mc.level.getBlockState(neighbor).canBeReplaced()) n++;
            }
            if (n > 0) enclosure.put(b, n);
        }
        resetCounters();
        noTool = new ArrayList<>();
        unreachable = new ArrayList<>();
        sort(p.position());
        pass = 1;
        deedsAtPassStart = deeds;
        phase = Phase.CHOOSING;
        Logbook.note("fill_job", finishingOff
                ? String.format("finishing touches (doors and such): %d cells", pendingList.size())
                : String.format("layer Y=%d: %d cells to do%s", layer, pendingList.size(),
                        enclosure.isEmpty() ? "" : " (" + enclosure.size() + " against terrain, they go first)"));
    }

    /**
     * The layer review. When nothing is left to do in it, it waits for the server to
     * confirm what was placed and checks cell by cell against the blueprint. What is
     * wrong goes back to the queue and is repeated; if after {@link #REVIEWS_MAX} it is
     * still wrong, it stops and says which cells: it will almost always be the
     * blueprint's fault (a window with nothing to rest on), and building on top of that
     * would only hide it.
     */
    private void reviewing(LocalPlayer p) {
        if (++ticksInPhase < REVIEW_WAIT) return;
        Minecraft mc = Minecraft.getInstance();
        List<BlockPos> bad = new ArrayList<>();
        for (var e : blueprint.entrySet()) {
            if (!ofTheBatch(e.getKey())) continue;
            if (!finishingOff && e.getKey().getY() != layer) continue;
            BlockState rightThere = mc.level.getBlockState(e.getKey());
            if (!likeTheBlueprint(e.getKey(), rightThere)) {
                isMine(e.getKey(), rightThere);
                bad.add(e.getKey());
            }
        }
        if (bad.isEmpty()) {
            if (finishingOff) {
                Logbook.note("fill_job", "finishing touches reviewed: fine");
                finishBlueprint();
                return;
            }
            layersReady++;
            Logbook.note("fill_job", String.format("layer Y=%d reviewed: fine%s",
                    layer, reviews > 0 ? " (after " + reviews + " reviews)" : ""));
            nextLayer(p);
            return;
        }
        if (reviews >= REVIEWS_MAX) {
            StringBuilder whichOnes = new StringBuilder();
            int n = 0;
            for (BlockPos b : bad) {
                if (n++ == 6) { whichOnes.append(", ..."); break; }
                if (n > 1) whichOnes.append(", ");
                whichOnes.append(b.toShortString()).append(" (")
                      .append(nameOf(b)).append(")");
            }
            String reason = finishingOff
                    ? String.format("the finishing touches (doors and such) do not come out right "
                            + "after %d reviews: %d cells wrong: %s%s%s", REVIEWS_MAX,
                            bad.size(), whichOnes, whySkipped(), blueprintSummary())
                    : String.format("layer Y=%d does not come out right after %d "
                            + "reviews: %d cells wrong: %s. I do not go on with the one above%s%s",
                            layer, REVIEWS_MAX, bad.size(), whichOnes, whySkipped(),
                            blueprintSummary());
            // Before giving up, collect what is left over INSIDE the blueprint: the
            // towers and bridges it built to get around. If it just stops, they stay in
            // the middle of the house.
            List<BlockPos> leftovers = new ArrayList<>();
            for (var e : blueprint.entrySet()) {
                if (e.getValue() != null) continue;
                BlockState rightThere = mc.level.getBlockState(e.getKey());
                // Only ITS OWN: terrain it could not clear is not scaffolding, and
                // calling it that was confusing.
                if (!acceptableEmpty(rightThere) && isMine(e.getKey(), rightThere)) {
                    leftovers.add(e.getKey());
                }
            }
            if (leftovers.isEmpty()) {
                stop(reason);
                return;
            }
            Logbook.note("fill_job", String.format("I cannot manage layer Y=%d; "
                    + "I pick up %d leftover blocks inside the blueprint and stop",
                    layer, leftovers.size()));
            cleaning = true;
            layerFailure = reason;
            pendingList = leftovers;
            resetCounters();
            noTool = new ArrayList<>();
            unreachable = new ArrayList<>();
            sort(p.position());
            pass = 1;
            deedsAtPassStart = deeds;
            phase = Phase.CHOOSING;
            return;
        }
        reviews++;
        Logbook.note("fill_job", String.format("review %d of layer Y=%d: %d "
                + "cells wrong, fixing them", reviews, layer, bad.size()));
        pendingList = bad;
        resetCounters();
        noTool = new ArrayList<>();
        unreachable = new ArrayList<>();
        sort(p.position());
        pass = 1;
        deedsAtPassStart = deeds;
        phase = Phase.CHOOSING;
    }

    private void finishBlueprint() {
        stop(String.format("finished: %d blocks of the blueprint placed%s, %d layers "
                + "reviewed and fine%s", deeds - clearedOnes, blueprintSummary(),
                layersReady, clearedOnes > 0
                        ? String.format(" (I cleared %d cells first)", clearedOnes)
                        : ""));
    }

    /** "(34 cobblestone, 80 oak_planks)" or nothing. */
    private String blueprintSummary() {
        if (placedBy.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (var e : placedBy.entrySet()) parts.add(e.getValue() + " " + e.getKey());
        return " (" + String.join(", ", parts) + ")";
    }

    /**
     * What is missing to be able to dig the area, or {@code null} if nothing is.
     *
     * <p>It is enough for <b>a single</b> pending block to be diggable with what it has
     * at hand: a mixed area starts anyway and whatever does not work gets skipped. What
     * must be avoided is the case where <b>none</b> can be, which is when the whole job
     * is a stroll.
     *
     * <p>And if the right tool is stored, it says which and where, because it does not
     * pick it up by itself: automatic selection only looks at the hotbar.
     */
    private String whatIsMissingToDig(Minecraft mc) {
        if (pendingList.isEmpty()) return null;   // already emptied, nothing to ask for
        var p = mc.player;
        var state = mc.level.getBlockState(pendingList.get(0));
        boolean allWithoutPermission = true;
        for (BlockPos b : pendingList) {
            var e = mc.level.getBlockState(b);
            // Its own blocks count too (PlacedBlocks): the Miner already let them
            // through, but this preliminary check did not look at them and refused to
            // start because of two planks the bot itself had placed. And inside the job's
            // box everything goes (the free zone is not set yet, it is set on starting,
            // so the box is checked by hand here): the order on the area already is the
            // permission; the whitelist only rules when breaking to MOVE.
            allWithoutPermission = false;
            if (Miner.toolWorks(p, e)) return null;
            state = e;                       // the last one looked at, for the notice
        }
        // Permissions first: if it cannot break ANYTHING in the area, the problem is not
        // the pickaxe and offering to craft one would mislead.
        if (allWithoutPermission) {
            return String.format("I am not starting: I have no permission to break "
                    + "anything in that area (%s, for example); permissions are "
                    + "granted by the server owner", Miner.nameOf(state));
        }
        String inBackpack = Miner.toolInBackpack(p, state);
        if (inBackpack != null) {
            return String.format("I am not starting: with what I carry in the hotbar "
                    + "%s drops nothing. I have a %s in the backpack — "
                    + "wield it first and ask me again",
                    Miner.nameOf(state), inBackpack);
        }
        return String.format("I am not starting: %s drops nothing with what I carry, "
                + "and I have nothing better stored; a better pickaxe is needed",
                Miner.nameOf(state));
    }

    /**
     * In which order the blocks are tackled. It is not cosmetic: it decides whether the
     * job can be finished or gets stuck halfway.
     *
     * <p><b>Emptying, top to bottom.</b> It is how a human dismantles, and for the same
     * reason: removing the layer above leaves you standing on the next one, so there is
     * always somewhere to stand. Ordering by proximity (as it used to) bites the nearest
     * face of the cube, leaves a thin crust that is not a corridor anyone fits in, and on
     * the second pass nothing can be reached: 42 of 392 and stopped.
     *
     * <p><b>Filling, bottom to top</b>, the usual rule for building: a block needs
     * something to rest on, and the one below must exist first.
     *
     * <p>At equal height, closest first: that way what is seen right away is already
     * progress.
     */
    private void sort(Vec3 me) {
        boolean emptying = emptying() || clearing;
        pendingList.sort((u, v) -> {
            if (u.getY() != v.getY()) {
                return emptying ? Integer.compare(v.getY(), u.getY())
                                : Integer.compare(u.getY(), v.getY());
            }
            // With a blueprint, the most enclosed by terrain first (see `enclosure`).
            if (blueprint != null && !clearing && !cleaning) {
                int cu = enclosure.getOrDefault(u, 0), cv = enclosure.getOrDefault(v, 0);
                if (cu != cv) return Integer.compare(cv, cu);
                // And the most PERIPHERAL first: the corners of a roof are seen from
                // inside along the diagonal only while the edge is not there; the center
                // is always seen. The other way round, 19 of 25 cells of a 5x5 roof went
                // unplaced.
                double pu = Math.pow(u.getX() + 0.5 - centerX, 2)
                        + Math.pow(u.getZ() + 0.5 - centerZ, 2);
                double pv = Math.pow(v.getX() + 0.5 - centerX, 2)
                        + Math.pow(v.getZ() + 0.5 - centerZ, 2);
                if (Math.abs(pu - pv) > 0.01) return Double.compare(pv, pu);
            }
            return Double.compare(u.distToCenterSqr(me), v.distToCenterSqr(me));
        });
    }

    synchronized void stop(String because) {
        BreakPermissions.noFreeZone();
        onlyThese = null;
        blueprint = null;
        cleaning = false;
        finishingOff = false;
        working = false;
        outcome = because;
        current = null;
    }

    synchronized void tick() {
        if (!working) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { stop("I left the world"); return; }
        if (!p.isAlive()) { stop("I was killed while working"); return; }
        if (MasuriumBot.eating()) return;      // a bite is 32 ticks; the job waits

        switch (phase) {
            case CHOOSING -> choose(p);
            case GOING -> going(p);
            case STEPPING_ASIDE -> steppingAside(p);
            case ACTING -> acting(p);
            case REVIEWING -> reviewing(p);
        }
    }

    private void choose(LocalPlayer p) {
        // Re-sorted on every choice, not once per pass: "the closest" is measured from
        // where it is NOW. With the order frozen at the start it lurched from one end of
        // the area to the other as it progressed.
        sort(p.position());

        long from = System.nanoTime();
        while (!pendingList.isEmpty()) {
            current = pendingList.remove(0);
            soughtElsewhere = false;
            timesSteppedAside = 0;
            if (!mustTouch(current)) continue;      // changed in the meantime
            if (inReach(p, current)) {
                phase = Phase.ACTING;
                ticksInPhase = 0;
                return;
            }
            // This runs on the game thread, and searching routes is the only part of the
            // whole job that really costs. It plans until the time budget runs out and
            // continues next tick; what was not looked at goes back to the queue, it is
            // not lost.
            if (System.nanoTime() - from > PLAN_MAX_NS) {
                pendingList.add(0, current);
                return;
            }
            if (goToward(p, current, from)) {
                phase = Phase.GOING;
                ticksInPhase = 0;
                return;
            }
            Logbook.note("fill_job", "I skip a block: " + whyIDidntGo);
            unreachable.add(current);               // not today; maybe later
        }

        // Another pass, if the previous one achieved something.
        // A solid cube is not emptied in one go: to break a block you have to be within
        // 4, and nobody fits inside stone; only the outer face can be reached. But every
        // block removed **opens the spot to stand on for the next one**, so retrying what
        // is left is enough: it eats the cube from the outside in and ends up getting in
        // through its own hole.
        // And it is safe by construction, not by care: only blocks in the marked box are
        // touched, and it only stands where it already can. The area you marked IS the
        // permission; outside it nothing is touched.
        if (!(unreachable.isEmpty() && noTool.isEmpty())
                && deeds > deedsAtPassStart
                && pass < PASSES_MAX) {
            pendingList = unreachable;
            pendingList.addAll(noTool);   // maybe there is something to dig them with now
            noTool = new ArrayList<>();
            unreachable = new ArrayList<>();
            sort(p.position());
            pass++;
            deedsAtPassStart = deeds;
            return;
        }

        // Phase 1 done: the area is cleared (or what is not, counted). Now the list is
        // rebuilt with EVERYTHING left to place and the order switches to bottom-up. What
        // could not be cleared shows up here as an occupied cell and gets a second chance
        // with acting's emergency dig-and-place.
        if (clearing) {
            skipped += unreachable.size();
            notArriving += unreachable.size();
            skipped += noTool.size();
            byTool += noTool.size();
            noTool = new ArrayList<>();
            unreachable = new ArrayList<>();
            clearing = false;
            clearedOnes = deeds;
            if (blueprint != null) {
                Logbook.note("fill_job", "site cleared; now the blueprint, layer by layer");
                nextLayer(p);
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            pendingList = new ArrayList<>();
            for (BlockPos b : BlockPos.betweenClosed(cornerA, cornerB)) {
                // With loose cells, only those: without this filter, after clearing it
                // filled the WHOLE box containing them.
                if (onlyThese != null && !onlyThese.contains(b)) continue;
                if (mc.level.getBlockState(b).getBlock() != withWhat) {
                    pendingList.add(b.immutable());
                }
            }
            sort(p.position());
            pass = 1;
            deedsAtPassStart = deeds;
            Logbook.note("fill_job", String.format(
                    "area cleared; now I place %d of %s",
                    pendingList.size(), nameWithWhat));
            return;
        }

        // With a blueprint, the layer counts as done when there is nothing left to try in
        // it, and then it is REVIEWED, which is not the same as accepting it. What was
        // not reached is not noted as skipped here: the review will see it and try again.
        if (blueprint != null) {
            noTool = new ArrayList<>();
            unreachable = new ArrayList<>();
            if (cleaning) {
                // What could be collected was collected: now it stops, with the reason.
                stop(layerFailure + ". Before stopping I picked up what was left inside "
                      + "the blueprint (my scaffolding)");
                return;
            }
            phase = Phase.REVIEWING;
            ticksInPhase = 0;
            return;
        }

        // Done: whatever survived the last pass cannot be reached, full stop.
        skipped += unreachable.size();
        notArriving += unreachable.size();
        skipped += noTool.size();
        byTool += noTool.size();
        noTool = new ArrayList<>();
        stop(String.format("finished: %d blocks placed at %s%s%s%s",
                deeds - clearedOnes, nameWithWhat,
                clearedOnes > 0
                        ? String.format(" (I cleared %d cells first)", clearedOnes)
                        : "",
                whySkipped(),
                pass > 1 ? String.format(" (in %d passes)", pass) : ""));
    }

    /** The breakdown of why what was skipped was skipped. */
    private String whySkipped() {
        if (skipped == 0) return ", none skipped";
        List<String> reasons = new ArrayList<>();
        if (unsupported > 0) {
            reasons.add(unsupported + " with nothing to rest them against (the area "
                        + "floats in the air)");
        }
        if (notArriving > 0) reasons.add(notArriving + " that I could not reach");
        if (byPermission > 0) {
            reasons.add(byPermission + " that I have no permission to break (that "
                        + "is for the server owner to decide)");
        }
        if (forSlowOnes > 0) reasons.add(forSlowOnes + " that did not give way in time");
        if (byTool > 0) {
            reasons.add(byTool + " that would drop nothing with the "
                        + "tool I carry (it is not that I could not reach)");
        }
        return String.format(", %d skipped: %s", skipped,
                             String.join("; ", reasons));
    }

    private void going(LocalPlayer p) {
        if (inReach(p, current)) {
            walker.stop("I already reach the block");
            phase = Phase.ACTING;
            ticksInPhase = 0;
            return;
        }
        if (!walker.walking()) {
            // It arrived where it planned and still cannot reach. Before giving up on it,
            // a second tile (and only one, or it would go wandering): the world has
            // changed since the first was chosen, because every broken block opens and
            // closes lines of sight.
            if (!soughtElsewhere && goToward(p, current)) {
                soughtElsewhere = true;
                ticksInPhase = 0;
                return;
            }
            Logbook.note("fill_job", "I arrived and still could not reach");
            skipped++;
            notArriving++;
            phase = Phase.CHOOSING;
        }
    }

    /**
     * Getting out of the gap. It exists because GOING counted as arrived whoever was
     * INSIDE the cell (it reaches it, of course) and stopped the walker before it took
     * the step aside: step aside -> going -> "I already reach it" -> acting -> still
     * inside -> step aside, without moving a block. Here reach is not checked: it only
     * waits for the feet to finish.
     */
    private void steppingAside(LocalPlayer p) {
        if (walker.walking()) return;
        phase = Phase.ACTING;
        ticksInPhase = 0;
    }

    private void acting(LocalPlayer p) {
        if (!mustTouch(current)) {                  // already as it should be
            deeds++;
            if (blueprint != null && !clearing && materialOf(current) != null) {
                placedBy.merge(nameOf(current), 1, Integer::sum);
                PlacedBlocks.note(current, nameOf(current));
            }
            phase = Phase.CHOOSING;
            return;
        }
        if (++ticksInPhase > 20 * 20) {
            skipped++;
            forSlowOnes++;
            phase = Phase.CHOOSING;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (materialOf(current) == null || clearing) {
            if (!miner.digging()) {
                // If the tool just broke, the whole job stops and says so; it does not go
                // on by hand pretending. The shovel once broke halfway through a trench
                // and nobody noticed until the bot was seen digging with its fists.
                String broken = miner.brokenTool();
                if (broken != null) {
                    stop(String.format("my %s broke halfway through the work; "
                            + "I get another (or give me one) and ask me again",
                            broken));
                    return;
                }
                String failure = miner.begin(current);
                if (failure == null) return;
                // The Miner checks things not checked here (that it is really visible,
                // that the tool makes it drop something). If it says no, there is still
                // another spot to look for from where it would work: what someone would
                // do when an overhang hides what they want to dig. Only once per block,
                // or it would wander in circles.
                if (!soughtElsewhere && goToward(p, current)) {
                    soughtElsewhere = true;
                    phase = Phase.GOING;
                    ticksInPhase = 0;
                    return;
                }
                // This is NOT "I could not reach": it did reach. Reporting it like that
                // sent people looking for the failure in the path finder when the problem
                // was the pickaxe.
                Logbook.note("fill_job", "I could not dig: " + failure);
                // A "no" from the WHITELIST is neither route nor tool, and reporting it
                // as "I could not reach" sent the owner looking for the failure where it
                // was not: what is needed is their permission.
                if (failure.contains("permission")) {
                    skipped++;
                    byPermission++;
                    phase = Phase.CHOOSING;
                    return;
                }
                if (Miner.toolWorks(p, mc.level.getBlockState(current))) {
                    skipped++;
                    notArriving++;
                } else {
                    // Because of the TOOL it is not a final no: the pickaxe may arrive
                    // mid-job (given, crafted, taken from the backpack) and then those
                    // blocks can be dug. They used to be discarded on the spot and never
                    // came back: a bot cleared the area with the pickaxe already in hand
                    // and still left 183 stones it had rejected before having it. Now
                    // they wait for the next pass, which only happens if there was
                    // progress.
                    noTool.add(current);
                }
                phase = Phase.CHOOSING;
            }
        } else {
            // Filling is also REPLACING. An area at ground level has grass, dirt,
            // whatever, and placing against an occupied cell does not fail with a
            // message: the click does nothing, the 20 seconds run out and the cell is
            // skipped (it looked like it "tries to break something but cannot"). If the
            // cell holds another block, it is dug first and the next tick places.
            var occupies = mc.level.getBlockState(current);
            if (!occupies.canBeReplaced()) {
                if (!miner.digging()) {
                    String failure = miner.begin(current);
                    if (failure != null) {
                        Logbook.note("fill_job",
                                "I could not remove what was there: " + failure);
                        skipped++;
                        notArriving++;
                        phase = Phase.CHOOSING;
                    }
                }
                return;
            }
            // If I am INSIDE the cell, get out first: the server rejects placing a block
            // where there is a body, but the client draws it anyway, a ghost block that
            // disappears next tick. That is how 8 cobblestone were "placed" that never
            // existed: the count read the client's prediction before the correction.
            LocalPlayer p2 = mc.player;
            if (p2.getBoundingBox().intersects(
                    new net.minecraft.world.phys.AABB(current))) {
                // Getting out of the gap needs no line of sight: a walkable neighbour
                // tile is enough. Requiring sight from there is what left the bot planted
                // inside its own gap ("I am in the way myself and found nowhere to step
                // aside"). On arrival, GOING checks again whether it reaches and, if not,
                // looks for a spot as usual.
                if (++timesSteppedAside <= 3 && stepAside(p2, current)) {
                    phase = Phase.STEPPING_ASIDE;
                    ticksInPhase = 0;
                    return;
                }
                if (!soughtElsewhere && goToward(p2, current)) {
                    soughtElsewhere = true;
                    phase = Phase.GOING;
                    ticksInPhase = 0;
                    return;
                }
                Logbook.note("fill_job", "I could not place it: I am in the way myself "
                        + "and I found nowhere to step aside");
                skipped++;
                notArriving++;
                phase = Phase.CHOOSING;
                return;
            }
            // With the GUARD inside the cell it cannot place either: ask it to make way
            // (its body steps aside without dropping the escort) and wait.
            var guard = Guards.guardAt(mc, new net.minecraft.world.phys.AABB(current));
            if (guard != null) {
                Guards.moveAside(guard, net.minecraft.world.phys.Vec3.atCenterOf(current), null,
                        2.0, "going to place a block at " + current.toShortString());
                return;
            }
            // Placing needs a solid neighbour to rest against.
            Direction support = solidNeighbor(mc, current);
            if (support == null) {
                // In Minecraft a block is not placed in the void: you click on another
                // block's face. An area floating in the air, with nothing around, is
                // infeasible and has to be said so.
                skipped++;
                unsupported++;
                phase = Phase.CHOOSING;
                return;
            }
            // One click every 10 ticks, not one per tick: between clicks the server
            // confirms or reverts, and the check above already reads the truth instead of
            // the prediction.
            if (ticksInPhase % 10 != 1) return;
            // It places WHAT WAS ASKED, not just any scaffolding: if dirt was asked for
            // and the builder placed cobblestone, the cell would stay "pending" forever,
            // placed and all, never the requested block. With a blueprint, the material
            // is THIS cell's; the hand switches by itself on each placement
            // (Builder.placeOf wields the slot).
            Block material = materialOf(current);
            String name = nameOf(current);
            if (blueprint != null) nameWithWhat = name;
            int slot = slotWith(mc.player, material);
            if (slot < 0) {
                // Restock from the backpack before giving up: if the errand is placing
                // cobblestone and it carries more in the backpack, bringing it up is
                // CONTINUING the order, not deciding for anyone. It stopped with 30
                // placed and 187 stored, and that is not running out of material.
                slot = MasuriumBot.takeFromBackpack(mc.player, name);
            }
            if (slot < 0) {
                stop(String.format("I ran out of %s (not even in the backpack)%s",
                        name, blueprint != null ? " halfway through the blueprint" + blueprintSummary() : ""));
                return;
            }
            String failure = Builder.placeOf(current, support, slot);
            if (failure != null) { stop("I could not fill: " + failure); }
        }
    }

    /** The HOTBAR slot holding that block, or -1. Placing requires the hotbar. */
    private static int slotWith(LocalPlayer p, Block block) {
        var item = block.asItem();
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).getItem() == item) return i;
        }
        return -1;
    }

    private boolean mustTouch(BlockPos b) {
        var state = Minecraft.getInstance().level.getBlockState(b);
        // While clearing, a cell is done when it is no longer in the way (air, tall
        // grass...): comparing it with the final block would keep it "pending" forever
        // within this phase.
        if (clearing) return !state.canBeReplaced();
        Block m = materialOf(b);
        if (m == null) return blueprint != null ? !acceptableEmpty(state) : !state.isAir();
        return state.getBlock() != m;
    }

    /**
     * Whether that block can be worked from where it stands: close <b>and in sight</b>.
     *
     * <p>It used to measure only the distance, and there was the bug that left a cube
     * with a pit in it. Inside a solid mass, almost everything within 4 blocks is
     * <b>hidden by the mass itself</b>: the Miner (which does check visibility) rejected
     * it, and the FillWorker counted that rejection as "I could not reach" instead of
     * moving to a spot where it would be visible. It got 59 of 392, and the shape left
     * was not a layer but a hole.
     */
    private static boolean inReach(LocalPlayer p, BlockPos b) {
        return p.getEyePosition().distanceTo(Vec3.atCenterOf(b)) <= REACH
                && visibleFrom(p, p.getEyePosition(), b);
    }

    /**
     * Whether the block can be seen from eyes placed at {@code eyes}.
     *
     * <p>It serves two different things: checking what is seen from here, and (without
     * having moved yet) whether it would be seen from a candidate tile. The second is
     * what turns "I cannot see it" into "I move", which is exactly what a player does.
     */
    /**
     * Like {@link #visibleFrom}, but also from {@link #VIEW_MARGIN} to each side: what is
     * only visible from the exact center of the tile will not be visible on arrival. The
     * offset points stay within the same tile (free, since one can stand there), so none
     * starts inside a block.
     */
    private static boolean visibleWithMargin(LocalPlayer p, Vec3 eyes, BlockPos b) {
        if (!visibleFrom(p, eyes, b)) return false;
        double m = VIEW_MARGIN;
        return visibleFrom(p, eyes.add(m, 0, 0), b)
                && visibleFrom(p, eyes.add(-m, 0, 0), b)
                && visibleFrom(p, eyes.add(0, 0, m), b)
                && visibleFrom(p, eyes.add(0, 0, -m), b);
    }

    private static boolean visibleFrom(LocalPlayer p, Vec3 eyes, BlockPos b) {
        BlockHitResult r = p.level().clip(new ClipContext(
                eyes, Vec3.atCenterOf(b),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return r.getType() == HitResult.Type.MISS || r.getBlockPos().equals(b);
    }

    private static Direction solidNeighbor(Minecraft mc, BlockPos b) {
        for (Direction d : Direction.values()) {
            BlockPos neighbor = b.relative(d);
            if (!mc.level.getBlockState(neighbor).getCollisionShape(
                    mc.level, neighbor).isEmpty()) {
                return d;
            }
        }
        return null;
    }

    /**
     * One step to any walkable neighbour tile other than the block's, closest first. Only
     * to get off a gap.
     */
    private boolean stepAside(LocalPlayer p, BlockPos target) {
        Minecraft mc = Minecraft.getInstance();
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MasuriumBot.whereAmI(world, p);
        List<Route.Point> neighborTiles = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    int x = here.x() + dx, y = here.y() + dy, z = here.z() + dz;
                    if (x == target.getX() && y == target.getY()
                            && z == target.getZ()) {
                        continue;
                    }
                    if (world.canStand(x, y, z)) {
                        neighborTiles.add(new Route.Point(x, y, z));
                    }
                }
            }
        }
        neighborTiles.sort((u, v) -> Double.compare(
                distanceSq(u, here), distanceSq(v, here)));
        for (Route.Point c : neighborTiles) {
            Route.Result res = Route.search(world, here, c, new Route.Options(
                    MasuriumBot.safeFall(p.getHealth()), 500, false));
            if (!res.hasRoute()) continue;
            if (walker.follow(res.steps(), d -> null, FINE_ARRIVAL) == null) {
                Logbook.note("fill_job", "I step away from the gap");
                return true;
            }
        }
        return false;
    }

    /** Sends the walker to a spot from which the block can be reached. */
    private boolean goToward(LocalPlayer p, BlockPos target) {
        return goToward(p, target, System.nanoTime());
    }

    /** @param from start of the {@link #PLAN_MAX_NS} budget */
    private boolean goToward(LocalPlayer p, BlockPos target, long from) {
        Minecraft mc = Minecraft.getInstance();
        ClientWorld world = new ClientWorld(mc.level);
        // Where it leaves from matters as much as where it goes: standing at the edge of
        // its own pillar, floor(position) gave an empty tile and the path finder would
        // not even start. See MasuriumBot.whereAmI.
        Route.Point here = MasuriumBot.whereAmI(world, p);
        // First the tiles from which the block would be reached are gathered, and sorted
        // by proximity. Searching a route to each would launch hundreds of ~10 ms
        // searches ON the game thread: seconds of stutter for anyone playing.
        List<Route.Point> candidateSet = new ArrayList<>();

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                // Up to 4 below, not 1: a ceiling is dug from the floor. With the bottom
                // of the cube emptied, the blocks left above can only be reached from the
                // floor, 3-4 below, and with the short window that tile was not even
                // considered: 254 skips of "not a single tile it can be seen from" with
                // the floor cleared right below. The visibility and reach filters already
                // decide whether it is valid; the window only decides what gets LOOKED
                // at.
                for (int dy = -4; dy <= 2; dy++) {
                    int x = target.getX() + dx, y = target.getY() + dy,
                        z = target.getZ() + dz;
                    if (!world.canStand(x, y, z)) continue;
                    // Never the target cell itself: to PLACE there it must not be inside
                    // it. A block is not placed where the bot has its feet; the server
                    // rejects it and the client draws it anyway (a ghost block that
                    // "disappears").
                    if (x == target.getX() && y == target.getY()
                            && z == target.getZ()) {
                        continue;
                    }
                    // That it really is reachable from there, eyes included.
                    double d = Math.sqrt(
                            Math.pow(x + 0.5 - (target.getX() + 0.5), 2)
                            + Math.pow(y + 1.6 - (target.getY() + 0.5), 2)
                            + Math.pow(z + 0.5 - (target.getZ() + 0.5), 2));
                    if (d > REACH - 0.4) continue;
                    // And that it can be seen from there. Without this it walked to a
                    // tile next to the block from which it still could not see it, and
                    // the trip was useless.
                    if (!visibleWithMargin(p, new Vec3(x + 0.5, y + 1.6, z + 0.5),
                                       target)) {
                        continue;
                    }
                    candidateSet.add(new Route.Point(x, y, z));
                }
            }
        }
        if (candidateSet.isEmpty()) {
            // When this happens with walkable tiles around, the usual cause is that the
            // block is HIDDEN by terrain OUTSIDE the marked area, and outside the area
            // nothing is touched: that is the rule. It is not a routing failure: it is
            // the permission working, and it has to be said so.
            whyIDidntGo = "nothing sees it: it may be covered by blocks outside "
                    + "the area, and outside the area I do not touch";
            return false;
        }
        // First the one closest TO THE BLOCK, and at equal distance the one closest to
        // the bot. It used to be ordered only by closeness to the bot, which picked the
        // far tile with the grazing angle, the one that barely sees the block; the one
        // next to it sees it from above and does not miss.
        Route.Point goal = new Route.Point(target.getX(), target.getY(),
                                         target.getZ());
        candidateSet.sort((u, v) -> {
            int c = Double.compare(distanceSq(u, goal), distanceSq(v, goal));
            return c != 0 ? c : Double.compare(distanceSq(u, here),
                                               distanceSq(v, here));
        });

        // The acceptable fall depends on the health it has, not on a fixed number: with
        // full health it drops off a pillar without trouble, with two hearts it does not.
        // It may build a tower to get there, and that is the only way to empty a solid
        // mass from outside: to peel the top layer you have to be ON TOP, and a smooth
        // 7-high wall cannot be climbed; without this it got 31 of 392 and the central
        // column was never touched.
        // It is a wider permission than the rest of the job: the pillar goes OUTSIDE the
        // marked area, so "the box is the permission" no longer holds here. What bounds
        // it is the material: the Builder only spends scaffolding (dirt, stone,
        // cobblestone, planks), never anything valuable, and a dirt pillar can be seen
        // and removed. The node budget grows with distance. Short (8,000) for local work:
        // the most expensive tower climb measured spent 2,463, and the unreachable always
        // burns the whole budget, so cheap = giving up fast.
        // Far away it needs MUCH more than it seems, and the reason is in the heuristic:
        // it only counts horizontal distance (on purpose: inflated height broke falls). A
        // goal HIGH UP behind a wall is 17 towers at cost 6 = 102 the estimate does not
        // see coming, so A* explores a whole ellipse of "cheap" plain before accepting to
        // pay the climb: ~25-30k nodes measured against a solid mass of logs. With 24,000
        // it failed by a hair; 60,000 is plenty, and it is only paid in full when there
        // is NO route (~200 ms, one slow tick). As soon as a route comes out, the bot
        // walks and everything becomes local again.
        int far = Math.abs(here.x() - target.getX())
                + Math.abs(here.y() - target.getY())
                + Math.abs(here.z() - target.getZ());
        Route.Options op = new Route.Options(
                MasuriumBot.safeFall(p.getHealth()),
                far > 20 ? 60_000 : 8_000, true);
        int attempts = 0;
        String lastFailure = "";
        for (Route.Point c : candidateSet) {
            if (++attempts > 8) break;
            // The budget rules in here too: a single block with 8 unreachable candidates
            // would cost ~800 ms on its own. At least one is always tried, so "I ran out
            // of time" is not confused with "there was no route".
            if (attempts > 1 && System.nanoTime() - from > PLAN_MAX_NS) {
                break;
            }
            Route.Result res = Route.search(world, here, c, op);
            if (!res.hasRoute()) { lastFailure = res.reason(); continue; }
            String negative = walker.follow(res.steps(), d -> null, FINE_ARRIVAL);
            if (negative == null) return true;
            lastFailure = "the walker said: " + negative;
        }
        // Plan B: if it is far, do not solve the whole trip at once; get close on foot
        // first and leave the climb for when it is a local problem. A goal high up and
        // far away floods A* no matter what (the heuristic does not see height): from
        // spawn not even 60,000 nodes were enough. Two small searches do fit.
        if (far > 30 && approachMe(p, world, here, target)) {
            return true;
        }
        whyIDidntGo = String.format("%d tiles in sight, I tried %d, "
                + "none worked: %s", candidateSet.size(),
                Math.min(attempts, candidateSet.size()), lastFailure);
        return false;
    }

    /**
     * Walks to the foot of the area, without building. It does not leave the bot in
     * working position: it leaves it where working is a local problem; the next choice
     * plans the same block again from up close, and up close the climb costs little (33k
     * nodes measured from the base of the wall of logs, against a budget that was not
     * enough from afar).
     */
    private boolean approachMe(LocalPlayer p, ClientWorld world,
                              Route.Point here, BlockPos target) {
        // Columns around the area, looking for walkable ground at any height below: the
        // foot of the wall, the hillside, whatever there is.
        List<Route.Point> bases = new ArrayList<>();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                if (Math.abs(dx) < 2 && Math.abs(dz) < 2) continue;
                int x = target.getX() + dx, z = target.getZ() + dz;
                for (int y = target.getY() + 2;
                        y >= target.getY() - 40; y--) {
                    if (world.canStand(x, y, z)) {
                        bases.add(new Route.Point(x, y, z));
                        break;
                    }
                }
            }
        }
        // From the bot's side: approach from where it already is, do not go around.
        bases.sort((u, v) -> Double.compare(
                distanceSq(u, here), distanceSq(v, here)));

        Route.Options onFoot = new Route.Options(
                MasuriumBot.safeFall(p.getHealth()), 12_000, false);
        int tried = 0;
        for (Route.Point b : bases) {
            if (++tried > 3) break;
            Route.Result res = Route.search(world, here, b, onFoot);
            if (res.hasRoute()
                    && walker.follow(res.steps(), d -> null) == null) {
                Logbook.note("fill_job", String.format(
                        "far (%d): first I get closer on foot to %d %d %d",
                        manhattanDistance(here, target), b.x(), b.y(), b.z()));
                return true;
            }
        }
        return false;
    }

    private static int manhattanDistance(Route.Point a, BlockPos b) {
        return Math.abs(a.x() - b.getX()) + Math.abs(a.y() - b.getY())
                + Math.abs(a.z() - b.getZ());
    }

    private static double distanceSq(Route.Point a, Route.Point b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    synchronized String state() {
        if (!working) {
            return String.format(
                    "{\"working\":false,\"outcome\":\"%s\",\"deeds\":%d,"
                    + "\"skipped\":%d}",
                    Request.escape(outcome), deeds, skipped);
        }
        String ofTheBlueprint = blueprint == null ? "" : String.format(
                ",\"blueprint\":true,\"clearing\":%b,\"layer\":%d,\"reviews\":%d,"
                + "\"layers_done\":%d,\"cells\":%d",
                clearing, layer, reviews, layersReady, blueprint.size());
        return String.format(
                "{\"working\":true,\"with\":\"%s\",\"deeds\":%d,\"skipped\":%d,"
                + "\"remaining\":%d,\"phase\":\"%s\"%s}",
                nameWithWhat, deeds, skipped, pendingList.size(),
                phase.name().toLowerCase(), ofTheBlueprint);
    }

    synchronized boolean working() {
        return working;
    }
}

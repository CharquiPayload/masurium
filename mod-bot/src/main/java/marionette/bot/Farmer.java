package marionette.bot;

import marionette.common.Logbook;
import marionette.common.Request;
import marionette.common.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Items;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.InteractionHand;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Crops: tilling, sowing, harvesting and resowing.
 *
 * <p>Two jobs. <b>Sow</b>: on a plot of grass or dirt, till each tile with the hoe (a
 * click on the top face) and plant the seed on it; the plot is noted as the bot's own
 * farm. <b>Harvest</b>: what is ripe around is broken (one click: crops have no
 * hardness), the tile is stepped on to collect what dropped and the same crop is sown
 * again, so the farm does not die. Harvesting on its own only touches its own farms;
 * other people's, only if someone asks.
 *
 * <p>Water: without water within four blocks tilled soil dries out and the seeds pop off.
 * It is checked before tilling, and said.
 */
final class Farmer {

    enum Mode { SOW, HARVEST, WATER, GATHER }
    private enum Phase { CHOOSING, GOING, ACTING }
    /** Side of the irrigation module: one water block hydrates 4 on each side. */
    private static final int MODULE = 9;

    /** Reach from the eyes to touch a tile. */
    private static final double REACH = 4.5;
    private static final int CELLS_MAX = 400;
    static final int RADIUS_MAX = 24;
    /** Ticks of patience per tile before skipping it. */
    private static final int PATIENCE = 20 * 10;
    private static final int EVERY_ACTION = 8;

    /** crop (block) -> seed (item) it is resown with. */
    static final Map<String, String> SEED_OF = Map.of(
            "wheat", "wheat_seeds", "carrots", "carrot",
            "potatoes", "potato", "beetroots", "beetroot_seeds");
    static final Set<String> SEEDS = Set.of(SEED_OF.values().toArray(new String[0]));

    /** A farm of its own: corner, size and seed. */
    record Farm(BlockPos corner, int width, int length, String seed) {
        boolean contains(BlockPos b) {
            return b.getX() >= corner.getX() && b.getX() < corner.getX() + width
                    && b.getZ() >= corner.getZ() && b.getZ() < corner.getZ() + length
                    && Math.abs(b.getY() - corner.getY()) <= 2;
        }
    }

    private final Walker walker;
    private final Miner miner;
    private final FillWorker fillWorker;
    /** SOW: while the FillWorker flattens the site, the Farmer waits. */
    private boolean flattening;
    private final List<Farm> farms = new ArrayList<>();
    /** Irrigation centers still to dig and fill (SOW). */
    private List<BlockPos> pools = new ArrayList<>();
    private BlockPos source;          // WATER: the chosen source
    /** GATHER: which blocks, which item I count, how many more I want. */
    private Set<net.minecraft.world.level.block.Block> targets = Set.of();
    private String targetName = "";
    private String itemMeta;          // null = I count broken blocks
    private int goal, countedBefore;
    private int bucketsBefore;

    private Mode mode;
    private String seed;
    private List<BlockPos> cells = new ArrayList<>();   // SOW: the ground; HARVEST: the crop
    private BlockPos current;
    private Phase phase = Phase.CHOOSING;
    private int ticksInPhase, actionTicks;
    private boolean went;
    private int deeds, skipped;
    private final Map<String, Integer> resown = new TreeMap<>();
    private boolean working;
    private String outcome = "I have not farmed yet";

    Farmer(Walker walker, Miner miner, FillWorker fillWorker) {
        this.walker = walker;
        this.miner = miner;
        this.fillWorker = fillWorker;
        loadFarms();
    }

    // ------------------------------------------------------------ own farms

    private static Path file() {
        return ServerIdentity.file("farms");
    }

    private void loadFarms() {
        try {
            if (!Files.exists(file())) return;
            for (String line : Files.readAllLines(file())) {
                String[] t = line.trim().split(",");
                if (t.length < 6) continue;
                farms.add(new Farm(new BlockPos(Integer.parseInt(t[0]), Integer.parseInt(t[1]),
                        Integer.parseInt(t[2])), Integer.parseInt(t[3]), Integer.parseInt(t[4]), t[5]));
            }
        } catch (Exception e) {
            Logbook.note("farm", "could not read my fields: " + e.getMessage());
        }
    }

    private void saveFarms() {
        try {
            Files.createDirectories(file().getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Fields I harvest and resow alone: x,y,z,width,length,seed");
            for (Farm h : farms) {
                lines.add(String.format("%d,%d,%d,%d,%d,%s", h.corner().getX(), h.corner().getY(),
                        h.corner().getZ(), h.width(), h.length(), h.seed()));
            }
            Files.write(file(), lines);
        } catch (Exception e) {
            Logbook.note("farm", "could not save my fields: " + e.getMessage());
        }
    }

    /** Notes a farm as mine (sown by me, or allowed by someone). */
    synchronized void aim(Farm h) {
        farms.removeIf(o -> o.corner().equals(h.corner()));
        farms.add(h);
        saveFarms();
        Places.remember("farm", h.corner(), SEED_OF.entrySet().stream()
                .filter(e -> e.getValue().equals(h.seed())).map(Map.Entry::getKey)
                .findFirst().orElse(h.seed()));
    }

    /** Someone else's farm I am ALLOWED to harvest: corner to corner. */
    synchronized String allow(BlockPos a, BlockPos b, String seed) {
        int x1 = Math.min(a.getX(), b.getX()), x2 = Math.max(a.getX(), b.getX());
        int z1 = Math.min(a.getZ(), b.getZ()), z2 = Math.max(a.getZ(), b.getZ());
        int width = x2 - x1 + 1, length = z2 - z1 + 1;
        if (width * length > CELLS_MAX) {
            return String.format("a %dx%d field is too much (max %d tiles)", width, length, CELLS_MAX);
        }
        aim(new Farm(new BlockPos(x1, Math.min(a.getY(), b.getY()), z1), width, length,
                SEEDS.contains(seed) ? seed : "wheat_seeds"));
        return null;
    }

    synchronized List<Farm> farms() {
        return new ArrayList<>(farms);
    }

    /**
     * Water (source or flowing) within 4 blocks horizontally, at its height or one above?
     */
    private static boolean isWaterNear(Minecraft mc, BlockPos b) {
        for (BlockPos q : BlockPos.betweenClosed(b.offset(-4, 0, -4), b.offset(4, 1, 4))) {
            if (mc.level.getFluidState(q).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- start

    /** @return null if it started, or the reason */
    synchronized String beginSowing(BlockPos corner, int width, int length, String seed) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (working) return "I am already at the field; wait or stop";
        if (!SEEDS.contains(seed)) {
            return "I do not know how to sow '" + seed + "': I know wheat_seeds, carrot, potato or beetroot_seeds";
        }
        if (width < 1 || length < 1 || width * length > CELLS_MAX) {
            return String.format("a %dx%d field no: at most %d tiles", width, length, CELLS_MAX);
        }
        if (hoeSlot(p) < 0) {
            return "I carry no hoe: craft one (2 sticks and 2 planks, cobblestone or iron) and ask me again";
        }
        int seeds = MarionetteBot.quantityCarried(p, seed);
        if (seeds == 0) return "I do not carry " + seed + " to sow";

        // FIRST IT FLATTENS: the whole site at the corner's height, with dirt in the
        // holes and whatever is in the way above removed (two high). The FillWorker does
        // it with a blueprint, like a house.
        List<BlockPos> list = new ArrayList<>();
        Map<BlockPos, String> solar = new java.util.LinkedHashMap<>();
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < length; dz++) {
                BlockPos ground = corner.offset(dx, 0, dz);
                list.add(ground);
                BlockState s = mc.level.getBlockState(ground);
                boolean dirt = s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT)
                        || s.is(Blocks.FARMLAND) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT);
                if (!dirt) solar.put(ground, "dirt");
                for (int dy = 1; dy <= 2; dy++) {
                    BlockPos up = ground.above(dy);
                    if (!mc.level.getBlockState(up).canBeReplaced()) solar.put(up, "air");
                }
            }
        }
        // IRRIGATION: one water block hydrates 4 on each side, so the plot is split into
        // 9x9 modules with the water in the center; otherwise the crops dry out. Where
        // there is water at hand already none is placed; otherwise a water bucket is
        // needed per module, and without buckets it says so. The bot places the water
        // ITSELF: a pool in the center of each 9x9, always, without trusting spilled
        // water around. It is only skipped if that tile already holds a source block.
        List<BlockPos> pools = new ArrayList<>();
        for (int mx = 0; mx < width; mx += MODULE) {
            for (int mz = 0; mz < length; mz += MODULE) {
                int cx = corner.getX() + Math.min(mx + MODULE / 2, width - 1);
                int cz = corner.getZ() + Math.min(mz + MODULE / 2, length - 1);
                BlockPos center = new BlockPos(cx, corner.getY(), cz);
                if (!mc.level.getFluidState(center).isSource()) pools.add(center);
                solar.remove(center);   // the pool is dug, not filled
            }
        }
        int buckets = MarionetteBot.quantityCarried(p, "water_bucket");
        if (buckets < pools.size()) {
            return String.format("to water a %dx%d field %d block(s) of "
                    + "water are needed (one per 9x9) and I carry %d water bucket(s): fill buckets "
                    + "at a source (collect_water) or make the field next to water",
                    width, length, pools.size(), buckets);
        }
        // Pools are not sown.
        list.removeAll(pools);
        this.pools = pools;
        // The flattening is handed to the FillWorker, which counts the dirt and refuses
        // if there is not enough; the sowing waits for it to finish.
        this.flattening = false;
        if (!solar.isEmpty()) {
            String problem = fillWorker.beginBlueprint(solar);
            if (problem != null) return "I cannot flatten the site: " + problem;
            this.flattening = true;
            Logbook.note("farm", String.format("flattening the site: %d cells (dirt in the holes, obstacles out)",
                    solar.size()));
        }
        if (seeds < list.size()) {
            list = new ArrayList<>(list.subList(0, seeds));
        }
        this.mode = Mode.SOW;
        this.seed = seed;
        List<BlockPos> order = new ArrayList<>(pools);
        order.addAll(list);
        this.cells = order;
        aim(new Farm(corner.immutable(), width, length, seed));
        startUp();
        Logbook.note("farm", String.format("sowing %d of %s from %s",
                list.size(), seed, corner.toShortString()));
        return null;
    }

    /** @param onlyMine only what is ripe inside my farms (harvesting on its own) */
    synchronized String beginHarvest(int radius, boolean onlyMine) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (working) return "I am already at the field; wait or stop";
        List<BlockPos> list = ripe(mc, p, Math.min(Math.max(radius, 1), RADIUS_MAX), onlyMine);
        if (list.isEmpty()) return "I see nothing ripe around here";
        this.mode = Mode.HARVEST;
        this.seed = null;
        this.cells = list;
        startUp();
        Logbook.note("farm", String.format("harvesting %d ripe crops", list.size()));
        return null;
    }

    /**
     * Fills buckets at an INFINITE source (a pool of at least 2x2, or three in a row: a
     * source block with two sources next to it refills itself).
     */
    synchronized String beginWater(int radius) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (working) return "I am already at the field; wait or stop";
        if (MarionetteBot.quantityCarried(p, "bucket") == 0) {
            return "I carry no empty buckets: they are crafted with 3 iron ingots";
        }
        BlockPos me = p.blockPosition();
        BlockPos best = null;
        int r = Math.min(Math.max(radius, 4), 48);
        for (BlockPos b : BlockPos.betweenClosed(me.offset(-r, -6, -r), me.offset(r, 6, r))) {
            if (!isInfiniteSource(mc, b)) continue;
            if (best == null || b.distSqr(me) < best.distSqr(me)) best = b.immutable();
        }
        if (best == null) return "I see no infinite water source (a 2x2 pool) within " + r + " blocks";
        this.mode = Mode.WATER;
        this.source = best;
        this.cells = new ArrayList<>(List.of(best));
        this.bucketsBefore = MarionetteBot.quantityCarried(p, "water_bucket");
        startUp();
        Logbook.note("farm", "going for water to " + best.toShortString());
        return null;
    }

    private static boolean isInfiniteSource(Minecraft mc, BlockPos b) {
        FluidState f = mc.level.getFluidState(b);
        if (!f.isSource() || !f.is(FluidTags.WATER)) return false;
        int neighborTiles = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            FluidState v = mc.level.getFluidState(b.relative(d));
            if (v.isSource() && v.is(FluidTags.WATER)) neighborTiles++;
        }
        return neighborTiles >= 2;
    }

    /** Cutting GRASS for wheat seeds: the special case of gathering. */
    synchronized String beginSeeds(int howMany, int radius) {
        return beginGather(List.of("short_grass", "tall_grass", "fern"),
                "wheat_seeds", howMany, radius);
    }

    /**
     * GATHER, the generic tool: break every block of those ids around (with the Miner:
     * permissions, tool and line of sight as usual) and step on each tile to collect what
     * drops, until carrying {@code quantity} more of {@code item} (or, without an item,
     * until breaking that many).
     *
     * <p>ORES are left out on purpose: looking at blocks all around sees through rock,
     * and searching ores that way would be X-ray. That belongs to `dig` and
     * `strip_mine_start`, which go after what can be seen.
     */
    synchronized String beginGather(List<String> ids, String item, int quantity, int radius) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (working) return "I am already gathering; wait or stop";
        Set<net.minecraft.world.level.block.Block> iWant = new java.util.HashSet<>();
        List<String> names = new ArrayList<>();
        for (String id : ids) {
            String cleanOne = id.trim().toLowerCase().replace("minecraft:", "");
            if (cleanOne.isEmpty()) continue;
            if (isOre(cleanOne)) {
                return "ores do not go through here (it would be looking through rock): "
                        + "for " + cleanOne + " use dig or strip_mine_start";
            }
            var rl = net.minecraft.resources.ResourceLocation.tryParse("minecraft:" + cleanOne);
            if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
                return "I do not know the block '" + cleanOne + "'; ids go in English";
            }
            iWant.add(BuiltInRegistries.BLOCK.get(rl));
            names.add(cleanOne);
        }
        if (iWant.isEmpty()) return "tell me which block to break";
        int r = Math.min(Math.max(radius, 4), RADIUS_MAX);
        List<BlockPos> list = new ArrayList<>();
        BlockPos me = p.blockPosition();
        // Only what is IN SIGHT: with some face open to air (or water, or grass). Looking
        // at the whole cube sees through the ground, and with "gather stone" in a meadow
        // the list was the BURIED rock: it went to each tile, arrived a meter and a half
        // from a block it could not even see, skipped it and moved to the next: 67 skips,
        // 0 done, going back and forth for five minutes. Buried rock is for digging or
        // going down.
        int buried = 0;
        for (BlockPos b : BlockPos.betweenClosed(me.offset(-r, -4, -r), me.offset(r, 6, r))) {
            if (!iWant.contains(mc.level.getBlockState(b).getBlock())) continue;
            if (!exposed(mc, b)) { buried++; continue; }
            list.add(b.immutable());
        }
        if (list.isEmpty()) {
            return "I see no " + String.join("/", names) + " in sight within " + r + " blocks"
                    + (buried > 0 ? String.format(" (there are %d buried, with no face "
                            + "to the air: that is for dig or dig_down_to, not for gather)",
                            buried) : "");
        }
        // Logs: only TREE logs. A log cabin is logs too, and chopping it down is not
        // gathering wood. A log belongs to a tree if its group of logs touches leaves,
        // touches no building and was not placed by me. The group is checked once and
        // holds for all its logs.
        boolean hasLogs = iWant.stream().anyMatch(b -> b.defaultBlockState().is(net.minecraft.tags.BlockTags.LOGS));
        int discardedOnes = 0;
        if (hasLogs) {
            Map<BlockPos, Boolean> verdict = new java.util.HashMap<>();
            List<BlockPos> onlyTrees = new ArrayList<>();
            for (BlockPos b : list) {
                if (!mc.level.getBlockState(b).is(net.minecraft.tags.BlockTags.LOGS)) { onlyTrees.add(b); continue; }
                Boolean v = verdict.get(b);
                if (v == null) v = isTreePart(mc, b, verdict);
                if (v) onlyTrees.add(b); else discardedOnes++;
            }
            list = onlyTrees;
            if (list.isEmpty()) {
                return String.format("I see %d logs but none belongs to a tree (no leaves, or "
                        + "attached to a building): I do not chop houses", discardedOnes);
            }
        }
        list.sort((u, v) -> Double.compare(u.distSqr(me), v.distSqr(me)));
        if (discardedOnes > 0) {
            Logbook.note("farm", discardedOnes + " logs discarded for not being tree logs");
        }
        this.mode = Mode.GATHER;
        this.targets = iWant;
        this.targetName = String.join("/", names);
        this.itemMeta = item == null || item.isBlank() ? null : item.trim().toLowerCase();
        this.countedBefore = itemMeta == null ? 0 : MarionetteBot.quantityCarried(p, itemMeta);
        this.goal = Math.max(quantity, 1);
        this.seed = itemMeta;
        this.cells = list;
        startUp();
        Logbook.note("farm", String.format("gathering %s: %d in sight, I want %d%s",
                targetName, list.size(), goal, itemMeta == null ? " blocks" : " " + itemMeta));
        return null;
    }

    /**
     * Is this log part of a tree? Its group of touching logs is walked (up to 96) and it
     * counts if some log touches LEAVES and none touches a building (planks, stairs,
     * slabs, glass, doors, wool, fences, walls) nor is a block placed by me. The verdict
     * is noted for the whole group.
     */
    private static boolean isTreePart(Minecraft mc, BlockPos beginning, Map<BlockPos, Boolean> verdict) {
        var tags = net.minecraft.tags.BlockTags.class;
        List<BlockPos> group = new ArrayList<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        queue.add(beginning.immutable());
        seen.add(beginning.immutable());
        boolean leaves = false, builtUp = false;
        while (!queue.isEmpty() && group.size() < 96) {
            BlockPos b = queue.poll();
            group.add(b);
            BlockState s = mc.level.getBlockState(b);
            String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
            if (PlacedBlocks.isMine(b, id)) builtUp = true;
            for (Direction d : Direction.values()) {
                BlockPos v = b.relative(d);
                BlockState sv = mc.level.getBlockState(v);
                if (sv.is(net.minecraft.tags.BlockTags.LOGS)) {
                    if (seen.add(v.immutable())) queue.add(v.immutable());
                } else if (sv.is(net.minecraft.tags.BlockTags.LEAVES)) {
                    leaves = true;
                } else if (sv.is(net.minecraft.tags.BlockTags.PLANKS) || sv.is(net.minecraft.tags.BlockTags.STAIRS)
                        || sv.is(net.minecraft.tags.BlockTags.SLABS) || sv.is(net.minecraft.tags.BlockTags.DOORS)
                        || sv.is(net.minecraft.tags.BlockTags.WOOL) || sv.is(net.minecraft.tags.BlockTags.FENCES)
                        || sv.is(net.minecraft.tags.BlockTags.WALLS) || sv.is(net.minecraft.tags.BlockTags.TRAPDOORS)
                        || sv.is(Blocks.GLASS) || sv.is(Blocks.GLASS_PANE) || sv.is(Blocks.BRICKS)
                        || sv.is(Blocks.STONE_BRICKS) || sv.is(Blocks.TORCH) || sv.is(Blocks.WALL_TORCH)) {
                    builtUp = true;
                }
            }
        }
        boolean tree = leaves && !builtUp;
        for (BlockPos b : group) verdict.put(b, tree);
        return tree;
    }

    /** Ores and other things that would be X-ray to look for from the list. */
    static boolean isOre(String id) {
        return id.endsWith("_ore") || id.equals("ancient_debris") || id.equals("budding_amethyst")
                || (id.startsWith("raw_") && id.endsWith("_block")) || id.equals("amethyst_cluster");
    }

    private boolean goalReached(LocalPlayer p) {
        return itemMeta == null ? deeds >= goal
                : MarionetteBot.quantityCarried(p, itemMeta) - countedBefore >= goal;
    }

    private List<BlockPos> ripe(Minecraft mc, LocalPlayer p, int radius, boolean onlyMine) {
        List<BlockPos> list = new ArrayList<>();
        BlockPos me = p.blockPosition();
        for (BlockPos b : BlockPos.betweenClosed(me.offset(-radius, -4, -radius),
                                                  me.offset(radius, 4, radius))) {
            BlockState s = mc.level.getBlockState(b);
            if (!(s.getBlock() instanceof CropBlock c) || !c.isMaxAge(s)) continue;
            if (onlyMine && farms.stream().noneMatch(h -> h.contains(b))) continue;
            list.add(b.immutable());
        }
        list.sort((u, v) -> Double.compare(u.distSqr(me), v.distSqr(me)));
        return list;
    }

    /** Is anything ripe in my farms in sight? For harvesting on its own. */
    synchronized boolean hasMyRipe(int radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || farms.isEmpty()) return false;
        return !ripe(mc, mc.player, radius, true).isEmpty();
    }

    private void startUp() {
        deeds = 0;
        skipped = 0;
        resown.clear();
        phase = Phase.CHOOSING;
        current = null;
        outcome = null;
        working = true;
    }

    synchronized void stop(String because) {
        if (flattening) fillWorker.stop(because);
        flattening = false;
        working = false;
        outcome = because;
        current = null;
    }

    // ----------------------------------------------------------------- tick

    synchronized void tick() {
        if (!working) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { stop("I left the world"); return; }
        if (!p.isAlive()) { stop("I was killed at the field"); return; }
        if (MarionetteBot.eating()) return;
        if (flattening) {
            if (fillWorker.working()) return;      // the site, first
            flattening = false;
            Logbook.note("farm", "site ready; now the water and the sowing");
        }
        switch (phase) {
            case CHOOSING -> choose(mc, p);
            case GOING -> going(mc, p);
            case ACTING -> act(mc, p);
        }
    }

    private void choose(Minecraft mc, LocalPlayer p) {
        while (!cells.isEmpty()) {
            current = cells.remove(0);
            went = false;
            if (done(mc, current)) continue;
            ticksInPhase = 0;
            actionTicks = 0;
            if (inReach(p, current)) { phase = Phase.ACTING; return; }
            if (goNear(mc, p, current)) { phase = Phase.GOING; return; }
            skipped++;
            Logbook.note("farm", "I could not find my way to " + current.toShortString());
        }
        finish();
    }

    private void going(Minecraft mc, LocalPlayer p) {
        if (++ticksInPhase > PATIENCE) { skipped++; phase = Phase.CHOOSING; return; }
        if (inReach(p, current)) { walker.stop("I already reach the tile"); phase = Phase.ACTING; ticksInPhase = 0; return; }
        if (walker.walking()) return;
        if (!went && goNear(mc, p, current)) { went = true; return; }
        skipped++;
        phase = Phase.CHOOSING;
    }

    private void act(Minecraft mc, LocalPlayer p) {
        if (++ticksInPhase > PATIENCE) {
            skipped++;
            Logbook.note("farm", "I give up on " + current.toShortString());
            phase = Phase.CHOOSING;
            return;
        }
        if (done(mc, current)) { deeds++; phase = Phase.CHOOSING; return; }
        if (!inReach(p, current)) { phase = Phase.CHOOSING; cells.add(0, current); return; }
        if (++actionTicks % EVERY_ACTION != 1) return;

        if (mode == Mode.SOW || mode == Mode.HARVEST) {
            // The guard standing on the tile: ask it to make way and wait. See Guards.
            var guard = Guards.guardAt(mc, new net.minecraft.world.phys.AABB(current.above()));
            if (guard != null) {
                Guards.moveAside(guard, Vec3.atCenterOf(current.above()), null, 2.0,
                        "working on the tile " + current.toShortString());
                return;
            }
        }
        if (mode == Mode.WATER) {
            // Look at the water and use the bucket: the bucket uses the look, not the
            // click.
            int bucket = slotOf(p, "bucket");
            if (bucket < 0) { deeds++; phase = Phase.CHOOSING; return; }   // all full
            if (p.getInventory().selected != bucket) { p.getInventory().selected = bucket; return; }
            p.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(source));
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            p.swing(InteractionHand.MAIN_HAND);
            return;
        }
        if (mode == Mode.GATHER) {
            if (goalReached(p)) { cells.clear(); finish(); return; }
            BlockState s = mc.level.getBlockState(current);
            if (targets.contains(s.getBlock())) {
                if (miner.digging()) return;
                String failure = miner.begin(current);
                if (failure != null) {
                    Logbook.note("farm", "I could not break " + current.toShortString() + ": " + failure);
                    skipped++;
                    phase = Phase.CHOOSING;
                }
                return;
            }
            // Cut: step on the tile to collect what it dropped.
            if (p.blockPosition().distManhattan(current) > 1 && !walker.walking()) {
                if (!went && goOnTop(mc, p, current)) { went = true; return; }
            }
            if (walker.walking()) return;
            deeds++;
            phase = Phase.CHOOSING;
            return;
        }
        if (mode == Mode.SOW && pools.contains(current)) {
            // An irrigation pool: dig the tile and empty the bucket.
            BlockState gap = mc.level.getBlockState(current);
            if (!gap.isAir() && !gap.getFluidState().is(FluidTags.WATER)) {
                mc.gameMode.continueDestroyBlock(current, Direction.UP);
                if (actionTicks <= EVERY_ACTION) mc.gameMode.startDestroyBlock(current, Direction.UP);
                p.swing(InteractionHand.MAIN_HAND);
                return;
            }
            if (gap.isAir()) {
                int bucket = slotOf(p, "water_bucket");
                if (bucket < 0) { stop("I ran out of water buckets halfway through the field"); return; }
                if (p.getInventory().selected != bucket) { p.getInventory().selected = bucket; return; }
                // The bucket pours where it looks: at the top face of the pool's bottom.
                p.lookAt(EntityAnchorArgument.Anchor.EYES,
                        Vec3.atCenterOf(current.below()).add(0, 0.5, 0));
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                p.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }
        if (mode == Mode.SOW) {
            BlockState ground = mc.level.getBlockState(current);
            if (!ground.is(Blocks.FARMLAND)) {
                int hoe = hoeSlot(p);
                if (hoe < 0) { stop("my hoe wore out halfway through the field"); return; }
                Builder.placeOf(current.above(), Direction.DOWN, hoe);   // the tilling click
                return;
            }
            int seedSlot = slotOf(p, seed);
            if (seedSlot < 0) { stop("I ran out of " + seed + " halfway through the field"); return; }
            Builder.placeOf(current.above(), Direction.DOWN, seedSlot);
            return;
        }

        // HARVEST: break what is ripe, step on the tile to collect it, resow.
        BlockState s = mc.level.getBlockState(current);
        if (s.getBlock() instanceof CropBlock c && c.isMaxAge(s)) {
            String crop = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
            mc.gameMode.startDestroyBlock(current, Direction.UP);
            p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            resown.putIfAbsent(crop, 0);
            return;
        }
        if (s.isAir() || s.canBeReplaced()) {
            // Step on the tile: what dropped is collected by touching it.
            if (p.blockPosition().distManhattan(current) > 1 && !walker.walking()) {
                if (!went && goOnTop(mc, p, current)) { went = true; return; }
            }
            if (walker.walking()) return;
            String crop = resown.keySet().stream()
                    .reduce((a, b) -> b).orElse(null);
            String seedSlot = crop == null ? null : SEED_OF.get(crop);
            int slot = seedSlot == null ? -1 : slotOf(p, seedSlot);
            if (slot < 0) { deeds++; phase = Phase.CHOOSING; return; }   // no seed: it stays harvested
            if (mc.level.getBlockState(current.below()).is(Blocks.FARMLAND)) {
                Builder.placeOf(current, Direction.DOWN, slot);
                resown.merge(crop, 1, Integer::sum);
            }
            deeds++;
            phase = Phase.CHOOSING;
        }
    }

    /** SOW: sown (there is a crop on top). HARVEST: no longer ripe. */
    private boolean done(Minecraft mc, BlockPos b) {
        if (mode == Mode.WATER) {
            LocalPlayer p = mc.player;
            return MarionetteBot.quantityCarried(p, "bucket") == 0;   // all buckets full
        }
        if (mode == Mode.GATHER) {
            BlockState s = mc.level.getBlockState(b);
            // Done when it is no longer the block AND nothing is left lying on top.
            if (targets.contains(s.getBlock())) return false;
            return mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(b).inflate(1.0)).isEmpty();
        }
        if (mode == Mode.SOW && pools.contains(b)) {
            return mc.level.getFluidState(b).is(FluidTags.WATER);
        }
        if (mode == Mode.SOW) {
            return mc.level.getBlockState(b.above()).getBlock() instanceof CropBlock;
        }
        BlockState s = mc.level.getBlockState(b);
        return !(s.getBlock() instanceof CropBlock c && c.isMaxAge(s)) && !s.isAir();
    }

    private void finish() {
        String what = mode == Mode.GATHER
                ? (itemMeta == null
                    ? String.format("finished: I broke %d of %s", deeds, targetName)
                    : String.format("finished: I broke %d of %s and I carry %d %s (I had %d)", deeds,
                        targetName, MarionetteBot.quantityCarried(Minecraft.getInstance().player, itemMeta),
                        itemMeta, countedBefore))
                : mode == Mode.WATER
                ? String.format("finished: I filled buckets, I carry %d of water (before %d)",
                        MarionetteBot.quantityCarried(Minecraft.getInstance().player, "water_bucket"), bucketsBefore)
                : mode == Mode.SOW
                ? String.format("finished: I sowed %d of %s%s", deeds, seed,
                        pools.isEmpty() ? "" : " with " + pools.size() + " watering pool(s)")
                : String.format("finished: I harvested %d crops%s", deeds,
                        resown.isEmpty() ? "" : ", resowed " + resown);
        stop(what + (skipped > 0 ? String.format(", %d skipped", skipped) : ""));
    }

    // ------------------------------------------------------------- helpers

    /** Does it have a free face (air, water, grass: nothing in the way)? */
    private static boolean exposed(Minecraft mc, BlockPos b) {
        for (var d : net.minecraft.core.Direction.values()) {
            BlockPos v = b.relative(d);
            if (mc.level.getBlockState(v).getCollisionShape(mc.level, v).isEmpty()) return true;
        }
        return false;
    }

    private static boolean inReach(LocalPlayer p, BlockPos b) {
        return p.getEyePosition().distanceTo(Vec3.atCenterOf(b)) <= REACH;
    }

    private boolean goNear(Minecraft mc, LocalPlayer p, BlockPos b) {
        return go(mc, p, b, 2.5, 1.4);
    }

    private boolean goOnTop(Minecraft mc, LocalPlayer p, BlockPos b) {
        return go(mc, p, b, 0.5, 0.4);
    }

    private boolean go(Minecraft mc, LocalPlayer p, BlockPos b, double near, double fineGrained) {
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MarionetteBot.whereAmI(world, p);
        Route.Point goal = new Route.Point(b.getX(), b.getY(), b.getZ());
        Route.Result r = Route.search(world, here, Route.Meta.near(goal, near),
                new Route.Options(MarionetteBot.safeFall(p.getHealth()), 6_000, false, true)
                        .withDeadline(25));
        if (!r.hasRoute() || r.steps().size() <= 1) return false;
        return walker.follow(r.steps(), x -> null, fineGrained) == null;
    }

    /** The hotbar slot with a hoe (bringing it up from the backpack if needed), or -1. */
    private static int hoeSlot(LocalPlayer p) {
        var inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).getItem() instanceof HoeItem) return i;
        }
        for (int i = 9; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() instanceof HoeItem) {
                return MarionetteBot.takeFromBackpack(p,
                        BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
            }
        }
        return -1;
    }

    private static int slotOf(LocalPlayer p, String id) {
        var inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals(id)) return i;
        }
        return MarionetteBot.takeFromBackpack(p, id);
    }

    synchronized String state() {
        if (!working) {
            StringBuilder hs = new StringBuilder();
            for (Farm h : farms) {
                if (hs.length() > 0) hs.append(',');
                hs.append(String.format("{\"x\":%d,\"y\":%d,\"z\":%d,\"width\":%d,\"length\":%d,\"seed\":\"%s\"}",
                        h.corner().getX(), h.corner().getY(), h.corner().getZ(), h.width(), h.length(), h.seed()));
            }
            return String.format("{\"working\":false,\"outcome\":\"%s\",\"farms\":[%s]}",
                    Request.escape(outcome), hs);
        }
        return String.format("{\"working\":true,\"mode\":\"%s\",\"target\":\"%s\",\"deeds\":%d,"
                + "\"skipped\":%d,\"remaining\":%d}", mode.name().toLowerCase(),
                Request.escape(mode == Mode.GATHER ? targetName : (seed == null ? "" : seed)),
                deeds, skipped, cells.size());
    }

    synchronized boolean working() {
        return working;
    }
}

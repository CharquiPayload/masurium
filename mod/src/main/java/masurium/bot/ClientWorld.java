package masurium.bot;

import masurium.common.World;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * The real world, as seen by the client, with the face the path finder expects.
 *
 * <p><b>It can only be used on the game thread.</b> Reading blocks from the HTTP thread
 * gives half-baked state.
 *
 * <p>It caches what it looks up in a map: A* asks about the same tile many times (every
 * neighbour looks at it from several sides) and the block does not change while the
 * search lasts. Without this, a long route means hundreds of thousands of reads.
 */
final class ClientWorld implements World {

    private final BlockGetter levelValue;
    private final Map<Long, Byte> cache = new HashMap<>();
    private final BlockPos.MutableBlockPos aux = new BlockPos.MutableBlockPos();

    private static final byte AIR = 0, SOLID = 1, WATER = 2, LAVA = 3,
            DOOR = 4, DANGER = 5;

    ClientWorld(BlockGetter levelValue) {
        this.levelValue = levelValue;
    }

    /** How many different tiles were looked at. Useful for measuring. */
    int queried() {
        return cache.size();
    }

    /**
     * What the path finder steps on: the usual, MINUS the tiles where the body just got
     * stuck (see {@link StuckSpots}).
     */
    /**
     * Like {@link #canStand} but WITHOUT the {@link StuckSpots} veto: for the START tile
     * of a route. Vetoing the spot the bot is standing on left it 90 s unable to start
     * any trip ("where I am is not a spot where one can stand").
     */
    public boolean canStandWithoutVeto(int x, int y, int z) {
        if (!World.super.canStand(x, y, z)) return false;
        aux.set(x, y - 1, z);
        BlockState ground = levelValue.getBlockState(aux);
        if (ground.is(Blocks.MAGMA_BLOCK)) return false;
        return !(ground.is(BlockTags.CAMPFIRES)
                && ground.getValue(CampfireBlock.LIT));
    }

    @Override
    public boolean canStand(int x, int y, int z) {
        if (StuckSpots.avoided(x, y, z)) return false;
        // What does not block but hurts (berry bush, fire, powder snow, wither rose) is
        // NOT vetoed here: the search charges it through dangerous(). Vetoing it left the
        // bot without a route when it was already inside a bush.
        if (!World.super.canStand(x, y, z)) return false;
        // And the floor that burns: magma and lit campfires are solid, so they pass the
        // filter above, but they are not a place to stand.
        aux.set(x, y - 1, z);
        BlockState ground = levelValue.getBlockState(aux);
        if (ground.is(Blocks.MAGMA_BLOCK)) return false;
        return !(ground.is(BlockTags.CAMPFIRES)
                && ground.getValue(CampfireBlock.LIT));
    }

    @Override
    public boolean dangerous(int x, int y, int z) {
        return type(x, y, z) == DANGER;
    }

    @Override
    public boolean solid(int x, int y, int z) {
        return type(x, y, z) == SOLID;
    }

    @Override
    public boolean water(int x, int y, int z) {
        return type(x, y, z) == WATER;
    }

    /**
     * Doors and gates opened BY HAND. Iron ones are not: those need redstone and for the
     * bot they are a wall, which is exactly what they are.
     *
     * <p>Closed ones have a collision box, so without this question they fell into the
     * "solid" bag and a closed house was unreachable.
     */
    @Override
    public boolean door(int x, int y, int z) {
        return type(x, y, z) == DOOR;
    }

    /**
     * Lava, which used to count as water, with the result that the search took it for a
     * place to stand and a free fall, so it would have jumped in rather than go around.
     * They are split here, in the only place that knows about fluids.
     */
    @Override
    public boolean lava(int x, int y, int z) {
        return type(x, y, z) == LAVA;
    }

    /**
     * Breakable = solid, with normal hardness, and ON THE WHITELIST: the same {@link
     * BreakPermissions} list that governs the miner. The break_to_advance toggle decides
     * whether the search asks this, but WHAT may be broken is always decided by the list.
     * Not cached: it is only asked for tiles that already blocked a step.
     */
    @Override
    public boolean breakable(int x, int y, int z) {
        if (!solid(x, y, z)) return false;
        aux.set(x, y, z);
        BlockState state = levelValue.getBlockState(aux);
        if (state.getDestroySpeed(levelValue, aux) < 0) return false;   // bedrock
        return BreakPermissions.mayIBreak(net.minecraft.core.registries
                .BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    private byte type(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        Byte saved = cache.get(key);
        if (saved != null) return saved;

        aux.set(x, y, z);
        BlockState state = levelValue.getBlockState(aux);
        byte t;
        if (state.is(Blocks.BUBBLE_COLUMN)
                && state.getValue(BubbleColumnBlock.DRAG_DOWN)) {
            // WHIRLPOOL: the bubble column that pulls down, the one above magma at the
            // bottom of the sea. For the game it is water; for whoever swims above it, a
            // trap: it drags to the bottom, the magma burns a point every half second and
            // the current does not let you rise. A bot died like that twice in a row at
            // the same spot (it looked like "falling into lava"; it was magma under
            // water). It is treated as lava: neither stepped on nor brushed against. The
            // one pulling UP (soul sand) is normal water, and even helps to get out.
            t = LAVA;
        } else if (!state.getFluidState().isEmpty()) {
            // Water does not block movement and also cancels fall damage. Lava does not
            // block movement either, and that is where the likeness ends.
            t = state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)
                    ? LAVA : WATER;
        } else if (state.isAir()) {
            t = AIR;
        } else {
            // "Solid" for walking is not the same as "has a block": tall grass, torches
            // or flowers stop nobody. The collision box is what really matters, so that
            // is what is asked.
            var box = state.getCollisionShape(levelValue, aux);
            if (box.isEmpty()) {
                t = AIR;
            } else if (box.max(net.minecraft.core.Direction.Axis.Y) <= 0.5
                    || (state.is(BlockTags.TRAPDOORS) && state.getValue(
                            net.minecraft.world.level.block.TrapDoorBlock.OPEN))) {
                // THIN FLOOR: closed trapdoor, carpet, pressure plate, snow layers,
                // bottom slab. They have a box, but of HALF a block or less: one stands
                // ON this tile (at 0.2 or 0.5 height, not 1.0), so counting it as a whole
                // block left the walker jumping in place without ever "arriving" (a bot
                // stuck on a trapdoor). The threshold was a quarter and was raised to
                // half: snow of 3 and 4 layers (0.375 and 0.5) counted as a block, and on
                // a snowy mountain that is every tile, which left a bot "stuck without a
                // route" with its feet on a three-layer drift. Half a block is what a
                // player walks up without jumping (step height 0.6); five layers and up
                // are still a step to jump. An OPEN trapdoor is a vertical sheet against
                // one face: it can be walked through.
                t = AIR;
            } else {
                t = SOLID;
            }
            if (t == AIR && (state.is(Blocks.SWEET_BERRY_BUSH)
                    || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                    || state.is(Blocks.POWDER_SNOW)
                    || state.is(Blocks.WITHER_ROSE))) {
                t = DANGER;
            }
            // A wooden door or gate is as much in the way as a wall, but it opens. They
            // are marked separately so the search can pay for opening them instead of
            // walking around the house.
            if (t == SOLID && (state.is(BlockTags.WOODEN_DOORS)
                    || state.is(BlockTags.FENCE_GATES))) {
                t = DOOR;
            }
        }
        // FENCES AND WALLS are 1.5 tall: their upper half lives in the cell above, which
        // as blocks is "air". Without this, the path finder believed one could stand on a
        // fence and cross, and the bot stayed jumping against it forever, building
        // useless towers. If the block below sticks out above 1.0, this cell counts as
        // occupied.
        if (t == AIR) {
            aux.set(x, y - 1, z);
            BlockState down = levelValue.getBlockState(aux);
            var box = down.getCollisionShape(levelValue, aux);
            // A closed GATE is also 1.5 tall, but it opens: counting its upper half as
            // wall would leave the head tile blocked and it could not be crossed even by
            // opening it.
            if (!box.isEmpty()
                    && box.max(net.minecraft.core.Direction.Axis.Y) > 1.0
                    && !down.is(BlockTags.FENCE_GATES)) {
                t = SOLID;
            }
        }
        cache.put(key, t);
        return t;
    }
}

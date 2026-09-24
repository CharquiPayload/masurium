package masurium.server.puppet;

import masurium.common.World;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;

import java.util.HashMap;
import java.util.Map;

/**
 * The path finder's view of the world for a server-side bot, read OFF the server's
 * thread: EXPERIMENT (server-side bots).
 *
 * <p>The server's own thread takes the snapshot: which chunks are loaded around the
 * start and the goal ({@code getChunkNow} only answers on that thread, and never loads
 * one). The search then runs on a thread of its own and reads those chunks' blocks
 * directly: reading is not synchronised with the server writing, so a block changed in
 * the middle of a search may be read before or after the change, and a read that trips
 * on a section being resized counts as a wall. What a search costs the tick is taking
 * the snapshot, not the search.
 *
 * <p>What is solid, a floor, a door or a danger is decided as the client bot's world
 * decides it (bot/ClientWorld), without its memory of stuck spots and without the
 * break whitelist, which this prototype does not use.
 */
final class SnapshotWorld implements World, BlockGetter {

    private static final byte AIR = 0, SOLID = 1, WATER = 2, LAVA = 3, DOOR = 4, DANGER = 5;

    private final Map<Long, LevelChunk> chunks;
    private final int minY, height;
    private final Map<Long, Byte> cache = new HashMap<>();
    private final BlockPos.MutableBlockPos aux = new BlockPos.MutableBlockPos();

    private SnapshotWorld(Map<Long, LevelChunk> chunks, int minY, int height) {
        this.chunks = chunks;
        this.minY = minY;
        this.height = height;
    }

    /**
     * The loaded chunks of the box between two points, widened by {@code margin} blocks.
     * On the server's thread only. At most {@code maxChunks} of them: a longer trip is
     * searched a stretch at a time.
     */
    static SnapshotWorld around(ServerLevel level, BlockPos a, BlockPos b, int margin, int maxChunks) {
        int x0 = (Math.min(a.getX(), b.getX()) - margin) >> 4, x1 = (Math.max(a.getX(), b.getX()) + margin) >> 4;
        int z0 = (Math.min(a.getZ(), b.getZ()) - margin) >> 4, z1 = (Math.max(a.getZ(), b.getZ()) + margin) >> 4;
        Map<Long, LevelChunk> found = new HashMap<>();
        for (int cx = x0; cx <= x1 && found.size() < maxChunks; cx++) {
            for (int cz = z0; cz <= z1 && found.size() < maxChunks; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk != null) found.put(ChunkPos.asLong(cx, cz), chunk);
            }
        }
        return new SnapshotWorld(found, level.getMinBuildHeight(), level.getHeight());
    }

    int chunkCount() {
        return chunks.size();
    }

    int queried() {
        return cache.size();
    }

    // --- BlockGetter: what a block's collision shape may ask about its neighbours ---

    @Override
    public BlockState getBlockState(BlockPos pos) {
        LevelChunk chunk = chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (chunk == null) return Blocks.BEDROCK.defaultBlockState();   // unknown is a wall
        return chunk.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinBuildHeight() {
        return minY;
    }

    // --- World: the path finder's questions ---

    @Override
    public boolean solid(int x, int y, int z) {
        return type(x, y, z) == SOLID;
    }

    @Override
    public boolean water(int x, int y, int z) {
        return type(x, y, z) == WATER;
    }

    @Override
    public boolean lava(int x, int y, int z) {
        return type(x, y, z) == LAVA;
    }

    @Override
    public boolean door(int x, int y, int z) {
        return type(x, y, z) == DOOR;
    }

    @Override
    public boolean dangerous(int x, int y, int z) {
        return type(x, y, z) == DANGER;
    }

    @Override
    public boolean canStand(int x, int y, int z) {
        if (!World.super.canStand(x, y, z)) return false;
        aux.set(x, y - 1, z);
        BlockState ground = read(aux);
        if (ground.is(Blocks.MAGMA_BLOCK)) return false;
        return !(ground.is(BlockTags.CAMPFIRES) && ground.getValue(CampfireBlock.LIT));
    }

    private BlockState read(BlockPos pos) {
        try {
            return getBlockState(pos);
        } catch (RuntimeException e) {
            // Read while the server was writing that section: taken for a wall.
            return Blocks.BEDROCK.defaultBlockState();
        }
    }

    private byte type(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        Byte saved = cache.get(key);
        if (saved != null) return saved;
        byte t = classify(x, y, z);
        cache.put(key, t);
        return t;
    }

    private byte classify(int x, int y, int z) {
        if (y < minY || y >= minY + height) return y < minY ? SOLID : AIR;
        aux.set(x, y, z);
        BlockState state = read(aux);
        byte t;
        if (state.is(Blocks.BUBBLE_COLUMN) && state.getValue(BubbleColumnBlock.DRAG_DOWN)) {
            t = LAVA;
        } else if (!state.getFluidState().isEmpty()) {
            t = state.getFluidState().is(FluidTags.LAVA) ? LAVA : WATER;
        } else if (state.isAir()) {
            t = AIR;
        } else {
            var box = safeShape(state, aux);
            if (box == null) {
                t = SOLID;
            } else if (box.isEmpty()) {
                t = AIR;
            } else if (box.max(Direction.Axis.Y) <= 0.5
                    || (state.is(BlockTags.TRAPDOORS) && state.getValue(TrapDoorBlock.OPEN))) {
                t = AIR;
            } else {
                t = SOLID;
            }
            if (t == AIR && (state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.FIRE)
                    || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.POWDER_SNOW)
                    || state.is(Blocks.WITHER_ROSE))) {
                t = DANGER;
            }
            if (t == SOLID && (state.is(BlockTags.WOODEN_DOORS) || state.is(BlockTags.FENCE_GATES))) {
                t = DOOR;
            }
        }
        // Fences and walls are 1.5 tall: the cell above one is taken too.
        if (t == AIR) {
            aux.set(x, y - 1, z);
            BlockState below = read(aux);
            var belowBox = below.isAir() ? null : safeShape(below, aux);
            if (belowBox != null && !belowBox.isEmpty() && belowBox.max(Direction.Axis.Y) > 1.0) {
                t = SOLID;
            }
        }
        return t;
    }

    private net.minecraft.world.phys.shapes.VoxelShape safeShape(BlockState state, BlockPos pos) {
        try {
            return state.getCollisionShape(this, pos);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

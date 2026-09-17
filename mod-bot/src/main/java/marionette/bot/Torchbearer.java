package marionette.bot;

import marionette.common.Logbook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;

/**
 * Lighting up the mine.
 *
 * <p>The bot can see darkness levels, which makes lighting mines efficient; it only
 * places torches while mining or when a player asks, never all over the overworld.
 *
 * <p>Both halves of that are here:
 * <ul>
 *   <li><b>Efficient</b>: it looks at the BLOCK light of the tile it is on and only
 *       places a torch if it is ZERO. Since 1.18 monsters only spawn at block light 0, so
 *       that is the real threshold: a torch every ten or twelve blocks is enough, and
 *       planting one every three is wasting coal.</li>
 *   <li><b>Only while mining</b>: this is not a behaviour with its own tick. The {@link
 *       Miner} calls it right before starting a block, which is the exact definition of
 *       "while digging". Outside of that nobody lights anything.</li>
 * </ul>
 *
 * <p>It goes BEFORE digging and not halfway through on purpose: switching slots to wield
 * the torch resets the block's progress, so doing it halfway would mean paying twice for
 * the same hole.
 */
final class Torchbearer {

    private Torchbearer() {}

    /**
     * Block light below which a torch is lit. Zero, which is where monsters spawn since
     * 1.18.
     */
    private static final int DARK = 0;
    /**
     * Minimum distance between two of its own torches. It exists because of strip mining:
     * advancing one tile per tick, the light of the torch just placed had not propagated
     * in the client yet, the next tile read zero and another torch went in: four torches
     * in four blocks in a row. One every seven lights plenty (light 14, dropping one per
     * block), about a torch every 8 blocks.
     */
    private static final double SPACING = 7.0;
    private static BlockPos last;

    /**
     * Places a torch if the spot is dark and there is something to place.
     *
     * @return true if it placed one (so the caller knows a tick went to something else)
     */
    static boolean lightIfNeeded() {
        if (!Preferences.is("torches_while_mining")) return false;
        if (MarionetteBot.eating()) return false;       // do not switch hands mid-bite
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isAlive()) return false;
        if (!p.onGround()) return false;

        BlockPos feet = p.blockPosition();
        if (last != null && last.distSqr(feet) < SPACING * SPACING) {
            return false;
        }
        if (mc.level.getBrightness(LightLayer.BLOCK, feet) > DARK) return false;
        // Room for the torch: the feet tile empty and solid ground below. If the bot is
        // in water or snow, it does not even try.
        if (!mc.level.getBlockState(feet).isAir()) return false;
        if (mc.level.getBlockState(feet.below()).getCollisionShape(
                mc.level, feet.below()).isEmpty()) {
            return false;
        }

        int slot = slotWithTorch(p);
        if (slot < 0) slot = MarionetteBot.takeFromBackpack(p, "torch");
        if (slot < 0) {
            // Without torches it carries on in the dark and says nothing: torches are
            // OPTIONAL. Players are not asked for torches and activities do not stop for
            // lack of them. (It used to be a body notice, and the brain ended up asking
            // for them.)
            Logbook.note("light", "no torches: carrying on in the dark");
            return false;
        }

        int before = p.getInventory().selected;
        String failure = Builder.placeOf(feet, Direction.DOWN, slot);
        p.getInventory().selected = before;   // the tool goes back to the hand
        if (failure != null) return false;
        last = feet.immutable();
        Logbook.note("light", String.format("torch at %d %d %d",
                feet.getX(), feet.getY(), feet.getZ()));
        return true;
    }

    /**
     * The hotbar slot with torches, or -1. Any torch counts: normal, soul and redstone
     * torches light differently, but all count as "something I carry to light up".
     */
    private static int slotWithTorch(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = p.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (id.equals("torch") || id.equals("soul_torch")) return i;
        }
        return -1;
    }
}

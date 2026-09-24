package masurium.server.puppet;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.function.ToDoubleFunction;

/**
 * What a puppet does beyond walking, a tick at a time: EXPERIMENT (server-side bots).
 *
 * <p>Before the walk, {@link #think} decides where to walk (a search, through
 * {@link Puppets#plan}); after the walk has pressed its keys, {@link #act} does what the
 * hands do, and may take the keys over for the last steps, straight at what is near.
 */
abstract class Job {

    /** Before the walk. False when the job is over; it has said why, in {@code doing}. */
    abstract boolean think(Puppets.Puppet p, long now);

    /** What it is at, for {@code list}: shown while the job lasts, over the walk's own words. */
    abstract String status();

    /** After the walk's keys: the hands, and the last steps. */
    void act(Puppets.Puppet p) {
    }

    /** It ends, however: what the hands were doing is let go. */
    void end(Puppets.Puppet p) {
    }

    /**
     * The best in the inventory by {@code score} into the hand: selected if it is in the
     * hotbar, swapped into the selected slot if it is not. Nothing better than what is
     * held, nothing changes.
     */
    static void hold(Puppets.Puppet p, ToDoubleFunction<ItemStack> score) {
        Inventory inv = p.body.getInventory();
        int best = inv.selected;
        double bestScore = score.applyAsDouble(inv.getItem(best));
        for (int i = 0; i < inv.items.size(); i++) {
            double s = score.applyAsDouble(inv.getItem(i));
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        if (best == inv.selected) return;
        if (Inventory.isHotbarSlot(best)) {
            inv.selected = best;
        } else {
            ItemStack held = inv.getItem(inv.selected);
            inv.setItem(inv.selected, inv.getItem(best));
            inv.setItem(best, held);
        }
    }

    /** The last steps, straight at {@code to}: forward, and a jump when something is in the way. */
    static void walkStraight(Puppets.Puppet p, Vec3 to) {
        PuppetPlayer b = p.body;
        double dx = to.x - b.getX(), dz = to.z - b.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        b.setYRot(yaw);
        b.setYHeadRot(yaw);
        b.zza = 1.0f;
        b.xxa = 0;
        b.setSprinting(false);
        b.setJumping(b.horizontalCollision && b.onGround() || b.isInWater());
    }
}

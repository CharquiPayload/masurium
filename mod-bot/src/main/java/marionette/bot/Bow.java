package marionette.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The bow as a mechanic: wield it, aim, draw and release.
 *
 * <p>It lived entirely inside the {@link Archer} and was pulled out when the {@link
 * Lookout} needed to shoot too (at creepers, from a distance). What decides WHAT to shoot
 * at and what to do with the result still belongs to each behaviour; only the gesture is
 * here, which is what took nights to figure out.
 */
final class Bow {

    private Bow() {}

    /**
     * Full charge: the game gives it at 20 ticks; a couple more as margin so the server,
     * which keeps its own count, also sees it as full.
     */
    static final int LOAD_JOB = 22;

    /**
     * One step of the cycle, one tick. Returns true ONLY in the tick the arrow really
     * leaves.
     *
     * <p><b>The faked key, the lesson of the bite</b> (and of the walker): the client
     * releases the use as soon as the use key is not pressed, and in a bot it never is.
     * Without this the bow was stuck in a 1-tick draw-and-release loop: "it only draws,
     * it does not shoot". The key is held while charging and released right before {@code
     * releaseUsingItem}, which is what shoots.
     */
    /**
     * What is NOT shot at: the breeze deflects any projectile and the enderman teleports
     * before the arrow arrives. Against those, the sword.
     */
    static boolean immuneToArrows(Entity e) {
        return e instanceof net.minecraft.world.entity.monster.breeze.Breeze
                || e instanceof net.minecraft.world.entity.monster.EnderMan;
    }

    static boolean drawAndRelease(Minecraft mc, LocalPlayer p, Entity who) {
        int slot = inHotbar(p);
        if (slot < 0) slot = MarionetteBot.takeFromBackpack(p, "bow");
        if (slot < 0) return false;          // the caller already ruled it out
        if (p.getInventory().selected != slot) {
            p.getInventory().selected = slot;
            return false;   // the usual lost tick: switching resets everything
        }
        aim(p, who);
        mc.options.keyUse.setDown(true);
        if (!p.isUsingItem()) {
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            return false;
        }
        if (p.getTicksUsingItem() < LOAD_JOB) return false;
        mc.options.keyUse.setDown(false);
        mc.gameMode.releaseUsingItem(p);
        return true;
    }

    /**
     * Aim where the arrow will be, not where the mob is: at full charge it leaves at 3
     * blocks per tick and gravity takes 0.05 per tick, so the drop grows with the square
     * of the distance: d*d/360 in a vacuum, ~d*d/340 with air drag. At 10 blocks it does
     * not show; at 25 it is more than a block and a half, the difference between hitting
     * the chest and whistling underneath.
     */
    static void aim(LocalPlayer p, Entity who) {
        Vec3 aimTarget = who.position().add(0, who.getBbHeight() * 0.5, 0);
        double dx = aimTarget.x - p.getX();
        double dz = aimTarget.z - p.getZ();
        double blueprint = Math.sqrt(dx * dx + dz * dz);
        p.lookAt(EntityAnchorArgument.Anchor.EYES,
                aimTarget.add(0, blueprint * blueprint / 340.0, 0));
    }

    /**
     * null if there is a bow and arrows; otherwise the reason, in words. Arrows are
     * counted by the game with {@code getProjectile}: tipped and spectral arrows count on
     * their own, without our own list.
     */
    /** Last time it was noted that it does not shoot in water. */
    private static long noShootInWaterMs;

    /**
     * In water it does NOT shoot: first to land. Two bots spent minutes floating in a
     * lake shooting at a creeper and a skeleton on the shore, surfacing to breathe every
     * twelve seconds and going back to the fight instead of getting out, because every
     * shot stopped the walk. Without shooting the walk goes on and the bot gets out of
     * the water; on land it fights. The Lookout and the Escort check it BEFORE stopping
     * the feet, which is why it is not inside the draw-and-release step.
     */
    static boolean standingInWater(LocalPlayer p) {
        if (!p.isInWater()) return false;
        long now = System.currentTimeMillis();
        if (now - noShootInWaterMs > 10_000) {
            noShootInWaterMs = now;
            marionette.common.Logbook.note("danger",
                    "in the water I do not shoot: first I get out to land");
        }
        return true;
    }

    static String ready(LocalPlayer p) {
        ItemStack bow = ItemStack.EMPTY;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && bow.isEmpty(); i++) {
            if (inv.getItem(i).is(Items.BOW)) bow = inv.getItem(i);
        }
        if (bow.isEmpty()) return "I carry no bow";
        if (p.getProjectile(bow).isEmpty()) {
            return "I carry a bow but not a single arrow";
        }
        return null;
    }

    /**
     * Cancels a half-drawn bow. First release the faked key: leaving it pressed would
     * make the client reuse whatever is in hand, on its own, every 4 ticks. Then, since
     * there is no "put the arrow away" button, what really cancels the draw (client and
     * server at once) is switching slots: the best weapon is wielded, which also leaves
     * the hand ready to finish the job. {@code stopUsingItem} stays as a fallback in case
     * the best weapon turns out to be the bow itself.
     *
     * <p><b>Only if what is in use (or in hand) is the bow.</b> The faked use key is
     * shared with the bite ({@code eat}), and this is called EVERY TICK from the Escort
     * with no hostile in sight, from the Lookout with an attacker and no bow, and from
     * the Archer: releasing the key and switching slots blindly cut every bite. A hungry,
     * hurt guard wrote it ten times in a row: "my meal was cut short: someone put
     * iron_sword in my hand". With bread in hand there is nothing to release here.
     */
    static void leave(LocalPlayer p) {
        boolean bowInUse = p.isUsingItem() && p.getUseItem().is(Items.BOW);
        if (!bowInUse && !p.getMainHandItem().is(Items.BOW)) return;
        Minecraft.getInstance().options.keyUse.setDown(false);
        if (!bowInUse) return;
        int another = WeaponPicker.bestWeapon(p);
        if (another != p.getInventory().selected) {
            p.getInventory().selected = another;
        } else {
            p.stopUsingItem();
        }
    }

    /** The hotbar slot with a bow, or -1. */
    static int inHotbar(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).is(Items.BOW)) return i;
        }
        return -1;
    }
}

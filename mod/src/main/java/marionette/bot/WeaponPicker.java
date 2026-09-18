package marionette.bot;

import marionette.common.Logbook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;

/**
 * Choosing what to hit with.
 *
 * <p><b>It is checked before EVERY hit, not once at the start of the fight.</b> A lesson
 * written in blood: building towers and bridges leaves a handful of cobblestone in the
 * hand in the middle of combat, and the bot kept hitting with that. It is even easier
 * now, because the bot places blocks and digs, and both change what it holds.
 *
 * <p>Damage is asked of the game through the item's attributes, not with our own list of
 * "the sword hits harder than the axe". That way it works with modded items, and it also
 * gets the known surprise right: a trident hits 9 and a netherite sword 8.
 *
 * <p><b>Three rules</b>, after watching the bot defend itself with a pickaxe while a
 * diamond sword sat in its backpack: <ol>
 *   <li><b>Weapon before tool.</b> A sword, axe, trident or mace always beats a pickaxe,
 *       shovel or hoe, whatever each one hits. Hitting with a tool costs double
 *       durability, and on top of that it is the pickaxe it is working with.</li>
 *   <li><b>Damage per second, not per hit.</b> A wooden sword (4 × 1.6) outdamages a
 *       diamond pickaxe (5 × 1.2); looking only at a single hit was what made the pickaxe
 *       win.</li>
 *   <li><b>The WHOLE inventory is looked at</b>, and if the best weapon is in the
 *       backpack it is brought up to the hotbar before hitting. Otherwise rearranging the
 *       hotbar on its own is forbidden on purpose (it would be deciding for the player,
 *       see {@code MarionetteBot.takeFromBackpack}); defending itself is the exception,
 *       and it is noted in the logbook every time.</li> </ol>
 */
final class WeaponPicker {

    /**
     * The 36 item slots: hotbar 0..8 and backpack 9..35. 36..40 are armor and the off
     * hand.
     */
    private static final int OBJECTS = 36;

    private WeaponPicker() {}

    /** Real weapons. A pickaxe hits too, but it is not a weapon. */
    static boolean isWeapon(ItemStack stack) {
        var it = stack.getItem();
        return it instanceof SwordItem || it instanceof AxeItem
                || it instanceof TridentItem || it instanceof MaceItem;
    }

    /** Damage per second: what decides a fight, not the damage of a single hit. */
    static double scoring(ItemStack stack) {
        return damage(stack) * speed(stack);
    }

    /**
     * Whether {@code a} hits better than {@code b}: first weapon before tool, then damage
     * per second.
     */
    static boolean betterThan(ItemStack a, ItemStack b) {
        boolean weaponA = isWeapon(a), weaponB = isWeapon(b);
        if (weaponA != weaponB) return weaponA;
        return scoring(a) > scoring(b);
    }

    /** The hotbar slot that hits best, or the current one if none beats it. */
    static int bestWeapon(Player p) {
        return bestAmong(p, 9);
    }

    /** The same, but also looking at the backpack: it may return 9..35. */
    static int bestOverall(Player p) {
        return bestAmong(p, OBJECTS);
    }

    private static int bestAmong(Player p, int until) {
        var inv = p.getInventory();
        int best = inv.selected;
        for (int i = 0; i < until; i++) {
            if (betterThan(inv.getItem(i), inv.getItem(best))) best = i;
        }
        return best;
    }

    /**
     * Leaves the best weapon AT HAND and returns the hotbar slot to wield. If the best
     * one is in the backpack, it brings it up to the hotbar with a swap (the same as
     * pressing a number with the inventory open).
     *
     * <p>It does not wield it here on purpose: switching items resets the attack bar, and
     * that lost tick belongs to whoever hits.
     */
    static int prepareWeapon(Minecraft mc, LocalPlayer p) {
        int best = bestOverall(p);
        if (best < 9) return best;
        // With a chest or furnace open the menu is a different one and the clicks would
        // go to it.
        if (p.containerMenu != p.inventoryMenu) return bestWeapon(p);
        var inv = p.getInventory();
        int destination = tileToClimb(p);
        String weapon = name(inv.getItem(best));
        String low = name(inv.getItem(destination));
        moveToHotbar(mc, p, best, destination);
        Logbook.note("defense", String.format(
                "I brought %s up from the backpack to slot %d%s", weapon, destination + 1,
                low.isEmpty() ? "" : " (moving " + low + " to the backpack)"));
        return destination;
    }

    /**
     * The real backpack↔hotbar swap, the same as pressing a number with the inventory
     * open. In the player's own menu the backpack slots 9..35 match their index, so no
     * translation is needed; the hotbar number goes as the "button".
     */
    static void moveToHotbar(Minecraft mc, LocalPlayer p, int backpackSlot, int hotbarSlot) {
        mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId,
                backpackSlot, hotbarSlot, ClickType.SWAP, p);
    }

    /**
     * Which hotbar slot to give up to bring a weapon up: <ol>
     *   <li>the one with the weakest weapon that is not a tool (the wooden sword goes
     *       down, the diamond one takes its place; not an axe, which is for chopping, and
     *       the Miner only looks at the hotbar);</li>
     *   <li>an empty one;</li>
     *   <li>the most expendable: not a tool, food, bow, torch, nor what is in hand, and
     *       among those the smallest stack (a poppy before 64 cobblestone);</li>
     *   <li>if everything is valuable, the one in hand, just like {@code
     *       takeFromBackpack}.</li> </ol>
     */
    static int tileToClimb(LocalPlayer p) {
        var inv = p.getInventory();
        int loose = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (!isWeapon(stack) || stack.getItem() instanceof DiggerItem) continue;
            if (loose < 0 || betterThan(inv.getItem(loose), stack)) loose = i;
        }
        if (loose >= 0) return loose;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) return i;
        }
        int expendable = -1;
        for (int i = 0; i < 9; i++) {
            if (i == inv.selected) continue;
            ItemStack stack = inv.getItem(i);
            if (isValuable(stack)) continue;
            if (expendable < 0
                    || stack.getCount() < inv.getItem(expendable).getCount()) {
                expendable = i;
            }
        }
        return expendable >= 0 ? expendable : inv.selected;
    }

    /**
     * What is not moved down to the backpack to make room: tools, weapons, bows, food and
     * torches.
     */
    private static boolean isValuable(ItemStack stack) {
        var it = stack.getItem();
        return it instanceof DiggerItem || isWeapon(stack)
                || it instanceof ProjectileWeaponItem
                || stack.has(DataComponents.FOOD)
                || it == Items.TORCH;
    }

    /**
     * Leaves in hand whatever hits hardest (bringing it up from the backpack if needed).
     * Returns what ended up wielded.
     */
    static String wieldBest(Minecraft mc, LocalPlayer p) {
        p.getInventory().selected = prepareWeapon(mc, p);
        ItemStack stack = p.getMainHandItem();
        return stack.isEmpty() ? "my hands" : name(stack);
    }

    static String name(ItemStack stack) {
        return stack.isEmpty() ? ""
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    /** The damage of an item in the main hand. An empty hand hits 1. */
    static double damage(ItemStack stack) {
        return 1.0 + sum(stack, Attributes.ATTACK_DAMAGE);
    }

    /**
     * Hits per second: 4 base, and each weapon subtracts its own (sword 1.6; pickaxe 1.2;
     * diamond axe 1.0).
     */
    static double speed(ItemStack stack) {
        return Math.max(0.1, 4.0 + sum(stack, Attributes.ATTACK_SPEED));
    }

    private static double sum(ItemStack stack, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        double total = 0.0;
        if (stack.isEmpty()) return total;
        for (var entry : stack.getAttributeModifiers().modifiers()) {
            if (entry.attribute() != attribute) continue;
            // It only counts if the modifier applies to the main hand: some items give
            // damage only when worn in another slot.
            if (entry.slot() != EquipmentSlotGroup.MAINHAND
                    && entry.slot() != EquipmentSlotGroup.HAND
                    && entry.slot() != EquipmentSlotGroup.ANY) {
                continue;
            }
            total += entry.modifier().amount();
        }
        return total;
    }
}

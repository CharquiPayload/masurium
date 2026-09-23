package masurium.bot;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

import masurium.common.Logbook;
import masurium.common.Request;

/**
 * Putting armor on and taking it off.
 *
 * <p>Everything goes through the player's own menu ({@code inventoryMenu}), which is
 * always open and synced with the server: the same path as furnaces and chests, not a
 * local "setItem" the server would ignore. With the body slot empty a QUICK_MOVE is
 * enough (the menu routes the piece by itself); when occupied, it takes three PICKUPs:
 * lift the new piece, swap it with the worn one, and the old one lands in the gap the new
 * one left. No extra room needed.
 */
final class Armor {

    private Armor() {}

    /** From head to feet, so everything is listed the way it is worn. */
    private static final EquipmentSlot[] BODY = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET };

    private static String nameOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    /** Which body part this piece goes on, or null if it is not armor. */
    private static EquipmentSlot partOf(ItemStack stack) {
        return stack.getItem() instanceof ArmorItem a
                ? a.getEquipmentSlot() : null;
    }

    /** How much it protects: armor points decide, toughness breaks ties. */
    private static double protection(ItemStack stack) {
        if (!(stack.getItem() instanceof ArmorItem a)) return 0;
        return a.getDefense() + a.getToughness() / 10.0;
    }

    // The player's own menu numbers slots differently from the inventory: armor 5..8
    // (head..feet), backpack 9..35 as is, and the hotbar 0..8 becomes 36..44. The same
    // numbering trap as /wield, in another menu.
    private static int inMenu(int invSlot) {
        return invSlot < 9 ? 36 + invSlot : invSlot;
    }

    private static int bodyMenu(EquipmentSlot part) {
        return 8 - part.getIndex();
    }

    private static String otherMenuOpen(LocalPlayer p) {
        if (p.containerMenu != p.inventoryMenu) {
            return "{\"ok\":false,\"error\":\"I have another menu open (chest or "
                    + "furnace); wait until I am done with it\"}";
        }
        return null;
    }

    /** The worn pieces, from head to feet, as a JSON list. */
    static String placement(LocalPlayer p) {
        List<String> pieces = new ArrayList<>();
        for (EquipmentSlot part : BODY) {
            ItemStack stack = p.getInventory().armor.get(part.getIndex());
            if (!stack.isEmpty()) pieces.add("\"" + nameOf(stack) + "\"");
        }
        return "[" + String.join(",", pieces) + "]";
    }

    private static void dress(Minecraft mc, LocalPlayer p, int from,
                               EquipmentSlot part, boolean emptyBody) {
        int menu = p.inventoryMenu.containerId;
        if (emptyBody) {
            mc.gameMode.handleInventoryMouseClick(menu, inMenu(from), 0,
                    ClickType.QUICK_MOVE, p);
            return;
        }
        mc.gameMode.handleInventoryMouseClick(menu, inMenu(from), 0,
                ClickType.PICKUP, p);
        mc.gameMode.handleInventoryMouseClick(menu, bodyMenu(part), 0,
                ClickType.PICKUP, p);
        mc.gameMode.handleInventoryMouseClick(menu, inMenu(from), 0,
                ClickType.PICKUP, p);
    }

    /** Puts on the best it carries in the backpack, piece by piece. */
    static String best(Minecraft mc, LocalPlayer p) {
        String occupied = otherMenuOpen(p);
        if (occupied != null) return occupied;
        var inv = p.getInventory();
        List<String> changes = new ArrayList<>();
        for (EquipmentSlot part : BODY) {
            ItemStack placement = inv.armor.get(part.getIndex());
            double ceiling = protection(placement);
            int chosen = -1;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inv.getItem(i);
                if (partOf(stack) == part && protection(stack) > ceiling) {
                    ceiling = protection(stack);
                    chosen = i;
                }
            }
            if (chosen < 0) continue;
            String fresh = nameOf(inv.getItem(chosen));
            String before = placement.isEmpty() ? null : nameOf(placement);
            dress(mc, p, chosen, part, placement.isEmpty());
            changes.add("\"" + fresh
                    + (before == null ? "" : " (before " + before + ")") + "\"");
        }
        if (!changes.isEmpty()) {
            Logbook.note("armor", "I put on " + String.join(", ", changes));
        }
        return String.format(
                "{\"ok\":true,\"changes\":[%s],\"placed\":%s%s}",
                String.join(",", changes), placement(p),
                changes.isEmpty()
                        ? ",\"note\":\"I carry nothing better than what is worn\"" : "");
    }

    /**
     * Puts on a specific piece from the backpack, better or not: whoever asks decides.
     */
    static String putOn(Minecraft mc, LocalPlayer p, String id) {
        String occupied = otherMenuOpen(p);
        if (occupied != null) return occupied;
        var inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            EquipmentSlot part = partOf(stack);
            if (part == null || !nameOf(stack).equals(id)) continue;
            ItemStack placement = inv.armor.get(part.getIndex());
            if (!placement.isEmpty() && nameOf(placement).equals(id)) {
                return String.format(
                        "{\"ok\":true,\"note\":\"I am already wearing %s\","
                        + "\"placed\":%s}", id, placement(p));
            }
            dress(mc, p, i, part, placement.isEmpty());
            Logbook.note("armor", "I put on " + id);
            return String.format("{\"ok\":true,\"changes\":[\"%s\"],"
                    + "\"placed\":%s}", id, placement(p));
        }
        return String.format("{\"ok\":false,\"error\":\"I do not carry '%s' in the "
                + "backpack, or it is not an armor piece\"}",
                Request.escape(id));
    }

    /** Takes off a worn piece and stores it in the backpack. */
    static String takeOff(Minecraft mc, LocalPlayer p, String id) {
        String occupied = otherMenuOpen(p);
        if (occupied != null) return occupied;
        var inv = p.getInventory();
        for (EquipmentSlot part : BODY) {
            ItemStack placement = inv.armor.get(part.getIndex());
            if (placement.isEmpty() || !nameOf(placement).equals(id)) continue;
            if (inv.getFreeSlot() < 0) {
                return "{\"ok\":false,\"error\":\"my backpack is full, there is "
                        + "nowhere to store it\"}";
            }
            mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId,
                    bodyMenu(part), 0, ClickType.QUICK_MOVE, p);
            Logbook.note("armor", "I took off " + id);
            return String.format("{\"ok\":true,\"placed\":%s}", placement(p));
        }
        return String.format("{\"ok\":false,\"error\":\"I am not wearing "
                + "'%s'\"}", Request.escape(id));
    }
}

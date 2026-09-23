package masurium.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Placing blocks to reach places: bridges and towers.
 *
 * <p><b>Only used when the task asks for it.</b> A bot that can place and remove blocks
 * all the time is a bot that redecorates the house by accident (one broke a window just
 * by <i>moving</i>): the build permission is turned on for the task that needs it and off
 * when it ends.
 *
 * <p>It only places blocks from a short, cheap list. It never spends anything valuable
 * you carry to make a bridge.
 */
final class Builder {

    /** What may be spent on a bridge. Nothing valuable. */
    private static final Set<String> SCAFFOLD = Set.of(
            "dirt", "coarse_dirt", "rooted_dirt", "grass_block",
            "cobblestone", "cobbled_deepslate", "stone", "deepslate",
            "andesite", "diorite", "granite", "tuff", "calcite",
            "netherrack", "blackstone", "basalt", "sand", "gravel",
            "oak_planks", "spruce_planks", "birch_planks", "cherry_planks");

    private Builder() {}

    /**
     * Whether it carries scaffolding anywhere, hotbar or backpack. To decide BEFORE
     * planning whether a route can count on bridges and towers: planning them without
     * material ends in "I could not bridge" in front of the gap, again and again.
     */
    static boolean hasScaffold(LocalPlayer p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isScaffold(inv.getItem(i))) return true;
        }
        return false;
    }

    /**
     * Brings scaffolding up from the backpack to the hotbar and returns the slot, or -1
     * if there is none or it cannot (another menu open).
     */
    static int climbScaffold(LocalPlayer p) {
        if (p.containerMenu != p.inventoryMenu) return -1;
        var inv = p.getInventory();
        for (int i = 9; i < 36; i++) {
            if (!isScaffold(inv.getItem(i))) continue;
            int destination = inv.selected;
            for (int j = 0; j < 9; j++) {
                if (inv.getItem(j).isEmpty()) { destination = j; break; }
            }
            WeaponPicker.moveToHotbar(Minecraft.getInstance(), p, i, destination);
            return destination;
        }
        return -1;
    }

    /** The hotbar slot with scaffolding, or -1. */
    static int slotWithScaffold(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            if (isScaffold(p.getInventory().getItem(i))) return i;
        }
        // The backpack before giving up: a bot cut a branch one block away from four
        // diamonds saying "I carry no blocks to cover it" with 88 deepslate stored. It is
        // brought up to the hotbar, as the Miner does with pickaxes.
        for (int i = 9; i < p.getInventory().getContainerSize(); i++) {
            ItemStack stack = p.getInventory().getItem(i);
            if (!isScaffold(stack)) continue;
            int ascent = MasuriumBot.takeFromBackpack(p,
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
            if (ascent >= 0) return ascent;
        }
        return -1;
    }

    static boolean isScaffold(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return false;
        if (SCAFFOLD.contains(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath())) {
            return true;
        }
        // And by family, not only by name: the list above had four woods and the world
        // has more. A bot crafted 100 pale_oak_planks and `fill` rejected them as "not
        // cheap". The game's tags also pick up what mods add, if they tag it properly.
        if (stack.is(ItemTags.PLANKS) || stack.is(ItemTags.DIRT)) return true;
        var block = bi.getBlock().defaultBlockState();
        return block.is(BlockTags.BASE_STONE_OVERWORLD)
                || block.is(BlockTags.BASE_STONE_NETHER);
    }

    /**
     * Places a block at {@code where}, resting it on the neighbour given by {@code
     * fromFace}.
     * In Minecraft blocks are not placed "in the air": you click the face of an existing
     * block and the new one appears against that face. That is why the support is needed,
     * and why it has to be LOOKED AT: the server validates both the reach and the face
     * clicked, and without turning the head it rejects the placement silently.
     *
     * @return null if it worked, or the reason it did not
     */
    static String place(BlockPos where, Direction fromFace) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return "I am in no world";
        int slot = slotWithScaffold(p);
        if (slot < 0) slot = climbScaffold(p);   // what is in the backpack counts
        if (slot < 0) {
            return "I carry no blocks to build with "
                   + "(dirt, stone, cobblestone, planks...)";
        }
        return placeOf(where, fromFace, slot);
    }

    /**
     * The same, but placing what is in a specific slot.
     *
     * <p>It exists because {@link #place} picks the material itself from the scaffolding
     * list, which is fine for a bridge but not for "put the table there": a
     * crafting_table is not scaffolding (nor should it be; a table is not spent on a
     * bridge), so there was no way to place it. Here the caller decides the material.
     */
    static String placeOf(BlockPos where, Direction fromFace, int slot) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";

        // Including the slot change packet: switching the field and placing in the same
        // tick leaves the server believing we hold the previous item, and it places that.
        // See MasuriumBot.wieldNow.
        MasuriumBot.wieldNow(p, slot);

        BlockPos support = where.relative(fromFace);
        if (mc.level.getBlockState(support).isAir()) {
            return "there is nothing to rest the block on at " + support.toShortString();
        }
        // The exact point that is "touched": the center of the face looking at the gap.
        // The face is the opposite of the direction the support is in.
        Direction face = fromFace.getOpposite();
        Vec3 center = Vec3.atCenterOf(support).add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);

        p.lookAt(EntityAnchorArgument.Anchor.EYES, center);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(center, face, support, false));
        p.swing(InteractionHand.MAIN_HAND);
        // What it places is its own and it may remove it without permission: scaffolding,
        // jobs, blueprints. Without this only /place noted it, and the review of a house
        // could not fix a plank the bot itself had placed crooked ("I did not place it").
        var stack = p.getInventory().getItem(slot);
        if (!stack.isEmpty()) {
            PlacedBlocks.note(where, net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).getPath());
        }
        return null;
    }

    /** The horizontal direction of a one-tile jump. Null if it is not one. */
    static Direction towardWhere(int dx, int dz) {
        if (dx == 1 && dz == 0) return Direction.EAST;
        if (dx == -1 && dz == 0) return Direction.WEST;
        if (dx == 0 && dz == 1) return Direction.SOUTH;
        if (dx == 0 && dz == -1) return Direction.NORTH;
        return null;
    }
}

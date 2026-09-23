package masurium.bot;

import masurium.common.Logbook;
import masurium.common.Request;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Using chests (and barrels): put in, take out and look.
 *
 * <p>Twin brother of {@link FurnaceHandler}, with the same convictions: real clicks, a
 * real menu, and the contents are read from the open menu (data synced by the server),
 * never from the bot's own belief. A state machine in the tick, because opening a menu
 * takes a round trip to the server.
 *
 * <p>Using one notes it in the {@link Places} memory automatically, and if it is gone on
 * arrival it is deleted from there: both halves of the rule.
 */
final class ChestHandler {

    private static final double REACH = 4.0;
    private static final int MENU_WAIT = 60;

    private enum Job { NONE, PUT, TAKE, LOOK }

    private Job job = Job.NONE;
    private BlockPos chest;
    private String what;
    /** How many to move; <= 0 = everything there is, which is how it started. */
    private int quantity;
    private int ticks;
    private String outcome = "I have not used any chest yet";
    private boolean working;

    /** Starts a job. @return null if it is under way, or the reason. */
    synchronized String begin(String mode, BlockPos where, String what) {
        return begin(mode, where, what, 0);
    }

    /**
     * The same, but moving an exact quantity.
     *
     * <p>Without it, only "all or nothing" was possible: it moved whole stacks with the
     * quick click (the game's shift-click), and with 900 logs in a chest "take out 10"
     * was impossible.
     *
     * @param quantity 0 or less = everything there is, the old behaviour
     */
    synchronized String begin(String mode, BlockPos where, String what,
                                int quantity) {
        this.quantity = quantity;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (working) return "I am already at a chest; wait until I am done";

        var state = mc.level.getBlockState(where);
        String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (!block.contains("chest") && !block.equals("barrel")) {
            String extra = Places.forget(where)
                    ? " (I had it in my memory; I deleted it)" : "";
            return String.format("at %d %d %d there is %s, not a chest%s",
                    where.getX(), where.getY(), where.getZ(), block, extra);
        }
        if (Places.remember("chest", where)) {
            Logbook.note("places", String.format("I remember a chest at %d %d %d",
                    where.getX(), where.getY(), where.getZ()));
        }
        double d = p.getEyePosition().distanceTo(Vec3.atCenterOf(where));
        if (d > REACH) {
            return String.format("the chest is %.1f away and I reach %.1f; "
                    + "I have to get closer", d, REACH);
        }

        this.chest = where;
        this.what = what;
        this.job = switch (mode) {
            case "stop_flag" -> Job.PUT;
            case "take" -> Job.TAKE;
            default -> Job.LOOK;
        };
        this.ticks = 0;
        this.outcome = null;
        this.working = true;

        Vec3 center = Vec3.atCenterOf(where);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, center);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(center, Direction.UP, where, false));
        p.swing(InteractionHand.MAIN_HAND);
        return null;
    }

    synchronized boolean working() {
        return working;
    }

    synchronized void tick() {
        if (!working) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { finish("I left the world"); return; }

        if (!(p.containerMenu instanceof ChestMenu m)) {
            if (++ticks > MENU_WAIT) {
                finish("the chest did not open (covered by a block on top, "
                        + "or someone is using it?)");
            }
            return;
        }

        int ofTheChest = m.getRowCount() * 9;   // 27 single, 54 double
        switch (job) {
            case PUT -> {
                // From the player (slots >= ofTheChest) into the chest.
                int movedItems = move(mc, p, m, ofTheChest, m.slots.size(),
                        0, ofTheChest);
                close(mc, p, movedItems > 0
                        ? String.format("I put in %d %s; %s",
                                movedItems, what, contents(m, ofTheChest))
                        : String.format("I carry no %s to put in; %s",
                                what, contents(m, ofTheChest)));
            }
            case TAKE -> {
                // From the chest (slots < ofTheChest) to the player.
                int movedItems = move(mc, p, m, 0, ofTheChest,
                        ofTheChest, m.slots.size());
                close(mc, p, movedItems > 0
                        ? String.format("tome %d %s; %s",
                                movedItems, what, contents(m, ofTheChest))
                        : String.format("there is no %s in this chest; %s",
                                what, contents(m, ofTheChest)));
            }
            case LOOK -> close(mc, p, contents(m, ofTheChest));
            default -> close(mc, p, "I had no job");
        }
    }

    /**
     * Moves {@code what} from one area of the menu to the other with real mouse clicks,
     * and returns how many REALLY moved.
     *
     * <p>A whole stack goes with the quick click, the game's shift-click. For less than a
     * stack there is no shortcut: the stack is picked up with the left button, items are
     * dropped one by one with the right button (exactly what a player does) and the rest
     * goes back to its slot.
     *
     * <p>What moved is not counted by adding up intentions but by looking at the menu
     * before and after: a click may not land if the destination filled up, and saying
     * "took 10" when 3 went in would be the usual lie.
     */
    private int move(Minecraft mc, LocalPlayer p, ChestMenu m,
                      int fromStart, int fromEnd, int towardStart, int towardEnd) {
        int requests = quantity <= 0 ? Integer.MAX_VALUE : quantity;
        int before = counts(m, towardStart, towardEnd);
        for (int i = fromStart; i < fromEnd; i++) {
            int carried = counts(m, towardStart, towardEnd) - before;
            if (carried >= requests) break;
            ItemStack stack = m.slots.get(i).getItem();
            if (stack.isEmpty() || !id(stack).equals(what)) continue;
            int missingCount = requests - carried;
            if (missingCount >= stack.getCount()) {
                mc.gameMode.handleInventoryMouseClick(
                        m.containerId, i, 0, ClickType.QUICK_MOVE, p);
                continue;
            }
            int destination = gap(m, towardStart, towardEnd);
            if (destination < 0) break;                  // does not fit: how many will be said
            mc.gameMode.handleInventoryMouseClick(
                    m.containerId, i, 0, ClickType.PICKUP, p);
            for (int n = 0; n < missingCount; n++) {
                mc.gameMode.handleInventoryMouseClick(
                        m.containerId, destination, 1, ClickType.PICKUP, p);
            }
            mc.gameMode.handleInventoryMouseClick(
                    m.containerId, i, 0, ClickType.PICKUP, p);   // the rest, back to its slot
        }
        return counts(m, towardStart, towardEnd) - before;
    }

    /** How many {@code what} there are in that area of the menu. */
    private int counts(ChestMenu m, int ini, int end) {
        int total = 0;
        for (int i = ini; i < end; i++) {
            ItemStack stack = m.slots.get(i).getItem();
            if (!stack.isEmpty() && id(stack).equals(what)) total += stack.getCount();
        }
        return total;
    }

    /** A slot in the destination where it fits: empty, or holding the same with room. */
    private int gap(ChestMenu m, int ini, int end) {
        for (int i = ini; i < end; i++) {
            ItemStack stack = m.slots.get(i).getItem();
            if (stack.isEmpty()) return i;
            if (id(stack).equals(what)
                    && stack.getCount() < stack.getMaxStackSize()) {
                return i;
            }
        }
        return -1;
    }

    private void close(Minecraft mc, LocalPlayer p, String how) {
        Logbook.note("chest", String.format("%s (at %d %d %d)", how,
                chest.getX(), chest.getY(), chest.getZ()));
        finish(how);
        p.closeContainer();
    }

    /** What is inside, grouped by item: read from the menu, not from belief. */
    private static String contents(ChestMenu m, int ofTheChest) {
        Map<String, Integer> sum = new LinkedHashMap<>();
        for (int i = 0; i < ofTheChest; i++) {
            ItemStack stack = m.slots.get(i).getItem();
            if (!stack.isEmpty()) {
                sum.merge(id(stack), stack.getCount(), Integer::sum);
            }
        }
        if (sum.isEmpty()) return "the chest is empty";
        StringBuilder sb = new StringBuilder("inside: ");
        boolean firstItem = true;
        for (var e : sum.entrySet()) {
            if (!firstItem) sb.append(", ");
            firstItem = false;
            sb.append(e.getValue()).append(' ').append(e.getKey());
        }
        return sb.toString();
    }

    private static String id(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    private void finish(String how) {
        working = false;
        job = Job.NONE;
        outcome = how;
    }

    synchronized void stop(String because) {
        if (working) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.closeContainer();
        }
        finish(because);
    }

    synchronized String state() {
        if (!working) {
            return String.format("{\"with_chest\":false,\"outcome\":\"%s\"}",
                    Request.escape(outcome));
        }
        return String.format("{\"with_chest\":true,\"job\":\"%s\"}",
                job.name().toLowerCase());
    }
}
